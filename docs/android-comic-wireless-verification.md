# Assistant, images, comics and local data — COMIC-01 wireless verification

**Returned and reconciled; do not repeat.** See [the two scoped handset passes](topic-3-agent-test-results.md#returned-comic-verification--6deb4f6). No archive or final-copy qualification.

One retained wireless checkpoint with two separate synthetic cases: **retry only the challenged page**, stopping at a deliberate page-three failure, and **retire a visible verifier on real same-tab document replacement**. Neither chapter can produce a complete CBZ. This does not repeat the hosted successful archive, timeout, or Back checks and does not test the real viewer, shared destination, final copy, or renderer loss.

## Setup and scope

- Verify the pinned ZIP; use only the Bash `d` helper and [Section 1 target guard](android-download-wireless-resume.md#1-select-the-retained-target-without-changing-it). Keep the existing wireless S21 FE connection, user, WebView, app/test APKs, settings and retained data. No builds, installs, resets, force-stop, instrumentation, app-data reads, logcat, public sites, permission changes or owner device action. No clipboard, settings edits, or synthetic Android lifecycle or native callback injection.
- From the extracted root run `python3 -B tests/fixtures/comic_wireless_verification.py` in a host terminal. Check `d reverse --list`; use only `d reverse --no-rebind tcp:8030 tcp:8030` on a free target port. Host counts: `curl -fsS http://127.0.0.1:8030/control/counts`. Only the phone loads chapter/image/verifier routes or posts witnesses. Host may use the two specific release commands below; no other host requests to test routes during the run. No network exposure, fallback, reruns, or fixture restart to clear counts mid-run.
- Record that run's tab count if safely visible. Use the single known link entry into a new regular fixture tab, then the fixture's own link for the second case:

  ```bash
  d shell am start -W --user 0 -n com.browser/com.privacy.browser.MainActivity \
    --es com.privacy.browser.EXTRA_LOAD_URL http://127.0.0.1:8030/retry \
    --ez com.privacy.browser.EXTRA_OPEN_IN_NEW_TAB true
  ```

- Each page declares exactly three synthetic positions. Find **`fab_comic_download`** and open the real dialog (**`tv_comic_page_count`**: 3 detected pages); require no page-body requests yet. Use **`btn_start_comic_download`** only for these synthetic chapters, with their existing test titles. If the control, staging or verifier is unavailable, record the blocker and do not turn on Comic, change NoScript, or pick another destination. The normal preparation may allocate temporary app staging or create an empty **Comics** directory; do not inspect, delete or change any storage or folder to force the test. The fixture cannot supply a complete chapter. If the app nevertheless reports **Detected pages saved to:**, stop and report the mismatch.

## 1. `COMIC-01/verify-retry`

1. On **COMIC-01 wireless retry fixture** (`GET /retry`), start the native dialog's job once. Require `GET /retry/first.png` (200), one `GET /retry/gate 403`, then the actual verifier with its **Synthetic retry verifier** heading, **Local verifier witness recorded**, and **Verification Done** (`btn_captcha_done`). At this point expect one `GET /retry/gate 200 html` and `POST /witness/retry` (204), with zero PNG retry or page-three request. This window contains no external challenge, account or input field.
2. While that window is still visible, within the fixture's 45 s gate after the first 403, run on the host:

   ```bash
   curl -fsS -X POST http://127.0.0.1:8030/control/release/retry
   ```

   Require `released`; then tap actual **Verification Done** once in this synthetic window only. If the release is refused or the window is gone, do not tap or retry. Release permits only the second image; the third always returns 404.
3. Require the verifier to dismiss, and the native progress/status to report **Page 3 of 3**, **HTTP 404**, and **No archive was saved** (no **Detected pages saved to:**). Fixture counts should add exactly one `GET /retry/gate 200 png` and one `GET /retry/missing.png 404`; `/retry/first.png` stays at one. Record the observed outcome, then close just the retry sheet with `btn_close_comic_dialog` and use **Open verifier retirement fixture** in the same tab.

## 2. `COMIC-01/verify-retire`

1. Require fresh `GET /retire` and **COMIC-01 wireless retire fixture**. Separately start the three-position synthetic job; require the analogous one first image, one 403, one verifier HTML, and `POST /witness/retire` (204), plus a visible **Synthetic retire verifier** and **Verification Done** before release. Do **not** tap that button or close the verifier. Even if the retry outcome was blocked, run retirement once if its own controls are safely reachable from this same setup.
2. While this second verifier is still visible and within 45 s of its first 403, run on the host:

   ```bash
   curl -fsS -X POST http://127.0.0.1:8030/control/release/retire
   ```

   Require `released`. The original fixture document itself then navigates to `/after-retire`. The control cannot release an image. Do not use Back/Home, Activity changes, another intent, a prompt close, or a timer expiry to simulate retirement.
3. Require one new `GET /after-retire`, visible **Comic verifier retired** in the original selected tab, and both old dialogs gone before the verifier's 60 s timeout. Record elapsed time or state why it could not be distinguished from timeout. After a short bounded observation (at least 10 s after the replacement), record unchanged old gate counts, zero `/retire/missing.png` requests, and no visible saved status. This is visible UI retirement and no continued request during observation, not proof of internal WebView destruction or drained staging.

## Return

Close only the identifiable test tab, remove only the still-matching reverse this run created, stop only its host fixture and clean scratch captures. If a positive control or timing witness is missing, report `BLOCKED` or `INCONCLUSIVE` as appropriate, not a pass. Return `comic-wireless-verification.md` with ZIP/commit/tree/hash, retained app/test hashes and environment (no address/serial), per-case controls and exact native outcome, fixture counts/release ages before and after, scoped cleanup, and any limitations. Return no filesystem inventory, raw captures, personal data or logs. No shared archive/viewer, final-copy drain, or actual service verification is qualified by this pass.
