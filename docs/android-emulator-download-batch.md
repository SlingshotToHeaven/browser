# Android emulator: download test batch

One branch-scoped CI run exercises the download, media and comic checks together on a fresh Android 14 / API 34 x86_64 emulator. It archives the tested source, builds the *normal* debug APK plus a separate instrumentation APK, and records both installed APK hashes. Each named device case starts from a fresh browser profile on that same disposable emulator. A failed method does not skip later methods. The app is not repacked, given root or granted broad storage access.

The [batch driver](../tools/check_android_download_batch.py) runs real Android queue and WebView paths using [loopback-only fixtures](../tests/fixtures/download_batch.py). When testing process death, the host stops the app while the real worker is held at a persisted range checkpoint, then relaunches Downloads directly. SQLite, the saved bytes, HTTP intervals and the exact published file are checked independently. The JVM/host FFmpeg results are generated separately; a host pass is not reproduction on Android or evidence that its bundled JNI loaded.

The workflow retains only `download-batch.json` and `download-host-tests.json` for one day. Checks annotations retain the device record (`DOWNLOAD_BATCH_PROOF`, numbered base64 chunks and their combined SHA-256) and the host summary (`DOWNLOAD_HOST_PROOF`; `DOWNLOAD_HOST_SHA256` in the final run). No APK, database, raw trace, video, or credential values are uploaded. **Blocked** means capability/preflight prevented the actual case, not that the behavior passed. **Not run** means no named test result was obtained. A failed test can mean a product assertion or an inconclusive test harness; the reason is recorded per case.

## Final result

