from __future__ import annotations

import os
import tomllib
from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class Config:
    api_key: str
    gallery: str
    data_dir: Path
    folders: list[Path] = field(default_factory=list)
    # rel-path prefix ("Photos/2024") -> gallery id; longest prefix wins, else `gallery`
    galleries: dict[str, str] = field(default_factory=dict)
    port: int = 8765
    monthly_limit: int = 1000      # Free plan: 1,000 API units/month
    max_file_mb: int = 32          # Free plan: 32 MB/file
    upload_delay: float = 1.0      # seconds between uploads
    batch_limit: int = 0           # max uploads per sync run (0 = no limit); --limit overrides
    backup_dir: Path | None = None  # where originals go after a successful upload
    after_upload: str = "copy"     # "copy" (keep original) or "move" (take it out of the source folder)

    @property
    def db_path(self) -> Path:
        return self.data_dir / "imgvault.db"

    @property
    def journal_path(self) -> Path:
        return self.data_dir / "uploads.jsonl"

    def gallery_for(self, rel: str) -> str:
        best = ""
        for p in self.galleries:  # keys are stored without surrounding slashes
            if (rel == p or rel.startswith(p + "/")) and len(p) > len(best):
                best = p
        return self.galleries[best] if best else self.gallery


def load(path: Path) -> Config:
    if not path.is_file():
        raise SystemExit(
            f"Không tìm thấy {path}. Copy config.example.toml thành config.toml và điền api_key/gallery.")
    try:
        raw = tomllib.loads(path.read_text(encoding="utf-8"))
    except tomllib.TOMLDecodeError as e:
        raise SystemExit(
            f"Lỗi cú pháp trong {path}: {e}\n"
            "Mẹo: đường dẫn Windows hãy dùng ngoặc đơn 'C:\\Users\\...' hoặc dấu / như \"C:/Users/...\".")
    base = path.resolve().parent

    def resolve(p: str) -> Path:
        q = Path(p).expanduser()
        return q if q.is_absolute() else (base / q).resolve()

    api_key = os.environ.get("POSTIMAGE_API_KEY") or raw.get("api_key", "")
    gallery = os.environ.get("POSTIMAGE_GALLERY") or raw.get("gallery", "")
    cfg = Config(
        api_key=api_key,
        gallery=gallery,
        data_dir=resolve(raw.get("data_dir", "data")),
        folders=[resolve(f) for f in raw.get("folders", [])],
        galleries={k.strip("/"): v for k, v in raw.get("galleries", {}).items()},
        port=int(raw.get("port", 8765)),
        monthly_limit=int(raw.get("monthly_limit", 1000)),
        max_file_mb=int(raw.get("max_file_mb", 32)),
        upload_delay=float(raw.get("upload_delay", 1.0)),
        batch_limit=max(0, int(raw.get("batch_limit", 0))),
        backup_dir=resolve(raw["backup_dir"]) if raw.get("backup_dir") else None,
        after_upload=str(raw.get("after_upload", "copy")).lower(),
    )
    if cfg.after_upload not in ("copy", "move"):
        raise SystemExit('after_upload phải là "copy" hoặc "move".')
    # A backup folder *inside* a source folder is fine (the scanner skips it);
    # one that is or contains a source folder is not.
    for f in cfg.folders:
        if cfg.backup_dir and (cfg.backup_dir == f or cfg.backup_dir in f.parents):
            raise SystemExit(f"backup_dir ({cfg.backup_dir}) không được trùng/chứa thư mục nguồn {f}.")
    cfg.data_dir.mkdir(parents=True, exist_ok=True)
    return cfg
