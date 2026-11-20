"""Two loopback origins for one synthetic attachment and input delivery checks."""
from contextlib import ExitStack, contextmanager
from http.server import ThreadingHTTPServer
import json
from threading import Thread
from urllib.parse import urlsplit

from browser_recovery import Handler as Base, State

PORTS = (8027, 8028)
ACKS = frozenset(f"{phase}-{mode}" for phase in ("ready", "down", "tap") for mode in ("top", "frame"))
HTML = '<!doctype html><meta name="viewport" content="width=device-width, initial-scale=1">'
ATTACHMENT = HTML + """<title>Synthetic attachment</title><style>
body{margin:0}#offer{display:grid;place-items:center;width:100%;height:180px;background:#fde047;color:black}
</style><a id="offer" download="fixture.bin">Download synthetic attachment</a><script>
const link = document.getElementById('offer');
const mode = window.top === window ? 'top' : 'frame';
function ack(phase) { fetch('/ack/' + phase + '-' + mode, {keepalive:true}).catch(() => {}); }
link.href = URL.createObjectURL(new Blob(['synthetic attachment bytes'], {type:'application/octet-stream'}));
link.addEventListener('touchstart', e => { if(e.isTrusted) ack('down'); }, {passive:true});
link.addEventListener('click', e => { if(e.isTrusted) ack('tap'); });
ack('ready');
</script>"""


class Handler(Base):
    def route(self):
        if self.command != "GET" or self.headers.get("Host") != f"127.0.0.1:{self.server.server_port}":
            self.reply(400)
            return
        path = urlsplit(self.path).path
        state = self.server.fixture
        if self.server.is_child:
            if path == "/offer":
                state.record("child", "GET", path, False)
                self.reply(200, ATTACHMENT.encode(), "text/html; charset=utf-8")
                return
            if path.startswith("/ack/") and path[5:] in ACKS:
                with state.lock:
                    state.counts[path[5:]] += 1
                self.reply(204)
                return
        elif path == "/parent":
            state.record("parent", "GET", path, False)
            body = (HTML + '<title>Attachment frame fixture</title><style>body{margin:0}</style>'
                    '<iframe id="child" title="Synthetic attachment frame" style="display:block;width:100%;height:180px;border:0"'
                    f' src="http://127.0.0.1:{self.server.child_port}/offer"></iframe>')
            self.reply(200, body.encode(), "text/html; charset=utf-8")
            return
        elif path == "/counts":
            self.reply(200, json.dumps(state.snapshot()).encode(), "application/json")
            return
        self.reply(404)


@contextmanager
def serve(ports=PORTS):
    state = State()
    with ExitStack() as stack:
        parent, child = [stack.enter_context(ThreadingHTTPServer(("127.0.0.1", port), Handler)) for port in ports]
        for server in (parent, child):
            server.fixture = state
            server.is_child = server is child
            server.child_port = child.server_port
            thread = Thread(target=server.serve_forever, daemon=True)
            thread.start()
            stack.callback(thread.join)
            stack.callback(server.shutdown)
        yield state, (parent.server_port, child.server_port)
