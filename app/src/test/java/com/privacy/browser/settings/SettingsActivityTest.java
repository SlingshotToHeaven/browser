package com.privacy.browser.settings;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SettingsActivityTest {

    @Test
    public void testFloatingButtonKeyConstant() {
        assertEquals("pref_floating_download_button", SettingsActivity.KEY_FLOATING_DOWNLOAD_BUTTON);
    }

    @Test
    public void testFloatingButtonDefaultWhenContextNull() {
        assertTrue(SettingsActivity.isFloatingDownloadButtonEnabled(null));
    }
}
