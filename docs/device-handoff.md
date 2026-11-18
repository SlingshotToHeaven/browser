# Android verification handoff

The source changes and offline fixtures are ready for an SDK-equipped runner. **No APK, device pass, signature, or release approval is implied.** The [device checklist](../DEVICE_TEST_CHECKLIST.md) is the acceptance record; the [release runbook](release-readiness.md) covers the independent non-Play release gates.

## Matched build sources

For the macOS agent, start with [the Antigravity testing instructions](antigravity-device-test.md). The requested testing-branch ZIP already contains the candidate, the Gradle wrapper, and [three verified source overlays](../tests/fixtures/perf_baselines/README.md). It needs no Arena patch or Git initialization. From a fresh extraction or a worktree at that testing commit, use Python 3.9+ and Git to prepare the matched, build-only copies:

```sh
python3 -B tools/prepare_perf_baselines.py /path/to/new/baselines
```

The output path must not already exist and must be outside the candidate checkout. The helper includes only build inputs, does not edit the candidate, and verifies restored source hashes. Build each copy with `:app:assembleDebug` only; test classes and synthetic fixtures live in the candidate. All four builds install as **`com.browser`** (debug label **Browser (Test)**) and use the same unrelated remediation. Verify each APK's ID and signer before installing. Neither old `com.privacy.browser.injection` package is an upgrade baseline or a data source.

| Check | Build A | Build B | Data and procedure |
| --- | --- | --- | --- |
| PERF-07 — physical typing, **pre-push** | `baselines/step17` | `baselines/step18` | [Paired address-bar procedure](../README.md#device-procedure-perf-07); also exercise the final candidate for regression. Both small (1k history / 4k bookmark) and large (50k / 20k) seeds. |
| PERF-08 — media discovery | `baselines/step18` | `baselines/step19` | [Paired media procedure](../README.md#device-procedure-perf-08) and the disposable loopback format fixture. |
| PERF-09 — download refresh | `baselines/step19` | testing candidate | [Paired download procedure](../README.md#device-procedure-perf-09), v7→v8 and 0/1k/50k histories. |

For earlier matched comparisons, use the actual saved pre-change source or APK where available. An end-to-end comparison to the original commit does not isolate a single step. Use the same JDK, SDK, WebView, device, signing identity for lab debug APKs, and seed data per pair. Keep the build hashes and named trace slices; the host work counts do not pass any physical gate.

## Run in this order

1. **SDK runner:** Use JDK 17, Platform 34, and Build Tools 34.0.0 (35.0.0 for release inspection). In the testing candidate, run the [quality checks](../README.md#verify-without-a-device) for debug/Android test APK assembly, Gradle JVM tests and lint. Build each baseline only with `./gradlew --no-daemon :app:assembleDebug`. Record hashes. An unsigned minified build and ZIP inspection are additional SDK checks, not distribution approval.
2. **Android runtime:** On an isolated debug profile, run the [instrumented and manual checks](../DEVICE_TEST_CHECKLIST.md), including the [Agent sheet and v1 transcript fixtures](../README.md#assistant-checks-on-android-not-yet-run). Keep fake-provider cancellation results separate from Android HTTPS cancellation. Run real in-place upgrades with approved same-ID, same-signer lab APKs and synthetic existing data; do not clear data between them. For v7→v8, use a `step19` debug build as the older lab APK. Installed-version tests cannot use the old app's different package ID. An emulator pass is useful but distinct from a physical-device pass.
3. **Physical Android:** On disposable ARM64 hardware, run the paired timing and work-count procedures. The user-requested branch snapshot is only for testing; do not promote it to `main` or release while the repeatable typing-gain and settled-latency gate is open. Verify 4 KiB and native 16 KiB setup, real content grants and native mux/playback with the signed artifact when authorized. Record ABI, page size, OS and WebView version without including serial numbers or real browsing data.
4. **Owner-controlled non-Play release:** Confirm the channel, signing identity and upgrade versionCode; resolve native/filter provenance and notices and dispose of any real historical credential exposures. Align then sign on the owner's station. Verify the actual APK, including the trusted signer and **≤20,000,000 bytes**, and complete the [release matrix](release-readiness.md) before staged distribution. Google Play approval, Play App Signing and a Play-driven retarget are not required. No private tabs, image-byte upload or direct SAF write path is enabled by this handoff.
