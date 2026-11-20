# Downloads and playback — one ARM64 device batch (DL-01–DL-06)

**Run once on a spare Android device.** This is the complete residual Downloads handoff, not a request to test browser recovery, the Assistant, comics, performance, or a release APK. Host checks verified the HLS query-only fix, but did not run the Android APK or its bundled JNI. A testing-branch push is not sign-off or promotion.

## 1. Verify and build the exact source

Use the commit-pinned **GitHub source ZIP** attached by the owner. It extracts to `browser-<source_commit>/`; it is **not** the earlier local bundle with `candidate/` and `step19/` directories. Do not apply a patch or build the branch tip or `main` in place of the pinned commit. Set `zip` to the attachment, and set `source_commit`, `candidate_tree`, and `zip_sha256` to the exact handoff values. Supply those same identities in the result.

The legacy v7 `step19` build is restored from the clean public base `bbd83546a0c719fe15ae47d4fedf5685b2a93d90`, not from the fixed candidate. The build block fetches that pinned baseline ZIP separately; the owner only needs to attach the candidate. It verifies the baseline ZIP and all 482 v7 build inputs. This restoration is for the installed Downloads upgrade, not a performance test.

Use a host with JDK 17 (`JAVA_HOME`), Python 3.11+, Android Platform 34 and Build Tools **34.0.0** in `ANDROID_HOME`, current `adb`, Git, OpenSSL and FFmpeg with `libx264` and AAC. Use Bash on macOS or Linux. The candidate and v7 must use the **same local debug key**; keep the fixture recipient's independent throwaway signer. Do not sign for distribution. Use a new scratch directory; stop before device access if the build block fails.

```bash
: "${zip:?Set the absolute path to the attached GitHub ZIP}"
: "${source_commit:?Set the exact handoff commit}"
: "${candidate_tree:?Set the exact handoff Git tree}"
: "${zip_sha256:?Set the handoff ZIP SHA-256}"
sdk=${ANDROID_HOME:?Set ANDROID_HOME to the installed SDK}
: "${JAVA_HOME:?Set JAVA_HOME to the installed JDK 17}"
ffmpeg=$(command -v ffmpeg)
base=bbd83546a0c719fe15ae47d4fedf5685b2a93d90
lab=$(mktemp -d "${TMPDIR:-/tmp}/browser-downloads-lab.XXXXXX")
root="$lab/browser-$source_commit"
(
  set -e -o pipefail
  test -n "$ffmpeg"
  printf '%s  %s\n' "$zip_sha256" "$zip" | shasum -a 256 -c -
  unzip -q "$zip" -d "$lab"
  curl -fLsS "https://codeload.github.com/SlingshotToHeaven/browser/zip/$base" -o "$lab/v7-base.zip"
  printf '%s  %s\n' 'ad51cdfdb4833b6b0f62c7aeed6236dc29e74978199663c1b065a18b0eac99bc' "$lab/v7-base.zip" | shasum -a 256 -c -
  unzip -q "$lab/v7-base.zip" -d "$lab"
  python3 -B "$lab/browser-$base/tools/prepare_perf_baselines.py" "$lab/baselines"
  python3 -B - "$lab/baselines" <<'PY'
from hashlib import sha256
from pathlib import Path
import sys
base = Path(sys.argv[1])
files = sorted(p for p in (base / 'step19').rglob('*') if p.is_file())
manifest = ''.join(f'{sha256(p.read_bytes()).hexdigest()}  {p.relative_to(base).as_posix()}\n' for p in files)
if len(files) != 482 or sha256(manifest.encode()).hexdigest() != 'f804a8d6915cc657e8c309bc743ff6137f760663eaf609b18b2ab4d4d6a4e947':
    raise SystemExit('Restored Step 19 build inputs did not match the pinned v7 tree')
PY
  (
    cd "$root"
    shasum -a 256 -c docs/native-arm64.sha256 docs/release-assets-build-inputs.sha256
    FFMPEG_TEST_BIN="$ffmpeg" ./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :downloader:testDebugUnitTest
  )
  (cd "$lab/baselines/step19" && ./gradlew --no-daemon :app:assembleDebug)
  bash "$root/tests/fixtures/download_upgrade/build_receiver.sh" --disposable "$sdk" "$lab/recipient"
  touch "$lab/build-ok"
)
```

If the build block fails or `build-ok` is absent, stop before touching the phone. Report the failed step and tool versions; a signature-only `javac` compile does not replace the Gradle build. On success:

