# Communication convention

Read this and the [ultimate testing checklist](ultimate_testing_checklist_and_source_of_truth.markdown) before each testing handoff. This file defines **how we work and communicate**; the checklist owns the active topic, case status and evidence.

## Stay on the test run

- Start at the last verified checkpoint in the active run. Keep prior phone and host results at their recorded scopes. Run the next unresolved eligible check, not the full suite again or an unrelated topic.
- Group independent checks that can share one setup and record a separate result for each. If a bounded attempt fails or a prerequisite is missing, record exactly what happened and continue with eligible work. Do not turn a block into another setup or confirmation loop.
- **Use the already-connected lab phone over wireless ADB only.** Use its existing connection, target it explicitly, and drive the eligible test UI remotely. Do not switch environments, reconnect the device, or ask the owner to touch the phone. Keep the installed APKs and retained profile; no installs or resets. Report missing prerequisites instead of improvising a workaround.
- At each return, verify the named evidence, update the affected checklist rows, and record exactly one next checkpoint. Keep `PASS`, `FAIL`, `BLOCKED`, `NOT RUN` and `STOPPED BY SCOPE` separate.
- **Queue order is not acceptance.** Normally finish the active topic before moving on. If the owner explicitly moves specific blocked checks to the end, record them in [tests to do at last](test_moved_to_be_done_at_last.markdown) with their prerequisites, then proceed with the next topic's eligible checks. Keep the moved gates open; do not imply acceptance, release, or permission to change the lab.

## Every test handoff: ZIP + prompt

1. **For the owner:** Attach a verified, commit-pinned source ZIP and give its direct download link. The owner relays the ZIP and prompt to the intended testing chat and returns the result. Do not give the owner the tester's shell or device work.
2. **Arena → testing agent · copy/paste:** One self-contained block: active run and next case IDs, ZIP/commit/tree/SHA-256, exact runbook, permitted environment and stop conditions, expected evidence and named return artifact. The tester must not choose an unrelated project or scope. Reuse the exact pin if source hasn't changed. Never include APKs, private files, keys or logs in the ZIP.
3. **Testing agent → Arena (via owner):** One compact report with source and tested APK hashes, environment, per-case result and evidence, exact blockers and cleanup status. No raw logs, device serials, secrets or personal data. Enough evidence to record the result without another status-only round.

When the owner asks what is next, give the handoff, not a list for the owner to execute. Avoid status chatter and repeated preflights that do not advance the active run.
