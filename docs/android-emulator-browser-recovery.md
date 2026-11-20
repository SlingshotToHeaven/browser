# Android emulator: browser recovery and privacy

The [browser recovery workflow](../.github/workflows/android-browser-recovery.yml) runs one batch on a fresh GitHub-hosted Android 14 / API 34 x86_64 emulator. It builds the normal debug app and its separate instrumentation APK from a pinned source archive and checks the installed APK hashes. This suite does not rerun the download matrix or touch a phone, existing browsing profile, release artifact, or performance setup.

The [host runner](../tools/check_android_browser_recovery.py) uses a fresh browser profile **between independent cases only**, so one failure does not stop later cases. The two [fixture origins](../tests/fixtures/browser_recovery.py) use `127.0.0.1` and `localhost` over one loopback-only reverse. The server logs route counts and whether the synthetic login cookie matched, not header values. The JSON report includes named outcomes, the WebView version when available, and scoped test observations. Checks annotations carry numbered `BROWSER_RECOVERY_PROOF` chunks and the combined SHA-256; the JSON-only Actions artifact is retained for one day.

## What the batch checks

- **Session:** new native same-address history entries, actual background completion while editing the foreground address, and a separate real force-stop. The latter holds the next main-document HTTP response before sending headers, verifies a published checkpoint for the last committed Back entry, cuts the browser process, and checks the unchanged index and native state file before relaunch. The restored tab must use the same identity, support real Back and Forward, get a fresh signed HTTP response, and not replay the held URL.
- **Renderer and Home:** the bundled Home bookmark endpoint, and actual WebView renderer termination. The latter retires the old view and a genuine native media confirmation. The test opens a fresh confirmation on the restored tab, invokes the detached one and requires that the fresh confirmation is unaffected, with no download row or body request. It checks the sibling tab too. This is not a claim about every specialized WebView or callback.
- **Privacy:** cold/warm private intents and the tab-picker entry, and real two-screen native confirmation via Site Info. Each case seeds cookies, storage markers, HTTP cache and app history on two origins. Cancel and each of the four choices independently cold-start the same profile without another `pm clear`. The fixture counts a real fetch and cache hit before each choice and independently checks the cached response after restart. The legacy Web Storage test reports marker observations rather than assuming `deleteAllData()` guarantees erasure. A completed local synthetic file and its download row, a bookmark, and a browser zoom setting act as unrelated-data sentinels.

## Verified result

