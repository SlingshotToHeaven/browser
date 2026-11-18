package com.privacy.downloader.provider;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;

/** Build temporary read grants for the chosen recipient; never grant a tree or write access. */
public final class DownloadFileIntents {
    private static final int READ = Intent.FLAG_GRANT_READ_URI_PERMISSION;

    private DownloadFileIntents() {}

    public static Intent view(Context context, String id) throws FileNotFoundException {
        CompletedDownloadProvider.FileInfo file = CompletedDownloadProvider.forJob(context, id);
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(file.uri, file.mime);
        view.setClipData(new ClipData(file.name, new String[]{file.mime}, new ClipData.Item(file.uri)));
        return view.addFlags(READ | Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    public static Intent share(Context context, List<String> ids) throws FileNotFoundException {
        if (ids.isEmpty()) throw new FileNotFoundException("Select at least one completed download to share.");
        ArrayList<Uri> uris = new ArrayList<>(ids.size());
        String mime = null;
        String name = null;
        for (String id : ids) {
            CompletedDownloadProvider.FileInfo file = CompletedDownloadProvider.forJob(context, id);
            name = file.name;
            mime = mime == null || mime.equals(file.mime) ? file.mime : "*/*";
            uris.add(file.uri);
        }
        ClipData clip = new ClipData("Downloaded files", new String[]{mime}, new ClipData.Item(uris.get(0)));
        for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
        boolean single = uris.size() == 1;
        Intent send = new Intent(single ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE).setType(mime);
        if (single) {
            send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            send.putExtra(Intent.EXTRA_SUBJECT, name);
        } else {
            send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        send.setClipData(clip);
        send.addFlags(READ);
        Intent chooser = Intent.createChooser(send, single ? "Share " + name : "Share " + uris.size() + " files");
        chooser.setClipData(clip);
        return chooser.addFlags(READ);
    }
}
