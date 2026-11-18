# Antigravity handoff: verify Privacy Browser on macOS and Android

**Task for the testing agent:** Build the isolated app from the supplied **testing-branch ZIP** (or approved checkout) and run the Android/device gates in `DEVICE_TEST_CHECKLIST.md`. Host checks on Arena are already green; Android assembly, lint, and device behavior are not yet verified. Give the user a results report, not a release or a push. This is the Antigravity macOS testing agent, not the retired Antigravity OAuth feature inside the browser.

## 1. Keep the installed app and the Mac safe

- The **only app under test** is `com.browser` (debug launcher label **Browser (Test)**); the instrumentation APK is `com.browser.test`. The Java activity remains `com.privacy.browser.MainActivity`. The old IDs `com.privacy.browser.injection` and `com.privacy.browser.injection.debug` must not be installed over, cleared, force-stopped, uninstalled, or used as a source of browsing data. Do not change the default browser. Use synthetic data and a dedicated fixture download destination; shared Downloads is not protected just by changing the app ID.
- Work in a fresh extraction or a disposable worktree on the Mac. Use only the supplied source, this repo, documented SDK tools, and the explicitly authorized ADB device. Do not scan the Mac for credentials, read production keystores or browser data, write to the owner's existing checkout, run `adb root`, grant special access, upload logs, push to GitHub, or deploy the Worker. Ask before any device-wide change (clock, proxy, reboot, system settings) or deleting an existing test installation. Restore any approved temporary changes. No Play submission or Play-driven retargeting.
- `adb devices -l` is a local check. Pick the authorized device and use `adb -s "$serial"` for **every** device command. If a device is unauthorized or several are connected and the right one is unclear, stop and ask. Do not include serial numbers, cookies, signing material, real page content, or unsanitized logcat/trace contents in the report.

## 2. Verify the source handoff

**Preferred: the GitHub ZIP for the supplied testing commit on `arena/bb0ffed3-browser`.** Extract it into a fresh local folder and open the folder containing `gradlew`. This ZIP already contains the remediation and the binary Gradle wrapper. **Do not apply an Arena patch to it.** Record the supplied commit SHA and SHA-256 of the ZIP. No `git init`, checkout, or commit is needed. If the owner gives you their local repo instead of the ZIP, create a disposable detached worktree at that same **testing commit**, not `main`, and record its SHA. Do not disturb the original checkout or rebuild from an older ZIP without checking with the owner.

From the candidate folder, use Git and Python 3.9+ to check the inputs and prepare the matched performance trees. Stop on any failed check:

```sh
work=$(mktemp -d "${TMPDIR:-/tmp}/browser-android-test.XXXXXX")
shasum -a 256 -c docs/native-arm64.sha256 docs/release-assets-build-inputs.sha256
python3 -B tools/prepare_perf_baselines.py "$work/baselines"
```

The helper gives you `step17`, `step18` and `step19` in separate build-only trees. Do not apply those nested patches to the candidate.

