#!/usr/bin/env python3
"""Generate tiny playable HLS cases for testing the signed APK; loopback only."""
import argparse
from functools import partial
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import re
import struct
import subprocess
import tempfile

PORT = 8018
HLS = "application/vnd.apple.mpegurl"
AUDIO_GROUP = '#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="Tone",DEFAULT=YES,AUTOSELECT=YES'


def master(video, audio=None):
    rendition = f',URI="{audio}"' if audio else ""
    return (f'#EXTM3U\n#EXT-X-VERSION:7\n{AUDIO_GROUP}{rendition}\n'
            f'#EXT-X-STREAM-INF:BANDWIDTH=300000,RESOLUTION=160x96,AUDIO="a"\n{video}\n')


def fragmented(track, data):
    offset = 0
    while offset + 8 <= len(data):
        size, kind = struct.unpack_from(">I4s", data, offset)
        if size < 8 or size > len(data) - offset:
            break
        if kind == b"moof" and offset > 0:
            return (f'#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:2\n'
                    f'#EXT-X-MAP:URI="{track}",BYTERANGE="{offset}@0"\n'
                    f'#EXTINF:2,\n#EXT-X-BYTERANGE:{len(data) - offset}@{offset}\n{track}\n#EXT-X-ENDLIST\n')
        offset += size
    raise ValueError(f"{track}: generated MP4 has no usable first fragment")


def build_assets(ffmpeg):
    def run(*args):
        subprocess.run([ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", *args], check=True, timeout=30)

    with tempfile.TemporaryDirectory(prefix="release-mux-") as temp:
        video, audio, transport = (Path(temp) / name for name in ("video.mp4", "audio.mp4", "av.ts"))
        flags = "+frag_keyframe+empty_moov"
        run("-f", "lavfi", "-i", "testsrc2=size=160x96:rate=10", "-t", "2", "-c:v", "libx264",
            "-pix_fmt", "yuv420p", "-preset", "ultrafast", "-movflags", flags, str(video))
        run("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "2", "-c:a", "aac",
            "-movflags", flags, str(audio))
        run("-i", str(video), "-i", str(audio), "-map", "0:v:0", "-map", "1:a:0",
            "-c", "copy", "-f", "mpegts", str(transport))
        run("-xerror", "-i", str(transport), "-map", "0:v:0", "-map", "0:a:0", "-f", "null", "-")
        v, a, ts = (p.read_bytes() for p in (video, audio, transport))

    playlists = {
        "mux-ts.m3u8": master("ts-media.m3u8"),
        "mux-fragments.m3u8": master("video.m3u8", "audio.m3u8"),
        # The declared audio rendition downloads valid bytes, but they contain no audio stream.
        "mux-missing-audio.m3u8": master("video.m3u8", "video.m3u8"),
        "ts-media.m3u8": "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\nav.ts\n#EXT-X-ENDLIST\n",
        "video.m3u8": fragmented("video.mp4", v),
        "audio.m3u8": fragmented("audio.mp4", a),
    }
    page = "<!doctype html><meta charset=utf-8><title>Release mux fixture</title><h1>Release mux fixture</h1><ul>"
    for name, label in (("mux-ts.m3u8", "H.264/AAC transport stream"),
                        ("mux-fragments.m3u8", "Ranged fMP4 with separate H.264/AAC renditions"),
                        ("mux-missing-audio.m3u8", "Must fail: required audio missing")):
        page += f'<li><a href="/{name}">{label}</a></li>'
    assets = {f"/{name}": (HLS, text.encode()) for name, text in playlists.items()}
    return {**assets, "/": ("text/html; charset=utf-8", (page + "</ul>").encode()),
            "/video.mp4": ("video/mp4", v), "/audio.mp4": ("audio/mp4", a), "/av.ts": ("video/mp2t", ts)}


class Fixture(BaseHTTPRequestHandler):
    def __init__(self, *args, assets, **kwargs):
        self.assets = assets
        super().__init__(*args, **kwargs)

    def log_message(self, *_args):
        pass  # Only log fixture routes and validated numeric ranges below.

    def do_HEAD(self):
        self.respond(False)

    def do_GET(self):
        self.respond(True)

    def respond(self, send_body):
        if self.path not in self.assets:
            self.send_error(404)
            return
        mime, data = self.assets[self.path]
        byte_range = self.headers.get("Range")
        start, end, status = 0, len(data) - 1, 200
        if byte_range is not None:
            match = re.fullmatch(r"bytes=([0-9]{1,8})-([0-9]{0,8})", byte_range)
            if match:
                start = int(match[1])
                end = min(end, int(match[2])) if match[2] else end
            if not match or start > end:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{len(data)}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            status = 206
        self.send_response(status)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(end - start + 1))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Cache-Control", "no-store")
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{len(data)}")
        self.end_headers()
        print(f"{self.command} {self.path} {status}" + (f" {start}-{end}" if status == 206 else ""), flush=True)
        if send_body:
            try:
                self.wfile.write(data[start:end + 1])
            except (BrokenPipeError, ConnectionResetError):
                pass  # A cancelled transfer or metadata-only request.


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ffmpeg", default="ffmpeg", help="host FFmpeg with libx264 and AAC (not the APK library)")
    parser.add_argument("--check", action="store_true", help="generate and check media without starting a server")
    args = parser.parse_args()
    assets = build_assets(args.ffmpeg)
    if args.check:
        print(f"Generated {len(assets)} synthetic routes; host H.264/AAC TS decode passed. No Android code ran.")
        return
    with ThreadingHTTPServer(("127.0.0.1", PORT), partial(Fixture, assets=assets)) as server:
        print(f"Synthetic release mux fixture: http://127.0.0.1:{PORT}/", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
