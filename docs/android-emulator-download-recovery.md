# Android emulator: interrupted download publication

This is one automated **DL-01 / Steps 03–04** case for app-private storage. It extends the earlier [emulator launch proof](https://github.com/SlingshotToHeaven/browser/actions/runs/44686756405) without repeating the physical transfer interruption or late collision cases. The [publication run's workflow](https://github.com/SlingshotToHeaven/browser/blob/ee3bb457445e2c1558871763824bde020d5c3157/.github/workflows/android-emulator-poc.yml) built the normal, unmodified debug APK from the exact run SHA, booted one fresh Android 14 / API 34 emulator on a GitHub-hosted KVM runner, and kept only a small JSON result for one day. It does not upload an APK, database, fixture file, or screenshot.

## What the check proves

1. Use **Downloads → Add download** with one connection and a loopback-only synthetic HTTP fixture. The sole change to the fresh app profile before launch is the default download directory and disabling category subfolders, so the fixture stays inside a dedicated app-private directory. The browser, queue controller, SQLite and file publisher all run normally.
2. A host-only JDI debugger waits at the first instruction of `DownloadTask.persistPublication`, called by `publishOutput`. At this point the verified file already exists at the final name. Require both entries to match the fixture bytes and record whether they share an inode (hard link) or have separate inodes (verified copy). Independently read the real SQLite row (`RUNNING`, full checkpoint, no completion identity) and use `am force-stop` **without resuming the held process**. Require that row to be unchanged before relaunch.
3. Relaunch the browser and open Downloads. Require the **FAILED** badge and an occupied-destination error in SQLite, not a completed row, even though the uncommitted file already has the correct bytes. Both paths must still exist unchanged and the fixture must see no new request.
4. Move the interrupted final entry aside in the disposable folder. Put an unrelated sentinel at the reserved name and choose **Start over**. Check that the name still blocks the attempt, the sentinel and old files are preserved, and no request is made.
5. Move only the sentinel aside, then choose **Retry**. Require a single fresh HTTP 200 from byte zero, a **COMPLETED** badge and a committed SQLite identity for the new file, independent byte-count and SHA-256 checks, and preservation of the previous files.

The debugger and fixture are not part of the app build. The tool checks that the only ADB device is the fresh emulator and does not support running on a handset. Missing breakpoint, file, SQLite, network or UI evidence is **not a pass**. The report names the phase reached and is also available as a bounded, hash-tagged `BROWSER_DOWNLOAD_RESULT` check annotation if artifact download is unavailable.

## Verified result

**Pass — one app-private verified copy, interrupted after the copy and before SQLite recorded completion.** [Run 44691573199](https://github.com/SlingshotToHeaven/browser/actions/runs/44691573199), job `136138372601`, source `ee3bb457445e2c1558871763824bde020d5c3157`, Android 14/API 34 x86_64. The normal debug APK SHA-256 was `61091251ee21248d276257b17e84ff722683e1e4fd37348dff811a8fda234d04`.

| Check | Observed |
| --- | --- |
| Exact cut | The real publisher had created a separate verified copy. Both staging and final file matched the **4,194,304-byte** fixture; the SQLite row was `RUNNING` with a full saved checkpoint and no completion identity. The app was force-stopped while the debugger held it, and the row was unchanged before launch. |
| Relaunch with uncommitted output | A new browser process showed **FAILED**, with an occupied-destination reason in SQLite. The copy and its staged file were left intact, even though both had the correct bytes. No new HTTP requests. |
| Same name occupied by an unrelated file | **Start over** was refused. The **36-byte** sentinel kept its inode and SHA-256 `bb85450dd07732056ef197e614a52c4dc4edfe7ebc65396007437baea1be4f7a`; both old download files were also preserved. No new HTTP requests. |
| Retry after moving the sentinel aside | One fresh unconditional HTTP 200 from byte zero. The same row and the Downloads badge became **COMPLETED** with a new file identity. The new **4,194,304-byte** file matched SHA-256 `3f594a89bfe61cdd0fdeb4592211e70737211891cfdcde17d52464d9648f66b2`; previous files were unchanged. |

The [one-day JSON artifact](https://github.com/SlingshotToHeaven/browser/actions/runs/44691573199/artifacts/14609246243) is an 851-byte archive. The identical 1,288-byte result record was retrieved from the job's check annotation and verified against SHA-256 `6307986ba1f47918eff91556cc5af145f6b6fcd52c705af4d760dcf21284b31f`.

## Scope still open

This run did not exercise hard-link publication, kill during an in-progress exclusive copy, shared/removable storage, or power loss. An installed database upgrade and actual Android Open/Share were checked in the [separate follow-up run](android-emulator-download-upgrade.md#verified-result). ARM64 playback, signed release qualification and hardware checks remain separate. PERF-07 is skipped; this test makes no timing claim.
