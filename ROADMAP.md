# Modern Browser Feature Domains Matrix & Implementation Roadmap

This document tracks the progress and status of all major feature domains for the Privacy Browser. It is updated upon completion of each feature domain.

## Feature Domains Matrix

| # | Feature Domain | Key Capabilities & Architecture | Status | Completed Commit |
|---|---|---|---|---|
| 1 | Bookmarks & Favorites | Persistent SQLite database (BookmarkDbHelper), dynamic star toggle in menu, dedicated full-screen AMOLED BookmarksActivity with live search, click-to-load, contextual actions (Open in New Tab, Delete, Share). | DONE | Commits 10, 30 |
| 2 | Native Sharing | System share sheet integration via Android Intent.ACTION_SEND sharing active page title and URL. | DONE | Commit 10 |
| 3 | Browsing History & Incognito | Persistent SQLite history log (HistoryDbHelper) with timestamps, dedicated full-screen AMOLED HistoryActivity with live search, individual item deletion, clear-all action, and isolated Incognito/Private tabs that suppress history, bypass session storage, and disable form caching. | DONE | Commits 11, 30 |
| 4 | Address Bar Aesthetics & Security | Unified AMOLED capsule pill, inline real-time shield indicator (SHIELD 0 / INC 0), dynamic security scheme colorization (https in #4CAF50, http in #FF5252, :// in white). | DONE | Commits 12-14 |
| 5 | File Downloads & Download Manager | Multi-segment parallel HTTP transfer engine (1-16 threads), Range request resume, link update support, AMOLED download manager dashboard, background foreground service with notification progress, time-window scheduler (night downloads), batch URL import, ZIP auto-extraction, link availability probing, and Android Share Sheet integration. | DONE | Commits 15-27 |
| 6 | Search Engine Selector & Omnibox Autocomplete | Selectable default engine (DuckDuckGo, Brave, Startpage, SearXNG, Google, Bing), search bangs (!w, !g, !yt), instant typing suggestions queried live from Bookmarks and History, and AMOLED SettingsActivity dashboard. | DONE | Commit 29 |
| 7 | Page Export (Print / Save as PDF) | High-fidelity offline export using Android's native PrintManager and WebView.createPrintDocumentAdapter(). | PENDING | - |
| 8 | Per-Site Security & Runtime Permissions | Fine-grained WebChromeClient handling for Camera, Microphone, and Geolocation requests with prompt dialogs, HTTPS-only automatic upgrading, and per-site JavaScript/Cookie toggles. | PENDING | - |
| 9 | Speed Dial & AMOLED Home Dashboard | Minimalist new tab homepage featuring top visited/pinned shortcuts, quick search pill, and cumulative privacy stats counter (total trackers & fingerprinting attempts deflected). | PENDING | - |
| 10 | Page Zoom & Accessibility | Text scaling preferences (50% to 200%), forced pinch-to-zoom override for sites with restrictive meta viewports, and font contrast tuning. | PENDING | - |
| 11 | Filter List & Ad-Block Customization | Dynamic filter rule management: toggling ad/tracker blocking per domain (whitelisting exceptions), and importing custom blocklists beyond the bundled rules. | PENDING | - |
| 12 | Media & Video Enhancements | Fullscreen video player support (WebChromeClient.onShowCustomView), background audio playback options, and PiP (Picture-in-Picture) window support. | PENDING | - |

---

## Detailed Implementation Notes & Changelog

### Domain 1: Bookmarks & Favorites [COMPLETED]
- Implemented persistent SQLite database BookmarkDbHelper.
- Added bookmark star toggle in browser menu.
- Dedicated full-screen AMOLED BookmarksActivity with live keyword filtering, open in current/new tab, copy URL, system share, and delete actions.

### Domain 2: Native Sharing [COMPLETED]
- Integrated system share sheet via Android Intent.ACTION_SEND (MIME text/plain).
- Shares current page title and canonical URL to any compatible Android app.

### Domain 3: Browsing History & Incognito Mode [COMPLETED]
- Implemented HistoryDbHelper with automated timestamp recording.
- Dedicated full-screen AMOLED HistoryActivity with live keyword search, quick item deletion, open in current/new tab, system share, and clear-all confirmation dialog.
- Isolated Incognito mode tabs with history suppression, private cache, and disabled form autocompletion.

### Domain 4: Address Bar Aesthetics & Security [COMPLETED]
- Custom AMOLED capsule address bar pill.
- Real-time privacy shield counter and security scheme colorizer (green HTTPS, red HTTP, neutral host).

### Domain 5: File Downloads & Download Manager [COMPLETED]
- Standalone multi-segment parallel transfer engine (1 to 16 threads).
- Range request resume, single-stream fallback, link refresh / update on expiration.
- AMOLED dashboard with live transfer rates, byte progress, dynamic ETA, and per-thread diagnostics chunk inspector.
- Daily time-window scheduler (night downloads with auto-pause and auto-resume).
- Batch URL import dialog with regex link parser and bulk queueing.
- Multi-threaded HTTP HEAD availability probing (Online/Offline badges) in LinkGrabber.
- Category-based subfolder routing and automatic ZIP archive extraction.
- Android system Share Sheet integration (ACTION_SEND).

### Domain 6: Search Engine Selector & Omnibox Autocomplete [COMPLETED]
- AMOLED pitch-black SettingsActivity accessible from the 3-dots overflow menu.
- Selectable search engines: DuckDuckGo (privacy-first default), Brave Search, Startpage, SearXNG, Google, Bing, and custom search query template URL.
- Direct search bangs support (!w Wikipedia, !g Google, !ddg DuckDuckGo, !b Brave, !yt YouTube, !gh GitHub, !r Reddit, !a Amazon, !maps OSM, !wikt Wiktionary, !sp Startpage, !sx SearXNG) with prefix and suffix syntax.
- Real-time Omnibox autocomplete suggestions dropdown querying Bookmarks, History, Search Bangs, and Web Search with color-coded badges and icons.
- Instant click-to-navigate, back-button and outside-tap dismiss handling, and automatic suppression on Incognito tabs.

---

## Next Planned Execution Order
1. Domain 7: Page Export (Print / Save as PDF)
2. Domain 8: Per-Site Security & Runtime Permissions
3. Domain 9: Speed Dial & AMOLED Home Dashboard
4. Domain 10: Page Zoom & Accessibility
5. Domain 11: Filter List & Ad-Block Customization
6. Domain 12: Media & Video Enhancements
