from pathlib import Path
from types import SimpleNamespace

import pytest
from PIL import Image

from imgvault.config import Config
from imgvault.postimg import Uploaded


def make_img(path: Path, size=(800, 600), color=(200, 30, 30), exif_date=None) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    img = Image.new("RGB", size, color)
    if exif_date:
        exif = Image.Exif()
        exif.get_ifd(0x8769)[36867] = exif_date
        img.save(path, exif=exif)
    else:
        img.save(path)
    return path


class FakeClient:
    def __init__(self, fail=False):
        self.calls = []
        self.fail = fail

    async def upload(self, path, gallery=""):
        if self.fail:
            raise ConnectionError("offline")
        self.calls.append(SimpleNamespace(path=path, gallery=gallery))
        n = len(self.calls)
        return Uploaded(page=f"https://postimg.cc/P{n}", hotlink=f"https://i.postimg.cc/P{n}/{path.name}",
                        thumbnail=f"https://i.postimg.cc/P{n}/thumb.jpg",
                        delete=f"https://postimg.cc/delete/P{n}/abc", edit=None, width=None, height=None)


@pytest.fixture
def cfg(tmp_path):
    def make(**kw):
        kw.setdefault("folders", [tmp_path / "Photos"])
        kw.setdefault("upload_delay", 0)
        c = Config(api_key="k", gallery="G0", data_dir=tmp_path / "data", **kw)
        c.data_dir.mkdir(parents=True, exist_ok=True)
        return c
    return make
