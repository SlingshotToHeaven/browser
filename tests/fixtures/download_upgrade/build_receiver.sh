#!/usr/bin/env bash
# Build an isolated offline recipient for the one-run Android test. Do not distribute this APK.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_ENVIRONMENT:-}" == github-hosted ]]
: "${JAVA_HOME:?}"
sdk=$1
out=$2
here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
bin="$sdk/build-tools/34.0.0"
jar="$sdk/platforms/android-34/android.jar"
mkdir "$out"
mkdir "$out/classes"
"$JAVA_HOME/bin/javac" --release 11 -cp "$jar" -d "$out/classes" "$here/FixtureReceiver.java"
# D8 is given every nested class, and the receiver has no external dependencies.
mapfile -d '' classes < <(find "$out/classes" -name '*.class' -print0)
"$bin/d8" --min-api 34 --lib "$jar" --output "$out" "${classes[@]}"
"$bin/aapt2" link -I "$jar" --manifest "$here/AndroidManifest.xml" -o "$out/unsigned.apk"
python3 -B - "$out" <<'PY'
import sys
from pathlib import Path
from zipfile import ZipFile
root = Path(sys.argv[1])
with ZipFile(root / 'unsigned.apk', 'a') as apk:
    apk.write(root / 'classes.dex', 'classes.dex')
PY
"$bin/zipalign" -f 4 "$out/unsigned.apk" "$out/aligned.apk"
# Independent throwaway key, unrelated to the two matching app debug installs.
"$JAVA_HOME/bin/keytool" -genkeypair -keystore "$out/recipient.p12" -storetype PKCS12 \
  -storepass android -keypass android -alias fixture -keyalg RSA -keysize 2048 -validity 2 \
  -dname 'CN=Disposable Emulator Fixture' >/dev/null 2>&1
"$bin/apksigner" sign --ks "$out/recipient.p12" --ks-pass pass:android --out "$out/receiver.apk" "$out/aligned.apk"
rm -f "$out/recipient.p12"
