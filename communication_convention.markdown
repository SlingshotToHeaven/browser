# Communication convention

Read this and the [ultimate testing checklist](ultimate_testing_checklist_and_source_of_truth.markdown) before each testing handoff. This file defines **how we work and communicate**; the checklist owns the active topic, case status and evidence.

## Stay on the test run

- Start at the last verified checkpoint in the active run. For the **DL-01–DL-06 Test Run**, keep the prior phone, host and emulator results at their recorded scopes. Run the next unresolved eligible check, not the full suite again and not another topic.
- The destination is to finish the active run as quickly as the evidence permits. Group independent remaining checks when they can share one setup; give each a result even if another fails. If the required setup is unavailable or a bounded attempt fails, record the exact block or failure and continue with independent eligible work. Do not turn a block into another setup or confirmation loop.
- **No USB ADB or tests that need the owner to interact physically with a phone.** For this run use a disposable local/hosted emulator or host fixtures only. Leave physical profiles and APKs alone. A stopped phone-only check is not an emulator pass, and missing evidence is not permission to improvise a replacement. Do not ask the owner to plug in, unlock, tap, charge, reset, or reinstall anything on the phone.
- At each return, verify the named evidence, update only the affected checklist rows, and record exactly one next checkpoint. Keep `PASS`, `FAIL`, `BLOCKED`, `NOT RUN` and `STOPPED BY SCOPE` separate. Do not advance to the next topic or imply acceptance/release while required evidence is unresolved.

## Every test handoff: ZIP + prompt

1. **For the owner:** Attach a verified, commit-pinned source ZIP and give its direct download link. The owner relays the ZIP and prompt to the intended testing chat and returns the result. Do not give the owner the tester's shell or device work.
2. **Arena → testing agent · copy/paste:** One self-contained block: active run and next case IDs, ZIP/commit/tree/SHA-256, exact runbook, permitted environment and stop conditions, expected evidence and named return artifact. The tester must not choose an unrelated project or scope. Reuse the exact pin if source hasn't changed. Never include APKs, private files, keys or logs in the ZIP.
3. **Testing agent → Arena (via owner):** One compact report with source and tested APK hashes, environment, per-case result and evidence, exact blockers and cleanup status. No raw logs, device serials, secrets or personal data. Enough evidence to record the result without another status-only round.

When the owner asks what is next, give the handoff, not a list for the owner to execute. Avoid status chatter and repeated preflights that do not advance the active run.
