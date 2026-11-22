# Browser, privacy and rules — held navigation and Cancel

The first wireless UI return passed same-address history, redirect/reload, ordinary/child bridge absence and all four private-entry refusals. **Do not repeat those cases.** This follow-up is only WV-01 background commit and pending-load recovery, plus PR-01 cancellation (not clearing). Downloads stays out of this pass.

## One guarded session

- Extract the verified source ZIP. Reuse only the Bash `d` helper and [target guard in Section 1](android-download-wireless-resume.md#1-select-the-retained-target-without-changing-it) for the S21 FE over its existing wireless ADB connection. Drive the production UI in new regular lab tabs. Keep both installed APKs and all retained data/settings. No new connection, instrumentation, resets, privileged access, or owner interaction. If the verified synthetic lab is unavailable, stop rather than trying another target.
- Start these existing synthetic fixtures together from the extracted root. The two recovery listeners have **independent** gates/counters; Cancel's storage fixture is separate. A busy host port blocks only its dependent case.

```bash
PYTHONPATH=tests/fixtures python3 -B - <<'PY'
from contextlib import ExitStack
from http.server import ThreadingHTTPServer
from threading import Event, Thread
from browser_recovery import serve
from webview_storage import Handler

with ExitStack() as stack:
    ports = []
    for port in (8026, 8027):
        try:
            stack.enter_context(serve(port))
        except OSError as exc:
            print(f'BLOCKED: recovery fixture {port}: {exc}', flush=True)
        else:
            ports.append(port)
    try:
        storage = stack.enter_context(ThreadingHTTPServer(('127.0.0.1', 8013), Handler))
    except OSError as exc:
        print(f'BLOCKED: storage fixture 8013: {exc}', flush=True)
    else:
        thread = Thread(target=storage.serve_forever, daemon=True)
        thread.start()
        stack.callback(thread.join, 3)
        stack.callback(storage.shutdown)
        ports.append(8013)
    if not ports:
        raise SystemExit('No fixture port available')
    print('Loopback fixtures ready:', ports, flush=True)
    try:
        Event().wait()
    except KeyboardInterrupt:
        pass
PY
```

- Check `d reverse --list`; for each required, free target port `PORT` that your fixture owns, add `d reverse --no-rebind tcp:PORT tcp:PORT`. No rebind. Recovery evidence: `curl -fsS 'http://127.0.0.1:8026/control/counts'` and the same endpoint on 8027. Only **8026** is released by the tester. Do not send host requests to test pages. Use screen bounds, fixed loopback URLs and redacted fixture counts only. A dependent failure does not stop other safe cases. Do not add workarounds or repeat attempts in a loop.

## 1. WV-01 background commit — port 8026

1. Create lab tab A at `http://127.0.0.1:8026/session/one` (**Committed page one**) and tab B at `/session/two` (**Committed page two**). Record A/B positions and verified navigation controls before starting the hold. Start `/session/held` in **selected B**, keeping the same tab. Require a fresh `a GET /session/held` count. Do not use Open Link in Background; it queues a dormant tab rather than starting its WebView.
2. Within the fixture's 35-second pre-header hold, switch to A. Focus `edit_url` and enter a small synthetic draft; **do not submit it**. Verify A and the focused draft are in front before releasing B with `curl -fsS 'http://127.0.0.1:8026/control/release'` (expect `released`). If you missed the hold window, mark the assertion inconclusive; do not call a later foreground load a background pass.
3. Before choosing B, observe whether A's draft and focus stayed intact after release, and whether foreground `page_progress` stayed hidden and `btn_site_security` remained enabled. Then check the grid: A still selected, B's `tab_card_url` is `/session/held` and B's title shows **Held navigation; was not committed at the cut** (the fixture's fixed response title). Capture this without selecting B; a later render by itself is not proof of background commit. Then select B to confirm the marker without a second held GET. Report UI/wire observations separately, not an internal lifecycle trace.

## 2. PR-01 native Cancel — port 8013, never confirm a clear

1. In two new regular lab tabs open `http://127.0.0.1:8013/` and `http://localhost:8013/`. Use **Write dummy markers**, then **Inspect markers** once ready. Record baseline presence of each fixture marker: dummy cookie, localStorage, IndexedDB, Cache Storage, and active worker. Use **Request cacheable resource** twice per origin, sequentially; require a fresh `/cacheable` GET then a hit with no second GET. Record a missing baseline marker or a cache probe without a fresh control as unproved rather than clearing or changing settings to force it. Report presence only, not raw cookie values or browser data.
2. Via Site Info (`btn_site_security`, `site_info_btn_clear_browser`) open **Clear browsing data — all sites**. Select the four choices in this temporary dialog, tap **Review**, verify **Confirm all-sites cleanup**, then tap only **Cancel** (`android:id/button2`, visible label Cancel). **Never tap Clear selected data.** If the intended controls are ambiguous, dismiss without confirming and record the blocker.
3. Verify the fixture tab and tab count are unchanged and no cleanup result appears. Reinspect both origins (do not reseed); report each marker and cache-hit result against the baseline. If the guarded restart in case 3 occurs, recheck the same two tabs after all WV-01 recovery observations are recorded. If it does not occur, report the live Cancel observations and restart **not run**; do not add a second stop. No claims about what an actual clear deletes or about unrelated app records not measured here.

## 3. WV-01 held navigation across a real stop — port 8027

1. Only if the retained Browser is an idle synthetic lab with no active transfer or unsaved work. In a new lab tab load `http://127.0.0.1:8027/session/login` → `/session/one`; require a `signed` count on that route. In that same tab go to `/session/two`, then use menu Back to the visible **Committed page one**; confirm menu Forward is enabled without invoking it, then dismiss the menu. Record the tab count. After the page is settled, stay selected at least two seconds, background via Android Home for at least five seconds (the app checkpoints and requests cookie flush on stop), and return normally to the same process/tab, without a URL override. Verify the baseline again. The waits are not proof of a flush; the fresh signed request after restart is.
2. Pre-stage the cut commands and start `/session/held` in that same selected tab. Require one fresh `a GET /session/held` on 8027, verify that port was not released, and cut the Browser **within 30 seconds of initiating the held navigation**, before the fixture's 35-second header timeout. Do not send release on 8027. Record old PID, elapsed time, and termination. If the idle guard, selected baseline, wire hold or time bound is missing, do not cut; report the exact block instead.

```bash
d shell am force-stop --user 0 com.browser
# Verify the old PID exited before the normal launch below.
d shell am start -W --user 0 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \
  -n com.browser/com.privacy.browser.MainActivity
```

3. Record a new PID. Before any manual reload or address submission, require the same tab count and selected **Committed page one** with its matching URL. Use browser Forward to **Committed page two**, then Back to one. Reload one; require a **fresh** `a GET /session/one signed` increment and the correct rendered page. Check the held URL was never requested again. A restore after navigating manually is not a pass. This is UI/wire evidence for pending vs committed recovery; it does not compare native snapshot bytes.

## Return and stop

Return `browser-wireless-recovery.md` with commit/tree/ZIP, retained APK and environment identities, separate results/evidence for cases 1–3, any unproved sub-assertions, and cleanup status. Close only clearly new test tabs, remove only still-matching reverses you added, and stop your fixtures. Report baseline/final tab count; do not read or export saved-state files, databases, raw screenshots/hierarchies or user data. No renderer termination, NoScript or rules change, actual data clear or production catalog. Do not start another topic.
