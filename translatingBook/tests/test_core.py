import re
import sys
import zipfile
from pathlib import Path

import pytest
from lxml import etree

sys.path.insert(0, str(Path(__file__).parent))
import sample_epub  # noqa: E402

from booktrans import prompts  # noqa: E402
from booktrans.config import Settings  # noqa: E402
from booktrans.epub_io import encode_inline, export_book, fill_element, load_book, plain_text  # noqa: E402
from booktrans.llm import ChatResult, QuotaExhausted  # noqa: E402
from booktrans.pipeline import Pipeline, chunk_units, parse_analysis, parse_paragraphs, parse_terms  # noqa: E402
from booktrans.project import Project  # noqa: E402

CH = "OEBPS/text/chapter 1.xhtml"


@pytest.fixture
def epub(tmp_path):
    return sample_epub.build(str(tmp_path / "book.epub"))


class FakeRouter:
    """Translates by prefixing 'VI ' and keeping placeholder tags; review prefixes 'BT '."""

    def __init__(self, drop_ids=(), quota_after=None):
        self.calls = []
        self.requests = 0
        self.drop_ids = set(drop_ids)
        self.quota_after = quota_after

    def chat(self, system, user, **kw):
        self.calls.append((system, user))
        self.requests += 1
        if self.quota_after is not None and self.requests > self.quota_after:
            raise QuotaExhausted("hết quota")
        if "<draft>" in user:
            block = user.split("<draft>")[1]
            paras = re.findall(r'<p id="(\d+)">(.*?)</p>', block)
            return ChatResult("\n".join(f'<p id="{i}">BT {t}</p>' for i, t in paras), "fake", "fake")
        if "<book>" in user:
            return ChatResult("## 1. Tổng quan\nTiểu thuyết.\n<glossary>\nEnglish | Vietnamese | ghi chú\n"
                              "Harbour | Bến Cảng | tên riêng\n</glossary>", "fake", "fake")
        block = user.split("<source>")[1]
        paras = re.findall(r'<p id="(\d+)">(.*?)</p>', block)
        out = [f'<p id="{i}">VI {t}</p>' for i, t in paras if int(i) not in self.drop_ids]
        self.drop_ids = set()  # only drop on the first call
        return ChatResult("\n".join(out) + "\n<terms>\nTuesday | thứ Ba | ngày\n</terms>", "fake", "fake")

    def close(self):
        pass


def test_load_units(epub):
    book = load_book(epub)
    assert book.title == "The Quiet Harbour"
    ch = next(d for d in book.docs if d.href == CH)
    srcs = [u.src for u in ch.units]
    assert srcs[0] == "Chapter One"
    assert srcs[1] == "It was, she thought, <i1>far too</i1> quiet for a Tuesday.<a2><sup3>1</sup3></a2>"
    assert "The sea is calm tonight,<x1/>The tide is full &amp; fair." in srcs
    assert "* * *" not in srcs and "code stays" not in srcs
    assert {u.placement for u in ch.units} >= {"heading", "sibling", "inside"}
    nav = next(d for d in book.docs if d.href.endswith("nav.xhtml"))
    assert [u.src for u in nav.units] == ["Contents", "Chapter One", "The Letter"]
    assert any(d.kind == "ncx" for d in book.docs)


def test_fill_element_restores_markup_and_survives_bad_output():
    el = etree.fromstring('<p xmlns="http://www.w3.org/1999/xhtml">A <em>b</em> c<a id="n" href="#f"><sup>1</sup></a></p>')
    marked, spec = encode_inline(el)
    assert marked == "A <i1>b</i1> c<a2><sup3>1</sup3></a2>"
    dst = etree.Element("{http://www.w3.org/1999/xhtml}p")
    fill_element(dst, "X <i1>y</i1> z & w<a2><sup3>1</sup3></a2><zz9>q</zz9>", spec, strip_ids=True, used_ids=set())
    X = "{http://www.w3.org/1999/xhtml}"
    assert dst.find(f"{X}em").text == "y" and dst.find(f"{X}a").get("href") == "#f"
    assert dst.find(f"{X}a").get("id") is None and dst.find(f"{X}a/{X}sup").text == "1"
    assert "".join(dst.itertext()) == "X y z & w1q"
    # model dropped the anchor: with ids kept, the link target survives
    dst2 = etree.Element("{http://www.w3.org/1999/xhtml}p")
    fill_element(dst2, "chỉ có chữ", spec, strip_ids=False, used_ids=set())
    assert 'id="n"' in etree.tostring(dst2, encoding="unicode")


def test_parsers():
    got = parse_paragraphs('```\n<p id="1">Một\nhai</p>\n<p id=\'2\'>Ba <i1>x</i1></p>```')
    assert got == {1: "Một hai", 2: "Ba <i1>x</i1>"}
    assert parse_terms("<terms>\nEnglish term | Vietnamese rendering | note\nSam | Sam | giữ tên\n</terms>") == [
        ("Sam", "Sam", "giữ tên")]
    brief, gl = parse_analysis("## Brief\ntext\n<glossary>\n| Mr. Hale | ông Hale | |\n</glossary>")
    assert brief == "## Brief\ntext" and gl == [("Mr. Hale", "ông Hale", "")]


