# Android test follow-up

Use the source supplied for each retest and record its identity. Follow the [isolated testing rules](antigravity-device-test.md#1-keep-the-installed-app-and-the-mac-safe): test only the disposable `com.browser` / `com.browser.test` pair, with synthetic data and an explicitly selected device. Keep the old browser and its data untouched. This is a retest, not a release or a GitHub operation.

## What the revised-source retest establishes

The second report identifies commit `7932d396ad6568c7a31c55338fa8ffb44b54dd40` (its branch ZIP SHA-256: `72bcc0b5d3fa4abd10160cef5047e0e83b385a9f9edce8900d9417bcbae28993`). It used JDK 17, Platform 34 and Build Tools 35.0.0 on macOS, plus a Galaxy S21 FE on API 36, ARM64, **4 KiB** pages, WebView `137.0.7151.115`. The report says the original app remained installed and untouched. These results are for that build, not a device run of the follow-up tests below.

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
