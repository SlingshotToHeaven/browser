package com.privacy.browser.adblock;

import com.privacy.browser.InternalHome;
import com.privacy.downloader.net.RequestContext;

import java.net.URI;
import java.util.Locale;

/** Host matching never falls back to checking arbitrary URL substrings. */
final class UserScriptMatch {
    private UserScriptMatch() {}

    static boolean matches(String pattern, String url) {
        if (pattern == null || pattern.length() > 1024 || RequestContext.origin(url) == null || InternalHome.ownsHost(url)) return false;
        pattern = pattern.trim();
        if ("*youtube.com*".equalsIgnoreCase(pattern)) pattern = "https://*.youtube.com/*"; // old bundled preset
        if ("*".equals(pattern) || "<all_urls>".equals(pattern) || "all".equalsIgnoreCase(pattern)) return true;
        URI uri = URI.create(url);
        int schemeEnd = pattern.indexOf("://");
        if (schemeEnd >= 0) {
            String scheme = pattern.substring(0, schemeEnd);
            if (!"*".equals(scheme) && !scheme.equalsIgnoreCase(uri.getScheme())) return false;
            pattern = pattern.substring(schemeEnd + 3);
        }
        int slash = pattern.indexOf('/');
        String authority = slash < 0 ? pattern : pattern.substring(0, slash);
        if (!"*".equals(authority)) {
            boolean subdomains = authority.startsWith("*.");
            String hostAndPort = subdomains ? authority.substring(2) : authority;
            URI allowed;
            try { allowed = URI.create("https://" + hostAndPort); }
            catch (IllegalArgumentException e) { return false; }
            if (allowed.getRawUserInfo() != null || allowed.getHost() == null || allowed.getRawQuery() != null
                    || allowed.getRawFragment() != null || allowed.getPort() > 65535) return false;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            String expected = allowed.getHost().toLowerCase(Locale.ROOT);
            if (!host.equals(expected) && !(subdomains && host.endsWith("." + expected))) return false;
            if (allowed.getPort() >= 0 && allowed.getPort() != effectivePort(uri)) return false;
        }
        if (slash < 0) return true;
        String path = uri.getRawPath();
        return glob(pattern.substring(slash), path == null || path.isEmpty() ? "/" : path);
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    /** Only '*' is special; no regex constructs or backtracking engine. */
    private static boolean glob(String rule, String path) {
        int r = 0, p = 0, star = -1, resume = -1;
        while (p < path.length()) {
            if (r < rule.length() && rule.charAt(r) != '*' && rule.charAt(r) == path.charAt(p)) { p++; r++; }
            else if (r < rule.length() && rule.charAt(r) == '*') { star = r++; resume = p; }
            else if (star >= 0) { r = star + 1; p = ++resume; }
            else return false;
        }
        while (r < rule.length() && rule.charAt(r) == '*') r++;
        return r == rule.length();
    }
}
