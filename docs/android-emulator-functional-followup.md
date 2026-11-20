# Android emulator: functional follow-up before native playback

These are independent checks on disposable GitHub-hosted Android 14 / API 34 x86_64 emulators. The workflows built the normal debug app and separate instrumentation APKs from source archives and checked the installed hashes. No production code, Gradle configuration or native libraries changed. The [download batch](android-emulator-download-batch.md#final-result) and [browser recovery batch](android-emulator-browser-recovery.md#verified-result) keep their original results.

## Browser checks

[Run 44723185762 / job 136243872689](https://github.com/SlingshotToHeaven/browser/actions/runs/44723185762/job/136243872689) tested `3741940eb0b277c6e79d027e330d3c508324cea9`, on 4 KiB pages and `com.google.android.webview` `113.0.5672.136`. It recorded **49 passed, 2 failed, 0 blocked, 0 not run**. Each selector started with a fresh disposable app profile; a failing selector did not skip the rest.

| Check | Initial result | What was observed |
| --- | ---: | --- |
| Private entry | 4/4 | Cold and warm external intents, native tab picker and a real touch on bundled Home all displayed native refusal without admitting a private or extra regular tab. No denied URL reached the fixture. The warm control kept its existing tab and synthetic login. This is separate evidence from the interrupted private-entry repeat in the earlier recovery run. |
| Selected-tab launch | 2/3 | Both the missing native-state fallback and admitted incoming link preserved the dormant 30-row session inventory. The former fetched only `/tab/30`, the latter only `/incoming`. In the URL-only selection case, `/tab/30` reached the server but not `/tab/7`. Instrumentation said `Process crashed.` without a named terminal result; this run did not establish why. |
| Foreign-frame attachment | 0/1 | Trusted touch and click on the attachment as a top-level page opened a real confirmation; the test cancelled before enqueue. The same page loaded in the foreign-origin frame, but the hit-point check failed before trusted child input was delivered. No containment conclusion from this run. |
| Tab residency and session | 4/4 | The 30-visit bound and Back-stack recovery, two grid/preview lifecycle checks including renderer loss, and rejection of a platform state bundle without usable native history passed. |
| WebView interception and script policy | 6/6 | Two request-interception and four script-policy cases passed against synthetic resources on this provider. Not a YouTube playback or fallback-provider pass. |
| Assistant, reminders and legacy data | 18/18 | Native review (2), sheet/session lifecycle with fake provider (7), reminder persistence (3), and legacy transcript/data handling (6). Not live HTTPS teardown, installed-version upgrade, reboot/clock scenarios, or a deployed service check. |
| Bookmarks and thumbnails | 15/15 | 10 bookmark SQLite and migration cases plus 5 local thumbnail cases. These do not establish full bookmark UI or installed-upgrade parity. |

### Targeted recheck

[Run 44724535955 / job 136248494092](https://github.com/SlingshotToHeaven/browser/actions/runs/44724535955/job/136248494092) **passed both remaining cases** on `2286f50e911310e2c0ff2f235753cf59c5b9ff04`, using the same API, ABI, page size and WebView version. This was a two-case recheck, not a new green 51-case run.

- **Foreign frame:** The fixture now serves a valid icon on both origins rather than risking a missing subresource hiding the already loaded frame. The same attachment opened a real confirmation at top level; the test cancelled it. In the child frame the fixture server independently observed one trusted touch and one actual click. The browser showed no confirmation and created no job, page transfer or file. This establishes refusal for this embedded foreign-origin case, not for every provider, frame layout or blob transfer.
- **Selected-tab launch:** All 30 URL-only rows kept their identities. Exactly one view was live before explicitly selecting row 7, and the only document GETs were `/tab/30` then `/tab/7`. The earlier instrumentation crash did not recur; its cause remains unknown.

## Public Downloads interruption

[Run 44723568561 / job 136245207360](https://github.com/SlingshotToHeaven/browser/actions/runs/44723568561/job/136245207360) **passed** at `6608fc7f3a91c68ca37210a1bbe43f24196b10e7`. The earlier app-private cut still stands separately. The earlier `run-as` probe could not create the shared folder; this follow-up used the ordinary app process to create it, write/rename a synthetic neighbor and select the public Downloads path. The independent shell observer matched the neighbor's file identity and hash. The app used that path in the real confirmation, not a fallback. No root, broad storage grant, or change to app storage policy was used.

- **Cut:** The real publisher was held after 32 writes of 65,536 bytes. The final-name entry was 2,097,152 bytes, with a separate complete 4,194,304-byte staging file. SQLite was `RUNNING` before publication commit; the app was force-stopped while still held.
- **Relaunch:** The app preserved both files and the unrelated neighbor, reported `FAILED` because the reserved name was occupied, and showed diagnostics instead of offering Open/Share. Selecting just that failed job and tapping Share was refused.
- **Retry:** After the harness moved aside the known partial without replacing another file, explicit Retry completed the same job with all 4,194,304 bytes and SHA-256 `3f594a89bfe61cdd0fdeb4592211e70737211891cfdcde17d52464d9648f66b2`. It preserved the moved file and neighbor, removed staging, and issued **zero** additional HTTP requests. The only requests were the original range probe and conditional transfer.

This verified interrupted **exclusive copy** under public Downloads on this emulator. It does not cover hard-link publication, removable storage, power loss, or access by other storage-aware apps to a short public final-name entry.

## Evidence and remaining gates

The Checks annotations encode only scoped result records. The JSON-only Actions artifacts were retained for one day. The raw JSON digests below were verified against the Checks annotations and their run, job and source commit. The browser reports also match the exact selected test inventories.

| Report | Result SHA-256 | Installed debug APK SHA-256 | Installed test APK SHA-256 |
| --- | --- | --- | --- |
| Browser checks | `f315105ccc7535b913e4c16783ce3a3f512034f224d1274842df3567bfc3eed6` | `f15d4de5c6697d9fdb5aeb54afd8b431e7f89072b7f95f2576707482e1daefbb` | `1288533233c62a07a7bcd4474cfedf46bf178fe31a925864f74edfc510be96ea` |
| Two-case recheck | `b53a0347f9e1c211a79fe1d3d5c90de4d812c9ab1d25f553b1230530c303f0e6` | `5f24b5ff3473f256931eac67a16e51ba67ba346af06d3000caa39bfb33060925` | `ae7b7289be2dc005afe3edb251c852387cb0c1e13b9a91fdb920b6e2bb2755ef` |
| Public Downloads cut | `508e5e31057dfde786d97f36b368e341d4545c16064fc6490db013fd8c4cc823` | `dfee4d07f5ace073d38a171e90a01af873330d40bc70518685558d04686e4379` | `ac3cb02337824008535fd35d067a60e2354215bcfbd0d298a608f091ef85bba9` |

Local checks: 108 Python tests, 27 Node tests, shell and fixture script syntax, 23 pinned checksums and all 8 checkout ARM64 ELF checks passed. The separate local Miniflare suite passed 6/6 without a deployment. These are not Android, JNI runtime, or release evidence.

The physical ARM64 follow-up is intentionally last: retest just the four stalled **positive** mux paths (separate A/V, MPEG-TS HLS, ranged fMP4 HLS and AES-128 HLS) and require actual playback with both tracks. The x86_64/translation stalls do not establish the cause or that they reproduce on ARM64. The separate PERF-07 paired gain remains unverified; this work did not rerun timing, remove Step 18, verify native 16 KiB runtime, or qualify a signed release.
