package com.privacy.imagesearch.ui;

import org.junit.Test;
import static org.junit.Assert.*;

public class ImageSearchUrlTest {
    @Test public void preservesEncodedPathAndQueryForReview() {
        assertEquals("https://example.invalid/Signed%2FPhoto?ID=Case", ImageSearchUrl.normalize(" https://example.invalid/Signed%2FPhoto?ID=Case "));
    }

    @Test public void blocksLocalAndInlinePayloadsAndUserInfo() {
        for (String address : new String[]{"data:image/png;base64,AAAA", "content://images/fixture", "file:///test.jpg",
                "blob:https://example.invalid/fixture", "https://user:password@example.invalid/a", "https://example.invalid:65536/a",
                "https://example.invalid/\u202ephoto", "https://example.invalid/" + "a".repeat(2048)}) {
            assertNull(ImageSearchUrl.normalize(address));
        }
    }
}
