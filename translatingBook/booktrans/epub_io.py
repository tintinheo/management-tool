"""EPUB reading, translation-unit extraction, and bilingual / Vietnamese-only export.

A *unit* is one leaf block of text (a paragraph, heading, list item, table cell, TOC label…).
Its inline markup (italics, bold, links, footnote refs, line breaks…) is replaced by short
numbered placeholder tags such as ``<i1>…</i1>``, ``<a2>…</a2>`` or ``<x3/>`` so the model sees
clean text but can keep the formatting.  On export the same document is walked again with the
same deterministic algorithm, the placeholders are mapped back to the original elements, and the
translation is inserted next to (bilingual) or instead of (Vietnamese-only) the original.
"""

from __future__ import annotations

import html.entities
import posixpath
import re
import zipfile
from dataclasses import dataclass, field
from urllib.parse import unquote
from xml.sax.saxutils import escape as xml_escape

from lxml import etree

OPF_NS = "http://www.idpf.org/2007/opf"
DC_NS = "http://purl.org/dc/elements/1.1/"
NCX_NS = "http://www.daisy.org/z3986/2005/ncx/"
CONTAINER_NS = "urn:oasis:names:tc:opendocument:xmlns:container"
XML_LANG = "{http://www.w3.org/XML/1998/namespace}lang"

OPF_HREF = "__opf__"  # pseudo-document holding the book title unit

# Elements that can be a translation unit when they contain no nested block element.
UNIT_TAGS = {
    "p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "dt", "dd", "blockquote", "figcaption",
    "caption", "td", "th", "div", "section", "article", "aside", "header", "footer",
    "address", "center",
}
# Any of these inside an element means it is a container, not a leaf.
BLOCK_ALL = UNIT_TAGS | {
    "ol", "ul", "dl", "table", "tr", "tbody", "thead", "tfoot", "figure", "main", "nav",
    "hr", "pre", "body", "hgroup", "details", "summary", "form", "fieldset",
}
SKIP_TAGS = {"script", "style", "pre", "svg", "math", "head", "template", "noscript"}
INSIDE_TAGS = {"li", "dt", "dd", "td", "th", "caption"}  # translation goes inside the element
HEADING_TAGS = {"h1", "h2", "h3", "h4", "h5", "h6"}
VOID_TAGS = {"br", "img", "image", "wbr", "hr", "input", "col", "area", "source"}

# Short, model-friendly placeholder names: <i1> = italics, <b2> = bold, <a3> = link …
SHORT = {
    "i": "i", "em": "i", "cite": "i", "dfn": "i", "var": "i",
    "b": "b", "strong": "b",
    "a": "a", "sup": "sup", "sub": "sub", "span": "s", "small": "sm", "u": "u",
    "q": "q", "abbr": "abbr", "code": "code", "mark": "mark", "s": "del", "del": "del",
}
_PLACEHOLDER_RE = re.compile(r"^([a-z]+)(\d+)$")
_ANY_PLACEHOLDER_RE = re.compile(r"</?[a-z]+\d+\s*/?>")
_LETTER_RE = re.compile(r"[^\W\d_]")


class EpubError(Exception):
    pass


# --------------------------------------------------------------------------- helpers


def localname(el) -> str | None:
    if not isinstance(el.tag, str):
        return None
    return etree.QName(el).localname.lower()


def _ns(el) -> str | None:
    return etree.QName(el).namespace


def _qn(el, name: str) -> str:
    ns = _ns(el)
    return f"{{{ns}}}{name}" if ns else name


def norm_ws(s: str | None) -> str:
    return re.sub(r"\s+", " ", s or "")


def has_letters(s: str) -> bool:
    return bool(_LETTER_RE.search(s))


def plain_text(marked: str) -> str:
    """Strip placeholder tags and unescape XML entities."""
    s = _ANY_PLACEHOLDER_RE.sub("", marked)
    return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", '"').replace("&amp;", "&")


