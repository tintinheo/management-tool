import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from booktrans.config import Connection
from booktrans.llm import AuthError, ChatClient, QuotaExhausted, Router, Truncated


class FakeServer:
    """Scripted OpenAI-compatible server: each POST pops the next (status, body, headers) from a script."""

    def __init__(self):
        self.script: list = []
        self.bodies: list = []
        self.headers: list = []
        outer = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def _send(self, status, body, headers=None):
                data = json.dumps(body).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                for k, v in (headers or {}).items():
                    self.send_header(k, v)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def do_GET(self):
                self._send(200, {"data": [{"id": "models/gemini-x"}, {"id": "qwen"}]})

            def do_POST(self):
                n = int(self.headers.get("Content-Length", 0))
                outer.bodies.append(json.loads(self.rfile.read(n)))
                outer.headers.append(dict(self.headers))
                status, body, headers = outer.script.pop(0)
                self._send(status, body, headers)

        self.httpd = HTTPServer(("127.0.0.1", 0), H)
        self.url = f"http://127.0.0.1:{self.httpd.server_address[1]}/v1"
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def close(self):
        self.httpd.shutdown()


def ok(text, finish="stop"):
    return (200, {"model": "m", "choices": [{"message": {"content": text}, "finish_reason": finish}],
                  "usage": {"prompt_tokens": 10, "completion_tokens": 5}}, None)


@pytest.fixture
def srv():
    s = FakeServer()
    yield s
    s.close()


def conn(srv, name="a", key="k"):
    return Connection(name=name, preset="x", base_url=srv.url, model="m", api_key=key, rpm=0)


def test_chat_strips_think_and_sends_key(srv):
    srv.script = [ok("<think>hmm</think>\nXin chào")]
    c = ChatClient(conn(srv))
    r = c.chat("sys", "hi", temperature=0.2)
    assert r.text == "Xin chào" and r.prompt_tokens == 10
    assert srv.headers[0]["Authorization"] == "Bearer k"
    assert srv.bodies[0]["messages"][0] == {"role": "system", "content": "sys"}
    assert c.list_models() == ["gemini-x", "qwen"]


def test_retry_on_rate_limit_then_success(srv):
    srv.script = [(429, {"error": {"message": "Rate limit per minute"}}, {"Retry-After": "0"}),
                  (503, {"error": {"message": "overloaded"}}, None), ok("OK")]
    c = ChatClient(conn(srv), max_retries=3)
    import booktrans.llm as llm
    orig = llm._sleep
    llm._sleep = lambda s, stop: None
    try:
        assert c.chat("s", "u").text == "OK"
    finally:
        llm._sleep = orig
    assert len(srv.bodies) == 3


def test_daily_quota_falls_back_to_next_connection(srv):
    srv.script = [(429, {"error": {"message": "Quota exceeded for metric: generate_requests_per_day"}}, None),
                  ok("từ model dự phòng")]
    logs = []
    r = Router([conn(srv, "gemini"), conn(srv, "groq")], log=logs.append)
    res = r.chat("s", "u")
    assert res.text == "từ model dự phòng" and res.connection == "groq"
    assert any("hết quota" in m for m in logs)
    srv.script = [ok("again")]
    assert r.chat("s", "u").connection == "groq"  # exhausted one is skipped


def test_all_exhausted_and_auth_and_truncation(srv):
    srv.script = [(401, {"error": {"message": "bad key"}}, None)]
    with pytest.raises(AuthError):
        ChatClient(conn(srv)).chat("s", "u")
    srv.script = [(429, {"error": {"message": "daily limit reached, free-models-per-day"}}, None)]
    with pytest.raises(QuotaExhausted):
        Router([conn(srv)]).chat("s", "u")
    srv.script = [ok("cut", finish="length")]
    with pytest.raises(Truncated):
        ChatClient(conn(srv)).chat("s", "u")
