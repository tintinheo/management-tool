"""Translation project stored in SQLite next to the EPUB (``<book>.btproj.db``) – resumable."""

from __future__ import annotations

import sqlite3
import threading
import time
from pathlib import Path

from .epub_io import Book, load_book, plain_text

SCHEMA = """
CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT);
CREATE TABLE IF NOT EXISTS docs (
    href TEXT PRIMARY KEY, doc_order INTEGER, kind TEXT, title TEXT);
CREATE TABLE IF NOT EXISTS units (
    uid TEXT PRIMARY KEY, href TEXT, n INTEGER, doc_order INTEGER, placement TEXT,
    src TEXT, draft TEXT, final TEXT, status TEXT DEFAULT 'pending', model TEXT, note TEXT,
    updated REAL);
CREATE INDEX IF NOT EXISTS units_order ON units(doc_order, n);
CREATE TABLE IF NOT EXISTS glossary (
    en TEXT PRIMARY KEY COLLATE NOCASE, vi TEXT, note TEXT, source TEXT);
"""

TRANSLATED = ("draft", "reviewed", "edited")
STATUS_LABEL = {
    "pending": "chưa dịch", "draft": "bản nháp", "reviewed": "đã biên tập",
    "edited": "bạn đã sửa", "error": "lỗi",
}


def project_path_for(epub_path: str) -> Path:
    p = Path(epub_path)
    return p.with_name(p.stem + ".btproj.db")


