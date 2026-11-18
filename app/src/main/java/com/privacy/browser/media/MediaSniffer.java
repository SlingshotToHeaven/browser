package com.privacy.browser.media;

import android.os.Trace;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** A registry for one native document in one regular view. Passive recording never submits work. */
public final class MediaSniffer {
    static final int MAX_HINTS = 48;
    static final int MAX_ROWS = 96;
    static final int PLANS_PER_OPEN = 6;
    static final int PLANS_PER_PAGE = 16;
    static final int WORKERS = 2;
    static final int QUEUE_SIZE = 4;
    static final String HLS_MIME = "application/vnd.apple.mpegurl";
    static final String DASH_MIME = "application/dash+xml";

    private static final ThreadPoolExecutor BROWSER_WORKERS = newPool();

    interface Resolver { List<SniffedMedia> lookup(MediaSource item, String title, MediaMetadata.Budget budget) throws Exception; }

    static ThreadPoolExecutor newPool() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(WORKERS, WORKERS, 20, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_SIZE), task -> new Thread(task, "Browser-media"));
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private final ThreadPoolExecutor workers;
    private final Resolver resolver;
    private final LinkedHashMap<String, MediaSource> sources = new LinkedHashMap<>();
    private final LinkedHashMap<String, SniffedMedia> rows = new LinkedHashMap<>();
    private final Set<String> attempted = new HashSet<>();
    private volatile boolean observing;
    private boolean closed;
    private Runnable changed;
    private Work work;

    public MediaSniffer() { this(BROWSER_WORKERS, MediaMetadata::lookup); }

    MediaSniffer(ThreadPoolExecutor workers, Resolver resolver) {
        this.workers = workers;
        this.resolver = resolver;
    }

    public synchronized void observe(Runnable changed) {
        if (closed) return;
        this.changed = changed;
        observing = true;
    }

    public boolean isObserving() { return observing; }

    public synchronized void suspend() {
        observing = false;
        changed = null;
        cancelEnrichment();
    }

    public synchronized void close() {
        closed = true;
        suspend();
        sources.clear();
        rows.clear();
        attempted.clear();
    }

    public synchronized int count() { return rows.size(); }

    public synchronized List<SniffedMedia> snapshot() { return new ArrayList<>(rows.values()); }

    public void requestHint(String url, String encodedPath, String title) {
        if (observing) record(MediaSource.request(url, encodedPath), title);
    }

    /** User-triggered, current main-document DOM result. Never starts I/O by itself. */
    public void elementHint(String url, String type, String title) {
        if (observing) record(MediaSource.element(url, type), title);
    }

    private void record(MediaSource item, String title) {
        if (item == null) return;
        Runnable listener;
        synchronized (this) {
            if (!observing) return;
            MediaSource old = sources.get(item.key);
            if (old != null) {
                // Explicit <source type> can clarify an otherwise ambiguous native or DOM hint.
                if (old.kind == item.kind || (item.kind != MediaSource.Kind.HLS && item.kind != MediaSource.Kind.DASH)) return;
                sources.put(item.key, item);
                if (rows.containsKey(item.key)) rows.put(item.key, MediaMetadata.sourceRow(item, title, -1));
                return;
            }
            if (sources.size() >= MAX_HINTS) return;
            sources.put(item.key, item);
            if (rows.size() < MAX_ROWS) rows.putIfAbsent(item.key, MediaMetadata.sourceRow(item, title, -1));
            listener = changed;
        }
        if (listener != null) listener.run();
    }

    /** One batch, one owner. Repeated opens reuse known rows instead of submitting duplicate work. */
    public synchronized boolean enrich(String documentUrl, String title, List<String> embeds,
                                       Predicate<String> allowed, Runnable done) {
        if (!observing || work != null || attempted.size() >= PLANS_PER_PAGE) return false;
        List<MediaSource> plans = new ArrayList<>(PLANS_PER_OPEN);
        MediaSource platform = MediaSource.platform(documentUrl);
        if (platform == null) {
            for (int i = 0; i < Math.min(embeds.size(), 16); i++) {
                platform = MediaSource.platform(embeds.get(i));
                if (platform != null && allowed.test(platform.url)) break; // Only the first recognized player.
                platform = null;
            }
        }
        if (platform != null && allowed.test(platform.url) && !attempted.contains(platform.key)) plans.add(platform);
        int limit = Math.min(PLANS_PER_OPEN, PLANS_PER_PAGE - attempted.size());
        addPlans(plans, limit, true, allowed);
        addPlans(plans, limit, false, allowed);
        if (plans.isEmpty()) return false;
        // Install the reservation before enqueue, not after a worker opens a connection.
        Work next = new Work(plans, limit, title == null ? "Media" : clip(title, 256), allowed, done);
        work = next;
        try {
            workers.execute(next);
            return true;
        } catch (RejectedExecutionException busy) {
            work = null;
            return false; // Queued budget is full. Existing options can still be used.
        }
    }

    private void addPlans(List<MediaSource> plans, int limit, boolean playlists, Predicate<String> allowed) {
        for (MediaSource source : sources.values()) {
            if (plans.size() >= limit) return;
            if (source.kind != MediaSource.Kind.DASH && (source.kind == MediaSource.Kind.HLS) == playlists
                    && !attempted.contains(source.key) && allowed.test(source.url)) plans.add(source);
        }
    }

    public synchronized void cancelEnrichment() {
        if (work == null) return;
        work.canceled = true;
        work.done = null; // Don't retain the Activity callback during a blocked read.
        if (workers.remove(work)) work = null;
        else if (work.runner != null) work.runner.interrupt();
    }

    private final class Work implements Runnable {
        final List<MediaSource> plans;
        final int limit;
        final String title;
        final Predicate<String> allowed;
        volatile boolean canceled;
        Runnable done; // guarded by the document lock
        Thread runner;

        Work(List<MediaSource> plans, int limit, String title, Predicate<String> allowed, Runnable done) {
            this.plans = plans;
            this.limit = limit;
            this.title = title;
            this.allowed = allowed;
            this.done = done;
        }

        @Override public void run() {
            synchronized (MediaSniffer.this) {
                runner = Thread.currentThread();
            }
            MediaMetadata.Budget budget = new MediaMetadata.Budget(() -> canceled, allowed);
            Trace.beginSection("Browser.media.enrich");
            try {
                for (int i = 0; i < plans.size() && i < limit; i++) {
                    MediaSource plan = plans.get(i);
                    synchronized (MediaSniffer.this) {
                        if (budget.stopped() || budget.exhausted()) break;
                        if (!attempted.add(plan.key)) continue;
                    }
                    try {
                        List<SniffedMedia> found = resolver.lookup(plan, title, budget);
                        synchronized (MediaSniffer.this) {
                            if (canceled || !observing || budget.stopped()) break;
                            merge(found);
                        }
                        if (plan.kind == MediaSource.Kind.VIMEO || plan.kind == MediaSource.Kind.YOUTUBE) {
                            // One provider master, prioritized without exceeding the batch/document limits.
                            for (SniffedMedia row : found) {
                                if (!HLS_MIME.equals(row.manifestMime)) continue;
                                MediaSource source = MediaSource.element(row.url, "hls");
                                if (source != null && allowed.test(source.url)) plans.add(i + 1, source);
                                break;
                            }
                        }
                    } catch (Exception unavailable) {
                        // Preserve baseline hints. Don't log a URL, token, payload, or exception details.
                    }
                }
            } finally {
                Trace.endSection();
                complete();
            }
        }

        private void complete() {
            Runnable notify, deliver;
            synchronized (MediaSniffer.this) {
                runner = null;
                if (work != this) return;
                work = null;
                if (canceled || !observing) return;
                notify = changed;
                deliver = done;
            }
            if (notify != null) notify.run();
            if (deliver != null) deliver.run();
        }
    }

    private void merge(List<SniffedMedia> found) {
        for (SniffedMedia row : found) {
            URI uri = MediaSource.httpUri(row.url);
            if (uri == null) continue;
            String key = MediaSource.identity(uri);
            SniffedMedia old = rows.get(key);
            if ((old == null && rows.size() < MAX_ROWS) || (old != null && rank(row) >= rank(old))) rows.put(key, row);
        }
    }

    private static int rank(SniffedMedia row) {
        if (row.codec != null || (row.manifestMime != null && !"HLS Master".equals(row.quality))) return 2;
        return row.fileSize > 0 || row.manifestMime != null ? 1 : 0;
    }

    private static String clip(String text, int limit) { return text.substring(0, Math.min(limit, text.length())); }

    public static final class SniffedMedia {
        private final String url;
        private final String title;
        private final String type; // "video", "audio", etc.
        private final String quality; // "1080p", "720p", "HLS Stream", etc.
        private final long fileSize; // in bytes, or -1 if unknown
        private final String codec; // "AV1", "H.264", "VP9", "AAC", "Opus"
        private final long bitrate; // in bps, or -1 if unknown
        private final int fps; // 30, 60
        private final int formatId; // itag or format ID
        private final String audioUrl; // companion audio url if paired
        private final long audioFileSize; // companion audio size
        private final String manifestMime;
        private final String audioGroup;

        SniffedMedia(String url, String title, String type, String quality, long fileSize, String codec, long bitrate,
                     int fps, int formatId, String audioUrl, long audioFileSize, String manifestMime, String audioGroup) {
            this.url = url;
            this.title = title != null ? clip(title, 256).trim() : "Video Stream";
            this.type = type != null ? type : "video";
            this.quality = quality != null ? clip(quality, 64) : "Auto";
            this.manifestMime = manifestMime;
            this.audioGroup = audioGroup;
            this.fileSize = fileSize;
            this.codec = codec;
            this.bitrate = bitrate;
            this.fps = fps;
            this.formatId = formatId;
            this.audioUrl = audioUrl;
            this.audioFileSize = audioFileSize;
        }

        public String getUrl() { return url; }
        public String getTitle() { return title; }
        public String getType() { return type; }
        public String getQuality() { return quality; }
        public long getFileSize() { return fileSize; }
        public String getCodec() { return codec; }
        public long getBitrate() { return bitrate; }
        public int getFps() { return fps; }
        public int getFormatId() { return formatId; }
        public String getAudioUrl() { return audioUrl; }
        public long getAudioFileSize() { return audioFileSize; }
        public String getAudioGroup() { return audioGroup; }
        public String getManifestMime() { return manifestMime; }

        public String getFormattedBitrate() {
            if (bitrate <= 0) return "";
            if (bitrate >= 1000000) {
                return String.format(Locale.US, "%.1f Mbps", bitrate / 1000000.0);
            } else {
                return (bitrate / 1000) + " kbps";
            }
        }

        public String getDisplayDescription() {
            StringBuilder sb = new StringBuilder();
            if (codec != null && !codec.isEmpty()) {
                sb.append(codec);
            }
            if (fps > 0 && fps != 30) {
                if (sb.length() > 0) sb.append(" • ");
                sb.append(fps).append("fps");
            }
            if (bitrate > 0) {
                if (sb.length() > 0) sb.append(" • ");
                sb.append(getFormattedBitrate());
            }
            if (sb.length() == 0) {
                if (HLS_MIME.equals(manifestMime)) return "HLS Stream • Native Remux";
                if (DASH_MIME.equals(manifestMime)) return "DASH Stream";
                return "Direct Media Track";
            }
            return sb.toString();
        }

        public String getFormattedSize() {
            if (fileSize <= 0) {
                if (HLS_MIME.equals(manifestMime)) return "HLS Stream";
                if (DASH_MIME.equals(manifestMime)) return "DASH Stream";
                return "Video Stream";
            }
            final String[] units = new String[]{"B", "KB", "MB", "GB"};
            int digitGroups = (int) (Math.log10(fileSize) / Math.log10(1024));
            if (digitGroups >= units.length) digitGroups = units.length - 1;
            return String.format(Locale.US, "%.1f %s", fileSize / Math.pow(1024, digitGroups), units[digitGroups]);
        }
    }

}
