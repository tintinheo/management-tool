"""Orchestrates analysis → translation → editorial review over a Project."""

from __future__ import annotations

import re
import threading
from typing import Callable

from . import prompts
from .config import Settings
from .epub_io import has_letters, placeholders_in, plain_text
from .llm import LLMError, Router, Truncated
from .project import Project

_P_RE = re.compile(r"<p\s+id\s*=\s*[\"']?(\d+)[\"']?\s*>(.*?)</p\s*>", re.S | re.I)
_TERMS_RE = re.compile(r"<terms>(.*?)</terms>", re.S | re.I)
_GLOSSARY_RE = re.compile(r"<glossary>(.*?)</glossary>", re.S | re.I)
MAX_PARAS_PER_CHUNK = 40


def parse_paragraphs(text: str) -> dict[int, str]:
    out: dict[int, str] = {}
    for m in _P_RE.finditer(text):
        body = re.sub(r"\s*\n\s*", " ", m.group(2)).strip()
        if body:
            out[int(m.group(1))] = body
    return out


def parse_table(block: str) -> list[tuple[str, str, str]]:
    rows = []
    for line in block.splitlines():
        line = line.strip().strip("-*").strip()
        if "|" not in line:
            continue
        cells = [c.strip() for c in line.strip("|").split("|")]
        if len(cells) < 2 or not cells[0] or not cells[1]:
            continue
        if cells[0].lower() in ("english", "english term", "en", "tiếng anh") or set(cells[0]) <= set("-: "):
            continue
        rows.append((cells[0], cells[1], cells[2] if len(cells) > 2 else ""))
    return rows


def parse_terms(text: str) -> list[tuple[str, str, str]]:
    m = _TERMS_RE.search(text)
    return parse_table(m.group(1)) if m else []


def parse_analysis(text: str) -> tuple[str, list[tuple[str, str, str]]]:
    m = _GLOSSARY_RE.search(text)
    if not m:
        return text.strip(), []
    return (text[: m.start()] + text[m.end():]).strip(), parse_table(m.group(1))


def looks_untranslated(src: str, vi: str) -> bool:
    a, b = plain_text(src).strip(), plain_text(vi).strip()
    return len(a) > 60 and a == b


def chunk_units(units: list[dict], max_chars: int) -> list[list[dict]]:
    chunks: list[list[dict]] = []
    cur: list[dict] = []
    size = 0
    for u in units:
        ln = len(u["src"])
        if cur and (size + ln > max_chars or len(cur) >= MAX_PARAS_PER_CHUNK or u["href"] != cur[-1]["href"]):
            chunks.append(cur)
            cur, size = [], 0
        cur.append(u)
        size += ln
    if cur:
        chunks.append(cur)
    return chunks


