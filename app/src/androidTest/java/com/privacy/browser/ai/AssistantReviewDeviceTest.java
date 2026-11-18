package com.privacy.browser.ai;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import androidx.test.platform.app.InstrumentationRegistry;

import com.privacy.browser.MainActivity;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.Assert.*;

/** Disposable debug install; run the tab_trust fixture with adb reverse tcp:8011 tcp:8011. */
public class AssistantReviewDeviceTest {
    private static final String URL = "http://127.0.0.1:8011/script-landing";
    private static final String RECIPE = "automation_recipe_json";
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private MainActivity browser;

    @After public void stop() {
        if (browser != null) instrumentation.runOnMainSync(browser::finish);
        instrumentation.waitForIdleSync();
    }

    @Test public void approvalDoesNotSurviveNativeReloadOfSameAddress() throws Exception {
        browser = (MainActivity) instrumentation.startActivitySync(launch().putExtra(MainActivity.EXTRA_LOAD_URL, URL));
        AssistantApproval.Page first = awaitPage(null);
        AssistantApproval.Scope before = scope(first);
        AssistantApproval approval = AssistantApproval.request(AssistantAction.parse("nav_get_active_tab_info", new JSONObject(), false),
                AssistantApproval.Source.MANUAL_RECIPE, before, before, SystemClock.elapsedRealtime());
        assertTrue(ui(() -> {
            assertTrue(browser.reloadApprovedAssistantPage(first));
            assertFalse(first.matches(browser.assistantPage()));
            return true;
        }));
        AssistantApproval.Page next = awaitPage(first);
        assertEquals(first.url, next.url);
        assertNull(approval.consume(scope(next), SystemClock.elapsedRealtime()));
        assertFalse(ui(() -> browser.evaluateApprovedAssistantScript(first, "return {fixture:true};", value -> fail("Old document executed"))));
    }

    @Test public void externalRecipeExtrasAreNotAdmittedOnColdOrWarmLaunch() throws Exception {
        Intent cold = launch().putExtra(RECIPE, "[{\"action\":\"nav_reload_page\",\"params\":{}}]")
                .putExtra(MainActivity.EXTRA_LOAD_URL, URL + "?must-not-open");
        browser = (MainActivity) instrumentation.startActivitySync(cold);
        // Activity draw/idle no longer means the background startup gate has handled the link.
        long coldDeadline = SystemClock.uptimeMillis() + 15_000;
        while (ui(() -> browser.getIntent().hasExtra(RECIPE) || browser.getIntent().hasExtra(MainActivity.EXTRA_LOAD_URL))) {
            assertTrue("Startup did not consume the blocked recipe request", SystemClock.uptimeMillis() < coldDeadline);
            Thread.sleep(40);
        }
        instrumentation.runOnMainSync(() -> {
            browser.setIntent(new Intent().putExtra("waiting_for_warm_recipe_fixture", true));
            context.startActivity(launch().putExtra(RECIPE, "[]")
                    .putExtra(MainActivity.EXTRA_LOAD_URL, URL + "?warm-must-not-open"));
        });
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            if (ui(() -> !browser.getIntent().hasExtra("waiting_for_warm_recipe_fixture")
                    && !browser.getIntent().hasExtra(RECIPE) && !browser.getIntent().hasExtra(MainActivity.EXTRA_LOAD_URL))) {
                instrumentation.waitForIdleSync();
                assertFalse(ui(() -> {
                    AssistantApproval.Page page = browser.assistantPage();
                    return page != null && page.url.contains("must-not-open");
                }));
                return;
            }
            Thread.sleep(40);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Warm recipe request remained on the Activity");
    }

    private AssistantApproval.Page awaitPage(AssistantApproval.Page previous) throws InterruptedException {
        long deadline = SystemClock.uptimeMillis() + 15_000;
        do {
            AssistantApproval.Page page = ui(browser::assistantPage);
            if (page != null && URL.equals(page.url) && (previous == null || !previous.matches(page))) return page;
            Thread.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("No fresh committed fixture page; verify adb reverse and the fixture server");
    }

    private Intent launch() { return new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); }
    private static AssistantApproval.Scope scope(AssistantApproval.Page page) { return new AssistantApproval.Scope("device-fixture", 1, page); }

    private <T> T ui(Supplier<T> read) {
        AtomicReference<T> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> result.set(read.get()));
        return result.get();
    }
}
