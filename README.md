# Privacy Browser

Android WebView browser with ad blocking, tab/session management, and optional media download, image search, and AI features. The Android app is in `app/`, with `downloader/` and `image-search/` as library modules. The optional v2 filter snapshot Worker is separate from the APK build. The legacy agent companion is retired.

See [the remediation plan](REMEDIATION_PLAN.md) for known issues and the order of fixes. Use the [physical-device checklist](DEVICE_TEST_CHECKLIST.md) to track what still needs Android or real hardware and to record results. A successful debug build or host-side test run is not a release, privacy, or device-performance sign-off.

## Build the debug APK

- Use a **JDK 17** (Temurin 17 in CI), Android SDK Platform **34**, and Build Tools **34.0.0**. Install the packages through Android Studio or `sdkmanager`:

  ```sh
  sdkmanager 'platforms;android-34' 'build-tools;34.0.0'
  ```

- Set `JAVA_HOME` to the JDK or put its `java` and `javac` on `PATH`. Set `ANDROID_HOME` to the SDK, or let Android Studio create `local.properties` with `sdk.dir`. Don't commit machine-specific SDK paths.
- Run `./build.sh` from a Bash environment. It uses the checked-in wrapper, not any Gradle on `PATH`. You can also run `./gradlew :app:assembleDebug` directly (`.\gradlew.bat :app:assembleDebug` on Windows).

