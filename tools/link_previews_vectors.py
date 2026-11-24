#!/usr/bin/env python3
"""Writes vectors/link-previews.json (SPEC 19.87; docs/link-previews-v2.md): link previews built by the sender.

    python3 tools/link_previews_vectors.py vectors/link-previews.json          # write
    python3 tools/link_previews_vectors.py vectors/link-previews.json --check  # compare, change nothing

Needs the `cryptography` package for the encrypted picture's AES-256-GCM (the only generator here that does).
The expected values of the html / read sections are written by hand below: they are the contract, and
core's LinkPreviewsVectorsTest runs every case against the Kotlin code.
"""
import hashlib
import json
import sys

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

DOMAIN = "belfry.example"
FILES = "https://files.other.example"
BLOSSOM = "https://blossom.belfry.example"


def sha(b):
    return hashlib.sha256(b).hexdigest()


def cut(s, n):
    return s if len(s) <= n else s[:n].rstrip()


LINKS = [
    ("one outside link", "This is what we could become: https://toolshare.example/stories/street-library", [], "https://toolshare.example/stories/street-library"),
    ("only the first", "https://a.example/one and https://b.example/two", [], "https://a.example/one"),
    ("http is not previewed", "old http://a.example/one", [], None),
    ("http is skipped, the https one after it counts", "http://a.example/one then https://b.example/two", [], "https://b.example/two"),
    ("the sentence's punctuation is not the link's", "Read https://a.example/page.", [], "https://a.example/page"),
    ("a bare host is not a link", "see a.example/page", [], None),
    ("the chat", "https://chat.belfry.example/#/c/garden-3f9a", [], None),
    ("the belfry itself", "https://belfry.belfry.example/skills/homepage.md", [], None),
    ("the community's own domain", "our page https://belfry.example/about", [], None),
    ("any name under the domain", "https://blossom.belfry.example/abc and https://wiki.belfry.example/x", [], None),
    ("a domain that only ends alike is outside", "https://notbelfry.example/x", [], "https://notbelfry.example/x"),
    ("the host's case does not matter", "https://Chat.Belfry.Example/#/c/x then https://a.example/", [], "https://a.example/"),
    ("a belfry link first, an outside one second", "https://chat.belfry.example/#/c/garden-3f9a see https://a.example/x", [], "https://a.example/x"),
    ("an attachment (imeta)", "https://cdn.example/f/abc", [["imeta", "url https://cdn.example/f/abc", "m application/zip"]], None),
    ("the file server", "https://files.other.example/9f2c", [], None),
    ("a picture by its ending", "https://a.example/cat.JPG", [], None),
    ("a picture with a query", "https://a.example/cat.png?w=200", [], None),
    ("a PDF, a recording", "https://a.example/minutes.pdf https://a.example/talk.m4a", [], None),
    ("a file first, a page second", "https://a.example/cat.png https://a.example/cats", [], "https://a.example/cats"),
    ("preview none", "https://toolshare.example/stories/street-library", [["h", "general"], ["preview", "none"]], None),
    ("another preview value is not none", "https://a.example/x", [["preview", "small"]], "https://a.example/x"),
    ("credentials in the address", "https://user:pw@a.example/x", [], None),
    ("no text", "", [], None),
    ("in a Markdown link", "[the story](https://a.example/story)", [], "https://a.example/story"),
]

LONG = ("Word " * 60).strip()  # 299 characters
LONG_D = ("Neighbours lend tools. " * 20).strip()
EMOJI = "a" * 199 + "\U0001F600\U0001F600"


def doc(head):
    return "<!doctype html><html><head>" + head + "</head><body><p>Body text that is never read.</p></body></html>"


