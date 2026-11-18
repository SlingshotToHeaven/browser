# AI Integration Architecture and Implementation Plan

> Historical design, not setup instructions. Termux execution, Antigravity OAuth, unattended recipes, and new image attachments are disabled. See [current Agent behavior](README.md#agent-actions-reminders-and-image-sharing).

Branch: `ai-in`
Target Module: `01-androidx-webview-injection`
Created: 2026-11-03

---

## 1. Executive Overview

This plan defines the integration of an autonomous AI Agent into the browser. The Agent acts as an intelligent assistant capable of:
1. Managing and controlling browser history, bookmarks, and downloads with natural language fuzzy queries.
2. Managing site storage, cookies, and cache on demand.
3. Autonomous web navigation, console-like DOM inspection and interaction (click, type, scroll), and multimodal visual perception (viewport screenshots and visual verification).
4. Automated and scheduled routines (e.g., timed navigation and multi-step actions via Android AlarmManager).
5. Self-healing adblocker operations (iteratively diagnosing anti-adblock banners, synthesizing cosmetic or scriptlet rules, hot-reloading and verifying in-memory, and committing validated states).

### Dual-Mode Strategy
- **Structured Mode (Active Priority)**: Strict Google Gemini function-calling tool execution over pre-declared JSON schemas with typed validation, robust error handling, and security confirmation gates.
- **Raw Mode (Preview/Stub)**: Cleanly stubbed in both the UI (tab indicator showing preview card) and backend code (`RawModeExecutor.java`), ready for future expansion into direct scripting and unrestricted system orchestration.

---

## 2. In-App AI System Guide Format: `ai_assistant_guide.json`

Location: `app/src/main/assets/ai/ai_assistant_guide.json`

The guide is authored in standardized JSON (not Markdown) to enable:
- Direct, zero-translation ingestion into the Gemini API `tools` / `function_declarations` request payload.
- Instant, lightweight parsing in Android Java using standard `org.json.JSONObject` with zero extra dependencies.
- Clear structural division across 4 core sections:
  1. `system_manifest`: Version, constraints, zero-emoji policy, concise communication style, confirmation protocols.
  2. `browser_internals`: Database schemas (history, bookmarks), storage layout, and adblocking engine architecture.
  3. `execution_playbooks`: Step-by-step reasoning recipes (anti-adblock healing, automated navigation and interaction, batch data cleanup).
  4. `tools`: Complete JSON schemas for all 30 predefined tools, including parameters, descriptions, and `requires_confirmation` flags.

---

## 3. Predefined Tool Registry (Structured Mode)

### Group 1: Navigation & Tab Lifecycle
- `nav_open_url(url: string, in_new_tab: boolean)`: Navigate current or open new tab.
- `nav_get_active_tab_info()`: Return current URL, title, scroll offset, and loading state.
- `nav_list_tabs()`: List all active tabs with IDs and titles.
- `nav_switch_tab(tab_id: integer)`: Bring tab to foreground.
- `nav_close_tab(tab_id: integer)`: Close specified tab.
- `nav_reload_page(bypass_cache: boolean)`: Refresh active page.
- `nav_go_back_or_forward(direction: string)`: History navigation (`back` or `forward`).

### Group 2: DOM Inspection & Console Actions
- `dom_get_page_content(format: string)`: Extract structured page content (`headings`, `links`, `forms`, `interactive_elements`, `text`).
- `dom_find_elements(css_selector: string, xpath: string)`: Return tag, classes, IDs, visibility, text, and bounding boxes.
- `dom_click_element(css_selector: string, index: integer)`: Scroll into view and dispatch mouse/pointer click events.
- `dom_type_text(css_selector: string, text: string, clear_first: boolean, press_enter: boolean)`: Focus and input text.
- `dom_scroll_page(direction: string, pixels: integer, to_element_selector: string)`: Scroll viewport or scroll to element.
- `dom_select_dropdown(css_selector: string, option_value_or_text: string)`: Select option in dropdown.

### Group 3: Multimodal Vision & Visual Verification
- `vision_capture_viewport(include_full_page: boolean)`: Capture active WebView rendering as high-res bitmap and attach to Gemini context.
- `vision_capture_element(css_selector: string)`: Crop specific element (captcha challenge, popup, banner) for inspection.

### Group 4: History, Bookmarks & Downloads Management
- `history_search(query: string, domain: string, start_time_ms: integer, end_time_ms: integer, limit: integer)`: Query browsing history.
- `history_delete(ids: array, query_filter: string, all_matching: boolean)`: Batch delete history items. *(Requires Confirmation)*
- `bookmarks_search(query: string, folder: string, url_pattern: string)`: Query saved bookmarks.
- `bookmarks_create(title: string, url: string, folder: string)`: Add bookmark.
- `bookmarks_delete(ids: array, url_pattern: string)`: Delete bookmarks. *(Requires Confirmation)*
- `downloads_list(name_pattern: string, status: string)`: List download records.
- `downloads_delete(file_paths: array, remove_files_from_disk: boolean)`: Remove records and optionally delete files. *(Requires Confirmation)*

### Group 5: Storage, Privacy & Site Data
- `privacy_get_site_data(domain: string)`: Query cookie count, cache, and local storage usage.
- `privacy_clear_site_data(domain: string, clear_cookies: boolean, clear_cache: boolean, clear_storage: boolean)`: Clear site data. *(Requires Confirmation)*

### Group 6: Adblock & Scriptlet Self-Healing
- `adblock_inspect_page()`: Inspect blocked network calls, active cosmetic filters, and anti-adblock detection scripts.
- `adblock_test_rule(rule_text: string, rule_type: string)`: In-memory injection test of cosmetic hide rule or scriptlet in active tab.
- `adblock_save_and_commit(rule_text: string, target_file: string, commit_message: string)`: Persist rule and commit state. *(Requires Confirmation)*

### Group 7: Timed Routines & Automations
- `automation_schedule_task(trigger_time_epoch_ms: integer, cron_or_delay: string, recipe_json: string, title: string)`: Register exact alarm with AlarmManager.
- `automation_list_tasks()`: List active scheduled automations.
- `automation_cancel_task(task_id: string)`: Cancel scheduled automation.

---

## 4. UI Architecture & Components

1. **3-Dot Overflow Menu Toggle (`popup_menu.xml`, `MainActivity.java`)**:
   - Placed directly below the **Ad Blocker** menu item.
   - Title: **Agent** with a switch icon.
   - Persisted in `SharedPreferences` (`pref_agent_enabled`).

2. **Floating Draggable AI Action Button (`AiFloatingButtonView.java`, `view_ai_floating_button.xml`)**:
   - Visible when enabled in the 3-dot menu.
   - Draggable along screen edges with spring edge docking.
   - Subtle glowing ambient pulse.
   - Tapping opens the Agent Bottom Sheet.

3. **Agent Bottom Sheet / Chat Panel (`AgentBottomSheet.java`, `dialog_agent_sheet.xml`)**:
   - Header with title, API key settings shortcut, and Mode Switcher tab:
     - `[ * Structured Mode (Active) ]`
     - `[ Raw Mode (Preview) ]`
   - Chat message thread with user prompts, AI text responses, and tool invocation status indicators.
   - Inline Action Confirmation Card (`dialog_ai_action_confirmation.xml`):
     - Displays action summary, item count, target list preview, or diff.
     - `Cancel` vs `Confirm & Execute` buttons. Execution callback only fires upon user confirmation.
   - Bottom input bar with text input, screenshot attachment button, and send button.

4. **API Key & Configuration Dialog (`AiSettingsDialog.java`, `dialog_ai_settings.xml`)**:
   - Secure input for Google Gemini API Key.
   - Model selection: `gemini-2.5-flash` (default, fast & multimodal) and `gemini-1.5-pro`.
   - Persisted securely in private `SharedPreferences`.

---

## 5. Implementation Phases

- **Phase 1: Knowledge Base Specification & Gemini Client Foundation**
  - Author `app/src/main/assets/ai/ai_assistant_guide.json` with all schemas, rules, and playbooks.
  - Create `GeminiClient.java` (handling Gemini HTTP requests, function calling protocol, multimodal image attachments).
  - Create `AiConfigManager.java` for API key and model persistence.

- **Phase 2: UI Integration (Menu Toggle, Floating Button, Agent Sheet)**
  - Add Agent toggle item in 3-dot overflow menu below Ad Blocker.
  - Implement `AiFloatingButtonView`.
  - Implement `AgentBottomSheet` with chat recycler view, mode tab strip, and settings dialog.
  - Add Action Confirmation Card view logic and `RawModeExecutor` stub.

- **Phase 3: Core Tool Execution Engine (Browser Data & Privacy)**
  - Implement `ToolRegistry.java` and `ToolDispatcher.java`.
  - Implement executors for Navigation, History, Bookmarks, Downloads, and Privacy/Cookies.
  - Wire confirmation gate to protect destructive tools.

- **Phase 4: DOM Console & Multimodal Vision Engine**
  - Implement `DomToolExecutor.java` (console-like script injection for query, click, type, and scroll).
  - Implement `VisionToolExecutor.java` (PixelCopy/Canvas screenshot capture and base64 attachment).

- **Phase 5: Adblock Self-Healing Loop & Automation Scheduler**
  - Implement in-memory adblock rule test and verify loop.
  - Wire into existing `StateCommit` and adblock file storage.
  - Implement `AutomationManager.java` using `AlarmManager` for scheduled routines.

---

## 6. Constraints & Coding Standards
- 100% Pure Java + XML (no Kotlin, no Compose).
- Zero-emoji policy across all layouts, code, strings, logs, and commit messages.
- Total app size strictly monitored under the 20 MB budget.
- Two-phase commit protocol recorded in `commits.txt`.

