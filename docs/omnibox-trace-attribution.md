# Retained-trace attribution check (report 11)

This procedure is complete; see the [report-11 findings and hold](android-test-followup.md#report-11-offline-attribution-complete-perf-07-blocked). Do not rerun this probe by default.

**Mac only.** The owner's one-capture approval was used in the [report-10 device addendum](android-test-followup.md#report-10-split-buffer-retry-dispatch-link-unresolved). Do not connect to a device, use ADB, collect another trace, rebuild or install APKs, or change anything on the phone. Do not rerun the report-8/9 or report-10 preflights. The existing trace is the only input.

## Verify and query the retained trace

From a fresh extraction of the supplied source ZIP, use the approved local Trace Processor (`tp` is its path). Set `trace` to the retained **report-10 retry** `hit.pftrace`, not the report-8 file of the same name. Check `wc -c < "$trace"` and `shasum -a 256 "$trace"`: expected **3,946,596 bytes**, SHA-256 `46a757b3cea07731d95a2c8592d850085be6246373d1859ffe28238c5281806b`. If it is missing or mismatched, report **Blocked** without substituting or regenerating it.

```sh
"$tp" --version
"$tp" -q tests/fixtures/omnibox_trace_attribution.sql "$trace"
```

The [attribution probe](../tests/fixtures/omnibox_trace_attribution.sql) reports only aggregate counts and offsets relative to one app-main edit. It is **not** a replacement for the unchanged [strict readiness query](../tests/fixtures/omnibox_trace_pilot.sql). Carry forward `dispatched_edits = 0` and `post_edit_surface_display_pairs = 0` from the device addendum, not a new pass/fail based on this probe.

## Return the three independent checks

| Output | Report | Do not infer |
| --- | --- | --- |
| `shape`, `dispatch` | Confirm the single edit anchor and show **both** main dispatches in order (ordinal, start/end offset from edit start). Record whether a completed same-app ATrace async window has the same event ID as each sync dispatch, and separately whether it spans the edit. If no unique main edit is available, there are no relative dispatch rows. | A time gap, or an edit within an async window, is not proof of a callback or of which event caused the edit. The ID check only joins two *framework* markers; the edit label carries no event ID. |
| `flow`, `flow_roles` | Report directed Trace Processor edges, if present, from those sync or ID-matched async slices to the exact edit or an ancestor (one edge, no time-based matching). The probe returns endpoint roles, not raw names or IDs. If none, say **no direct link in this probe** and whether the trace had any flow edges at all. | An unrelated flow or one into a broad parent is not automatically the stimulus link. A zero count does not exclude every indirect path or prove a platform restriction. |
| `frame` | Report actual app surfaces and matched SurfaceFlinger tokens completing after the edit, and how many of those pairs complete after a later main `localRows` slice. These counts do not depend on synchronous nesting. | A later completed pair does not prove an input-caused frame, or which frame showed the current text, fallback, or history row. These are not latency or presentation measurements. |

The matching framework marker formats come from [Android's `ViewRootImpl`](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/core/java/android/view/ViewRootImpl.java) and [Perfetto's ATrace importer](https://github.com/google/perfetto/blob/v59.1/src/trace_processor/importers/systrace/systrace_parser.cc). On the browser's text-change path, [`MainActivity`](../app/src/main/java/com/privacy/browser/MainActivity.java) calls the marked method directly from `onTextChanged`. The same method can also run for focus/click changes. The report's temporal ordering alone does not establish a posted browser callback or a specific IME path.

Return only `out/android-test-report-11.md` with the source ZIP and trace identities, tool/query status, the sanitized result rows, and whether a trace-recorded dispatch-to-edit link is available for review. If a table, marker or link is unavailable, identify it rather than selecting the nearest dispatch, changing the readiness check, or taking another capture. Keep raw traces, names/args, absolute timestamps, IDs, logs, and any browsing data on the Mac. **PERF-07 remains open**: even a validated input-to-edit link is not a dispatch-to-presented-content measurement or a paired result.
