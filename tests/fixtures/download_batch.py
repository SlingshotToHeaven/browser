"""Loopback fixtures for the disposable Android download batch; never log header values."""
from base64 import b64decode
from contextlib import ExitStack, contextmanager
from hashlib import sha256
from http.server import ThreadingHTTPServer
import json
import re
import subprocess
from threading import Event, Lock, Thread
import time
from urllib.parse import urlsplit

import comic_archives
import release_mux
import scoped_downloads

PRIMARY, FOREIGN = 8024, 8025
# A favicon 404 can disturb WebView after the child page has already loaded.
FRAME_ICON = "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"
FRAME_ICON_LINK = f'<link rel="icon" href="data:image/gif;base64,{FRAME_ICON}">'
FRAME_INPUT = frozenset(f"/frame-input/{scope}/{event}" for scope in ("top", "child") for event in ("pointer", "click"))
LENGTH, PREFIX = 294_917, 65_536
BODY = b"d" * LENGTH
DIGEST = sha256(BODY).hexdigest()
ETAG = '"download-batch-1"'
HLS = release_mux.HLS
JOB = r"[a-z][a-z0-9-]{0,48}\.bin"


def playlist(segment):
    return f"#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\n{segment}\n#EXT-X-ENDLIST\n".encode()


STATIC_MEDIA = {
    "/invalid.m3u8": (HLS, playlist("/invalid.ts")),
    "/invalid.ts": ("video/mp2t", b"<!DOCTYPE html><html><body>Fixture error</body></html>"),
    "/broken-mux.m3u8": (HLS, playlist("/broken-mux.ts")),
    # Looks like TS at the sniff boundary but carries no elementary streams.
    "/broken-mux.ts": ("video/mp2t", (b"\x47\x1f\xff\x10" + b"\xff" * 184) * 32),
    "/unsupported.mpd": ("application/dash+xml", b"<MPD/>"),
}


QUERY_HLS_PATH = "/query-only/clip.m3u8"
QUERY_HLS = QUERY_HLS_PATH + "?part=master"
QUERY_MEDIA = QUERY_HLS_PATH + "?part=media%2Flist"
QUERY_SEGMENT = QUERY_HLS_PATH + "?part=media%2Fsegment"


def media_assets(ffmpeg):
    assets = release_mux.build_assets(ffmpeg)
    transport = assets["/av.ts"][1]
    key, iv = bytes(range(16)), bytes(range(16, 32))
    cipher = subprocess.run(["openssl", "enc", "-aes-128-cbc", "-K", key.hex(), "-iv", iv.hex()],
                            input=transport, check=True, capture_output=True, timeout=10).stdout
    encrypted = playlist("/encrypted.ts").replace(b"#EXTINF", (f'#EXT-X-KEY:METHOD=AES-128,URI="/fixture.key",IV=0x{iv.hex()}\n#EXTINF').encode())
    assets.update({"/encrypted.m3u8": (HLS, encrypted), "/encrypted.ts": ("video/mp2t", cipher),
                   "/fixture.key": ("application/octet-stream", key),
                   "/short.m3u8": (HLS, playlist("/short.ts")), "/short.ts": ("video/mp2t", transport),
                   QUERY_HLS: (HLS, release_mux.master("?part=media%2Flist").encode()),
                   QUERY_MEDIA: (HLS, playlist("?part=media%2Fsegment")),
                   QUERY_SEGMENT: ("video/mp2t", transport)})
    return assets


class State:
    def __init__(self, assets=None):
        self.lock, self.events, self.gates = Lock(), [], {}
        self.assets = STATIC_MEDIA | (assets or {})

    def gate(self, name, action):
        with self.lock:
            if action == "arm":
                if name in self.gates:
                    return False
                self.gates[name] = Event()
            elif name in self.gates:
                self.gates[name].set()
            else:
                return False
            return True

    def reset(self):
        with self.lock:
            for event in self.gates.values():
                event.set()
            self.gates.clear()
            self.events.clear()

    def add(self, entry):
        with self.lock:
            if len(self.events) < 4096:
                self.events.append(entry)

    def requests(self, path=None):
        with self.lock:
            return [entry for entry in self.events if path is None or entry["path"] == path]


