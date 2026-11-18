package com.privacy.browser.ai;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.privacy.browser.BrowserDataPolicy;
import com.privacy.browser.MainActivity;
import com.privacy.browser.ThemeManager;
import com.privacy.browser.adblock.AdBlockFileManager;
import com.privacy.browser.adblock.rules.FilterSnapshot;
import com.privacy.browser.adblock.rules.RuleWork;
import com.privacy.browser.adblock.rules.FilterSnapshot.Part;
import com.privacy.browser.bookmarks.BookmarkDbHelper;
import com.privacy.browser.bookmarks.BookmarkModel;
import com.privacy.browser.history.HistoryDbHelper;
import com.privacy.browser.history.HistoryModel;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.function.Supplier;

/** The only dispatch entry point is a native, one-shot review of a typed action. UI thread only. */
final class ToolDispatcher implements AgentSessionManager.Reviews {
    interface ExecutionCallback {
        void onCompleted(JSONObject result);
        void onStopped(String status, String reason);
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MainActivity activity;
    private AlertDialog review;
    private Runnable invalidateReview;

    ToolDispatcher(Context context) { this.context = context.getApplicationContext(); }

    @Override public void attach(MainActivity owner) {
        if (owner != activity) {
            cancelReview();
            activity = owner;
        }
    }

    @Override public void detach(MainActivity owner) { if (activity == owner) attach(null); }
    @Override public MainActivity owner() { return activity; }
    @Override public AssistantApproval.Page page() { return activity == null ? null : activity.assistantPage(); }

    @Override public void cancelReview() { if (invalidateReview != null) invalidateReview.run(); }

    @Override public void review(AssistantAction action, AssistantApproval.Source source, AssistantApproval.Scope expected,
                                 Supplier<AssistantApproval.Scope> live, ExecutionCallback result) {
        if (Looper.myLooper() != Looper.getMainLooper() || activity == null || review != null) {
            result.onStopped("unavailable", "Only one visible browser action can be reviewed at a time.");
            return;
        }
        final AssistantApproval approval;
        try { approval = AssistantApproval.request(action, source, expected, live.get(), SystemClock.elapsedRealtime()); }
        catch (IllegalArgumentException e) { result.onStopped("stale", e.getMessage()); return; }
        String note = source == AssistantApproval.Source.CHAT
                ? "\n\nThis action's result can be sent to Google AI Studio."
                : "\n\nThis step runs only if you approve it now. Other steps do not run automatically.";
        if (action.kind == AssistantAction.Kind.CLICK || action.kind == AssistantAction.Kind.TYPE || action.kind == AssistantAction.Kind.SELECT) {
            note += "\n\nThis may submit a form or trigger other site behavior.";
        }
        if (action.kind == AssistantAction.Kind.CLEAR) note += "\n\n" + BrowserDataPolicy.LIMITS;
        if (action.kind == AssistantAction.Kind.REMIND) note += "\n\nIn-app only. The alarm marks the reminder due; it never opens the browser or runs a recipe.";
        final String summary = note;
        final MainActivity owner = activity;
        boolean[] decided = {false};
        AlertDialog dialog = new AlertDialog.Builder(owner, ThemeManager.getDialogTheme(owner))
                .setTitle("Review one browser action")
                .setMessage("Tab: " + JSONObject.quote(java.net.URI.create(expected.page.url).toASCIIString()) + "\n\n" + action.reviewText() + summary)
                .setNegativeButton("Deny", null)
                .setPositiveButton("Approve once", null).create();
        java.util.function.BiConsumer<String, String> reject = (status, reason) -> {
            if (review != dialog || decided[0]) return;
            decided[0] = true;
            approval.deny();
            dialog.dismiss();
            result.onStopped(status, reason);
        };
        Runnable expire = () -> reject.accept("timeout", "Action review expired. Nothing ran.");
        dialog.setOnDismissListener(d -> {
            main.removeCallbacks(expire);
            if (review == dialog) {
                review = null;
                invalidateReview = null;
            }
            if (!decided[0]) {
                approval.deny();
                result.onStopped("denied", "Action was not approved. Nothing ran.");
            }
        });
        review = dialog;
        invalidateReview = () -> reject.accept("stale", "The review is no longer current. Nothing ran.");
        try { dialog.show(); }
        catch (android.view.WindowManager.BadTokenException hidden) {
            decided[0] = true;
            approval.deny();
            review = null;
            invalidateReview = null;
            result.onStopped("unavailable", "The review could not be shown. Nothing ran.");
            return;
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setFilterTouchesWhenObscured(true);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (review != dialog || decided[0] || !dialog.isShowing()) return;
            decided[0] = true;
            // No posted click or mutable target between validation and dispatch.
            AssistantAction accepted = approval.consume(live.get(), SystemClock.elapsedRealtime());
            dialog.dismiss();
            if (accepted == null || activity != owner) {
                result.onStopped("stale", "Approval expired or the active session, tab or document changed. Nothing ran.");
                return;
            }
            dispatch(accepted, owner, expected, approval, live, result);
        });
        main.postDelayed(expire, AssistantApproval.LIFETIME_MS);
    }

