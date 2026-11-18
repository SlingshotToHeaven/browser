package com.privacy.browser.ai;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeNoException;

public class AgentLegacyFilesTest {
    private static final String USED = "img_12345678-1234-1234-1234-000000000001.webp";
    private static final String UNUSED = "img_12345678-1234-1234-1234-000000000002.png";
    private static final String OTHER = "img_12345678-1234-1234-1234-000000000003.jpeg";
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    @Test public void explicitSweepPreservesReferencesAliasesAndUnknownFiles() throws Exception {
        File root = temp.newFolder("files");
        Path dir = Files.createDirectory(root.toPath().resolve("agent_images"));
        Path used = write(dir.resolve(USED));
        Path unused = write(dir.resolve(UNUSED));
        Path unrelated = write(dir.resolve("note.txt"));
        Path malformed = write(dir.resolve("img_------------------------------------.png"));
        Path folder = Files.createDirectory(dir.resolve(OTHER));
        write(folder.resolve("nested.png"));
        Path external = write(root.toPath().resolve(UNUSED));

        // Old app data can name the same attachment under a different spelling of its path.
        String referenced = AgentLegacyFiles.referenceName("/different/private/files/agent_images/" + USED);
        assertEquals(USED, referenced);
        AgentLegacyFiles.Sweep result = AgentLegacyFiles.removeUnused(root, Set.of(referenced), null);
        assertTrue(result.complete);
        assertEquals(1, result.removed);
        assertFalse(Files.exists(unused));
        for (Path entry : new Path[]{used, unrelated, malformed, folder.resolve("nested.png"), external}) {
            assertTrue("Unrelated or referenced file was not retained", Files.isRegularFile(entry));
        }
        assertEquals(0, AgentLegacyFiles.removeUnused(root, Set.of(referenced), null).removed);
    }

    @Test public void targetedDeletionDoesNotSweepUnrelatedOrphans() throws Exception {
        File root = temp.newFolder("files");
        Path dir = Files.createDirectory(root.toPath().resolve("agent_images"));
        Path shared = write(dir.resolve(USED));
        Path deletedChat = write(dir.resolve(UNUSED));
        Path other = write(dir.resolve(OTHER));
        AgentLegacyFiles.Sweep result = AgentLegacyFiles.removeUnused(root, Set.of(USED), Set.of(USED, UNUSED, "../" + OTHER));
        assertTrue(result.complete);
        assertEquals(1, result.removed);
        assertTrue(Files.exists(shared));
        assertFalse(Files.exists(deletedChat));
        assertTrue(Files.exists(other));
    }

    @Test public void incompleteSnapshotCannotDeleteFilesAndAbsentDirectoryIsFine() throws Exception {
        File root = temp.newFolder("files");
        assertTrue(AgentLegacyFiles.removeUnused(root, Collections.emptySet(), null).complete);
        assertFalse(Files.exists(root.toPath().resolve("agent_images")));
        Path dir = Files.createDirectory(root.toPath().resolve("agent_images"));
        Path file = write(dir.resolve(UNUSED));
        AgentLegacyFiles.Sweep result = AgentLegacyFiles.removeUnused(root, null, null);
        assertFalse(result.complete);
        assertEquals(0, result.removed);
        assertTrue(Files.exists(file));
    }

    @Test public void previewAndSweepNeverFollowLinksInsideTheAttachmentDirectory() throws Exception {
        File root = temp.newFolder("files");
        Path dir = Files.createDirectory(root.toPath().resolve("agent_images"));
        Path used = write(dir.resolve(USED));
        Path other = write(root.toPath().resolve(UNUSED));
        Path link = dir.resolve(UNUSED);
        symlink(link, other);
        assertNull(AgentLegacyFiles.previewFile(root, link.toString()));
        assertNull(AgentLegacyFiles.previewFile(root, other.toString()));
        assertNull(AgentLegacyFiles.previewFile(root, USED));
        assertEquals(used.toFile().getCanonicalFile().toPath(), AgentLegacyFiles.previewFile(root, used.toString()));
        AgentLegacyFiles.Sweep result = AgentLegacyFiles.removeUnused(root, Set.of(USED), null);
        assertTrue(result.complete);
        assertEquals(0, result.removed);
        assertTrue(Files.exists(link, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isRegularFile(other));
    }

    @Test public void aSymlinkInPlaceOfTheDirectoryCannotAuthorizeCleanup() throws Exception {
        File root = temp.newFolder("files");
        Path foreignDir = temp.newFolder("other_area").toPath();
        Path external = write(foreignDir.resolve(UNUSED));
        symlink(root.toPath().resolve("agent_images"), foreignDir);
        AgentLegacyFiles.Sweep result = AgentLegacyFiles.removeUnused(root, Collections.emptySet(), null);
        assertFalse(result.complete);
        assertEquals(0, result.removed);
        assertTrue(Files.isRegularFile(external));
    }

    private static Path write(Path path) throws IOException { return Files.write(path, new byte[]{42}); }

    private static void symlink(Path link, Path target) {
        try { Files.createSymbolicLink(link, target); }
        catch (IOException | UnsupportedOperationException | SecurityException e) { assumeNoException(e); }
    }
}
