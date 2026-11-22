# Browser, privacy and rules — retire old page controls

This pass covers **WV-01 stale media/element picker UI** and the **WV-02 legacy Home address**. Do not repeat prior navigation, recovery, private-entry or Cancel checks. These are scoped production-UI checks, not detached native callback invocation, restored legacy state, renderer termination, or a release test.

## One guarded handset session

- Verify the pinned ZIP. Reuse only the Bash `d` helper and [Section 1 target guard](android-download-wireless-resume.md#1-select-the-retained-target-without-changing-it) for the existing wireless ADB connection, retained S21 FE and installed APKs/profile. No new connection, APK, reset, process stop, settings change or owner action. Work in new regular lab tabs and observe only the synthetic UI; **do not read saved-tab files, preferences, databases or unrelated app data**. Return redacted assertions, not raw captures or dumps.
- From the extracted root, run `python3 -B tests/fixtures/browser_wireless_retirement.py` in a host terminal. It serves only loopback on 8028. Check `d reverse --list` first and add only `d reverse --no-rebind tcp:8028 tcp:8028` on the free target port. No rebind. Host counts: `curl -fsS 'http://127.0.0.1:8028/control/counts'`. Do not request test pages from the host. Stop the fixture-dependent checks if the mapping or route is unavailable.
- Record the synthetic Downloads **All** row count if it can safely be observed through the native UI (Select All for the count only; exit selection without any actions). There should be no new row or media-body GET. If the UI count cannot be verified without unrelated access, report it as unproved. Use on-screen controls; do not inspect storage or change ad-blocking or script policy to force a positive control.

## 1. WV-01 — a real media confirmation, then a new document

1. Open `http://127.0.0.1:8028/media-old` in one new lab tab. Require the heading **Old media document**, one fresh page GET, and the floating download control `fab_video_download`. Do not play the video. Tap the control, require a fresh `HEAD /clip/old.mp4`, then choose the media options panel's `dialog_btn_download` to **open the confirmation only**. Require the actual `prompt_text_url` to name `/clip/old.mp4` and the real `prompt_btn_download` to be visible. **Never tap `prompt_btn_download`.** If the configured app cannot present this positive control, record the blocker and leave it unchanged.
2. While the old confirmation is visible, send `curl -fsS -X POST 'http://127.0.0.1:8028/control/advance/media'` from the host; expect `released`. The fixture page itself then navigates to `/media-new`. Do not dismiss the prompt, send a new Activity intent, switch tabs, or background the app to simulate dismissal. Within the approximately two-minute original page wait window, require a fresh GET and **New media document** in the same selected tab, with the old confirmation gone and no new download. If the page never replaces, the retirement assertion was not exercised.
3. On the new document, open a fresh media confirmation the same way. Require a new `HEAD /clip/new.mp4` and the exact new prompt URL, not the old offer. Cancel it via `prompt_btn_cancel`. Record old/new metadata counts and no `GET /clip/{old,new}.mp4`. This checks visible retirement and a working fresh offer, not an injected click on a detached callback.

## 2. WV-01 — an actual element picker, then a new document

1. In another new lab tab open `http://127.0.0.1:8028/picker-old` and require **Old picker document**. Open Shield (`btn_shield`) and the pick-element control (`btn_tool_picker`). Tap the visible **Select this synthetic element** target. Require the actual **Element Picker** panel before proceeding. Do not create, preview, save, or zap anything.
2. While the panel is visible, send `curl -fsS -X POST 'http://127.0.0.1:8028/control/advance/picker'`. Require `released` and the fresh `/picker-new` GET and **New picker document** in the same selected tab, with the old picker panel gone. Do not close the panel yourself or leave the Activity. Tap **Test ordinary tap** and require **New document tap accepted** with no picker reopening or consuming it. A missing Old picker positive control or no replacement is not a retirement pass.

## 3. WV-02 — legacy address and a subsequent ordinary page

- In a separate new regular lab tab, enter only the built-in legacy alias `file:///android_asset/home.html` in the address bar. Require the real bundled Home UI (including the **Private tabs** action, no need to tap it) and the tab card's canonical Home URL `home.privacy-browser.invalid/`; do not inspect bookmarks. This is an explicit address conversion, not a restored saved tab or a different WebView provider.
- In that same tab go to `http://127.0.0.1:8028/after-home`. Require the GET, **Ordinary page after Home**, and rendered `Legacy=undefined, Home=undefined`. Record separately if the second assertion cannot be observed. Do not call any privileged bridge methods. This checks the new document following Home, not a detached old message.

## Return and stop

Run each independent safe case once. Mark missing prerequisites, positive controls or visible assertions separately. Recheck the Downloads row count where safely established, without touching previous downloads. Close only clearly new test tabs, remove your still-matching reverse, and stop your fixture. Return `browser-wireless-retirement.md` with source and retained APK identities, environment, per-case UI/wire outcomes and cleanup. No actual clears, NoScript/rules changes, live catalogs, media playback, or next topic.