HTML = [
    ("og: every piece", "https://toolshare.example/stories/street-library",
     doc('<title>Street library | ToolShare</title><meta property="og:title" content="How a street tool library lends 400 drills a year">'
         '<meta property="og:description" content="Neighbours started lending their tools from a garage."><meta property="og:site_name" content="ToolShare Journal">'
         '<meta property="og:image" content="https://toolshare.example/og/street-library.jpg"><meta name="description" content="Not this one">'),
     "How a street tool library lends 400 drills a year", "Neighbours started lending their tools from a garage.", "ToolShare Journal",
     "https://toolshare.example/og/street-library.jpg"),
    ("twitter when there is no og", "https://a.example/post",
     doc('<title>Page title</title><meta name="twitter:title" content="Twitter title"><meta name="twitter:description" content="Twitter description">'
         '<meta name="twitter:image" content="/img/card.png">'),
     "Twitter title", "Twitter description", "", "https://a.example/img/card.png"),
    ("title and meta description when there is neither", "https://a.example/",
     doc('<title>Just a title</title><meta name="description" content="Just a description">'),
     "Just a title", "Just a description", "", None),
    ("an empty og:title falls back", "https://a.example/",
     doc('<meta property="og:title" content="  "><meta name="twitter:title" content=""><title>The title element</title>'),
     "The title element", "", "", None),
    ("og before twitter before the rest, piece by piece", "https://a.example/",
     doc('<title>T</title><meta name="description" content="D"><meta name="twitter:description" content="TD"><meta property="og:title" content="OG">'),
     "OG", "TD", "", None),
    ("nothing usable", "https://a.example/", doc('<meta property="og:image" content="/x.png">'), "", "", "", "https://a.example/x.png"),
    ("a title over 200 characters is cut", "https://a.example/", doc("<title>" + LONG + "</title>"), cut(LONG, 200), "", "", None),
    ("a description over 300 characters is cut", "https://a.example/", doc('<title>T</title><meta name="description" content="' + LONG_D + '">'), "T", cut(LONG_D, 300), "", None),
    ("characters are code points, never half of one", "https://a.example/", doc("<title>" + EMOJI + "</title>"), "a" * 199 + "\U0001F600", "", "", None),
    ("entities", "https://a.example/",
     doc('<title>Caf&eacute; &amp; Bar &#8212; &#x27;Open&#x27; &unknown; &lt;b&gt;</title>'),
     "Café & Bar — 'Open' &unknown; <b>", "", "", None),
    ("tags inside the title are stripped", "https://a.example/", doc("<title>The <b>bold</b>\n   plan</title>"), "The bold plan", "", "", None),
    ("whitespace and no-break spaces collapse", "https://a.example/", doc('<meta property="og:title" content="  Two\n\n\tlines&nbsp;&nbsp;here  ">'), "Two lines here", "", "", None),
    ("a relative picture against the page", "https://a.example/blog/post/1", doc('<title>T</title><meta property="og:image" content="../img/a.png">'), "T", "", "", "https://a.example/blog/img/a.png"),
    ("a protocol-relative picture", "https://a.example/", doc('<title>T</title><meta property="og:image" content="//cdn.example/x.jpg">'), "T", "", "", "https://cdn.example/x.jpg"),
    ("an http picture is none", "https://a.example/", doc('<title>T</title><meta property="og:image" content="http://cdn.example/x.jpg">'), "T", "", "", None),
    ("a data picture is none", "https://a.example/", doc('<title>T</title><meta property="og:image" content="data:image/png;base64,AAAA">'), "T", "", "", None),
    ("relative to the final address after redirects", "https://www.b.example/final/page",
     doc('<title>T</title><meta property="og:image" content="/og.png">'), "T", "", "", "https://www.b.example/og.png"),
    ("an entity in the picture's address", "https://a.example/", doc('<title>T</title><meta property="og:image" content="https://a.example/i.png?a=1&amp;b=2">'), "T", "", "", "https://a.example/i.png?a=1&b=2"),
    ("attributes in any order, any quotes, any case", "https://a.example/",
     doc("<META CONTENT='Hi there' PROPERTY=og:title><meta content=\"The site\" property=\"og:site_name\"/>"), "Hi there", "", "The site", None),
    ("comments and scripts are not read", "https://a.example/",
     doc('<!-- <meta property="og:title" content="In a comment"> --><script>var s = \'<meta property="og:title" content="In a script">\';</script><title>Real</title>'),
     "Real", "", "", None),
    ("the first of two wins", "https://a.example/", doc('<meta property="og:title" content="First"><meta property="og:title" content="Second">'), "First", "", "", None),
    ("the site from application-name", "https://a.example/", doc('<title>T</title><meta name="application-name" content="Garden Wiki">'), "T", "", "Garden Wiki", None),
    ("og:image:secure_url and twitter:image:src", "https://a.example/",
     doc('<title>T</title><meta name="twitter:image:src" content="https://a.example/tw.png"><meta property="og:image:secure_url" content="https://a.example/secure.png">'),
     "T", "", "", "https://a.example/secure.png"),
    ("twitter tags given as property", "https://a.example/", doc('<meta property="twitter:title" content="As a property">'), "As a property", "", "", None),
]

