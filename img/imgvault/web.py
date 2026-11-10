"""Local gallery: aiohttp server on 127.0.0.1 backed by the SQLite index."""
from __future__ import annotations

import asyncio
from collections import deque
from pathlib import Path
from urllib.parse import quote

from aiohttp import web

from .config import Config
from .db import DB
from .sync import SyncResult, db_row_dict, journal, sync_folders

STATIC = Path(__file__).parent / "static"
FIELDS = ("id", "rel_path", "album", "filename", "size", "width", "height", "taken_at",
          "uploaded_at", "page_url", "hotlink", "thumb_url", "delete_url", "edit_url")


def local_file(r) -> Path | None:
    """The original on disk: where it was uploaded from, else its backup copy."""
    for p in (r["local_path"], r["backup_path"]):
        if p and Path(p).is_file():
            return Path(p)
    return None


def image_json(r) -> dict:
    d = {k: r[k] for k in FIELDS}
    d["has_local"] = local_file(r) is not None
    d["backup_path"] = r["backup_path"]
    return d


class Gallery:
    def __init__(self, client, db: DB, cfg: Config):
        self.client, self.db, self.cfg = client, db, cfg
        self.thumbs = cfg.data_dir / "thumbs"
        self.sync_task: asyncio.Task | None = None
        self.last_sync: SyncResult | None = None
        self.log: deque[str] = deque(maxlen=200)

    def app(self) -> web.Application:
        app = web.Application()
        app.add_routes([
            web.get("/", self.index),
            web.get("/api/albums", self.albums),
            web.get("/api/images", self.images),
            web.post("/api/images/{id}/deleted", self.mark_deleted),
            web.get("/api/status", self.status),
            web.post("/api/sync", self.start_sync),
            web.get("/thumb/{id}", self.thumb),
            web.get("/local/{id}", self.local),
        ])
        return app

    def _image(self, request):
        row = self.db.get_image(int(request.match_info["id"]))
        if row is None or row["status"] != "ok":
            raise web.HTTPNotFound()
        return row

    async def index(self, request):
        return web.FileResponse(STATIC / "index.html")

    async def albums(self, request):
        return web.json_response([{"album": a, "count": n} for a, n in self.db.albums()])

    async def images(self, request):
        q = request.query
        rows = self.db.list_images(album=q.get("album") or None, q=q.get("q") or None,
                                   offset=int(q.get("offset", 0)), limit=min(int(q.get("limit", 120)), 500))
        return web.json_response([image_json(r) for r in rows])

    async def thumb(self, request):
        row = self._image(request)
        f = self.thumbs / f"{row['sha256']}.jpg"
        if f.exists():
            return web.FileResponse(f, headers={"Cache-Control": "max-age=31536000, immutable"})
        if row["thumb_url"]:
            raise web.HTTPFound(row["thumb_url"])
        raise web.HTTPFound(row["hotlink"])

    async def local(self, request):
        """Original file from disk (Postimages free hotlinks are capped at 1280px)."""
        row = self._image(request)
        p = local_file(row)
        if p is None:
            raise web.HTTPNotFound(text="File gốc không còn trên máy.")
        headers = {}
        if request.query.get("download"):
            headers["Content-Disposition"] = f"attachment; filename*=UTF-8''{quote(row['filename'])}"
        return web.FileResponse(p, headers=headers)

    async def mark_deleted(self, request):
        """Called after the user confirmed deletion on Postimages' own delete page."""
        row = self._image(request)
        self.db.mark_deleted(row["id"])
        journal(self.cfg, db_row_dict(self.db, row["sha256"]))
        try:
            (self.thumbs / f"{row['sha256']}.jpg").unlink(missing_ok=True)
        except OSError:  # Windows: may still be open by a streaming response
            pass
        return web.json_response({"ok": True})

    async def status(self, request):
        last = self.last_sync
        return web.json_response({
            "running": bool(self.sync_task and not self.sync_task.done()),
            "counts": self.db.counts(),
            "month_used": self.db.monthly_uploads(),
            "month_limit": self.cfg.monthly_limit,
            "last": last.summary() if last else None,
            "log": list(self.log)[-30:],
        })

    async def start_sync(self, request):
        if self.sync_task and not self.sync_task.done():
            return web.json_response({"started": False, "reason": "Đang sync"})
        if not self.cfg.folders:
            return web.json_response({"started": False, "reason": "Chưa khai báo folders trong config.toml"})

        async def run():
            self.log.append("— Bắt đầu sync —")
            self.last_sync = await sync_folders(self.client, self.db, self.cfg, log=self.log.append)
            self.log.append(f"— Xong: {self.last_sync.summary()} —")
        self.sync_task = asyncio.create_task(run())
        return web.json_response({"started": True})


async def serve(client, db: DB, cfg: Config, open_browser: bool = True) -> None:
    runner = web.AppRunner(Gallery(client, db, cfg).app())
    await runner.setup()
    await web.TCPSite(runner, "127.0.0.1", cfg.port).start()
    url = f"http://127.0.0.1:{cfg.port}/"
    print(f"Gallery: {url}  (Ctrl+C để dừng)")
    if open_browser:
        import webbrowser
        webbrowser.open(url)
    try:
        await asyncio.Event().wait()
    finally:
        await runner.cleanup()
