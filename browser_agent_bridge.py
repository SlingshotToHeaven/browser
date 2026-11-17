#!/usr/bin/env python3
# ==============================================================================
# Privacy Browser - Termux Antigravity Agent Sidecar Bridge
# ==============================================================================
# This bridge runs inside the Termux Linux environment on Android.
# It connects Privacy Browser's autonomous Agent to:
#  1. The Antigravity CLI (agy) using your Google AI Pro / Antigravity quota.
#  2. Installed Termux programs (python, curl, sqlite3, ffmpeg, git, etc.).
# ==============================================================================

import os
import sys
import json
import time
import glob
import signal
import base64
import shutil
import tempfile
import threading
import subprocess
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

PORT = int(os.environ.get("PORT", 8080))
HOST = "127.0.0.1"

COMMON_TOOLS = ["python", "python3", "curl", "sqlite3", "git", "ffmpeg", "bash", "jq", "tar"]

def terminate_stale_bridges():
    """Terminate previous instances of this bridge running on the device."""
    my_pid = os.getpid()
    for path in glob.glob('/proc/[0-9]*/cmdline'):
        try:
            pid = int(path.split('/')[2])
            if pid == my_pid:
                continue
            with open(path, 'rb') as f:
                cmd = f.read().decode('utf-8', errors='ignore')
                if 'browser_agent_bridge.py' in cmd or 'antigravity_bridge.py' in cmd or 'ant.py' in cmd:
                    print(f"[BRIDGE] Terminating stale bridge process (PID {pid})...")
                    os.kill(pid, signal.SIGKILL)
        except Exception:
            pass
    time.sleep(0.3)

class ReusableServer(ThreadingHTTPServer):
    allow_reuse_address = True
    daemon_threads = True

