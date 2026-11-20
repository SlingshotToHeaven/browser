# Offline omnibox trace follow-up (report 9)

This procedure was completed in [report 9](android-test-followup.md#report-9-ring-buffer-overwrite-confirmed). Do not repeat it by default; the [current offline handoff](android-test-followup.md#next-device-run) uses the subsequent retry trace.

[Report 8](android-test-followup.md#report-8-hitmiss-and-baseline-preflight-trace-attribution-unresolved) reported a visible synthetic hit and miss, two parseable traces, and two matched debug builds that were not installed. It did **not** establish capture readiness. This follow-up is **Mac-only**: inspect the existing artifacts in place. Do not connect to the phone, run ADB, collect another trace, rebuild or install APKs, or change device, profile or tracing settings. If an artifact or the approved local Trace Processor is unavailable, say exactly what is missing.

## 1. Reconcile the capture inputs

Use a fresh extraction of the supplied full source ZIP for the queries. Find the original `hit.pftrace` and `miss.pftrace` from report 8. For each, check byte count (`wc -c < "$trace"`) and SHA-256 (`shasum -a 256 "$trace"`) against the [reported artifact identities](android-test-followup.md#report-8-hitmiss-and-baseline-preflight-trace-attribution-unresolved). Label a missing or mismatched trace **Blocked** for analysis of that capture; do not substitute or regenerate one. Use the approved local `trace_processor_shell` (`tp` is its path); record its version. Keep outputs under private Mac scratch space, outside version control. With `trace` pointing to one matching trace and `label` set to `hit` or `miss`, run from the new extraction:

```sh
"$tp" --version
umask 077
mkdir -p out
scratch=$(mktemp -d "$PWD/out/omnibox-offline.XXXXXXXX")
# For local inspection only; do not copy this CSV or stderr into the report.
"$tp" -Q "SELECT str_value AS config_pbtxt FROM metadata WHERE name = 'trace_config_pbtxt' AND length(str_value) > 0" "$trace" \
  > "$scratch/$label.config.csv" 2> "$scratch/$label.config.stderr"
```

Return presence/absence of embedded config and only the relevant fields: `duration_ms`, each buffer's index / `size_kb` / fill policy, the three requested source names and target-buffer indexes, process scan / thread-name flags, ATrace categories and whether `com.browser` was requested, and whether explicit ftrace events were requested (mention `sched` events if present). Redact unrelated source, app or filter names. A source named in the config was **requested**, not necessarily collected. Report an omitted configuration field as omitted; all three default to buffer 0 if `target_buffer` is unset.

The [pinned pilot config](../tests/fixtures/omnibox_trace_pilot.pbtxt) SHA-256 is `6035a7139cd8c86edc4fa49f1807959161dcb610d299a252e067f9d19ea33fef`: **20,000 ms; one 16,384 KiB ring buffer; `input`, `view`, `gfx` and `com.browser`; no explicit `ftrace_events`**. `linux.process_stats` requests the initial process scan and thread names; `android.surfaceflinger.frametimeline` is the third source alongside `linux.ftrace`. Report 8 described 64 MiB and `sched` instead of `gfx`. State which fields match or differ. If the exact cfg file used in the original command was retained, report its SHA-256 and how it is linked to that command; if not, say so. Do not hash a newly extracted copy as if it were the executed input, or compare the reformatted embedded text byte-for-byte to the submitted file. If config metadata is absent, do not infer the config from trace file size.

## 2. Separate source availability from attribution

Run both queries on each matching trace, without disabling raw ftrace parsing:

```sh
"$tp" -q tests/fixtures/omnibox_trace_health.sql "$trace"
"$tp" -q tests/fixtures/omnibox_trace_pilot.sql "$trace"
```

- Return the health rows and unchanged strict counts, with query status per trace. The health query counts process/thread rows and name/command-line availability, raw ftrace and print events, total and completed input/omnibox slices **without** the app process filter, and actual Surface/Display rows. `browser_layer_label_surface_rows` only matches a layer label, not an attributed app frame. Parsed table counts show what survived parsing, not why something is absent or whether a producer ran throughout.
- Preserve numeric stats by name and index (buffer or CPU as appropriate), including zero rows. If an expected counter/index is not returned, report **unavailable**, not zero. Some single-valued stats default to zero in the parser even without a source packet. Include buffer size/bytes written, bytes/chunks overwritten, chunks discarded, wraps, ftrace read/overrun/drop stats, setup errors and parser-side exclusions. Several such stats are [informational or notices](https://github.com/google/perfetto/blob/v59.1/src/trace_processor/storage/stats.h), not `data_loss`. Wrap count or file size alone is not the overwritten-byte count. Positive overwrite, discard or drop counts show incomplete collection, not which specific event was lost.
- Check retained capture stderr (and any local import notices) for relevant setup failures. Return only a sanitized error category, known source/event if relevant, and count; say if the logs are missing. Do not return raw stderr, PIDs, timestamps, event or layer text, browsing content, device identifiers, or any raw trace/config. No online trace viewer.

A match in the unfiltered counts does not pass the strict join. If dispatch/edit/worker/FrameTimeline readiness is still inconclusive, state the **first missing evidence** and any overwrite, discard or setup evidence separately. Do not attribute it to an Android restriction or suggest a settings change from zero rows alone. A recapture is a separate decision after this evidence is reviewed and requires authorization.

## 3. Reconcile the existing matched-build artifacts

Report the original helper's restored-count summary if retained (redact local paths) and whether it exited successfully. From the common root of the **existing** step trees, recheck the manifest from the supplied extraction (set `src` to its absolute path and `baselines` to the existing output root):

```sh
(cd "$baselines" && shasum -a 256 -c "$src/tests/fixtures/perf_baselines/restored.sha256")
```

Return match/missing/mismatch counts by stage. The manifest contains **29 restored paths: step17 2, step18 15, step19 12**. These are restored-file hashes across all three stages, not a count of everything compiled in each tree. If the original step17/step18 debug APKs still exist, recheck their sizes and SHA-256 against report 8. A check of current files is not new build provenance and no build or install is requested.

Return only `out/android-test-report-9.md` with the pinned handoff ZIP hash, verified/missing artifact identities, tool/query status, sanitized config comparison, health and strict counts, relevant loss/setup evidence or lack thereof, and the helper/pin/APK reconciliation. Label each component **Pass / Fail / Blocked / Not run**; an unmeasured signal is not a pass. No new physical run, timings, APK swaps, seeding, repeats of reports 4–8, release or Git operations. PERF-07 still needs trace readiness and a separately authorized paired device comparison.
