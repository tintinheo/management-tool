"""Upload new local images to Postimages, then back up (copy or move) the originals."""
from __future__ import annotations

import asyncio
import json
import os
import shutil
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

from .config import Config
from .db import DB
from .scan import album_of, is_online_only, iter_images, make_thumb, probe, rel_path, sha256_file

GALLERY_THUMB_PX = 480


@dataclass
class SyncResult:
    uploaded: int = 0
    skipped: int = 0
    pending: int = 0        # new files found (dry run) or left over (limit reached)
    online_only: int = 0    # of the pending ones: still only in the cloud (OneDrive), not downloaded
    backed_up: int = 0
    failed: int = 0
    too_big: int = 0
    stopped: str | None = None
    errors: list[str] = field(default_factory=list)

    def summary(self) -> str:
        parts = [f"đã upload {self.uploaded}", f"bỏ qua {self.skipped}"]
        if self.backed_up:
            parts.append(f"đã backup {self.backed_up}")
        if self.pending:
            extra = f" ({self.online_only} còn trên OneDrive)" if self.online_only else ""
            parts.append(f"chưa sync {self.pending}{extra}")
        if self.too_big:
            parts.append(f"quá dung lượng {self.too_big}")
        if self.failed:
            parts.append(f"lỗi {self.failed}")
        s = ", ".join(parts)
        return f"{s} — dừng: {self.stopped}" if self.stopped else s


def journal(cfg: Config, record: dict) -> None:
    """Append-only backup of every upload/change; `rebuild` restores the index from it."""
    with open(cfg.journal_path, "a", encoding="utf-8") as f:
        f.write(json.dumps(record, ensure_ascii=False) + "\n")


def backup_original(src: Path, rel: str, sha: str, cfg: Config) -> tuple[Path, bool]:
    """Copy or move `src` to backup_dir/<rel>. Returns (backup path, whether anything changed).

    Never overwrites: a different file with the same name gets a " (n)" suffix.
    In move mode the source is deleted only after the copy is verified (sha256).
    """
    assert cfg.backup_dir is not None
    move = cfg.after_upload == "move"
    first = cfg.backup_dir / Path(*rel.split("/"))
    first.parent.mkdir(parents=True, exist_ok=True)
    dest, n = first, 1
    size = src.stat().st_size
    while dest.exists():
        # Same name already there: is it this very file (e.g. backup done, source delete failed)?
        if dest.stat().st_size == size and (not move or sha256_file(dest) == sha):
            if move:
                src.unlink()
            return dest, move
        dest = first.with_name(f"{first.stem} ({n}){first.suffix}")
        n += 1
    tmp = dest.with_name(dest.name + ".part")
    shutil.copy2(src, tmp)
    if move and sha256_file(tmp) != sha:
        tmp.unlink(missing_ok=True)
        raise OSError(f"bản sao trong backup không khớp với file gốc: {dest}")
    os.replace(tmp, dest)
    if move:
        src.unlink()
    return dest, True


async def _backup(db: DB, cfg: Config, row, p: Path, rel: str, sha: str, res: SyncResult, log) -> None:
    try:
        dest, changed = await asyncio.to_thread(backup_original, p, rel, sha, cfg)
    except Exception as e:
        res.failed += 1
        err = f"✗ backup {rel}: {type(e).__name__}: {e} (sẽ thử lại lần sync sau)"
        res.errors.append(err)
        log(err)
        return
    own = row["local_path"] == str(p)  # the image's own original, not a duplicate copy elsewhere
    if own and row["backup_path"] != str(dest):
        local = str(dest) if cfg.after_upload == "move" else str(p)
        db.set_paths(row["id"], local, str(dest))
        journal(cfg, db_row_dict(db, sha))
    if changed:
        res.backed_up += 1
        verb = "→ chuyển" if cfg.after_upload == "move" else "⧉ copy"
        log(f"{verb} {rel} → {dest}")