```bash
app="$root/app/build/outputs/apk/debug/app-debug.apk"
tests="$root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
v7="$lab/baselines/step19/app/build/outputs/apk/debug/app-debug.apk"
recipient="$lab/recipient/receiver.apk"
shasum -a 256 "$app" "$tests" "$v7" "$recipient"
```

## 2. Verify the device and run the scripted batch

Use one authorized **spare** native `arm64-v8a` handset/tablet on **API ≥34**, owner profile (`user 0`), unlocked, portrait, English UI, with working Wi-Fi and USB debugging. The phone must not already know `com.browser`, `com.browser.test` or `com.browser.fixture.receiver`. Do not remove an existing install to satisfy this check. Leave `com.privacy.browser.injection` **and** `.debug`, the default browser, and all personal data untouched. Do not give the app broad storage access or root. Only synthetic fixture paths and identity booleans go in the return report.

```bash
adb="$sdk/platform-tools/adb"
"$adb" devices -l
printf 'Serial of the explicitly authorized spare USB device: '; IFS= read -r serial
if [ -f "$lab/build-ok" ]; then
  python3 -B "$root/tools/run_download_device_lab.py" \
    --disposable --serial "$serial" --sdk "$sdk" --ffmpeg "$ffmpeg" \
    --apk "$app" --test-apk "$tests" --baseline "$v7" --recipient "$recipient" \
    --source-base "$base" --source-tree "$candidate_tree" --zip-sha256 "$zip_sha256" \
    --work "$lab/device-run"
fi
```

This lab driver reuses the existing fixture assertions. It verifies package IDs, signer relationships and installed byte hashes. It installs v7 fresh, replaces **that** application with v8 without clearing it, tests delivery to the offline separate UID, then resets **only the verified synthetic `com.browser` profile installed by this run** between independent cases. It removes only reverses it added. The battery case simulates Android battery-service events and resets the simulation; the Wi-Fi case changes the actual Wi-Fi setting temporarily and restores it. Keep the phone attached and don't interact with it while the script runs. Read the per-case JSON even on nonzero exit. `blocked`, missing terminal evidence, a timeout, and an omitted case are not passes. If a runner stops, keep the profile and report; do not bypass the preflight or relaunch a possibly stalled mux. Keep the raw scratch logs local.

### Scripted case inventory and required outcomes

Record subcases individually, including ones reported together by the script.

| Gate | Case IDs in the report | Expected behavior on this build |
| --- | --- | --- |
| **DL-01** | `installed-upgrade-and-chooser` — upgrade section | Installed v7→v8 with the same package, signer and UID. Completed file/history remain exact; queued and interrupted work alone resume after relaunch. Active suffix starts at **73,421**; manual pause stays at **51,913** until explicit Resume. No legacy headers sent. |
| **DL-01** | `service-force-stop` (also DL-02/03) | A real running worker stops at the held checkpoint. Relaunch recovers the same job and reservation, new attempt, exact anonymous suffix from **65,536** and exact **294,917** bytes. The separate manually paused row and partial do not move. |
| **DL-01** | `storage-precommit`, `storage-private-copy-or-link`, `storage-public-copy`, `storage-link` | Require evidence of the actual cut, DB state, file sizes/hash and inode relationship. A pre-commit complete-looking file is not adopted; a copy interrupted at **2 MiB** leaves a separate short destination and full **4 MiB** stage. Neither offers a completed file. After the pre-commit **Start over** sequence, expect one fresh HTTP 200 from byte zero. After parking an interrupted copy and selecting **Retry** without Start over, expect exact output from the saved stage with **no new HTTP request**. Both paths preserve neighbors. App-private and ordinary public paths are separate; record ordinary-UID storage refusal/fallback as blocked. A link probe alone isn't an interrupted link; require identical stage/final inode at the cut if supported. |
| **DL-01** | `storage-app-external`, `storage-public` | Exact output in app-specific external storage; supported public Downloads publishes the suffix without overwriting the existing synthetic entry. Neither qualifies removable storage. |
| **DL-02** | `queue-wifi`, `queue-power-schedule`, `queue-live-cap`, `service-recreate`, `service-force-stop` | Wait/run transitions and exact resumed range; battery/schedule gates open only when eligible; manual pause retained; change shared cap to and from unlimited and pause/cancel a waiting job; Home → stop actual foreground service → relaunch restores service on the same active attempt; idle service retires. |
| **DL-03** | `http-webview-scope`, `http-external`, `http-private`, `installed-upgrade-and-chooser`, `service-force-stop` | Same-origin cookie and origin-only referrer on both probe **and** actual body; foreign and return hops strip them. External intake and saved-job/relaunch retry anonymous. Private intent refuses with no navigation, prompt, row or network request. |
| **DL-04** | `installed-upgrade-and-chooser` — Open/Share section; four `grant-provider-…` tests; `storage-saf-refusal` | Tap-card and menu Open both deliver; single and multiple Share read exact selected bytes through Android chooser in the fixture recipient, a separate UID. No write, sibling/staging/descendant read, stale or replaced/removed row access. Document and tree URI paths fail before transfer or publication. |
| **DL-05** | `blob-queued`, `blob-cancel`, `blob-reload`, `blob-close-tab`, `blob-short`, `blob-idle-timeout`, `blob-collision`, `blob-foreign-frame` | Dismiss without fetch/row/stage; waiting job does not fetch; admission verifies exact bytes. Cancel/close/reload/short/stall/collision can't publish stale or incomplete bytes. An actual trusted click must reach the foreign frame, and must not admit a job. Record a missed touch as inconclusive. |
| **DL-06** | `stream-query-only` | The fixed same-path query-only HLS master, playlist and segment keep percent-encoded queries and get the three exact HTTP 200 routes. Require committed MP4, H.264/AAC and MediaPlayer completion. |
| **DL-06** | `stream-missing-audio`, `stream-invalid`, `stream-short`, `stream-mux-error`, `stream-dash` | Missing required audio, non-media/short body, actual mux error and unsupported DASH refuse without publishing a file or leaving staging. DASH must not fetch. |
| **DL-06** | `stream-separate`, `stream-ts`, `stream-fmp4`, `stream-aes128` | Exact positive route observations, including the fMP4 init/media 206 ranges and AES key fetch, followed by a completed MP4, Android track/frame/sample checks and MediaPlayer completion. The original four-case checks and limits are retained. **Add the visible and audible check below.** |

