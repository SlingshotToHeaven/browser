# One address-bar trace-readiness retry (report 10)

The [report-10 addendum](android-test-followup.md#report-10-split-buffer-retry-dispatch-link-unresolved) used the one approved capture. This protocol is historical; do not repeat it. The next handoff is [offline](omnibox-trace-attribution.md).

**Do not connect to the device or run any ADB command until the owner explicitly approves this one capture** on the selected lab phone and confirms the retained `com.browser` install is disposable. The testing ZIP and this procedure are not approval. If authorization or any prerequisite is missing, report **Blocked**; no phone work is required to inspect the retained traces.

[Report 9](android-test-followup.md#report-9-ring-buffer-overwrite-confirmed) reconciled the original capture inputs and found substantial service-buffer overwrite on both traces. It did not establish that a particular input event was overwritten or that the platform blocked app tracing. To test readiness after addressing the measured loss, the [retry config](../tests/fixtures/omnibox_trace_retry.pbtxt) shortens the window to **8 seconds**, drops `gfx`, keeps ATrace `input` / `view` for `com.browser`, and routes the three sources to separate **DISCARD** buffers. This protects initial process attribution and earlier packets from later writes; if a buffer fills it can still discard data. FrameTimeline is still requested independently. This is not a controlled comparison of the old and new configs or a guarantee of loss-free collection.

## 1. Preflight

- Verify the new source ZIP and extract it freshly. The new config file SHA-256 must be `aeb9ad928517126ab60e5f27ecedaafcb03ddb73260e9ff881c0933f81472ed6`. Use only that config with no edits or fallback. `tests/fixtures/omnibox_trace_pilot.pbtxt` is the previous config, not the retry.
- Reuse the authorized lab phone and exact verified **Browser (Test)** `com.browser` debug APK (SHA-256 `cd8c9b95c6eee6c1e4f9844bf988c925ac3b68c28961894c21372605043acb6c`; public lab signer `4e437ac464f60f0cd1d0e50d863db88f0881c71c3278151ea141c2af18658ce3`). If the installed identity or state is uncertain, stop. Use `adb -s "$serial"` for every device command; do not include the serial in evidence. No installs, APK swaps, resets, settings/permission/property changes, or root. Do not open the old browser or use any real browsing, clipboard or keyboard content. Only capture when the operator confirms that the foreground/IME and any clipboard content are synthetic or empty. Do not inspect personal clipboard data to verify this. Keep all raw traces, config and logs private on the Mac, not in Git or an online viewer.
- Use the built-in device `perfetto` and the approved local `trace_processor_shell` (`tp` is its path). Optionally, before device work, classify the **37 setup notices** on the retained report-8 traces using `"$tp" -q tests/fixtures/omnibox_trace_setup.sql "$trace"`. This is new evidence, not a repeat of report 9. The optional query uses v59.1 import-log tables and returns only category counts. If the detail is unavailable, say why; do not guess or share message text. A clearly unavailable required producer or explicitly blocked required setup is a blocker, not a reason to change permissions or use another device.

## 2. One synthetic hit

Check that host TCP 8020 and the selected-device reverse are unused. Start `python3 -B tests/fixtures/omnibox.py serve-trace-pilot` on the Mac (loopback only) and add `adb -s "$serial" reverse --no-rebind tcp:8020 tcp:8020`. In **one new regular test tab**, open `http://127.0.0.1:8020/perf18tracehit` and wait for the inert page to commit. In the address bar, prefill `perf18tracehitx`, caret at the end, then let the keyboard, focus and suggestions settle. Require the current fallback and no fixture history row before the edit. Do not submit either search or change autocomplete settings. If the UI precondition fails, skip capture and report it.

From the new extraction on the Mac, run once in the capture terminal. Enter `serial` locally; never copy it into the report. The checksum check protects the exact stdin file. Capture stderr separately; `-T` disables the PTY and `-o -` streams the raw trace to the Mac without a phone file.

```sh
(
  cfg=tests/fixtures/omnibox_trace_retry.pbtxt
  printf '%s  %s\n' aeb9ad928517126ab60e5f27ecedaafcb03ddb73260e9ff881c0933f81472ed6 "$cfg" | shasum -a 256 -c - || exit 1
  test -n "$serial" || exit 1
  umask 077
  mkdir -p out || exit 1
  outdir=$(mktemp -d "$PWD/out/omnibox-retry.XXXXXXXX") || exit 1
  printf 'Mac scratch: %s\n' "$outdir"
  trace="$outdir/hit.pftrace"
  if (set -C; adb -s "$serial" shell -T perfetto --txt -c - -o - < "$cfg" \
       > "$trace" 2> "$outdir/capture.stderr"); then capture_rc=0; else capture_rc=$?; fi
  printf 'capture_rc=%s\n' "$capture_rc"
  if [ -f "$trace" ]; then wc -c < "$trace"; shasum -a 256 "$trace"; fi
)
```

While that command is recording (about 2–3 seconds after launch, if still active), send **one** `adb -s "$serial" shell input keyevent KEYCODE_DEL` from a second terminal. Verify `perf18tracehit` and the exact fixture **HISTORY** row alongside the current fallback. Leave the result visible until recording ends. No screenshot, hierarchy dump, extra input or refocus during capture. If it ends early or the stimulus fails, mark that check inconclusive; **do not capture a second time** or widen collection. A `No PTY` warning alone is not a producer error. Server or trigger failure, nonzero capture exit or an empty trace must be reported separately from visible behavior.

## 3. Verify capture, loss and readiness separately

Use only local Trace Processor. Set `trace` and `outdir` to this run's outputs. The config CSV is for **local inspection only**. Also run the setup-category query from preflight on this trace if supported and there are setup notices.

```sh
"$tp" --version
"$tp" -Q "SELECT str_value AS config_pbtxt FROM metadata WHERE name = 'trace_config_pbtxt' AND length(str_value) > 0" "$trace" \
  > "$outdir/config.local.csv"
"$tp" -q tests/fixtures/omnibox_trace_health.sql "$trace"
"$tp" -q tests/fixtures/omnibox_trace_pilot.sql "$trace"
```

- **Identity:** Require the embedded config and `retry_session_matches = 1` for `browser-omnibox-readiness-retry`. Compare recorded duration, fill policies, source names and targets, initial process scan and thread-name flags, and ATrace app/categories against the submitted file. The expected indexes / sizes in KiB are `0 / 32768` for ftrace, `1 / 4096` for process stats and `2 / 8192` for FrameTimeline. The tag alone is not a config check.
- **Collection:** Require service stats for all three buffers with matching allocated sizes, and no reported overwrites, dropped/discarded chunks/patches or failed flushes. Check ftrace read and overrun/drop rows by CPU, parser exclusions and any nonzero `data_loss` / `error` stats. Missing required buffer or ftrace loss rows are **unavailable**, not zero. Preserve setup notice count/category separately; unresolved setup affecting a required source blocks readiness. A positive loss counter invalidates this capture for readiness even if some app signals survived; it does not identify the lost events. A count of wraps, a zero summary loss count or the file length alone is not a substitute for the explicit counters.
- **Readiness (unchanged attribution):** Require the correct visible hit, an identified app process and one completed main edit nested in app `deliverInputEvent`, exactly one `Browser.omnibox.lookup` on `Browser-omnibox` and none off it, main `localRows`, and an actual app Surface/SF DisplayFrame token pair completing after the edit. Report the clipboard slice count only. Raw print counts, an app layer label or a later frame without the strict join cannot substitute. Name the first missing signal (e.g. input present but edit not nested), keeping setup, loss and attribution evidence distinct. Even a readiness pass does not prove which frame showed the text/rows or measure latency.

Return only `out/android-test-report-10.md`: approval/prerequisites and scoped teardown; source ZIP, config and debug APK identities; device model/API/ABI/page size/WebView and tool version; capture exit/size/hash; exact synthetic row/fallback observation; sanitized embedded config comparison, health/strict rows and any relevant error categories. Use **Pass / Fail / Blocked / Not run** per component. No serial, raw logs, screenshots, config dump, paths that identify the owner, or trace attachments. Close only the tab added here, delete only its new synthetic visit if certainly identified, remove only your new reverse, and stop only the fixture you started. No repeats of prior download/UI/baseline runs, paired timings, APK changes, profile replacement, release or Git work. PERF-07 remains a separate gate requiring authorized disposable paired state and presentation evidence.
