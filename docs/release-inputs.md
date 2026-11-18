# Release inputs and third-party inventory

**Inventory only, not permission to distribute.** This describes the checkout for Step 21, including the local remediation changes over `e83d2fa08c17dce89d8ba52c22474e89126f16d7`. The corresponding-source, attribution, and resolved dependency records are not complete.

## Build inputs and declared runtime dependencies

| Input | Checkout selection | What to record for a release |
| --- | --- | --- |
| JDK / Gradle | Java 17 sources; CI uses Temurin 17. Wrapper 8.7 pins the distribution SHA-256 in `gradle/wrapper/gradle-wrapper.properties`. | Exact JDK, wrapper JAR hash, distribution hash and successful wrapper validation. |
| Android | AGP 8.6.1 (includes R8); compile/target SDK 34, min SDK 26; CI installs Build Tools 34.0.0 and 35.0.0 for inspection. No app-side NDK build: native inputs are prebuilts. | Exact SDK, AGP/R8 and tool versions and resolved dependency hashes. Retarget and retest if the chosen channel requires it. |
| App | `:app`, `:downloader`, `:image-search`; `com.browser`; versionName `1.0.0`, versionCode `1`. Debug uses the same ID, has a distinct launcher label and derives the test APK ID `com.browser.test`. Release minifies and shrinks resources, and Gradle produces unsigned output. | Source revision, clean tree, variant, R8 reports, merged manifest, final signed artifact digest and approved certificate. |
| System WebView | Installed and updated separately, not bundled. Document-start scripts and origin-aware messages are feature-gated; blob intake requires the origin-aware listener. Darkening and Safe Browsing depend on provider capabilities. | Android version, WebView package/version and feature outcomes for each test device. A feature skip is not a pass for that path. |
| Optional Worker | Separate `cloudflare-worker/` deployment; Workerd/Miniflare `4.20260730.0` is dev-only, pinned by the v3 npm lock. No app-side Node dependency; no deploy in CI. | Separate Worker revision, Wrangler/tool version, compatibility date, staging namespace, migration and local/staging test records. |

