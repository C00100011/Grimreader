#!/usr/bin/env python3
"""A tiny stand-in for a Grimmory server, for testing the app without a real library.

    python3 tools/mock-grimmory/server.py [port]        # default 8765

From the Android emulator the host is http://10.0.2.2:<port> (any username and password work).
It implements only what the app calls: login, the /app/* catalogue (books, authors, series,
filter options), progress, covers (generated PNGs), the EPUB download (one of the demo books for every id),
/api/v1/books/{id}/recommendations, an OPDS catalog at /opds (open) and /opds-private (user "reader", password "secret"),
a bare-bones Shelfmark (auth off, metadata search, empty releases) on the same address, and a fake Hardcover GraphQL endpoint at /hardcover/graphql
(use the key  http://10.0.2.2:<port>/hardcover/graphql|mocktoken  in a debug build; any other token is refused with 401).
Unknown paths answer 404 and are printed, so gaps are easy to spot.
"""
import json
import re
import struct
import sys
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlparse

ROOT = Path(__file__).resolve().parents[2]
EPUB = ROOT / "app/src/main/assets/demo/frankenstein.epub"

# id, title, authors, series, #, categories, language
BOOKS = [
    (1, "Frankenstein", ["Mary Shelley"], None, None, ["Gothic", "Horror", "Science Fiction"], "en"),
    (2, "The Time Machine", ["H. G. Wells"], None, None, ["Science Fiction", "Classic"], "en"),
    (3, "The War of the Worlds", ["H. G. Wells"], None, None, ["Science Fiction", "Classic"], "en"),
    (4, "The Invisible Man", ["H. G. Wells"], None, None, ["Science Fiction", "Horror"], "en"),
    (5, "Dracula", ["Bram Stoker"], None, None, ["Gothic", "Horror"], "en"),
    (6, "Strange Case of Dr Jekyll and Mr Hyde", ["Robert Louis Stevenson"], None, None, ["Gothic", "Horror", "Classic"], "en"),
    (7, "Treasure Island", ["Robert Louis Stevenson"], None, None, ["Adventure", "Classic"], "en"),
    (8, "The Last Man", ["Mary Shelley"], None, None, ["Science Fiction", "Gothic"], "en"),
    (9, "De Avonden", ["Gerard Reve"], None, None, ["Literary"], "nl"),
    (10, "Max Havelaar", ["Multatuli"], None, None, ["Classic", "Literary"], "nl"),
    (11, "Foundation", ["Isaac Asimov"], "Foundation", 1.0, ["Science Fiction"], "en"),
    (12, "Foundation and Empire", ["Isaac Asimov"], "Foundation", 2.0, ["Science Fiction"], "en"),
    (13, "Second Foundation", ["Isaac Asimov"], "Foundation", 3.0, ["Science Fiction"], "en"),
    (14, "I, Robot", ["Isaac Asimov"], None, None, ["Science Fiction"], "en"),
]
DESCRIPTION = "A mock book from the Grimreader test server."
progress = {}  # id -> percent of the web reader (what Grimreader writes)
kobo = {}  # id -> percent held by a (pretend) Kobo; Grimmory reports it as readProgress before the web reader's
puts = []  # (book id, percent) of every progress update the app sent, newest last


def overall(i):
    """What Grimmory calls the book's readProgress: KOReader, then Kobo, then the web reader."""
    return kobo.get(i, progress.get(i))
status = {11: "READ", 1: "READ"}  # finished books = seeds for Hardcover suggestions


def summary(b):
    i, title, authors, series, num, cats, lang = b
    return {
        "id": i, "title": title, "authors": authors, "thumbnailUrl": None,
        "readStatus": status.get(i, "UNREAD"), "personalRating": None,
        "seriesName": series, "seriesNumber": num, "libraryId": 1,
        "addedOn": f"2026-09-{10 + i:02d}T10:00:00Z", "lastReadTime": ("2026-10-01T10:00:00Z" if status.get(i) == "READ" else None),
        "readProgress": overall(i), "primaryFileId": 100 + i, "primaryFileType": "EPUB",
        "primaryFileName": title + ".epub", "coverUpdatedOn": "2026-09-01T10:00:00Z", "isPhysical": False,
        "publisher": "Mock Press", "categories": cats, "tags": [], "language": lang,
        "publishedDate": "1897-01-01", "pageCount": 200 + 13 * i, "fileSizeKb": 600,
    }


