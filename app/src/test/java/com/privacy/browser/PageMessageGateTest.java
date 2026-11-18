package com.privacy.browser;

import org.junit.Test;

import static org.junit.Assert.*;

public class PageMessageGateTest {
    @Test public void homepageNeedsExactOriginPathAndMainFrame() {
        assertTrue(InternalHome.allowsMessage(true, InternalHome.ORIGIN, InternalHome.URL));
        assertFalse(InternalHome.allowsMessage(false, InternalHome.ORIGIN, InternalHome.URL));
        assertFalse(InternalHome.allowsMessage(true, "https://site.example", InternalHome.URL));
        assertFalse(InternalHome.allowsMessage(true, InternalHome.ORIGIN, InternalHome.URL + "other"));
        assertFalse(InternalHome.allowsMessage(true, InternalHome.ORIGIN, InternalHome.URL + "?other"));
        assertFalse(InternalHome.allowsMessage(true, InternalHome.ORIGIN, "file:///android_asset/home.html"));
        assertTrue(InternalHome.ownsHost("http://" + InternalHome.HOST + ":8080/no"));
        assertFalse(InternalHome.ownsHost("https://" + InternalHome.HOST + ".example/"));
        assertEquals(InternalHome.URL, InternalHome.migrate("file:///android_asset/home.html?old"));
        assertEquals("file:///android_asset/home.html.bak", InternalHome.migrate("file:///android_asset/home.html.bak"));
    }

    @Test public void rejectSubframesForeignOriginsStaleTokensAndOversize() {
        PageMessageGate gate = new PageMessageGate("https://example.org:8443");
        assertFalse(gate.allowsFrame(false, "https://example.org:8443", "{}"));
        assertFalse(gate.allowsFrame(true, "https://example.org", "{}"));
        assertFalse(gate.allowsFrame(true, "https://example.org:8443", " ".repeat(PageMessageGate.MAX_MESSAGE + 1)));
        assertTrue(gate.allowsFrame(true, "https://example.org:8443", "{}"));
        PageMessageGate reloaded = new PageMessageGate("https://example.org:8443");
        assertNotEquals(reloaded.token, gate.token);
        for (int i = 0; i < 32; i++) assertTrue(gate.admitsPayload(0));
        assertFalse(gate.admitsPayload(999));
        assertTrue(gate.admitsPayload(1000));
    }

    @Test public void rejectExcessiveNestingBeforeParsing() {
        assertTrue(PageMessageGate.shallowObject("{\"text\":\"[ [{ \\\" \"}"));
        assertFalse(PageMessageGate.shallowObject("{\"nested\":" + "[".repeat(50) + "]".repeat(50) + "}"));
        assertFalse(PageMessageGate.shallowObject("{[}"));
        assertFalse(PageMessageGate.shallowObject(""));
        assertFalse(PageMessageGate.shallowObject("{/* comment */}"));
    }

    @Test public void nativePickerActionIsOneShotTypedAndExpires() {
        PageMessageGate gate = new PageMessageGate("https://example.org");
        assertFalse(gate.consumePicker("image", "fake", 10));
        String ticket = gate.armPicker("image", 10);
        assertFalse(gate.consumePicker("element", ticket, 11));
        assertFalse(gate.consumePicker("image", "fake", 11));
        assertTrue(gate.consumePicker("image", ticket, 11));
        assertFalse(gate.consumePicker("image", ticket, 12));
        String next = gate.armPicker("element", 10);
        assertFalse(gate.consumePicker("element", next, 60_010));
        gate.cancelPicker();
        assertFalse(gate.consumePicker("element", next, 11));
    }
}
