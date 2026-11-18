package com.privacy.imagesearch.ui;

import java.net.URI;

final class ImageSearchUrl {
    static final int MAX_LENGTH = 2048;

    /** A URL search never turns an inline image, local file, or credential into a provider query. */
    static String normalize(String address) {
        if (address == null || address.length() > MAX_LENGTH || address.codePoints().anyMatch(c ->
                Character.isISOControl(c) || Character.getType(c) == Character.FORMAT || Character.getType(c) == Character.SURROGATE)) return null;
        try {
            URI url = URI.create(address.trim());
            String scheme = url.getScheme();
            if ((!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) || url.getHost() == null
                    || url.getRawUserInfo() != null || url.getPort() == 0 || url.getPort() > 65535) return null;
            String ascii = url.toASCIIString();
            return ascii.length() <= MAX_LENGTH ? ascii : null;
        } catch (IllegalArgumentException invalid) { return null; }
    }
}
