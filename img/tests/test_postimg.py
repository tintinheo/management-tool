import base64

import aiohttp
import pytest
from aiohttp import web
from aiohttp.test_utils import TestServer

from conftest import make_img
from imgvault import postimg
from imgvault.postimg import CLIENT_TOKENS, PostimagesClient, UploadError, parse_response

OK_XML = """<?xml version="1.0" encoding="UTF-8"?>
<data success="1" status="200">
  <image><name>a</name><type>jpg</type><width>800</width><height>600</height><size>1234</size></image>
  <links>
    <page>https://postimg.cc/AbCd1234</page>
    <edit>https://postimg.cc/edit/AbCd1234</edit>
    <delete>https://postimg.cc/delete/AbCd1234/xyz</delete>
    <thumbnail>https://i.postimg.cc/AbCd1234/a.jpg?thumb</thumbnail>
    <hotlink>https://i.postimg.cc/AbCd1234/a.jpg</hotlink>
  </links>
</data>"""


def test_parse_ok():
    up = parse_response(OK_XML)
    assert up.page == "https://postimg.cc/AbCd1234"
    assert up.hotlink == "https://i.postimg.cc/AbCd1234/a.jpg"
    assert up.delete.endswith("/xyz") and (up.width, up.height) == (800, 600)


@pytest.mark.parametrize("body", ["<data success=\"0\" status=\"403\"/>", "Invalid API key", "<data/>"])
def test_parse_errors(body):
    with pytest.raises(UploadError):
        parse_response(body)


async def fake_server(handler):
    app = web.Application()
    app.router.add_post("/1/upload", handler)
    server = TestServer(app)
    await server.start_server()
    return server


@pytest.mark.asyncio
async def test_upload_sends_expected_form_and_falls_back_to_second_token(tmp_path, monkeypatch):
    seen = []

    async def handler(request):
        form = await request.post()
        seen.append(dict(form))
        if form["m"] == CLIENT_TOKENS[0][1]:
            return web.Response(text='<data success="0" status="400"/>')
        return web.Response(text=OK_XML)

    server = await fake_server(handler)
    monkeypatch.setattr(postimg, "UPLOAD_URL", str(server.make_url("/1/upload")))
    p = make_img(tmp_path / "Ảnh 1.JPG")
    try:
        async with aiohttp.ClientSession() as s:
            client = PostimagesClient("KEY", s)
            up = await client.upload(p, gallery="GAL")
            assert up.hotlink.endswith("a.jpg") and client.token_index == 1
            await client.upload(p, gallery="GAL")   # remembered: goes straight to the working pair
    finally:
        await server.close()
    assert len(seen) == 3
    f = seen[1]
    assert (f["key"], f["gallery"], f["name"], f["type"], f["version"], f["portable"]) == \
        ("KEY", "GAL", "Ảnh 1", "jpg", "1.0.1", "1")
    assert base64.b64decode(f["image"]) == p.read_bytes()
    assert seen[2]["m"] == CLIENT_TOKENS[1][1]


@pytest.mark.asyncio
async def test_upload_retries_on_429(tmp_path, monkeypatch):
    hits = []

    async def handler(request):
        await request.post()
        hits.append(1)
        if len(hits) < 3:
            return web.Response(status=429, headers={"Retry-After": "0"}, text="slow down")
        return web.Response(text=OK_XML)

    server = await fake_server(handler)
    monkeypatch.setattr(postimg, "UPLOAD_URL", str(server.make_url("/1/upload")))
    try:
        async with aiohttp.ClientSession() as s:
            up = await PostimagesClient("K", s, backoff=0).upload(make_img(tmp_path / "a.png"))
    finally:
        await server.close()
    assert up.page and len(hits) == 3


@pytest.mark.asyncio
async def test_upload_gives_up_on_http_error(tmp_path, monkeypatch):
    async def handler(request):
        return web.Response(status=403, text="forbidden")

    server = await fake_server(handler)
    monkeypatch.setattr(postimg, "UPLOAD_URL", str(server.make_url("/1/upload")))
    try:
        async with aiohttp.ClientSession() as s:
            with pytest.raises(UploadError, match="HTTP 403"):
                await PostimagesClient("K", s, backoff=0).upload(make_img(tmp_path / "a.png"))
    finally:
        await server.close()