class Fixture(scoped_downloads.Fixture):
    def do_GET(self):
        path = urlsplit(self.path).path
        state = self.server.fixture
        if path == "/control/requests":
            # Keep the device-side query bounded and select a single static fixture route.
            query = urlsplit(self.path).query
            self.reply(200, json.dumps(state.requests(query or None)[-120:]).encode(), "application/json")
            return
        command = re.fullmatch(r"/control/(arm|release)/(%s)" % JOB, path)
        if command:
            self.reply(200 if state.gate(command[2], command[1]) else 409, b"{}", "application/json")
            return
        job = re.fullmatch(r"/range/(%s)" % JOB, path)
        if job:
            self.range_job(path, job[1])
            return
        primary = self.server.server_port == self.server.primary_port
        if path == "/favicon.ico":
            self.reply(200, b64decode(FRAME_ICON), "image/gif")
        elif not primary and path in FRAME_INPUT and self.path == path:
            self.respond(path, 204, b"", [])
        elif primary and path == "/start":
            page = scoped_downloads.PAGE.replace(b"<title>", b'<meta name="viewport" content="width=device-width, initial-scale=1"><title>')
            self.respond(path, 200, page, [("Content-Type", "text/html; charset=utf-8"),
                         ("Set-Cookie", scoped_downloads.COOKIE + "; Path=/session/; SameSite=Lax")])
        elif primary and path == "/frames":
            origin = f"http://127.0.0.1:{self.server.other_port}"
            self.html(path, FRAME_ICON_LINK + f'''<style>body{{margin:0}}
                      #foreign-blob{{display:block;width:100%;height:220px;border:0}}</style>
                      <script>addEventListener('message', e => {{
                        const frame = document.querySelector('#foreign-blob');
                        if(e.origin === '{origin}' && frame && e.source === frame.contentWindow && e.data === 'attachment-ready') {{
                          window.fixtureFrame = true;
                        }}
                      }})</script>
                      <iframe id="foreign-blob" title="Foreign fixture attachment" src="{origin}/foreign-frame"></iframe>''')
        elif not primary and path == "/foreign-frame":
            owner = f"http://127.0.0.1:{self.server.primary_port}"
            self.html(path, FRAME_ICON_LINK + f'''<style>html,body{{height:100%;margin:0}}
                      #offer{{display:grid;place-items:center;width:100%;height:100%;font-size:18px;
                              background:#fde047;color:#17212e;touch-action:manipulation}}</style>
                      <a id="offer" download="foreign.bin">Request frame blob</a><script>
                      const link = document.querySelector('#offer');
                      const mode = window.top === window ? 'top' : 'child';
                      function witness(event) {{ fetch('/frame-input/' + mode + '/' + event, {{keepalive:true}}).catch(() => {{}}); }}
                      link.href = URL.createObjectURL(new Blob(['foreign fixture'], {{type:'application/octet-stream'}}));
                      link.addEventListener('pointerdown', e => {{ if(e.isTrusted && e.pointerType === 'touch') witness('pointer'); }});
                      link.addEventListener('click', e => {{ if(e.isTrusted) witness('click'); }});
                      window.fixtureAttachment = true;
                      if(mode === 'child') parent.postMessage('attachment-ready', '{owner}');
                      </script>''')
        elif primary and path == "/session/external.bin":
            self.csv_response(path)
        elif primary and self.comic(path):
            pass
        elif primary and path == QUERY_HLS_PATH and self.path in state.assets:
            self.asset(self.path)
        elif primary and path in state.assets:
            self.asset(path)
        else:
            super().handle_request()

    def html(self, path, body, status=200):
        page = '<!doctype html><meta charset=utf-8><meta name="viewport" content="width=device-width, initial-scale=1">' + body
        self.respond(path, status, page.encode(), [("Content-Type", "text/html; charset=utf-8")])

    def comic(self, path):
        kind = path.removeprefix("/comic") if path.startswith("/comic/") else None
        if kind in comic_archives.SCENARIOS:
            pages = json.dumps(comic_archives.SCENARIOS[kind])
            self.html(path, f'<title>Batch comic</title><h1>Batch comic</h1><script>window.chapterImages={pages}</script>')
        elif path == "/reader/green":
            self.html(path, '<img class="reader-image" src="/images/green.png">')
        elif path in comic_archives.IMAGES:
            self.respond(path, 200, comic_archives.IMAGES[path], [("Content-Type", "image/png")])
        elif path == "/missing-page":
            self.respond(path, 404, b"", [])
        elif path == "/short-page":
            body = comic_archives.IMAGES[comic_archives.BLUE]
            self.record(path, 200)
            self.reply(200, body[:24], "image/png", declared=len(body))
        elif path == "/slow-page":
            body = comic_archives.IMAGES[comic_archives.BLUE]
            self.record(path, 200)
            try:
                self.reply(200, body[:24], "image/png", declared=len(body), stall=12)
            except OSError:
                pass
        elif path == "/gated-page":
            if comic_archives.VERIFIED in self.headers.get("Cookie", "").split("; "):
                self.respond(path, 200, comic_archives.IMAGES[comic_archives.BLUE], [("Content-Type", "image/png")])
            else:
                # The worker sees 403. A main-document browser navigation receives the
                # synthetic challenge as a renderable 200 page, not a provider error UI.
                navigation = (self.headers.get("Sec-Fetch-Mode") == "navigate"
                              or self.headers.get("Accept", "").startswith("text/html"))
                self.html(path, f'''<button id="verify" style="font-size:18px;padding:16px"
                          onclick="document.cookie='{comic_archives.VERIFIED}; Path=/gated-page; SameSite=Lax';this.disabled=true">Set fixture cookie</button>''',
                          200 if navigation else 403)
        else:
            return False
        return True

    def do_HEAD(self):
        path = urlsplit(self.path).path
        if path in self.server.fixture.assets:
            self.asset(path)
        else:
            super().handle_request()

    def record(self, path, code):
        ref = self.headers.get_all("Referer", [])
        cookies = self.headers.get_all("Cookie", [])
        values = self.headers.get_all("Range", [])
        exact = len(values) == 1 and re.fullmatch(r"bytes=([0-9]{1,9})-([0-9]{0,9})", values[0])
        markers = {part.strip() for value in cookies for part in value.split(';')}
        self.server.fixture.add(dict(path=path, port=self.server.server_port, method=self.command, code=code,
                                     cookie=bool(cookies), fixture_cookie=scoped_downloads.COOKIE in markers,
                                     verified_cookie=comic_archives.VERIFIED in markers,
                                     referer=bool(ref), origin_only=ref == [f"http://127.0.0.1:{self.server.primary_port}/"],
                                     auth="Authorization" in self.headers, conditional="If-Range" in self.headers,
                                     start=int(exact[1]) if exact else -1, end=int(exact[2]) if exact and exact[2] else -1))

    def respond(self, route, status, body, headers):
        self.record(route, status)
        mime = next((value for key, value in headers if key == "Content-Type"), "application/octet-stream")
        self.reply(status, body, mime, [(key, val) for key, val in headers if key != "Content-Type"])

    def reply(self, status, body, mime, headers=(), gate=None, declared=None, stall=0):
        self.send_response(status)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(body) if declared is None else declared))
        self.send_header("Cache-Control", "no-store")
        for key, value in headers:
            self.send_header(key, value)
        self.end_headers()
        if self.command == "HEAD":
            return
        try:
            if gate is not None and not gate.is_set() and len(body) > PREFIX:
                self.wfile.write(body[:PREFIX])
                self.wfile.flush()
                if not gate.wait(16):
                    return
                body = body[PREFIX:]
            self.wfile.write(body)
            if stall:
                self.wfile.flush()
                time.sleep(stall)
        except (BrokenPipeError, ConnectionResetError):
            pass  # The real app interrupted a synthetic transfer.

    def range_job(self, path, name):
        probe = self.headers.get_all("Range", []) == ["bytes=0-0"] and "If-Range" not in self.headers
        suffix = re.fullmatch(r"bytes=(\d{1,9})-(\d{1,9})", self.headers.get("Range", ""))
        if probe:
            first, last = 0, 0
        elif suffix and int(suffix[1]) < LENGTH and int(suffix[2]) == LENGTH - 1 and self.headers.get_all("If-Range", []) == [ETAG]:
            first, last = int(suffix[1]), LENGTH - 1
        else:
            self.respond(path, 416, b"", [("Content-Range", f"bytes */{LENGTH}")])
            return
        self.record(path, 206)
        with self.server.fixture.lock:
            gate = self.server.fixture.gates.get(name)
        self.reply(206, BODY[first:last + 1], "application/octet-stream",
                   [("ETag", ETAG), ("Accept-Ranges", "bytes"), ("Content-Range", f"bytes {first}-{last}/{LENGTH}")], gate)

    def asset(self, path):
        mime, body = self.server.fixture.assets[path]
        if path == "/short.ts":
            self.record(path, 200)
            self.reply(200, body[:63], mime, declared=len(body))
        elif "Range" in self.headers:
            match = re.fullmatch(r"bytes=(\d{1,9})-(\d{0,9})", self.headers["Range"])
            if not match or int(match[1]) >= len(body) or (match[2] and int(match[2]) < int(match[1])):
                self.respond(path, 416, b"", [("Content-Range", f"bytes */{len(body)}")])
                return
            first, last = int(match[1]), min(int(match[2]) if match[2] else len(body) - 1, len(body) - 1)
            self.respond(path, 206, body[first:last + 1], [("Content-Type", mime), ("Content-Range", f"bytes {first}-{last}/{len(body)}")])
        else:
            self.respond(path, 200, body, [("Content-Type", mime)])


@contextmanager
def servers(assets=None, ports=(PRIMARY, FOREIGN)):
    state = State(assets)
    with ExitStack() as stack:
        endpoints = [stack.enter_context(ThreadingHTTPServer(("127.0.0.1", port), Fixture)) for port in ports]
        for server in endpoints:
            server.fixture = state
            server.primary_port, server.other_port = [e.server_port for e in endpoints]
        threads = [Thread(target=e.serve_forever, daemon=True) for e in endpoints]
        for thread in threads:
            thread.start()
        try:
            yield state, endpoints
        finally:
            state.reset()
            for server in endpoints:
                server.shutdown()
            for thread in threads:
                thread.join(timeout=3)
