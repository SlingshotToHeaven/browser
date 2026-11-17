package com.privacy.downloader.interceptor;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.WebView;

import com.privacy.downloader.db.DownloadDbHelper;
import com.privacy.downloader.engine.BlobDownloadTask;
import com.privacy.downloader.engine.DownloadTask;
import com.privacy.downloader.engine.SpeedLimiter;
import com.privacy.downloader.model.DownloadItem;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BlobDownloadManager implements BlobDownloadBridge.BlobListener {

    private static BlobDownloadManager instance;
    private final Context context;
    private final BlobDownloadBridge bridge;
    private final Handler mainHandler;

    private static class BlobSession {
        final String blobId;
        final String blobUrl;
        long totalSize = -1;
        String mimeType = "";
        File bufferedTempFile;
        FileOutputStream bufferedOut;
        boolean isComplete = false;
        String failedError = null;
        BlobDownloadTask linkedTask = null;

        BlobSession(String blobId, String blobUrl, File tempFile) {
            this.blobId = blobId;
            this.blobUrl = blobUrl;
            this.bufferedTempFile = tempFile;
        }

        synchronized void closeBufferedStream() {
            if (bufferedOut != null) {
                try {
                    bufferedOut.flush();
                    bufferedOut.close();
                } catch (Exception ignored) {}
                bufferedOut = null;
            }
        }
    }

    private final Map<String, BlobSession> sessionsByBlobId = new ConcurrentHashMap<>();
    private final Map<String, BlobSession> sessionsByUrl = new ConcurrentHashMap<>();

    public static synchronized BlobDownloadManager getInstance(Context context) {
        if (instance == null && context != null) {
            instance = new BlobDownloadManager(context.getApplicationContext());
        }
        return instance;
    }

    private BlobDownloadManager(Context context) {
        this.context = context;
        this.bridge = new BlobDownloadBridge(this);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public BlobDownloadBridge getBridge() {
        return bridge;
    }

    public synchronized void prepareBlob(WebView webView, String blobUrl) {
        if (webView == null || blobUrl == null || !blobUrl.startsWith("blob:")) return;

        String blobId = "blob_" + Math.abs(blobUrl.hashCode()) + "_" + System.currentTimeMillis();
        File tempFile = new File(context.getCacheDir(), blobId + ".part");
        BlobSession session = new BlobSession(blobId, blobUrl, tempFile);

        sessionsByBlobId.put(blobId, session);
        sessionsByUrl.put(blobUrl, session);

        // Inject JavaScript to fetch and stream blob in 512KB slices immediately
        String js = String.format(
                "(function() {" +
                "  var blobUrl = '%s';" +
                "  var blobId = '%s';" +
                "  fetch(blobUrl)" +
                "    .then(function(res) {" +
                "      if (!res.ok) throw new Error('HTTP ' + res.status);" +
                "      return res.blob();" +
                "    })" +
                "    .then(function(blob) {" +
                "      var totalSize = blob.size;" +
                "      var mime = blob.type || '';" +
                "      if (window.BlobDownloadBridge) {" +
                "        window.BlobDownloadBridge.onBlobReady(blobId, totalSize, mime);" +
                "      }" +
                "      var CHUNK_SIZE = 524288;" +
                "      var offset = 0;" +
                "      function readSlice() {" +
                "        if (offset >= totalSize) {" +
                "          if (window.BlobDownloadBridge) window.BlobDownloadBridge.onBlobEnd(blobId);" +
                "          return;" +
                "        }" +
                "        var end = Math.min(offset + CHUNK_SIZE, totalSize);" +
                "        var slice = blob.slice(offset, end);" +
                "        var reader = new FileReader();" +
                "        reader.onload = function(e) {" +
                "          var bytes = new Uint8Array(e.target.result);" +
                "          var binary = '';" +
                "          var len = bytes.byteLength;" +
                "          var step = 8192;" +
                "          for (var i = 0; i < len; i += step) {" +
                "            binary += String.fromCharCode.apply(null, bytes.subarray(i, Math.min(i + step, len)));" +
                "          }" +
                "          var base64 = btoa(binary);" +
                "          if (window.BlobDownloadBridge) {" +
                "            window.BlobDownloadBridge.onBlobChunk(blobId, base64, offset, totalSize);" +
                "          }" +
                "          offset = end;" +
                "          readSlice();" +
                "        };" +
                "        reader.onerror = function() {" +
                "          if (window.BlobDownloadBridge) window.BlobDownloadBridge.onBlobFailed(blobId, 'Read error at offset ' + offset);" +
                "        };" +
                "        reader.readAsArrayBuffer(slice);" +
                "      }" +
                "      readSlice();" +
                "    })" +
                "    .catch(function(err) {" +
                "      if (window.BlobDownloadBridge) window.BlobDownloadBridge.onBlobFailed(blobId, err.message || err.toString());" +
                "    });" +
                "})();", blobUrl, blobId
        );

        mainHandler.post(() -> {
            try {
                webView.evaluateJavascript(js, null);
            } catch (Exception ignored) {}
        });
    }

    public synchronized BlobDownloadTask getOrCreateTask(DownloadItem item, DownloadDbHelper dbHelper,
                                                         DownloadTask.TaskListener listener, SpeedLimiter globalLimiter) {
        BlobDownloadTask task = new BlobDownloadTask(item, dbHelper, listener, globalLimiter);
        BlobSession session = sessionsByUrl.get(item.getUrl());

        if (session != null) {
            synchronized (session) {
                session.linkedTask = task;
                if (session.totalSize > 0) {
                    task.setInitialMetadata(session.totalSize);
                }

                // If early chunks were buffered to cache file while user was in prompt dialog, transfer them
                session.closeBufferedStream();
                if (session.bufferedTempFile != null && session.bufferedTempFile.exists() && session.bufferedTempFile.length() > 0) {
                    try {
                        byte[] buffer = new byte[65536];
                        int read;
                        long offset = 0;
                        try (FileInputStream in = new FileInputStream(session.bufferedTempFile)) {
                            while ((read = in.read(buffer)) > 0) {
                                byte[] chunk = new byte[read];
                                System.arraycopy(buffer, 0, chunk, 0, read);
                                task.appendChunk(chunk, offset, session.totalSize);
                                offset += read;
                            }
                        }
                    } catch (Exception ignored) {}
                    session.bufferedTempFile.delete();
                }

                if (session.isComplete) {
                    task.onStreamComplete();
                } else if (session.failedError != null) {
                    task.onStreamFailed(session.failedError);
                }
            }
        }

        return task;
    }

    @Override
    public void onBlobReady(String blobId, long totalSize, String mimeType) {
        BlobSession session = sessionsByBlobId.get(blobId);
        if (session == null) return;
        synchronized (session) {
            session.totalSize = totalSize;
            session.mimeType = mimeType;
            if (session.linkedTask != null) {
                session.linkedTask.setInitialMetadata(totalSize);
            }
        }
    }

    @Override
    public void onBlobChunk(String blobId, String base64Chunk, long offset, long totalSize) {
        BlobSession session = sessionsByBlobId.get(blobId);
        if (session == null || base64Chunk == null) return;

        byte[] data;
        try {
            data = Base64.decode(base64Chunk, Base64.DEFAULT);
        } catch (Exception e) {
            return;
        }

        synchronized (session) {
            if (session.linkedTask != null) {
                session.linkedTask.appendChunk(data, offset, totalSize);
            } else {
                // Buffer to cache file until user confirms prompt dialog
                try {
                    if (session.bufferedOut == null) {
                        session.bufferedOut = new FileOutputStream(session.bufferedTempFile, true);
                    }
                    session.bufferedOut.write(data);
                } catch (Exception ignored) {}
            }
        }
    }

    @Override
    public void onBlobEnd(String blobId) {
        BlobSession session = sessionsByBlobId.get(blobId);
        if (session == null) return;
        synchronized (session) {
            session.isComplete = true;
            session.closeBufferedStream();
            if (session.linkedTask != null) {
                session.linkedTask.onStreamComplete();
            }
        }
    }

    @Override
    public void onBlobFailed(String blobId, String error) {
        BlobSession session = sessionsByBlobId.get(blobId);
        if (session == null) return;
        synchronized (session) {
            session.failedError = error;
            session.closeBufferedStream();
            if (session.bufferedTempFile != null && session.bufferedTempFile.exists()) {
                session.bufferedTempFile.delete();
            }
            if (session.linkedTask != null) {
                session.linkedTask.onStreamFailed(error);
            }
        }
    }
}
