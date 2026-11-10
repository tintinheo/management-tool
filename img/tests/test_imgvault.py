import pytest
from PIL import Image

from conftest import FakeClient, make_img
from imgvault.db import DB
from imgvault.scan import album_of, make_thumb, probe, rel_path
from imgvault.sync import db_row_dict, journal, rebuild_from_journal, sync_folders

quiet = dict(settle_seconds=0, log=lambda s: None)


def test_paths(tmp_path):
    root = tmp_path / "Photos"
    p = root / "2024" / "trip" / "a.jpg"
    assert rel_path(root, p) == "Photos/2024/trip/a.jpg"
    assert album_of("Photos/2024/trip/a.jpg") == "Photos/2024/trip"
    assert album_of("a.jpg") == ""


def test_gallery_mapping(cfg):
    c = cfg(galleries={"Photos/2024": "G24", "Photos/2024/Tết": "GTET"})
    assert c.gallery_for("Photos/2024/Tết/a.jpg") == "GTET"
    assert c.gallery_for("Photos/2024/x/a.jpg") == "G24"
    assert c.gallery_for("Photos/20245/a.jpg") == "G0"   # prefix must end at a folder boundary


def test_probe_and_thumb(tmp_path):
    p = make_img(tmp_path / "a.jpg", size=(1200, 900), exif_date="2023:07:14 08:30:00")
    assert probe(p) == (1200, 900, "2023-07-14 08:30:00")
    t = tmp_path / "t.jpg"
    assert make_thumb(p, t, 320, 190_000)
    with Image.open(t) as im:
        assert max(im.size) == 320
    bad = tmp_path / "bad.jpg"
    bad.write_bytes(b"not an image")
    assert probe(bad) == (None, None, None)
    assert not make_thumb(bad, tmp_path / "t2.jpg", 320)


def test_png_with_alpha_thumb(tmp_path):
    p = tmp_path / "a.png"
    Image.new("RGBA", (500, 500), (0, 0, 0, 0)).save(p)
    assert make_thumb(p, tmp_path / "t.jpg", 200)


def test_db_listing(tmp_path):
    db = DB(tmp_path / "x.db")
    db.upsert_image(sha256="1" * 64, rel_path="P/a/x.jpg", album="P/a", filename="x.jpg", size=10)
    db.upsert_image(sha256="2" * 64, rel_path="P/a/b/y.jpg", album="P/a/b", filename="y.jpg", size=20)
    db.upsert_image(sha256="3" * 64, rel_path="P/ab/z.jpg", album="P/ab", filename="z.jpg", size=5)
    assert {r["filename"] for r in db.list_images(album="P/a")} == {"x.jpg", "y.jpg"}
    assert [r["filename"] for r in db.list_images(q="z.j")] == ["z.jpg"]
    db.mark_deleted(db.image_by_sha("1" * 64)["id"])
    assert db.counts() == {"ok": 2, "deleted": 1, "bytes": 25}
    assert db.albums() == [("P/a/b", 1), ("P/ab", 1)]


@pytest.mark.asyncio
async def test_sync_uploads_once_and_dedups(tmp_path, cfg):
    c = cfg(galleries={"Photos/copy": "GCOPY"})
    root = c.folders[0]
    make_img(root / "2024" / "a.jpg", color=(1, 2, 3))
    make_img(root / "2024" / "b.png", color=(4, 5, 6))
    make_img(root / "copy" / "a_dup.jpg", color=(1, 2, 3))   # same bytes as a.jpg
    make_img(root / "copy" / "d.jpg", color=(9, 9, 9))
    make_img(root / ".hidden" / "c.jpg", color=(7, 8, 9))
    (root / "notes.txt").write_text("x")
    db = DB(tmp_path / "x.db")
    client = FakeClient()

    dry = await sync_folders(None, db, c, dry_run=True, **quiet)
    assert dry.pending == 4 and dry.uploaded == 0

    res = await sync_folders(client, db, c, **quiet)
    assert (res.uploaded, res.skipped, res.failed) == (3, 1, 0)
    assert [x.gallery for x in client.calls] == ["G0", "G0", "GCOPY"]
    assert len(list((c.data_dir / "thumbs").glob("*.jpg"))) == 3
    row = db.list_images(q="b.png")[0]
    assert row["album"] == "Photos/2024" and row["width"] == 800
    assert row["hotlink"].endswith("/b.png") and row["delete_url"].startswith("https://postimg.cc/delete/")
    assert db.monthly_uploads() == 3

    res2 = await sync_folders(client, db, c, **quiet)
    assert (res2.uploaded, res2.skipped) == (0, 4)

    # deleted in the gallery -> not re-uploaded
    db.mark_deleted(row["id"])
    assert (await sync_folders(client, db, c, **quiet)).uploaded == 0