CHARSETS = [
    ("latin-1 by the header", "text/html; charset=iso-8859-1", "<title>Caf\xe9</title>".encode("latin-1"), "Café"),
    ("latin-1 by a meta charset", "text/html", b'<meta charset="iso-8859-1"><title>Gr\xfc\xdfe</title>', "Grüße"),
    ("UTF-8 when nothing says", "text/html", "<title>Café ☕</title>".encode("utf-8"), "Café ☕"),
    ("a byte order mark", "text/html", b"\xef\xbb\xbf<title>BOM</title>", "BOM"),
]

URLS = [
    ("https://a.example/x", True), ("https://a.example:443/x", True), ("https://a.example./x", True),
    ("https://xn--caf-dma.example/", True),
    ("http://a.example/", False), ("ftp://a.example/", False), ("https://a.example:8443/", False),
    ("https://user:pw@a.example/", False), ("https://user@a.example/", False),
    ("https://127.0.0.1/", False), ("https://93.184.215.14/", False), ("https://[::1]/", False), ("https://[2606:4700::1111]/", False),
    ("https://localhost/", False), ("https://my.localhost/", False), ("https://intranet/", False), ("https://printer.local/", False),
    ("https://router.lan/", False), ("https://2130706433/", False), ("https://0x7f.0.0.1/", False), ("not a url", False),
]

ADDRESSES = [
    ("93.184.215.14", True), ("8.8.8.8", True), ("1.1.1.1", True), ("2606:4700:4700::1111", True), ("2a00:1450:4001:80b::200e", True),
    ("0.0.0.0", False), ("10.1.2.3", False), ("100.64.0.1", False), ("100.127.255.254", False), ("100.128.0.1", True),
    ("127.0.0.1", False), ("127.255.0.9", False), ("169.254.169.254", False), ("172.16.0.1", False), ("172.31.255.255", False),
    ("172.32.0.1", True), ("192.0.0.8", False), ("192.0.2.1", False), ("192.168.1.1", False), ("198.18.0.1", False),
    ("198.51.100.7", False), ("203.0.113.9", False), ("224.0.0.251", False), ("239.255.255.250", False), ("240.0.0.1", False),
    ("255.255.255.255", False),
    ("::", False), ("::1", False), ("::ffff:127.0.0.1", False), ("::ffff:8.8.8.8", True), ("64:ff9b::a00:1", False), ("64:ff9b::808:808", True),
    ("2001:db8::1", False), ("2001::1", False), ("2002:a00:1::1", False), ("2002:808:808::1", True),
    ("fc00::1", False), ("fd12:3456::1", False), ("fe80::1", False), ("fec0::1", False), ("ff02::1", False), ("100::1", False),
]

PICTURES = [
    ("png", "89504e470d0a1a0a0000000d49484452", "image/png"),
    ("jpeg", "ffd8ffe000104a464946", "image/jpeg"),
    ("gif89a", "474946383961010001", "image/gif"),
    ("gif87a", "474946383761010001", "image/gif"),
    ("webp", "52494646240000005745425056503820", "image/webp"),
    ("riff that is not webp", "52494646240000005741564566", None),
    ("svg", "3c3f786d6c2076657273696f6e3d", None),
    ("html", "3c21646f63747970652068746d6c3e", None),
    ("too short", "ffd8", None),
    ("empty", "", None),
]

# a stand-in for a re-encoded picture: a JPEG's first bytes then filler (only the bytes' hashes matter here)
PLAIN = bytes.fromhex("ffd8ffe000104a46494600010100000100010000") + bytes(range(44))
KEY = "a1" * 32
NONCE = "b2" * 12
CT = AESGCM(bytes.fromhex(KEY)).encrypt(bytes.fromhex(NONCE), PLAIN, None)
PIC_URL = BLOSSOM + "/" + sha(PLAIN) + ".jpg"
ENC_URL = BLOSSOM + "/" + sha(CT)


