"""Local file helpers: discovery, hashing, metadata and thumbnails."""
from __future__ import annotations

import hashlib
import re
from pathlib import Path
from typing import Iterator

from PIL import Image, ImageOps

try:  # optional HEIC/HEIF support
    from pillow_heif import register_heif_opener

    register_heif_opener()
except ImportError:  # pragma: no cover
    pass

IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".tif", ".tiff",
              ".heic", ".heif", ".avif"}

# Windows "Files On-Demand" (OneDrive etc.): the file is a placeholder, reading it downloads it.
_FILE_ATTRIBUTE_OFFLINE = 0x1000
_FILE_ATTRIBUTE_RECALL_ON_OPEN = 0x40000
_FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS = 0x400000
_CLOUD_ATTRS = _FILE_ATTRIBUTE_OFFLINE | _FILE_ATTRIBUTE_RECALL_ON_OPEN | _FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS


def is_online_only(st) -> bool:
    return bool(getattr(st, "st_file_attributes", 0) & _CLOUD_ATTRS)


def iter_images(root: Path, exclude: Path | None = None) -> Iterator[Path]:
    """Yield image files under root, skipping hidden files/folders and `exclude` (e.g. the backup dir)."""
    for p in sorted(root.rglob("*")):
        rel_parts = p.relative_to(root).parts
        if any(part.startswith(".") for part in rel_parts):
            continue
        if exclude is not None and (p == exclude or exclude in p.parents):
            continue
        if p.is_file() and p.suffix.lower() in IMAGE_EXTS:
            yield p


def rel_path(root: Path, p: Path) -> str:
    """Path shown in the vault: '<root folder name>/<sub/dirs>/<file>'."""
    return f"{root.name}/{p.relative_to(root).as_posix()}"


def album_of(rel: str) -> str:
    return rel.rsplit("/", 1)[0] if "/" in rel else ""


def sha256_file(p: Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _exif_date(img: Image.Image) -> str | None:
    exif = img.getexif()
    raw = exif.get_ifd(0x8769).get(36867) or exif.get(306)  # DateTimeOriginal / DateTime
    if not raw or not isinstance(raw, str):
        return None
    raw = raw.strip().replace("\x00", "")
    m = re.match(r"(\d{4})[:-](\d{2})[:-](\d{2})[ T](\d{2}):(\d{2}):(\d{2})", raw)
    if not m or m.group(1) == "0000":
        return None
    y, mo, d, h, mi, s = m.groups()
    return f"{y}-{mo}-{d} {h}:{mi}:{s}"


def probe(p: Path) -> tuple[int | None, int | None, str | None]:
    """(width, height, taken_at) with EXIF orientation applied; Nones if unreadable."""
    try:
        with Image.open(p) as img:
            w, h = img.size
            if img.getexif().get(274) in (5, 6, 7, 8):
                w, h = h, w
            return w, h, _exif_date(img)
    except Exception:
        return None, None, None


def make_thumb(src: Path, dest: Path, size: int, max_bytes: int | None = None) -> bool:
    """Write a JPEG thumbnail (longest side <= size). Returns False if src can't be decoded."""
    try:
        with Image.open(src) as img:
            img.seek(0)
            img = ImageOps.exif_transpose(img)
            img.thumbnail((size, size))
            if img.mode not in ("RGB", "L"):
                bg = Image.new("RGB", img.size, (255, 255, 255))
                rgba = img.convert("RGBA")
                bg.paste(rgba, mask=rgba.split()[-1])
                img = bg
            dest.parent.mkdir(parents=True, exist_ok=True)
            quality = 82
            while True:
                img.save(dest, "JPEG", quality=quality, optimize=True)
                if max_bytes is None or dest.stat().st_size <= max_bytes or quality <= 40:
                    return True
                quality -= 10
    except Exception:
        dest.unlink(missing_ok=True)
        return False
