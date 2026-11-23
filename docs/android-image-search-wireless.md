# Assistant, images, comics and local data — IMG-01 wireless review and Cancel

**Returned and reconciled; do not repeat.** The native review/Cancel and both witnessed page-picker refusals passed at the scope in the [returned evidence](topic-3-agent-test-results.md#returned-image-review--acf5a6f).

This checkpoint covers only **IMG-01 native URL review and Cancel**, plus **in-page `data:` and `blob:` pickup refusal** in that same image-picker flow. Search by Image is already reachable from the Browser menu without turning on Agent or a download setting. There is no share, live-provider request, image upload, file chooser, stale-confirmation click, or full IMG-01 acceptance in this run.

## Keep the retained lab unchanged

- Verify the exact ZIP, extract to scratch, and reuse only the Bash `d` helper and [Section 1 target guard](android-download-wireless-resume.md#1-select-the-retained-target-without-changing-it) on the existing wireless ADB connection. Keep the S21 FE, user, WebView, app/test APKs, settings and retained data. Do not build, install, reconnect, run instrumentation, force-stop, clear, change network/permissions or ask the owner to touch the phone. Existing UI availability is a prerequisite, not permission to change a switch.
- Use one **new regular fixture tab** via the single explicit entry below. Drive the test itself through visible controls. No app-data inspection, clipboard reads/writes, logs, accessibility dump retention or unrelated UI capture. Do not use the hub's **Enter image URL** action or select a provider in the hub before a page image is selected: the URL-entry form can prefill the retained clipboard. Never tap **Open URL search** or **Open in background**, including as a stale-approval probe.
- From the extracted source root, run `python3 -B tests/fixtures/image_search_wireless.py` in a host terminal. Check `d reverse --list` and add only `d reverse --no-rebind tcp:8029 tcp:8029` if free. The server binds `127.0.0.1`; do not expose it or replace anyone else's reverse. The host can read `curl -fsS http://127.0.0.1:8029/control/counts` before and after the steps. Only the phone should load the fixture pages and trigger the witnesses during the run. No extra check-only relay.

## 1. `IMG-01/review-cancel` — one destination, then native Cancel

1. Note the regular-tab count if visible without inspecting other tabs. Open only the isolated URL in its own regular tab with the app's existing link entry, avoiding the omnibox and any clipboard suggestion:

   ```bash
   d shell am start -W --user 0 -n com.browser/com.privacy.browser.MainActivity \
     --es com.privacy.browser.EXTRA_LOAD_URL http://127.0.0.1:8029/review \
     --ez com.privacy.browser.EXTRA_OPEN_IN_NEW_TAB true
   ```

   Require **Image review fixture**, **Synthetic image ready**, and the host GETs for `/review` and `/image/Case%2FCard.png?Marker=MiXeD&v=01`. The displayed test image is a blue-and-amber rectangle. Establish the pre-review tab count here; expect one more than the opening baseline if both counts are available.
2. Use **Menu → Search by Image** (`menu_item_image_search`) **→ Choose page image** (`btn_select_image`). Require **Tap any image on the page to search** and tap the visible rectangle once through real remote input. Require **Search Selected Image** (`text_selected_image_url` for its URL), then tap the **Google Lens** row once to open the *review only*.
3. Require the actual **Share image URL with Google Lens?** dialog. Verify `Destination: lens.google.com` and the complete `http://127.0.0.1:8029/image/Case%2FCard.png?Marker=MiXeD&v=01`, including case, `%2F`, and query, plus the URL-only/no-file-or-screenshot disclosure. Tap native **Cancel** (`android:id/button2`). Verify the same selected `/review` page and unchanged tab count immediately after dismissing. Record the observed UI dispatch outcome and fixture counts; those counts do **not** measure network traffic to the provider. If any provider tab appears unexpectedly, stop the run and record the observed mismatch; do not interact with the provider page.

## 2. `IMG-01/local-picker-refusal` — `data:` and `blob:` targets

Run only after the successful HTTP image positive control; otherwise these cannot distinguish rejection from an inactive picker. In the same fixture tab use **Open inline fixture** for `/data`, then **Open blob fixture** for `/blob` after canceling the inline picker. Use only those fixture links, not the omnibox or additional intents. Require each matching heading (**Inline image fixture** / **Blob image fixture**), **Synthetic image ready**, and a fresh page GET. Each page has just one local image, no HTTP image to select by proximity.

On each page re-arm **Menu → Search by Image → Choose page image** and confirm the banner and target are both visible. While armed, tap the rectangle once. Require **Normal tap recorded while picker active**, the corresponding `POST /witness/data` or `POST /witness/blob` (HTTP 204), the picker banner still present, and no selected-image/provider review or new tab. The page sends its witness only for a trusted click on the loaded image while the picker banner exists. The witness is ordinary page-input evidence, not an internal sink or packet capture. Cancel the banner before leaving each page. Report each scheme separately. If the banner, target, witness or normal input is missing, do not count no-dialog alone as a pass. Do not inject events, send fixture witnesses from the host, or try file URLs/clipboard to simulate a refusal.

## Return and stop

Use one bounded attempt per assertion. Mark prerequisite failures `BLOCKED` and unresolved inputs `INCONCLUSIVE` instead of widening scope. Close only the fixture tab if identifiable, remove only the still-matching 8029 reverse created by this run, stop only its fixture, remove scratch UI captures, and report cleanup and any limitation. Do not audit or change other tabs/data to establish cleanup.

Return exactly one redacted `image-search-wireless-review.md`: ZIP/commit/tree/digest, both matched retained APK digests and device/API/ABI/page-size/WebView (no serial), `review-cancel` and `data:` and `blob:` results with native copy, page/tap counts, observed tab counts where available, and cleanup. Include no private data, clipboard, raw screen captures or logs. **Live confirmation, `file:` handling, provider receipt, stale acceptance after tab/document/Activity retirement, and search results remain untested.**