class Pipeline:
    def __init__(self, project: Project, settings: Settings, *, log: Callable[[str], None] = print,
                 progress: Callable[[int, int], None] | None = None, on_unit: Callable[[str], None] | None = None,
                 stop: threading.Event | None = None, translator: Router | None = None,
                 reviewer: Router | None = None):
        self.p = project
        self.s = settings
        self.log = log
        self.progress = progress or (lambda done, total: None)
        self.on_unit = on_unit or (lambda uid: None)
        self.stop = stop or threading.Event()
        self._translator = translator
        self._reviewer = reviewer
        self._doc_titles = {d["href"]: d["title"] for d in project.docs()}

    # ----------------------------------------------------------------- routers
    @property
    def translator(self) -> Router:
        if self._translator is None:
            self._translator = Router(self.s.translators(), self.s.request_timeout, self.s.max_retries, self.log)
        return self._translator

    @property
    def reviewer(self) -> Router:
        if self._reviewer is None:
            conns = self.s.reviewers() or self.s.translators()
            self._reviewer = Router(conns, self.s.request_timeout, self.s.max_retries, self.log)
        return self._reviewer

    def close(self) -> None:
        for r in (self._translator, self._reviewer):
            if r is not None:
                r.close()

    # ------------------------------------------------------------------ prompts
    def _brief(self) -> tuple[str, str]:
        return self.p.get_meta("brief"), self.p.get_meta("custom_instructions")

    def _glossary_for(self, text: str) -> list[tuple[str, str, str]]:
        low = text.lower()
        out = []
        for g in self.p.glossary():
            en = g["en"].lower()
            if re.search(r"(?<!\w)" + re.escape(en) + r"(?!\w)", low):
                out.append((g["en"], g["vi"], g["note"] or ""))
        return out

    # ----------------------------------------------------------------- analysis
    def build_sample(self, max_chars: int) -> str:
        paras = [plain_text(u["src"]) for u in self.p.units()
                 if u["placement"] not in ("ncx", "title", "inline")]
        total = sum(len(x) + 1 for x in paras)
        if total <= max_chars:
            return "\n".join(paras)
        # 50% from the beginning, then 25% from the middle and 25% from the last third.
        parts = []
        for start_frac, share in ((0.0, 0.5), (0.45, 0.25), (0.75, 0.25)):
            i = int(len(paras) * start_frac)
            budget = int(max_chars * share)
            got = []
            while i < len(paras) and budget > 0:
                got.append(paras[i])
                budget -= len(paras[i]) + 1
                i += 1
            parts.append("\n".join(got))
        return "\n[…]\n".join(parts)

    def analyze(self) -> tuple[str, list[tuple[str, str, str]]]:
        sample = self.build_sample(self.s.analysis_sample_chars)
        title = self.p.get_meta("title")
        self.log(f"Đang phân tích tác phẩm ({len(sample):,} ký tự mẫu)…")
        res = self.translator.chat(prompts.ANALYSIS_SYSTEM, prompts.analysis_user(title, sample),
                                   temperature=0.3, stop=self.stop)
        brief, glossary = parse_analysis(res.text)
        self.log(f"✓ Phân tích xong bằng {res.connection} / {res.model}: {len(glossary)} thuật ngữ.")
        return brief, glossary

    # -------------------------------------------------------------- translation
    def translate_docs(self, hrefs: list[str], review: bool) -> None:
        todo = [u for h in hrefs for u in self.p.units(h, ("pending", "error"))]
        to_review = [u for h in hrefs for u in self.p.units(h, ("draft",))] if review else []
        total = len(todo) + len(to_review)
        done = 0
        self.progress(done, total)
        if not total:
            self.log("Không còn đoạn nào cần dịch trong phần đã chọn.")
            return
        self.log(f"Bắt đầu: {len(todo)} đoạn cần dịch" + (f", {len(to_review)} bản nháp cần biên tập" if to_review else ""))
        for chunk in chunk_units(to_review, self.s.chunk_chars):
            self._check_stop()
            self._review_chunk(chunk)
            done += len(chunk)
            self.progress(done, total)
        for chunk in chunk_units(todo, self.s.chunk_chars):
            self._check_stop()
            self._translate_chunk(chunk, review=review)
            done += len(chunk)
            self.progress(done, total)
        self.log(f"✓ Xong. Đã gửi {self.translator.requests} yêu cầu dịch"
                 + (f", {self._reviewer.requests} yêu cầu biên tập" if self._reviewer else "") + ".")

    def retranslate(self, uids: list[str], note: str, review: bool) -> None:
        units = [u for u in (self.p.unit(x) for x in uids) if u]
        units.sort(key=lambda u: (u["doc_order"], u["n"]))
        total = len(units)
        done = 0
        for chunk in chunk_units(units, self.s.chunk_chars):
            self._check_stop()
            self._translate_chunk(chunk, review=review, note=note)
            done += len(chunk)
            self.progress(done, total)

    def review_only(self, uids: list[str]) -> None:
        units = [u for u in (self.p.unit(x) for x in uids) if u and Project.text_of(u)]
        units.sort(key=lambda u: (u["doc_order"], u["n"]))
        for chunk in chunk_units(units, self.s.chunk_chars):
            self._check_stop()
            self._review_chunk(chunk)

    def _check_stop(self) -> None:
        if self.stop.is_set():
            from .llm import Stopped
            raise Stopped()

    def _translate_chunk(self, chunk: list[dict], review: bool, note: str = "", _depth: int = 0) -> None:
        brief, custom = self._brief()
        system = prompts.translator_system(brief, custom)
        first = chunk[0]
        chapter = self._doc_titles.get(first["href"], first["href"])
        srcs = [u["src"] for u in chunk]
        glossary = self._glossary_for(" ".join(plain_text(s) for s in srcs))
        context = self.p.previous_context(first["doc_order"], first["n"], self.s.context_paragraphs)
        user = prompts.translate_user(chapter, srcs, glossary, context, note)
        try:
            res = self.translator.chat(system, user, temperature=self.s.temperature, stop=self.stop)
        except Truncated:
            if len(chunk) == 1:
                self.p.set_status(first["uid"], "error", "Đoạn quá dài so với giới hạn đầu ra của model")
                self.log(f"✗ Đoạn {first['uid']} quá dài cho model.")
                return
            mid = len(chunk) // 2
            self.log("… câu trả lời bị cắt, chia nhỏ phần này để dịch lại.")
            self._translate_chunk(chunk[:mid], review, note, _depth + 1)
            self._translate_chunk(chunk[mid:], review, note, _depth + 1)
            return
        except LLMError as e:
            if len(chunk) > 1 and "quota" not in str(e).lower():
                mid = len(chunk) // 2
                self.log(f"… lỗi ({e}); thử lại với phần nhỏ hơn.")
                self._translate_chunk(chunk[:mid], review, note, _depth + 1)
                self._translate_chunk(chunk[mid:], review, note, _depth + 1)
                return
            raise

        got = parse_paragraphs(res.text)
        ok_units, ok_texts, missing = [], [], []
        for i, u in enumerate(chunk, 1):
            vi = got.get(i)
            if not vi or looks_untranslated(u["src"], vi):
                missing.append(u)
                continue
            lost = placeholders_in(u["src"]) - placeholders_in(vi)
            if lost:
                self.log(f"  (đoạn {u['uid']}: model bỏ mất định dạng {', '.join(sorted(lost))})")
            self.p.save_draft(u["uid"], vi, f"{res.connection}/{res.model}")
            self.on_unit(u["uid"])
            ok_units.append(u)
            ok_texts.append(vi)
        for en, vi, nt in parse_terms(res.text):
            if self.p.upsert_term(en, vi, nt, source="auto", overwrite=False):
                self.log(f"  + thuật ngữ mới: {en} → {vi}")

        if review and ok_units:
            self._review_chunk([dict(u, draft=t, final=None) for u, t in zip(ok_units, ok_texts)])

        if missing:
            if _depth < 2 and len(missing) < len(chunk):
                self.log(f"… {len(missing)} đoạn bị thiếu trong câu trả lời, dịch lại riêng.")
                self._translate_chunk(missing, review, note, _depth + 1)
            elif _depth < 1:
                self.log("… câu trả lời không đúng định dạng, thử lại một lần.")
                self._translate_chunk(missing, review, note, _depth + 1)
            else:
                for u in missing:
                    self.p.set_status(u["uid"], "error", "Model không trả về bản dịch cho đoạn này")
                    self.on_unit(u["uid"])
                self.log(f"✗ {len(missing)} đoạn chưa dịch được (đánh dấu lỗi, có thể dịch lại sau).")

    def _review_chunk(self, chunk: list[dict]) -> None:
        brief, custom = self._brief()
        system = prompts.reviewer_system(brief, custom)
        first = chunk[0]
        chapter = self._doc_titles.get(first["href"], first["href"])
        srcs = [u["src"] for u in chunk]
        drafts = [Project.text_of(u) for u in chunk]
        glossary = self._glossary_for(" ".join(plain_text(s) for s in srcs))
        context = self.p.previous_context(first["doc_order"], first["n"], self.s.context_paragraphs)
        user = prompts.review_user(chapter, srcs, drafts, glossary, context)
        try:
            res = self.reviewer.chat(system, user, temperature=min(self.s.temperature, 0.3), stop=self.stop)
        except Truncated:
            if len(chunk) > 1:
                mid = len(chunk) // 2
                self._review_chunk(chunk[:mid])
                self._review_chunk(chunk[mid:])
            return
        except LLMError as e:
            if "quota" in str(e).lower():
                raise
            self.log(f"⚠ Biên tập thất bại, giữ bản nháp: {e}")
            return
        got = parse_paragraphs(res.text)
        for i, u in enumerate(chunk, 1):
            vi = got.get(i)
            if vi and has_letters(plain_text(vi)) and not looks_untranslated(u["src"], vi):
                self.p.save_final(u["uid"], vi, f"{res.connection}/{res.model}")
                self.on_unit(u["uid"])
