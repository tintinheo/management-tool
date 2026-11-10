import pytest

from conftest import FakeClient, make_img
from imgvault import config as config_mod
from imgvault import sync as sync_mod
from imgvault.db import DB
from imgvault.sync import sync_folders

quiet = dict(settle_seconds=0, log=lambda s: None)


@pytest.mark.asyncio
async def test_move_after_upload(tmp_path, cfg):
    c = cfg(backup_dir=tmp_path / "backup", after_upload="move")
    root = c.folders[0]
    a = make_img(root / "2024" / "Đà Lạt" / "a.jpg", color=(1, 2, 3))
    b = make_img(root / "b.png", color=(4, 5, 6))
    dup = make_img(root / "copy" / "a2.jpg", color=(1, 2, 3))  # same content as a.jpg
    db = DB(tmp_path / "x.db")

    res = await sync_folders(FakeClient(), db, c, **quiet)
    assert (res.uploaded, res.backed_up, res.failed) == (2, 3, 0)
    assert not a.exists() and not b.exists() and not dup.exists()
    moved = tmp_path / "backup" / "Photos" / "2024" / "Đà Lạt" / "a.jpg"
    assert moved.is_file() and (tmp_path / "backup" / "Photos" / "copy" / "a2.jpg").is_file()
    row = db.list_images(q="a.jpg")[0]
    assert row["local_path"] == str(moved) and row["backup_path"] == str(moved)

    # nothing left to do; a dry run sees nothing new
    res = await sync_folders(FakeClient(), db, c, **quiet)
    assert (res.uploaded, res.backed_up, res.skipped) == (0, 0, 0)


@pytest.mark.asyncio
async def test_copy_mode_keeps_source_and_is_idempotent(tmp_path, cfg):
    c = cfg(backup_dir=tmp_path / "backup", after_upload="copy")
    a = make_img(c.folders[0] / "a.jpg")
    db = DB(tmp_path / "x.db")
    res = await sync_folders(FakeClient(), db, c, **quiet)
    assert res.backed_up == 1 and a.exists() and (tmp_path / "backup/Photos/a.jpg").exists()
    row = db.list_images()[0]
    assert row["local_path"] == str(a) and row["backup_path"].endswith("a.jpg")
    res = await sync_folders(FakeClient(), db, c, **quiet)
    assert (res.uploaded, res.backed_up, res.skipped) == (0, 0, 1)


@pytest.mark.asyncio
async def test_name_collision_gets_suffix(tmp_path, cfg):
    c = cfg(backup_dir=tmp_path / "backup", after_upload="move")
    existing = make_img(tmp_path / "backup" / "Photos" / "a.jpg", color=(9, 9, 9))  # unrelated file
    make_img(c.folders[0] / "a.jpg", color=(1, 1, 1))
    db = DB(tmp_path / "x.db")
    await sync_folders(FakeClient(), db, c, **quiet)
    assert existing.read_bytes() != (tmp_path / "backup/Photos/a (1).jpg").read_bytes()
    assert db.list_images()[0]["local_path"].endswith("a (1).jpg")


@pytest.mark.asyncio
async def test_failed_backup_is_retried_without_reupload(tmp_path, cfg, monkeypatch):
    c = cfg(backup_dir=tmp_path / "backup", after_upload="move")
    a = make_img(c.folders[0] / "a.jpg")
    db = DB(tmp_path / "x.db")
    real = sync_mod.backup_original

    def boom(*args):
        raise PermissionError("file đang được OneDrive dùng")
    monkeypatch.setattr(sync_mod, "backup_original", boom)
    client = FakeClient()
    res = await sync_folders(client, db, c, **quiet)
    assert res.uploaded == 1 and res.failed == 1 and a.exists()

    monkeypatch.setattr(sync_mod, "backup_original", real)
    res = await sync_folders(client, db, c, **quiet)
    assert res.uploaded == 0 and res.backed_up == 1 and not a.exists()
    assert len(client.calls) == 1   # uploaded only once