@pytest.mark.asyncio
async def test_limits(tmp_path, cfg):
    c = cfg(monthly_limit=2, max_file_mb=1)
    root = c.folders[0]
    for i in range(4):
        make_img(root / f"{i}.png", color=(i, i, i))
    big = root / "big.bmp"
    Image.effect_noise((800, 800), 64).convert("RGB").save(big)  # ~1.9 MB
    db = DB(tmp_path / "x.db")

    res = await sync_folders(FakeClient(), db, c, limit=1, **quiet)
    assert res.uploaded == 1 and res.pending == 3 and res.too_big == 1 and "limit" in res.stopped

    res = await sync_folders(FakeClient(), db, c, **quiet)
    assert res.uploaded == 1 and res.pending == 2 and "tháng" in res.stopped


@pytest.mark.asyncio
async def test_batch_limit_from_config_and_override(tmp_path, cfg):
    c = cfg(batch_limit=2)
    for i in range(5):
        make_img(c.folders[0] / f"{i}.png", color=(i, i, i))
    db = DB(tmp_path / "x.db")

    res = await sync_folders(FakeClient(), db, c, **quiet)          # config default: 2 per run
    assert res.uploaded == 2 and res.pending == 3 and "limit 2" in res.stopped
    res = await sync_folders(FakeClient(), db, c, limit=1, **quiet)  # --limit 1 overrides
    assert res.uploaded == 1 and res.pending == 2
    res = await sync_folders(FakeClient(), db, c, limit=0, **quiet)  # --limit 0 = no limit
    assert res.uploaded == 2 and res.pending == 0 and res.stopped is None


@pytest.mark.asyncio
async def test_sync_skips_files_still_being_written(tmp_path, cfg):
    c = cfg()
    make_img(c.folders[0] / "new.jpg")
    db = DB(tmp_path / "x.db")
    res = await sync_folders(FakeClient(), db, c, settle_seconds=60, log=lambda s: None)
    assert res.uploaded == 0 and res.skipped == 0


@pytest.mark.asyncio
async def test_failure_retried_next_run_and_journal_rebuild(tmp_path, cfg):
    c = cfg()
    make_img(c.folders[0] / "a.jpg")
    make_img(c.folders[0] / "b.jpg", color=(1, 1, 1))
    db = DB(tmp_path / "x.db")

    res = await sync_folders(FakeClient(fail=True), db, c, **quiet)
    assert res.failed == 2 and res.errors and db.monthly_uploads() == 0
    res = await sync_folders(FakeClient(), db, c, **quiet)
    assert res.uploaded == 2
    a = db.list_images(q="a.jpg")[0]
    db.mark_deleted(a["id"])
    journal(c, db_row_dict(db, a["sha256"]))   # what the gallery does on delete

    fresh = DB(tmp_path / "fresh.db")
    assert rebuild_from_journal(fresh, c) == 2
    assert [r["filename"] for r in fresh.list_images()] == ["b.jpg"]   # deletion replayed
    assert fresh.counts()["deleted"] == 1