def placeholders_in(marked: str) -> set[str]:
    return set(re.findall(r"<([a-z]+\d+)\s*/?>", marked))


_XML_PREDEFINED = {"amp", "lt", "gt", "quot", "apos"}


def _fix_entities(data: bytes) -> bytes:
    """Replace HTML named entities (&nbsp; …) by numeric refs so a non-DTD XML parser accepts them."""

    def repl(m: re.Match) -> bytes:
        name = m.group(1).decode("ascii")
        if name in _XML_PREDEFINED:
            return m.group(0)
        cp = html.entities.name2codepoint.get(name)
        return f"&#{cp};".encode("ascii") if cp else m.group(0)

    return re.sub(rb"&([A-Za-z][A-Za-z0-9]*);", repl, data)


def parse_xml(data: bytes) -> etree._ElementTree:
    parser = etree.XMLParser(recover=True, resolve_entities=False, huge_tree=True, remove_blank_text=False)
    root = etree.fromstring(_fix_entities(data), parser)
    if root is None:
        raise EpubError("Không đọc được tài liệu XHTML")
    return root.getroottree()


def serialize(tree: etree._ElementTree) -> bytes:
    return etree.tostring(tree, xml_declaration=True, encoding="utf-8")


# ------------------------------------------------------------------ inline encoding


@dataclass
class Spec:
    tag: str
    attrib: dict
    void: bool


def encode_inline(el) -> tuple[str, dict[int, Spec]]:
    """Return the element's content as placeholder-marked text plus the placeholder spec."""
    spec: dict[int, Spec] = {}
    counter = [0]

    def esc(s: str | None) -> str:
        return xml_escape(norm_ws(s))

    def inner(node) -> str:
        out = [esc(node.text)]
        for c in node:
            if not isinstance(c.tag, str):  # comment / processing instruction
                out.append(esc(c.tail))
                continue
            counter[0] += 1
            n = counter[0]
            ln = localname(c) or "t"
            void = ln in VOID_TAGS or (len(c) == 0 and not (c.text or "").strip())
            spec[n] = Spec(c.tag, dict(c.attrib), void)
            if void:
                out.append(f"<x{n}/>")
            else:
                code = SHORT.get(ln, "t")
                out.append(f"<{code}{n}>{inner(c)}</{code}{n}>")
            out.append(esc(c.tail))
        return "".join(out)

    return inner(el).strip(), spec


def _parse_marked(s: str):
    try:
        return etree.fromstring(f"<r>{s}</r>")
    except etree.XMLSyntaxError:
        pass
    fixed = re.sub(r"&(?!(?:[a-zA-Z]+|#\d+|#x[0-9a-fA-F]+);)", "&amp;", s)
    fixed = re.sub(r"<(?!/?[a-z]+\d+\s*/?>)", "&lt;", fixed)
    try:
        return etree.fromstring(f"<r>{fixed}</r>")
    except etree.XMLSyntaxError:
        return None


def _append_text(d, s: str | None) -> None:
    if not s:
        return
    if len(d):
        d[-1].tail = (d[-1].tail or "") + s
    else:
        d.text = (d.text or "") + s


