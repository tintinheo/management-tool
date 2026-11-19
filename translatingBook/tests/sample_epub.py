"""Builds a small but tricky EPUB 3 (with NCX) for tests."""

import zipfile

CONTAINER = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""

OPF = """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bookid">urn:uuid:1234</dc:identifier>
    <dc:title>The Quiet Harbour</dc:title>
    <dc:language>en</dc:language>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="c1" href="text/chapter%201.xhtml" media-type="application/xhtml+xml"/>
    <item id="img" href="images/a.png" media-type="image/png"/>
  </manifest>
  <spine toc="ncx"><itemref idref="c1"/></spine>
</package>"""

NAV = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>Nav</title></head>
<body><nav epub:type="toc"><h1>Contents</h1>
<ol><li><a href="text/chapter%201.xhtml">Chapter One</a>
  <ol><li><a href="text/chapter%201.xhtml#s2">The Letter</a></li></ol></li></ol>
</nav></body></html>"""

NCX = """<?xml version="1.0" encoding="utf-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
<head/><docTitle><text>The Quiet Harbour</text></docTitle>
<navMap><navPoint id="n1" playOrder="1"><navLabel><text>Chapter One</text></navLabel>
<content src="text/chapter%201.xhtml"/></navPoint></navMap></ncx>"""

CHAPTER = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="en">
<head><title>Chapter One</title></head>
<body>
<section>
<h2 id="c1">Chapter One</h2>
<p class="first">It was, she thought, <em>far too</em> quiet&nbsp;for a Tuesday.<a id="r1" href="#fn1" epub:type="noteref"><sup>1</sup></a></p>
<p>“Don’t,” he said. <a id="page5"/>“Just&#8212;don’t.”</p>
<p class="verse">The sea is calm tonight,<br/>The tide is full &amp; fair.</p>
<p>* * *</p>
<p><img src="../images/a.png" alt="harbour"/> The harbour at dawn.</p>
<ul><li>Bread</li><li>Salt <b>and</b> fish</li></ul>
<table><tr><td>Tide</td><td>High</td></tr></table>
<div><p id="s2">Inside a div.</p></div>
<pre>code stays</pre>
<aside id="fn1" epub:type="footnote"><p>1. A footnote.</p></aside>
</section>
</body></html>"""


def build(path: str) -> str:
    with zipfile.ZipFile(path, "w") as z:
        z.writestr(zipfile.ZipInfo("mimetype"), b"application/epub+zip", compress_type=zipfile.ZIP_STORED)
        z.writestr("META-INF/container.xml", CONTAINER)
        z.writestr("OEBPS/content.opf", OPF)
        z.writestr("OEBPS/nav.xhtml", NAV)
        z.writestr("OEBPS/toc.ncx", NCX)
        z.writestr("OEBPS/text/chapter 1.xhtml", CHAPTER)
        z.writestr("OEBPS/images/a.png", b"\x89PNG fake")
    return path
