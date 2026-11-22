# DL-01–DL-06 Test Run — wireless continuation

**Returned and reconciled.** Do not repeat this paste/HLS round. The [next wireless blob handoff](android-download-wireless-blob-recheck.md) reuses only the guard in Section 1.

Resume from the matched check-only receipt on the **already-connected Galaxy S21 FE**, over **wireless ADB only**. Run the unresolved UI intake checks, not the batch. Use the original installed browser and its synthetic Downloads profile. No builds, APK replacements, profile resets, connection changes, or action by the owner. Stop if the existing connection, verified lab app, or remote UI is unavailable.

| Checkpoint | This round |
| --- | --- |
| DL-01, DL-02, DL-04 | Keep the existing upgrade, publication cuts, queue/service, Open/Share and grant results. Do not rerun them. Removable storage, ordinary-UID hard link and the real charging edge remain outside this round. |
| DL-03-M | First: actual **Downloads → Add Download → Paste** with separate anonymous probe and body evidence. |
| DL-05 | Preserve the seven original lifecycle passes and the hosted frame/source-switch results. No test APK replacement, reset or provider change for these cases in this handoff. |
| DL-06-M | Independently: submit one query-only HLS job through the actual Downloads UI and record the exact requests, MP4 completion and Open File/playback where observable over the existing remote controls. No rerun of the four native positives. |

## 1. Select the retained target without changing it

Verify the ZIP digest from the prompt and extract it into scratch for the fixtures. Nothing in the ZIP needs to be installed. Select `serial` from the **existing wireless ADB connection** in `adb devices -l`; never pair, reconnect, or restart ADB as part of this test. Keep its serial and address out of the report. Set `adb` to the local Platform Tools executable. Use Bash and pin **every** device command to that serial. Continue only if the one-time guard passes. This is not a rerun of the old preflight or a new instrumentation build.

```bash
d() { "$adb" -s "$serial" "$@"; }
(
  set -euo pipefail
  : "${adb:?}" "${serial:?}"
  [[ -z "${ADB_SERVER_SOCKET:-}${ANDROID_ADB_SERVER_ADDRESS:-}${ANDROID_ADB_SERVER_PORT:-}${ADB_SERVER_PORT:-}" ]]
  [[ "$serial" =~ :[0-9]+$ || "$serial" =~ _adb-tls-connect\._tcp\.?$ ]]
  p() { d shell "$@" | tr -d '\r'; }
  [[ "$(d get-state)" == device && "$(p am get-current-user)" == 0 ]]
  [[ "$(p getprop ro.kernel.qemu)" =~ ^(0)?$ && "$(p getprop ro.boot.qemu)" =~ ^(0)?$ ]]
  [[ "$(p getprop ro.product.model)" == SM-G990E && "$(p getprop ro.build.version.sdk)" == 36 ]]
  [[ "$(p getprop ro.product.cpu.abi)" == arm64-v8a && "$(p getconf PAGE_SIZE)" == 4096 ]]
  webview=$(p dumpsys webviewupdate)
  [[ "$webview" == *'Current WebView package (name, version): (com.google.android.webview, 137.0.7151.115)'* ]]
  assert_apk() {
    local package=$1 expect=$2 path row got got_path rest
    row=$(p pm path --user 0 "$package")
    [[ "$row" == package:/data/app/*/base.apk && "$row" != *[[:space:]]* ]]
    path=${row#package:}
    row=$(p run-as "$package" sha256sum "$path")
    read -r got got_path rest <<< "$row"
    [[ "$got" == "$expect" && "$got_path" == "$path" && -z "$rest" ]]
  }
  assert_apk com.browser 60be1238af1b9725930d2d9ccbe562453c0a6ddd7b002710b9fa911a091dd391
  assert_apk com.browser.test 5fd0954b86c93cab5d7b08432519282790bc6e6060e869fca547d42af9c9b7ad
  d shell run-as com.browser test -f files/download-device-lab.token
  d shell run-as com.browser test '!' -L files/download-device-lab.token
  [[ "$(p run-as com.browser stat -c %s files/download-device-lab.token)" == 32 ]]
  printf 'Retained Downloads target confirmed.\n'
)
```

Use target-pinned remote UI actions only in the verified lab Browser. Confirm the visible control before sending input. The Add Download controls are `com.browser:id/btn_add_download`, `com.browser:id/add_url_btn_paste`, and `com.browser:id/add_url_btn_continue`. If the phone is locked, on a different profile, or a chosen control cannot be reached remotely, record `BLOCKED`; do not ask the owner to intervene. Don't change permissions, defaults, or unrelated data. Keep only synthetic evidence in the report.

## 2. Start the loopback fixtures