def imeta_plain():
    return ["imeta", "url " + PIC_URL, "m image/jpeg", "dim 1200x630", "x " + sha(PLAIN), "purpose preview"]


def imeta_enc():
    return ["imeta", "url " + ENC_URL, "m image/jpeg", "dim 1200x630", "x " + sha(CT), "ox " + sha(PLAIN), "encryption-algorithm aes-gcm",
            "decryption-key " + KEY, "decryption-nonce " + NONCE, "purpose preview"]


STORY = "https://toolshare.example/stories/street-library"
TITLE = "How a street tool library lends 400 drills a year"
DESC = "Neighbours started lending their tools from a garage."
TEXT = "This is what we could become: " + STORY


def ptag(url=STORY, title=TITLE, desc=DESC, site="ToolShare Journal"):
    return ["preview", url, title, desc, site]


def shown(url=STORY, title=TITLE, desc=DESC, site="ToolShare Journal", line="ToolShare Journal · toolshare.example", picture=None):
    return {"url": url, "title": title, "description": desc, "site": site, "site_line": line, "picture": picture}


PIC = {"url": PIC_URL, "mime": "image/jpeg", "width": 1200, "height": 630, "encrypted": False}
EPIC = {"url": ENC_URL, "mime": "image/jpeg", "width": 1200, "height": 630, "encrypted": True}

READ = [
    ("a preview with its picture", TEXT, [["h", "general"], ptag(), imeta_plain()], shown(picture=PIC)),
    ("an encrypted picture", TEXT, [ptag(), imeta_enc()], shown(picture=EPIC)),
    ("no picture", TEXT, [ptag()], shown()),
    ("a picture from anywhere but the belfry's Blossom is not shown", TEXT,
     [ptag(), ["imeta", "url https://toolshare.example/og.jpg", "m image/jpeg", "x " + sha(PLAIN), "purpose preview"]], shown()),
    ("a look-alike Blossom host", TEXT, [ptag(), ["imeta", "url https://blossom.belfry.example.evil.example/a", "m image/jpeg", "x " + sha(PLAIN), "purpose preview"]], shown()),
    ("Blossom on another port", TEXT, [ptag(), ["imeta", "url https://blossom.belfry.example:8443/a", "m image/jpeg", "x " + sha(PLAIN), "purpose preview"]], shown()),
    ("Blossom over http", TEXT, [ptag(), ["imeta", "url http://blossom.belfry.example/a", "m image/jpeg", "x " + sha(PLAIN), "purpose preview"]], shown()),
    ("an imeta that is not the preview's is not its picture", TEXT, [ptag(), imeta_plain()[:-1]], shown()),
    ("a picture without x", TEXT, [ptag(), ["imeta", "url " + PIC_URL, "m image/jpeg", "purpose preview"]], shown()),
    ("a picture that is not an image type", TEXT, [ptag(), ["imeta", "url " + PIC_URL, "m image/svg+xml", "x " + sha(PLAIN), "purpose preview"]], shown()),
    ("an encrypted picture without its nonce", TEXT, [ptag(), [t for t in imeta_enc() if not t.startswith("decryption-nonce")]], shown()),
    ("an encrypted picture with another algorithm", TEXT, [ptag(), [t.replace("aes-gcm", "aes-cbc") for t in imeta_enc()]], shown()),
    ("no size: the picture without one", TEXT, [ptag(), [t for t in imeta_plain() if not t.startswith("dim")]], shown(picture=dict(PIC, width=None, height=None))),
    ("preview none", TEXT, [["preview", "none"]], None),
    ("no tag: a plain link", TEXT, [["h", "general"]], None),
    ("a link the text doesn't hold", "Nothing to see", [ptag()], None),
    ("an http link", "See http://a.example/x", [ptag(url="http://a.example/x")], None),
    ("no title and no description", TEXT, [ptag(title="", desc="")], None),
    ("only a title, no site: the host", TEXT, [ptag(desc="", site="")], shown(desc="", site="", line="toolshare.example")),
    ("www. is not part of the host", "Read https://www.toolshare.example/x", [ptag(url="https://www.toolshare.example/x", site="toolshare.example")],
     shown(url="https://www.toolshare.example/x", site="toolshare.example", line="toolshare.example")),
    ("a missing site element", TEXT, [["preview", STORY, TITLE, DESC]], shown(site="", line="toolshare.example")),
    ("an overlong title in the tag is cut on reading", TEXT, [ptag(title=LONG + " " + LONG)], shown(title=cut(LONG + " " + LONG, 200))),
    ("the first preview tag counts", TEXT, [ptag(), ptag(title="Second")], shown()),
]


