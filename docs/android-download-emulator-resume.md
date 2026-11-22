# DL-01–DL-06 Test Run — emulator-only continuation

Resume after the recorded phone and hosted results. **Run the missing UI intake checks below, not the batch. No physical device, USB ADB, or action by the owner.** The old physical-lab instructions in this ZIP are historical for this round. Use the same fresh disposable Android emulator for both cases. Skip a failing prerequisite; never switch to a handset or reuse an existing profile.

| Checkpoint | This round |
| --- | --- |
| DL-01, DL-02, DL-04 | Keep the existing upgrade, publication cuts, queue/service, Open/Share and grant results. Do not rerun them. Removable volume, ordinary-UID hard link and real low-battery/charging are not part of this round. |
| DL-03-M | **First:** actually paste into **Downloads → Add Download** and verify anonymous probe and body independently of `ACTION_SEND`. |
| DL-05 | Keep `eac5045` hosted trusted iframe input/refusal at 320 and 560 dpi and independent source switch. Stop the phone retests. No provider downgrade or simulated missing capability. |
| DL-06-M | **Next, independently:** one Downloads UI query-only HLS submission on a native ARM64 AVD if available. Record the exact wire path, completion, and whether its completed file opens and plays. Keep the four original native phone passes; do not rerun them. |

## 1. Guard the emulator and build

Verify the attached ZIP SHA-256 from the handoff. Extract into fresh scratch; use JDK 17, SDK Platform 34 and Build Tools 34.0.0. Use an **empty, disposable API 34 AVD** on the tester's machine, preferably native ARM64. If a compatible AVD cannot be started without owner involvement, report `BLOCKED` and stop. Do not change a default browser, contact a handset, use a shared ADB target or bypass a preflight. A usable x86_64 AVD advertising `arm64-v8a` translation may run **DL-03-M only**; the unmodified APK bundles ARM64 libraries.

Set `serial` to the emulator serial and `avd` to the name of that scratch AVD. Use the default local ADB server. Target the emulator explicitly for **every** ADB call; do not run `adb usb`, `adb connect`, or `adb kill-server`. The guarded Bash setup below builds only the debug app. Stop on any nonzero exit.

```bash
(
  set -euo pipefail
  : "${ANDROID_HOME:?}" "${JAVA_HOME:?}" "${serial:?}" "${avd:?}"
  [[ "$serial" =~ ^emulator-[0-9]+$ ]]
  [[ -z "${ADB_SERVER_SOCKET:-}${ANDROID_ADB_SERVER_ADDRESS:-}${ANDROID_ADB_SERVER_PORT:-}${ADB_SERVER_PORT:-}" ]]
  d() { "$ANDROID_HOME/platform-tools/adb" -s "$serial" "$@"; }
  prop() { d shell getprop "$1" | tr -d '\r\n'; }
  [[ "$(d get-state)" == device ]]
  [[ "$(d emu avd name | tr -d '\r' | sed -n '1p')" == "$avd" ]]
  [[ "$(prop ro.kernel.qemu)" == 1 || "$(prop ro.boot.qemu)" == 1 ]]
  [[ "$(prop sys.boot_completed)" == 1 && "$(prop ro.build.version.sdk)" == 34 ]]
  [[ ",$(prop ro.product.cpu.abilist)," == *,arm64-v8a,* ]]
  [[ -z "$(d shell pm list packages -u --user 0 com.browser | tr -d '\r\n')" ]]
  printf 'Primary ABI: %s\n' "$(prop ro.product.cpu.abi)"
  shasum -a 256 -c docs/native-arm64.sha256 docs/release-assets-build-inputs.sha256
  ./gradlew --no-daemon :app:assembleDebug
  apk=app/build/outputs/apk/debug/app-debug.apk
  shasum -a 256 "$apk"
  d install --no-replace --user 0 "$apk"
)
```

Record the locally built APK hash, API, primary ABI, and WebView (`dumpsys webviewupdate`, current package/version only). This run uses the production UI of a debug APK; no instrumentation APK or retained profile is needed. Use emulator controls or target-pinned UI automation. Do not modify product code during the run.

## 2. Serve the existing synthetic fixtures

