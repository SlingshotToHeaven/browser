package com.privacy.browser.adblock;

import com.privacy.downloader.net.RequestContext;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

/** The defuser runs on these HTTPS origins only (including intentional /embed/ frames). */
final class YouTubeScope {
    static final Set<String> ORIGINS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "https://youtube.com", "https://www.youtube.com", "https://m.youtube.com",
            "https://www.youtube-nocookie.com", "https://youtube-nocookie.com")));

    private static final Set<String> HOSTS = new LinkedHashSet<>();
    static { for (String origin : ORIGINS) HOSTS.add(origin.substring("https://".length())); }

    static boolean hasHost(String lowercaseHost) { return HOSTS.contains(lowercaseHost); }

    private YouTubeScope() {}

    static String origin(String url) {
        String origin = RequestContext.origin(url);
        return ORIGINS.contains(origin) ? origin : null;
    }

    static Set<String> selectOrigins(boolean enabled, Predicate<String> allowed) {
        if (!enabled) return Collections.emptySet();
        Set<String> selected = new LinkedHashSet<>();
        for (String origin : ORIGINS) if (allowed.test(origin)) selected.add(origin);
        return selected;
    }

    private static String includesOrigin(Set<String> origins) {
        // Only fixed, app-owned strings from ORIGINS can enter the generated condition.
        StringBuilder condition = new StringBuilder();
        for (String origin : ORIGINS) {
            if (!origins.contains(origin)) continue;
            if (condition.length() > 0) condition.append("||");
            condition.append("location.origin==='").append(origin).append("'");
        }
        return condition.length() == 0 ? "false" : "(" + condition + ")";
    }

    static String wrap(Set<String> origins, Set<String> cosmeticOrigins, boolean crossSiteEmbeds,
                       boolean crossSiteCosmetics, String script) {
        String allowed = includesOrigin(origins);
        if ("false".equals(allowed)) return "";
        return "(function(){if(!" + allowed + ")return;"
                + "const page=new URL(location.href);if(page.username||page.password)return;"
                + "const embedded=window.top!==window;"
                + "if(embedded&&!location.pathname.startsWith('/embed/'))return;"
                // When there are site exceptions, do not guess a cross-origin ancestor's policy
                // from referrer or expose the list of exceptions in the registered script.
                + "let sameSite=!embedded;if(embedded){try{sameSite=window.top.location.origin===location.origin;}catch(e){}}"
                + "if(!sameSite&&!" + crossSiteEmbeds + ")return;"
                + "if(window.__privacyBrowserYouTubeInstalled)return;window.__privacyBrowserYouTubeInstalled=true;\n"
                + "(function(privacyBrowserYouTubeCosmetic){\n" + script + "\n})("
                + includesOrigin(cosmeticOrigins) + "&&(sameSite||" + crossSiteCosmetics + "));})();";
    }
}
