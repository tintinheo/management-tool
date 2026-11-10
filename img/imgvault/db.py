from __future__ import annotations

import sqlite3
from datetime import datetime
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT);
CREATE TABLE IF NOT EXISTS files(
    path TEXT PRIMARY KEY, size INTEGER, mtime_ns INTEGER, sha256 TEXT);
CREATE TABLE IF NOT EXISTS images(
    id INTEGER PRIMARY KEY,
    sha256 TEXT UNIQUE NOT NULL,
    local_path TEXT,
    rel_path TEXT,
    album TEXT,
    filename TEXT,
    size INTEGER,
    width INTEGER,
    height INTEGER,
    taken_at TEXT,
    uploaded_at TEXT,
    gallery TEXT,
    page_url TEXT,
    hotlink TEXT,
    thumb_url TEXT,
    delete_url TEXT,
    edit_url TEXT,
    backup_path TEXT,
    status TEXT NOT NULL DEFAULT 'ok');
CREATE INDEX IF NOT EXISTS ix_images_album ON images(album);
"""

IMAGE_FIELDS = ("sha256", "local_path", "rel_path", "album", "filename", "size", "width", "height",
                "taken_at", "uploaded_at", "gallery", "page_url", "hotlink", "thumb_url",
                "delete_url", "edit_url", "backup_path", "status")


def now() -> str:
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


class DB:
    def __init__(self, path: Path | str):
        self.conn = sqlite3.connect(str(path))
        self.conn.row_factory = sqlite3.Row
        self.conn.executescript(SCHEMA)
        cols = {r[1] for r in self.conn.execute("PRAGMA table_info(images)")}
        if "backup_path" not in cols:  # DBs created by v0.2.0
            self.conn.execute("ALTER TABLE images ADD COLUMN backup_path TEXT")
            self.conn.commit()

    def set_paths(self, image_id: int, local_path: str, backup_path: str | None) -> None:
        with self.conn:
            self.conn.execute("UPDATE images SET local_path=?, backup_path=? WHERE id=?",
                              (local_path, backup_path, image_id))

    def close(self) -> None:
        self.conn.close()

    # --- meta -------------------------------------------------------------
    def get_meta(self, key: str) -> str | None:
        row = self.conn.execute("SELECT value FROM meta WHERE key=?", (key,)).fetchone()
        return row[0] if row else None

    def set_meta(self, key: str, value: str) -> None:
        with self.conn:
            self.conn.execute(
                "INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                (key, value))

    def monthly_uploads(self) -> int:
        return int(self.get_meta("uploads_" + datetime.now().strftime("%Y-%m")) or 0)

    def count_upload(self) -> None:
        key = "uploads_" + datetime.now().strftime("%Y-%m")
        self.set_meta(key, str(int(self.get_meta(key) or 0) + 1))

    # --- local hash cache (avoid re-hashing unchanged files) ---------------
    def cached_hash(self, path: str, size: int, mtime_ns: int) -> str | None:
        row = self.conn.execute(
            "SELECT sha256 FROM files WHERE path=? AND size=? AND mtime_ns=?",
            (path, size, mtime_ns)).fetchone()
        return row[0] if row else None

    def remember_hash(self, path: str, size: int, mtime_ns: int, sha: str) -> None:
        with self.conn:
            self.conn.execute(
                "INSERT INTO files(path,size,mtime_ns,sha256) VALUES(?,?,?,?) "
                "ON CONFLICT(path) DO UPDATE SET size=excluded.size, mtime_ns=excluded.mtime_ns, sha256=excluded.sha256",
                (path, size, mtime_ns, sha))

    # --- images -----------------------------------------------------------
    def image_by_sha(self, sha: str) -> sqlite3.Row | None:
        return self.conn.execute("SELECT * FROM images WHERE sha256=?", (sha,)).fetchone()

    def get_image(self, image_id: int) -> sqlite3.Row | None:
        return self.conn.execute("SELECT * FROM images WHERE id=?", (image_id,)).fetchone()

    def upsert_image(self, **fields) -> int:
        fields.setdefault("status", "ok")
        fields.setdefault("uploaded_at", now())
        cols = [c for c in IMAGE_FIELDS if c in fields]
        updates = ", ".join(f"{c}=excluded.{c}" for c in cols if c != "sha256")
        with self.conn:
            self.conn.execute(
                f"INSERT INTO images({', '.join(cols)}) VALUES({', '.join('?' * len(cols))}) "
                f"ON CONFLICT(sha256) DO UPDATE SET {updates}",
                [fields[c] for c in cols])
        return self.image_by_sha(fields["sha256"])["id"]

    def mark_deleted(self, image_id: int) -> None:
        with self.conn:
            self.conn.execute("UPDATE images SET status='deleted' WHERE id=?", (image_id,))

    def list_images(self, album: str | None = None, q: str | None = None,
                    offset: int = 0, limit: int = 120) -> list[sqlite3.Row]:
        sql = "SELECT * FROM images WHERE status='ok'"
        args: list = []
        if album:
            sql += " AND (album=? OR album LIKE ?)"
            args += [album, album + "/%"]
        if q:
            sql += " AND rel_path LIKE ?"
            args.append(f"%{q}%")
        sql += " ORDER BY COALESCE(taken_at, uploaded_at) DESC, id DESC LIMIT ? OFFSET ?"
        args += [limit, offset]
        return self.conn.execute(sql, args).fetchall()

    def albums(self) -> list[tuple[str, int]]:
        rows = self.conn.execute(
            "SELECT album, COUNT(*) FROM images WHERE status='ok' GROUP BY album ORDER BY album").fetchall()
        return [(r[0], r[1]) for r in rows]

    def counts(self) -> dict[str, int]:
        rows = self.conn.execute(
            "SELECT status, COUNT(*), COALESCE(SUM(size),0) FROM images GROUP BY status").fetchall()
        out = {"ok": 0, "deleted": 0, "bytes": 0}
        for status, n, size in rows:
            out[status] = n
            if status == "ok":
                out["bytes"] = size
        return out
