package com.privacy.browser.adblock.rules;

import android.content.Context;

import com.privacy.browser.adblock.AdBlockFileManager;
import com.privacy.browser.adblock.rules.FilterSnapshot.Part;

import java.io.IOException;

/** The editor and cosmetic engine read from the same versioned snapshot as interception. */
public final class UserFiltersManager {
    private final AdBlockFileManager files;

    private UserFiltersManager(Context context) { files = AdBlockFileManager.getInstance(context); }

    public static UserFiltersManager getInstance(Context context) { return new UserFiltersManager(context); }

    public FilterSnapshot snapshot() { return files.ruleSnapshot(); }

    public FilterSnapshot prepareScopedRule(FilterSnapshot before, String rule, String host) {
        FilterSnapshot.pickerSelector(rule, host);
        String text = before.text(Part.USER_FILTERS);
        for (String line : FilterSnapshot.lines(text)) if (line.trim().equals(rule)) {
            return before;
        }
        String separator = text.isEmpty() || text.endsWith("\n") || text.endsWith("\r") ? "" : "\n";
        return before.replace(Part.USER_FILTERS, text + separator + rule + "\n");
    }

    public FilterSnapshot setAllRules(FilterSnapshot before, String text) throws IOException {
        FilterSnapshot next = before.replace(Part.USER_FILTERS, text);
        commit(before, next);
        return next;
    }

    public void commit(FilterSnapshot before, FilterSnapshot next) throws IOException {
        files.applyRuleSnapshot(before, next);
    }

    public String ruleWarning() { return files.ruleWarning(); }
    public String getCosmeticCssForDomain(String domain) { return snapshot().cosmeticCss(domain); }
}