The final four automated positives start in a clean lab profile and leave their files for playback. If public storage or a hard link is missing, record the exact probe result. If only a link cut occurred and the copy path never ran, the exclusive-copy cut is still unverified on the handset. If a negative stream check or early query-only run stalls in native code, do not count any unrun positive as passed.

## 3. Operator observations on the same build

Start with playback before making any further changes to the app. After the four native cases return terminal results, open Downloads and play each completed file through its actual **Open File** grant in a known local video player. Verify **moving video and audible tone** on the same phone. A thumbnail, track listing, host decode or muted clip is not a pass. Compare the recorded byte count/hash with the saved fixture result where you can; the four output names are below. Don't open a stale job if the lab runner reports an active or incomplete native attempt.

```bash
d() { "$adb" -s "$serial" "$@"; }
d shell am start -W --user 0 -n com.browser/com.privacy.downloader.ui.DownloadsActivity
```

| Case | File | Automated result | Moving video | Audible ~440 Hz tone | Final result / note |
| --- | --- | --- | --- | --- | --- |
| `stream-separate` | `separate.mp4` | | | | |
| `stream-ts` | `transport.mp4` | | | | |
| `stream-fmp4` | `fragments.mp4` | | | | |
| `stream-aes128` | `encrypted.mp4` | | | | |

For manual HTTP, storage, and blob cases, reuse the lab fixtures without the reset script. In a separate host shell, set `root` and `ffmpeg` as above, then run:

```bash
cd "$root"
python3 -B - "$ffmpeg" <<'PY'
import sys
from threading import Event
sys.path.insert(0, 'tests/fixtures')
from download_batch import media_assets, servers
with servers(media_assets(sys.argv[1])):
    print('Synthetic fixtures on host loopback 8024 and 8025; Ctrl-C to stop.', flush=True)
    try:
        Event().wait()
    except KeyboardInterrupt:
        pass
PY
```

With ADB still pointed at the same lab phone, run `d reverse --no-rebind tcp:8024 tcp:8024` and `d reverse --no-rebind tcp:8025 tcp:8025`. Keep the exact list of successful adds and remove only those at the end (`d reverse --remove tcp:<port>`). Fixture request summaries are at `http://127.0.0.1:8024/control/requests`, with optional `?/<fixture-path>` filter; only use its synthetic rows, and compare before/after per case rather than assuming a zero count. When a hash is needed, copy only the named fixture via the confirmed lab app UID and hash it on the host. Never export the Downloads database or browse real user directories.