From the extracted source root, run the existing Python 3.11+ fixture in a host terminal. Start without media. Use `1` instead of `0` for the second case after saving the first case's evidence. Media needs an existing FFmpeg with `libx264` and AAC, and OpenSSL. A missing media generator must not block the paste case.

```bash
PYTHONPATH=tests/fixtures python3 -B - 0 <<'PY'
from hashlib import sha256
import sys
from threading import Event
from download_batch import QUERY_HLS, media_assets, servers
from scoped_downloads import BODY

assets = media_assets('ffmpeg') if sys.argv[1] == '1' else {}
assets['/session/paste-ready'] = ('text/html; charset=utf-8', f'''<!doctype html>
<meta name="viewport" content="width=device-width, initial-scale=1">
<h1>Downloads UI fixtures</h1>
<p><a href="/session/external.bin">Copy the synthetic paste-test link</a></p>
<p><a href="{QUERY_HLS}">Copy the query-only HLS link</a></p>
'''.encode())
with servers(assets):
    print(f'Loopback ready; DL-03-M expected: {len(BODY)} bytes SHA-256 {sha256(BODY).hexdigest()}', flush=True)
    try:
        Event().wait()
    except KeyboardInterrupt:
        pass
PY
```

Add only `d reverse --no-rebind tcp:8024 tcp:8024` on the guarded target. All phone fixture URLs are `http://127.0.0.1:8024/...`. Verify the phone's first fixture page reaches the host before testing. If the reverse is refused, a port is occupied, or the page cannot reach the host, mark the dependent checks `BLOCKED`; don't take over an existing mapping or expose the server to the network. Keep the one owned reverse while switching the fixture to media mode. At the end remove only this mapping with `d reverse --remove tcp:8024` if this run created it and it is still the same mapping, and stop only this fixture process.

Use `curl -fsS 'http://127.0.0.1:8024/control/requests?/session/external.bin'` on the host to read redacted wire rows. For the cookie control and HLS, select the corresponding paths from `/control/requests` JSON. Do not issue test-route requests from the host for these observations.

## 3. Run two independent UI checks

### DL-03-M — clipboard paste, anonymous download

1. Remotely open a new regular tab in the lab Browser and visit `/start`, then `/session/paste-ready`. Require a real page GET with `fixture_cookie: true` on the latter and no `/session/external.bin` requests before Add Download. Long-press the first fixture link and select **Copy Link Address**; do not navigate to it or use the link-download action.
2. Open **Downloads → Add Download → Paste** and verify the field shows `http://127.0.0.1:8024/session/external.bin`. Continue, choose **one connection**, and confirm once with a new synthetic name (e.g. `dl03-paste.csv`; use a unique suffix if occupied). Do not overwrite an existing file, type in place of Paste, or substitute `ACTION_SEND`.
3. Require an actual anonymous GET probe (206, `0–0`, no `If-Range`) and conditional GET body (206, `0–118795`, with `If-Range`), with `cookie`, `referer`, and `auth` false on both. Require **Completed** and the fixture's exact 118,796 bytes/SHA-256. Read/hash only the exact new synthetic file if its path is known. At 60 seconds report the actual last state and any missing evidence. Do not alter queue settings or resubmit to force a pass.

### DL-06-M — query-only HLS from Downloads UI

Run independently if the target and fixture controls are still usable. After saving the first case's evidence, stop the first host fixture and start it in media mode. Copy the second link on `/session/paste-ready`, then use **Downloads → Add Download → Paste → Continue**. Require `http://127.0.0.1:8024/query-only/clip.m3u8?part=master` in the field. Confirm once with a new synthetic MP4 filename. Check these as separate outcomes:

- **UI and wire:** record the UI input and successful GET replies for master, `?part=media%2Flist`, and `?part=media%2Fsegment`, each on `/query-only/clip.m3u8`. Metadata requests do not substitute for media GETs.
- **Completion:** record the actual Completed MP4, bytes/SHA and both tracks if observable, or the last state and error after 90 seconds. Wire success is not a mux pass. Do not open or export an incomplete file.
- **Open File / playback:** use the completed row's actual **Open File** menu with an already available local offline player. Record the player and motion/audio only when proven over the existing remote observation setup. If the viewer or motion/audio cannot be observed remotely, mark that part `BLOCKED`; do not substitute host playback or infer audible tone from a track listing. No owner interaction or additional viewer install.

## 4. Return and stop

Return one `downloads-wireless-resume.md` with source commit/tree/ZIP SHA-256, the verified installed APK hash, API/ABI/WebView, per-case UI and redacted wire results, new synthetic file identities and any exact blocker. Say whether your reverse was removed and the host fixture stopped. Do not return device addresses, serials, personal data, raw dumps, or APKs. No changes to the original browser/test APKs or prior results. Do not restart the DL-01–DL-06 batch or advance to another topic.