def fill_element(dst, marked: str, spec: dict[int, Spec], *, strip_ids: bool, used_ids: set[str]) -> None:
    """Append the translated, placeholder-marked text into ``dst``, restoring inline elements."""
    root = _parse_marked(marked)
    if root is None:
        _append_text(dst, norm_ws(plain_text(marked)))
        return
    emitted: set[int] = set()

    def make(d, n: int):
        sp = spec[n]
        attrib = dict(sp.attrib)
        if "id" in attrib:
            if strip_ids or attrib["id"] in used_ids:
                del attrib["id"]
            else:
                used_ids.add(attrib["id"])
        emitted.add(n)
        return etree.SubElement(d, sp.tag, attrib)

    def build(src, d) -> None:
        _append_text(d, src.text)
        for c in src:
            m = _PLACEHOLDER_RE.match(c.tag) if isinstance(c.tag, str) else None
            n = int(m.group(2)) if m else None
            if n in spec:
                new = make(d, n)
                if not spec[n].void:
                    build(c, new)
            else:  # unknown tag invented by the model: keep its text only
                _append_text(d, "".join(c.itertext()))
            _append_text(d, c.tail)

    build(root, dst)

    # Never lose images, and (when ids are kept) never lose link targets such as footnote anchors.
    for n, sp in spec.items():
        if n in emitted:
            continue
        ln = etree.QName(sp.tag).localname.lower()
        if ln in ("img", "image"):
            make(dst, n)
        elif not strip_ids and "id" in sp.attrib and sp.attrib["id"] not in used_ids:
            used_ids.add(sp.attrib["id"])
            anchor = etree.Element(sp.tag, {"id": sp.attrib["id"]})
            anchor.tail = dst.text
            dst.text = None
            dst.insert(0, anchor)


# ------------------------------------------------------------------ unit discovery


def _is_leaf(el) -> bool:
    for d in el.iterdescendants():
        if localname(d) in BLOCK_ALL:
            return False
    return True


def _placement(ln: str) -> str:
    if ln in HEADING_TAGS:
        return "heading"
    if ln in INSIDE_TAGS:
        return "inside"
    return "sibling"


def iter_unit_elements(root) -> list[tuple[object, str]]:
    """Deterministic list of (element, placement) for an XHTML document."""
    out: list[tuple[object, str]] = []
    body = None
    for el in root.iter():
        if localname(el) == "body":
            body = el
            break
    if body is None:
        return out

    def text_ok(el) -> bool:
        return has_letters("".join(el.itertext()))

    def walk(el, in_nav: bool) -> None:
        for c in el:
            ln = localname(c)
            if ln is None or ln in SKIP_TAGS:
                continue
            if ln == "nav":
                walk(c, True)
                continue
            if in_nav and ln == "li":
                for cc in c:
                    lcc = localname(cc)
                    if lcc in ("a", "span") and text_ok(cc):
                        out.append((cc, "inline"))
                    elif lcc in ("ol", "ul"):
                        walk(cc, True)
                continue
            if ln in UNIT_TAGS and _is_leaf(c):
                if text_ok(c):
                    out.append((c, _placement(ln)))
                continue
            walk(c, in_nav)

    walk(body, False)
    return out


def _ncx_text_elements(root) -> list:
    return [t for t in root.iter(f"{{{NCX_NS}}}text") if t.getparent() is not None
            and etree.QName(t.getparent()).localname == "navLabel"]


@dataclass
class Unit:
    href: str
    n: int
    placement: str
    src: str  # placeholder-marked source text

    @property
    def uid(self) -> str:
        return f"{self.href}#{self.n}"


@dataclass
class Doc:
    order: int
    href: str  # path inside the zip
    kind: str  # xhtml | ncx | opf
    title: str
    units: list[Unit] = field(default_factory=list)


@dataclass
class Book:
    path: str
    title: str
    language: str
    opf_path: str
    docs: list[Doc]

    @property
    def units(self) -> list[Unit]:
        return [u for d in self.docs for u in d.units]


def _first_heading(root) -> str:
    for el in root.iter():
        if localname(el) in ("h1", "h2", "h3"):
            t = norm_ws("".join(el.itertext())).strip()
            if t:
                return t[:80]
    return ""


def _read_container(z: zipfile.ZipFile) -> str:
    try:
        root = etree.fromstring(z.read("META-INF/container.xml"))
    except KeyError as e:
        raise EpubError("File không phải EPUB hợp lệ (thiếu META-INF/container.xml)") from e
    rf = root.find(f".//{{{CONTAINER_NS}}}rootfile")
    if rf is None:
        raise EpubError("container.xml không có rootfile")
    return rf.get("full-path")


