"""Minimal OpenAI-compatible chat client with rate limiting, retries and model fallback.

Works with Google AI Studio (Gemini), OpenRouter, Groq, Ollama, LM Studio and any other
service exposing ``POST {base_url}/chat/completions``.
"""

from __future__ import annotations

import re
import threading
import time
from dataclasses import dataclass

import httpx

from .config import Connection


class LLMError(Exception):
    pass


class AuthError(LLMError):
    pass


class QuotaExhausted(LLMError):
    """Daily/total quota used up – switch to another model or continue later."""


class Truncated(LLMError):
    """The answer hit the output-token limit."""


class Stopped(Exception):
    """The user pressed Stop."""


_THINK_RE = re.compile(r"<think>.*?</think>", re.S | re.I)
_DAILY_HINTS = ("per day", "perday", "daily", "requests per day", "rpd", "free-models-per-day",
                "quota exceeded for metric", "exceeded your current quota", "credits")


def _sleep(seconds: float, stop: threading.Event | None) -> None:
    end = time.monotonic() + seconds
    while True:
        left = end - time.monotonic()
        if left <= 0:
            return
        if stop is not None and stop.wait(min(left, 0.5)):
            raise Stopped()
        if stop is None:
            time.sleep(min(left, 0.5))


def _retry_after(resp: httpx.Response) -> float | None:
    h = resp.headers.get("retry-after")
    if h:
        try:
            return float(h)
        except ValueError:
            pass
    m = re.search(r'"retryDelay"\s*:\s*"(\d+(?:\.\d+)?)s"', resp.text)  # Gemini
    if m:
        return float(m.group(1))
    m = re.search(r"try again in (?:(\d+)m)?(\d+(?:\.\d+)?)s", resp.text)  # Groq
    if m:
        return float(m.group(1) or 0) * 60 + float(m.group(2))
    return None


def _error_text(resp: httpx.Response) -> str:
    try:
        j = resp.json()
        err = j.get("error", j) if isinstance(j, dict) else j
        if isinstance(err, list) and err:
            err = err[0].get("error", err[0])
        if isinstance(err, dict):
            return str(err.get("message") or err)[:500]
        return str(err)[:500]
    except Exception:
        return resp.text[:500]


@dataclass
class ChatResult:
    text: str
    model: str
    connection: str
    prompt_tokens: int = 0
    completion_tokens: int = 0


