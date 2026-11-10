import json

import pytest
from aiohttp.test_utils import TestClient, TestServer

from conftest import FakeClient, make_img
from imgvault.db import DB
from imgvault.sync import sync_folders
from imgvault.web import Gallery


@pytest.mark.asyncio
async def test_gallery_endpoints(tmp_path, cfg):
    c = cfg()
    root = c.folders[0]
    for i, name in enumerate(["trip/Đà Lạt 1.jpg", "trip/b.jpg", "home/c.png"]):
        make_img(root / name, color=(i * 60, 10, 10))
    db = DB(tmp_path / "x.db")
    await sync_folders(FakeClient(), db, c, settle_seconds=0, log=lambda s: None)

    async with TestClient(TestServer(Gallery(FakeClient(), db, c).app())) as cl:
        assert (await cl.get("/")).status == 200
        albums = await (await cl.get("/api/albums")).json()
        assert albums == [{"album": "Photos/home", "count": 1}, {"album": "Photos/trip", "count": 2}]
        imgs = await (await cl.get("/api/images", params={"album": "Photos/trip"})).json()
        assert len(imgs) == 2
        t = next(i for i in imgs if i["filename"] == "Đà Lạt 1.jpg")
        assert t["has_local"] and t["hotlink"].startswith("https://i.postimg.cc/") and t["delete_url"]

        r = await cl.get(f"/thumb/{t['id']}")
        assert r.status == 200 and r.content_type == "image/jpeg"
        await r.read()

        r = await cl.get(f"/local/{t['id']}", params={"download": "1"})
        assert len(await r.read()) == (root / "trip/Đà Lạt 1.jpg").stat().st_size
        assert "UTF-8''" in r.headers["Content-Disposition"]

        # thumbnail missing locally -> redirect to Postimages thumbnail
        b = next(i for i in imgs if i["filename"] == "b.jpg")
        (c.data_dir / "thumbs" / f"{db.get_image(b['id'])['sha256']}.jpg").unlink()
        r = await cl.get(f"/thumb/{b['id']}", allow_redirects=False)
        assert r.status == 302 and "postimg.cc" in r.headers["Location"]

        assert (await cl.post(f"/api/images/{t['id']}/deleted")).status == 200
        assert (await cl.get(f"/local/{t['id']}")).status == 404
        assert len(await (await cl.get("/api/images")).json()) == 2
        last = json.loads(c.journal_path.read_text(encoding="utf-8").splitlines()[-1])
        assert last["status"] == "deleted" and last["filename"] == "Đà Lạt 1.jpg"

        st = await (await cl.get("/api/status")).json()
        assert st["counts"]["ok"] == 2 and st["month_used"] == 3 and st["running"] is False
        assert (await (await cl.post("/api/sync")).json())["started"] is True