The [final grouped run](https://github.com/SlingshotToHeaven/browser/actions/runs/44715157196/job/136216546298) tested `e84f14f0f414f26723a3825ab822f74d911fd3ec` on an API 34 x86_64 emulator with 4 KiB pages and ARM64 translation. It recorded **43 passed, 5 failed, 1 blocked, 0 not run** out of 49 Android cases. The CI job is red; the host suite was green.

| Android group | Passed / total | Verified behavior or remaining result |
| --- | ---: | --- |
| Queue and service | 5 / 5 | Wi-Fi and power/schedule transitions, live limit changes, foreground service recreation on the same attempt, and exact recovery after a real force-stop. The interrupted job resumed at 65,536 and published the expected 294,917 bytes; the manual pause stayed unchanged. |
| Storage | 3 / 4 | Document/tree refusal, app-specific external publication, and exact suffixed publication under public Downloads without replacing a neighbor. `storage-link` **blocked**: the ordinary app UID could not make a same-directory hard link. |
| HTTP | 3 / 3 | Same-origin probe and body carried scoped headers; foreign and return hops were anonymous. External share-link intake was anonymous and private intent was refused before a fetch. |
| WebView blob | 7 / 8 | Dismiss-before-fetch, waiting admission with exact bytes, cancel, reload, tab close, short body, timeout and occupied destination. `blob-foreign-frame` **inconclusive**: the frame loaded, but the fixture did not acknowledge a trusted pointer event. No passing containment result was recorded for that case. |
| Stream downloads | 5 / 9 | Non-media, short, missing-audio, malformed mux input and DASH refused without publication. Separate A/V, ranged HLS/fMP4, HLS/MPEG-TS and AES-128 HLS all **timed out**. In each, the row was still `RUNNING` in the mux phase and the worker stack was inside `nativeFFmpegExecute`. The run did not establish playback or whether the same behavior reproduces outside ARM translation. |
| Comic archives | 10 / 10 | Exact ordered app-private CBZs with a repeated page and a collision suffix; missing/short/stalled/refused plans; cancellation on Close/navigation/Home; actual verifier approval and Back disposal. ZIP checks were in-app test assertions, not an external viewer. |
| Media discovery | 3 / 3 | Explicit options, stale SPA callback retirement and settings switches on synthetic pages. |
| History and grants | 7 / 7 | SQLite v7→v8 migration, cursor results, list paging, Select All with 57 matches and 48 loaded cards, exact read-only provider grants, and replacement/revocation behavior. Separate chooser delivery is linked below. |

The **host JVM suite passed all 167 tests** across 20 classes, including all three playback/containment methods with FFmpeg `n9.0.2-17-g2a571b6068-20260930`. This verified real host decode of separate A/V, ranged fMP4 and MPEG-TS and rejection of missing required audio. It did not exercise Android JNI. Local checks at the same source also passed (91 Python tests, 17 focused Node tests, shell syntax and 23 pinned asset hashes).

Verified Checks evidence (SHA-256):

| Record | SHA-256 |
| --- | --- |
| Android results | `2e9e2d0223666f58129709c7e8897982a2dc5685761f52a3e57a1d6f360a12cf` |
| Host JVM results | `45083b5fae30e006c356067897c301e1d9929e6d084a4f23a120f4f4d979796a` |
| Installed debug APK | `c8e7fd2a0771410e199d2900e563bbe47791027fd8eb3bc719ac19c005b54596` |
| Installed test APK | `2e5e127ef250bddb5f6eecc016650ef56ec38b50245ef26f35307c3096ffdd11` |

## Earlier runs and harness fixes

[Run 44710174515](https://github.com/SlingshotToHeaven/browser/actions/runs/44710174515) exercised the full matrix from `fa45c1e76811ca6747cc6cc39755862127b1cac2`: **27 passed, 13 failed or inconclusive, 9 blocked**. The first run verified Wi-Fi, power/schedule and limiter transitions, a mid-transfer force-stop and exact range recovery, document/tree refusal, app-specific external and public Downloads publication, private-intent refusal, most comic dialog cases, and the existing history, provider and media-discovery suites. The ordinary app UID could not make a hard link. The device report SHA-256 is `0813b0a4451e5488be80bcbd84f670767f205b5f315e59d0602d8e112f0e9ea0`; it is evidence for those observed cases only.

Several WebView checks could not bind jobs because the test read a queue projection that omits filename and destination; the harness changed to loading the full row by ID and matching the destination directory. Other test-only fixes used ActivityManager to relaunch Downloads and exercised the actual verification WebView. The first runner lacked FFmpeg: eight Android media cases were blocked, and the JVM ran **164 passed, 0 failed, 3 skipped** (the playback class).

[Run 44712657898](https://github.com/SlingshotToHeaven/browser/actions/runs/44712657898), `dc07c5ae7c56865a4620c10420b392cc42c8a04e`, reran all 49 checks, with **40 passed, 8 failed, 1 blocked**. It verified service recreation, the scoped WebView and external HTTP cases, six additional blob cases, both comic verification paths, and refusal of non-media segments, missing audio, broken mux input and DASH. The remaining failures were power/schedule (an extra request during the battery transition), short blob and short HLS (different rejection messages from the ones asserted), foreign-frame input (no trusted click acknowledgment), and four positive mux cases (no completed publication before timeout). Device record SHA-256: `e79edf734aed3e5fa5f54600d12ed9d5e0307d1943e3170d9eb96d1fac74f50a`.

The second run's pinned FFmpeg 4.1 binary generated the device media successfully, but does not support the production `empty_output_stream` flag. The JVM reported **134 passed, 2 failed, 0 skipped**; one playback case passed and two failed. Gradle stopped before the three app JVM suites. The final rerun uses a checksum-pinned FFmpeg 9.0.2 CLI and Gradle `--continue` so a failure does not skip independent suites. The CLI generates fixture media and runs host playback only; Android uses the packaged ARM64 libraries. The test APK and harness evolved; production sources and native assets did not.

## Prior passes (not rerun as part of this batch)

- [Interrupted exclusive copy, refusal and explicit Retry](android-emulator-download-mid-copy.md#verified-result): app-private, partial final name preserved, exact Retry with no new fetch.
- [Interruption after a full copy but before SQLite commit](android-emulator-download-recovery.md#verified-result): uncommitted bytes preserved and not adopted as complete.
- [Installed v7 → v8 upgrade and actual chooser delivery](android-emulator-download-upgrade.md#verified-result): real same-ID upgrade, resumed ranges were anonymous, and both Open surfaces and single/multiple Share reached a separate app with only the selected bytes readable.
- [Report 4 on an earlier ARM64 debug APK](android-test-followup.md#latest-manual-report-settled-restart-and-debug-playback) reported MPEG-TS and ranged fMP4 playback on a 4 KiB phone. That does not reproduce the final batch's four cases on its exact APK, or establish that translation caused the timeouts.

## Later follow-up

A [two-case recheck](android-emulator-functional-followup.md#targeted-recheck) separately verified the foreign-frame attachment refusal after a trusted touch and actual child click, with a working top-level prompt control. Another [API 34 run](android-emulator-functional-followup.md#public-downloads-interruption) verified interruption and exact checkpoint Retry in public Downloads. Neither run exercised hard links or native mux.

## Scope limits

The batch does not qualify minimum-API devices, interrupted copies in public Downloads, removable storage, a hard-link process-death cut, a separate CBZ viewer on a supported destination, the complete native ARM64 playback matrix, 16 KiB runtime, the signed release, or paired performance traces. No phone or lab test ran in this batch. Download limiter checks describe app body reads and behavior, not socket bytes, radio traffic or battery impact. No background wakeup timing promise was tested. See the [device checklist](../DEVICE_TEST_CHECKLIST.md#downloads-storage-and-media-steps-0306) for the broader gates.
