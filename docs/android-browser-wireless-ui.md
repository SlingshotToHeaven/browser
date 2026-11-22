# Browser, privacy and rules — wireless UI first pass

**Returned and reconciled; do not repeat.** The [held-navigation and Cancel handoff](android-browser-wireless-recovery.md) covers separate checks.

**Run only the scoped UI checks below:** WV-01 navigation, WV-02 ordinary-page/frame bridge exposure, and PR-01 private-entry refusal. The blocked Downloads cases were [moved to the end](../test_moved_to_be_done_at_last.markdown); leave their results, files and settings alone. This is not a full Browser gate pass or release test.

## One setup on the retained handset

1. Verify the pinned ZIP from the relay. Reuse only the Bash `d` helper and identity guard in [Section 1 of the prior wireless guide](android-download-wireless-resume.md#1-select-the-retained-target-without-changing-it): the already-connected S21 FE `SM-G990E`, user 0, API 36, arm64-v8a, 4 KiB, WebView `137.0.7151.115`, original app/test hashes and marker. Do not rerun the prior guide's Downloads checks. No new APKs, profile reset, reconnect, or owner action. If the target or synthetic lab profile cannot be confirmed, stop without acting on the device.
2. From the extracted source root, in a separate host terminal, run:

   ```bash
   python3 -u -B tests/fixtures/tab_trust.py
   ```

   Both listeners are host loopback only. If they cannot start, do not take over occupied ports. Check `d reverse --list`; on free target ports add only `d reverse --no-rebind tcp:8011 tcp:8011` and `d reverse --no-rebind tcp:8012 tcp:8012`. No rebind. Mark dependent cases blocked if a listener or reverse is unavailable; the native Home and tab-grid checks do not require the fixture. Confirm a real `/session` GET from the new lab tab before relying on network evidence. While testing, do not send host requests to test routes; attribute fixture request lines to the handset by path and by action. Return only counts/statuses for the synthetic routes, not raw logs or captures.
3. Drive visible controls remotely with current screen bounds. Create new regular lab tabs for this run only. Do not submit anything other than the named loopback fixture URLs, change rules/settings, visit external services, or inspect unrelated tabs/files. If JavaScript is already off or a control is inaccessible, report the exact dependent blocker rather than changing policy. Bound each attempt to the time needed for normal UI loading (up to 30 seconds for a page or control); no repeated setup loops.

## WV-01 — same-address history, redirect, reload

- Open `http://127.0.0.1:8011/session` in a new lab tab. Tap **Push same-URL entry** twice; report state `step: 1`, then `step: 2`. Use the actual browser menu Back (`menu_nav_back`) and Forward (`menu_nav_forward`) to observe `2 → 1 → 2` at the same URL. The page's state text is the witness, not just the address string. Do not use system Back to close tabs.
- Navigate that tab to `http://127.0.0.1:8011/script-redirect`. Require the fixture's 302 followed by GET 200 at `http://localhost:8012/script-landing`, the landing content, and the matching final address in browser chrome. Use menu Back then Forward once to check the previous fixture and final landing. Reload the landing twice via menu Reload (`menu_nav_reload`) without waiting unnecessarily between actions; report the actual GETs that reached the server, the final rendered marker, and final address. A superseded load need not complete; the final committed UI must belong to the landing. If the other origin cannot be reached, retain the independent same-address result.

## WV-02 — ordinary page and cross-origin child

- In a new lab tab, open `http://127.0.0.1:8011/`. Record the actual rendered **Ordinary top frame: legacy=undefined, home=undefined** and **Cross-origin frame: legacy=undefined, home=undefined** text, plus GET 200 for `/` and for `/frame` on port 8012. A loading, blank or failed frame is not a pass. Do not call any Home endpoints, read bookmarks, or claim stale-document, fallback-provider or renderer-loss coverage. PR-01's real bundled Home action below is a separate positive control for the native route.

## PR-01 — private-entry refusal, no clearing

- In a new bundled Home tab, use the visible **Private tabs** action. Then independently use the tabs grid **Private tabs unavailable — details** (`tabs_btn_new_incognito`). For each, require the native **Private tabs unavailable** explanation, dismiss it, and verify no new tab or navigation. Compare the tab count and selected synthetic UI before/after separately. Do not enable private mode.
- With the lab Browser already running and a synthetic tab selected, use the supported private intent with the fixed negative-control URL below. Require the native refusal, unchanged tab count and foreground page, and **zero GET/HEAD** to the exact `?case=private-warm` route. A successful Activity launch is not proof of refusal. Record a normal `/session` GET as the fixture-reachability control.

   ```bash
   d shell am start -W --user 0 -n com.browser/com.privacy.browser.MainActivity \
     --ez com.privacy.browser.EXTRA_OPEN_IN_INCOGNITO true \
     --es com.privacy.browser.EXTRA_LOAD_URL 'http://127.0.0.1:8011/session?case=private-warm'
   ```

- **Cold entry, only if safe:** If you can confirm the retained Browser is exclusively the idle synthetic lab (no active transfer or unsaved work), record the current selected lab page, tab count and browser PID. Stop only `com.browser` once with `d shell am force-stop --user 0 com.browser` and confirm its PID is gone. Reissue the private intent with `?case=private-cold`; require a new PID, native refusal, no extra tab or target navigation and zero GET/HEAD to that exact URL. If the idle lab or stable restart baseline cannot be established, report the cold subcase blocked and leave the app running. This is not a data clear or a pending-load recovery test.

## Return and stop

Return `browser-wireless-ui.md` with commit/tree/ZIP SHA-256, installed APK hashes, device/API/ABI/page size/WebView, separate UI and redacted fixture evidence and results for every subcase, blockers and cleanup. Run independent reachable cases when safe. Close only unmistakably new test tabs if you can do so without touching retained tabs, remove only the still-matching reverse mappings you added, and stop only your fixture. No private data, raw hierarchy/logs, APKs, or device identifiers in the report.

**Not in this pass:** pending-load restart and stale picker/media callbacks, renderer termination and other provider configurations, NoScript (changing it reloads existing tabs and drops their back history), real video playback, data clearing, and RULE-01's disposable catalog. Do not change the retained state or hit a production catalog to try those. Do not advance to the next topic.
