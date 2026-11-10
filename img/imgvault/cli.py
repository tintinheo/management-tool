from __future__ import annotations

import argparse
import asyncio
import sys
from contextlib import asynccontextmanager
from pathlib import Path

import aiohttp

from . import config as config_mod
from .db import DB
from .postimg import PostimagesClient
from .sync import rebuild_from_journal, sync_folders

DEFAULT_CONFIG = Path(__file__).resolve().parent.parent / "config.toml"


def fmt_bytes(n: float) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024
    return str(n)


@asynccontextmanager
async def make_client(cfg, db):
    async with aiohttp.ClientSession(headers={"User-Agent": "imgvault/0.2"}) as session:
        client = PostimagesClient(cfg.api_key, session, token_index=int(db.get_meta("token_index") or 0))
        try:
            yield client
        finally:
            db.set_meta("token_index", str(client.token_index))


async def cmd_sync(cfg, db, args):
    roots = [Path(f).resolve() for f in args.folders] or cfg.folders
    if not roots:
        raise SystemExit("Chưa có thư mục nào: thêm 'folders' vào config.toml hoặc truyền đường dẫn.")
    if not cfg.gallery and not cfg.galleries:
        print("Cảnh báo: chưa đặt 'gallery' → ảnh sẽ không vào album nào trên Postimages.")
    if args.dry_run:
        res = await sync_folders(None, db, cfg, roots, dry_run=True, settle_seconds=0)
        print(res.summary())
        return
    if not cfg.api_key:
        raise SystemExit("Thiếu api_key trong config.toml (đăng nhập postimages.org rồi mở https://postimages.org/login/api).")
    async with make_client(cfg, db) as client:
        while True:
            res = await sync_folders(client, db, cfg, roots, limit=args.limit)
            print(res.summary())
            print(f"Lượt upload tháng này: {db.monthly_uploads()}/{cfg.monthly_limit}")
            if not args.watch:
                break
            await asyncio.sleep(args.watch)


async def cmd_rebuild(cfg, db, args):
    n = rebuild_from_journal(db, cfg)
    print(f"Đã khôi phục {n} ảnh từ {cfg.journal_path}.")


async def cmd_status(cfg, db, args):
    c = db.counts()
    print(f"Đã upload: {c['ok']} ảnh, {fmt_bytes(c['bytes'])}  (đã xoá: {c['deleted']})")
    print(f"Lượt upload tháng này: {db.monthly_uploads()}/{cfg.monthly_limit}")
    if cfg.folders:
        res = await sync_folders(None, db, cfg, dry_run=True, settle_seconds=0, log=lambda s: None)
        extra = []
        if res.online_only:
            extra.append(f"{res.online_only} chưa tải về từ OneDrive")
        if res.too_big:
            extra.append(f"quá dung lượng: {res.too_big}")
        print(f"Trên máy chưa sync: {res.pending}" + (f" ({', '.join(extra)})" if extra else ""))
    print(f"Mỗi lần sync: tối đa {cfg.batch_limit or 'không giới hạn'} ảnh")
    if cfg.backup_dir:
        print(f"Backup: {cfg.after_upload} → {cfg.backup_dir}")


async def cmd_serve(cfg, db, args):
    from .web import serve
    if args.port:
        cfg.port = args.port
    async with make_client(cfg, db) as client:
        await serve(client, db, cfg, open_browser=not args.no_browser)


def main(argv: list[str] | None = None) -> None:
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except AttributeError:
            pass

    ap = argparse.ArgumentParser(prog="imgvault", description="Sync ảnh lên Postimages + gallery quản lý.")
    ap.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("sync", help="Upload ảnh mới")
    p.add_argument("folders", nargs="*", help="Mặc định: 'folders' trong config.toml")
    p.add_argument("--dry-run", action="store_true", help="Chỉ liệt kê ảnh mới, không upload")
    p.add_argument("--limit", type=int,
                   help="Upload tối đa N ảnh rồi dừng (mặc định: batch_limit trong config; "
                        "--limit 1 để thử, --limit 0 = không giới hạn)")
    p.add_argument("--watch", type=int, metavar="GIÂY", help="Lặp lại sau mỗi N giây")
    sub.add_parser("rebuild", help="Khôi phục chỉ mục từ data/uploads.jsonl")
    sub.add_parser("status", help="Thống kê")
    p = sub.add_parser("serve", help="Mở gallery trên trình duyệt")
    p.add_argument("--port", type=int)
    p.add_argument("--no-browser", action="store_true")
    args = ap.parse_args(argv)

    cfg = config_mod.load(args.config)
    db = DB(cfg.db_path)
    handler = globals()[f"cmd_{args.cmd}"]
    try:
        asyncio.run(handler(cfg, db, args))
    except KeyboardInterrupt:
        pass
    finally:
        db.close()