def _check_drm(z: zipfile.ZipFile) -> None:
    if "META-INF/encryption.xml" not in z.namelist():
        return
    data = z.read("META-INF/encryption.xml").decode("utf-8", "replace")
    algos = set(re.findall(r'EncryptionMethod[^>]*Algorithm="([^"]+)"', data))
    font_obfuscation = {"http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC"}
    if algos - font_obfuscation:
        raise EpubError("EPUB này có DRM (nội dung bị mã hoá) nên không thể dịch.")


def _resolve(base_dir: str, href: str) -> str:
    return posixpath.normpath(posixpath.join(base_dir, unquote(href.split("#")[0])))


class _OpfInfo:
    def __init__(self, z: zipfile.ZipFile):
        self.opf_path = _read_container(z)
        self.base = posixpath.dirname(self.opf_path)
        self.tree = parse_xml(z.read(self.opf_path))
        root = self.tree.getroot()
        self.manifest: dict[str, dict] = {}
        for item in root.iter(f"{{{OPF_NS}}}item"):
            self.manifest[item.get("id")] = {
                "href": _resolve(self.base, item.get("href", "")),
                "type": item.get("media-type", ""),
                "props": item.get("properties", "") or "",
            }
        spine = root.find(f"{{{OPF_NS}}}spine")
        self.spine: list[str] = []
        self.ncx: str | None = None
        if spine is not None:
            for ir in spine.iter(f"{{{OPF_NS}}}itemref"):
                it = self.manifest.get(ir.get("idref"))
                if it and it["type"] in ("application/xhtml+xml", "text/html"):
                    self.spine.append(it["href"])
            toc_id = spine.get("toc")
            if toc_id and toc_id in self.manifest:
                self.ncx = self.manifest[toc_id]["href"]
        if self.ncx is None:
            for it in self.manifest.values():
                if it["type"] == "application/x-dtbncx+xml":
                    self.ncx = it["href"]
        self.nav = next((it["href"] for it in self.manifest.values() if "nav" in it["props"].split()), None)
        t = root.find(f".//{{{DC_NS}}}title")
        self.title_el = t
        self.title = norm_ws(t.text).strip() if t is not None and t.text else ""
        lang = root.find(f".//{{{DC_NS}}}language")
        self.language = (lang.text or "").strip() if lang is not None else ""


def _doc_hrefs(info: _OpfInfo) -> list[tuple[str, str]]:
    hrefs = [(h, "xhtml") for h in info.spine]
    if info.nav and info.nav not in info.spine:
        hrefs.append((info.nav, "xhtml"))
    if info.ncx:
        hrefs.append((info.ncx, "ncx"))
    return hrefs


def load_book(path: str) -> Book:
    try:
        z = zipfile.ZipFile(path)
    except zipfile.BadZipFile as e:
        raise EpubError("File không phải EPUB hợp lệ (không giải nén được)") from e
    with z:
        _check_drm(z)
        info = _OpfInfo(z)
        names = set(z.namelist())
        docs: list[Doc] = []
        if info.title:
            d = Doc(0, OPF_HREF, "opf", "Tên sách")
            d.units.append(Unit(OPF_HREF, 0, "title", xml_escape(info.title)))
            docs.append(d)
        ncx_labels: dict[str, str] = {}
        for href, kind in _doc_hrefs(info):
            if href not in names:
                continue
            tree = parse_xml(z.read(href))
            root = tree.getroot()
            doc = Doc(len(docs), href, kind, "")
            if kind == "ncx":
                doc.title = "Mục lục (NCX)"
                for i, t in enumerate(_ncx_text_elements(root)):
                    txt = norm_ws(t.text).strip()
                    if has_letters(txt):
                        doc.units.append(Unit(href, i, "ncx", xml_escape(txt)))
                for np in root.iter(f"{{{NCX_NS}}}navPoint"):
                    t = np.find(f"{{{NCX_NS}}}navLabel/{{{NCX_NS}}}text")
                    c = np.find(f"{{{NCX_NS}}}content")
                    if t is not None and c is not None and t.text:
                        target = _resolve(posixpath.dirname(href), c.get("src", ""))
                        ncx_labels.setdefault(target, norm_ws(t.text).strip())
            else:
                for i, (el, placement) in enumerate(iter_unit_elements(root)):
                    marked, _ = encode_inline(el)
                    doc.units.append(Unit(href, i, placement, marked))
                doc.title = _first_heading(root)
                if href == info.nav:
                    doc.title = doc.title or "Mục lục"
            if doc.units:
                docs.append(doc)
        for d in docs:
            if d.kind == "xhtml" and d.href in ncx_labels:
                d.title = ncx_labels[d.href]
            if not d.title:
                d.title = posixpath.basename(d.href)
        return Book(path, info.title or posixpath.basename(path), info.language, info.opf_path, docs)


