package com.browser.fixture.receiver;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Offline emulator-only app. It never asks the browser or shell to grant access. */
public final class FixtureReceiver {
    private static final String ACTION_PROBE = "com.browser.fixture.receiver.PROBE";
    private static final String AUTHORITY = "com.browser.completed-downloads";
    private static final String RESULT = "result.json";
    private static final int READ = Intent.FLAG_GRANT_READ_URI_PERMISSION;
    private static final int WRITE = Intent.FLAG_GRANT_WRITE_URI_PERMISSION;

    public static final class Receive extends Activity {
        @Override public void onCreate(Bundle state) {
            super.onCreate(state);
            TextView text = new TextView(this);
            text.setText("Fixture Receiver");
            setContentView(text);
            receive(getIntent());
        }

        @Override public void onNewIntent(Intent intent) {
            super.onNewIntent(intent);
            setIntent(intent);
            receive(intent);
        }

        private void receive(Intent intent) {
            new Thread(() -> record(this, "delivery", intent)).start();
        }
    }

    public static final class Probe extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (!ACTION_PROBE.equals(intent.getAction())) return;
            String phase = intent.getStringExtra("phase");
            if (phase == null || !List.of("before", "replaced", "removed").contains(phase)) return;
            var pending = goAsync();
            new Thread(() -> {
                try { record(context, phase, null); }
                finally { pending.finish(); }
            }).start();
        }
    }

    private static void record(Context context, String phase, Intent delivery) {
        try {
            JSONObject config = new JSONObject(new String(Files.readAllBytes(context.getFileStreamPath("probe.json").toPath()),
                    StandardCharsets.UTF_8));
            JSONObject known = config.getJSONObject("uris");
            JSONObject receipt = new JSONObject().put("phase", phase).put("uid", Process.myUid());
            if (delivery != null) {
                List<Uri> streams = streams(delivery);
                ClipData clip = delivery.getClipData();
                if (clip == null || clip.getItemCount() != streams.size()) throw new IOException("Unexpected ClipData");
                Set<String> labels = new HashSet<>();
                for (int i = 0; i < streams.size(); i++) {
                    Uri uri = streams.get(i);
                    if (!uri.equals(clip.getItemAt(i).getUri()) || !labels.add(label(known, uri))) {
                        throw new IOException("Unexpected or duplicate delivered URI");
                    }
                }
                receipt.put("action", delivery.getAction()).put("selected", new JSONArray(labels));
            }
            JSONObject access = new JSONObject();
            for (Iterator<String> i = known.keys(); i.hasNext();) {
                String name = i.next();
                Uri uri = Uri.parse(known.getString(name));
                if (!"content".equals(uri.getScheme()) || !AUTHORITY.equals(uri.getAuthority())) throw new IOException("Not a fixture URI");
                access.put(name, inspect(context, uri));
            }
            receipt.put("access", access).put("privateStage", privateStage(config.getString("privateStage")));
            write(context, receipt);
        } catch (Exception e) {
            try { write(context, new JSONObject().put("phase", phase).put("error", e.getClass().getSimpleName())); }
            catch (Exception ignored) { /* The host must fail if no receipt is written. */ }
        }
    }

    @SuppressWarnings("deprecation")
    private static List<Uri> streams(Intent intent) throws IOException {
        String action = intent.getAction();
        List<Uri> result;
        if (Intent.ACTION_VIEW.equals(action)) result = List.of(intent.getData());
        else if (Intent.ACTION_SEND.equals(action)) result = List.of((Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM));
        else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) result = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        else throw new IOException("Wrong delivery action");
        if (result == null || result.isEmpty() || result.size() > 2) throw new IOException("Wrong stream count");
        return new ArrayList<>(result);
    }

    private static String label(JSONObject known, Uri uri) throws Exception {
        for (Iterator<String> i = known.keys(); i.hasNext();) {
            String key = i.next();
            if (known.getString(key).equals(uri.toString())) return key;
        }
        throw new IOException("Unknown delivered URI");
    }

    private static JSONObject inspect(Context context, Uri uri) throws Exception {
        JSONObject result = new JSONObject().put("readGrant", granted(context, uri, READ))
                .put("writeGrant", granted(context, uri, WRITE));
        try (ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "rw")) {
            // 'rw' requests access without truncation; never actually write to the test file.
            if (fd == null) throw new IOException("Null write descriptor");
            result.put("write", "opened");
        } catch (SecurityException e) { result.put("write", "denied"); }
        catch (FileNotFoundException e) { result.put("write", "missing"); }
        try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
            if (stream == null) throw new IOException("Null read descriptor");
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            byte[] bytes = new byte[8192];
            int total = 0;
            for (int count; (count = stream.read(bytes)) != -1;) {
                total += count;
                if (total > 1024 * 1024) throw new IOException("Fixture receipt is too large");
                hash.update(bytes, 0, count);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : hash.digest()) hex.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            try (Cursor row = context.getContentResolver().query(uri, null, null, null, null)) {
                if (row == null || !row.moveToFirst() || row.getCount() != 1 || row.getColumnIndex("_data") != -1
                        || row.getLong(row.getColumnIndexOrThrow(OpenableColumns.SIZE)) != total) throw new IOException("Wrong file metadata");
                result.put("name", row.getString(row.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)));
            }
            result.put("read", "opened").put("bytes", total).put("sha256", hex.toString());
        } catch (SecurityException e) { result.put("read", "denied"); }
        catch (FileNotFoundException e) { result.put("read", "missing"); }
        return result;
    }

    private static boolean granted(Context context, Uri uri, int flag) {
        return context.checkUriPermission(uri, Process.myPid(), Process.myUid(), flag) == PackageManager.PERMISSION_GRANTED;
    }

    private static String privateStage(String path) throws Exception {
        if (!path.matches("/data/(user/0|data)/com\\.browser/files/download-publication/download-upgrade-paused\\.part")) {
            throw new IOException("Not the paused fixture's private file");
        }
        try {
            FileDescriptor fd = Os.open(path, OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW, 0);
            Os.close(fd);
            return "opened";
        } catch (ErrnoException e) {
            if (e.errno == OsConstants.EACCES || e.errno == OsConstants.EPERM) return "denied";
            if (e.errno == OsConstants.ENOENT) return "hidden";
            throw e;
        }
    }

    private static void write(Context context, JSONObject receipt) throws IOException {
        var temp = context.getFileStreamPath("result.tmp").toPath();
        byte[] data = receipt.toString().getBytes(StandardCharsets.UTF_8);
        if (data.length > 3072) throw new IOException("Receipt exceeds limit");
        Files.write(temp, data);
        Files.move(temp, context.getFileStreamPath(RESULT).toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
