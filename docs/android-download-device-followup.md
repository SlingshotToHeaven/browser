# Downloads: focused follow-up on the retained spare phone

This is **five cases**, not another DL-01–DL-06 run. It addresses the three debugger cuts, trusted foreign-frame input, and the explicit blob source switch missing from the `4a90876` evidence. Use the commit-pinned GitHub source ZIP identified in the handoff. Do not rebuild/install the browser, repeat the upgrade or native mux batch, or qualify a release.

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

The check is read-only on the device. Require PASS: same phone/provider, exact v8/test/recipient bytes from the prior report (or the exact same new test APK on a subsequent check), and the private lab marker. The v8 APK must remain SHA-256 `60be1238af1b9725930d2d9ccbe562453c0a6ddd7b002710b9fa911a091dd391`. A newly built `app-debug.apk` is **not** the installed app and must not be installed. If identities don't match, stop without deleting or changing any packages or profiles.

## 2. Record any manual evidence before the reset

Do this **before** the focused runner. Its authorized resets will erase the previous synthetic in-app records and private files, including the earlier playback outputs. If a check already happened as specified, return the existing observation; don't repeat it. Reuse the synthetic fixture server and non-rebinding reverses from [Section 3 of the prior guide](android-download-device-lab.md#3-operator-observations-on-the-same-build) only as needed, always pinned to the same serial. Stop those fixtures and remove only their reverses before starting Section 3.

- **DL-03-M**: document the separate Downloads → Add download paste with that paste's probe and body results, both without cookie or referrer. The earlier `ACTION_SEND` rows are not paste evidence.
- **DL-06**: for each of `separate.mp4`, `transport.mp4`, `fragments.mp4`, `encrypted.mp4`, state whether the operator saw motion and heard tone through **Downloads → Open File** on the phone, and name the local player. If the earlier claim was only file analysis, check the retained lab outputs now, or mark unavailable; no full native rerun. For **DL-06-M**, document separate UI intake, same-path encoded HLS requests and playback for the query-only file if actually observed.
- **DL-02-M**: check real unplugged ≤15% → charging only if conditions are naturally available. Report actual battery/plug states, queue transition and offset or the exact block. Do not spoof the battery for this check.
- **DL-01-M / hard link / DL-05-M2**: no suitable removable volume, ordinary-UID link denial, and current provider supports the origin-aware listener were reported. Leave these blocked unless a separately authorized setup actually supplies the missing capability. Do not grant storage privileges or replace/downgrade WebView to obtain a result.

## 3. Run one focused batch, only with owner authorization to reset the synthetic profile

**Ask before the first reset.** The following replaces only the verified lab's `com.browser.test` APK and clears the verified disposable `com.browser` app's data between cases. It will not install, replace or uninstall the browser APK, delete the prior public fixture folder, or touch other packages. If the owner hasn't confirmed this lab contains only disposable data and approved its resets, do not run the command.

```bash
"${follow[@]}" --reset-confirmed-lab --work "$lab/focused"
```

The debugger rechecks the exact lab APK, private run marker, app UID and PID and its own loopback forward before attachment; the hosted-emulator guard is unchanged. The public cut uses a new per-run directory under public Downloads. Do not force past a guard or stop an arbitrary process. Keep the phone connected. Interact **only** when the runner asks you to tap **Request frame blob**; physically tap that visible button once (not ADB, JS or accessibility click). The fixture has to see both a trusted pointer and trusted click from that iframe before the test can pass.

| Case | Required evidence |
| --- | --- |
| `storage-precommit` | Confirm actual held pre-DB boundary and process stop. No adoption of the occupied complete-looking file. Exact retry after Start over, one fresh HTTP 200 from byte zero, other files unchanged. |
| `storage-private-copy-or-link` | Real cut at 2 MiB of the exclusive copy leaving a distinct short destination and intact 4 MiB stage, or an actual same-inode hard-link cut if supported. No Open/Share of the failed entry. Park only the fixture, then Retry to exact output from stage with **no new HTTP**. A link alone does not verify a copy cut. |
| `storage-public-copy` | Same cut and retry behavior on ordinary public Downloads, not an app-specific fallback. Independent ordinary app/shell file identity must match. Preserve the old folder and new neighbor. |
| `blob-foreign-frame` | Actual pointer and click from the exact foreign iframe, then no prompt, row or output. A missed touch isn't a denial pass. |
| `blob-source-switch` | Real stalled first chunk; the same tab commits a foreign origin; old attempt fails/drains without committed bytes or stage. A fresh attempt saves exact independent fixture bytes. |

If one cut is inconclusive or the runner stops, retain its exact report and profile. Missing cases are not-run. If a debugger could not confirm stopping its target, do not disconnect it manually and resume a held job, rerun, or clear the phone; report the last phase and ask for guidance. The runner removes only its owned reverses and forwards when safe. Leave remaining synthetic files and the lab installation in place.

## 4. Return evidence only

Return `download-device-followup.json` and one short Markdown report. Include its SHA-256, source commit/tree/ZIP hash, prior JSON hash, device/API/ABI/page size/provider, installed browser SHA and unchanged identity, new instrumentation hash and signer, per-case results, manual observations or exact prerequisites lacking, and the cleanup outcome. No raw ADB/logcat/UI dump, serials, keys, DBs or APKs. Do not run other topics, old build/performance matrices, or sign off Downloads or release.