def detail(b):
    d = summary(b)
    d.update({
        "subtitle": None, "description": DESCRIPTION, "isbn13": None, "goodreadsRating": 4.1,
        "goodreadsReviewCount": 1200, "libraryName": "Mock library", "shelves": [],
        "fileTypes": ["EPUB"],
        "files": [{"id": 100 + b[0], "bookId": b[0], "fileName": b[1] + ".epub", "isBook": True,
                   "bookType": "EPUB", "fileSizeKb": 600, "extension": "epub", "isPrimary": True}],
    })
    return d


def full_book(b):
    """The big `Book` DTO that /recommendations returns (nested metadata)."""
    i, title, authors, series, num, cats, lang = b
    return {
        "id": i, "libraryId": 1, "libraryName": "Mock library", "title": title,
        "primaryFile": {"id": 100 + i, "bookId": i, "fileName": title + ".epub", "bookType": "EPUB", "extension": "epub"},
        "addedOn": f"2026-09-{10 + i:02d}T10:00:00Z", "lastReadTime": None, "readStatus": status.get(i, "UNREAD"),
        "personalRating": None, "isPhysical": False, "shelves": [],
        "metadata": {
            "bookId": i, "title": title, "authors": authors, "categories": cats, "language": lang,
            "seriesName": series, "seriesNumber": num, "pageCount": 200 + 13 * i,
            "coverUpdatedOn": "2026-09-01T10:00:00Z", "description": DESCRIPTION, "publisher": "Mock Press",
            "publishedDate": "1897-01-01", "rating": 4.1, "unknownFutureField": {"x": 1},
        },
    }


def matches(b, q):
    q = q.lower()
    return q in b[1].lower() or any(q in a.lower() for a in b[2]) or (b[3] and q in b[3].lower())


