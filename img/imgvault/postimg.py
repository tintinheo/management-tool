"""Client for the Postimages upload API (api key based).

Postimages publishes no API reference; the request shape below is the one used by the official
desktop uploader as documented by the community (ShareX issue #472, imgup crate). The `o`/`m`
tokens are fixed values of that uploader; two variants are known, so we try both and remember
the one that works.
"""
from __future__ import annotations

import asyncio
import base64
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

import aiohttp

UPLOAD_URL = "https://api.postimage.org/1/upload"
CLIENT_TOKENS = [
    ("2b819584285c102318568238c7d4a4c7", "59c2ad4b46b0c1e12d5703302bff0120"),
    ("2b819584285c102318568238c7d4a4c7", "fb733cccce28e7db3ff9f17d7ccff3d1"),
]
RETRY_STATUS = {429, 500, 502, 503, 504}


class UploadError(Exception):
    pass


@dataclass
class Uploaded:
    page: str
    hotlink: str
    thumbnail: str | None
    delete: str | None
    edit: str | None
    width: int | None
    height: int | None


def parse_response(body: str) -> Uploaded:
    try:
        root = ET.fromstring(body.strip())
    except ET.ParseError:
        raise UploadError(f"Phản hồi không phải XML: {body[:200]!r}") from None
    if root.get("success") not in (None, "1"):
        raise UploadError(f"Postimages báo lỗi (status={root.get('status')}): {body[:300]!r}")

    def text(path: str) -> str | None:
        el = root.find(path)
        return el.text.strip() if el is not None and el.text else None

    hotlink, page = text("links/hotlink"), text("links/page")
    if not hotlink or not page:
        raise UploadError(f"Thiếu link trong phản hồi: {body[:300]!r}")

    def num(path: str) -> int | None:
        v = text(path)
        return int(v) if v and v.isdigit() else None

    return Uploaded(page=page, hotlink=hotlink, thumbnail=text("links/thumbnail"),
                    delete=text("links/delete"), edit=text("links/edit"),
                    width=num("image/width"), height=num("image/height"))


class PostimagesClient:
    def __init__(self, api_key: str, session: aiohttp.ClientSession, token_index: int = 0,
                 retries: int = 3, backoff: float = 5.0):
        self.api_key = api_key
        self.session = session
        self.token_index = token_index
        self.retries = retries
        self.backoff = backoff

    async def _post(self, form: dict) -> str:
        for attempt in range(self.retries + 1):
            try:
                async with self.session.post(UPLOAD_URL, data=form,
                                             timeout=aiohttp.ClientTimeout(total=300)) as r:
                    body = await r.text()
                    if r.status in RETRY_STATUS and attempt < self.retries:
                        wait = float(r.headers.get("Retry-After") or self.backoff * 2 ** attempt)
                        await asyncio.sleep(wait)
                        continue
                    if r.status != 200:
                        raise UploadError(f"HTTP {r.status}: {body[:300]!r}")
                    return body
            except (aiohttp.ClientConnectionError, asyncio.TimeoutError):
                if attempt >= self.retries:
                    raise
                await asyncio.sleep(self.backoff * 2 ** attempt)
        raise UploadError("Hết số lần thử lại")

    async def upload(self, path: Path, gallery: str = "") -> Uploaded:
        if not self.api_key:
            raise UploadError("Thiếu api_key trong config.toml (lấy tại https://postimages.org/login/api).")
        data = await asyncio.to_thread(path.read_bytes)
        form = {
            "key": self.api_key,
            "gallery": gallery,
            "name": path.stem,
            "type": path.suffix.lstrip(".").lower(),
            "image": base64.b64encode(data).decode("ascii"),
            "version": "1.0.1",
            "portable": "1",
        }
        last: UploadError | None = None
        # Start with the token pair that worked last time, fall back to the others.
        order = [self.token_index] + [i for i in range(len(CLIENT_TOKENS)) if i != self.token_index]
        for i in order:
            form["o"], form["m"] = CLIENT_TOKENS[i]
            try:
                result = parse_response(await self._post(form))
                self.token_index = i
                return result
            except UploadError as e:
                last = e
        raise last or UploadError("Upload thất bại")
