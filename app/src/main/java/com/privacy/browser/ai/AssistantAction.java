package com.privacy.browser.ai;

import com.privacy.browser.BrowserDataPolicy;
import com.privacy.browser.InternalHome;
import com.privacy.browser.adblock.rules.FilterSnapshot;
import com.privacy.downloader.net.RequestContext;

import org.json.JSONException;
import org.json.JSONObject;

/** An immutable, typed action. Only normalized values displayed by native review reach dispatch. */
final class AssistantAction {
    private final String parameters;
    final Kind kind;

    enum Kind {
        OPEN("nav_open_url", "Open web address", true, false),
        INFO("nav_get_active_tab_info", "Read current tab address and title", true, false),
        RELOAD("nav_reload_page", "Reload current tab", true, false),
        CONTENT("dom_get_page_content", "Read text, headings or links", true, true),
        FIND("dom_find_elements", "Find elements matching a CSS selector", true, true),
        SCROLL("dom_scroll_page", "Scroll current page", true, true),
        CLICK("dom_click_element", "Click a matching element", true, true),
        TYPE("dom_type_text", "Enter text in a matching input", true, true),
        SELECT("dom_select_dropdown", "Select an option", true, true),
        HISTORY("history_search", "Read matching browsing history", false, false),
        BOOKMARKS("bookmarks_search", "Read matching bookmarks", false, false),
        BOOKMARK("bookmarks_create", "Save a bookmark in the root folder", false, false),
        CLEAR("privacy_clear_site_data", "Clear selected data for all regular sites", false, false),
        SAVE_RULE("adblock_save_and_commit", "Save a local filter rule (no cloud publication)", false, false),
        REMIND("automation_schedule_task", "Schedule an in-app recipe review reminder", false, false),
        REMINDERS("automation_list_tasks", "Read recipe reminder statuses", false, false),
        CANCEL_REMINDER("automation_cancel_task", "Cancel a recipe review reminder", false, false);

        final String name, label;
        final boolean recipe, scripts;
        Kind(String name, String label, boolean recipe, boolean scripts) {
            this.name = name; this.label = label; this.recipe = recipe; this.scripts = scripts;
        }

        static Kind find(String name) {
            for (Kind kind : values()) if (kind.name.equals(name)) return kind;
            return null;
        }
    }

    private AssistantAction(Kind kind, JSONObject args) { this.kind = kind; parameters = args.toString(); }
    JSONObject args() throws JSONException { return new JSONObject(parameters); }
    String reviewText() { return kind.label + ("{}".equals(parameters) ? "" : "\n\n" + parameters); }

