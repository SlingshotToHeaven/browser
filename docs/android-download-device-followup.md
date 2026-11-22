# Downloads: focused follow-up on the retained spare phone

**Historical phone procedure.** Phone testing is stopped for the current DL-01–DL-06 continuation. Use the [emulator-only handoff](android-download-emulator-resume.md); do not execute this runbook on the retained phone.

This handoff covers **two independently selected blob cases** on the retained `4a90876` browser. It replaces only the instrumented tests. The three physical publication-cut tests from `b456f26` remain available unchanged, but are **not part of this retest**. Do not rerun the full batch, upgrade, or native mux automation. Use the commit-pinned GitHub source ZIP identified in the handoff.

## 1. Build only the new test APK and check the retained lab

Use the **same debug signing identity** as the earlier run, JDK 17, Android SDK Platform 34 and Build Tools 34.0.0, current ADB, Git and Python 3.11+. Use a fresh scratch directory in Bash. The original saved `download-device-lab.json` must match SHA-256 `6a6a89ddd0850196b91f641e4cc73898a9aedb2fed75a2925353af1accdd678e`; do not reformat it. Do not copy a signing key or upload any APKs. If the original signer, receipt, installed app or spare phone is unavailable, stop and report the mismatch. Do not clear/uninstall anything to pass the check.

Set `zip` and `prior` to the absolute paths of the GitHub ZIP and saved JSON, and `source_commit`, `source_tree`, `zip_sha256` to the values in the handoff. Set `JAVA_HOME` and `ANDROID_HOME` for this session. Then:

```bash
: "${zip:?Set the GitHub ZIP path}" "${prior:?Set the original lab JSON path}"
: "${source_commit:?Set the handoff commit}" "${source_tree:?Set the handoff tree}" "${zip_sha256:?Set the ZIP SHA-256}"
: "${JAVA_HOME:?Set JDK 17}"
sdk=${ANDROID_HOME:?Set the Android SDK path}
lab=$(mktemp -d "${TMPDIR:-/tmp}/downloads-followup.XXXXXX")
root="$lab/browser-$source_commit"
(
  set -e -o pipefail
  printf '%s  %s\n' "$zip_sha256" "$zip" | shasum -a 256 -c -
  printf '%s  %s\n' '6a6a89ddd0850196b91f641e4cc73898a9aedb2fed75a2925353af1accdd678e' "$prior" | shasum -a 256 -c -
  unzip -q "$zip" -d "$lab"
  cd "$root"
  shasum -a 256 -c docs/native-arm64.sha256 docs/release-assets-build-inputs.sha256
  ./gradlew --no-daemon :app:assembleDebugAndroidTest
  touch "$lab/build-ok"
)
```

Do not proceed unless `build-ok` exists. Use only the same authorized spare USB phone, owner user 0, unlocked, portrait, English UI, with the default local ADB server (no `ADB_SERVER_SOCKET` or `ANDROID_ADB_SERVER_ADDRESS` overrides). No root, permission escalation, storage grants, Wi-Fi/battery simulation, or changes to the default browser. Do not access the old app IDs or personal files. Look at `adb devices -l` locally and explicitly choose the spare serial; do not include it in a report.

```bash
adb="$sdk/platform-tools/adb"
"$adb" devices -l
printf 'Authorized spare USB serial: '; IFS= read -r serial
follow=(python3 -B "$root/tools/run_download_device_followup.py"
  --disposable --serial "$serial" --sdk "$sdk" --prior-lab-json "$prior"
  --test-apk "$root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
  --source-commit "$source_commit" --source-tree "$source_tree" --zip-sha256 "$zip_sha256")
"${follow[@]}" --check-only --work "$lab/identity-check"
```

The check is read-only on the device. Require `preflight: passed`: same phone/provider, exact v8/recipient bytes from the prior report, and the private lab marker. On the first check of the restored profile, `installed_test_sha256_before` must be the original `5fd0954b86c93cab5d7b08432519282790bc6e6060e869fca547d42af9c9b7ad`. The v8 APK must remain SHA-256 `60be1238af1b9725930d2d9ccbe562453c0a6ddd7b002710b9fa911a091dd391`. A newly built `app-debug.apk` is **not** the installed app and must not be installed. If identities don't match, stop without deleting or changing any packages or profiles.