**Only if the owner supplied a patch instead of the testing ZIP:** apply it once in a disposable source tree at the original base `e83d2fa08c17dce89d8ba52c22474e89126f16d7`. Run `git apply --check --whitespace=nowarn "$patch"` before applying. If only `gradle/wrapper/gradle-wrapper.jar` fails as a binary export, exclude that exact path in both commands and restore the wrapper from the [official Gradle `v8.7.0` tag](https://github.com/gradle/gradle/blob/v8.7.0/gradle/wrapper/gradle-wrapper.jar). Its SHA-256 must be `cb0da6751c2b753a16ac168bb354870ebb1e162e9083f116729cec9c781156b8`. For any other error, stop rather than skip hunks or edit the patch. Never print historical secrets from removed patch lines. Record the base and patch SHA-256 instead of a ZIP hash. If the owner already applied the patch, do not repeat that step; verify the supplied tree as above.

## 3. Build on macOS and check the package before touching the device

- Use JDK 17 (`/usr/libexec/java_home -v 17`), the checked-in Gradle 8.7 wrapper, and Android SDK Platform 34, Build Tools 34.0.0/35.0.0 and current Platform Tools. Android Studio normally uses `$HOME/Library/Android/sdk` on macOS. Set `JAVA_HOME` and `ANDROID_HOME` in this session; use `sdkmanager` if approved for missing components. Record versions and avoid global environment changes. Keep one lab debug signing identity for candidate and baselines and verify cert equality; use a disposable Android user-home for debug tooling if needed, never production signing material.
- Follow [Verify without a device](../README.md#verify-without-a-device): Python, Node 22/npm, local Workerd, and, when available, host FFmpeg playback with `FFMPEG_TEST_BIN`. Run these Gradle tasks on the candidate (capture logs separately):

```sh
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest \
  :app:testDebugUnitTest :downloader:testDebugUnitTest :image-search:testDebugUnitTest
./gradlew --no-daemon :app:lintDebug :downloader:lintDebug :image-search:lintDebug
./gradlew --no-daemon :app:assembleRelease :app:lintRelease
python3 -B tools/verify_release_apk.py apk app/build/outputs/apk/release/app-release-unsigned.apk \
  --build-tools "$ANDROID_HOME/build-tools/35.0.0" --inspect-only
```

- Run Build Tools 35.0.0 `aapt2 dump badging` on the debug APK and its `app-debug-androidTest.apk`. Require exactly `com.browser` (debuggable, label **Browser (Test)**), `com.browser.test`, and instrumentation targeting `com.browser` in the test manifest. Confirm the merged manifest resolves the launcher to `com.privacy.browser.MainActivity` and the completed-download authority to `com.browser.completed-downloads`. Record APK byte counts and SHA-256. **Do not install** an unexpected ID, including `com.browser.debug`. Do not install the unsigned release.
- Build `:app:assembleDebug` only in each baseline directory when needed. Both debug and release of the new app use `com.browser`; they cannot coexist if separately signed. Do not try to force a signing mismatch or version downgrade.

## 4. Run the device matrix without risking the old install

1. Record model, Android/API, ABI, `getconf PAGE_SIZE`, and WebView provider/version on the selected device. ARM64 is needed for the bundled FFmpeg checks. Check `pm path` for the two old IDs (record presence only), `com.browser`, and `com.browser.test`. If either new ID already exists and is not a confirmed disposable build from this test, stop before replacing it. Install the verified debug and test APKs (`adb -s "$serial" install "$debug_apk"` and `adb -s "$serial" install -t "$test_apk"`). Launch explicitly with `adb -s "$serial" shell am start -W -n com.browser/com.privacy.browser.MainActivity`. Verify the original install is still present if it was there before and the new app starts with separate state.
2. Run **each suite in [the device checklist](../DEVICE_TEST_CHECKLIST.md#authored-android-suites--all-not-run)** individually. Start only the Mac loopback fixture named in its README instructions and reverse only the needed TCP ports on the selected device. `tab_trust.py` uses 8011/8012, storage 8013, startup 8014, filters 8015, media 8016, history 8017, and release mux 8018. Check for existing listeners/ADB reverses before using them. For example, after starting `tab_trust.py`:

   ```sh
   adb -s "$serial" reverse tcp:8011 tcp:8011
   adb -s "$serial" shell am instrument -w -r \
     -e class com.privacy.browser.ai.AssistantSessionDeviceTest \
     com.browser.test/androidx.test.runner.AndroidJUnitRunner
   ```

   Use the full test class names, not `com.browser` Java names. Capture the JUnit count/summary and check for failures or missing tests; a shell exit of zero is not enough. This command targets one device, unlike a connected test task that might select multiple devices. For README commands using plain `adb`, add `-s "$serial"` every time. Allow setup prompts only for the candidate; if a human action is needed, ask or record the case as blocked. Stop fixtures and remove only ADB reverses you added when finished.
3. Run the checklist's **manual acceptance** too. Prioritize transfer integrity and scoped headers, queue/service recovery, actual chooser read grants, private-mode refusal and truthful clearing, NoScript/rule behavior, tab/renderer and process-death recovery, real Agent Stop/approval and v1 transcript/photo retention, alarm reminders, and comic/media playback on fixture data. Keep old private tabs, byte uploads, and direct SAF writes disabled. Fake Gemini tests require no API key; only check on-wire cancellation with a separately authorized synthetic provider test. No operator secrets are needed for the fake-provider or local fixtures.
4. Test **migrations** on synthetic state, not the installed old app. Run the Android legacy fixtures. For a lab in-place v7→v8 upgrade, use the `step19` debug build under `com.browser` with the same lab signing certificate, seed nonempty v7 data per the README, then install the candidate over it **without clearing or uninstalling**. Verify history, paused checkpoint, completed grant and recovery. Record separately the fixture-only upgrades and the installed upgrade. Other installed-upgrade checks require an approved same-ID older test APK/seed and matching signing identity. An old package with a different ID cannot be upgraded to `com.browser`, and must remain untouched. A fresh install or a schema fixture is not an installed-upgrade pass.
5. **Run the physical performance gates.** Use `step17` vs `step18` for PERF-07, `step18` vs `step19` for PERF-08, and `step19` vs candidate for PERF-09; exercise final-candidate typing again for regression. Read the precise seeds, workload, trace slices and output metrics in [the handoff](device-handoff.md). Keep phone, WebView, keyboard, signers, seeds and settings matched. Agree on the settled-local latency budget and small-data regression tolerance before comparing; alternate build order and capture repeated cold/warm median and p95, work counts and correctness. Do not force downgrades or benchmark against the original installed app. For same-ID debug swaps, verify the APK and signer each time; `pm clear` is only for your confirmed disposable `com.browser` during reseeding, **not** during upgrade tests. Missing matched input, unsupported provider, or failure to show repeatable typing gain within its settled-latency budget leaves the push gate open. One phone does not cover every API or page-size configuration.

## 5. Return an evidence-based report and stop

Write `out/android-test-report.md` in the scratch candidate. Summarize the source commit, ZIP or patch SHA-256, APK hashes, toolchain, model/API/ABI/page size/WebView (no serial), whether both package identities coexist, and a row for each checklist ID with **Pass / Fail / Not run / Blocked** and evidence or reason. Keep host, SDK, Android fixture, physical, and signed-artifact results separate. For each failure give a minimal reproduction, expected/actual and sanitized evidence; don't weaken tests, hide skips, change app scope, or modify files outside scratch. Ask before applying a fix; return any authorized fix as a separate patch.

Treat unsigned minified inspection as useful SDK evidence only. [REL-01](release-readiness.md) still needs owner authorization and the exact signed ARM64 APK, package, trusted cert, ZIP alignment, **≤20,000,000 bytes**, native 4 KiB/16 KiB runtime without compatibility fallback, upgrades where applicable, and playback/grants. On the available phone report only the page size/device you actually tested. Do not search for production keys, sign for distribution, push, upload, or approve release. Resolve rights and any real historical credentials with the owner. Leave the old app and its data in place and tell the user what was left installed for the test.