Use Python 3.11+ from the extracted source root. This adds a tiny local page for copying the fixture link and for proving the browser has the cookie before the paste. It does not change the app. `10.0.2.2` is the AVD's alias for the host loopback listener. **No ADB reverse.** First start without media dependencies. Restart with `1` in place of `0` only for the eligible DL-06-M case, after saving the DL-03-M request evidence.

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
    print(f'AVD: http://10.0.2.2:8024; DL-03-M expected: {len(BODY)} bytes SHA-256 {sha256(BODY).hexdigest()}', flush=True)
    try:
        Event().wait()
    except KeyboardInterrupt:
        pass
PY
```

Read only the fixture's redacted request records on the host. In another host shell, use `curl -fsS 'http://127.0.0.1:8024/control/requests?/session/external.bin'` (replace the path as needed; quote the URL). Do not curl the tested download/HLS routes themselves: that would contaminate the evidence. For HLS use `/control/requests` with no query and select the three exact fixture routes from the JSON. If the loopback port is in use, fail closed; do not kill an unrelated server.

## 3. Run the two independent checks

### DL-03-M — clipboard paste, anonymous download

1. In a regular Browser tab on the AVD, visit `http://10.0.2.2:8024/start`, then `http://10.0.2.2:8024/session/paste-ready`. Require an HTTP 200 for the latter with `fixture_cookie: true`. Confirm no `/session/external.bin` requests before submission. Long-press the first link and choose **Copy Link Address**; do not open/download the page link.
2. In **Downloads → Add Download**, use the dialog's **Paste** control and verify the field reads `http://10.0.2.2:8024/session/external.bin`. Do not type the URL into the dialog or use `ACTION_SEND`. Continue, choose **one connection**, name the file `dl03-paste.csv`, and confirm once. Record the actual UI path.
3. Require the pasted URL's real GET probe (206, bytes `0–0`, no `If-Range`) and conditional body (206, `0–118795`, `If-Range`) with `cookie`, `referer` and `auth` all false on both. A matching page or metadata request is not a body pass. Require **Completed** and the exact fixture file size/SHA printed by the server (export/read only the named synthetic file, if needed). If it does not complete within 60 seconds, record the last observed state; do not restart the batch.

### DL-06-M — query-only HLS from Downloads UI

Run this even if DL-03-M fails, **only** on an AVD with primary ABI `arm64-v8a` and a working host FFmpeg (`libx264`/AAC) and OpenSSL. No translated native rerun; mark unavailable prerequisites `BLOCKED`. Restart the fixture with media enabled (argument `1`), open `/session/paste-ready`, copy the second link and add it via **Downloads → Add Download → Paste → Continue**. Verify exactly `http://10.0.2.2:8024/query-only/clip.m3u8?part=master` before confirming once as `dl06-query.mp4`.

Record separate outcomes for:

- **UI and wire:** actual Add Download submission; GET results for `/query-only/clip.m3u8?part=master`, `?part=media%2Flist` and `?part=media%2Fsegment` on the **same path**. Require successful body responses from the correct routes; keep actual HTTP codes. Allow metadata requests but do not count them as media fetches.
- **Completion:** terminal Completed MP4 and bytes/SHA, or last state and the exact error after at most 90 seconds. Check both tracks when completed. A wire pass is not a mux pass. Don't play or export an incomplete file.
- **Open File / playback:** use the completed row's actual **Open File** menu and a local offline player on the AVD if available. Record which player received it, and actual motion/audio only if observed. If no viewer or no way for the tester to observe audio/video without the owner, record that part as `BLOCKED`. Do not substitute host playback or infer tone from a track listing. Any successful result here is emulator evidence, not the prior phone playback matrix or release.

## 4. Return and stop

Return one `downloads-emulator-resume.md`: source commit/tree/ZIP SHA-256, APK SHA-256, API/ABI/WebView, per-case result and short evidence (clipboard action, redacted wire rows, file size/SHA and viewer result or precise blocker). Note that no physical phone was used. Stop the locally started loopback fixtures and only the scratch AVD you started. Keep the rest of DL-01–DL-06 and later topics untouched. Do not ask the owner to troubleshoot or touch a phone, or turn setup errors into extra test rounds.
