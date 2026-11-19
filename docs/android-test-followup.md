# Android test follow-up

Use the source supplied for each retest and record its identity. Follow the [isolated testing rules](antigravity-device-test.md#1-keep-the-installed-app-and-the-mac-safe): test only the disposable `com.browser` / `com.browser.test` pair, with synthetic data and an explicitly selected device. Keep the old browser and its data untouched. This is a retest, not a release or a GitHub operation.

## Report 6: saved download checkpoints after relaunch

Report 6 used `c0843fa475fb65ef591b8d0cba0ec3a7dddbd596` (ZIP SHA-256 `214458c6e2b4e44e12b2f7f3d68ce94872a692aeaa93e2fec986924d3eb1c4c7`) with the same `com.browser` debug APK, SHA-256 `cd8c9b95c6eee6c1e4f9844bf988c925ac3b68c28961894c21372605043acb6c`. Galaxy S21 FE, API 36, ARM64 with **4 KiB** pages, WebView `137.0.7151.115`. The original browser and unrelated files were reported untouched.

| Scoped check | Reported observation |
| --- | --- |
| DL-01 / DL-02 active job | The running `interrupted.bin` stopped at force-stop. No new requests were observed in the five seconds before explicit launch. After launch and opening Downloads, without Resume/Retry, its first body was conditional HTTP 206 from byte **1,703,936** with a matching ETag. |
| DL-01 / DL-02 manual pause | `manual-pause.bin` stayed Paused and made no requests through relaunch and completion of the other job. After a Resume tap, its first body was conditional HTTP 206 from byte **1,507,328** with a matching ETag. |
| DL-03 resumed requests | Both initial native probes and bodies carried the scoped synthetic cookie and origin-only referrer. Both actual resumed bodies omitted both headers. |
| Integrity | Both outputs were independently measured by the lab at **4,194,304 bytes**, each with its own matching fixture SHA-256. The resumed ranges and output hashes were checked against the pinned source; the APK and device run were not reproduced here. |

This covers two eligible one-connection jobs with stable ETags and app-owned storage. The resumed ranges and exact outputs show checkpoint reuse; rounded UI progress and host `body_end` counts aren't durable-byte measurements. A single cold-launch measurement is not a speed comparison. Upgrade, power/network constraints, changed representations, publication races, and unattended recovery are not demonstrated.

## Next device run

Run **HTTP resume rejection and late destination collision (DL-01 / Step 03 subset)**, not the prior suites. Only the host fixture, its tests, and docs changed. Reuse the exact verified report-6 debug APK if installed in the confirmed disposable `com.browser` slot; otherwise use `./gradlew :app:assembleDebug` with JDK 17/Platform 34 and verify the debug ID, APK hash and expected lab signer. Install with `-r` only over a confirmed disposable, same-signer build. Preserve both old app IDs, existing apps/files and data. No uninstall, clear-data, downgrade, device settings changes, broad storage access, or release signing.

First confirm that host port 8019 and the authorized phone's reverse mapping are unused. On the Mac use Python 3.9+ and start `python3 -B tests/fixtures/download_recovery.py` with no flag, then add `adb -s "$serial" reverse --no-rebind tcp:8019 tcp:8019`. Select the phone with `-s "$serial"` for **every** device command. Leave that mapping in place during any host fixture restart. Use a committed regular test tab at `http://127.0.0.1:8019/start`. For each job, **long-press → Download Link with Manager**, choose **one connection**, and a fresh, simple filename in the dedicated **app-owned** test folder. Use **File Details & Diagnostics** to confirm the actual reserved path; don't guess from the suggested name. Run one job at a time under existing settings. If an owned test path or required capability isn't available, mark the dependent check **Blocked**.

### A. Changed validator at the same URL

1. Start the **Manually paused job** (`/jobs/manual-pause.bin`, version 1). **Leave Expected SHA-256 blank** in the prompt, so a checksum check can't mask a resume protocol error. Capture the initial conditional 206 body with `version=1 if_range_version=1` (not just its probe). Let progress advance for at least eight seconds, then manually Pause and wait for the old body to drain. Confirm Paused, nonzero progress, and no published file.
2. Stop **only** the Mac fixture process and restart it with `python3 -B tests/fixtures/download_recovery.py --replace-paused`. Keep the same device URL, port, reverse and job; do not reload/enqueue the link or edit the URL. Confirm the fixture prints version 2 of `manual-pause.bin`, whose length is unchanged but whose strong ETag and all fixture data were generated separately. `interrupted.bin` remains version 1.
3. Tap **Resume** for that paused job. Capture the stale request: `GET`, `version=2`, `range_present=True`, `requested_start>0`, `if_range_version=1`, `validator_match=False`, `conditional=True`, `probe=False`, `status=200`, **served** `start=0`. The app must reject the 200 as a resume response, show `FAILED` with a range/Start over reason, and not publish anything. If those request conditions weren't exercised, don't infer rejection from a generic error. Record the exact failure before proceeding.
4. From that failed row choose **Retry or start over → Start over**, not Retry or a new job. Expect a fresh full `GET status=200` with `version=2`, `range_present=False`, `requested_start=None`, `if_range_version=none`, `conditional=False`, `start=0`. Independently verify the completed file against the version-2 digest below. There must be no automatic publication before the explicit action. On a failure to drain or to start, capture the exact status/message instead of resetting data.

| Fixture | Expected bytes | SHA-256 |
| --- | ---: | --- |
| `manual-pause.bin` version 1 (old representation) | 4,194,304 | `dd57a6043af06c13671a06e145115b4c1903a6684ef0c41feefe50ee9caef6c4` |
| `manual-pause.bin` version 2 (only valid completed output in A) | 4,194,304 | `51b5d0a129098c43c19989171c8307df017e92f99219a1e5c225fa0ff5481f05` |
| `interrupted.bin` version 1 (fixture body in B, not the expected on-device file) | 4,194,304 | `3f594a89bfe61cdd0fdeb4592211e70737211891cfdcde17d52464d9648f66b2` |
| Case B sentinel (`android-report-7-occupied` plus one LF) | 26 | `45508a98fac27025251c3ca1a1169a1cf31db697cfbdac5171cdf36ac892f315` |

### B. Destination becomes occupied after admission

1. Start the **Interrupted active job** (`/jobs/interrupted.bin`), but **do not interrupt it**. Use a different fresh name. After its actual conditional 206 body starts and while still transferring, confirm the exact reserved **vacant** app-owned final path and create the 26-byte synthetic sentinel there. Do not touch its staging file. The transfer takes about 64 seconds with one connection. If it finishes before sentinel creation, the collision was **not exercised**.
2. For a simple verified path `dest` under `/storage/emulated/0/Android/data/com.browser/files/Download/` (only ASCII letters, digits, `/`, `.`, `_`, `-`; no `..` components or symlinked folders), this selected-device command creates only a new entry and refuses to overwrite. Do not use it on an uncertain or shared path. If `run-as` cannot write here, mark this case **Blocked**; don't broaden permissions.

   ```sh
   create_marker='test ! -e "$1" && test ! -L "$1" && set -C && printf "%s\n" android-report-7-occupied > "$1"'
   adb -s "$serial" shell -T "run-as com.browser sh -c '$create_marker' sh '$dest'"
   ```

3. Verify the sentinel's size and SHA-256 before the final outcome, and record that it was created after the transfer started and before its body ended. Let the host body finish. Expect `FAILED` with a destination-exists message, no completed record or alternate output for that job, and the sentinel **unchanged** (exact size and digest). Don't remove it or tap Retry before gathering evidence. A conflict while initially choosing a name, a generic failed transfer, or a timing miss is not a pass for late collision.

For hashes, read only the exact authorized fixture or sentinel file, e.g. `adb -s "$serial" exec-out run-as com.browser cat "$dest"` into a fresh Mac scratch file, check the read succeeded, and use `wc -c` and `shasum -a 256`. Return `out/android-test-report-7.md` with source ZIP/APK IDs and hashes, device/API/ABI/page size/WebView, **Pass / Fail / Blocked / Not run** for each case, sanitized fixture lines distinguishing requested/served offsets and new/reused validators, the exact UI reasons and action order, and independent file sizes/hashes or missing-file evidence. No serial, raw headers or unrelated logs. Remove only the files and mapping you can identify as created for this run and stop the host fixture; don't sweep staging files. Record intentional leftovers. This is not a crash during copy/commit, in-place upgrade, network/power, paired performance, or release run.

## Report 5: scoped downloads, blob intake and single-file Share

Report 5 used `8c7ed592ea6ff99e578a1c14015594fb430d60a9` (ZIP SHA-256 `cfb7a3d53a555d3eeade19feb275d577d642d9368e2daf9f7d9be34ec22c09de`) and reused the verified `com.browser` debug APK, SHA-256 `cd8c9b95c6eee6c1e4f9844bf988c925ac3b68c28961894c21372605043acb6c`. Same Galaxy S21 FE, API 36, ARM64 with **4 KiB** pages, WebView `137.0.7151.115`. The reported run used app-owned test storage and left the original browser untouched.

| Scoped check | Reported result | Limits |
| --- | --- | --- |
| DL-03 (4/4) | Same-origin native probe and conditional body sent the synthetic cookie and origin-only referrer. Both were absent on the foreign hop, on return to the first origin, and throughout an unbound Add Download. All four conditional bodies yielded the exact CSV hash. | Retry from a saved job after relaunch wasn't covered. This checks native downloads, not general WebView navigation or every provider. |
| DL-05 (4/4) | Dismiss kept zero fetches and no row. Normal produced the exact 262,147-byte file. One-byte-short and same-address reload after the first stalled chunk both failed without a final file; the new document did not fetch for the old job. | Queue-admission, timeout, cancel, other frame/origin, and destination-collision cases remain separate. |
| DL-04 single Share (1/1) | An approved offline receiver completed a single-file read from the app-owned output; its byte count and SHA-256 matched the original. This closes the missing single-file read evidence from report 4. | Other Open surface, full grant confinement and destination/permission cases aren't implied. The separate-UID grant/revoke test is separate evidence. |

## Previous checkpoint run

These were the instructions for report 6; do not repeat them by default.

Run the **download restart and checkpoint test (DL-01 / DL-02 subset)** below, not the previous suites. This update only changes host tests, a fixture and docs. Reuse the exact verified report-5 debug APK if already installed in the confirmed disposable `com.browser` slot; otherwise build `./gradlew :app:assembleDebug` with JDK 17/Platform 34 and verify package, hash and lab signer. Never replace an uncertain install; `-r` is only for a confirmed disposable same-signer install. Use only app-owned synthetic storage. Leave both old app IDs and their data untouched. No installation downgrade, clear-data, device settings changes, or release signing. **Keep Wi-Fi and the ADB link up**; this is deliberately not the connectivity/charging/schedule matrix.

### Setup: two paced, distinct assets

On the Mac start `python3 -B tests/fixtures/download_recovery.py` in a separate terminal. Confirm the device and unused host/reverse port. Use `adb -s "$serial"` for every device command and `adb -s "$serial" reverse --no-rebind tcp:8019 tcp:8019`. Leave this fixture and mapping running throughout the app stop/relaunch. If existing queue constraints prevent a start, record the reason instead of changing device settings. No other downloads should be running in the confirmed disposable profile.

Open `http://127.0.0.1:8019/start` on a regular test tab and let it commit. For each job **long-press its page link → Download Link with Manager**; don't navigate to the link or use the unbound Add Download. Use **one connection**, a fresh unique name in the app-owned test folder, and the fixture's expected SHA-256 in the prompt if available. The stable ETags differ; each file has **4,194,304** position-dependent bytes and takes roughly 64 s uninterrupted. The fixture prints both digests when it starts:

| Fixture job | SHA-256 |
| --- | --- |
| `manual-pause.bin` | `dd57a6043af06c13671a06e145115b4c1903a6684ef0c41feefe50ee9caef6c4` |
| `interrupted.bin` | `3f594a89bfe61cdd0fdeb4592211e70737211891cfdcde17d52464d9648f66b2` |

### Run: paused versus interrupted

1. Start `manual-pause.bin`. Record the initial `status=206` body request (`probe=False`, `conditional=True`, `validator_match=True`, `start=0`) and whether it sent the fixture cookie and origin-only referrer. Let the row visibly advance for at least eight seconds, then tap Pause for **that job only** before completion. Verify **Paused** with nonzero progress and no completed output; allow the old request to stop. The fixture's `body_end` is a socket observation, not proof of persisted bytes.
2. Start `interrupted.bin` from the same fixture page. Record its initial body request and let it advance for at least eight seconds while it is still running, without pausing it. Note the displayed progress immediately before `adb -s "$serial" shell am force-stop com.browser`. Wait five seconds and check that neither job starts a new request in that window (an old `body_end` line is not a new request). Leave the host server and reverse alone. Relaunch explicitly with `adb -s "$serial" shell am start -W -n com.browser/com.privacy.browser.MainActivity` and open Downloads normally.
3. **Do not tap Resume/Retry yet.** If eligible, `interrupted.bin` should resume on its own, while `manual-pause.bin` stays **Paused** and makes **no new request**. Capture the first actual post-launch body request: successful checkpoint reuse requires `status=206`, `start>0`, `conditional=True`, and `validator_match=True`. A new zero-offset transfer can recover the job but is **not** checkpoint reuse, even if its final hash is correct. Report unexpected waiting/failed states and exact reason before changing anything.
4. After `interrupted.bin` completes, explicitly resume `manual-pause.bin` and check the same nonzero validated range and completion. Both new attempts must be anonymous (`cookie_present=False`, `referer_present=False`); record the initial scoped positive controls too. Compare each completed test file's exact byte count and independent SHA-256 with the table. Report UI status, probe (`probe=True`) versus actual body, start offsets and hash separately; no raw header values or unredacted logs.

Return `out/android-test-report-6.md` with source ZIP/APK IDs and hashes, device/API/ABI/page size/WebView, the paused and active states before and after relaunch, the requested offsets/validator flags, final file verification, and **Pass / Fail / Blocked / Not run** per observation. If either job completed before pause/stop, say the interruption wasn't exercised. Hash only the exact owned fixture files. Clean up only these test outputs and the one reverse you added; stop the host fixture. In-place upgrade, interruption during final publication, Wi-Fi/power transitions, paired performance and signed release remain separate gates.

## Latest manual report: settled restart and debug playback

Report 4 identifies handoff commit `80d96c80f2cc2339ed601e410bf613ea85eb90d4` (source ZIP SHA-256 `690f5246724d6bdf861639df6e801056f216b830370e8be34afcfde3e33e2291`). It reused the verified report-3 debug APK, SHA-256 `cd8c9b95c6eee6c1e4f9844bf988c925ac3b68c28961894c21372605043acb6c`, on the Galaxy S21 FE, API 36, ARM64, **4 KiB** pages and WebView `137.0.7151.115`. The original browser was reported untouched.

| Check | Reported evidence | Still open or not implied |
| --- | --- | --- |
| WV-01 / PERF-01-R settled restart | After 3 s selected and 6 s background, relaunch restored two tabs and selected step **1** before reload; Forward to 2 and Back to 1 worked. The dummy login still responded **Signed in** after reload. | This run did not test interruption during publication, corrupt snapshot fallback or installed upgrade. The launch time is not a paired performance result. |
| DL-06 debug ARM64 | MPEG-TS and ranged fMP4 (HTTP 206) produced 54,043- and 53,843-byte MP4s with H.264/AAC. Both played moving video and tone on the phone. Missing audio failed with no completed row or output. | Not direct companion A/V, encryption/interruption coverage, a minified runtime test or 16 KiB hardware evidence. |
| DL-04 actual delivery | Open from `DownloadsActivity` played both files. An offline receiver read enough to archive both selected files with their expected sizes. Single Share reached a recipient's save dialog. | Report 4 did not show a completed single-file read (later verified in report 5), or Open from the other surface. Outputs were in shared Downloads, so these observations don't prove URI-only access. The separate-UID grant/revoke fixture remains distinct evidence. |

## Previous download-boundary run

These were the instructions for report 5. Keep them for reproducing those cases; do not repeat them by default. The run focused on **DL-03 and DL-05**, plus single-file Share. This update changes only host tests, fixtures and docs; the app build is unchanged. Extract the pinned ZIP into a new folder. Reuse the exact verified report-4 `com.browser` debug APK if it is already the confirmed disposable install. If you need a new build, use `./gradlew :app:assembleDebug` with JDK 17/Platform 34 and check the debug ID, digest and expected lab signer. Use `-r` only over the confirmed disposable `com.browser` with the same lab signer; stop on a mismatch or uncertain install. Do not rebuild baselines, sign a release or rerun the green suites for this pass.

- Use a confirmed synthetic test profile and dedicated **app-owned** fixture destination, with fresh distinct filenames for every case. Do not use the shared Downloads root, delete any preexisting files, grant broad storage permissions or change system settings. If the test destination or approved offline receiver is unavailable, report the dependent check as **Blocked**. Do not touch either old app ID.
- On the Mac, start `python3 -B tests/fixtures/scoped_downloads.py` in a separate terminal. It serves only fixed pages and synthetic files on loopback. Use `adb -s "$serial"` for every device command, verify the authorized device and unused ports, and check `adb -s "$serial" reverse --list` before adding `adb -s "$serial" reverse --no-rebind tcp:8001 tcp:8001` and the same mapping for `tcp:8002`. Record the fixture's log lines for each case separately. Don't replace other mappings or expose the server to the LAN.

### A. Native download header scope (DL-03 subset)

On a regular test tab load `http://127.0.0.1:8001/start?query=not-a-referer` and let it commit. The response sets a dummy cookie only for `/session/`, not for the initiating `/start` page. **Long-press** each CSV link and use **Download Link with Manager**, then confirm one job at a time. Don't click to navigate to the files: ordinary WebView navigation is not the native download, and cookies are not port-scoped in the WebView jar.

| Case | Expected native log |
| --- | --- |
| Same-origin link | On 8001, the probe and body request both have `fixture_cookie=True`, `cookie_present=True`, `referer_present=True`, `origin_only=True`. |
| Foreign redirect | The job's initial 8001 probe has the authorized headers as in the positive control; 8002 must have **both** `cookie_present=False` and `referer_present=False`, including the body request. |
| Round-trip redirect | The initial 8001 probe is scoped as above. After 8001 → 8002 → 8001, both `/session/bounce.csv` and `/session/return.csv` stay anonymous (`cookie_present=False`, `referer_present=False`), including the body request to the returned path. |
| Unbound Add Download | In Downloads, use **Add Download** (the add button) and enter `http://127.0.0.1:8001/session/redirect.csv`, not the address bar or an opened browser link. Both origins must stay anonymous. |

For the confirmed job the 1-byte probe is `GET ... probe=True conditional=False`; look separately for the actual range with `probe=False conditional=True` (`If-Range`) and HTTP 206. HEAD or the one-byte probe alone is not evidence about the body. Verify each completed CSV is **118,796 bytes**, SHA-256 `14474f4a1a4ba155fd15dd82d2ec168c219e85094a4e2f87e72b806179cf92ba`. Missing or ambiguous requests are incomplete evidence, not a pass. Do not include cookie values, full referrers, or other browsing traffic in the report.

### B. Real WebView blob intake (DL-05 subset)

On a regular test tab with scripts allowed, open `http://127.0.0.1:8001/blob_download.html`. Use a fresh page load and filename for each case. The page counters indicate fetch and synthetic chunk activity, not durable storage. If the WebView lacks supported origin-aware messaging, record the app's refusal and the unsupported case; don't enable a fallback.

1. **Dismiss**: with Normal selected, tap the link, wait for the prompt, then dismiss. `Downloader fetches` must stay **0**, with no new row or fixture file.
2. **Confirm Normal**: verify one fetch and one completed file with **262,147 bytes**, SHA-256 `3906393794f65f3ce67995cc639367320caa7f1c8554e6f7f6603948e1fb3bbf`. Measure the exact synthetic file, not the rounded UI size.
3. **One byte short**: confirm, then wait for the job to fail. The fixture declares 262,147 bytes but emits 262,146. Expect no completed row or published file; check after the job drains.
4. **First chunk, then stall → reload**: confirm, wait for **Synthetic stream chunks supplied: 1** while the job is active, then reload the *source* tab at the same address before the idle timeout. Record that the first chunk was observed before reload. The old job must not complete/publish, and the new document must not fetch or take over the old job. An idle timeout before reload is a different result. Do not reuse the old source via Retry.

### Optional: finish the single-file Share evidence

If an already installed, approved offline receiver can safely save or independently read a test file, share one verified CSV or blob from the app-owned test destination through **Share File**. Complete the read into a new test-only location and verify the received bytes or SHA-256 match the source. Record the chooser delivery and completed read separately. A prefilled save dialog is not a read. Otherwise mark this check **Blocked**; do not install a receiver, choose an upload destination or edit its unrelated data.

For hashes, read only the exact synthetic output in the confirmed test folder (for example, `adb -s "$serial" exec-out run-as com.browser cat "$fixture_path"` into a fresh Mac scratch file with a simple, known fixture path). Check the read succeeds and use `wc -c` and `shasum -a 256` on that copy. If you cannot verify the exact file safely, report incomplete evidence rather than using a rounded UI size or changing permissions.

Return `out/android-test-report-5.md` with source ZIP and APK digests, app ID and public lab signer fingerprint, device/API/ABI/page size/WebView, one outcome per case, sanitized fixture log lines distinguishing probe and body, and exact file sizes/hashes or expected-vs-actual failure evidence. For blob failures include the counter before the action and whether a row or file remained after drain. Mark blocked/unrun cases explicitly. Stop the host fixture and remove only the reverses and test outputs you created, never other data. Full retry/queue/timeout/migration checks, paired performance and signed release still need separate evidence.

## Latest retest: the three targeted checks passed

Report 3 identifies tested commit `006a0cf9830f1e3998615e2bebf50ce8de3e2ef3`, source ZIP SHA-256 `a5ce0a0bad59855d9616d85b7516d8949018d8d5c09fe49da31e9a74fcd40685`, JDK 17 and Platform 34. The reported phone was a Galaxy S21 FE, Android 16/API 36, ARM64 with **4 KiB** pages and WebView `137.0.7151.115`. The original installed app was reported untouched.

| Check | Reported evidence | Limit |
| --- | --- | --- |
| SDK preflight and unsigned inspection | Debug/test and minified release assembly, JVM tests and lint passed. The updated verifier passed `--inspect-only` for the **7,294,459-byte** unsigned `com.browser` APK: non-debuggable, minSdk 26, targetSdk 34, eight stored ARM64 libraries, APK ZIP and ELF alignment checks passed. | No release signature or 16 KiB runtime was tested. |
| Native history | `TabSessionDeviceTest` passed **2/2**. The fixture captured committed Back index 1 and recovered the exact history; empty-state rejection also passed. The report lists **16/16** regular suites passing, including the restored Agent settings coverage (7 tests). | Scope this to the automated fixtures and distinguish new runs from earlier evidence. Full app restart/upgrade, manual checks and physical timing aren't established by these results. |
| Named profiles | **4/4** separate-process phases passed: seed isolation, cold lookup and persisted markers, delete/recreate, and cleanup. | Two synthetic origins on one provider. Not disk-erasure proof, nor qualification of private tabs. Keep private tabs disabled. |

- Tested debug APK SHA-256: `cd8c9b95c6eee6c1e4f9844bf988c925ac3b68c28961894c21372605043acb6c` (unchanged from report 2).
- Tested instrumentation APK SHA-256: `63c1e37d32f62898a4be74e271f1fd7e0982daa136d2ddd637bd2b9f9cefc481`.
- Inspected unsigned APK SHA-256: `ebf0e78e9fb18f534cd5471055bb12f7f201a45859b487c72655bab8a327b34a` (unchanged from report 2).

The earlier targeted rerun steps are kept below for reproductions; don't treat them as failures to rerun by default.

## Previous manual run: settled restart and playback

These were the instructions for report 4. Keep them for reproducing those cases; do not repeat the full run by default.

- Extract the supplied pinned ZIP to a fresh folder. Record its commit and SHA-256, plus the ID, digest and public lab signer fingerprint of the debug APK actually used. If the exact verified report-3 debug APK is already installed in the confirmed disposable test slot, reuse it and record that. Otherwise build `./gradlew :app:assembleDebug` with JDK 17/Platform 34, or use the verified matching APK. Before installing, verify `com.browser` (**Browser (Test)**, debuggable) and the expected lab signer; use `-r` only with the same signer and confirmed disposable install. Stop on a mismatch; no uninstall, clear-data, or forced downgrade.
- Confirm the test slot contains only synthetic data; leave the original app and its data untouched. Use `adb -s "$serial"` for every device command. Check host ports and `adb -s "$serial" reverse --list` before adding a reverse; don't replace or remove unrelated mappings. Don't change system settings or install a receiver just to pass a test. If a manual action/observation is unavailable, ask the operator or report **Blocked**.

### A. Settled session and cookie after process death (WV-01 / PERF-01-R subset)

1. Start `python3 -B tests/fixtures/tab_trust.py` on the Mac; use `adb -s "$serial" reverse --no-rebind tcp:8011 tcp:8011`. No reverse on 8012 is needed for these two pages. On a regular test tab load `http://127.0.0.1:8011/session-cookie`, tap **Set dummy login cookie** and confirm **Signed in**. No real account is used.
2. In another regular tab with scripts allowed, load `http://127.0.0.1:8011/session`. Tap **Push same-URL entry** twice and check the page's `state.step` is 2. Use the browser menu's **Back** arrow and wait for step **1**, at the same URL. Keep this tab selected for at least two seconds after the visible Back result, then press Android Home to background the app. Wait at least five seconds before the stop and record both waits. If available, record whether `Browser.session.publish` and `Browser.cookies.stopFlush` completed; elapsed time alone isn't proof of a successful write.
3. `adb -s "$serial" shell am force-stop com.browser`, then `adb -s "$serial" shell am start -W -n com.browser/com.privacy.browser.MainActivity` without a URL extra. Verify the selected tab, URL and step **1** **before** manually reloading. Verify Forward reaches step 2 and Back returns to step 1, and the other tab remains. Switch to the dummy-login tab and reload it to prove the fixture still responds **Signed in**. Report each result separately. Don't edit saved files or imply this also tested abrupt mid-write failure, corrupt-state fallback, or installed upgrade.

### B. Native mux and actual Open/Share delivery (DL-06 / DL-04 subsets)

1. Start the [playable mux fixture](release-readiness.md#playable-mux-fixture-for-the-minified-apk) on the Mac (`python3 -B tests/fixtures/release_mux.py`, host FFmpeg with libx264/AAC); use `adb -s "$serial" reverse --no-rebind tcp:8018 tcp:8018`. Use the fixture with the verified **debug** APK only. Use a dedicated app-owned test destination and distinct fixture filenames. If you can't select a safe destination, report that instead of requesting broad storage access. No real media or personal downloads.
2. At `http://127.0.0.1:8018/`, use **Grab Page Links** and download each master separately: `mux-ts.m3u8`, `mux-fragments.m3u8`, and `mux-missing-audio.m3u8`. Record whether the first two publish actual MP4s, with HTTP 206 for the fMP4 ranges, and whether the missing-audio case fails without a completed row or output. For the good MP4s verify moving frames **and** the audible test tone on the phone. Report stream verification for synthetic output separately if `ffprobe` is available. Host decode or seeing a thumbnail isn't phone playback evidence.
3. Use **Open File** from each download surface and single/multi **Share** via the Android chooser with those verified synthetic files. Select only an approved, already installed **local/offline** viewer or receiver in a different app, not the original browser or an upload destination. Verify the actual recipient opens/reads the intended file(s); merely showing the chooser is not delivery. Record Open, single-share, multi-share and playback separately; mark a missing receiver/capability as **Blocked** and do not infer write denial or wider access checks from playback. If mux fails, do not open or share incomplete output; use other verified synthetic downloads if already available, or mark the dependent cases **Blocked**.

Stop the two host fixtures and remove only the reverses you added. Return `out/android-test-report-4.md` with the ZIP and exact APK identities, device/API/ABI/page size/WebView (no serials), and one result per step actually checked. Include before/after step number, selected tab and dummy-login status, each media output and playback/receiver result, and concise sanitized failure lines with expected/actual. Label carried-forward results and unrun or blocked checks explicitly. No credentials, browsing data, or unsanitized logs. Signed release, 16 KiB runtime, full process-death/upgrade cases and paired PERF-07/08/09 remain open.

## What the revised-source retest establishes

The second report identifies commit `7932d396ad6568c7a31c55338fa8ffb44b54dd40` (its branch ZIP SHA-256: `72bcc0b5d3fa4abd10160cef5047e0e83b385a9f9edce8900d9417bcbae28993`). It used JDK 17, Platform 34 and Build Tools 35.0.0 on macOS, plus a Galaxy S21 FE on API 36, ARM64, **4 KiB** pages, WebView `137.0.7151.115`. The report says the original app remained installed and untouched. These are historical results for that build; see the third report above for resolution of these three findings.

| Area | Reported result | Targeted follow-up |
| --- | --- | --- |
| SDK and release | Debug/test and minified unsigned release assembly, JVM tests and lint passed. The 7,294,459-byte unsigned APK was built, but the verifier stopped at the `aapt2` minimum-SDK field, before package alignment checks. | Rerun `--inspect-only` using the updated script; it accepts both `sdkVersion` and `minSdkVersion` and still requires exactly 26. A matching existing unsigned APK (SHA-256 `ebf0e78e9fb18f534cd5471055bb12f7f201a45859b487c72655bab8a327b34a`) can be reused for this tooling-only check. |
| Regular device suites | **15/16** suites passed, including Agent settings/Stop (7/7), privacy admission/cleanup (3/3), and separate-UID download grant/revoke (4/4). Native Back reached index 1 and capture returned bytes, but the restore assertion failed. | Rebuild the debug/test pair and rerun `TabSessionDeviceTest`. Compare restored index and URL pairs separately. WV-01 and PERF-01-R stay open. |
| Opt-in named profile | Seed isolation passed. The name was missing before the reopen could check markers; fresh-process deletion was not run. Cleanup reported success, which does not prove the old profile was durably registered or deleted. | Run all four phases with a new ID. Require persisted cookie/DOM markers in the cold reopen and a subsequent successful deletion before claiming those behaviors. Private tabs stay disabled. |

In [Chromium 137's history query](https://github.com/chromium/chromium/blob/137.0.7151.115/content/browser/renderer_host/navigation_controller_android.cc), the list can expose the pending Back index. [State serialization](https://github.com/chromium/chromium/blob/137.0.7151.115/android_webview/browser/state_serializer.cc) instead uses the last committed index, even when `saveState()` returns a list at the pending index. The reported equality-failure hashes match index 1 vs. index 2 with identical URL pairs, consistent with capture before commit. The fixture now waits for both the native history callback and a distinct renderer `popstate` marker **before** capturing. It does not relax the restore check or change production tab recovery.

## What the first report establishes

The report describes a Galaxy S21 FE (`SM-G990E`), Android 16/API 36, ARM64, **4 KiB** pages, and WebView `137.0.7151.115`. It reports separate installation of `com.browser` beside the original app, successful debug/test assembly and package checks, and image-search JVM tests and lint passing. **14 of 16 device suites were reported passing; two WebView suites failed.** The run used JDK 21 rather than the requested 17. APK and input digests were supplied, but the report did not identify the exact source commit/ZIP hash or include the failing task/assertion excerpts. Treat these as reported results for that run, not a pass on the revised source.

| Area | Evidence reported | Still open |
| --- | --- | --- |
| Downloads | v7→v8 in-place upgrade preserved 100 synthetic completed rows. History and exact read-grant/revoke fixtures passed, including a separate shell UID. | Full DL-01/PERF-09-R: paused/queued checkpoints, partials, integrity and process-death recovery. DL-04 system chooser and read delivery; DL-02/03/05/06 service, scoped requests, blob and ARM64 playback. |
| WebView | Lifecycle and script-policy fixtures passed. `TabSessionDeviceTest` failed waiting for native index 1 after `goBackOrForward(-1)`, before capture/restore. The provider probe failed deleting a named profile. | WV-01/PERF-01-R remain partial. Verify manual lifecycle, restore and playback cases even where WV-02/WV-03 fixtures passed. PR-01 needs actual entry-point refusal and targeted clears, including restart. The named-profile probe is separate from those product checks. |
| Assistant and local data | Review, reminder persistence, legacy chat, fake-provider Stop, bookmark DB and thumbnail fixtures passed. | Manual approval and reminder lifecycle, installed legacy upgrades, UI/preview cases, and authorized real HTTPS cancellation. The fake provider cannot establish network teardown. |
| Filters, tabs, omnibox, media | Reported fixture passes are useful correctness evidence. | Run remaining [manual cases](../DEVICE_TEST_CHECKLIST.md), including rule review, image URL sharing and CBZ viewing. Fixture passes are not the paired physical PERF-07/08/09 results. |
| SDK and release | App/downloader JVM and lint failed; R8 blocked the release task. | Rebuild and verify unsigned packaging; signed release, exact size cap, 16 KiB hardware, notices and provenance are separate gates. |

## 1. Rerun SDK preflight on the revised source

1. Extract to a new folder. Record the exact source identity: the supplied base and Git tree for a local snapshot (or a commit for a GitHub ZIP/worktree), and the SHA-256 of the ZIP you actually used (`shasum -a 256 "$zip"`). Compare against the sender's digest. No patch or Git initialization is needed.
2. Use JDK 17: `export JAVA_HOME=$(/usr/libexec/java_home -F -v 17)`. Verify `"$JAVA_HOME/bin/java" -version` and `./gradlew --version` rather than silently using JDK 21. Use Platform 34 and Build Tools 34.0.0/35.0.0; verify the [input hashes and baseline trees](antigravity-device-test.md#2-verify-the-source-handoff). No SDK retarget.
3. Run the [Gradle preflight and unsigned APK inspection](antigravity-device-test.md#3-build-on-macos-and-check-the-package-before-touching-the-device). Record every task's outcome, including all three modules' JVM/lint tasks. If one task stops a combined invocation, run the unattempted tasks separately where possible; don't report them as passes.

The revised source replaces the four unsupported `Files.readString` calls with explicit UTF-8 reads. It preserves atomic close-on-exec on API 26 using [Oreo's Bionic flag value](https://github.com/aosp-mirror/platform_bionic/blob/android-8.0.0_r36/libc/kernel/uapi/asm-generic/fcntl.h), uses the public constant on API 27+, and retains no-follow, nonblocking and descriptor identity checks. The light-navigation theme item is in `values-v27` and the preview WebView has a sized parent. JSpecify is a declared dependency so R8 can resolve jsoup's annotation types; missing classes are not suppressed. Host checks do not establish that Gradle lint, R8 or device behavior now pass.

## 2. Rerun the affected Android checks

Verify package badging, instrumentation target, provider authority, digests and signing certificate **before** replacing only the confirmed disposable test pair. Recheck the original app's presence without opening its data. Use the same lab signer for upgrades. Record the actual API, ABI, page size and WebView again; don't roll back a provider to match the first report.

On the unlocked device, run these separately with `adb -s "$serial" shell am instrument -w -r -e class FULL_CLASS com.browser.test/androidx.test.runner.AndroidJUnitRunner`:

| Class | Expected tests | What changed / what to check |
| --- | ---: | --- |
| `com.privacy.browser.BrowserPrivacyTest` | 3 | Real private admission refusal and cleanup reporting. **Clears all regular-profile cookies**, so run only on the disposable install. Run before the named-profile probe. |
| `com.privacy.browser.TabSessionDeviceTest` | 2 | The foreground fixture uses actual taps, then waits for native Back to commit index 1 before saving. Restore must return index 1 with all three URL pairs, and mismatched or empty state must still be rejected. Record whether load, input, Back commit, capture or restore failed. If restore fails, report the actual index and whether the ordered URL pairs matched. |
| `com.privacy.browser.downloads.CompletedDownloadGrantTest` | 4 | Recheck the real provider and separate UID. Exercise the API 26 flag path on a supported API 26 device/emulator as a separately labeled minimum-API check. |

No port reversal is needed for these three suites. Keep focus on the foreground history fixture during its run. Also open the bookmark preview editor on a synthetic page, scroll, move the crop and save a cover; verify it scales/crops correctly. Check light navigation icons on API 26 and a recent API where possible. Report missing configurations as untested.

For the restored Agent settings panel, run `com.privacy.browser.ai.AssistantSessionDeviceTest` (7 tests, fake provider) with the [assistant loopback fixture](../README.md#assistant-checks-on-android-not-yet-run). Open the gear in both themes and check the panel at large text sizes with the keyboard open. Exercise **Gemini API**, **Off**, Save and Cancel using dummy values; do not send a real request. Recipe review, reminders and cleanup must still use their existing consent flow.

## 3. Run the named-profile probe in separate processes

The original test **already detached and destroyed** its WebViews. [ProfileStore](https://developer.android.com/reference/androidx/webkit/ProfileStore#deleteProfile(java.lang.String)) can reject deletion of a profile loaded in the process. This probe separates live isolation, persistence after a process stop, and deletion without loading the named profile first. It checks two synthetic HTTPS origins and both cookies and DOM storage. It does not prove disk erasure or qualify private tabs.

[Chromium 137's profile store](https://github.com/chromium/chromium/blob/137.0.7151.115/android_webview/browser/aw_browser_context_store.cc) lists names from the persisted registry without loading each profile. [Registry writes](https://github.com/chromium/chromium/blob/137.0.7151.115/android_webview/browser/aw_feature_list_creator.cc) use a JSON preference store with a [10-second default write interval](https://github.com/chromium/chromium/blob/137.0.7151.115/base/files/important_file_writer.cc). The earlier six-second wait may have let the instrumentation process end before the registry was written; flushing cookies doesn't flush the registry. The probe now stays alive for 15 seconds after seed, delete-and-recreate, and cleanup to give background writes time. This is a bounded grace period, not proof of a flush. If the name is still absent after a successful seed with the same ID, report the cold-start failure; do not create a replacement to make the phase pass.

After the disposable APK and device checks above, use a new probe ID for each attempt and keep it constant across the phases:

```sh
: "${serial:?Select the authorized ADB device first}"
probe=$(uuidgen | tr '[:upper:]' '[:lower:]')
run_profile_phase() {
  adb -s "$serial" shell am force-stop com.browser || return 1
  adb -s "$serial" shell am instrument -w -r \
    -e class "com.privacy.browser.ProviderProfileDeviceProbe#$1" \
    -e profileProbeId "$probe" \
    com.browser.test/androidx.test.runner.AndroidJUnitRunner
}
```

Run **one line at a time** and let instrumentation finish on its own (some phases now take at least 15 seconds). Check that the requested test ran and passed before continuing. A zero shell exit or a JUnit summary by itself is not enough; if `MULTI_PROFILE` is unavailable, record the probe as **Skip**, not Pass. Do not clear app data, reinstall, or run the cookie-clear suite between phases.

```sh
run_profile_phase seedDistinctMarkers
run_profile_phase persistedMarkersRemainIsolatedAfterRestart
run_profile_phase deletionInFreshProcessRetiresOnlyTheNamedProfile
```

The last phase checks the name is removed from the provider's list and a recreated profile has no markers while the default profile retains its distinct markers. After the attempt, use `run_profile_phase removeProbeResidueInFreshProcess` with the **same** ID to retire the recreated or leftover synthetic profile and remove only the fixture's default-profile markers. Record cleanup failure rather than deleting other profiles or wiping the app. These opt-in phases are reported separately from the 16 regular suites.

## 4. Return evidence; then continue the device matrix

- Return a sanitized report with source/ZIP identity, JDK/SDK, APK and signer digests, device/provider details, and per-task and per-test results. Include the failing task or method, the sanitized assertion/stack lines and expected vs. actual state. For the profile probe, include each phase separately. Exclude device serials, cookies, real page data and signing material.
- A successful targeted rerun is not full checklist acceptance. Resume the [manual and matched physical matrix](device-handoff.md) using builds made from the same revised source. Keep **PERF-07/08/09** open until the matched measurements exist; the earlier testing-branch push was not a performance pass.
- Leave private tabs, image-byte uploads and direct SAF disabled. Do not sign or distribute. The release owner still needs the exact signed/minified ARM64 artifact at **≤20,000,000 bytes**, tested playback and grants on supported 4 KiB and 16 KiB hardware, and resolution of [provenance, notices and historical credentials](release-readiness.md).