[Run 44720020278, job 136233078443](https://github.com/SlingshotToHeaven/browser/actions/runs/44720020278/job/136233078443) tested source `6ccff96cfc32185dfd54b2b9249358b80e93426a` from 10:31:58 to 10:37:23 UTC (GitHub's 2026-11-20 timestamps). The emulator used Android 14 / API 34, x86_64, 4 KiB pages, and Google WebView `113.0.5672.136` (`com.google.android.webview`). **10 passed, 1 failed, 0 blocked, 0 not run.** The failure was missing a terminal instrumentation result in an additional private-entry repeat; the requested session, renderer and all five clearing checks passed.

| Case | Result | Observation |
| --- | --- | --- |
| `session-pending-force-stop` | Pass | One held main-document request, with no response headers sent at the cut. The old browser PID exited; the published session index and referenced native state bytes were unchanged. An ordinary launch in a different process restored the same tab at the previous Back entry. Back/Forward worked, a fresh HTTP request was signed in, and the held URL was never requested again. |
| `session-native-same-url` | Pass | Independently exercised real same-address history entries, the Back index, and native capture/restore. This is not the process-death test. |
| `session-background` | Pass | A second tab completed its held navigation while the foreground address was being edited. The selected tab, draft text, progress and security control still belonged to the foreground tab. |
| `renderer-restore` | Pass | Terminated the actual WebView renderer. Reload restored the active tab in the same Activity. A new media confirmation worked; invoking the detached pre-kill confirmation did not change the new one, add a download row or request the media body. The sibling tab also lost its renderer and restored successfully. |
| `home-bookmarks` | Pass | The bundled Home read the test bookmark through its scoped endpoint on this provider. |
| `private-entry` | Failed to finish | Instrumentation started but did not return its terminal result. No fixture target request was observed. The [initial run](https://github.com/SlingshotToHeaven/browser/actions/runs/44719097448/job/136229905085) passed this check (cold/warm intents and tab picker), but the repeat is inconclusive, not a new pass or a reproduced product defect. |
| Five cleanup choices | 5 passed | See the post-restart results below. |

### Privacy clearing after restart

Each row is an independent profile with both origins populated. All five preserved the completed download row **and file bytes**, the bookmark, and the browser zoom setting. Both origins had a cache hit without a second HTTP request before confirmation. The after-restart probes fetched the original resource again over HTTP only in the URL-cache-only case (one GET on each origin, then a cache hit). In the history/tabs case, the new process had already restored only Home with empty history, no original tab files and no requests to either former page **before** the test revisited the origins to probe retained site data.

| Choice | Dummy login cookies | HTTP-cache entry | localStorage marker | IndexedDB marker | Cache Storage marker | Service worker registration | Original history and tabs |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Cancel with all four selected | Kept | Hit | Kept | Kept | Kept | Kept | Kept |
| Cookies only | Absent on the wire and in the provider | Hit | Kept | Kept | Kept | Kept | Kept |
| URL cache only | Kept | Miss, then hit | Kept | Kept | Kept | Kept | Kept |
| Legacy Web Storage only | Kept | Hit | Absent | Absent | Kept | Kept | Kept |
| History and saved tabs only | Kept | Hit | Kept | Kept | Kept | Kept | Removed; only Home restored |

The app accurately said **clear requested** for URL cache and **deletion requested; no completion callback** for legacy Web Storage. Those messages are not proof of deletion. The table reports only the specific seeded response, markers, registrations and app records observed on this provider. In particular, legacy deletion **did not remove** the Cache Storage markers or unregister the workers.

### Evidence

- Installed debug APK SHA-256: `cd4341ce0c755823a73c9a3c54d7aa7b7ee91dcd5cf22664bcefcdbba4aa6b6c`.
- Installed instrumentation APK SHA-256: `8ea7ef0e616f3518e0a54d55d05c8537dcfe916d80929e80a9352e17ad9efe8f`.
- `browser-recovery.json`: 7,781 bytes; SHA-256 `8d36380b0fa5bfb42ef9803dd908fbb7ad38ae4d9223007e1f7f51efac533405`. Reconstructed and verified from five [Checks annotations](https://api.github.com/repos/SlingshotToHeaven/browser/check-runs/136233078443/annotations?per_page=100) for this job. The Checks and Actions records tie the JSON to this job and source; the JSON records both installed APK hashes. It was also uploaded as artifact `14620649563` in the run (one-day retention).
- The initial run on `631cc1f3b728813e48d6a929b089242327138939` passed 11/11, with restart checks for cookies and history/tabs only. The later run is the evidence for **all five** choices after restart and for the fresh native confirmation after renderer loss.
- Source-archive checks: 99 Python tests, 27 Node tests, 23 pinned input checksums and shell syntax passed. These do not stand in for the emulator results.

## Scope and remaining gaps

Use these results alongside [WV-01, WV-02 and PR-01](../DEVICE_TEST_CHECKLIST.md#webview-privacy-and-filters-steps-0710), not as full sign-off. This suite did not test Home's private-entry action; that passed in the [separate API 34 functional follow-up](android-emulator-functional-followup.md#browser-checks). Other WebView versions, fallback providers, every retired callback, and provider profile isolation/deletion were not covered. No secure-erasure claim is made. No download batch, physical device, performance trace or signed-release check was run.