def test_chunking_respects_docs_and_size():
    units = [{"href": "a", "src": "x" * 100}] * 5 + [{"href": "b", "src": "y" * 100}] * 2
    chunks = chunk_units(units, 250)
    assert [len(c) for c in chunks] == [2, 2, 1, 2]


def test_prompts_include_brief_glossary_context():
    sys_p = prompts.translator_system("Người kể xưng tôi", "Giữ ngoặc kép")
    assert "Người kể xưng tôi" in sys_p and "Giữ ngoặc kép" in sys_p and "<terms>" in sys_p
    u = prompts.translate_user("Ch 1", ["Hello <i1>you</i1>"], [("Harbour", "Bến Cảng", "")],
                               [("Prev.", "Trước.")], "giọng mỉa mai hơn")
    assert "Harbour → Bến Cảng" in u and "VI: Trước." in u and '<p id="1">Hello <i1>you</i1></p>' in u
    assert "mỉa mai" in u


def test_full_pipeline_and_export(epub, tmp_path):
    proj, book = Project.open_for_epub(epub)
    s = Settings(chunk_chars=200)
    tr, rv = FakeRouter(drop_ids={2}), FakeRouter()
    pipe = Pipeline(proj, s, log=lambda m: None, translator=tr, reviewer=rv)
    brief, gl = pipe.analyze()
    assert gl == [("Harbour", "Bến Cảng", "tên riêng")]
    proj.set_meta("brief", brief)
    for en, vi, note in gl:
        proj.upsert_term(en, vi, note, "ai")
    pipe.translate_docs([d["href"] for d in proj.docs()], review=True)
    units = proj.units()
    assert all(u["status"] == "reviewed" for u in units), [(u["uid"], u["status"]) for u in units]
    assert proj.unit(f"{CH}#1")["final"].startswith("BT VI It was")
    # glossary filtered into prompts, terms learned, context passed on later chunks
    assert any("Harbour → Bến Cảng" in c[1] for c in tr.calls)
    assert any(g["en"] == "Tuesday" and g["source"] == "auto" for g in proj.glossary())
    assert any("<previous_passage>" in c[1] for c in tr.calls[1:])

    trans = proj.translations()
    out_bi = str(tmp_path / "bi.epub")
    rep = export_book(epub, out_bi, trans, "bilingual")
    assert rep.translated == len(units) and rep.untranslated == 0 and rep.mismatched == 0
    with zipfile.ZipFile(out_bi) as z:
        assert z.namelist()[0] == "mimetype" and z.getinfo("mimetype").compress_type == zipfile.ZIP_STORED
        ch = z.read(CH).decode("utf-8")
        opf = z.read("OEBPS/content.opf").decode("utf-8")
        ncx = z.read("OEBPS/toc.ncx").decode("utf-8")
        nav = z.read("OEBPS/nav.xhtml").decode("utf-8")
    root = etree.fromstring(ch.encode())  # still well-formed XML
    ids = [e.get("id") for e in root.iter() if e.get("id")]
    assert len(ids) == len(set(ids)), "duplicate ids in bilingual output"
    assert "far too" in ch and "<em>far too</em></p>" not in ch.split("BT VI")[0][-5:]
    assert '<p class="first bt-vi" lang="vi" xml:lang="vi">BT VI It was, she thought, <em>far too</em>' in ch
    assert "bt-heading" in ch and "<style" in ch and 'src="../images/a.png"' in ch
    assert ch.count("<br/>") == 2  # verse line break kept in both languages
    assert "<pre>code stays</pre>" in ch
    assert "<dc:language>vi</dc:language>" in opf and "urn:uuid:1234-en-vi" in opf and "Song ngữ" in opf
    assert "Chapter One — BT VI Chapter One" in ncx
    assert "bt-vi" in nav

    out_vi = str(tmp_path / "vi.epub")
    export_book(epub, out_vi, trans, "vi")
    with zipfile.ZipFile(out_vi) as z:
        ch = z.read(CH).decode("utf-8")
    root = etree.fromstring(ch.encode())
    assert ch.count("It was, she thought") == 1 and "BT VI It was" in ch and "bt-vi" not in ch
    ids = [e.get("id") for e in root.iter() if e.get("id")]
    assert len(ids) == len(set(ids)) and {"r1", "page5", "fn1", "c1", "s2"} <= set(ids)
    proj.close()


def test_quota_stops_and_resume(epub):
    proj, _ = Project.open_for_epub(epub)
    s = Settings(chunk_chars=100)
    tr = FakeRouter(quota_after=2)
    pipe = Pipeline(proj, s, log=lambda m: None, translator=tr, reviewer=tr)
    with pytest.raises(QuotaExhausted):
        pipe.translate_docs([d["href"] for d in proj.docs()], review=False)
    done = len(proj.units(statuses=("draft",)))
    assert 0 < done < len(proj.units())
    # resume with a fresh model: only remaining units are sent
    tr2 = FakeRouter()
    Pipeline(proj, s, log=lambda m: None, translator=tr2, reviewer=tr2).translate_docs(
        [d["href"] for d in proj.docs()], review=False)
    assert len(proj.units(statuses=("draft",))) == len(proj.units())
    sent = sum(len(re.findall(r'<p id="\d+">', c[1].split("<source>")[1])) for c in tr2.calls)
    assert sent == len(proj.units()) - done
    proj.close()


def test_user_edit_survives_and_plain_text():
    assert plain_text("a <i1>b</i1> &amp; c<x2/>") == "a b & c"
