package com.privacy.browser.downloads;

import android.app.Instrumentation;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import androidx.test.platform.app.InstrumentationRegistry;

import com.privacy.downloader.db.DownloadDbHelper;
import com.privacy.downloader.engine.DownloadTask;
import com.privacy.downloader.model.DownloadItem;
import com.privacy.downloader.model.DownloadStatus;
import com.privacy.downloader.provider.DownloadFileIntents;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Run on a disposable Android emulator/device. This uses the real provider, SQLite and OS file descriptors. */
public class CompletedDownloadGrantTest {
    private static final int READ = Intent.FLAG_GRANT_READ_URI_PERMISSION;
    private static final int GRANTS = READ | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
    private static final String PAYLOAD = "completed-download-grant-fixture";
    private static final byte[] BYTES = PAYLOAD.getBytes(StandardCharsets.US_ASCII);
    private static final String SHELL_PACKAGE = "com.android.shell";
    private final List<String> jobs = new ArrayList<>();
    private Instrumentation instrumentation;
    private Context context;
    private DownloadDbHelper db;
    private File directory;

    @Before public void setup() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        db = DownloadDbHelper.getInstance(context);
        directory = Files.createTempDirectory(context.getCacheDir().toPath(), "grant-test-").toFile();
    }

    @After public void cleanup() throws Exception {
        for (String id : jobs) {
            DownloadItem item = db.getDownload(id);
            if (item != null) db.deleteDownload(item, false);
        }
        if (directory != null) {
            File[] children = directory.listFiles();
            if (children != null) for (File child : children) Files.deleteIfExists(child.toPath());
            Files.delete(directory.toPath());
        }
    }

    @Test public void viewAndSingleShareUseTheSameReadOnlyCompletedFile() throws Exception {
        DownloadItem item = publish("sample.txt");
        Intent view = DownloadFileIntents.view(context, item.getId());
        Uri uri = view.getData();
        assertEquals(Intent.ACTION_VIEW, view.getAction());
        assertEquals("content", uri.getScheme());
        assertEquals(context.getPackageName() + ".completed-downloads", uri.getAuthority());
        assertFalse(uri.toString().contains("sample.txt"));
        assertEquals("text/plain", view.getType());
        assertEquals(READ, view.getFlags() & GRANTS);
        assertEquals(uri, view.getClipData().getItemAt(0).getUri());
        assertEquals("text/plain", context.getContentResolver().getType(uri));
        assertBytes(uri);
        assertThrows(FileNotFoundException.class, () -> context.getContentResolver().openFileDescriptor(uri, "w"));
        try (Cursor info = context.getContentResolver().query(uri, null, null, null, null)) {
            assertNotNull(info);
            assertTrue(info.moveToFirst());
            assertEquals("sample.txt", info.getString(info.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)));
            assertEquals(BYTES.length, info.getLong(info.getColumnIndexOrThrow(OpenableColumns.SIZE)));
            assertEquals(-1, info.getColumnIndex("_data"));
        }

        Intent chooser = DownloadFileIntents.share(context, Arrays.asList(item.getId()));
        Intent share = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
        assertEquals(Intent.ACTION_SEND, share.getAction());
        assertEquals(uri, share.getParcelableExtra(Intent.EXTRA_STREAM));
        assertClipAndReadFlags(chooser, uri);
        assertClipAndReadFlags(share, uri);
    }

    @Test public void multiShareListsEachExactFileAndNeverIncludesPartials() throws Exception {
        DownloadItem first = publish("one.txt"), second = publish("two.txt");
        DownloadItem pending = reserve("waiting.txt");
        assertThrows(FileNotFoundException.class, () -> DownloadFileIntents.view(context, pending.getId()));
        assertThrows(FileNotFoundException.class, () -> DownloadFileIntents.share(context, Arrays.asList(first.getId(), pending.getId())));
        Intent chooser = DownloadFileIntents.share(context, Arrays.asList(first.getId(), second.getId()));
        Intent send = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
        assertEquals(Intent.ACTION_SEND_MULTIPLE, send.getAction());
        List<Uri> streams = send.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        Uri one = DownloadFileIntents.view(context, first.getId()).getData();
        Uri two = DownloadFileIntents.view(context, second.getId()).getData();
        assertEquals(Arrays.asList(one, two), streams);
        assertClipAndReadFlags(send, one, two);
        assertClipAndReadFlags(chooser, one, two);
        assertBytes(one);
        assertBytes(two);
    }

    @Test public void issuedUriRejectsReplacementLinksAndNonCanonicalUri() throws Exception {
        DownloadItem item = publish("original.txt");
        Uri uri = DownloadFileIntents.view(context, item.getId()).getData();
        Uri tampered = uri.buildUpon().appendQueryParameter("path", "some-other-file").build();
        assertThrows(FileNotFoundException.class, () -> context.getContentResolver().openFileDescriptor(tampered, "r"));
        Path path = new File(item.getDestinationPath()).toPath();
        Path moved = path.resolveSibling("moved.txt");
        Files.move(path, moved);
        Files.write(path, BYTES, StandardOpenOption.CREATE_NEW); // even the same bytes are not the committed file
        assertThrows(FileNotFoundException.class, () -> DownloadFileIntents.view(context, item.getId()));
        assertThrows(FileNotFoundException.class, () -> context.getContentResolver().openFileDescriptor(uri, "r"));
        Files.delete(path);
        Files.createSymbolicLink(path, moved);
        assertThrows(FileNotFoundException.class, () -> context.getContentResolver().openFileDescriptor(uri, "r"));
        Files.delete(path);
        Files.move(moved, path);
        assertBytes(uri);
        assertTrue(db.deleteDownload(db.getDownload(item.getId()), false));
        assertThrows(FileNotFoundException.class, () -> context.getContentResolver().openFileDescriptor(uri, "r"));
        assertTrue(Files.isRegularFile(path));
    }

    @Test public void independentShellUidCanReadOnlyTheGrantedFileUntilRevoked() throws Exception {
        assumeTrue("Use an unrooted shell (uid 2000)", "2000".equals(shell("id -u").trim()));
        Uri granted = DownloadFileIntents.view(context, publish("grant.txt").getId()).getData();
        Uri other = DownloadFileIntents.view(context, publish("other.txt").getId()).getData();
        assertFalse(shell("content read --uri " + granted).contains(PAYLOAD));
        context.grantUriPermission(SHELL_PACKAGE, granted, READ);
        try {
            assertEquals(PAYLOAD, shell("content read --uri " + granted));
            assertFalse(shell("content read --uri " + other).contains(PAYLOAD));
            // A read grant cannot authorize append/truncate even for a valid URI.
            shell("content update --uri " + granted + " --bind fixture:s:denied");
            assertBytes(granted);
        } finally {
            context.revokeUriPermission(granted, READ);
        }
        assertFalse(shell("content read --uri " + granted).contains(PAYLOAD));
    }

    private DownloadItem reserve(String name) throws Exception {
        DownloadItem item = new DownloadItem();
        item.setId(UUID.randomUUID().toString());
        item.setUrl("data:text/plain," + PAYLOAD);
        item.setMimeType("text/plain");
        item.setFileName(name);
        item.setDestinationPath(new File(directory, name).getPath());
        db.enqueueDownload(item, new File(item.getDestinationPath()));
        jobs.add(item.getId());
        return item;
    }

    private DownloadItem publish(String name) throws Exception {
        DownloadItem item = reserve(name);
        assertTrue(db.claim(item, new File(item.getDestinationPath()), UUID.randomUUID().toString()));
        new DownloadTask(item, db, null, null).run();
        assertEquals(item.getErrorMessage(), DownloadStatus.COMPLETED, item.getStatus());
        return item;
    }

    private void assertClipAndReadFlags(Intent intent, Uri... uris) {
        assertEquals(READ, intent.getFlags() & GRANTS);
        ClipData clip = intent.getClipData();
        assertEquals(uris.length, clip.getItemCount());
        for (int i = 0; i < uris.length; i++) assertEquals(uris[i], clip.getItemAt(i).getUri());
    }

    private void assertBytes(Uri uri) throws Exception {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            assertNotNull(in);
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] block = new byte[512];
            for (int count; (count = in.read(block)) != -1; ) buffer.write(block, 0, count);
            assertArrayEquals(BYTES, buffer.toByteArray());
        }
    }

    private String shell(String command) throws Exception {
        try (InputStream stdout = new ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation().executeShellCommand(command))) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] block = new byte[512];
            for (int count; (count = stdout.read(block)) != -1; ) buffer.write(block, 0, count);
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