async def sync_folders(client, db: DB, cfg: Config, roots: list[Path] | None = None, *,
                       dry_run: bool = False, limit: int | None = None, settle_seconds: float = 5,
                       log: Callable[[str], None] = print) -> SyncResult:
    """Upload every image under roots whose content (sha256) isn't uploaded yet, then back it up.

    - OneDrive online-only files are downloaded (by reading them) before upload; a dry run
      doesn't download them, it only counts them.
    - Images deleted via the gallery stay deleted (not re-uploaded, not backed up).
    - Files modified within `settle_seconds` wait for the next run (may still be copying/syncing).
    - Stops when the monthly upload budget (cfg.monthly_limit) or `limit` (default: cfg.batch_limit)
      is reached.
    - A failed backup is retried on the next run (the file is still in the source folder).
    """
    res = SyncResult()
    roots = roots if roots is not None else cfg.folders
    if limit is None and cfg.batch_limit:
        limit = cfg.batch_limit  # applies to CLI, --watch rounds and the gallery's Sync button
    if limit == 0:  # explicit --limit 0 = no per-run limit
        limit = None
    max_bytes = cfg.max_file_mb * 1024 * 1024
    for root in roots:
        root = Path(root).resolve()
        if not root.is_dir():
            msg = f"Không thấy thư mục: {root}"
            log(msg)
            res.errors.append(msg)
            continue
        for p in iter_images(root, exclude=cfg.backup_dir):
            rel = rel_path(root, p)
            try:
                st = p.stat()
                if time.time() - st.st_mtime < settle_seconds:
                    continue
                sha = db.cached_hash(str(p), st.st_size, st.st_mtime_ns)
                if sha is None:
                    if is_online_only(st):
                        # Don't pull files down from OneDrive unless we can actually upload them now.
                        if not dry_run and not res.stopped:
                            if limit is not None and res.uploaded >= limit:
                                res.stopped = f"đủ --limit {limit}"
                            elif db.monthly_uploads() >= cfg.monthly_limit:
                                res.stopped = f"đã dùng hết {cfg.monthly_limit} lượt upload của tháng"
                                log(res.stopped)
                        if dry_run or res.stopped or st.st_size > max_bytes:
                            res.pending += 1
                            res.online_only += 1
                            if dry_run:
                                log(f"[mới, còn trên OneDrive] {rel}")
                            continue
                        log(f"⇣ tải về từ OneDrive: {rel}")
                    sha = await asyncio.to_thread(sha256_file, p)  # reading also downloads placeholders
                    db.remember_hash(str(p), st.st_size, st.st_mtime_ns, sha)
                row = db.image_by_sha(sha)
                if row is not None:
                    res.skipped += 1
                    if row["status"] == "ok" and cfg.backup_dir and not dry_run:
                        await _backup(db, cfg, row, p, rel, sha, res, log)
                    continue
                if st.st_size > max_bytes:
                    res.too_big += 1
                    log(f"[quá {cfg.max_file_mb} MB] {rel}")
                    continue
                if dry_run or res.stopped:
                    res.pending += 1
                    if dry_run:
                        log(f"[mới] {rel}")
                    continue
                if limit is not None and res.uploaded >= limit:
                    res.stopped = f"đủ --limit {limit}"
                    res.pending += 1
                    continue
                if db.monthly_uploads() >= cfg.monthly_limit:
                    res.stopped = f"đã dùng hết {cfg.monthly_limit} lượt upload của tháng"
                    res.pending += 1
                    log(res.stopped)
                    continue

                if res.uploaded and cfg.upload_delay:
                    await asyncio.sleep(cfg.upload_delay)
                gallery = cfg.gallery_for(rel)
                up = await client.upload(p, gallery)
                db.count_upload()
                w, h, taken = await asyncio.to_thread(probe, p)
                await asyncio.to_thread(make_thumb, p, cfg.data_dir / "thumbs" / f"{sha}.jpg", GALLERY_THUMB_PX)
                record = dict(sha256=sha, local_path=str(p), rel_path=rel, album=album_of(rel),
                              filename=p.name, size=st.st_size, width=w or up.width, height=h or up.height,
                              taken_at=taken, gallery=gallery, page_url=up.page, hotlink=up.hotlink,
                              thumb_url=up.thumbnail, delete_url=up.delete, edit_url=up.edit)
                db.upsert_image(**record)
                journal(cfg, db_row_dict(db, sha))
                res.uploaded += 1
                log(f"↑ {rel}  {up.page}")
                if cfg.backup_dir:
                    await _backup(db, cfg, db.image_by_sha(sha), p, rel, sha, res, log)
            except Exception as e:  # keep going; the file is retried next run
                res.failed += 1
                err = f"✗ {rel}: {type(e).__name__}: {e}"
                res.errors.append(err)
                log(err)
    return res


def db_row_dict(db: DB, sha: str) -> dict:
    row = db.image_by_sha(sha)
    return {k: row[k] for k in row.keys() if k != "id"}


def rebuild_from_journal(db: DB, cfg: Config) -> int:
    """Restore the index from uploads.jsonl (Postimages has no API to list your images)."""
    if not cfg.journal_path.exists():
        return 0
    seen = set()
    with open(cfg.journal_path, encoding="utf-8") as f:
        for line in f:  # replay in order: later records (e.g. deletions, moves) win
            line = line.strip()
            if line:
                rec = json.loads(line)
                db.upsert_image(**rec)
                seen.add(rec["sha256"])
    return len(seen)
