# DL-05 — wireless blob recheck on the retained phone

DL-03-M paste and DL-06-M query-only UI/wire/mux already returned. **Do not repeat them.** On the same Galaxy S21 FE and retained browser, check only `blob-source-switch` and `blob-foreign-frame` through the production UI. Use only the already-connected wireless ADB target and drive the UI remotely. No APK build or replacement, resets, connection changes, or owner action. Prior scoped results and files stay intact.

## Set up once

- Extract the pinned ZIP and reuse the shell helper and brief target guard from [Section 1 of the wireless runbook](android-download-wireless-resume.md#1-select-the-retained-target-without-changing-it) in **Bash** with the normal local PATH. Do not rerun that guide's two finished download checks. Reuse the same serial with `d` for every device step; if the connection/profile has changed or the UI is locked, record `BLOCKED` and stop the affected actions.
- In a separate host shell, start the two existing loopback fixtures without generating media:

```bash
PYTHONPATH=tests/fixtures python3 -B - <<'PY'
from threading import Event
from download_batch import PRIMARY, FOREIGN, servers

with servers():
    print(f'Blob fixtures listening on host loopback {PRIMARY} and {FOREIGN}.', flush=True)
    try:
        Event().wait()
    except KeyboardInterrupt:
        pass
PY
```

- Add only `d reverse --no-rebind tcp:8024 tcp:8024` and `d reverse --no-rebind tcp:8025 tcp:8025`. Use the phone URLs below. If you cannot add both owned reverses or reach the fixture, do not rebind existing mappings or expose the server. Read the redacted host events via `curl -fsS 'http://127.0.0.1:8024/control/requests'`; compare counts before and after each action. Do not send host requests to actual test routes. Remove only the still-matching reverses you added and stop only your fixture process at the end.
- Act on current screen/UI bounds, in new unpinned lab tabs. Use real remotely injected touchscreen taps (`d shell input touchscreen tap X Y`), not JavaScript click calls. Observe only the synthetic UI and files. Use the established app-private fixture output folder from the prior run, verified in the download prompt. Do not change download settings, read/export a database, or touch other files. If a path or control cannot be checked safely, report the missing assertion rather than assuming a pass.

## 1. `blob-source-switch` (run first and record separately)

1. Open `http://127.0.0.1:8024/blob_download.html` in a new lab tab. Record the tab count and fixture directory entries **before** confirming. Select **First chunk, then stall**; open the real download link and confirm once with a unique synthetic name such as `dl05-old-origin.bin`. Require **Synthetic stream chunks supplied: 1** while the transfer is held. A queued attempt or one that already timed out is not a valid source switch.
2. As soon as the first chunk is observed, navigate **that selected tab** to `http://127.0.0.1:8025/foreign-frame` without clicking its attachment. Pre-stage this command so it can be sent immediately; do not spend the 30-second stall window collecting reports. Check that the foreign page committed in the same selected tab and no new tab opened.

```bash
d shell am start -W --user 0 -n com.browser/com.privacy.browser.MainActivity \
  --es com.privacy.browser.EXTRA_LOAD_URL http://127.0.0.1:8025/foreign-frame \
  --ez com.privacy.browser.EXTRA_OPEN_IN_NEW_TAB false
```

3. Require the named old job to be **FAILED** with a diagnostic referring to the **original page**, not an idle timeout. Its context menu must not offer Open or Share. Check no new stage remains and no old output exists in the known fixture folder. Don't retry the failed row. Return the same tab to `/blob_download.html`, select **Normal**, and confirm a different new fixture name. Require **Completed**, 262,147 bytes, SHA-256 `3906393794f65f3ce67995cc639367320caa7f1c8554e6f7f6603948e1fb3bbf`, and the old row still failed. Record the limited scope of any check you could not observe remotely.

## 2. `blob-foreign-frame` (independent of source-switch result)

1. Baseline: record the Downloads **All** row count (with search cleared; select a known fixture → **Select All**, record the selection count, then exit selection; do not Share/Delete). Snapshot the known fixture directory entries. Open `http://127.0.0.1:8025/foreign-frame` as a top-level page in a new unpinned lab tab. Wait until the yellow attachment is visibly rendered. Tap inside it once using the current screen bounds. Require new `/frame-input/top/pointer` and `/frame-input/top/click` HTTP 204 witnesses and a real download prompt for the fixture `blob:http://127.0.0.1:8025/...`. A generated filename is fine. Cancel without queueing.
2. Open `http://127.0.0.1:8024/frames`; require fresh parent and child GETs and a visibly rendered yellow **Request frame blob** target. Tap inside that actual iframe attachment once. Require new `/frame-input/child/pointer` and `/frame-input/child/click` HTTP 204 witnesses. **No witness is an input failure, not a denial pass.** Don't infer a tap from a page GET or a discarded press outside the frame.
3. After witnesses, observe no download prompt. Check the test tab card in the tab grid (`btn_tabs`, `tab_card_status`) for no **Downloading** badge when observed; this is a UI observation, not continuous instrumentation of the lease. Recheck the same All row count and fixture directory entries. Record prompt, rows, badge, and file/stage checks separately; a missing input or missing required observation is not a full refusal pass.

Return `downloads-wireless-blob-recheck.md`: commit/tree/ZIP, retained APK and device/provider identity, each case outcome with the exact redacted witnesses and UI/file assertions, and cleanup status. Do not send device identifiers, raw hierarchy/DB, or APKs. If either case is inconclusive, still try the independent other case when safe. Do not retry cases in a loop, touch the earlier passes, or move to a different topic.
