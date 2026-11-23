# Assistant, images, comics and local data — DATA-01 wireless folders

One **folder-only** checkpoint in the actual Bookmark UI: move one nested subtree, verify the same-destination no-op and reopen its path, then delete only that synthetic subtree while a separate synthetic sibling stays. This is not a migration, bulk move, write-failure, full destination-list audit, or check of retained personal records.

## Guard and isolate the fixture

- Verify the pinned source ZIP. Use only the Bash `d` helper and [Section 1 target guard](android-download-wireless-resume.md#1-select-the-retained-target-without-changing-it) for the already-connected wireless S21 FE. Keep the installed APKs, connection, settings and existing records. No build/install, reconnect, process stop, reset, instrumentation, SQL/filesystem reads, logging private data, clipboard, or owner action. Only four **new fixture folder rows** are in mutation scope, through normal UI, plus one new test tab. No other bookmark actions, sharing or site visits.
- Run `python3 -B tests/fixtures/bookmark_wireless_folders.py` from the extracted root. It prints one `ArenaF` tag and the exact test URL; refer to the tag below as `TAG` (not the literal letters `TAG`). It serves host loopback only on 8031. Inspect `d reverse --list` and use only `d reverse --no-rebind tcp:8031 tcp:8031` on a free port. If either port or the guarded UI is unavailable, stop and report that blocker. Host counts: `curl -fsS http://127.0.0.1:8031/control/counts`. Do not load the fixture page from the host.
- Note the tab count if safe. Use the following sole explicit entry after setting `tag` to the fixture's printed tag, then require the exact `{tag}-Tab` title/heading (`{tag}` means the actual tag) and an entry GET. Use normal UI for all folder actions.

  ```bash
  d shell am start -W --user 0 -n com.browser/com.privacy.browser.MainActivity \
    --es com.privacy.browser.EXTRA_LOAD_URL "http://127.0.0.1:8031/folders/$tag" \
    --ez com.privacy.browser.EXTRA_OPEN_IN_NEW_TAB true
  ```

- Open **Menu → Bookmarks** (`menu_item_bookmarks_list`). **Do not enumerate unrelated rows**: use only the toolbar and exact synthetic text, and filter root through `bookmarks_edit_search` with the generated tag before reading any rows. Do not retain an unfiltered screenshot or dump or read the database. A destination picker may show unrelated folders; seek only the exact named test target, do not read, export or select other titles. If safe identification is not possible, stop. Hide the keyboard after typing without submitting a browser search. Never use Select All.

## 1. `DATA-01/move-reopen` — actual selected folder, then read its path anew

1. Require zero root matches for the fresh tag before creation. Via `bookmarks_btn_new_folder` (**New Folder → Create**), create root folders `TAG-A` and `TAG-B`. Check `bookmarks_header_title` before every creation. Inside A create one `TAG-C`; inside B create one `TAG-W`. C and W are empty. Use the real breadcrumb/Back control to return to root, not app restart or new intents. Confirm exact names and structure before continuing. If a name already exists, do not adopt it as fixture data.
2. At root, with the tag filter still applied, long-press only the visible exact A row. Require **1 selected**, then `bookmarks_btn_move_selected` and **Move to Folder (1 item)**. Select only exact B, never root or a prefix match. Require A to disappear from the filtered root and B to contain the exact A and W rows. A must still contain C. Record the native move status if visible; the hierarchy is the main witness.
3. While in B, single-select that exact A again and choose B once more. This is `DATA-01/no-op`. Require **Item is already in this folder** and unchanged B/A/C and B/W. If the toast cannot be observed, report the unchanged hierarchy but mark the message inconclusive; do not repeat the move. Exit selection before leaving Bookmarks.
4. Return to the same fixture tab using ordinary toolbar navigation. Reopen **Menu → Bookmarks** (reapply the tag filter), then open `B → A → C`. Require the expected synthetic names in the headers/breadcrumb and the empty C. Verify W remains in B. This is a real Bookmark Activity reopen, not a process restart or an installed upgrade.

## 2. `DATA-01/owned-subtree-delete` — only the rows created by this attempt

- Within B, ensure A contains exactly C and C is empty, and the separate empty W is still present. Check *all* contents/counts only inside these owned folders (do not mistake a filtered list for an empty folder). If ownership/content cannot be confirmed, **do not tap Delete**. Show B, long-press the exact A once, verify **1 selected**, use `bookmarks_btn_delete_selected`, and confirm only the native **Delete Selected Items** review for one selected folder. Do not use the immediate-delete row action. Expect A gone, B and W still present, and, if observable, **2 items deleted** (A and C). This is scoped UI evidence of the synthetic subtree action and sibling retention, not a raw database/orphan check. If the count toast is missed, report that limit.
- Cleanup: after confirming B contains only that run's empty W, go to filtered root, select only exact B and use the same one-item review to remove the two known fixture rows. Confirm zero tag matches at root. On a partial attempt, clean up only creations whose ownership and full contents can be established from that run; otherwise report the exact synthetic residues, with no inferred deletion or broader cleanup.

## Return and stop

Close only the identifiable fixture tab (`TAG-Tab` and its fixture URL), remove only the still-matching reverse created by this run, stop only its host listener, and remove temporary captures. Record own tab counts, not baseline tab identities. Missing controls, unreadable timing/toast, unexpected extra rows or uncertain ownership are separate blockers/inconclusive observations, never permission for wider inspection. One attempt per assertion.

Return `bookmarks-wireless-folders.md` with the ZIP/commit/tree/digest, app/test digests, handset/API/ABI/page-size/WebView, the fixture tag and entry count, move/reopen, no-op and owned-delete results, exact synthetic tree before/after, native UI and cleanup. No serials, unrelated folder labels, raw UI dumps/captures or logs. Self/descendant picker completeness and stale writes remain open, as do mixed moves, a disposable upgrade and the remaining Topic 3 checks.