The wrapper pins **Gradle 8.7** and verifies its [published distribution checksum](https://github.com/gradle/gradle-distributions/releases/tag/v8.7.0). The Android Gradle Plugin is **8.6.1**. A clean installation needs network access to download Gradle and the Google/Maven dependencies. The debug APK is under `app/build/outputs/apk/debug/`; no release signing configuration is needed for this workflow. The app targets API 34, with minimum API 26; the checked-in FFmpeg libraries are ARM64 only.

The installed ID is **`com.browser`** (debug label **Browser (Test)**; instrumentation ID `com.browser.test`). The Java namespace stays `com.privacy.browser`. This is separate from the old `com.privacy.browser.injection` and `com.privacy.browser.injection.debug` installs and does not reuse their data. Debug and release of the new app use the same package ID, not necessarily the same signer. Check the target ID and use only the disposable test install for resets, seeds and ADB instrumentation. For a Mac and a connected device, use the [Antigravity test instructions](docs/antigravity-device-test.md).

## Verify without a device

Run the same smoke checks as the **Verify** workflow using Python 3, Node.js 22 or later, and npm. These tests use disposable files, databases and fake settings; they do not deploy the Worker, start a sidecar, or call a live provider.

```sh
bash -n build.sh
sh -n gradlew
python3 -B -m unittest discover -s tests -v
node --test tests/blob_intake.test.mjs tests/youtube_policy.test.mjs tests/comic_detector.test.mjs tests/filter_work.test.mjs tests/media_discovery.test.mjs
npm ci --prefix cloudflare-worker
npm test --prefix cloudflare-worker
```

With the JDK and Android SDK installed:

```sh
./gradlew --no-daemon :app:assembleDebug \
  :app:assembleDebugAndroidTest :app:testDebugUnitTest :downloader:testDebugUnitTest :image-search:testDebugUnitTest
./gradlew --no-daemon :app:lintDebug :downloader:lintDebug :image-search:lintDebug
```

The queue tests control the order of stop, drain, retry and callback delivery, and check that waiting jobs recover under new constraints without unpausing manual pauses. The Python suite also runs the Java schema and queue predicates on host SQLite, including upgrading existing rows and closing and reopening a database.

The direct HTTP fixtures serve known bytes over disposable loopback HTTP. They check the actual files after full and ranged downloads, pause and recovery, ignored or malformed ranges, changed or missing validators, short or overlong responses, and invalid checkpoints. These JVM tests don't run Android SQLite or certify device publication behavior. The filter snapshot tests cover exact replacement, append-only merge, unsupported inputs, concurrent publishes, and rollback. Assistant tests use fake responses and alarm ports to check action approval and result pairing, not live providers or alarm delivery.

Two-origin loopback tests check that native fetchers re-read cookies for each authorized URL, drop browsing headers on cross-origin hops, and don't regain credentials through an HLS child or a verified range URL. The limiter tests share one bucket across readers and run a direct transfer and an HLS transfer against local servers. They exercise oversized buffers, pause/interrupt, and switching to and from unlimited. The host SQLite checks include removing legacy header values without discarding range checkpoints.

The blob fixtures exercise the page script's no-read-before-start and one-chunk-at-a-time behavior, plus frame/document matching, one-shot queue admission, timeouts, and cleanup in JVM tests. These checks do not exercise Android's WebView callback delivery. The [device blob fixture](tests/fixtures/blob_download.html) and [manual checks](REMEDIATION_PLAN.md#05-fix-stream-and-blob-completion-paths) cover that separately.

Stream containment tests use local HTTP and a substitute muxer. They make the server hold back a segment's remaining bytes until the prefix is on disk, and check failed ranges, short and interrupted bodies, AES padding, required audio, and mux/publication failures. To also run the optional playback checks, provide a current host FFmpeg with H.264 (`libx264`), AAC, and `-abort_on empty_output_stream` support (the host checks passed with FFmpeg 8.x):

```sh
FFMPEG_TEST_BIN=/path/to/ffmpeg ./gradlew --no-daemon :downloader:testDebugUnitTest \
  --tests 'com.privacy.downloader.engine.StreamDownloadPlaybackTest'
```

Those checks mux and decode generated separate A/V, ranged HLS/fMP4, and HLS/MPEG-TS files and verify that missing required audio fails. They do not load the bundled ARM64 libraries. WebView lifecycle, bundled mux/playback, and performance still require Android/device verification. Use synthetic profiles and providers; don't add real credentials, browsing data, or signing material to fixtures or logs.

## Release readiness (Step 21)

- The [Android verification handoff](docs/device-handoff.md) gives the SDK/device run order and identifies the matched source trees for the physical performance comparisons.
- See the [release input and licensing inventory](docs/release-inputs.md) for dependencies, bundled assets, observed native build flags, and checksums. Source provenance and distribution rights are still open.
- See the [signed artifact runbook](docs/release-readiness.md) for unsigned release assembly, owner-managed signing and fingerprint verification, the 20 MB ARM64 APK budget, Android 16 KiB checks, and staged recovery. Gradle no longer loads a release key. No size, upgrade, or runtime result is claimed without the actual signed artifact. An unsigned CI check is not a release approval.
- The release candidate is intended for **ARM64** with Android API 26+ and a compatible installed WebView. Some script and download features need document-start or origin-aware messaging; an unsupported feature must not silently use a weaker bridge. Private tabs remain unavailable. Optional AI/image providers and the optional rules catalog are separate network services, not guarantees of private processing. See the feature sections below for exactly what is sent or stored.
- Report reproducible problems with a synthetic page/profile and the version or APK hash, Android/API, ABI, page size and WebView version. Keep signing details, secrets, URLs with queries, logs with browsing contents and device identifiers out of reports. Report suspected privacy or credential exposure privately to the maintainer before posting a public reproduction.

## What happens to queued downloads when the app restarts

The queue rechecks pending and interrupted downloads when the app reopens. Automatic waits for Wi-Fi, the configured schedule, or low battery can clear on their own; **Paused** means a manual pause and requires Resume. Disabling global Wi-Fi-only does not clear a per-download Wi-Fi requirement. Completed, failed and cancelled entries stay in history unless you remove them or explicitly retry a failed entry.

When a constraint becomes unmet, the current attempt stops and cleans up before its slot is reused. Retry can wait for cleanup; **Start over** and link edits require the earlier attempt to have stopped. If the reserved destination is occupied after an interruption, the app leaves both the destination and any checkpoint alone. Move the occupying entry before retrying, or queue a new download with another name. The app never assumes a file is complete simply because it exists.

Schedule checks are best effort while the process is alive and on reopening the app. Android can delay or prevent foreground service restarts; the app does not promise to wake at an exact time or resume automatically after a force-stop. A blob that is no longer available must be downloaded again from its original page.

## Recover a stalled HTTP download

**Retry** reuses saved progress only from a verified checkpoint. Resumes require the same resource and a matching strong ETag; old partial files, changed links, and servers without a suitable validator may require a fresh transfer. For a failed download, tap Retry and choose **Start over**. It makes a full request into a new `.part` file and leaves the old partial in its original directory. Queued downloads reserve distinct names (for example, `report_1.pdf` when `report.pdf` is already used). If another program creates a file at the reserved name before publication, the download fails rather than replacing it. Queue a new download to choose a new name.

## Verify a file before publication

Paste a trusted 64-character SHA-256 hash into **Expected file SHA-256 (optional)** in the new download dialog. A mismatch fails the download without publishing it. For a muxed stream, the hash must be for the final MP4, not one of its inputs.

Downloads are prepared in staging files. When a filesystem cannot link the verified file into place, the downloader exclusively creates and verifies a copy instead. During that copy, a file manager may see the destination name before the app reports completion. This path writes to filesystem folders, not SAF/content-provider destinations. The interrupted-publication policy has host coverage; Android filesystem behavior and process-kill recovery still need device verification.

## Verify browser chrome and renderer recovery

On a disposable debug install, run the two real-WebView checks and record `adb shell dumpsys webviewupdate` with the result:

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.privacy.browser.BrowserViewLifecycleTest
```

The renderer termination case requires API 29+ and a separate, terminable WebView renderer. An assumption skip is not a pass. Use the [tab trust fixture](tests/fixtures/tab_trust.py) for the cross-frame and background-tab checks:

```sh
python3 tests/fixtures/tab_trust.py  # on the adb host, in a separate terminal
adb reverse tcp:8011 tcp:8011
adb reverse tcp:8012 tcp:8012
```

1. Visit `http://127.0.0.1:8011/`. Both the top page and cross-origin frame should show `legacy=undefined, home=undefined`; the embedded app home must not render bookmarks. On the real home, recent bookmarks should appear only on providers with origin-aware messaging. Try home via Back, reload, session restore, and with an old `file:///android_asset/home.html` saved URL.
2. Long-press the slow link and open in the background. It must not load until selected. Switch to it to start the load, then switch back promptly. Edit the foreground address while that page finishes in the background; its callbacks must not change the foreground progress, security, shield, media badge, or fullscreen state. Press **Native history change** and verify the `/spa#local` URL appears from the native callback and is saved on restart (not while editing). Repeat with redirects and a rapid same-URL reload.
3. With two tabs open, terminate a test renderer. A provider may share the renderer: inspect each affected tab for a **Restore tab** action. Restore one, switch/close tabs, then restore the others. Unaffected tabs must still work. Repeat with an armed picker and an in-progress blob transfer; neither should act on a replacement page. Check the preview editor, comic verification dialog, and module syntax tester independently.

These checks are not run by host JVM tests or by assembling the APK.

## Tab session checks on Android (not yet run)

Selection changes only the selected regular tab ID; it doesn't trigger a WebView history probe or capture. Native history events have their own 350 ms quiet window and 1.5 s maximum debounce. The history probe compares the provider's ordered URLs and current index, including repeated URLs. Android doesn't expose an entry ID or changes to opaque page state when the list, position and addresses are all unchanged. Captures up to 4 MiB per tab pass to one background writer, with a 16 MiB budget per pending batch. Oversize or missing state falls back to the matching tab's URL; disk publication failures leave the previous committed index in place. See [selected-tab startup](#selected-tab-startup-on-android-not-yet-run) for the lazy restore and first-request checks.

On a **disposable debug install**, unlock the screen and leave the native history fixture in the foreground. Its debug-only Activity intercepts the synthetic page and sends two trusted taps, then checks native Back at index 1, capture, and restore. No server is needed for that suite. Record `adb shell dumpsys webviewupdate`; start the synthetic page server only for the follow-on manual checks:

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.privacy.browser.TabSessionDeviceTest
python3 tests/fixtures/tab_trust.py  # separate host terminal
adb reverse tcp:8011 tcp:8011
adb reverse tcp:8012 tcp:8012
```

1. **Switch and same-address history.** Open `http://127.0.0.1:8011/session` in two regular tabs. Build different history lengths with **Push same-URL entry**, and use native Back/Forward partway through one stack. On settled tabs, switch repeatedly, then choose **Repeat unchanged replaceState**. Expect no new `Browser.session.capture` for switching or unchanged history, and no `Browser.session.probe` caused by selection. Run the isolated selection case within the live-view budget (Step 16 evictions have their own `Browser.tabs.evict` trace). Look at `Browser.tabs.switch`, `Browser.session.publish` on `browser-session-io`, and `Browser.cookies.stopFlush` on `browser-cookie-flush` in Perfetto. Record app-owned writes and frame time for the same 1/10/30-tab workload before and after; don't count unrelated loads or grid preview drawing as history captures.
2. **Recreation, process death and missing state.** Change the app theme (or recreate the Activity) while a capture is queued; the selected tab and its Back/Forward position should survive. On the disposable install, inspect `adb shell run-as com.browser cat files/saved_tabs/index.json` and the referenced `state-<id>-<generation>.bin` files. After a checkpoint publishes, force-stop and reopen from the launcher; verify stable IDs, selection and both stacks, and repeat with a force-stop during a change. When interrupted, expect the previous or new complete checkpoint, not a mix of URL and unrelated history. With the process stopped, remove or truncate **one fixture blob**, then reopen: just that tab should reload its saved URL without borrowing another tab's history. Don't copy or share real profile files.
3. **Upgrade, close and clear.** With the same package/signing key, upgrade a throwaway earlier install with legacy `browser_session` preferences, a selected non-first tab, and a legacy `file:///android_asset/home.html` row. Verify order, selection, desktop flag and URL fallback (including app home). The old positional `.bin` files are intentionally not adopted because they can't prove tab identity. After migration commits and deferred maintenance has run, check the old keys and blobs are retired and the new IDs persist on another relaunch. While history is dirty, close a tab, then separately confirm **History and saved tabs** cleanup. After reported success, neither closed tabs nor legacy rows may reappear; bookmarks, downloads, icons and unrelated files must remain. Private admission is still disabled.
4. **Authenticated restart.** At `http://127.0.0.1:8011/session-cookie`, set the dummy persistent HttpOnly login cookie, verify **Signed in**, then background the browser and relaunch after a process stop. Repeat after an abrupt kill to measure the provider's durability window separately. The stop-time flush is asynchronous; a kill before it completes is not confirmed durable. Use no real credentials. After a confirmed cookie-only clear, the fixture should show **Signed out** without removing tabs.

The JVM fixtures exercise scheduling, generation-file publication and failure ordering, not OS process death, the Android parcel implementation, cookie durability or device performance.

## Selected-tab startup on Android (not yet run)

The browser reads the session index and prepares current protection on workers. It creates a WebView only for the selected saved tab or the tab needed by a valid launch link. The other saved rows retain their titles, URLs, settings, and history references without live views. The browser reads a saved blob only when its row is selected, and discards stale results after a switch, close, wipe, or Activity replacement. Renderer-lost rows still require explicit recovery. AI transcripts stay lazy; resume checks the active view's appearance and defers session housekeeping, reminder reconciliation, and download recovery.

The disposable `files/adblock/rules-index-v1.bin` is a versioned compiled index keyed to the exact four rule texts. A missing, stale, or damaged index is rebuilt off-main before browsing. `rules-v2.bin` (or the legacy/bundled sources when it is absent) is still authoritative. If current rules cannot be loaded, startup offers **Try again** and opens no pages rather than using a stale index or incomplete rules. NoScript and site exceptions still apply.

### Behavior first

Use a **disposable debug install**: seeding replaces its saved-tab index. Start the fixture on the adb host and grant/dismiss the notification prompt before measurement:

```sh
python3 tests/fixtures/startup_restore.py  # separate host terminal
adb reverse tcp:8014 tcp:8014
pkg=com.browser
activity="$pkg/com.privacy.browser.MainActivity"
python3 tests/fixtures/startup_restore.py --index 30 >/tmp/perf02-index.json
adb shell am force-stop "$pkg"
adb shell run-as "$pkg" mkdir -p files/saved_tabs
adb shell -T "run-as $pkg sh -c 'cat >files/saved_tabs/index.json'" </tmp/perf02-index.json
adb shell am start -n "$activity"
```

1. **One visible view.** The first page should say **PERF-02 tab 30**. Before selecting another row, wait at least 5 seconds, background/resume, and open the tab grid. Expect all 30 titles/URLs, only one `Browser.tabs.createView`, no `Browser.session.readBlob` for this URL-only seed, and no other tab's GET in the fixture log. Select tab 7: it should create and navigate only that row. Repeat using genuine native checkpoints from the [tab session checks](#tab-session-checks-on-android-not-yet-run). On process restart expect one selected blob read, one view and one native restore attempt; selecting another unopened row later should restore its own stack. Switching/closing rapidly, recreation, and confirmed **History and saved tabs** cleanup must not resurrect a stale row or navigate a hidden view.
2. **Launch routing.** Reseed before each cold case. Start `adb shell am start -n "$activity" -a android.intent.action.VIEW -d http://127.0.0.1:8014/incoming`. Expect just the **incoming link** view, 31 rows, no GET for the old selection and no old blob read. Repeat with `adb shell am start -n "$activity" --es com.privacy.browser.EXTRA_LOAD_URL http://127.0.0.1:8014/incoming` (without the new-tab flag): expect 30 rows and the selected row's old history replaced, not restored first. Invalid or private extras must not consume a tab or fetch their target. Repeat a valid link on a warm launch; it should not reopen any unrelated tab.
3. **Name the fallback.** With the process stopped and a fixture checkpoint backed up, try a row without a `state` token, a referenced file that is missing, and a truncated referenced file in separate runs. Expect `Browser.tabs.urlOnly` (no blob read), `Browser.tabs.stateMissing`, and `Browser.tabs.stateCorrupt`, respectively, under `Browser.tabs.urlFallback`. If WebView rejects a checksum-valid bundle, expect `Browser.tabs.stateRejected` after `Browser.tabs.nativeRestore` and a fresh view for the *same* row. Run `TabSessionDeviceTest` for provider acceptance and rejection with an empty platform bundle; an unreadable file is not a provider rejection. Back up and restore only synthetic files between cases.
4. **First-request protection.** Loopback addresses are intentionally exempt from network rules. On the isolated disposable device, temporarily point its HTTP proxy to `127.0.0.1:8014` over the adb reverse, and seed using `--index 30 --origin http://startup.perf02.invalid`. The fixture serves those synthetic hosts directly and never forwards traffic. Save `||tracker.perf02.invalid/network^` and `@@||tracker.perf02.invalid/network/allowed^` in **Custom Network**. Save `||tracker.perf02.invalid/custom^` and `##.perf02-hide` in **My Filters**. Run a cold restore and a cold incoming link with the synthetic host. The log **must** show the main page and `/network/allowed/…` but never `/network/blocked/…` or `/custom/…`; a run without the allowed probe is inconclusive. Repeat with a populated index, with only `rules-index-v1.bin` removed or corrupted, and with an older index copied back after changing the saved custom rules. Expect the *current* decisions each time. Use `http://excepted.perf02.invalid` for a site exception control: all probes should arrive only on that site with its exception enabled. Restore the device's original proxy afterward.
5. **Current policy before execution.** On the synthetic page, the cosmetic marker should be hidden on a normal filtered site. On a provider with document-start support, the first inline script should see the fingerprint guard. With a saved NoScript rule, the page should instead report **No page JavaScript ran** even on the first cold load. Repeat after a policy edit/reload. Verify an unreadable *authoritative* `rules-v2.bin` on the disposable install opens no page (unlike damage to the derived index), then restore the saved rule source and retry. Also run the separate [script policy checks](#noscript-and-page-script-policy) for provider-specific behavior.

### Measure separately

Pair each run with a matching APK from before Step 15 (same WebView, Android build, ABI, build type, fixture state and network). Save `adb shell dumpsys webviewupdate`, process state, and starting cache/parcel condition. Compare process-cold launch (force-stop), warm task resume (settled page, no process restart), and Activity recreation (process and rules warm; the selected view still needs restoration). Use 1/10/30 rows, and record native restore, URL-only, missing/corrupt state, and cold incoming link separately. For a warmed filter-cache comparison, let the derived write finish before starting the next sample; report cache-miss runs separately.

Time from the same launch or resume stimulus to the **first presented frame that shows the expected fixture heading**. A recording that includes the stimulus and screen, or timestamped display capture aligned to Perfetto's frame timeline, can identify the frame. `am start -W`, Activity first draw, `Browser.tabs.commitVisible` (a provider milestone), and `onPageFinished` alone are not that endpoint. Collect median/p95 from repeated samples, and count filter prep, blob reads, view creations and main-frame navigations using `Browser.startup.filters`, `Browser.session.readIndex`, `Browser.session.readBlob`, `Browser.tabs.createView`, `Browser.tabs.nativeRestore`, `Browser.tabs.navigate`, and the fixture log. Unchanged warm resume should not recreate views, navigate, or reapply settings to inactive tabs; changing appearance should update the active view and then other views when selected. Unfiltered startup requests or extra hidden views fail the behavior gate regardless of timing. No paired Android measurements have been collected here.

## Tab residency and previews on Android (not yet run)

| Resource (per main browser Activity) | Policy |
| --- | --- |
| Tab WebViews | The selected view plus **3** most-recently-used unpinned inactive views at rest. One extra allocation slot lets selection attach before eviction, so the allocation ceiling is **5 ordinary slots** plus the explicit pins below, **9 tab WebViews total**. Releasing a pin makes that existing view eligible for eviction; it doesn't allocate another view. After a low-memory or UI-hidden trim, keep **0** unpinned inactive views until normal use fills the budget again. Counts exclude other screens' WebViews and WebView provider processes. |
| Live pins | Up to **2** manual pins via **Keep tab live** on a committed regular Web page. Use this before switching away from a draft or inline playback; **Release live pin** makes it evictable again. Fullscreen is owned by the selected tab and exits on switch. Separately, at most **2** confirmed, queued or streaming page-blob sources are kept live; unconfirmed offers and normal HTTP downloads don't pin a view. A view can hold both types of pin. |
| Preview pixels | **2 MiB** of app-owned bitmap allocations, including the single in-flight copy (at most **192 × 192**, ARGB_8888, with a **144 KiB** reservation). The in-memory cache evicts by bytes and recency. This is not a cap on GPU/display-list memory, renderer surfaces, or process RSS. |

**Open in Background** queues a URL-only row. It doesn't send a request until selected. An evicted tab keeps its ID, address, title, desktop setting and its most recent compatible native Back/Forward checkpoint. Evicting a dirty tab tries that tab's history only and hands the bytes to the same asynchronous session writer before destroying the view. A missing, oversized, incompatible or provider-rejected checkpoint falls back to a safe URL rather than borrowing another tab's history. Ordinary selection inside the budget still does not save native history. Renderer loss still requires **Restore tab** or explicit navigation. Pins hold the existing view, not a backup of form edits, live DOM or playback; they don't survive view replacement, close, renderer/Activity loss or process restart, and can't override a security policy refresh. A provider can still suspend playback in a detached view. There is no process-wide timer pause.

The grid binds cached pixels or a placeholder without capturing a WebView. After a regular page is visibly committed, finished and still foreground for about 750 ms, it can take one asynchronous window copy if there isn't a snapshot for that page. Switching, navigating, opening the grid, losing focus, trimming memory and tearing down the Activity can cancel delivery; dismissal itself does not start work. A failed copy leaves a placeholder. Snapshots never go to disk; private, Home and secure-window captures are skipped, and private tabs remain disabled.

### Device procedure (PERF-03)

Use the same **disposable debug install** and loopback [30-row seed](#behavior-first) as Step 15. Run this class with `startup_restore.py` and `adb reverse tcp:8014 tcp:8014` active; it replaces the install's tab rows. Dismiss the notification prompt in advance. A provider with no separately terminable renderer cannot pass the termination case.

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.privacy.browser.TabResourcesDeviceTest
```

1. **Bounded use after 30 real visits.** Visit all 30 fixture tabs (not just seed them). Leave each page foreground for at least a second after it appears so both copy and eviction paths are exercised. On API 29+ collect `Browser.tabs.liveViews` and `Browser.tabs.previewBytes` in Perfetto with app tracing enabled for `com.browser`; record steady and peak counts separately. Confirm older unpinned rows are released while the selected and recently visited pages stay live. Give an early row two distinct addresses or repeated same-address entries, move Back, then visit enough other rows to evict it. Select it again and verify the saved address and its own Back/Forward position. Try a URL-only row separately. Check that opening a background link creates no view or request until selection.
2. **Grid first frame and cancellation.** Open the grid immediately after a new page and again after a settled visit. Inspect correct placeholders/previews and scroll or recycle cards; no image from another row or a previous navigation should appear. Close/swipe a card, dismiss while a preview would be pending, repeat during quick tab switches and close-all, and switch away from a selected row with a blob read pending. `Browser.tabs.openGrid` should not contain WebView drawing, a `Browser.tabs.previewCopy`, or unselected loads. Measure open-to-first-presented grid frame and frame overruns separately from visited-page copy work. If comparing builds, use the same device/provider, corpus and gesture pacing; no speedup is claimed here.
3. **Pins and release.** Start [the tab trust fixture](#verify-browser-chrome-and-renderer-recovery) and open `http://127.0.0.1:8011/tab-live`. Type a dummy draft. Pin it and a second tab, then try a third manual pin (should be refused). Switch through more than three other tabs and run a trim; the pinned view and unsaved draft should still be there. Try inline playback and record whether the provider keeps it playing offscreen, distinct from whether the view survives. Release a pin and revisit enough tabs to evict it. Also test fullscreen exit and a policy refresh; no obsolete callback or pin can keep that old view alive. With [the blob fixture](tests/fixtures/blob_download.html), keep two source tabs queued or streaming while switching and trimming; a third confirmation should be refused until a source releases its pin. Finish/cancel/reload/close, and exercise renderer loss: completed HTTP or ingested bytes don't depend on a live view, and a canceled source cannot resume from a replacement page.
4. **Pressure and owner loss.** With grid cards bound, send a trim supported by the device (`adb shell am send-trim-memory com.browser RUNNING_LOW` where accepted). Check that previews drop to zero bytes once any copy returns, unpinned inactive views are released, and dismissing the grid doesn't bring them back. Repeat with Activity background/resume, close during restore, and Activity recreation; only a fresh selected request is eligible. Terminate the renderer with the grid open on a supported provider and verify affected rows need explicit recovery, their previews vanish, and unaffected rows still work. The instrumentation invokes the trim callback directly; that does not confirm Android's natural trim delivery. If a provider fails copying, verify the placeholder fallback and record the missing capture evidence rather than a preview performance pass.

## Filter work on Android (not yet run)

The stock cosmetic scriptlet keeps its selector rules in a stylesheet and retains the content guards for inline recovery. Normal matches are not repeatedly rewritten. For inline-priority, CSS cascade, blocked stylesheet and resolved frame URLs, it checks a bounded set of nodes and marks inline work already done. Popup link and `window.open` checks still run independently; the overlay path reads style only for eligible elements and measures only high-z-index transparent/near-invisible candidates. It batches removals after the reads, with no idle polling. These are best-effort heuristics, not guarantees about every overlay on a changing page.

Per stock scriptlet, the work budget is 4,096 elements for the initial walk, 512 per added subtree, 32 queued roots, and 64 visits per frame (one delayed slice at a time where rAF is unavailable). A mutation delivery looks at at most 64 recent records and 64 added nodes. Inline fallback can also inspect up to 128 descendants across the slice to protect article content. Excess fallback work isn't replaced with a page-wide scan; ordinary CSS matching still applies. This does not cap Chromium style work, and custom scripts, other defusers and failed-subframe recovery are separate.

Network checks cache the navigation site policy plus one alternate, rebuilt on global/site/font/media setting or filter snapshot publication. Main-frame interception uses the destination, subresources use the native navigation target with a Referer fallback only when it's absent, and navigation overrides use the native committed address. None of these filter contexts grants credentials. For regular pages, counts are live in memory. The existing all-time blocked total is written by a serial worker about two seconds after the first unsaved block in a window (an abrupt kill can lose the tail); allowed requests alone don't write counters. Only the visible page can schedule a shield refresh after 120 ms, with immediate reads on selection/resume. No private or Home requests publish counts, and private admission stays disabled.

A host smoke check with headless Chromium 131 exercised the fixed corpus with the assembled scriptlets: the stock markers collapsed under ordinary CSS, author priority and strict CSP; protected content and popup controls behaved as expected. In an isolated `/mutations` run, the old stock scriptlets made 15 document-wide queries before the mutation batch and 55 cumulative after it; the new scriptlets made none in that run. This counts scriptlet work only. The no-block proxy control served all 300 requests, but Chromium did not exercise Android request interception or establish device timing.

### Device procedure (PERF-05/06)

1. **Disposable profile and fixed network.** Use a throwaway debug install on a test device. Run `python3 tests/fixtures/filter_work.py` and `adb reverse tcp:8015 tcp:8015`. For the named `.perf17.invalid` sites, record the original device proxy and point the device's HTTP proxy at `127.0.0.1:8015`. The fixture never forwards. Restore the original proxy and remove the reverse after the run. Don't use a real account, profile, or browsing traffic with the proxy. Use **My Filters** to save:

   ```text
   ||tracker.perf17.invalid/deny/
   @@||tracker.perf17.invalid/deny/allowed.json^
   page.perf17.invalid##.perf17-custom
   ```

2. **Behavior before timings.** On `http://page.perf17.invalid/static`, settle and tap **Check layout**. `ordinary`, `inline`, `cascade`, `custom` and `adframe` should be hidden. The article should remain readable; the initial overlay should be absent, but `consent` and `small` should still be attached. With the burst button expect 300 blocked probe responses (JSON objects, not errors), no `item-N.json`, `logo.svg` or `/deny/frame` arrivals, and one successful `allowed.json` arrival. The fixture image is replaced by a transparent PNG and the frame by empty HTML, without WebView error chrome. A missing allowed response is inconclusive. Remove only the custom cosmetic rule: its marker should show again. With **Block fonts** / **Block large media**, the same-origin probes should stop reaching the fixture and show `font/woff2` / `text/plain`, status 200, empty bodies. Toggle them off and repeat. Check `http://excepted.perf17.invalid/static` explicitly allowlisted, global off, cosmetics off and popups allowed separately: the allowlisted/global-off controls should serve all probes from the proxy, cosmetics off should not stop request blocking, and popups allowed should leave the curtain. Verify current rules after reload, redirects/history and tab switches; compare native override versus interception with the direct Android cases below. With NoScript on, the page must instead say **No page script ran**.
3. **Mutation, CSP and ownership.** On `/mutations`, run **Add fixed DOM batches** and inspect the inserted ad and overlay after the queue settles, along with the unchanged controls. On `/strict`, test ordinary and frame collapse with `style-src 'none'`; the page's own styles are blocked too, so this isn't a popup geometry comparison. Try a request burst while switching to a different tab, reloading the same address, or pausing: no old page should repaint the selected shield. On return to a still-live page, the displayed count should catch up. Wait for the final aggregate write before a force-stop and compare the saved total after relaunch; also record the intentionally lossy early-kill window. Run the direct Android response tests separately (they inject synthetic requests, not WebView network traffic):

   ```sh
   ./gradlew :app:connectedDebugAndroidTest \
     -Pandroid.testInstrumentationRunnerArguments.class=com.privacy.browser.FilterInterceptionDeviceTest
   ```

4. **Upgrade without erasing edits.** On a disposable pre-Step-17 install, leave one of the two stock scriptlets byte-for-byte unchanged and edit the other, including a harmless executable marker. Install the new APK over it with the same app ID/test key. Verify the stock entry was replaced and the edited file stayed byte-for-byte unchanged and still runs on eligible pages. Swap the two in a fresh seed; also try missing files and **Restore Default**. Keep the existing [script scope and Home checks](#noscript-and-page-script-policy) in the acceptance set. Do not use a production profile for this check.

### Compare work and frames

Use matching before/after builds from immediately before and after Step 17, the same physical device, Android/WebView versions, fixture, settings, profile state, network, viewport, and gestures. Counter/renderer diagnostic runs are separate from frame timing. With `?diagnostic=1`, **Check layout** reports document queries and style/rect reads before the check; those totals include other scripts, so attribute renderer events to the stock scriptlets in Chrome DevTools. Confirm the initial work finishes, added-node batches don't restart a document scan and settled pages stop probing. For burst work in Perfetto (app tracing enabled), count `Browser.filters.sitePolicy`, `Browser.filters.shieldCount` and `Browser.filters.persistTotals`, along with served/blocked counts and interception-thread CPU. Use comparable temporary trace markers or method/task sampling on the pre-change build; the absence of new trace labels on it isn't zero work. Confirm the all-allowed control causes no counter writes on the new build.

Repeat settled static, mutation and request-burst runs and report median/p95 frame overruns, renderer style/layout time, interception work and UI/persistence counts. Time toolbar drags/snaps separately on the same settled page without mutations or request bursts; don't attribute those frames to filtering or count a toolbar rewrite as part of this change. If coverage, layout, or legitimate controls differ, record that before comparing timing. Without a matching baseline or working allowed probes, record the missing comparison, not a performance pass.

## Address-bar suggestions on Android (not yet run)

Autocomplete is local. Nonblank input gets the current search fallback, as well as any applicable localhost/IP and bang rows, without waiting for SQLite. After 100 ms without an edit, one worker can look up local rows. New edits replace the pending text instead of queuing a query for every key. Cancellation is cooperative: it skips further reads and drops any stale results; an open or SQLite call already running can finish. Tab selection, dismissal, window/editor focus loss, pause, recreation and data cleanup invalidate the address edit. A tap on a row from an earlier publication is ignored. Returning to the app does not replay pending suggestions.

The address bar matches literal substrings of titles or URLs with SQLite's default ASCII-insensitive `LIKE`; `%`, `_` and `\` aren't wildcards. History considers at most the 1,024 newest visits by timestamp. Bookmarks consider the last 1,024 inserted entries (including folders in the budget), then rank matching links by creation time. Each query returns up to three rows, with folders excluded and history duplicates suppressed in the list as before. Older matches outside the window still belong in History or Bookmarks. No schema migration, change to the Home bookmark list, or change to the assistant's search. A private request would skip history and clipboard; private admission stays disabled.

The clipboard is inspected once at the first eligible settled result of a regular edit, not in the text watcher. Only a plain-text first item up to 8,192 characters can be offered; it is held only for that focused edit. If the clipboard changes during an edit, use explicit paste or refocus. Blank or overlong input (more than 8,192 UTF-16 code units) does not schedule suggestions; direct entry is unchanged.

### Host SQLite evidence (PERF-07)

```sh
python3 -B tests/fixtures/omnibox.py benchmark --history 50000 --bookmarks 20000 --repeats 100
python3 -B tests/fixtures/omnibox.py benchmark --history 1000 --bookmarks 4000 --repeats 100
```

On host SQLite 3.40.1, the large seed gave the following **SQL VM instruction counts**, before → after. Each recent and common term produced three rows per store; the missing term produced none. This compares SQL work only, not time from an Android key to a presented frame.

| Stores in the large seed | `perf18recent` | `perf18common` | `perf18absent` |
| --- | ---: | ---: | ---: |
| History, 50,000 | 43 → 20,537 | 83 → 23,582 | 400,011 → 20,504 |
| Bookmarks, 20,000 | 140,066 → 21,954 | 209,059 → 24,810 | 140,011 → 21,921 |

**Plans for recent and missing terms:** the old history query was `SCAN history USING INDEX idx_history_visited_at`; the old bookmark query was `SCAN bookmarks` and `USE TEMP B-TREE FOR ORDER BY`. Both new queries have a `CO-ROUTINE (subquery-1)`: history uses `idx_history_visited_at` inside the limit; bookmarks read rowids backwards (reported as `SCAN bookmarks`). The outer scan matches and sorts only the capped candidates (`SCAN (subquery-1)`, `USE TEMP B-TREE FOR ORDER BY`). At 4,000 and 20,000 bookmarks and at 4,000 and 50,000 history rows, new query instruction counts were unchanged. The bound is on rows, not bytes or I/O wait. Fast history hits can do more SQL work: in this run warm history p95 for the recent term was 0.023 → 0.889 ms, while missing was 17.346 → 0.826 ms. These are in-memory host numbers; the 100 ms debounce, UI frames, cold Android I/O and SQLite pool contention are not included.

### Device procedure (PERF-07)

[Report 11](docs/android-test-followup.md#report-11-offline-attribution-complete-perf-07-blocked) confirmed post-edit frame pairs but no direct recorded dispatch-to-edit link in the retained trace. No further device run is requested. Before using this paired procedure, agree on a [new measurement calibration](docs/omnibox-perf-measurement.md) that links the selected app dispatch to the edit and to the actual presented content. APK swaps and reseeding require separately authorized disposable state; do not run the commands below on the retained test profile.

1. **Match the APKs.** Use the `step17` and `step18` trees from the [matched-build helper](docs/device-handoff.md#matched-build-sources) to isolate Step 18. Match SDK/JDK, variant and debug signing identity, verify `com.browser`, and record APK hashes. Assemble and lint the final candidate and run `com.privacy.browser.OmniboxDeviceTest` on an Android runner, covering real SQLite, fallback under a held helper monitor, stale row taps/tab switches, and private-mode query policy without enabling private tabs. Compare the final candidate with `step18` separately for regression under the same inputs; don't substitute it for `step18` in the isolation pair.
2. **Use identical synthetic data.** On a disposable debug install only, with the same WebView, keyboard, refresh rate and offline network state for both APKs, seed the app before launch. The commands below **erase the debug profile**. Run once with the large row counts and again with `--history 1000 --bookmarks 4000` in a fresh seed directory. Repeat seeding on each install. Do not use a personal device profile, clipboard or browsing data.

   ```sh
   seed=$(mktemp -d)
   python3 -B tests/fixtures/omnibox.py seed "$seed" --history 50000 --bookmarks 20000
   app=com.browser
   adb shell am force-stop "$app"
   adb shell pm clear "$app"                 # must report Success; disposable only
   adb shell run-as "$app" mkdir -p databases
   for store in history bookmarks; do
     adb exec-in "run-as $app sh -c 'cat > databases/$store.db'" < "$seed/$store.db"
     adb exec-out "run-as $app cat databases/$store.db" | cmp - "$seed/$store.db" || exit 1
   done
   adb shell am start -n "$app/com.privacy.browser.MainActivity"
   ```

3. **Check behavior before timing.** Use the bundled Home and no real network targets. Type `perf18recent`; verify the expected latest titles, bookmark before history, no folder rows and no doubled shared URL. Try `perf18common`, `perf18absent`, `perf18archive` (outside the large window, still in full searches), and the literal `route_50%\quoted's` term. Check bang, localhost/IP, selected-engine fallback, autocomplete off and on, clear/X, tap of a current row (use a disposable Home bookmark), and IME Go. Copy synthetic text before focus and change it during the edit; it should be inspected once, not again on more keystrokes. Refocus for a new snapshot. Run bursts of typing/deleting while opening the tab grid and switching, tapping the scrim or using Back to dismiss the edit, background/resume and recreate (e.g. change app theme), including returning to the same text and tab. An IME-only Back may hide the keyboard without ending editor focus; test the additional Back or scrim that ends the edit. Old rows must disappear and never return or navigate after these transitions. Verify native private-tab admission still refuses. Try a confirmed clear while suggestions are pending, then verify no wiped history appears. On Android 12+ the trace below should show lookups on `Browser-omnibox`, one active read, no new lookup for replaced pending edits, and at most one clipboard slice per regular edit.
4. **Measure paired trials on physical hardware.** Use the same seeds and input sequence in alternating build order; include a representative slower supported phone and a recent ARM64 phone, with API/WebView versions recorded. Separate first lookup after process start from settled/warm trials. Use at least 30 warm samples per term/build/size, plus repeated bursts and cold runs. On Android 12+, start the [Perfetto config](tests/fixtures/omnibox_trace.pbtxt) in one terminal and run the fixed input in another. Capture the screen (or film both input and screen at high frame rate) to verify the frames contain the expected text and row, not a previous result. If using screen recording, use it consistently in both builds or in separate corroborating trials to avoid measuring only recording overhead.

   ```sh
   adb shell -T perfetto --txt -c - -o /data/misc/perfetto-traces/omnibox.pftrace \
     < tests/fixtures/omnibox_trace.pbtxt
   mkdir -p out
   adb pull /data/misc/perfetto-traces/omnibox.pftrace out/omnibox.pftrace
   ```

   - For an isolated final key, prefill `perf18recentx` or `perf18commonx`, place the caret at the end and settle. Inject `adb shell input keyevent KEYCODE_DEL` to change a miss to a hit. For a miss, prefill `perf18absen`, settle and inject `adb shell input text t`. For bursts, clear the bar, settle the keyboard, then use `adb shell input text perf18recent` (repeat with `perf18common` and `perf18absent`) and repeat the same cases with the real IME. Report coalesced keystrokes rather than treating them as individually presented frames. Do not use the ADB round-trip time as input latency.
   - Start from app key/input dispatch. Measure the first **presented** frame showing the new text and the first presented frame with the current fallback, even if they coincide. Record frame overruns and drops across the typing burst. Measure the first presented **correct local row** after the final key separately for hits. On misses check for no stale row and don't assign a local-row latency. Record median and p95 for each latency, and note if a row is below the visible area. Use the actual app SurfaceFrame/DisplayFrame in FrameTimeline plus the visible check; View layout or `Browser.omnibox.localRows` alone is not presentation. If dispatch or presentation cannot be linked for a sample, mark it inconclusive. A calibrated high-speed recording can corroborate visible content; an actuation-to-screen measurement without a synchronized app-dispatch observation is a separately labeled metric, not dispatch-to-presentation latency.
   - Attribute work using `Browser.omnibox.edit`, `Browser.omnibox.lookup`, `Browser.omnibox.localRows` and `Browser.omnibox.clipboard` on `step18` and the final candidate. Identify comparable input and SQLite work on `step17`; the absence of newer marker names is not zero work. Check that no lookup SQLite work runs on the new build's main text path. Report settled latency including the intentional debounce, wrong/outdated rows, lookup and clipboard counts, and main-thread frames separately. Keep all raw screenshot/video/trace inputs synthetic and local to the lab, and remove only the separately authorized seeded profile afterwards.

**Push gate:** agree on a settled-local latency budget and a small-database input regression tolerance before comparing, and record both. Require correctness plus a repeatable physical improvement in large-data typing latency or frame overruns, beyond run-to-run noise, without an unacceptable settled-local delay. SQL instruction counts, hosted JVM checks, an emulator, or a single trace do not qualify. Trace readiness, paired timing and final-candidate regression are still open.

## Media discovery (Step 19 / PERF-08)

**Floating Video Download Button** controls passive collection as well as the visible button. With it enabled, allowed per-view media GETs on the selected regular tab provide URL hints; no headers/body are replayed. There is no media-discovery fetch/XHR wrapper, media `src` patch, page-wide media observer, timer, or process-wide ServiceWorker routing. A WebView interception callback has no document ID beyond the view: the captured native page scope bounds hint lifetime, not proof of who initiated a resource load. Hints are advisory and never authorize a download. The current document, view and enabled switch gate the native action; the script policy also gates DOM and platform discovery. Home, unsupported addresses and private tabs do not participate.

Opening the floating button (or Refresh in the format picker) takes a bounded DOM snapshot when scripts are allowed, then looks up formats/sizes on demand. Download formats can include direct audio/video, HLS master and variants, and publicly available Vimeo/YouTube formats including companion audio. DASH can be identified but is still **not downloadable**. Metadata uses anonymous requests, including on redirects, and respects current network rules and the site's large-media switch. No playlist segments are read by enrichment. A later, separately confirmed transfer can use the existing same-origin request context; cross-origin targets do not inherit WebView cookies or the referrer. The explicit **Grab Page Links** action remains available subject to the usual document/script gates; it takes a link snapshot, not another enrichment batch.

| Limit | Scope |
| --- | --- |
| 48 URL hints, 96 option rows | One regular view/document; fragments and host/default-port aliases do not duplicate probes; raw path case, escapes and query order/case are retained. |
| 6 plans per open, 16 attempted identities per document | At most one batch per document. Repeat opens do not re-probe a tried URL or player identity. Reload for a fresh scope (e.g., after a signed URL expires). |
| 2 workers and 4 queued batches | Process-wide ceiling for this feature. Full admission falls back to the known options. No worker is submitted by passive collection. |
| 12 HTTP starts per batch; up to 2 redirects per metadata GET/HEAD | Direct candidates try HEAD and at most one `Range: bytes=0-0` fallback on 403/405 (read size headers, not the response body). Provider GET redirects stay at their original provider origin. The fixed player POST never redirects. |
| 128 KiB HLS, up to 24 variants; 384 KiB HTML prefix; 512 KiB player JSON; 1 MiB total body data per batch | Complete-body reads can consume one additional byte to detect an oversized body, within the total cap. These are application reads, not socket buffering, header sizes or over-the-air byte limits. |
| 1.8 s or less per connect/read timeout; 6 s cooperative work budget | The picker doesn't wait past its 8 s worker wait budget (plus at most 800 ms for the DOM snapshot). Retirement drops queued work, interrupts active work, and prevents another hop, result publication or stale confirmation. A pending platform I/O call can take time to unwind; this is not an instantaneous socket cutoff or a real-time CPU guarantee. |

**Comic & Manga Downloader** runs a cheap signal check at commit and at most one finish recheck for a missing signal. Signals are a known reader manifest, a named reader region/image, or a paginated reader control. Native SPA URL changes retire the previous plan and get one short settling delay for the new route. A known reader region uses a coalesced change observer (160 ms); there is no scroll listener or page-wide observer, no polling for arbitrary late galleries, and no republishing identical ordered URLs. A normal gallery is examined only through **Find Comic Pages**, with a 512-image inspection limit. Known plans with missing slots or more than 150 pages are refused, not silently truncated; repeats remain separate positions. Settings changes, pause, tab switches and page retirement suspend the observers. An **on** switch doesn't itself make any native media or image request.

### Host evidence (PERF-08)

The matching Step 18 network path was **already** registering candidates without eager network probes. Compare idle renderer work, and evaluate the benefit/cost of the new format probes at the moment the user opens options; don't claim that a previously active background request path was removed.

- [JVM media fixtures](app/src/test/java/com/privacy/browser/media/MediaMetadataTest.java): zero requests and zero started workers before opening options, including after 250 duplicate observations. One opening caused **1 HEAD + 1 HLS GET**, zero variant/segment requests, and distinct signed variant URLs; reopening added **0**. In a separate case 405 caused exactly one range fallback. A blocked redirect caused zero requests to the denied target; accepted redirects carried no session headers. The redirect-loop fixture observed **3 starts per plan, 12 per batch** and no 13th request. Retiring while headers were pending gave exactly **1** observed HEAD, no subsequent request and no old result.
- [Worker/response bounds](app/src/test/java/com/privacy/browser/media/MediaSnifferTest.java): stalled work used **2 workers + 4 queued jobs**; the next job was refused and retiring a queued job freed exactly one slot. Oversize stream cases read **131,073** and **524,289** bytes including one rejection byte, and the aggregate test stopped at **1,048,576** bytes. These are controlled counts, not network timing or device battery readings.
- [Node](tests/comic_detector.test.mjs) and a Chromium 131 run of the production scripts on the loopback fixture: on the 120-image ordinary page, initial load plus three scrolls separated by 900 ms went from **4 → 0** whole-page image enumerations, **480 → 0** image inspections, and **4 → 0** reports. The current reader ran **1** initial scan, **0** extra for 75 same-value writes, **1** more for 75 changed writes, and **1** on a new SPA binding. This counts selector/inspection calls, not renderer wall time. Chromium ran the scripts with a fixture bridge; it did not exercise Android WebView message delivery or network interception.

### Device procedure (PERF-08)

For the Arena browser preview, run `python3 -B tests/fixtures/media_discovery.py --preview` on port 8016. This serves only the synthetic fixture pages; the Android app itself is not running in the preview. Without `--preview`, the fixture remains loopback-only for the device tests below.

1. **Match the builds.** Use the `step18` and `step19` trees prepared from the [patched candidate](docs/device-handoff.md#matched-build-sources). Use the same device, filter snapshot, settings and WebView. For the older trees use `:app:assembleDebug` (not candidate instrumentation). Match SDK/JDK, variant and debug key, verify `com.browser`, and record APK hashes.
2. **Run controlled cases.** Start the [loopback fixture](tests/fixtures/media_discovery.py) on the adb host and run the Android cases in a fresh disposable profile. Dismiss any platform setup/permission prompts first. The fixture reports synthetic route names and header presence only, not actual header values. It serves metadata, not playable video.

   ```sh
   python3 -B tests/fixtures/media_discovery.py  # separate host terminal
   adb reverse tcp:8016 tcp:8016
   ./gradlew :app:connectedDebugAndroidTest \
     -Pandroid.testInstrumentationRunnerArguments.class=com.privacy.browser.MediaDiscoveryDeviceTest
   ```

   - On both builds, open `/ordinary` and scroll three times with pauses; `/stats` on the host should show no extra media probes. On the candidate, repeat by opening the faded download button with no media; only explicit link discovery can follow, with no probes. Check scripts off, app pause/resume, the floating-button switch off/on from Settings, and private admission refusal. On `/options`, record normal WebView loads separately from metadata traffic. After the first open expect one clip HEAD, one HLS GET, one fallback HEAD plus a `bytes=0-0` GET, and 360p/720p variant rows without variant fetches. A second open/Refresh adds no requests on the same document. The dummy WebView cookie, referrer and authentication headers must not reach metadata requests. Try the site's **Block Large Media** toggle; there should be no new metadata starts even for extensionless or HLS sources. The JVM redirect fixture separately verifies that denied targets receive nothing. Existing [DL-03](DEVICE_TEST_CHECKLIST.md#downloads-storage-and-media-steps-0306) tests the separately authorized transfer.
   - On `/slow`, tap the page's delayed route change, then immediately open options. Once the server sees the delayed `HEAD /f/hold.mp4`, replace/close/switch the document in separate trials, also exercise same-URL reload, Back, background/resume, renderer loss and cancel. There must be no `HEAD /f/next.mp4` from the retired batch or old progress/format/download dialog. Try leaving after the confirmation opens; an old confirm or duplicate-resume action must not enqueue. Check raw request counts at `http://127.0.0.1:8016/stats` on the host; use `/reset` between settled cases.
   - Check `/reader` (3 ordered slots including a repeat, no change for equal writes, changed-source burst and then 2 positions after SPA change), `/gallery` (only **Find Comic Pages** enables review), and the [comic archive fixture](#comic-archive-checks-on-android-not-yet-run) for gap/oversize and real CBZ output. Check the comic switch on resume and under NoScript. An unsupported WebView message feature is a skip, not a pass.
   - On a separate test network, check an owned/public known-good direct clip, HLS and supported Vimeo/YouTube format set and actual downloads. Record the provider, available formats, and failures (e.g. signed URL expiry, sign-in, DRM or cipher-only responses). The synthetic tests don't certify live provider behavior. Don't use a personal browsing/login profile.
3. **Compare work, then time.** In alternating build order, use matched content and fixed scroll/mutation/tap sequences. Record browser/media requests, app body bytes (separately from total transfer), concurrent `Browser-media` work and `Browser.media.enrich` slices, and `Browser.comic.dispatch` counts/duration (native dispatch, **not** JS scan duration). The [Perfetto setup](#device-procedure-perf-07) captures the app slices and frames. Use renderer profiling for DOM/scan CPU and frame overruns; record latency from the tap to visible format rows separately. Keep raw timings for cold and warm trials. Report idle and explicit action costs separately; network, power and frame savings require physical evidence. Remove the reverse with `adb reverse --remove tcp:8016` when finished.

**Status:** host counts and stub compilation only. Android instrumentation, provider playback, actual APK/lint and physical timing have not been run here. The [existing paired physical push gate](#device-procedure-perf-07) also remains open.

## Downloads refresh (Step 20 / PERF-09)

The engine coalesces worker events into one immutable summary of the in-process owners. Progress updates wait up to one second for the next capture (plus controller scheduling time); unchanged summaries are not delivered. Initial readiness and entering/leaving the owner lifetime publish immediately. Notification formatting reads that summary, not SQLite or the segment list. The foreground service stops after the last owner drains, not merely when Pause is tapped. There is no notification polling thread. Normal checkpoint writes on transfer workers still happen.

Schedule, battery, Wi-Fi and time changes are reconciled on the queue controller. With a schedule enabled and automatic work pending or still owned, one process-local check is armed for the next wall-clock edge. A clock or time-zone change recomputes the edge. Ordinary and overnight windows include the start minute and exclude the end; identical start/end minutes give a closed window. Without automatic work there is no scheduled check, even if a schedule is enabled. This is not an Android alarm and does not wake a suspended or stopped app.

The Downloads screen reads up to **48 projected cards plus one look-ahead** per page on a reader thread. Date, name and saved-size sorts have stable ID ties; live bytes update the display, not the paging key. Search is a literal case-insensitive filename substring and settles for 160 ms before reading. An uncommon substring or extension can still scan index entries off-main; paging bounds the result and rendering, not every search's SQLite work. Scroll appends pages; transfer progress binds only the matching active rows and does not invalidate the current page or load more history. Committed state changes fetch matching IDs in batches of up to 128. A filter change, crash recovery, or a large bulk update can reload the first page. **Select All** includes matching IDs beyond the visible pages. Explicit diagnostics and staged resume are the segment-read paths; duplicate prompts use one indexed incomplete-name lookup and ignore dismissed or retired confirmations. Schema v8 backfills name keys and adds lookup indexes without replacing the queue, checkpoints or publication records.

### Host evidence (PERF-09)

The Step 19 service already polled only active owners, so its notification SQL count was already zero. The change removes periodic notification work and prevents progress from reloading Downloads. The production queue snapshot test drove two owners through 100 refreshes each with **0, 1,000 and 50,000 completed rows** in the test store. It captured two transfers and issued **zero task, queue or segment reads** during refresh in every case. The publisher tests cover 500 events coalescing to one capture, unchanged values, drain, and idle cancellation. This is structural host evidence, not a measurement of Android notification delivery or idle wakeups.

On host SQLite 3.40.1, the [query tests](tests/test_download_history_sql.py) use the production DDL and count VM instructions. With identical active/waiting rows, work was flat across the three completed-history sizes: **60** idle existence, **47** nonempty existence, **108** narrow queue, **19** duplicate lookup, and **8** for the unkeyed/staging lookup when there are no such paths. Changed rows use ID point lookups. A separate replay executed 84 generated page-query variants; a deep page with 50,000 equal keys took 2,589–3,054 steps for nonempty result cases. These numbers exclude index build/backfill, cold I/O and UI rendering.

### Device procedure (PERF-09)

1. **Match builds.** Compare `step19` from the [patch baselines](docs/device-handoff.md#matched-build-sources) with the patched candidate, not the original commit. Use only `:app:assembleDebug` for the baseline. Match SDK/JDK, variant and signing key; verify `com.browser` and record APK hashes. Run `com.privacy.browser.downloads.DownloadHistoryDeviceTest` and `CompletedDownloadGrantTest` separately on the candidate in a disposable profile with notification permission granted. Include a same-ID v7→v8 lab upgrade, checking history, a paused checkpoint and a committed grant. Report one-time upgrade separately from warm results.
2. **Seed identical synthetic completed histories** of 0, 1,000 and 50,000 on both builds. The commands below **erase the disposable debug profile**; never run them against a personal profile. The v7 seed is accepted by both; the candidate upgrades it on first open. Seed rows are metadata only. For real view/share checks, complete a fixture transfer.

   ```sh
   seed=$(mktemp -d)
   python3 -B tests/fixtures/download_history.py seed "$seed/downloads.db" --rows 50000 --version 7
   app=com.browser
   adb shell am force-stop "$app"
   adb shell pm clear "$app"  # require Success, disposable only
   adb shell run-as "$app" mkdir -p databases
   adb exec-in "run-as $app sh -c 'cat > databases/downloads.db'" < "$seed/downloads.db"
   adb exec-out "run-as $app cat databases/downloads.db" | cmp - "$seed/downloads.db" || exit 1
   adb shell am start -n "$app/com.privacy.downloader.ui.DownloadsActivity"
   ```

3. **Transfer and interact.** In another host terminal run `python3 -B tests/fixtures/download_history.py serve`, then `adb reverse tcp:8017 tcp:8017`. Use **Add download** and `http://127.0.0.1:8017/perf20-transfer.bin` with one selected connection. At one connection the 12 MiB body is paced over roughly 90 s. On both builds, confirm an active notification and normal completion. Repeat while pausing/resuming, with a waiting job, and while cancelling/deleting immediately before drain. Check the Latest/Oldest, size and name sorts, literal search and category pills, scroll across page boundaries, long press and select across pages, open/close Diagnostics, and share the completed file. Exercise duplicate resume, stale dialog dismissal and Clear Completed; the latter removes history, not the files. During a steady progress interval on the Completed filter, there must be no new page or changed-ID reads merely because the transfer advanced.
4. **Record work and physical frames.** Use `adb shell -T perfetto --txt -c - -o /data/misc/perfetto-traces/downloads.pftrace < tests/fixtures/download_history_trace.pbtxt` on Android 12+ and pull the result. With the list closed, compare equal-duration steady intervals at each size. Count `Downloads.snapshotActive` / `Downloads.notification` and `Downloads.notificationOwners`, and require zero history/segment SELECTs attributable to either slice. Separate worker checkpoints and initial queue work. Repeat with the list open: fixed fling and tap sequences, on device, in alternating build order. Check `Downloads.page`, `Downloads.changedRows`, `Downloads.applyPage`, `Downloads.applyRows` and `Downloads.applyProgress`; record the first correctly presented result, median/p95 input latency, and frame overruns in FrameTimeline. SQLite work should be off-main and progress should touch only affected cards. Include explicit open/search/details latency and small-history cases; VM instruction counts do not prove a frame-time win.
5. **Check idle and boundaries.** Capture at least 95 s with schedule enabled, no automatic work and stable power/network/time. After the final owner drains, require no foreground notification, no progress captures, and `Downloads.scheduleArmed=0` with no recurring `Downloads.reconcile` or `Downloads.scheduleBoundary`. Repeat with a job waiting outside the scheduled window; expect one armed check, correct admission at the opening edge while the app is awake, and correct waiting/drain at closing. Test an overnight window, time/zone changes, Wi-Fi and battery changes, and screen-off/resume. Report Android background-start refusals separately; no wake after force-stop is promised. Run `adb reverse --remove tcp:8017` and clear the disposable profile afterwards.

**Status:** host verification is complete; actual Android APK/lint, real SQLite/UI instrumentation, paired physical scroll and idle-scheduler traces have not run here. PERF-09 and the [existing physical push gate](#device-procedure-perf-07) remain open.

## Private tabs and clearing browser data

**Private tabs are unavailable.** The former mode shared WebView storage with regular tabs. This upgrade neither separates previously shared data nor automatically deletes it. The private-tab explanation links to the same explicit cleanup controls as the browser menu.

- **Site info:** no per-site wipe is offered. The site button opens the all-sites options, not a delete action.
- **Clear browsing data:** select all-sites WebView cookies, the app-wide WebView URL cache, a legacy Web Storage deletion request, and/or local history and saved tabs. A second screen confirms the exact selection. Cookie removal waits for the provider callback. URL cache and legacy Web Storage have no verified completion callback here; the result says **requested**. IndexedDB, service-worker registration, and Cache Storage are not promised. Open pages may recreate data. History/tab cleanup closes tabs and deletes this app's history/session records, not bookmarks, preview images, saved favicons, downloads, or settings.
- **Assistant:** `privacy_clear_site_data` rejects site names, missing scope, and empty category lists. Only an explicit `"*"` with selected WebView categories can open the same native confirmation. Scheduling or tool approval alone never deletes data.
- **Icons:** no remote favicon fallback. Bookmarks and History use a cached regular-page icon or a local placeholder.

The following test **clears regular-profile cookies**. Run it only on a disposable debug install. The optional [named WebView profile probe](docs/android-test-followup.md#3-run-the-named-profile-probe-in-separate-processes) is run separately, not by `BrowserPrivacyTest`. It does not qualify private mode, and an unsupported-provider skip does not verify isolation.

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.privacy.browser.BrowserPrivacyTest
python3 tests/fixtures/webview_storage.py  # adb host, separate terminal
adb reverse tcp:8013 tcp:8013
```

On the device, write distinct dummy markers on `http://127.0.0.1:8013/` and `http://localhost:8013/`. Verify that opening Site Info, requesting one site in the assistant, and cancelling confirmation leave both unchanged. Select cookies only, confirm, quit and relaunch: both sites should lose cookies, without removing history, bookmarks, previews, downloads or other files. Select history/tabs only and check the reverse; restart should not reopen the cleared session. Test URL cache separately with the cacheable endpoint and server log. Record what the provider actually does to legacy storage, Cache Storage, IndexedDB and the service worker; do not treat the request as proof of removal. Private requests from home, the tab picker, and external entry must not navigate. Before enabling a private profile, repeat storage/cache/worker and favicon/preview cases in two genuinely isolated profiles, including last-view closure, forced process restart, and failure to delete a profile. Keep the mode disabled if any of these are unverified.

## NoScript and page script policy

Android WebView does not expose a reliable per-origin JavaScript switch before a page or frame can execute. **While any NoScript rule is saved, JavaScript is off in all browser tabs.** The Shield dialog shows this and offers **Clear all NoScript rules**. This is a page policy, not a data wipe or a promise to unregister previously installed service workers. Use the address bar and native menus when the homepage's script actions are unavailable.

- Changes to script policy replace the live WebViews and reopen their committed addresses with GET. They do not resubmit forms or preserve the old Back stack and in-page state. This avoids retaining old wrappers or scripts in inactive tabs or back/forward entries.
- The YouTube defuser registers on the default HTTPS ports for `youtube.com`, `www.youtube.com`, `m.youtube.com`, `youtube-nocookie.com` and `www.youtube-nocookie.com` only. Aliases and lookalike hosts do not run it. It follows the master ad blocker, site exceptions, and the anti-adblock toggle. Child frames must be intentional `/embed/` pages; cross-origin embeds are skipped while any site exception is configured, because the provider cannot enforce the top site's preference at registration. Built-in YouTube styling also follows cosmetic settings; cross-origin embeds skip styling if any cosmetic exception could apply.
- If document-start is unsupported or registration fails, the defuser may run only on a matching committed main document. That path does not cover embeds or intercept earlier page script. A known stock YouTube script from older installs is migrated; custom files are not replaced by that migration. The bundled code only filters player responses, observes the player rather than the document, and has no polling interval or global JSON parser override.
- Custom scriptlets use **After page commit** (legacy `@run-at document-start`) or **After page load**, in committed HTTP(S) main frames only. They may not run ahead of inline page scripts. Use an exact host or a pattern such as `https://*.example.org/page/*`; `*` as the entire match means any eligible web origin. Lookalike hosts and URL substrings do not match. Fingerprint protection is independent of the ad blocker and needs document-start support; it is off under NoScript.
- Legacy cloud imports containing scripts or unknown destinations are rejected before writing any files. Rule-data-only imports can still apply. This does not add provenance to previously installed files or make legacy state application transactional.

**Provider checks (disposable debug install):** the instrumentation uses synthetic intercepted pages to check document-start, embeds, removal, forced fallback, and NoScript. A provider-feature skip is not a pass for the document-start case.

```sh
adb shell dumpsys webviewupdate
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.privacy.browser.adblock.BrowserScriptPolicyTest
python3 tests/fixtures/tab_trust.py  # separate host terminal
adb reverse tcp:8011 tcp:8011
adb reverse tcp:8012 tcp:8012
```

Open `http://127.0.0.1:8011/script-probe`. Check the main and frame markers with NoScript off, then add the NoScript rule in Shield. Exercise same-tab links, the redirect to `localhost`, the POST form, tab switching, Back, and process restore. Neither the top page nor the child should report that JavaScript ran under NoScript. Clear the rules and verify normal script behavior returns. Also test actual YouTube playback, a supported embed, a same-document player change, script edits, and site-protection changes on a device. The host fixtures do not verify provider callback order, APK/lint, or playback on YouTube.

## Local rules and cloud snapshots

- The app keeps one validated snapshot of `hosts`, `network`, `cosmetic`, and `user_filters`. Local edits and imports are committed together before switching the active rules. Legacy files stay on disk; unsupported legacy lines are inactive. Unreadable snapshots remain read-only for recovery rather than being silently overwritten. Browser startup waits for readable current rules before opening a page; a damaged derived index alone does not prevent browsing.
- Supported examples are `tracker.example.invalid` in the hosts list, `||example.invalid/Case%2Fpath^` and `@@||example.invalid/Allowed^` in **Custom Network**, and `##.ad, .sponsor` or `www.example.invalid##.ad` in cosmetic rules. Paths and query prefixes are case-sensitive. Cosmetic rules accept selector lists, not CSS declarations, scriptlets, or procedural filters. Network rules already in **My Filters** are still recognized.
- In **Ad Blocker → Modules → Cloud States**, **Make Same** replaces all four texts, including removals, comments and order. **Merge** appends distinct rule lines and keeps local rules and comments. Review the proposed changes before applying; if local rules changed while you were reviewing, reopen the preview. Applying a changed snapshot refreshes tab script policy, which can reopen pages and discard form and Back state.
- Publishing sends all four rule texts, including custom rules and comments, to the displayed HTTPS origin. It never sends scripts. Publishing is off by default on the Worker. If the response is lost, use **Retry same snapshot**; identical submissions are not added twice. Legacy v1 diffs are not imported. See the [Worker setup and rollout notes](cloudflare-worker/README.md).

## Agent actions, reminders and image sharing

- Enable **Agent** in the browser menu. The gear opens **Agent settings** directly. Select **Gemini API** and save to send prompts to Google AI Studio, or select **Off** and save to disable cloud chat without disabling local tools. **Text only** sends no tool declarations. **Structured** mode asks for native confirmation before each action, including reads of page text, history, or bookmarks. The review shows the exact action and tab; approved results may be sent to Gemini. Don't share sensitive page information or credentials in chat. Only bounded recent exchanges from the current session are sent; saved transcripts are local and never restored as authorization or replayed to a provider.
- A review is one-use and expires after 60 seconds. Navigation (including reload) and tab switches invalidate pending approvals and stale page results. Switching sessions, closing Agent, leaving the Activity, or changing its settings stops further work. Stop prevents further work; it cannot undo an approved page effect or recall bytes already sent. Expired review cards in old transcripts are read-only. Agent previews of supported old images are local; new attachments are unavailable.
- **Agent settings → Clean up old Agent data** removes retired provider replay data and action arguments, plus unused files made by the old Agent image writer, after native confirmation. On upgrade, saved chats and referenced images are retained. Exceptionally long individual messages display a bounded excerpt without changing what is stored. Cleanup does not send data, delete browsing history or account credentials, or securely erase SQLite free space, backups, or copies held elsewhere. It is not run at browser startup.
- **Agent settings → Review a recipe** parses pasted JSON without running it. Choose a step and approve that step separately. Apps, links and alarms cannot launch recipes. **Recipe reminders** only mark saved recipes due for review, and may be delayed or unavailable. They do not open the browser, send a notification, or execute actions. Exact-alarm special access is not requested. Legacy scheduled recipes remain inactive.
- The Termux sidecar and Antigravity OAuth path are unavailable; there is no fallback to Gemini or another service. Agent settings can remove old Antigravity credentials from the app. Local removal does not revoke them in your Google account.
- **Search by Image** opens an HTTP(S) image URL with one chosen provider after showing its destination and the URL (including any query). Screen capture, file uploads, provider fallback, and search-all fan-out are unavailable. Opening a search URL is not confirmation that the service found matches.

**Settings UI smoke check:** On the disposable install, open the gear in both light and dark themes. Check the rounded panel, provider pills and local tools. Use a dummy model and key without sending any chat. Verify invalid values stay in the panel with a field error, Cancel discards changes, and a saved **Off** leaves recipes available. With the keyboard open or larger text, ensure every field and footer action can be scrolled into view. Review a recipe or open cleanup to confirm that the existing native consent prompts are still required.

### Assistant session tests on the JVM

With the JDK and SDK configured, run:

```sh
./gradlew --no-daemon :app:testDebugUnitTest \
  --tests 'com.privacy.browser.ai.AgentSessionManagerTest' \
  --tests 'com.privacy.browser.ai.AgentToolBatchTest' \
  --tests 'com.privacy.browser.ai.AgentLegacyFilesTest'
python3 -B -m unittest discover -s tests -p test_assistant_legacy_sql.py -v
```

The [fixture](app/src/test/java/com/privacy/browser/ai/AgentSessionManagerTest.java) runs the production session manager with a fake provider, review UI, transcript store and main queue. It needs no API key or provider connection. It checks that Stop, a session switch, pause or a settings change blocks late work, and that a completed two-call turn sends one outcome per original call ID in one follow-up. A result queued before a same-address reload cannot send text from the retired document. Duplicate replies and errors from a previous provider request do not restart or interrupt the current turn. Separate file and host SQLite tests cover safe legacy cleanup, rollback, and preserved references. These are host checks, not tests of Android's native dialog, WebView callbacks, SQLiteOpenHelper, or HTTPS cancellation.

### Assistant checks on Android (not yet run)

Use a disposable debug install with scripts allowed for `http://127.0.0.1:8011/assistant-turn`. Start `python3 -B tests/fixtures/tab_trust.py` on your host and run `adb reverse tcp:8011 tcp:8011` on the test device. The tests use synthetic data only; leave cloud chat unconfigured. Run each class separately:

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.privacy.browser.ai.AssistantReviewDeviceTest
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.privacy.browser.ai.AssistantPersistenceDeviceTest
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.privacy.browser.ai.AssistantSessionDeviceTest
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.privacy.browser.ai.AssistantLegacyDataDeviceTest
```

`AssistantSessionDeviceTest` holds an actual synthetic WebView result after native approval, then delivers it after Stop, a new chat, a mode change, opening the settings panel, Activity pause, or same-URL reload. It uses a fake provider, so no Gemini request leaves the app. Its deadline is triggered deterministically; it does not measure timeout duration or on-wire cancellation. `AssistantLegacyDataDeviceTest` upgrades isolated v1 databases and verifies local preview decoding, transcript retention, and cleanup rollback. Also test an in-place upgrade with the old APK on a disposable profile, manual Cancel, tab switches and Activity recreation as described in the [device checklist](DEVICE_TEST_CHECKLIST.md). Remove the reverse with `adb reverse --remove tcp:8011` when done. On API 26–30 and API 31+, try a reminder before and after reboot, setting the clock forward/back, and a suppressed or overdue alarm. Verify that a canceled or old alarm changes no page and that neither the alarm nor a cold/warm recipe intent opens a recipe or runs a step. No private or unattended mode is qualified by these tests.

## Download streams

DASH (MPD) downloads are not supported. HLS segments are written to staging with fixed-size buffers, including for supported AES-128 streams. Byte ranges and declared response lengths must match before the mux. HLS and jobs with a companion audio track publish an MP4 only after the required inputs arrive and the mux succeeds. If required audio is missing or muxing fails, the job reports an error instead of saving a playlist or falling back to raw video. Retrying a stream starts a fresh transfer.

## Download a page-generated blob

The downloader does not read a `blob:` URL when it shows the prompt. After you confirm, it waits for a queue slot, then reads from the original HTTP(S) main document in that tab. This requires a WebView with origin-aware messaging and streaming reads; there is no legacy bridge fallback.

Keep the original tab on that document until the blob has been read. Only one blob offer or unfinished intake is allowed per tab. Up to two tabs can keep a confirmed source live while it is queued or streaming; a third confirmation must wait for one to finish or be canceled. The source expires after two minutes in the queue, and an active read has a 30-second idle timeout and a ten-minute overall deadline. Transfers use chunks up to 64 KiB and accept up to 512 MiB. A page reload, tab close, stopped attempt, or process restart can invalidate the source. To try again, request and confirm a new download on the original page; queue retries cannot reuse the old source.

## Save detected comic pages as CBZ

Enable **Comic & Manga Downloader** in Settings. On a supported reader or gallery, the comic button offers the ordered set of **detected pages**, not a promise that the site exposed its entire chapter. Known gaps or sets over the detection limit are not silently shortened. The app saves a ZIP-based `.cbz` only after every listed page arrives. Repeated URLs keep their original page positions, and the ZIP entry extensions come from the image bytes rather than the URL. No new `.cbr` files are written.

One missing, short, partial, or unsupported page stops the whole job and identifies its original number. Reader HTML must identify one explicit page image; a logo or a different image is not a replacement. The archive checks HTTP framing and image container markers, not full image decoding. Limits are 150 pages, 32 MiB per image, 512 MiB of images per archive, and 512 KiB per reader HTML response. Connect/read idle timeouts are at most eight seconds, with a 45-second page-fetch budget and a ten-minute job budget. At most one manual, same-origin verification prompt can wait up to 60 seconds. It uses the same origin-scoped request policy as other native downloads.

Closing the dialog, leaving the Activity, switching tabs, or navigating cancels unfinished work. An in-flight system call can take time to return; it cannot then commit a canceled archive. A file already committed is not undone. The result path is shown in the dialog under **Comics** in the selected writable downloads directory or the app-specific fallback. The app verifies an exclusive, no-replace copy before reporting success. As with other exclusive copies, the destination name can be visible during publication; abrupt process death can leave an unconfirmed file, which the app does not adopt as a saved comic. This path is not a queued download, a share grant, or a SAF write.

### Comic archive checks on Android (not yet run)

Use a disposable debug install with an origin-aware WebView provider. Start the [synthetic fixture](tests/fixtures/comic_archives.py) on the host and connect it to the device:

```sh
python3 tests/fixtures/comic_archives.py
# In another terminal:
adb reverse tcp:8014 tcp:8014
```

Enable the setting, then open `http://127.0.0.1:8014/` in a regular tab and check the following:

1. **`/ok`:** No native image requests before confirming. Save the four detected pages as one `.cbz`: red, green (from reader HTML), red again, then blue. Inspect the ZIP entries `page_0001.png` through `page_0004.png` and open it in a CBZ viewer from a supported shared location. Repeat with the same title; the previous file must stay unchanged.
2. **`/missing` and `/short`:** The app must report page 2 of 3, not fetch page 3 or publish a CBZ. **`/known-gap` and `/oversized`** must not offer a smaller archive.
3. **`/slow`:** First let the idle timeout fire. Repeat and cancel while page 2 is pending via Close, Back, leaving the Activity, or navigating the tab. Once the in-flight I/O returns, check that there are no later page requests, completion UI, or staged outputs. Retry `/ok` from a new dialog.
4. **`/verify`:** Set the synthetic cookie using the web prompt, then select **Verification Done** and check that only the blocked page retries. Repeat by dismissing the prompt, waiting past its time limit, and leaving the Activity while verification is pending. No late approval may start a retry or publish an archive. Record renderer loss separately if supported.

The [JVM archive tests](app/src/test/java/com/privacy/browser/comic/ComicArchiverTest.java) exercise loopback HTTP, actual ZIP entries, cancellation and destination races. The Node fixture runs the injected detector script. Neither verifies Android dialog delivery, actual WebView credential lookup, storage permissions, or viewer behavior; use the [device checklist](DEVICE_TEST_CHECKLIST.md) for those results.

## Bookmark folder recovery and moves

The first open of an older bookmark database adds any missing hierarchy columns and repairs broken parent links in place. A bookmark or folder with an invalid parent is moved to **Bookmarks (Root)**. For a cycle, only the lowest-ID folder in that cycle moves to root; the rest of the branch stays attached. The upgrade preserves saved rows, IDs, titles, URLs, timestamps and existing folder flags. If a legacy database lacks a folder flag, the app doesn't guess from its URLs. This repairs logical folder links, not a physically damaged database file.

Single and multi-item moves cannot target a selected folder, its descendants, or a folder disconnected from root. The picker omits those destinations, and the app checks the selection and destination again before committing any change. A rejected move changes no parent links. The count reports changed items only; moving to the current folder is not reported as a success. Deep breadcrumb paths show root and the most recent folders while keeping the full Back path.

### Bookmark checks on Android (not yet run)

Use a **disposable debug install with synthetic bookmarks**. The [device fixture](app/src/androidTest/java/com/privacy/browser/bookmarks/BookmarkDbHelperDeviceTest.java) creates and removes its own uniquely named databases. It doesn't open or modify the app's `bookmarks.db`. It tests v1, partial and v2 upgrades, rollback on failed writes, stale picker selections, and deleting cyclic or deep trees:

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.privacy.browser.bookmarks.BookmarkDbHelperDeviceTest
```

Then exercise the UI on that test install:

1. Create root folders **A** and **Other**. Under **A**, create **Child** and a **Grandchild** inside it. Save two synthetic URLs (for example, two paths from the loopback fixture above), and move one into **Child**.
2. Open **Move to Folder** for **A** by itself, then with a root bookmark selected. Both pickers should omit **A**, **Child**, and **Grandchild**, but offer **Other** and **Bookmarks (Root)**. Choose root while all items are still there and verify the “already in this folder” message and unchanged tree. Cancel once, then move the selection to **Other** and verify the reported count and nested bookmark.
3. Add more levels and verify that the breadcrumbs abbreviate the path without losing Back navigation. Back out and reopen folders quickly in both list and grid views. Restart the app and verify the move persisted; delete the synthetic branch and confirm that the unrelated bookmark remains.
4. For an in-place app upgrade, seed a disposable older debug build with healthy nested bookmarks, then install the new build over it with the same application ID and test signing key. Verify that the hierarchy and existing previews still match. Use the isolated SQLite fixture above for corrupt data; don't inject corruption into a personal profile.

The [JVM tree tests](app/src/test/java/com/privacy/browser/bookmarks/BookmarkTreeTest.java) verify the repair decisions and traversal on deep graphs without Android. They do not verify the SQLiteOpenHelper upgrade or what the dialog and breadcrumb UI display. Record those results separately under [DATA-01](DEVICE_TEST_CHECKLIST.md#bookmark-folders-step-13).

### Local bookmark covers and favicons

Bookmark cards and the edit dialog can show saved covers. Bookmark list rows, cards without a cover, and History can show saved favicons from regular WebViews. Binding a row uses memory or a placeholder first; it doesn't open an image file or fetch an icon from the network. A late result is applied only to its current row or dialog. A cover takes priority over a favicon.

Disk reads and decoding run on one worker per store. A cover file is limited to **2 MiB**, a **4,096 px** source edge and **4,194,304 source pixels**; its decoded image is at most **480 px** per edge. Favicon limits are **512 KiB**, **512 px** source edges and **96 px** decoded edges. Missing, oversized or undecodable files leave the placeholder without erasing the file. The memory caches are capped at **8 MiB** for covers and **1 MiB** for icons. Each store admits up to **24 pending reads**, including deliveries waiting for the UI; repeated requests for the same image share a read. Under heavy scroll the row can keep its placeholder until it is bound again.

Cover captures render into a 480 × 480 output bitmap. Edits and resets are ordered with local reads. The editor reports a save only after the new file replaces the old one. Older pending reads cannot repopulate a reset cover. Existing bookmark rows and thumbnail paths are unchanged.

### Bookmark cover and favicon checks on Android (not yet run)

The [isolated codec fixture](app/src/androidTest/java/com/privacy/browser/images/LocalThumbnailStoreDeviceTest.java) creates synthetic WebP/PNG files in a temporary cache directory, not the app's real bookmark and favicon stores. It checks cold memory-only peeks under StrictMode, sampled Android decodes, malformed or oversized files, and ordered replacement and reset:

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.privacy.browser.images.LocalThumbnailStoreDeviceTest
```

On a disposable debug install, save synthetic pages with distinguishable covers and local site icons. Include folders, bookmarks with reset or missing covers, and several rows from the same site. Force-stop and reopen to start with cold caches. Scroll quickly in the bookmark grid, bookmark list, and History; switch layout, filter, enter selection mode, and back out while images are loading. Verify that no recycled row shows another bookmark's cover or site icon. Open and dismiss the edit dialog while a cover is pending. Recapture a cover and check that it matches the crop overlay; then reset it and reopen the dialog to check that the old image does not return. Confirm that opening Bookmarks or History with a missing icon does not trigger an icon request.

The JVM suite exercises admission, stale delivery and eviction but does not run Android's codecs, adapters or editor. Record those checks under [DATA-02](DEVICE_TEST_CHECKLIST.md#bookmark-folders-step-13). Collect separate device traces before making a scroll-performance claim.

## Browser sessions and downloads

A native download confirmed from a regular, committed page may use WebView cookies **only for the exact same origin** (scheme, host, and effective port). The downloader looks up cookies for the requested URL at each hop, sends only the origin as the referrer, and never copies response cookies into WebView. Crossing origins drops the page's cookies, referrer, and custom user agent for the rest of that redirect chain and for child resources reached through it. A CDN or a site that requires cross-origin authentication may not work with native downloads.

Pasted or external links, WebView callbacks without an identified initiator, and automatic media probes are anonymous. Private tabs are disabled until [profile isolation and deletion](#private-tabs-and-clearing-browser-data) can be verified.

A queued browser session applies to the first attempt only. Retry or process restart can still use a valid range checkpoint, but it won't reuse browser headers. If a transfer needs sign-in, queue a new job from the signed-in page. The job's download URL, including any required signed query, is still saved for regular jobs. Upgrades clear the old cookie/referrer/user-agent columns from download records; this isn't a forensic wipe or a reset of WebView cookies.

## Open or share a completed download

On a completed item, use **Open File** or **Share File**. Multi-select can share several completed files at once. The receiving app gets a temporary, read-only `content://` grant for each selected file, not directory access. Files still being written and files moved or replaced since publication are not shared. Older history without a recorded file identity cannot issue a grant; use the original file in storage if it's accessible. Removing a history entry prevents future opens through that URI, but cannot take back a copy or a descriptor the recipient already has.

This is read access to an already-published filesystem download, **not** support for writing to a Storage Access Framework (SAF) folder or document. On a disposable Android device with the SDK and `adb` configured, run:

```sh
./gradlew --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.privacy.browser.downloads.CompletedDownloadGrantTest
```

The instrumentation checks the real provider and intent metadata, including an explicit grant/revoke to a separate shell UID on an unrooted device. Separately, the [Android 14 emulator result](docs/android-emulator-download-upgrade.md#verified-result) covers both Downloads Open actions and single/multi sharesheet delivery to a separate app using app-private storage. Other device and storage configurations remain in the [device checklist](DEVICE_TEST_CHECKLIST.md#downloads-storage-and-media-steps-0306). Host tests cannot verify either Android flow.

## Limit queued HTTP downloads

**Queued HTTP speed limit** is measured in KiB/s (`0` means unlimited). One budget covers the bodies of queued direct/ranged downloads, HLS playlists, keys and segments, and separately downloaded audio/video tracks. Changes apply while jobs run. Pausing or cancelling wakes a job waiting for a permit; a socket that is already blocked may still need its network timeout.

The limit controls app body reads, not total network-interface traffic. It doesn't cover response headers, metadata/verification probes, network-stack buffering, normal page traffic, standalone comic downloads, or local blob/data sources and muxing.