class AgentBridgeHandler(BaseHTTPRequestHandler):
    def address_string(self):
        return str(self.client_address[0])

    def _send_json(self, status_code, data):
        try:
            body = json.dumps(data).encode('utf-8')
            self.send_response(status_code)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except BrokenPipeError:
            pass

    def do_GET(self):
        """Health-check ping and environment diagnostics."""
        installed = [t for t in COMMON_TOOLS if shutil.which(t) is not None]
        agy_path = shutil.which("agy")

        resp = {
            "status": "ok",
            "engine": "Privacy Browser AG Bridge",
            "version": "1.0",
            "port": PORT,
            "agy_installed": agy_path is not None,
            "agy_path": agy_path or "not_found",
            "termux_tools": installed,
            "supported_models": [
                "gemini-3.8-flash",
                "gemini-2.5-pro",
                "claude-sonnet-4-6",
                "claude-opus-4-6",
                "gpt-oss-120b-medium"
            ]
        }
        self._send_json(200, resp)

    def do_POST(self):
        """Handles /exec for Termux shell commands and / (or /chat) for Agent queries."""
        tid = threading.get_ident()
        path = self.path.split('?')[0].rstrip('/')

        length = int(self.headers.get('Content-Length', 0))
        raw_body = self.rfile.read(length).decode('utf-8')

        try:
            payload = json.loads(raw_body) if raw_body else {}
        except Exception as e:
            self._send_json(400, {"status": "error", "message": f"Malformed JSON: {str(e)}"})
            return

        if path == "/exec":
            self.handle_exec(tid, payload)
        else:
            self.handle_chat(tid, payload)

    def handle_exec(self, tid, payload):
        """Executes a command inside the Termux Linux environment."""
        command = payload.get("command", "").strip()
        timeout = int(payload.get("timeout", 30))

        if not command:
            self._send_json(400, {"status": "error", "message": "Missing 'command' parameter"})
            return

        print(f"[BRIDGE-{tid}] Running Termux command: {command} (timeout: {timeout}s)")
        try:
            # Execute command using bash if available, else standard shell
            shell_bin = shutil.which("bash") or "/system/bin/sh"
            proc = subprocess.Popen(
                command,
                shell=True,
                executable=shell_bin,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True
            )
            stdout, stderr = proc.communicate(timeout=timeout)

            print(f"[BRIDGE-{tid}] Command finished with exit code {proc.returncode}")
            self._send_json(200, {
                "status": "ok",
                "exit_code": proc.returncode,
                "stdout": stdout,
                "stderr": stderr
            })
        except subprocess.TimeoutExpired:
            proc.kill()
            print(f"[BRIDGE-{tid}] Command timed out after {timeout} seconds")
            self._send_json(408, {
                "status": "error",
                "message": f"Execution timed out after {timeout} seconds"
            })
        except Exception as e:
            print(f"[BRIDGE-{tid}] Command execution failed: {str(e)}")
            self._send_json(500, {
                "status": "error",
                "message": str(e)
            })

    def handle_chat(self, tid, payload):
        """Forwards prompt and optional vision screenshot to agy CLI."""
        system_prompt = payload.get("system", "")
        user_prompt = payload.get("prompt", "")
        image_b64 = payload.get("data", "")
        mime_type = payload.get("mime_type", "image/jpeg")
        model = payload.get("model", "")
        effort = payload.get("effort", "").strip()
        session_id = payload.get("session_id", "")

        # Default effort to 'low' for gemini-3.8-flash and thinking models
        if not effort and model:
            if "3.8" in model.lower() or "flash" in model.lower():
                effort = "low"

        temp_media_path = None
        try:
            if image_b64:
                ext = ".pdf" if "pdf" in mime_type else ".jpg"
                with tempfile.NamedTemporaryFile(delete=False, suffix=ext, prefix=f"agent_vision_{tid}_") as f_media:
                    f_media.write(base64.b64decode(image_b64))
                    temp_media_path = f_media.name

                full_prompt = (
                    f"{system_prompt}\n\n"
                    f"A screenshot of the current page viewport was captured and saved at: {temp_media_path}\n"
                    f"Please inspect it when evaluating UI elements or visual layout.\n\n"
                    f"{user_prompt}"
                )
            else:
                full_prompt = f"{system_prompt}\n\n{user_prompt}" if system_prompt else user_prompt

            cmd = ["agy", "--dangerously-skip-permissions"]
            if model and model.strip():
                cmd.extend(["--model", model.strip()])
            if effort:
                cmd.extend(["--effort", effort])
            if session_id and session_id.strip():
                cmd.extend(["--session", session_id.strip()])

            print(f"[BRIDGE-{tid}] Spawning agy ({len(full_prompt)} chars, model={model or 'default'}, effort={effort or 'none'})...")

            proc = subprocess.Popen(
                cmd,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True
            )

            try:
                stdout, stderr = proc.communicate(input=full_prompt, timeout=180)
            except subprocess.TimeoutExpired:
                proc.kill()
                raise TimeoutError("Antigravity process timed out after 180 seconds.")

            if proc.returncode != 0:
                err_msg = stderr.strip() if stderr else stdout.strip()
                # If agy complains about missing --effort, auto-retry with --effort low
                if "requires --effort" in err_msg and "--effort" not in cmd:
                    print(f"[BRIDGE-{tid}] Auto-retrying with --effort low...")
                    retry_cmd = list(cmd) + ["--effort", "low"]
                    retry_proc = subprocess.Popen(
                        retry_cmd,
                        stdin=subprocess.PIPE,
                        stdout=subprocess.PIPE,
                        stderr=subprocess.PIPE,
                        text=True
                    )
                    r_stdout, r_stderr = retry_proc.communicate(input=full_prompt, timeout=180)
                    if retry_proc.returncode == 0:
                        stdout = r_stdout
                        proc = retry_proc
                        err_msg = None
                    else:
                        err_msg = r_stderr.strip() if r_stderr else r_stdout.strip()
                if err_msg:
                    raise RuntimeError(f"agy exit code {proc.returncode}: {err_msg}")

            result = stdout.strip()
            print(f"[BRIDGE-{tid}] Completed successfully ({len(result)} chars).")

            self._send_json(200, {
                "status": "ok",
                "response": result
            })

        except Exception as e:
            err = str(e)
            print(f"[BRIDGE-{tid} ERROR] {err}")
            self._send_json(500, {
                "status": "error",
                "message": err
            })
        finally:
            if temp_media_path and os.path.exists(temp_media_path):
                try:
                    os.remove(temp_media_path)
                except Exception:
                    pass

def main():
    terminate_stale_bridges()

    server = None
    for attempt in range(5):
        try:
            server = ReusableServer((HOST, PORT), AgentBridgeHandler)
            break
        except OSError as e:
            if 'Address already in use' in str(e):
                terminate_stale_bridges()
                time.sleep(0.4)
            else:
                raise e

    if not server:
        print(f"[BRIDGE ERROR] Could not bind to {HOST}:{PORT}")
        sys.exit(1)

    print("=================================================================")
    print(f"Privacy Browser - Termux Agent Bridge listening on {HOST}:{PORT}")
    print("Health check endpoint:   GET  http://127.0.0.1:8080/")
    print("Chat / Agent endpoint:   POST http://127.0.0.1:8080/")
    print("Termux Program Exec:     POST http://127.0.0.1:8080/exec")
    print("=================================================================")

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[BRIDGE] Stopping bridge...")
        server.server_close()

if __name__ == "__main__":
    main()
