package com.privacy.downloader.net;

import java.util.function.Function;

/** Supply destination-specific synthetic cookies without initializing WebView in a host test. */
public final class RequestContextFixture {
    private RequestContextFixture() {}

    public static RequestContext browser(String page, String ua, Function<String, String> cookies) {
        return RequestContext.fromBrowser(page, ua, false, cookies::apply);
    }
}