    private void dispatch(AssistantAction action, MainActivity owner, AssistantApproval.Scope scope,
                          AssistantApproval approval, Supplier<AssistantApproval.Scope> live, ExecutionCallback callback) {
        try {
            JSONObject args = action.args();
            if (action.kind.scripts) {
                evaluate(action, args, owner, scope, live, callback);
                return;
            }
            switch (action.kind) {
                case OPEN:
                    if (!owner.openApprovedAssistantLink(scope.page, args.getString("url"), args.getBoolean("in_new_tab"))) {
                        callback.onStopped("stale", "The link was not opened.");
                    } else callback.onCompleted(new JSONObject().put("status", "navigation_requested"));
                    break;
                case RELOAD:
                    if (!owner.reloadApprovedAssistantPage(scope.page)) callback.onStopped("stale", "The tab was not reloaded.");
                    else callback.onCompleted(new JSONObject().put("status", "reload_requested"));
                    break;
                case INFO:
                    callback.onCompleted(new JSONObject().put("url", scope.page.url).put("title", owner.assistantTitle(scope.page)));
                    break;
                case HISTORY:
                    JSONArray visits = new JSONArray();
                    for (HistoryModel visit : HistoryDbHelper.getInstance(context).searchHistory(args.getString("query"), args.getInt("limit"))) {
                        visits.put(new JSONObject().put("title", shortText(visit.getTitle())).put("url", shortText(visit.getUrl())));
                    }
                    callback.onCompleted(new JSONObject().put("items", visits));
                    break;
                case BOOKMARKS:
                    JSONArray bookmarks = new JSONArray();
                    for (BookmarkModel bookmark : BookmarkDbHelper.getInstance(context).searchBookmarks(args.getString("query"), args.getInt("limit"))) {
                        if (!bookmark.isFolder()) bookmarks.put(new JSONObject().put("title", shortText(bookmark.getTitle())).put("url", shortText(bookmark.getUrl())));
                    }
                    callback.onCompleted(new JSONObject().put("items", bookmarks));
                    break;
                case BOOKMARK:
                    if (BookmarkDbHelper.getInstance(context).addBookmark(args.getString("title"), args.getString("url"))) {
                        callback.onCompleted(new JSONObject().put("status", "bookmark_saved"));
                    } else callback.onStopped("error", "Bookmark was not saved.");
                    break;
                case CLEAR:
                    BrowserDataPolicy.Plan plan = BrowserDataPolicy.fromTool(args.getString("domain"), args.getBoolean("clear_cookies"),
                            args.getBoolean("clear_cache"), args.getBoolean("clear_storage"));
                    owner.clearApprovedBrowserData(plan, json -> {
                        try { if (!scope.matches(live.get())) {
                            callback.onStopped("page_changed", "Cleanup was requested before the page or session changed. The report was not used.");
                        } else callback.onCompleted(new JSONObject(json)); }
                        catch (org.json.JSONException e) { callback.onStopped("error", "Could not read the cleanup report."); }
                    }, error -> callback.onStopped("unavailable", error));
                    break;
                case SAVE_RULE:
                    Part part = "user_network".equals(args.getString("target_file")) ? Part.NETWORK : Part.USER_FILTERS;
                    AdBlockFileManager files = AdBlockFileManager.getInstance(context);
                    FilterSnapshot before = files.ruleSnapshot();
                    String rule = args.getString("rule_text");
                    RuleWork.run(() -> appendRule(before, part, rule), (next, error) -> {
                        if (activity != owner || !approval.current(live.get(), SystemClock.elapsedRealtime())) {
                            callback.onStopped("stale", "The local rule was not saved because the review is no longer current.");
                            return;
                        }
                        if (error != null) {
                            callback.onStopped("error", RuleWork.message(error));
                            return;
                        }
                        try {
                            // No worker gap after this final page/session guard and the atomic rule commit.
                            files.applyRuleSnapshot(before, next);
                            owner.refreshApprovedRules();
                            callback.onCompleted(new JSONObject().put("status", before == next ? "already_present" : "rule_saved_locally")
                                    .put("section", part.key));
                        } catch (Exception failure) {
                            callback.onStopped("error", RuleWork.message(failure));
                        }
                    });
                    break;
                case REMIND:
                    AutomationManager reminders = new AutomationManager(context);
                    AutomationManager.Task task = reminders.schedule(args.getString("title"), args.getLong("trigger_time_epoch_ms"),
                            RecipePlan.parse(args.getString("recipe_json")));
                    callback.onCompleted(task.summary());
                    break;
                case REMINDERS:
                    JSONArray tasks = new JSONArray();
                    for (AutomationManager.Task saved : new AutomationManager(context).list()) tasks.put(saved.summary());
                    callback.onCompleted(new JSONObject().put("reminders", tasks));
                    break;
                case CANCEL_REMINDER:
                    boolean canceled = new AutomationManager(context).cancel(args.getString("task_id"));
                    callback.onCompleted(new JSONObject().put("status", canceled ? "cancelled" : "not_found"));
                    break;
                default:
                    callback.onStopped("unsupported", "This action is unavailable.");
                    break;
            }
        } catch (Exception e) {
            callback.onStopped("error", e instanceof IllegalArgumentException || e instanceof java.io.IOException
                    ? shortText(e.getMessage()) : "Browser action failed.");
        }
    }