@pytest.mark.asyncio
async def test_backup_dir_inside_source_is_not_scanned(tmp_path, cfg):
    c = cfg(after_upload="move")
    c.backup_dir = c.folders[0] / "_backup"
    make_img(c.folders[0] / "a.jpg")
    db = DB(tmp_path / "x.db")
    await sync_folders(FakeClient(), db, c, **quiet)
    res = await sync_folders(FakeClient(), db, c, **quiet)
    assert (res.uploaded, res.skipped, res.backed_up) == (0, 0, 0)


@pytest.mark.asyncio
async def test_online_only_files_not_downloaded_in_dry_run(tmp_path, cfg, monkeypatch):
    c = cfg()
    make_img(c.folders[0] / "cloud.jpg")
    db = DB(tmp_path / "x.db")
    monkeypatch.setattr(sync_mod, "is_online_only", lambda st: True)
    hashed = []
    real_hash = sync_mod.sha256_file
    monkeypatch.setattr(sync_mod, "sha256_file", lambda p: hashed.append(p) or real_hash(p))

    res = await sync_folders(None, db, c, dry_run=True, **quiet)
    assert (res.pending, res.online_only) == (1, 1) and not hashed

    logs = []
    res = await sync_folders(FakeClient(), db, c, settle_seconds=0, log=logs.append)
    assert res.uploaded == 1 and hashed and any("OneDrive" in m for m in logs)


@pytest.mark.asyncio
async def test_online_only_files_not_downloaded_once_budget_is_used(tmp_path, cfg, monkeypatch):
    c = cfg(monthly_limit=2)
    for i in range(5):
        make_img(c.folders[0] / f"{i}.jpg", color=(i, i, i))
    db = DB(tmp_path / "x.db")
    monkeypatch.setattr(sync_mod, "is_online_only", lambda st: True)
    hashed = []
    real_hash = sync_mod.sha256_file
    monkeypatch.setattr(sync_mod, "sha256_file", lambda p: hashed.append(p) or real_hash(p))

    res = await sync_folders(FakeClient(), db, c, limit=1, **quiet)
    assert res.uploaded == 1 and res.pending == 4 and len(hashed) == 1
    res = await sync_folders(FakeClient(), db, c, **quiet)
    assert res.uploaded == 1 and res.pending == 3 and "tháng" in res.stopped and len(hashed) == 2


def test_config_errors(tmp_path):
    p = tmp_path / "config.toml"
    p.write_text('api_key = "k"\nfolders = ["C:\\Users\\me"]\n', encoding="utf-8")
    with pytest.raises(SystemExit, match="ngoặc đơn"):
        config_mod.load(p)
    p.write_text(f"api_key = 'k'\nfolders = ['{tmp_path}\\src']\nbackup_dir = '{tmp_path}'\n", encoding="utf-8")
    with pytest.raises(SystemExit, match="backup_dir"):
        config_mod.load(p)
    p.write_text(f"api_key = 'k'\nfolders = ['{tmp_path}\\src']\nbackup_dir = 'D:\\backup'\n"
                 "after_upload = 'MOVE'\n", encoding="utf-8")
    c = config_mod.load(p)
    assert c.after_upload == "move" and str(c.backup_dir) == "D:\\backup"


def test_db_migration_adds_backup_column(tmp_path):
    import sqlite3
    conn = sqlite3.connect(tmp_path / "old.db")
    conn.execute("CREATE TABLE images(id INTEGER PRIMARY KEY, sha256 TEXT UNIQUE NOT NULL, local_path TEXT, "
                 "rel_path TEXT, album TEXT, filename TEXT, size INTEGER, width INTEGER, height INTEGER, "
                 "taken_at TEXT, uploaded_at TEXT, gallery TEXT, page_url TEXT, hotlink TEXT, thumb_url TEXT, "
                 "delete_url TEXT, edit_url TEXT, status TEXT NOT NULL DEFAULT 'ok')")
    conn.commit()
    conn.close()
    db = DB(tmp_path / "old.db")
    db.upsert_image(sha256="1" * 64, backup_path="D:/backup/x.jpg")
    assert db.image_by_sha("1" * 64)["backup_path"] == "D:/backup/x.jpg"