def build():
    v = {
        "_": ("Link previews built by the sender (SPEC 19.87; collective-belfry docs/link-previews-v2.md). Written by belfry-android "
              "(tools/link_previews_vectors.py); web and iOS copy it byte for byte. Sections: links (which link of a text gets a preview: "
              "the composer's rule), html (a page's text and final address -> title / description / site / picture address; og, then twitter, "
              "then <title> / meta description, piece by piece; values: tags stripped, entities decoded, whitespace collapsed, cut to "
              "200 / 300 / 100 Unicode code points then trailing spaces trimmed; the picture made absolute against the final address, https "
              "only), charsets (bytes + Content-Type -> title), urls (may the sender's device fetch it), addresses (may it connect to a name "
              "that resolved to this; every address of a name must pass), pictures (the type by the first bytes, hex), tags (what a message "
              "carries: the preview tag and its imeta entry, plain and AES-256-GCM encrypted with a fixed key and nonce, as files of direct "
              "messages: vectors/dm-files.json), read (a message's text and tags -> the box a reader shows, or null; the picture only from "
              "the belfry's Blossom)."),
        "constants": {
            "title_max": 200, "description_max": 300, "site_max": 100, "page_max_bytes": 524288, "picture_in_max_bytes": 5242880,
            "picture_out_max_bytes": 307200, "picture_side_max": 1200, "redirects_max": 3, "timeout_ms": 5000,
            "no_preview_tag": ["preview", "none"], "purpose": "purpose preview",
        },
        "domain": DOMAIN,
        "files": FILES,
        "blossom": BLOSSOM,
        "links": [{"name": n, "text": t, "tags": g, "link": l} for n, t, g, l in LINKS],
        "html": [{"name": n, "final_url": u, "html": h, "title": t, "description": d, "site": s, "image": i} for n, u, h, t, d, s, i in HTML],
        "charsets": [{"name": n, "content_type": c, "bytes": b.hex(), "title": t} for n, c, b, t in CHARSETS],
        "urls": [{"url": u, "ok": ok} for u, ok in URLS],
        "addresses": [{"address": a, "ok": ok} for a, ok in ADDRESSES],
        "pictures": [{"name": n, "hex": h, "type": t} for n, h, t in PICTURES],
        "tags": [
            {"name": "with a picture in the clear", "preview": {"url": STORY, "title": TITLE, "description": DESC, "site": "ToolShare Journal"},
             "picture": {"plain": PLAIN.hex(), "url": PIC_URL, "mime": "image/jpeg", "width": 1200, "height": 630},
             "tags": [ptag(), imeta_plain()]},
            {"name": "with an encrypted picture", "preview": {"url": STORY, "title": TITLE, "description": DESC, "site": "ToolShare Journal"},
             "picture": {"plain": PLAIN.hex(), "url": ENC_URL, "mime": "image/jpeg", "width": 1200, "height": 630, "key": KEY, "nonce": NONCE,
                         "ciphertext": CT.hex()},
             "tags": [ptag(), imeta_enc()]},
            {"name": "no picture, no site", "preview": {"url": STORY, "title": TITLE, "description": "", "site": ""}, "picture": None,
             "tags": [["preview", STORY, TITLE, "", ""]]},
        ],
        "read": [{"name": n, "text": t, "tags": g, "shown": s} for n, t, g, s in READ],
    }
    return json.dumps(v, ensure_ascii=False, indent=1) + "\n"


if __name__ == "__main__":
    path = sys.argv[1]
    out = build()
    if "--check" in sys.argv:
        same = open(path, encoding="utf-8").read() == out
        print("same" if same else "DIFFERENT")
        sys.exit(0 if same else 1)
    open(path, "w", encoding="utf-8").write(out)
