package com.privacy.downloader.provider;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.webkit.MimeTypeMap;

import com.privacy.downloader.db.DownloadDbHelper;
import com.privacy.downloader.db.DownloadDbHelper.PublishedDownload;
import com.privacy.downloader.util.DownloadDestination;
import com.privacy.downloader.util.PublishedFileIdentity;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Read-only, exact-URI access to a committed download. There are no filesystem roots to browse. */
public final class CompletedDownloadProvider extends ContentProvider {
    // Bionic's Linux open flag on Oreo (asm-generic/fcntl.h). API 26 accepts it
    // in Os.open but exposes neither OsConstants.O_CLOEXEC nor public Os.fcntlInt.
    private static final int O_CLOEXEC_API_26 = 02000000;
    private static final String AUTHORITY_SUFFIX = ".completed-downloads";
    private static final String UNAVAILABLE = "Completed file is missing, has changed, or was saved by an older version.";
    private static final Pattern ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern MIME = Pattern.compile("[a-zA-Z0-9_.+-]+/[a-zA-Z0-9_.+-]+");

    static final class FileInfo {
        final Uri uri;
        final String name;
        final String mime;

        FileInfo(Uri uri, PublishedDownload row) {
            this.uri = uri;
            this.name = row.name;
            this.mime = mimeType(row);
        }
    }

    static FileInfo forJob(Context context, String id) throws FileNotFoundException {
        PublishedDownload row = validId(id) ? DownloadDbHelper.getInstance(context).getPublishedDownload(id) : null;
        if (row == null || !validId(row.token)) throw unavailable();
        String path = checkedPath(row);
        if (!row.identity.equals(PublishedFileIdentity.capture(path, row.size))) throw unavailable();
        return new FileInfo(uri(context, id, row.token), row);
    }

    private static Uri uri(Context context, String id, String token) {
        return new Uri.Builder().scheme("content").authority(context.getPackageName() + AUTHORITY_SUFFIX)
                .appendPath("file").appendPath(id).appendPath(token).build();
    }

    private static boolean validId(String id) { return id != null && ID.matcher(id).matches(); }

    @Override public boolean onCreate() { return true; }

    private PublishedDownload resolve(Uri uri) throws FileNotFoundException {
        List<String> parts = uri.getPathSegments();
        if (parts.size() != 3 || !validId(parts.get(1)) || !validId(parts.get(2))
                || !uri(getContext(), parts.get(1), parts.get(2)).equals(uri)) throw unavailable();
        PublishedDownload row = DownloadDbHelper.getInstance(getContext()).getPublishedDownload(parts.get(1));
        if (row == null || !parts.get(2).equals(row.token)) throw unavailable();
        return row;
    }

    private boolean hasReadGrant(Uri uri) {
        int uid = Binder.getCallingUid();
        return uid == Process.myUid() || getContext().checkUriPermission(uri, Binder.getCallingPid(), uid,
                Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requireReadGrant(Uri uri) {
        if (!hasReadGrant(uri)) throw new SecurityException("A read grant for this file is required.");
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        requireReadGrant(uri);
        if (!"r".equals(mode)) throw new FileNotFoundException("Downloads can only be opened for reading.");
        DownloadDbHelper db = DownloadDbHelper.getInstance(getContext());
        synchronized (db) { // A row removed from history cannot authorize a new open.
            PublishedDownload row = resolve(uri);
            String path = checkedPath(row);
            if (!row.identity.equals(PublishedFileIdentity.capture(path, row.size))) throw unavailable();
            FileDescriptor fd = null;
            try {
                int closeOnExec = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
                        ? OsConstants.O_CLOEXEC : O_CLOEXEC_API_26;
                fd = Os.open(path, OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW
                        | OsConstants.O_NONBLOCK | closeOnExec, 0);
                // Validate the opened descriptor, not just the name: removable/shared folders can change.
                if (!row.identity.equals(PublishedFileIdentity.of(Os.fstat(fd), row.size))) throw unavailable();
                return ParcelFileDescriptor.dup(fd);
            } catch (ErrnoException | IOException e) {
                throw unavailable();
            } finally {
                if (fd != null) try { Os.close(fd); } catch (ErrnoException ignored) {}
            }
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        requireReadGrant(uri);
        if (selection != null || (args != null && args.length != 0) || order != null) throw new IllegalArgumentException("Only a file URI is supported.");
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor result = new MatrixCursor(columns, 1);
        try {
            PublishedDownload row = resolve(uri);
            if (!row.identity.equals(PublishedFileIdentity.capture(checkedPath(row), row.size))) return result;
            Object[] values = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = row.name;
                if (OpenableColumns.SIZE.equals(columns[i])) values[i] = row.size;
            }
            result.addRow(values);
        } catch (FileNotFoundException ignored) {}
        return result;
    }

    @Override public String getType(Uri uri) {
        if (!hasReadGrant(uri)) return null;
        try { return mimeType(resolve(uri)); } catch (FileNotFoundException e) { return null; }
    }

    private static String mimeType(PublishedDownload row) {
        if (row.mime != null && MIME.matcher(row.mime).matches()) return row.mime.toLowerCase(Locale.ROOT);
        int dot = row.name == null ? -1 : row.name.lastIndexOf('.');
        String type = dot < 0 ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(row.name.substring(dot + 1).toLowerCase(Locale.ROOT));
        return type != null ? type : "application/octet-stream";
    }

    private static String checkedPath(PublishedDownload row) throws FileNotFoundException {
        if (row.path == null) throw unavailable();
        File file = new File(row.path);
        try {
            DownloadDestination.requireFileName(row.name);
            if (file.isAbsolute() && row.name.equals(file.getName()) && row.key.equals(DownloadDestination.key(file))) return file.getPath();
        } catch (IOException | SecurityException ignored) {}
        throw unavailable();
    }

    private static FileNotFoundException unavailable() { return new FileNotFoundException(UNAVAILABLE); }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("Read only"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("Read only"); }
}