# ------------------------------------------------------------------------- export

BILINGUAL_CSS = """
.bt-vi { color: %(color)s; margin-top: 0.2em; margin-bottom: 1em; }
p.bt-heading { font-weight: bold; text-align: center; font-size: 1.15em; margin-bottom: 1.2em; }
li > .bt-vi, td > .bt-vi, th > .bt-vi, dd > .bt-vi, dt > .bt-vi { margin-bottom: 0.3em; }
"""


@dataclass
class ExportReport:
    translated: int = 0
    untranslated: int = 0
    mismatched: int = 0
    docs: int = 0


def _clear(el) -> None:
    el.text = None
    for c in list(el):
        el.remove(c)


def _add_class(el, cls: str) -> None:
    cur = el.get("class")
    el.set("class", f"{cur} {cls}" if cur else cls)


def _mark_vi(el) -> None:
    el.set("lang", "vi")
    el.set(XML_LANG, "vi")


def _apply_unit(el, placement: str, vi: str, spec: dict[int, Spec], mode: str, used_ids: set[str]) -> None:
    ln = localname(el)
    if mode == "vi":
        _clear(el)
        fill_element(el, vi, spec, strip_ids=False, used_ids=used_ids)
        _mark_vi(el)
        return
    # bilingual
    if placement == "inline":
        span = etree.SubElement(el, _qn(el, "span"), {"class": "bt-vi"})
        span.text = " — "
        fill_element(span, vi, spec, strip_ids=True, used_ids=used_ids)
        _mark_vi(span)
    elif placement == "inside":
        div = etree.SubElement(el, _qn(el, "div"), {"class": "bt-vi"})
        fill_element(div, vi, spec, strip_ids=True, used_ids=used_ids)
        _mark_vi(div)
    else:
        if placement == "heading":
            new = etree.Element(_qn(el, "p"), {"class": f"bt-vi bt-heading bt-{ln}"})
        else:
            new = etree.Element(el.tag, {k: v for k, v in el.attrib.items() if k != "id"})
            _add_class(new, "bt-vi")
        fill_element(new, vi, spec, strip_ids=True, used_ids=used_ids)
        _mark_vi(new)
        new.tail = el.tail
        el.addnext(new)


def _add_style(root, css: str) -> None:
    head = None
    for el in root.iter():
        if localname(el) == "head":
            head = el
            break
    if head is None:
        head = etree.Element(_qn(root, "head"))
        root.insert(0, head)
    style = etree.SubElement(head, _qn(root, "style"), {"type": "text/css"})
    style.text = css


