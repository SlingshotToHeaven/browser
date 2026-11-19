# Address-bar trace pilot (PERF-07 readiness)

This is a **capture check on the installed candidate**, not the paired performance run. It checks whether we can explain one real edit end to end: the visible hit or miss, the app's input/worker slices, and usable SurfaceFlinger FrameTimeline data. It uses one synthetic visit, not the 1k/50k seed. Do not run the [paired-run reseeding instructions](../README.md#device-procedure-perf-07) on the existing profile.

## Confirm the test boundary

- Use the authorized lab phone and the exact verified report-7 debug `com.browser` install (**Browser (Test)**), SHA-256 `cd8c9b95c6eee6c1e4f9844bf988c925ac3b68c28961894c21372605043acb6c`, lab signer `4e437ac464f60f0cd1d0e50d863db88f0881c71c3278151ea141c2af18658ce3`. If it is missing or uncertain, mark the device portion **Blocked**; don't install a different APK for this pilot. Use `adb -s "$serial"` on every device command, and don't record the serial in evidence.
- Perfetto can observe OS and process events outside the app. Capture only on an authorized lab device with synthetic foreground/IME content and an empty or synthetic clipboard already verified by the operator. Don't inspect or change personal clipboard data, open the original browser, or record unrelated activity. Keep raw traces and diagnostics local in Mac scratch, not in Git or an online viewer. Return only sanitized counts and observations. If the isolated capture isn't authorized or possible, do not take it.
- Use Android 12+ with the built-in `perfetto` command and a trusted local Mac Trace Processor. Use only an approved host tool (for example an architecture-matching binary from the [official Perfetto releases](https://github.com/google/perfetto/releases), verified before use). Do not install phone software, change device settings or trace permissions, or fall back to root. If a tool or data source is unavailable, report the exact one and the sanitized error.

## 1. One synthetic hit for both captures

Check for existing host listeners and selected-device reverse mappings for TCP 8020. In a fresh extraction of the pinned ZIP, start `python3 -B tests/fixtures/omnibox.py serve-trace-pilot` on the Mac, bound to loopback only. Add `adb -s "$serial" reverse --no-rebind tcp:8020 tcp:8020`. Open `http://127.0.0.1:8020/perf18tracehit` in a **new regular test tab**, and wait for the page `Fixture perf18tracehit` to commit. This records an ordinary synthetic visit; no bookmark import, database rewrite or clearing data is needed.

Use the browser's address bar (`edit_url`), not an input on a WebView page. With autocomplete already enabled, verify that editing `perf18tracehit` gives the fixture history suggestion (exact loopback URL), and `perf18tracehitx` gives the current search fallback with no fixture row. **Do not submit either search**, tap a suggestion, or turn autocomplete on just for the pilot. If the visible hit/miss isn't established, capture the exact observed UI and report that prerequisite instead of interpreting a trace from another control.

## 2. Capture two isolated edits

Use the [20-second pilot config](../tests/fixtures/omnibox_trace_pilot.pbtxt), not the longer full-run config. It collects app slices, input/view/gfx tracing, process/thread attribution and FrameTimeline. No SQL-text tracing or `sched_switch` capture is needed for this check. Before each recording, prefill the specified text with the caret at the end, leave focus and the keyboard stable, and wait for suggestions to settle. Do setup, hierarchy dumps and screenshots outside the recording.

| Capture | Prefill before starting | Exactly one stimulus during capture | Expected display after stimulus |
| --- | --- | --- | --- |
| `hit` | `perf18tracehitx` | `adb -s "$serial" shell input keyevent KEYCODE_DEL` | `perf18tracehit`; current fallback and the correct fixture local row |
| `miss` | `perf18tracehit` | `adb -s "$serial" shell input text x` | `perf18tracehitx`; current fallback, no stale fixture row |

In one Mac terminal, set up a fresh local scratch directory under `out/`:

```sh
umask 077
mkdir -p out
outdir=$(mktemp -d "$PWD/out/omnibox-pilot.XXXXXXXX")
```

After prefilling, run this in the capture terminal, first with `label=hit`, then with `label=miss`. In another terminal send the one stimulus after capture has started and before it ends. Leave the result on screen; do not refocus or navigate. Wait for the process to exit normally and check its exit status and the output byte count. `-T` disables the PTY and `-o -` streams binary output to the Mac; no phone file is needed. Keep stderr separate.

```sh
label=hit     # or miss, as specified above
( set -C
  adb -s "$serial" shell -T perfetto --txt -c - -o - \
    < tests/fixtures/omnibox_trace_pilot.pbtxt \
    > "$outdir/$label.pftrace" 2> "$outdir/$label.capture.stderr"
)
wc -c < "$outdir/$label.pftrace"
shasum -a 256 "$outdir/$label.pftrace"
```

Use a fresh scratch directory if retrying. A successful shell exit or nonempty file is not proof of the required trace signals. If collection fails, record the command stage, exit code, output size and concise sanitized stderr. If supported, `adb -s "$serial" shell perfetto --query` can establish whether `linux.ftrace`, `linux.process_stats`, and `android.surfaceflinger.frametimeline` are advertised; report only presence/absence for those names, not the full dump.

## 3. Check capture readiness locally

For each completed trace, use the [read-only counts query](../tests/fixtures/omnibox_trace_pilot.sql). `tp` is the path to the approved `trace_processor_shell` (or compatible Trace Processor binary with `-q`). Record its version. Run separately for each label:

```sh
"$tp" --version
"$tp" -q tests/fixtures/omnibox_trace_pilot.sql "$outdir/$label.pftrace"
```

- Require the expected visible hit/miss, a completed main-thread `Browser.omnibox.edit` inside an app `deliverInputEvent` slice, a `Browser.omnibox.lookup` on `Browser-omnibox` (none off that worker in the sample), and `Browser.omnibox.localRows` on main. One isolated edit should not produce multiple settled lookups; if setup edits contaminated the capture, repeat in a new scratch directory or mark it inconclusive. Report the clipboard count, not its contents; zero is normal when the focused edit was already primed.
- Require at least one completed actual app SurfaceFrame with a matching completed SurfaceFlinger DisplayFrame token after the isolated edit (`post_edit_surface_display_pairs > 0`), and no reported nonzero data-loss counters. If the query doesn't find the signals, identify which is missing (process/thread attribution, input dispatch, app work, or FrameTimeline). A differing platform slice name, incomplete trace, parse error, or wrong database is **not** a performance regression result. Don't replace missing fields with zero or weaken the checks.
- These counts show useful trace signals. They do **not** identify the first frame containing the text/row, prove that a particular frame was displayed, prove all SQLite work off-main, or measure input latency. `localRows` also runs for misses. Do not report ADB round-trip, a layout callback, or edit-to-lookup time as presentation. The paired comparison still needs frame/visible-content correlation, repeat trials on both seeds and an agreed latency budget.

## 4. Prepare matched APKs on the Mac only

Independently of the capture result, use `python3 -B tools/prepare_perf_baselines.py /path/to/new/baselines` (**outside** the extraction, nonexistent path under existing scratch). Record the restored-hash check. If an SDK and approved lab debug signer are available, build `./gradlew --no-daemon :app:assembleDebug` separately in `step17` and `step18`, using the same JDK 17, Platform 34, Build Tools and lab debug identity. Record toolchain, SHA-256, versionCode, debug/package check and public signer fingerprint for each. A signer mismatch is a blocker for later swaps; don't change keys or seek production material. **Do not install the baselines, seed/replace on-phone databases, or enable any special install flags.** If build or matching is blocked, report the specific prerequisite or first diagnostic and what did run.

Return `out/android-test-report-8.md` with pinned source ZIP and candidate APK identities/digests; phone model/API/ABI/page size/WebView; hit and miss UI observations and per-trace exit/size/SHA-256/counts; any named missing signals or sanitized errors; and the independent baseline prep/build statuses. Use **Pass / Fail / Blocked / Not run** per component (label insufficient evidence inconclusive, not a pass). No raw trace, real URL, clipboard contents, device identifier or other-app details. Close only the pilot tab and, if it can be identified with certainty, delete only the newly created synthetic visit using History's **Delete Entry**, not Clear All History. Remove only the `tcp:8020` reverse added for this run and stop the fixture. Leave the old IDs, prior test state and settings intact. Do not deploy, sign for release, or push from the test machine.
