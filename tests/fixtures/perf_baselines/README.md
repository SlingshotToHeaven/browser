# Matched performance build inputs

These reverse source patches are snapshots of changes isolated for device measurements, not production code to apply to the candidate. Use `python3 -B tools/prepare_perf_baselines.py /path/to/new/baselines` in the testing-branch ZIP extraction or checkout. A ZIP extraction needs no Git initialization. The helper copies only build inputs, applies the three layers in order to disposable trees and verifies the hashes of restored files. The instrumentation and fixture scripts stay in the candidate. All four builds share the same app ID (`com.browser`), toolchain settings, and unrelated remediation.

| Restore | Saved source input | SHA-256 of original local overlay |
| --- | --- | --- |
| Candidate → Step 19 | `step19-download-sources.tar` (15 members, including one unused test file) | `814898dcbf7bc2ec21d5cecca7f0cc7a8917b23a1a92a4b661d03bec59f7481b` |
| Step 19 → Step 18 | `step18-baseline-source-overlay.tar` (15 members) | `053df77b708cf3623c493b9572642f3c350d0cb9fe1321a37ad8eb9ab606bd91` |
| Step 18 → Step 17 | `step17-omnibox-sources.tar` (2 members) | `2ce043dc908371deb443ab3ea0fdbc8285b6f7f3a74453d6f4483da89522fcc5` |

The reverse patches also remove only the new main-source files added at each step. `restored.sha256` pins the changed files after restoration (paths are relative to the output directory, with the step prefix). No tarballs, built APKs, signing inputs, real browsing data, or device captures are needed for these builds. See the [Android handoff](../../../docs/device-handoff.md) for how to use them without touching the original installed app.