def export_book(
    src_path: str,
    out_path: str,
    translations: dict[str, tuple[str, str]],
    mode: str = "bilingual",
    vi_color: str = "#5b4636",
) -> ExportReport:
    """Write a new EPUB.

    ``translations`` maps unit uid (``href#n``) to ``(source_marked, vietnamese_marked)``.
    ``mode`` is ``"bilingual"`` (original followed by Vietnamese) or ``"vi"`` (Vietnamese only).
    Units without a translation, or whose source no longer matches, keep the original text.
    """
    assert mode in ("bilingual", "vi")
    rep = ExportReport()
    css = BILINGUAL_CSS % {"color": vi_color}

    def lookup(href: str, n: int, src: str) -> str | None:
        t = translations.get(f"{href}#{n}")
        if not t or not t[1].strip():
            rep.untranslated += 1
            return None
        if t[0] != src:
            rep.mismatched += 1
            return None
        rep.translated += 1
        return t[1]

    with zipfile.ZipFile(src_path) as z:
        info = _OpfInfo(z)
        replaced: dict[str, bytes] = {}
        for href, kind in _doc_hrefs(info):
            if href not in z.namelist():
                continue
            tree = parse_xml(z.read(href))
            root = tree.getroot()
            if kind == "ncx":
                i = -1
                for i, t in enumerate(_ncx_text_elements(root)):
                    en = norm_ws(t.text).strip()
                    if not has_letters(en):
                        continue
                    vi = lookup(href, i, xml_escape(en))
                    if vi:
                        vi_plain = norm_ws(plain_text(vi)).strip()
                        t.text = vi_plain if mode == "vi" else f"{en} — {vi_plain}"
            else:
                units = iter_unit_elements(root)
                used_ids = {el.get("id") for el in root.iter() if isinstance(el.tag, str) and el.get("id")}
                if mode == "vi":
                    # Ids inside units are rebuilt from the translation; free them so they are re-emitted once.
                    for el, _ in units:
                        for d in el.iterdescendants():
                            if isinstance(d.tag, str) and d.get("id"):
                                used_ids.discard(d.get("id"))
                changed = False
                for n, (el, placement) in enumerate(units):
                    marked, spec = encode_inline(el)
                    vi = lookup(href, n, marked)
                    if vi:
                        _apply_unit(el, placement, vi, spec, mode, used_ids)
                        changed = True
                if mode == "vi":
                    root.set("lang", "vi")
                    root.set(XML_LANG, "vi")
                    changed = True
                if changed and mode == "bilingual":
                    _add_style(root, css)
            replaced[href] = serialize(tree)
            rep.docs += 1

        # OPF: title, language, identifier
        opf_root = info.tree.getroot()
        title_vi = lookup(OPF_HREF, 0, xml_escape(info.title)) if info.title_el is not None and info.title else None
        if title_vi:
            vi_title = norm_ws(plain_text(title_vi)).strip()
            info.title_el.text = vi_title if mode == "vi" else f"{info.title} — {vi_title} (Song ngữ Anh–Việt)"
        langs = list(opf_root.iter(f"{{{DC_NS}}}language"))
        if mode == "vi":
            for lang in langs:
                lang.text = "vi"
        elif langs and not any((x.text or "").strip().lower().startswith("vi") for x in langs):
            new = etree.Element(f"{{{DC_NS}}}language")
            new.text = "vi"
            new.tail = langs[-1].tail
            langs[-1].addnext(new)
        uid_name = opf_root.get("unique-identifier")
        for ident in opf_root.iter(f"{{{DC_NS}}}identifier"):
            if ident.get("id") == uid_name and ident.text:
                ident.text = ident.text.strip() + ("-vi" if mode == "vi" else "-en-vi")
        replaced[info.opf_path] = serialize(info.tree)

        with zipfile.ZipFile(out_path, "w") as out:
            out.writestr(zipfile.ZipInfo("mimetype"), b"application/epub+zip", compress_type=zipfile.ZIP_STORED)
            for item in z.infolist():
                if item.filename == "mimetype":
                    continue
                data = replaced.get(item.filename)
                if data is None:
                    data = z.read(item.filename)
                out.writestr(item.filename, data, compress_type=zipfile.ZIP_DEFLATED)
    return rep
