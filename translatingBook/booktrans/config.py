"""App settings: connections to (free) OpenAI-compatible model providers and translation options."""

from __future__ import annotations

import json
import os
import uuid
from dataclasses import asdict, dataclass, field
from pathlib import Path

# Model names on free tiers change often: these are only starting points.
# Use "Tải danh sách model" in the app to pick what the provider currently offers.
PRESETS: dict[str, dict] = {
    "Google Gemini (AI Studio – miễn phí)": {
        "base_url": "https://generativelanguage.googleapis.com/v1beta/openai",
        "model": "gemini-2.5-flash",
        "rpm": 8,
        "key_url": "https://aistudio.google.com/apikey",
        "needs_key": True,
    },
    "OpenRouter (model :free)": {
        "base_url": "https://openrouter.ai/api/v1",
        "model": "openai/gpt-oss-120b:free",
        "rpm": 15,
        "key_url": "https://openrouter.ai/keys",
        "needs_key": True,
    },
    "Groq (miễn phí)": {
        "base_url": "https://api.groq.com/openai/v1",
        "model": "openai/gpt-oss-120b",
        "rpm": 20,
        "key_url": "https://console.groq.com/keys",
        "needs_key": True,
    },
    "Ollama (chạy trên máy, offline)": {
        "base_url": "http://localhost:11434/v1",
        "model": "qwen3:14b",
        "rpm": 0,
        "key_url": "https://ollama.com/download",
        "needs_key": False,
    },
    "LM Studio (chạy trên máy)": {
        "base_url": "http://localhost:1234/v1",
        "model": "",
        "rpm": 0,
        "key_url": "https://lmstudio.ai",
        "needs_key": False,
    },
    "Tuỳ chỉnh (OpenAI-compatible)": {
        "base_url": "",
        "model": "",
        "rpm": 10,
        "key_url": "",
        "needs_key": False,
    },
}


@dataclass
class Connection:
    name: str
    preset: str
    base_url: str
    model: str
    api_key: str = ""
    rpm: int = 10  # requests per minute; 0 = unlimited
    enabled: bool = True
    translate: bool = True  # used for translating (in priority order)
    review: bool = True  # used for the editing pass
    id: str = field(default_factory=lambda: uuid.uuid4().hex[:8])

    @classmethod
    def from_preset(cls, preset: str) -> "Connection":
        p = PRESETS[preset]
        short = preset.split(" (")[0]
        return cls(name=short, preset=preset, base_url=p["base_url"], model=p["model"], rpm=p["rpm"])


@dataclass
class Settings:
    connections: list[Connection] = field(default_factory=list)
    chunk_chars: int = 3500  # source characters per request
    context_paragraphs: int = 4  # previous paragraphs (EN + VI) shown for continuity
    temperature: float = 0.4
    review_pass: bool = True
    analysis_sample_chars: int = 60000
    request_timeout: int = 300
    max_retries: int = 6
    vi_color: str = "#5b4636"
    last_epub: str = ""

    def translators(self) -> list[Connection]:
        return [c for c in self.connections if c.enabled and c.translate]

    def reviewers(self) -> list[Connection]:
        return [c for c in self.connections if c.enabled and c.review]


def config_dir() -> Path:
    base = os.environ.get("APPDATA") or str(Path.home() / ".config")
    d = Path(base) / "BookTrans"
    d.mkdir(parents=True, exist_ok=True)
    return d


def config_path() -> Path:
    return config_dir() / "settings.json"


def load_settings(path: Path | None = None) -> Settings:
    path = path or config_path()
    if not path.exists():
        s = Settings()
        s.connections.append(Connection.from_preset("Google Gemini (AI Studio – miễn phí)"))
        return s
    raw = json.loads(path.read_text(encoding="utf-8"))
    conns = [Connection(**c) for c in raw.pop("connections", [])]
    known = Settings.__dataclass_fields__.keys()
    return Settings(connections=conns, **{k: v for k, v in raw.items() if k in known})


def save_settings(s: Settings, path: Path | None = None) -> None:
    path = path or config_path()
    path.write_text(json.dumps(asdict(s), ensure_ascii=False, indent=2), encoding="utf-8")