    private static FilterSnapshot appendRule(FilterSnapshot before, Part part, String rule) {
        String text = before.text(part);
        for (String line : FilterSnapshot.lines(text)) if (line.trim().equals(rule)) return before;
        String separator = text.isEmpty() || text.endsWith("\n") || text.endsWith("\r") ? "" : "\n";
        return before.replace(part, text + separator + rule + "\n");
    }

    private void evaluate(AssistantAction action, JSONObject args, MainActivity owner, AssistantApproval.Scope scope,
                          Supplier<AssistantApproval.Scope> live, ExecutionCallback callback) throws Exception {
        String body;
        switch (action.kind) {
            case CONTENT:
                String format = args.getString("format");
                if ("headings".equals(format)) body = "return {headings:Array.from(document.querySelectorAll('h1,h2,h3')).slice(0,25).map(e=>e.innerText.trim().slice(0,160))};";
                else if ("links".equals(format)) body = "return {links:Array.from(document.links).slice(0,20).map(e=>({text:e.innerText.trim().slice(0,100),href:e.href.slice(0,512)}))};";
                else body = "return {title:document.title.slice(0,160),text:document.body?document.body.innerText.slice(0,3000):''};";
                break;
            case FIND:
                body = "const items=document.querySelectorAll(" + literal(args.getString("css_selector")) + ");"
                        + "return {count:items.length,items:Array.from(items).slice(0,15).map(e=>({tag:e.tagName,id:e.id.slice(0,80),text:(e.innerText||'').slice(0,100)}))};";
                break;
            case SCROLL:
                switch (args.getString("direction")) {
                    case "top": body = "window.scrollTo(0,0);"; break;
                    case "bottom": body = "window.scrollTo(0,document.documentElement.scrollHeight);"; break;
                    case "up": body = "window.scrollBy(0,-" + args.getInt("pixels") + ");"; break;
                    default: body = "window.scrollBy(0," + args.getInt("pixels") + ");"; break;
                }
                body += "return {status:'scroll_requested'};";
                break;
            case CLICK:
                body = "const e=document.querySelectorAll(" + literal(args.getString("css_selector")) + ")[" + args.getInt("index") + "];"
                        + "if(!e||!e.getClientRects().length)return {status:'not_found_or_not_visible'};"
                        + "e.click();return {status:'click_dispatched'};";
                break;
            case TYPE:
                body = "const e=document.querySelector(" + literal(args.getString("css_selector")) + ");"
                        + "if(!e||!e.matches('textarea,input[type=text],input:not([type]),input[type=search]')||e.disabled||e.readOnly||!e.getClientRects().length)return {status:'not_editable'};"
                        + "e.value=" + (args.getBoolean("clear_first") ? "" : "e.value+") + literal(args.getString("text")) + ";"
                        + "e.dispatchEvent(new Event('input',{bubbles:true}));return {status:'input_dispatched'};";
                break;
            case SELECT:
                body = "const e=document.querySelector(" + literal(args.getString("css_selector")) + ");"
                        + "if(!e||e.tagName!=='SELECT'||e.disabled||!e.getClientRects().length)return {status:'not_editable'};"
                        + "const wanted=" + literal(args.getString("option_value_or_text")) + ";"
                        + "const opts=Array.from(e.options);const i=opts.findIndex(o=>!o.disabled&&(o.value===wanted||o.text.trim()===wanted));"
                        + "if(i<0)return {status:'option_not_found'};e.selectedIndex=i;e.dispatchEvent(new Event('change',{bubbles:true}));return {status:'selection_dispatched'};";
                break;
            default: throw new IllegalArgumentException("Unsupported page action.");
        }
        if (!owner.evaluateApprovedAssistantScript(scope.page, "try{" + body + "}catch(_){return {status:'page_action_failed'};}", value -> {
            if (!scope.matches(live.get())) {
                callback.onStopped("page_changed", "Page changed after dispatch; the effect may already have occurred. No page result was used.");
                return;
            }
            try {
                if (value == null || value.length() > 24 * 1024 || !RecipePlan.boundedJson(value, 6)) throw new IllegalArgumentException();
                Object json = new JSONTokener(value).nextValue();
                if (!(json instanceof JSONObject)) throw new IllegalArgumentException();
                callback.onCompleted((JSONObject) json);
            } catch (Exception e) { callback.onStopped("unavailable", "No usable result from the approved document."); }
        })) callback.onStopped("unavailable", "No current scripted page is available for this action.");
    }

    private static String shortText(String value) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), 450));
    }

    private static String literal(String value) {
        return JSONObject.quote(value).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    }
}