## 2. Record any manual evidence before the reset

Do this **before** the first selected retest. Its authorized reset will erase the previous synthetic in-app records and private files, including the earlier playback outputs. If a check already happened as specified, return the existing observation; don't repeat it. Reuse the synthetic fixture server and non-rebinding reverses from [Section 3 of the prior guide](android-download-device-lab.md#3-operator-observations-on-the-same-build) only as needed, always pinned to the same serial. Stop those fixtures and remove only their reverses before starting Section 3.

- **DL-03-M**: document the separate Downloads → Add download paste with that paste's probe and body results, both without cookie or referrer. The earlier `ACTION_SEND` rows are not paste evidence.
- **DL-06**: for each of `separate.mp4`, `transport.mp4`, `fragments.mp4`, `encrypted.mp4`, state whether the operator saw motion and heard tone through **Downloads → Open File** on the phone, and name the local player. If the earlier claim was only file analysis, check the retained lab outputs now, or mark unavailable; no full native rerun. For **DL-06-M**, document separate UI intake, same-path encoded HLS requests and playback for the query-only file if actually observed.
- **DL-02-M**: check real unplugged ≤15% → charging only if conditions are naturally available. Report actual battery/plug states, queue transition and offset or the exact block. Do not spoof the battery for this check.
- **DL-01-M / hard link / DL-05-M2**: no suitable removable volume, ordinary-UID link denial, and current provider supports the origin-aware listener were reported. Leave these blocked unless a separately authorized setup actually supplies the missing capability. Do not grant storage privileges or replace/downgrade WebView to obtain a result.

## 3. Run one named blob check at a time, only with explicit reset authorization

**Ask first.** Each invocation can replace the verified `com.browser.test` APK and clears only the verified disposable `com.browser` app's data **once**, before the selected fixture. Never run the write mode without the owner's confirmation that the profile contains only synthetic data and that its reset is authorized. If verification fails, do not reinstall, clear or remove anything to satisfy it. Keep the browser APK, the offline recipient, any legacy IDs and the old public fixtures untouched.

Run these commands **separately**, for the authorized case, with distinct result directories. Do not omit `--case` (the original three cuts plus two-blob sequence is not requested):

```bash
"${follow[@]}" --case blob-source-switch --reset-confirmed-lab --work "$lab/source-switch"
```

```bash
"${follow[@]}" --case blob-foreign-frame --reset-confirmed-lab --work "$lab/foreign-frame"
```

- `blob-source-switch` has no frame result or operator tap prerequisite. It must record a stalled first chunk, same-tab foreign origin, an old attempt that fails without publishing or leaving staging, and a fresh exact save.
- `blob-foreign-frame` uses native touchscreen DOWN/UP mapped from a visible, hit-tested WebView target. Stay hands-off. It first opens and cancels the exact fixture attachment in a top-level tab as a positive control. In the foreign iframe, the foreign server must record both the trusted touchscreen pointer and actual anchor click. Then require no prompt, row, page transfer or output. Missing input is not a denial pass; there is no chat-to-tap countdown.

Use the **same built test APK** for both runs. After an authorized replacement, `installed_test_sha256_after` must match `followup_test.apk_sha256`, and `app_and_legacy_identity_preserved` must be true. The next invocation may find that exact test APK already installed; do not restore or rebuild packages between cases. Record each case independently. If the runner cannot prove the device identity, stops with cleanup unconfirmed, or the owner wants the failing profile preserved, stop and report before another reset. No forced repeat. The retained browser is still the original candidate; the physical debugger guard has not been loosened.

## 4. Return evidence only

Return each selected case’s `download-device-followup.json` (label the two copies by case), or the check-only JSON if blocked, and one short Markdown report. Include its SHA-256, source commit/tree/ZIP hash, prior JSON hash, device/API/ABI/page size/provider, installed browser SHA and unchanged identity, new instrumentation hash and signer, per-case results, manual observations or exact prerequisites lacking, and the cleanup outcome. No raw ADB/logcat/UI dump, serials, keys, DBs or APKs. Do not run other topics, old build/performance matrices, or sign off Downloads or release.
