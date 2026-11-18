package com.privacy.browser.adblock;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.*;

public class ScriptletMigrationTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void onlyExactStockBytesPermitReplacement() throws Exception {
        File path = temp.newFile("cosmetic_pruner.js");
        byte[] stock = "// shipped default\n".getBytes(UTF_8);
        StringBuilder digest = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(stock)) digest.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        Files.write(path.toPath(), stock);
        assertTrue(AdBlockFileManager.matchesStock(path, stock.length, digest.toString()));
        for (String edited : new String[] {"// shipped custom!\n", "// shipped default", "// shipped default\r\n", "// shipped default\n\n"}) {
            Files.write(path.toPath(), edited.getBytes(UTF_8));
            assertFalse(AdBlockFileManager.matchesStock(path, stock.length, digest.toString()));
            assertEquals(edited, new String(Files.readAllBytes(path.toPath()), UTF_8));
        }
    }
}