| Manual ID | How to check and what to record |
| --- | --- |
| **DL-01-M** — removable storage | If the phone has a supported, ordinary-app-writable removable mount, select a **new empty fixture directory** there with the normal folder picker. Add `http://127.0.0.1:8024/range/run.bin` as a one-connection job. Expect **294,917** bytes of `d`, exact SHA-256, and a second save that does not overwrite the first. Confirm the actual output path is on that volume, not a fallback. Mark blocked with the device capability reason if no usable volume or folder exists. Do not grant all-files access or force a SAF path. |
| **DL-02-M** — real low battery/charging edge | The scripted test uses real Android broadcasts, but simulated battery-service input. On the spare phone at a real battery level ≤15%, enable the downloader's low-battery setting. Verify an eligible fixture job waits while unplugged (`WAITING_FOR_BATTERY`) and resumes only when charging/eligible, preserving its checkpoint. A USB disconnect may end the ADB reverse; restore only the known lab reverse and distinguish fixture unreachability from a queue failure. Record real levels, plug state, queue state and observed resume offset; otherwise mark the physical edge not-run/blocked. No performance trace. |
| **DL-03-M** — paste | Open `/start` on `127.0.0.1:8024` in a regular lab tab to seed the synthetic cookie. Paste `http://127.0.0.1:8024/session/external.bin` into **Downloads → Add download**. Check probe/body request evidence against this path: no cookie and no referrer. Compare with the scoped long-press results and the anonymous relaunch/retry from the scripted batch. |
| **DL-05-M1** — source navigates to another origin | In a regular lab tab, open `http://127.0.0.1:8024/blob_download.html`, select **First chunk, then stall**, tap the download link and confirm. Wait for **Synthetic stream chunks supplied: 1**. Navigate **that tab** to `http://127.0.0.1:8025/foreign-frame`. The old attempt must fail without published bytes, staging, or an Open/Share grant. Return to `/blob_download.html`, select **Normal**, and confirm a new attempt with a different filename; no old chunks can satisfy it. Compare the new file to the pinned fixture. |
| **DL-05-M2** — unsupported provider | On an authorized spare configuration with a real WebView provider **without** `WEB_MESSAGE_LISTENER`, tap the download link on `/blob_download.html`. Expect an explicit “This WebView does not support secure blob downloads” refusal and no fallback bridge, transfer, output or job. If no such provider is available, record **blocked**, not verified. Do not downgrade or change the daily phone's WebView to manufacture this precondition. |
| **DL-06-M** — fixed HLS URL | With the same app build, add `http://127.0.0.1:8024/query-only/clip.m3u8?part=master` using the real Downloads UI. The fixture's `media%2Flist` and `media%2Fsegment` requests must retain `/query-only/clip.m3u8`. Check a completed MP4 and play it through Open File. Record independently from the instrumentation exact-count assertions; UI metadata can make additional fixture requests. |

Expected binary `run.bin` reference (generate locally, no network data): `python3 -c 'import hashlib; print(hashlib.sha256(b"d"*294917).hexdigest())'`. For the blob fixture, use the normal-case reference from the [fixture method and checksum](../app/src/androidTest/java/com/privacy/browser/DownloadWebViewBatchTest.java), not the MP4 expected hash. A fair test is not an invitation to select any real app file.

## 4. Return one report and stop

Send `download-device-lab.json` and the following results for the **same tested APKs**. If a case failed or a runner quit, include its last recorded phase, expected versus observed, and no more than a redacted excerpt. Do not send a device serial, keys, URLs from personal browsing, a DB, raw `logcat`, or APKs. Record whether the two old package IDs, if present before, are still present after. Stop all local fixtures and remove only your new reverses. Leave the lab install and its synthetic results in place for follow-up unless the owner asks to remove it.

```text
Source base / candidate tree / ZIP SHA-256:
Host OS / JDK / SDK & Build Tools / FFmpeg:
APK SHA-256 + signer SHA-256: v7 / v8 / instrumentation / fixture recipient:
Phone model / Android and API / native ABI / page bytes / WebView package+version:
Original package(s) present before / after (yes/no, no data):
Script report filename + SHA-256:
```

| Check | Pass / Fail / Blocked / Not run / Inconclusive | Actual result and synthetic evidence, or exact reason |
| --- | --- | --- |
| DL-01 upgrade / transfer / pre-commit / private copy or link / public copy / storage | | |
| DL-01-M removable | | |
| DL-02 scripted queue/service / DL-02-M actual charging edge | | |
| DL-03 scoped/external/relaunch/private / DL-03-M paste | | |
| DL-04 actual card/menu Open, single/multi Share and denial | | |
| DL-05 scripted lifecycle/frame / DL-05-M1 source switch / DL-05-M2 unsupported | | |
| DL-06 four playback observations above / five negatives / DL-06-M fixed URL | | |

This isn't acceptance of the topic or promotion to release. The agent reconciles the evidence (including any failures or blocked capabilities), fixes and retests as needed, and only then pushes the verified topic. The owner still builds, installs and verifies that pushed revision before the next topic opens.