class ChatClient:
    def __init__(self, conn: Connection, timeout: int = 300, max_retries: int = 6):
        self.conn = conn
        self.max_retries = max_retries
        self._lock = threading.Lock()
        self._next_slot = 0.0
        headers = {"Content-Type": "application/json"}
        if conn.api_key:
            headers["Authorization"] = f"Bearer {conn.api_key}"
        if "openrouter.ai" in conn.base_url:
            headers["X-Title"] = "BookTrans"
        self.http = httpx.Client(base_url=conn.base_url.rstrip("/") + "/", headers=headers, timeout=timeout)

    def close(self) -> None:
        self.http.close()

    def _wait_slot(self, stop: threading.Event | None) -> None:
        if self.conn.rpm <= 0:
            return
        interval = 60.0 / self.conn.rpm
        with self._lock:
            now = time.monotonic()
            start = max(now, self._next_slot)
            self._next_slot = start + interval
        _sleep(start - now, stop)

    def list_models(self) -> list[str]:
        r = self.http.get("models")
        if r.status_code in (401, 403):
            raise AuthError(_error_text(r))
        r.raise_for_status()
        data = r.json()
        items = data.get("data", data.get("models", [])) if isinstance(data, dict) else data
        ids = [m.get("id") or m.get("name", "") for m in items if isinstance(m, dict)]
        return sorted(i.removeprefix("models/") for i in ids if i)

    def chat(self, system: str, user: str, *, temperature: float = 0.4, max_tokens: int | None = None,
             stop: threading.Event | None = None) -> ChatResult:
        body: dict = {
            "model": self.conn.model,
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
            "temperature": temperature,
        }
        if max_tokens:
            body["max_tokens"] = max_tokens
        delay = 5.0
        last_err = ""
        for attempt in range(self.max_retries + 1):
            if stop is not None and stop.is_set():
                raise Stopped()
            self._wait_slot(stop)
            try:
                r = self.http.post("chat/completions", json=body)
            except (httpx.TimeoutException, httpx.TransportError) as e:
                last_err = f"Lỗi mạng: {e}"
                _sleep(delay, stop)
                delay = min(delay * 2, 120)
                continue
            if r.status_code == 200:
                return self._parse(r)
            msg = _error_text(r)
            last_err = f"HTTP {r.status_code}: {msg}"
            if r.status_code in (401, 403):
                raise AuthError(last_err)
            if r.status_code == 429:
                low = msg.lower()
                if any(h in low for h in _DAILY_HINTS) and "minute" not in low:
                    raise QuotaExhausted(last_err)
                wait = _retry_after(r) or delay
                if wait > 600:
                    raise QuotaExhausted(last_err)
                _sleep(wait + 1, stop)
                delay = min(delay * 2, 120)
                continue
            if r.status_code in (408, 409, 500, 502, 503, 504, 529):
                _sleep(delay, stop)
                delay = min(delay * 2, 120)
                continue
            raise LLMError(last_err)
        raise LLMError(f"Đã thử lại {self.max_retries} lần vẫn lỗi. {last_err}")

    def _parse(self, r: httpx.Response) -> ChatResult:
        data = r.json()
        choices = data.get("choices") or []
        if not choices:
            raise LLMError(f"Phản hồi rỗng: {str(data)[:300]}")
        ch = choices[0]
        content = (ch.get("message") or {}).get("content") or ""
        if isinstance(content, list):  # some providers return content parts
            content = "".join(p.get("text", "") for p in content if isinstance(p, dict))
        content = _THINK_RE.sub("", content).strip()
        if ch.get("finish_reason") == "length":
            raise Truncated("Câu trả lời bị cắt do giới hạn độ dài đầu ra")
        if ch.get("finish_reason") in ("content_filter", "safety") and not content:
            raise LLMError("Model từ chối (bộ lọc nội dung). Thử model khác cho đoạn này.")
        usage = data.get("usage") or {}
        return ChatResult(content, data.get("model") or self.conn.model, self.conn.name,
                          usage.get("prompt_tokens", 0) or 0, usage.get("completion_tokens", 0) or 0)


class Router:
    """Tries connections in priority order; moves on when one runs out of quota or keeps failing."""

    def __init__(self, conns: list[Connection], timeout: int = 300, max_retries: int = 6, log=None):
        if not conns:
            raise LLMError("Chưa có kết nối model nào được bật cho vai trò này (xem tab Cài đặt).")
        self.clients = [ChatClient(c, timeout, max_retries) for c in conns]
        self.exhausted: set[int] = set()
        self.log = log or (lambda msg: None)
        self.usage_in = 0
        self.usage_out = 0
        self.requests = 0

    def close(self) -> None:
        for c in self.clients:
            c.close()

    def chat(self, system: str, user: str, **kw) -> ChatResult:
        errors = []
        for i, client in enumerate(self.clients):
            if i in self.exhausted:
                continue
            try:
                res = client.chat(system, user, **kw)
                self.requests += 1
                self.usage_in += res.prompt_tokens
                self.usage_out += res.completion_tokens
                return res
            except (Stopped, Truncated):
                raise
            except QuotaExhausted as e:
                self.exhausted.add(i)
                self.log(f"⚠ {client.conn.name} ({client.conn.model}) hết quota: {e}. Chuyển model dự phòng…")
                errors.append(str(e))
            except AuthError as e:
                self.exhausted.add(i)
                self.log(f"⚠ {client.conn.name}: lỗi xác thực API key – {e}")
                errors.append(str(e))
            except LLMError as e:
                self.log(f"⚠ {client.conn.name} ({client.conn.model}) lỗi: {e}")
                errors.append(str(e))
        if len(self.exhausted) == len(self.clients):
            raise QuotaExhausted("Tất cả model đều đã hết quota hoặc lỗi key. Tiến độ đã lưu – "
                                 "hãy tiếp tục sau (quota thường reset mỗi ngày) hoặc thêm kết nối khác.")
        raise LLMError(" | ".join(errors) or "Không có model nào khả dụng")