| Direct release request | Modules requesting it | Upstream license reference (verify actual resolved artifacts) |
| --- | --- | --- |
| `androidx.annotation:annotation:1.8.2` | all three | [AndroidX](https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt), Apache-2.0 |
| `androidx.core:core:1.13.1` | downloader, image-search | AndroidX, Apache-2.0 |
| `androidx.recyclerview:recyclerview:1.3.2` | app, downloader | AndroidX, Apache-2.0 |
| `androidx.webkit:webkit:1.12.0` | app, downloader | AndroidX, Apache-2.0 |
| `org.jsoup:jsoup:1.18.1` | app | [jsoup 1.18.1 LICENSE](https://github.com/jhy/jsoup/blob/jsoup-1.18.1/LICENSE), MIT |
| `org.jspecify:jspecify:1.0.0` | app | [JSpecify 1.0.0 LICENSE](https://github.com/jspecify/jspecify/blob/v1.0.0/LICENSE), Apache-2.0. Resolves jsoup's annotation types for R8; confirm the resolved JAR and packaged notices. |
| FFmpeg / `libffmpegkit.so` | downloader | Embedded markers below; corresponding source and notices unresolved |

JUnit, host `org.json`, AndroidX test runner, instrumentation and synthetic media fixtures are not release runtime inputs. These are **declared** Android library coordinates, not the selected transitive graph or APK contents. From the build that produces the signed artifact, retain:

```sh
./gradlew --no-daemon :app:dependencies --configuration releaseRuntimeClasspath
./gradlew --no-daemon :app:buildEnvironment
```

Record the selected AAR/JARs, checksums and any bundled assets or native libraries they contribute. Confirm per-artifact `LICENSE`/`NOTICE` and dependency POM provenance (including transitives); assemble the required notices and verify how users of the exact distributable receive them. The checkout does not have a complete Gradle dependency lock/verification record or a release SBOM. Archive pinned resolution and input checksums before signing off on reproducibility; a checksum for the Gradle distribution is not dependency verification.

## Bundled assets and rights

The three `src/main/assets` trees contain 13 files / **2,096,403 source bytes**. These are inputs; Android packaging can merge paths (including the image-picker duplicate) and use different compression. [`release-assets-build-inputs.sha256`](release-assets-build-inputs.sha256) lists every source asset plus the wrapper JAR and Worker lockfile.

| Asset(s), under module `src/main/assets` unless noted | Evidence and gap |
| --- | --- |
| `app/filters/hosts.txt` (1,997,459 bytes) | No source/version/license header or source-generation recipe found; origin, upstream snapshot(s), changes and redistribution terms **unknown**. Do not assume the named lists in the planning documents produced this file. |
| `app/filters/cosmetic_rules.txt` (2,412 bytes) | File labels a curated EasyList subset. [EasyList upstream terms](https://easylist.to/pages/licence.html) are GPL-3.0-or-later or CC-BY-SA-3.0-or-later unless otherwise noted. Exact source revision, extract/diff and chosen license and attribution are not recorded; verify before distribution. |
| `app/filters/scriptlets/*.js` (5), `app/scripts/fingerprint_defense.js` | In-tree scripts; audit original authorship and any adapted snippets, capture corresponding notices. Repo presence is not a third-party rights determination. |
| `app/home.html`, `app/ai/ai_assistant_guide.json`, both `image_search_picker.js` copies, `downloader/downloader/blob_intake.js` | In-tree markup, JSON and scripts. Confirm ownership and imports before distributing. Compare both picker copies and inspect merged APK assets rather than counting both as packaged bytes. |
| Module `src/main/res` | XML icons, launcher art, themes, layouts, strings and vectors. Confirm project ownership or upstream source and notice for the drawings. No standalone font/image archive was found in the source inventory. Audit resources from resolved AARs separately. |

For a release, record a source URL, revision, license, copyright notice and modification/generation record for every third-party input. Restore the required notices and source access for verified inputs, or replace inputs of unknown origin with reviewed, licensed equivalents and rerun filtering/visual parity. There is no repository-wide license/notice decision here.

## Native configuration and provenance

[`native-arm64.sha256`](native-arm64.sha256) pins the eight objects under `downloader/src/main/jniLibs/arm64-v8a/`. The checkout files are ELF64 little-endian AArch64; all **28** `PT_LOAD` headers use `p_align=0x4000` and have congruent offsets/addresses. No other ABI prebuilt was found. Total size is **5,502,472 raw bytes** (5.502 MB / 5.248 MiB). Neither this nor recompressing source files measures the APK or proves loading on Android.

| Object(s) | Embedded evidence (not source attestation) |
| --- | --- |
| `libavcodec`, `libavdevice`, `libavfilter`, `libavformat`, `libavutil`, `libswresample`, `libswscale` | `libavutil` reports FFmpeg **6.0**; all seven have the same [observed configure string](ffmpeg-observed-config.txt) and LGPL-2.1-or-later strings. Android notes encode API 21 / NDK r27 build 12077973. The configure paths mention `ffmpeg-android-maker`; shared, no native networking, pruned demux/mux/codec/filter lists, max page size 16384. The configuration mentions `libopenh264` in codec selection; this does not establish that it was linked. |
| `libffmpegkit` | Native symbols and class lookups match the `com.arthenica.ffmpegkit.FFmpegKitConfig` bridge; Android note encodes API 24 / NDK r28c build 13676358; clang 19.0.1 and GNU build ID `ddb489af420f0c025606efdafc9e45fadbc31351`. No FFmpegKit source revision or unambiguous license/version string found for this binary. |

`DT_NEEDED` names are the bundled `libav*` dependencies and Android system `libandroid`, `libc`, `libdl`, `liblog`, `libm`, `libz`. No dynamic `libopenh264` dependency was observed; `DT_NEEDED` cannot establish the absence of statically linked code. No independent decoder, encoder, or provider support is inferred from configure flags.

Upstream leads: [FFmpeg tag `n6.0`](https://github.com/FFmpeg/FFmpeg/tree/n6.0) (`ea3d24bbe3c58b171e55fe2151fc7ffaca3ab3d2`), including its [license file](https://github.com/FFmpeg/FFmpeg/blob/n6.0/COPYING.LGPLv2.1) and [licensing checklist](https://ffmpeg.org/legal.html), and [FFmpegKit tag `v6.0`](https://github.com/arthenica/ffmpeg-kit/tree/v6.0) (`d6be56d7aec286eb3c292d6b23ff07a6b70d8693`). These are **reference points**, not identified corresponding sources for the checked-in binaries. Before distribution, recover (or rebuild from) exact FFmpeg, wrapper and dependent sources with patches, original license/notice texts, NDK toolchain, source or archive hashes, build scripts/config/cache, link inputs, generated/stripped artifacts and documented size/config choices. Verify applicable LGPL or other source and relinking obligations and any codec rights with the release owner. Review maintenance/security status of the exact native build, including the retired upstream FFmpegKit. If provenance cannot be established, replace or keep those binaries out of the distributable rather than shipping on the strength of the version string.

Local inspection from the repository root (read-only):

```sh
sha256sum -c docs/native-arm64.sha256 docs/release-assets-build-inputs.sha256
python3 -B tools/verify_release_apk.py native downloader/src/main/jniLibs/arm64-v8a
readelf -W -l -n -d downloader/src/main/jniLibs/arm64-v8a/libavutil.so
```

Keep the final APK inventory beside the artifact record; packaging, R8 and signing can change the byte totals. See [artifact and release gates](release-readiness.md).