def page(items, params, default_size=30):
    p = int(params.get("page", ["0"])[0])
    s = int(params.get("size", [str(default_size)])[0])
    total = len(items)
    chunk = items[p * s:(p + 1) * s]
    pages = max(1, -(-total // s))
    return {"content": chunk, "page": p, "size": s, "totalElements": total, "totalPages": pages,
            "hasNext": (p + 1) * s < total, "hasPrevious": p > 0}


def recommendations(book_id, limit):
    me = next((b for b in BOOKS if b[0] == book_id), None)
    if not me:
        return []
    scored = []
    for b in BOOKS:
        if b[0] == book_id:
            continue
        shared = len(set(b[5]) & set(me[5]))
        score = shared / max(len(set(me[5]) | set(b[5])), 1)
        if b[3] and b[3] == me[3]:
            score += 0.5
        if set(b[2]) & set(me[2]):
            score += 0.2
        if score > 0 and b[6] == me[6]:
            scored.append((score, b))
    scored.sort(key=lambda t: -t[0])
    return [{"book": full_book(b), "similarityScore": round(s, 3)} for s, b in scored[:limit]]


def png(book_id, w=200, h=300):
    hue = (book_id * 47) % 360
    def hsv(hh, s, v):
        import colorsys
        r, g, b = colorsys.hsv_to_rgb(hh / 360, s, v)
        return int(r * 255), int(g * 255), int(b * 255)
    rows = []
    for y in range(h):
        t = y / h
        r, g, b = hsv(hue, 0.55, 0.85 - 0.35 * t)
        if 0.62 < t < 0.8:
            r, g, b = r // 3, g // 3, b // 3
        rows.append(b"\x00" + bytes([r, g, b]) * w)
    raw = b"".join(rows)
    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")



# --- fake Hardcover (GraphQL) -------------------------------------------------------------------------
HC_BOOKS = {
    101: ("Dune", "Frank Herbert", 4.27, 1965, "<p>The desert planet <b>Arrakis</b> and the spice.</p>", "Dune", 1.0),
    102: ("Neuromancer", "William Gibson", 3.9, 1984, "A washed-up hacker is hired for one last job.", "Sprawl", 1.0),
    103: ("Hyperion", "Dan Simmons", 4.2, 1989, "Seven pilgrims, one shrike.", "Hyperion Cantos", 1.0),
    104: ("Solaris", "Stanislaw Lem", 4.0, 1961, "An ocean that thinks.", None, None),
    105: ("The Left Hand of Darkness", "Ursula K. Le Guin", 4.1, 1969, "A envoy on a winter planet.", "Hainish Cycle", 4.0),
    106: ("Foundation", "Isaac Asimov", 4.17, 1951, "Psychohistory and the fall of an empire.", "Foundation", 1.0),
    107: ("Dracula", "Bram Stoker", 4.0, 1897, "The count arrives in England.", None, None),
    108: ("Frankenstein", "Mary Shelley", 3.8, 1818, "A scientist makes a creature.", None, None),
    109: ("Project Hail Mary", "Andy Weir", 4.5, 2021, "A lone astronaut must save Earth.", None, None),
    110: ("Piranesi", "Susanna Clarke", 4.2, 2020, "A man lives in a house of endless halls.", None, None),
    111: ("The Martian", "Andy Weir", 4.4, 2011, "Stranded on Mars.", None, None),
    112: ("Klara and the Sun", "Kazuo Ishiguro", 3.9, 2021, "An artificial friend watches.", None, None),
}
HC_SIMILAR = {106: [101, 103, 104, 105, 102, 107], 108: [107, 104, 112, 110]}
HC_TRENDING = {
    "week": [109, 101, 110, 111, 103, 112, 102, 105],
    "month": [101, 109, 106, 103, 111, 110],
    "one_year": [111, 109, 101, 112, 110],
    "all": [101, 106, 107, 108, 111],
}


def hc_book(i, base):
    t, a, r, y, d, series, pos = HC_BOOKS[i]
    return {
        "id": i, "title": t, "subtitle": None, "slug": t.lower().replace(" ", "-"), "rating": r, "ratings_count": 1000 + i * 37,
        "users_count": 12000 + i * 311, "release_year": y, "pages": 200 + i, "description": d,
        "image": {"url": f"{base}/api/v1/media/book/{i}/cover"},
        "contributions": [{"author": {"name": a}}],
        "book_series": [{"position": pos, "series": {"name": series}}] if series else [],
    }


def hc_answer(query, variables, base):
    ids = variables.get("ids") or []
    if "books_trending" in query:
        lst = HC_TRENDING.get(variables.get("duration", "week"), [])[: variables.get("limit", 20)]
        return {"books_trending": {"ids": lst, "error": None}}
    if "cached_similar_book_ids" in query:
        return {"books": [{"id": i, "cached_similar_book_ids": HC_SIMILAR.get(i, [])} for i in ids if i in HC_BOOKS]}
    if "search(" in query:
        q = (variables.get("q") or "").lower()
        return {"search": {"ids": [i for i, b in HC_BOOKS.items() if b[0].lower() in q][:3], "error": None}}
    if "BooksByIds" in query:
        return {"books": [hc_book(i, base) for i in ids if i in HC_BOOKS]}
    return None


# --- OPDS catalog (open at /opds, behind basic auth at /opds-private) -------------------------------------
import base64
from xml.sax.saxutils import escape

OPDS_PAGE = 5


def opds_feed(title, self_url, entries, next_url=None, search=True):
    nav = "application/atom+xml;profile=opds-catalog;kind=navigation"
    acq = "application/atom+xml;profile=opds-catalog;kind=acquisition"
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           '<feed xmlns="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/terms/" xmlns:opds="http://opds-spec.org/2010/catalog">',
           f"<id>urn:mock:{escape(self_url)}</id><title>{escape(title)}</title><updated>2026-10-07T00:00:00Z</updated>",
           f'<link rel="self" href="{escape(self_url)}" type="{acq}"/>']
    if search:
        out.append(f'<link rel="search" href="{OPDS_PREFIX[0]}/osd" type="application/opensearchdescription+xml"/>')
    if next_url:
        out.append(f'<link rel="next" href="{escape(next_url)}" type="{acq}"/>')
    out += entries
    out.append("</feed>")
    return "\n".join(out)


def opds_nav_entry(title, href, kind, summary=""):
    t = f"application/atom+xml;profile=opds-catalog;kind={kind}"
    return (f"<entry><id>urn:nav:{escape(href)}</id><title>{escape(title)}</title><updated>2026-10-07T00:00:00Z</updated>"
            f'<content type="text">{escape(summary)}</content><link rel="subsection" href="{escape(href)}" type="{t}"/></entry>')


def opds_book_entry(b, base):
    i, title, authors, series, num, cats, lang = b
    a = "".join(f"<author><name>{escape(x)}</name></author>" for x in authors)
    return (f"<entry><id>urn:book:{i}</id><title>{escape(title)}</title><updated>2026-10-01T00:00:00Z</updated>{a}"
            f"<dc:language>{lang}</dc:language><summary>{escape(DESCRIPTION)}</summary>"
            f'<link rel="http://opds-spec.org/image" href="{base}/api/v1/media/book/{i}/cover" type="image/png"/>'
            f'<link rel="http://opds-spec.org/image/thumbnail" href="{base}/api/v1/media/book/{i}/thumbnail" type="image/png"/>'
            f'<link rel="http://opds-spec.org/acquisition" href="{OPDS_PREFIX[0]}/get/{i}.epub" type="application/epub+zip"/>'
            f'<link rel="http://opds-spec.org/acquisition" href="{OPDS_PREFIX[0]}/get/{i}.pdf" type="application/pdf"/></entry>')


OPDS_PREFIX = ["/opds"]


def opds_response(handler, path, q, host):
    """Returns True when the request was an OPDS one (and has been answered)."""
    for prefix, private in (("/opds-private", True), ("/opds", False)):
        if path == prefix or path.startswith(prefix + "/"):
            break
    else:
        return False
    if private:
        want = "Basic " + base64.b64encode(b"reader:secret").decode()
        if handler.headers.get("Authorization") != want:
            handler.send_response(401)
            handler.send_header("WWW-Authenticate", 'Basic realm="mock"')
            handler.send_header("Content-Length", "0")
            handler.end_headers()
            return True
    OPDS_PREFIX[0] = prefix
    base = f"http://{host}"
    sub = path[len(prefix):]
    atom = "application/atom+xml;profile=opds-catalog"
    if sub in ("", "/"):
        entries = [opds_nav_entry("Recently added", f"{prefix}/new", "acquisition", "The newest books"),
                   opds_nav_entry("Science Fiction", f"{prefix}/genre/Science%20Fiction", "acquisition", "Spaceships and ideas"),
                   opds_nav_entry("Authors", f"{prefix}/authors", "navigation", "Browse by author")]
        return handler.send(200, opds_feed("Mock OPDS catalog", f"{prefix}", entries), atom) or True
    if sub == "/osd":
        xml = ('<?xml version="1.0"?><OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/"><ShortName>Mock</ShortName>'
               f'<Url type="text/html" template="{base}/nothing?q={{searchTerms}}"/>'
               f'<Url type="application/atom+xml" template="{base}{prefix}/search?q={{searchTerms}}"/></OpenSearchDescription>')
        return handler.send(200, xml, "application/opensearchdescription+xml") or True
    if sub == "/authors":
        authors = sorted({a for b in BOOKS for a in b[2]})
        entries = [opds_nav_entry(a, f"{prefix}/author/{a.replace(' ', '%20')}", "acquisition", f"{sum(a in b[2] for b in BOOKS)} books") for a in authors]
        return handler.send(200, opds_feed("Authors", f"{prefix}/authors", entries), atom) or True
    books = None
    title = "Books"
    if sub == "/new":
        books, title = sorted(BOOKS, key=lambda b: -b[0]), "Recently added"
    elif sub.startswith("/genre/"):
        g = unquote(sub[len("/genre/"):])
        books, title = [b for b in BOOKS if g in b[5]], g
    elif sub.startswith("/author/"):
        a = unquote(sub[len("/author/"):])
        books, title = [b for b in BOOKS if a in b[2]], a
    elif sub == "/search":
        term = (q.get("q") or [""])[0]
        books, title = [b for b in BOOKS if matches(b, term)], f"Search: {term}"
    if books is not None:
        offset = int((q.get("offset") or ["0"])[0])
        page = books[offset:offset + OPDS_PAGE]
        more = offset + OPDS_PAGE < len(books)
        qs = "&".join(f"{k}={v[0]}" for k, v in q.items() if k != "offset")
        nxt = f"{prefix}{sub}?{qs + '&' if qs else ''}offset={offset + OPDS_PAGE}" if more else None
        return handler.send(200, opds_feed(title, f"{prefix}{sub}", [opds_book_entry(b, base) for b in page], nxt), atom) or True
    m = re.fullmatch(r"/get/(\d+)\.(epub|pdf)", sub)
    if m:
        if m.group(2) == "pdf":
            return handler.send(200, b"%PDF-1.4 mock", "application/pdf") or True
        return handler.send(200, EPUB.read_bytes(), "application/epub+zip") or True
    handler.send(404, {"message": "no such opds path"})
    return True


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("%s %s\n" % (getattr(self, "command", "?"), getattr(self, "path", "?")))

    def send(self, code, body=b"", ctype="application/json"):
        if isinstance(body, str):
            body = body.encode()
        elif not isinstance(body, (bytes, bytearray)):
            body = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n) if n else b""

    def do_POST(self):
        path = urlparse(self.path).path
        data = self.body()
        if path == "/hardcover/graphql":
            if self.headers.get("authorization") != "Bearer mocktoken":
                return self.send(401, {"error": "invalid_token", "error_description": "The token is missing, invalid or expired"})
            req = json.loads(data or b"{}")
            host = self.headers.get("Host", "10.0.2.2:8765")
            ans = hc_answer(req.get("query", ""), req.get("variables") or {}, f"http://{host}")
            if ans is None:
                return self.send(200, {"errors": [{"message": "mock: unknown query", "extensions": {"code": "validation-failed"}}]})
            return self.send(200, {"data": ans})
        if path in ("/api/v1/auth/login", "/api/v1/auth/refresh"):
            return self.send(200, {"accessToken": "mock-access", "refreshToken": "mock-refresh", "expires": 9999999999, "isDefaultPassword": False})
        if path == "/api/v1/shelves":
            req = json.loads(data or b"{}")
            return self.send(200, {"id": 7, "name": req.get("name", "Shelf"), "bookCount": 0})
        if path in ("/api/v1/reading-sessions", "/api/v1/books/shelves", "/api/v1/annotations", "/api/v1/bookmarks"):
            return self.send(200, {"id": 1} if path.endswith(("annotations", "bookmarks")) else b"", "application/json")
        self.unknown()

    def do_PUT(self):
        path = urlparse(self.path).path
        data = self.body()
        m = re.fullmatch(r"/api/v1/app/books/(\d+)/progress", path)
        if m:
            try:
                book = int(m.group(1))
                progress[book] = json.loads(data)["fileProgress"]["progressPercent"]
                puts.append((book, progress[book]))
            except Exception:
                pass
            return self.send(204)
        m = re.fullmatch(r"/api/v1/app/books/(\d+)/status", path)
        if m:
            status[int(m.group(1))] = json.loads(data).get("status", "UNREAD")
            return self.send(204)
        if path == "/api/v1/books/personal-rating":
            return self.send(200)
        self.unknown()

    def do_GET(self):
        u = urlparse(self.path)
        path, q = u.path, parse_qs(u.query)
        if opds_response(self, path, q, self.headers.get("Host", "10.0.2.2:8765")):
            return
        # --- bare-bones Shelfmark (no login): enough for connecting, searching and the activity poll ---
        if path == "/api/auth/check":
            return self.send(200, {"auth_mode": "none", "authenticated": True})
        if path == "/api/metadata/search":
            term = (q.get("query") or [""])[0].strip()
            hits = [b for b in BOOKS if matches(b, term)] if term else []
            books = [{"provider": "mock", "provider_id": str(b[0]), "title": b[1], "authors": b[2], "language": b[6],
                      "cover_url": f"/api/v1/media/book/{b[0]}/cover", "publish_year": 1950 + b[0], "description": DESCRIPTION}
                     for b in hits]
            return self.send(200, {"books": books, "provider": "mock", "page": 1, "total_found": len(books), "has_more": False})
        if path == "/api/status":
            return self.send(200, {})
        if path == "/api/release-sources":
            return self.send(200, [{"name": "direct_download", "display_name": "Direct download", "enabled": True}])
        if path == "/api/releases":
            return self.send(200, {"releases": [], "sources_searched": ["direct_download"]})
        if path == "/api/v1/public-settings":
            return self.send(200, {"oidcEnabled": False})
        if path == "/api/v1/app/books":
            items = [] if q.get("shelfId") else list(BOOKS)  # the Favorites shelf is empty
            s = (q.get("search") or [""])[0].strip()
            if s:
                items = [b for b in items if matches(b, s)]
            for a in q.get("authors", []):
                items = [b for b in items if a in b[2]]
            for sname in q.get("series", []):
                items = [b for b in items if b[3] == sname]
            for c in q.get("category", []):
                items = [b for b in items if c in b[5]]
            for st in q.get("status", []):
                items = [b for b in items if status.get(b[0], "UNREAD") == st]
            sort = (q.get("sort") or ["title"])[0]
            rev = (q.get("dir") or ["asc"])[0] == "desc"
            items.sort(key=lambda b: b[1] if sort == "title" else b[0], reverse=rev)
            return self.send(200, page([summary(b) for b in items], q))
        if path == "/api/v1/app/books/continue-reading":
            return self.send(200, [summary(b) for b in BOOKS if b[0] in progress][:12])
        if path == "/api/v1/app/books/recently-added":
            return self.send(200, [summary(b) for b in sorted(BOOKS, key=lambda b: -b[0])][:20])
        m = re.fullmatch(r"/api/v1/app/books/(\d+)", path)
        if m:
            b = next((b for b in BOOKS if b[0] == int(m.group(1))), None)
            return self.send(200, detail(b)) if b else self.send(404, {"message": "not found"})
        m = re.fullmatch(r"/api/v1/app/books/(\d+)/progress", path)
        if m:
            book = int(m.group(1))
            body = {"readProgress": overall(book), "readStatus": status.get(book, "UNREAD")}
            if book in progress:
                body["epubProgress"] = {"percentage": progress[book], "updatedAt": "2026-10-01T10:00:00Z"}
            return self.send(200, body)
        # test controls: pretend a Kobo is at some percentage, and see what the app sent
        if path == "/mock/kobo":
            kobo[int(q["id"][0])] = float(q["percent"][0])
            return self.send(200, {"kobo": kobo})
        if path == "/mock/state":
            return self.send(200, {"progress": progress, "kobo": kobo, "puts": puts[-20:]})
        m = re.fullmatch(r"/api/v1/books/(\d+)/recommendations", path)
        if m:
            limit = int((q.get("limit") or ["25"])[0])
            return self.send(200, recommendations(int(m.group(1)), limit))
        if path == "/api/v1/app/filter-options":
            authors = sorted({a for b in BOOKS for a in b[2]})
            cats = sorted({c for b in BOOKS for c in b[5]})
            return self.send(200, {
                "authors": [{"name": a, "count": sum(a in b[2] for b in BOOKS)} for a in authors],
                "categories": [{"name": c, "count": sum(c in b[5] for b in BOOKS)} for c in cats],
                "series": [{"name": "Foundation", "count": 3}],
                "languages": [{"code": "en", "label": "English", "count": 12}, {"code": "nl", "label": "Nederlands", "count": 2}],
                "readStatuses": [{"name": "UNREAD", "count": len(BOOKS)}],
            })
        if path == "/api/v1/app/authors":
            s = (q.get("search") or [""])[0].strip().lower()
            authors = sorted({a for b in BOOKS for a in b[2]})
            items = [{"id": 1000 + i, "name": a, "bookCount": sum(a in b[2] for b in BOOKS), "hasPhoto": False}
                     for i, a in enumerate(authors) if s in a.lower()]
            return self.send(200, page(items, q, 60))
        if path == "/api/v1/app/series":
            s = (q.get("search") or [""])[0].strip().lower()
            items = []
            if "foundation".find(s) >= 0 or not s:
                items.append({"seriesName": "Foundation", "bookCount": 3, "seriesTotal": 7, "authors": ["Isaac Asimov"], "booksRead": 0,
                              "coverBooks": [{"bookId": i} for i in (11, 12, 13)]})
            return self.send(200, page(items, q, 40))
        m = re.fullmatch(r"/api/v1/app/series/(.+)/books", path)
        if m:
            name = unquote(m.group(1))
            return self.send(200, page([summary(b) for b in BOOKS if b[3] == name], q, 100))
        if path in ("/api/v1/app/shelves",):
            return self.send(200, [{"id": 7, "name": "Favorites", "bookCount": 0, "publicShelf": False}])
        if path == "/api/v1/shelves":
            return self.send(200, [{"id": 7, "name": "Favorites", "bookCount": 0, "publicShelf": False}])
        m = re.fullmatch(r"/api/v1/media/book/(\d+)/(cover|thumbnail)", path)
        if m:
            return self.send(200, png(int(m.group(1))), "image/png")
        m = re.fullmatch(r"/api/v1/books/(\d+)/content", path)
        if m:
            data = EPUB.read_bytes()
            return self.send(200, data, "application/epub+zip")
        if re.fullmatch(r"/api/v1/(reviews|annotations|bookmarks)/book/\d+", path):
            return self.send(200, [])
        if path.startswith("/api/v1/media/author/"):
            return self.send(404, {})
        self.unknown()

    def unknown(self):
        sys.stderr.write("   !! not implemented: %s %s\n" % (self.command, self.path))
        self.send(404, {"message": "not implemented in mock"})


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    print(f"mock Grimmory on http://0.0.0.0:{port}  (emulator: http://10.0.2.2:{port})", flush=True)
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