class Project:
    def __init__(self, db_path: Path):
        self.db_path = Path(db_path)
        self._lock = threading.RLock()
        self.db = sqlite3.connect(str(self.db_path), check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.executescript(SCHEMA)
        self.db.commit()

    # ---------------------------------------------------------------- lifecycle
    @classmethod
    def open_for_epub(cls, epub_path: str) -> tuple["Project", Book]:
        book = load_book(epub_path)
        proj = cls(project_path_for(epub_path))
        proj.sync_book(book)
        return proj, book

    def sync_book(self, book: Book) -> None:
        with self._lock, self.db:
            self.set_meta("epub_path", book.path, commit=False)
            self.set_meta("title", book.title, commit=False)
            self.db.execute("DELETE FROM docs")
            for d in book.docs:
                self.db.execute("INSERT INTO docs VALUES (?,?,?,?)", (d.href, d.order, d.kind, d.title))
            existing = {r["uid"]: r["src"] for r in self.db.execute("SELECT uid, src FROM units")}
            seen = set()
            for d in book.docs:
                for u in d.units:
                    seen.add(u.uid)
                    if u.uid not in existing:
                        self.db.execute(
                            "INSERT INTO units(uid, href, n, doc_order, placement, src, status, updated)"
                            " VALUES (?,?,?,?,?,?,'pending',?)",
                            (u.uid, u.href, u.n, d.order, u.placement, u.src, time.time()))
                    elif existing[u.uid] != u.src:  # source changed: start this unit over
                        self.db.execute(
                            "UPDATE units SET src=?, draft=NULL, final=NULL, status='pending', doc_order=?,"
                            " placement=? WHERE uid=?", (u.src, d.order, u.placement, u.uid))
                    else:
                        self.db.execute("UPDATE units SET doc_order=? WHERE uid=?", (d.order, u.uid))
            for uid in set(existing) - seen:
                self.db.execute("DELETE FROM units WHERE uid=?", (uid,))

    def close(self) -> None:
        self.db.close()

    # --------------------------------------------------------------------- meta
    def get_meta(self, key: str, default: str = "") -> str:
        with self._lock:
            r = self.db.execute("SELECT value FROM meta WHERE key=?", (key,)).fetchone()
        return r["value"] if r else default

    def set_meta(self, key: str, value: str, commit: bool = True) -> None:
        with self._lock:
            self.db.execute("INSERT OR REPLACE INTO meta VALUES (?,?)", (key, value))
            if commit:
                self.db.commit()

    # --------------------------------------------------------------------- docs
    def docs(self) -> list[dict]:
        with self._lock:
            rows = self.db.execute(
                "SELECT d.href, d.doc_order, d.kind, d.title, COUNT(u.uid) AS total,"
                " SUM(CASE WHEN u.status IN ('draft','reviewed','edited') THEN 1 ELSE 0 END) AS done,"
                " SUM(CASE WHEN u.status='error' THEN 1 ELSE 0 END) AS errors"
                " FROM docs d LEFT JOIN units u ON u.href=d.href GROUP BY d.href ORDER BY d.doc_order").fetchall()
        return [dict(r) for r in rows]

    # -------------------------------------------------------------------- units
    def units(self, href: str | None = None, statuses: tuple[str, ...] | None = None) -> list[dict]:
        q = "SELECT * FROM units"
        cond, args = [], []
        if href is not None:
            cond.append("href=?")
            args.append(href)
        if statuses:
            cond.append(f"status IN ({','.join('?' * len(statuses))})")
            args.extend(statuses)
        if cond:
            q += " WHERE " + " AND ".join(cond)
        q += " ORDER BY doc_order, n"
        with self._lock:
            return [dict(r) for r in self.db.execute(q, args)]

    def unit(self, uid: str) -> dict | None:
        with self._lock:
            r = self.db.execute("SELECT * FROM units WHERE uid=?", (uid,)).fetchone()
        return dict(r) if r else None

    def save_draft(self, uid: str, text: str, model: str) -> None:
        with self._lock, self.db:
            self.db.execute("UPDATE units SET draft=?, final=NULL, status='draft', model=?, updated=? WHERE uid=?",
                            (text, model, time.time(), uid))

    def save_final(self, uid: str, text: str, model: str) -> None:
        with self._lock, self.db:
            self.db.execute("UPDATE units SET final=?, status='reviewed', model=model || ' + ' || ?, updated=?"
                            " WHERE uid=?", (text, model, time.time(), uid))

    def save_user_edit(self, uid: str, text: str) -> None:
        with self._lock, self.db:
            self.db.execute("UPDATE units SET final=?, status='edited', updated=? WHERE uid=?",
                            (text, time.time(), uid))

    def set_status(self, uid: str, status: str, note: str | None = None) -> None:
        with self._lock, self.db:
            self.db.execute("UPDATE units SET status=?, note=?, updated=? WHERE uid=?",
                            (status, note, time.time(), uid))

    def reset_units(self, uids: list[str]) -> None:
        with self._lock, self.db:
            for uid in uids:
                self.db.execute("UPDATE units SET draft=NULL, final=NULL, status='pending', note=NULL WHERE uid=?",
                                (uid,))

    @staticmethod
    def text_of(u: dict) -> str:
        return u.get("final") or u.get("draft") or ""

    def previous_context(self, doc_order: int, n: int, k: int) -> list[tuple[str, str]]:
        if k <= 0:
            return []
        with self._lock:
            rows = self.db.execute(
                "SELECT src, draft, final FROM units WHERE status IN ('draft','reviewed','edited')"
                " AND placement NOT IN ('ncx','title','inline')"
                " AND (doc_order < ? OR (doc_order = ? AND n < ?)) ORDER BY doc_order DESC, n DESC LIMIT ?",
                (doc_order, doc_order, n, k)).fetchall()
        return [(plain_text(r["src"]), plain_text(r["final"] or r["draft"])) for r in reversed(rows)]

    def translations(self) -> dict[str, tuple[str, str]]:
        with self._lock:
            rows = self.db.execute("SELECT uid, src, draft, final FROM units WHERE status IN"
                                   " ('draft','reviewed','edited')").fetchall()
        return {r["uid"]: (r["src"], r["final"] or r["draft"] or "") for r in rows}

    def stats(self) -> dict:
        with self._lock:
            rows = self.db.execute("SELECT status, COUNT(*) c, SUM(LENGTH(src)) chars FROM units GROUP BY status")
            out = {r["status"]: (r["c"], r["chars"] or 0) for r in rows}
        return out

    # ----------------------------------------------------------------- glossary
    def glossary(self) -> list[dict]:
        with self._lock:
            return [dict(r) for r in self.db.execute("SELECT * FROM glossary ORDER BY en COLLATE NOCASE")]

    def upsert_term(self, en: str, vi: str, note: str = "", source: str = "user", overwrite: bool = True) -> bool:
        en, vi = en.strip(), vi.strip()
        if not en or not vi:
            return False
        with self._lock, self.db:
            exists = self.db.execute("SELECT 1 FROM glossary WHERE en=?", (en,)).fetchone()
            if exists and not overwrite:
                return False
            self.db.execute("INSERT OR REPLACE INTO glossary VALUES (?,?,?,?)", (en, vi, note.strip(), source))
        return True

    def delete_term(self, en: str) -> None:
        with self._lock, self.db:
            self.db.execute("DELETE FROM glossary WHERE en=?", (en,))