    static AssistantAction parse(String name, JSONObject input, boolean recipe) throws JSONException {
        Kind kind = Kind.find(name);
        if (kind == null || recipe && !kind.recipe) {
            throw new IllegalArgumentException("This action is not available here. Use browser controls for unsupported actions.");
        }
        if (input == null || input.length() > 5) throw new IllegalArgumentException("Invalid action arguments.");
        JSONObject args = new JSONObject();
        switch (kind) {
            case OPEN:
                args.put("url", address(input, "url")).put("in_new_tab", flag(input, "in_new_tab"));
                break;
            case INFO: case RELOAD: case REMINDERS:
                break;
            case CONTENT:
                args.put("format", option(input, "format", "text", "headings", "links"));
                break;
            case FIND: case CLICK: case TYPE: case SELECT:
                args.put("css_selector", text(input, "css_selector", null, 512, true));
                if (kind == Kind.CLICK) args.put("index", integer(input, "index", 0L, 0, 99));
                if (kind == Kind.TYPE) {
                    args.put("text", text(input, "text", null, 1024, false));
                    args.put("clear_first", flag(input, "clear_first"));
                    // Keep form submission an explicit action on the page itself.
                }
                if (kind == Kind.SELECT) args.put("option_value_or_text", text(input, "option_value_or_text", null, 256, false));
                break;
            case SCROLL:
                args.put("direction", option(input, "direction", "down", "up", "top", "bottom"));
                args.put("pixels", integer(input, "pixels", 500L, 1, 5000));
                break;
            case HISTORY: case BOOKMARKS:
                args.put("query", text(input, "query", "", 128, true));
                args.put("limit", integer(input, "limit", 15L, 1, 25));
                break;
            case BOOKMARK:
                args.put("title", text(input, "title", null, 180, true)).put("url", address(input, "url"));
                break;
            case CLEAR:
                String domain = text(input, "domain", null, 1, true);
                boolean cookies = flag(input, "clear_cookies"), cache = flag(input, "clear_cache"), storage = flag(input, "clear_storage");
                BrowserDataPolicy.fromTool(domain, cookies, cache, storage);
                args.put("domain", domain).put("clear_cookies", cookies).put("clear_cache", cache).put("clear_storage", storage);
                break;
            case SAVE_RULE:
                String target = option(input, "target_file", null, "user_network", "user_filters");
                String rule = text(input, "rule_text", null, 1024, true);
                FilterSnapshot.Part part = target.equals("user_network") ? FilterSnapshot.Part.NETWORK : FilterSnapshot.Part.USER_FILTERS;
                // Validate one line against the same parser used by the editor and importer.
                FilterSnapshot.checkRule(part, rule);
                args.put("target_file", target).put("rule_text", rule);
                break;
            case REMIND:
                args.put("title", text(input, "title", null, 80, true))
                        .put("trigger_time_epoch_ms", integer(input, "trigger_time_epoch_ms", null, 1L, 9_007_199_254_740_991L))
                        .put("recipe_json", RecipePlan.parse(text(input, "recipe_json", null, RecipePlan.MAX_RECIPE, false)).json);
                break;
            case CANCEL_REMINDER:
                String id = text(input, "task_id", null, 36, true);
                if (!ReminderPlan.validId(id)) throw new IllegalArgumentException("Invalid reminder ID.");
                args.put("task_id", id);
                break;
        }
        java.util.Iterator<String> keys = input.keys();
        while (keys.hasNext()) if (!args.has(keys.next())) throw new IllegalArgumentException("Unsupported argument for this action.");
        return new AssistantAction(kind, args);
    }

    private static String address(JSONObject input, String key) {
        String url = text(input, key, null, 2048, true);
        if (RequestContext.origin(url) == null || InternalHome.ownsHost(url)) {
            throw new IllegalArgumentException("Enter an external HTTP(S) address without credentials.");
        }
        return java.net.URI.create(url).toASCIIString();
    }

    private static String text(JSONObject input, String key, String fallback, int max, boolean trim) {
        Object value = input.has(key) ? input.opt(key) : fallback;
        if (!(value instanceof String)) throw new IllegalArgumentException("Missing or invalid " + key + ".");
        String raw = (String) value;
        if (raw.length() > max) throw new IllegalArgumentException(key + " is too long.");
        // Do not let invisible formatting or control characters obscure the review.
        if (raw.codePoints().anyMatch(ch -> (Character.isISOControl(ch) && (trim || ch != '\n' && ch != '\r' && ch != '\t'))
                || Character.getType(ch) == Character.FORMAT || Character.getType(ch) == Character.SURROGATE)) {
            throw new IllegalArgumentException("Unsupported character in " + key + ".");
        }
        String clean = trim ? raw.trim() : raw;
        if (clean.isEmpty() && fallback == null && trim) throw new IllegalArgumentException("Missing " + key + ".");
        return clean;
    }

    private static String option(JSONObject input, String key, String fallback, String... choices) {
        String value = text(input, key, fallback, 80, true);
        if (fallback != null && fallback.equals(value)) return value;
        for (String choice : choices) if (choice.equals(value)) return value;
        throw new IllegalArgumentException("Unsupported " + key + ".");
    }

    private static boolean flag(JSONObject input, String key) {
        if (!input.has(key)) return false;
        Object value = input.opt(key);
        if (!(value instanceof Boolean)) throw new IllegalArgumentException("Expected true or false for " + key + ".");
        return (Boolean) value;
    }

    private static long integer(JSONObject input, String key, Long fallback, long min, long max) {
        Object value = input.has(key) ? input.opt(key) : fallback;
        if (!(value instanceof Number)) throw new IllegalArgumentException("Missing or invalid " + key + ".");
        Number number = (Number) value;
        long whole = number.longValue();
        if (whole < min || whole > max || number.doubleValue() != whole) throw new IllegalArgumentException("Out of range: " + key + ".");
        return whole;
    }
}
