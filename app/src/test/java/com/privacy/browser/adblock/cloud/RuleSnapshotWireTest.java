package com.privacy.browser.adblock.cloud;

import com.privacy.browser.adblock.rules.FilterSnapshot;
import com.privacy.browser.adblock.rules.FilterSnapshot.Part;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.util.EnumMap;

import static org.junit.Assert.*;

public class RuleSnapshotWireTest {
    private static final String ID = "v2_" + "0".repeat(64);

    @Test public void appWireSchemaRoundTripsWithoutNormalizingRuleText() throws Exception {
        EnumMap<Part, String> text = new EnumMap<>(Part.class);
        text.put(Part.HOSTS, "# comment\r\ntracker.example.invalid\n");
        text.put(Part.NETWORK, "@@||example.invalid/Allow%2Fthis?ID=1^");
        text.put(Part.COSMETIC, "##.ad, [data-slot=\"a,b\"]");
        text.put(Part.USER_FILTERS, "www.example.invalid##:is(.a, .b)\r\n");
        JSONObject encoded = RuleSnapshotWire.encode("Fixture", FilterSnapshot.parse(text, true));
        JSONObject reply = new JSONObject(encoded.toString()).put("state_id", ID).put("timestamp", 1);
        FilterSnapshot decoded = RuleSnapshotWire.decode(reply, ID);
        for (Part part : Part.values()) assertEquals(text.get(part), decoded.text(part));
    }

    @Test public void rejectsLegacyDiffsScriptDestinationsAndUnsupportedSections() throws Exception {
        assertThrows(IOException.class, () -> RuleSnapshotWire.decode(new JSONObject("{\"diffs\":{}}"), ID));
        JSONObject files = new JSONObject().put("hosts", "").put("network", "").put("cosmetic", "")
                .put("../scriptlets/fixture.js", "// test data");
        JSONObject reply = new JSONObject().put("version", 2).put("files", files).put("description", "Fixture")
                .put("state_id", ID).put("timestamp", 1);
        assertThrows(IOException.class, () -> RuleSnapshotWire.decode(reply, ID));
        files.remove("../scriptlets/fixture.js");
        files.put("user_filters", "example.invalid##+js(fixture)");
        assertThrows(IllegalArgumentException.class, () -> RuleSnapshotWire.decode(reply, ID));
        assertThrows(IOException.class, () -> RuleSnapshotWire.decode(reply, "v2_" + "1".repeat(64)));
    }
}
