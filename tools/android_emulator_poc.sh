#!/usr/bin/env bash
# One disposable Android emulator. Not for a handset, local profiles, or release artifacts.
set -euo pipefail

if [[ "${GITHUB_ACTIONS:-}" != true || "${RUNNER_ENVIRONMENT:-}" != github-hosted ]]; then
  echo 'This check runs only on a disposable GitHub-hosted runner.' >&2
  exit 1
fi
mode=${1:-launch}
download_args=()
if [[ "$mode" == download-batch && "${ANDROID_DOWNLOAD_SCOPE:-all}" == frame-recheck ]]; then
  download_args+=(--frame-recheck)
fi
if [[ $# -gt 1 || "$mode" != launch && "$mode" != download-publication && "$mode" != download-upgrade && "$mode" != download-mid-copy && "$mode" != download-batch && "$mode" != browser-recovery && "$mode" != functional-followup && "$mode" != public-copy ]]; then
  echo 'Expected launch, download-publication, download-upgrade, download-mid-copy, download-batch, browser-recovery, functional-followup, or public-copy.' >&2
  exit 2
fi
: "${RUNNER_TEMP:?}" "${ANDROID_HOME:?}" "${GITHUB_SHA:?}"
root="$RUNNER_TEMP/browser-emulator-poc"
image='system-images;android-34;google_apis;x86_64'
serial=emulator-5554
sdk="$ANDROID_HOME"
adb="$sdk/platform-tools/adb"
emulator="$sdk/emulator/emulator"
component='com.browser/com.privacy.browser.MainActivity'

if [[ ! -c /dev/kvm ]]; then
  echo 'No /dev/kvm on this runner; stopping before any SDK or image download.' >&2
  exit 1
fi
if [[ ! -r /dev/kvm || ! -w /dev/kvm ]]; then
  sudo chown "$(id -u):$(id -g)" /dev/kvm  # this ephemeral runner only
  sudo chmod u+rw /dev/kvm
fi
if [[ -x "$emulator" ]]; then "$emulator" -accel-check; fi
if [[ "$(df -Pk "$RUNNER_TEMP" | awk 'END { print $4 }')" -lt 7340032 ]]; then
  echo 'PoC requires at least 7 GiB free on the temporary runner; no image installed.' >&2
  exit 1
fi
mkdir "$root"
mkdir -p "$root/source" "$root/proof" "$root/avd" "$root/android"
export ANDROID_AVD_HOME="$root/avd" ANDROID_USER_HOME="$root/android"
# Gradle's default home is already outside this checkout on the disposable runner.
unset ADB_SERVER_SOCKET ANDROID_ADB_SERVER_ADDRESS ANDROID_ADB_SERVER_PORT ADB_SERVER_PORT ANDROID_SERIAL

sdk_tool() {
  local tool=$1 path
  # Hosted images do not always put the versioned command-line tools on PATH.
  for path in "$sdk/cmdline-tools/latest/bin/$tool" "$sdk"/cmdline-tools/*/bin/"$tool" "$sdk/tools/bin/$tool"; do
    if [[ -x "$path" ]]; then printf '%s\n' "$path"; return; fi
  done
  if command -v "$tool"; then return; fi
  printf 'Missing Android command-line tool: %s\n' "$tool" >&2
  return 127
}
sdkmanager=$(sdk_tool sdkmanager)
avdmanager=$(sdk_tool avdmanager)

# Probe ordinary build dependencies before spending disk or time on an image.
# The alternative is the same Maven Central repository, not a new dependency source.
central_available() {
  local endpoint=$1 coordinate
  for coordinate in commons-codec/commons-codec/1.10/commons-codec-1.10.pom \
    com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.pom; do
    if ! curl -fLsS --max-time 12 "$endpoint/$coordinate" -o "$root/probe.pom" \
      || ! grep -q '<project' "$root/probe.pom"; then
      return 1
    fi
  done
  return 0
}
default_central=https://repo.maven.apache.org/maven2
central=''
for endpoint in "$default_central" https://repo1.maven.org/maven2 https://maven-central.storage-download.googleapis.com/maven2; do
  if central_available "$endpoint"; then central=$endpoint; break; fi
done
build_args=()
if [[ -z "$central" ]]; then
  echo 'Maven Central endpoints unavailable; trying only the runner’s existing Gradle cache.'
  build_args+=(--offline)
elif [[ "$central" != "$default_central" ]]; then
  printf 'Using Maven Central through %s for this disposable build.\n' "$central"
  export POC_MAVEN_CENTRAL="$central"
  cat > "$root/central.init.gradle" <<'GRADLE'
gradle.settingsEvaluated { settings ->
    [settings.pluginManagement.repositories, settings.dependencyResolutionManagement.repositories].each { repos ->
        repos.withType(org.gradle.api.artifacts.repositories.MavenArtifactRepository).configureEach { repo ->
            if (repo.url?.host == 'repo.maven.apache.org' && (repo.url.path in ['/maven2', '/maven2/'])) {
                repo.setUrl(System.getenv('POC_MAVEN_CENTRAL'))
            }
        }
    }
}
GRADLE
  build_args+=(--init-script "$root/central.init.gradle")
else
  echo 'Maven Central default endpoint served the required probe POMs.'
fi

[[ "$(git rev-parse HEAD)" == "$GITHUB_SHA" ]]
git archive "$GITHUB_SHA" | tar -x -C "$root/source"
if [[ "$mode" == download-publication || "$mode" == download-mid-copy || "$mode" == public-copy ]]; then
  mkdir "$root/jdi"
  "${JAVA_HOME:?}/bin/javac" --add-modules jdk.jdi -d "$root/jdi" \
    "$root/source/tests/fixtures/download_publication/PublicationPause.java"
elif [[ "$mode" == download-upgrade ]]; then
  python3 -B "$root/source/tools/prepare_perf_baselines.py" "$root/baselines"
fi
"$sdkmanager" --sdk_root="$sdk" 'platforms;android-34' 'build-tools;34.0.0'
build_debug() { (cd "$1" && shift && ./gradlew --no-daemon --max-workers=2 "${build_args[@]}" :app:assembleDebug "$@"); }
# Old host binaries lack -abort_on empty_output_stream or can crash on the tiny
# AAC/TS remux. Pin the month-end build used for host playback (not in either APK).
batch_ffmpeg() {
  local release='autobuild-2026-09-30-13-08' asset='ffmpeg-n9.0.2-17-g2a571b6068-linux64-gpl-9.0'
  local archive="$root/ffmpeg-host.tar.xz" dir="$root/ffmpeg-host"
  mkdir "$dir" || return 1
  curl -fLsS --max-time 90 --retry 1 \
    "https://github.com/FFmpeg-Builds/FFmpeg-Builds/releases/download/$release/$asset.tar.xz" -o "$archive" || return 1
  printf '%s  %s\n' '68ee646831adaae2495618346f3bba94ff207ff83bbd34d643e7004730d66269' "$archive" \
    | sha256sum -c >&2 || return 1
  tar -xJf "$archive" -C "$dir" --strip-components=2 "$asset/bin/ffmpeg" || return 1
  [[ -x "$dir/ffmpeg" ]] || return 1
  printf '%s\n' "$dir/ffmpeg"
}
host_status=0
if [[ "$mode" == download-batch || "$mode" == browser-recovery || "$mode" == functional-followup || "$mode" == public-copy ]]; then
  build_debug "$root/source" :app:assembleDebugAndroidTest
  test_apk="$root/source/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
  [[ -s "$test_apk" ]]
else
  build_debug "$root/source"
fi
if [[ "$mode" == download-batch && ${#download_args[@]} == 0 ]]; then
  FFMPEG_TEST_BIN=$(batch_ffmpeg) || { FFMPEG_TEST_BIN=''; echo 'Host FFmpeg unavailable; record affected cases as blocked.'; }
  export FFMPEG_TEST_BIN
  (
    cd "$root/source"
    ./gradlew --no-daemon --continue --max-workers=2 "${build_args[@]}" :downloader:testDebugUnitTest \
      :app:testDebugUnitTest --tests 'com.privacy.browser.comic.*' --tests 'com.privacy.browser.media.*'
  ) || host_status=$?
  python3 -B "$root/source/tools/record_download_batch_host.py" "$root" "$host_status" || host_status=1
fi
apk="$root/source/app/build/outputs/apk/debug/app-debug.apk"
[[ -s "$apk" ]]
if [[ "$mode" == download-upgrade ]]; then
  build_debug "$root/baselines/step19"
  baseline="$root/baselines/step19/app/build/outputs/apk/debug/app-debug.apk"
  bash "$root/source/tests/fixtures/download_upgrade/build_receiver.sh" "$sdk" "$root/recipient"
  receiver="$root/recipient/receiver.apk"
  [[ -s "$baseline" && -s "$receiver" ]]
fi
printf '::notice title=Browser debug build complete::Built the unchanged debug APK; no APK uploaded.\n'
# Provision the single system image only after a real browser APK exists.
"$sdkmanager" --sdk_root="$sdk" platform-tools emulator "$image"
"$emulator" -accel-check
printf '::notice title=Emulator prerequisites checked::KVM and Android SDK tools are available; no AVD boot yet.\n'

"$adb" start-server
if [[ -n "$("$adb" devices | awk 'NR > 1 && NF {print $1}')" ]]; then
  echo 'Unexpected ADB target on this runner; refusing to install.' >&2
  exit 1
fi
printf 'no\n' | "$avdmanager" create avd --name browser-poc --package "$image" --device pixel_2
"$emulator" @browser-poc -port 5554 -accel on -no-window -gpu swiftshader_indirect \
  -no-snapshot -wipe-data -no-boot-anim -no-audio -no-metrics -camera-back none -camera-front none \
  -cores 2 -memory 2048 -partition-size 2048 > "$root/emulator.log" 2>&1 &
emulator_pid=$!
android() { "$adb" -s "$serial" "$@"; }
cleanup() {
  local status=$?
  if (( status != 0 )) && [[ "${booted:-false}" != true ]]; then
    grep -Ev 'androidboot\.(qemu\.adb\.pubkey|serialno)=' "$root/emulator.log" | tail -30 >&2 || :
  fi
  timeout 8 "$adb" -s "$serial" emu kill >/dev/null 2>&1 || kill "$emulator_pid" 2>/dev/null || :
  "$adb" kill-server >/dev/null 2>&1 || :
}
trap cleanup EXIT

# Never wait on or select a non-emulator target.
timeout 180 "$adb" -s "$serial" wait-for-device
booted=false
for _ in {1..60}; do
  kill -0 "$emulator_pid"
  if [[ "$(timeout 5 "$adb" -s "$serial" shell getprop sys.boot_completed | tr -d '\r\n')" == 1 ]]; then
    booted=true
    break
  fi
  sleep 2
done
[[ "$booted" == true ]] || { echo 'Android boot did not complete.' >&2; exit 1; }
api=$(android shell getprop ro.build.version.sdk | tr -d '\r\n')
abis=$(android shell getprop ro.product.cpu.abilist | tr -d '\r\n')
[[ "$api" == 34 ]]
if [[ ",$abis," != *,arm64-v8a,* ]]; then
  echo 'This image does not advertise ARM64 translation for the unmodified debug APK.' >&2
  exit 1
fi
android shell input keyevent KEYCODE_WAKEUP
android shell wm dismiss-keyguard
if [[ "$mode" == download-upgrade ]]; then
  python3 -B "$root/source/tools/check_android_download_upgrade.py" \
    --adb "$adb" --work "$root" --sdk "$sdk" --baseline "$baseline" --candidate "$apk" --recipient "$receiver"
  exit 0
fi
android install --user 0 "$apk"  # the normal debug APK, not a mock/repacked build
# Remove only the first-run notification prompt on the empty AVD.
android shell pm grant --user 0 com.browser android.permission.POST_NOTIFICATIONS
if [[ "$mode" == download-batch ]]; then
  android install -t --user 0 "$test_apk"
  device_status=0
  python3 -B "$root/source/tools/check_android_download_batch.py" \
    --adb "$adb" --work "$root" --apk "$apk" --test-apk "$test_apk" "${download_args[@]}" || device_status=$?
  (( device_status == 0 && host_status == 0 ))
  exit 0
fi
if [[ "$mode" == browser-recovery ]]; then
  android install -t --user 0 "$test_apk"
  python3 -B "$root/source/tools/check_android_browser_recovery.py" \
    --adb "$adb" --work "$root" --apk "$apk" --test-apk "$test_apk"
  exit 0
fi
if [[ "$mode" == functional-followup ]]; then
  android install -t --user 0 "$test_apk"
  python3 -B "$root/source/tools/check_android_functional_followup.py" \
    --adb "$adb" --work "$root" --apk "$apk" --test-apk "$test_apk"
  exit 0
fi
if [[ "$mode" == public-copy ]]; then
  android install -t --user 0 "$test_apk"
  python3 -B "$root/source/tools/check_android_public_copy.py" \
    --adb "$adb" --work "$root" --apk "$apk" --test-apk "$test_apk"
  exit 0
fi
if [[ "$mode" == download-publication || "$mode" == download-mid-copy ]]; then
  apk_sha=$(sha256sum "$apk" | cut -d ' ' -f1)
  python3 -B "$root/source/tools/check_android_${mode//-/_}.py" \
    --adb "$adb" --work "$root" --apk-sha256 "$apk_sha"
  exit 0
fi
android shell am start -W -n "$component" | tr -d '\r' | tee "$root/launch.txt"
grep -Fxq 'Status: ok' "$root/launch.txt"

foreground() {
  android shell dumpsys activity activities | awk -v component="$component" '
    /topResumedActivity|mResumedActivity/ && index($0, component) { found=1 }
    END { exit !found }
  '
}
ready=false
for _ in {1..10}; do
  if foreground \
    && timeout 12 "$adb" -s "$serial" shell uiautomator dump --compressed /data/local/tmp/browser-poc.xml >/dev/null 2>&1 \
    && android exec-out cat /data/local/tmp/browser-poc.xml > "$root/window.xml" \
    && python3 -B - "$root/window.xml" <<'PY'
import sys
from xml.etree import ElementTree as ET

try:
    nodes = [n.attrib for n in ET.parse(sys.argv[1]).iter('node') if n.get('package') == 'com.browser']
except ET.ParseError:
    sys.exit(1)
url_bar = any(n.get('resource-id', '').endswith(':id/edit_url') and n.get('enabled') == 'true' for n in nodes)
home = any(n.get('class') == 'android.webkit.WebView' or 'Privacy Browser' in n.get('text', '') for n in nodes)
sys.exit(0 if url_bar and home else 1)
PY
  then
    ready=true
    break
  fi
  sleep 2
done
[[ "$ready" == true ]] || { echo 'Browser activity, ready address bar, or home WebView not confirmed.' >&2; exit 1; }
android exec-out screencap -p > "$root/proof/browser.png"
foreground
android shell pidof com.browser >/dev/null
python3 -B - "$root/proof/browser.png" <<'PY'
import struct
import sys
from pathlib import Path

png = Path(sys.argv[1]).read_bytes()
assert 1024 < len(png) < 1024 * 1024 and png[:8] == b'\x89PNG\r\n\x1a\n' and png[12:16] == b'IHDR'
w, h = struct.unpack('>II', png[16:24])
assert w > 0 and h > 0
print(f'Captured Android display: {w}x{h}, {len(png)} bytes')
PY
apk_sha=$(sha256sum "$apk" | cut -d ' ' -f1)
png_sha=$(sha256sum "$root/proof/browser.png" | cut -d ' ' -f1)
{
  printf 'Commit: %s\n' "$GITHUB_SHA"
  printf 'Android API: %s (%s)\n' "$api" "$image"
  printf 'Advertised ABIs: %s\n' "$abis"
  printf 'APK: debug, unchanged build inputs, SHA-256 %s\n' "$apk_sha"
  printf 'Foreground: %s; address bar enabled and WebView visible\n' "$component"
  printf 'ADB screenshot SHA-256: %s\n' "$png_sha"
  printf 'Scope: launch only; no physical-device, FFmpeg, download, or performance evidence.\n'
} | tee "$root/proof/launch.txt"
printf '::notice title=Android browser launch verified::BROWSER_POC_PROOF %s %s API-%s\n' "$GITHUB_SHA" "$png_sha" "$api"
