package com.privacy.downloader.engine;

import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.privacy.downloader.db.DownloadDbHelper;
import com.privacy.downloader.model.DownloadItem;
import com.privacy.downloader.net.RequestContext;
import com.privacy.downloader.net.ScopedHttp;
import com.privacy.downloader.util.DownloadIntegrityVerifier;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Downloads HLS playlists or explicit video/audio inputs and remuxes staged media
 * with FFmpeg. This task cannot publish an original manifest or an unmuxed input.
 */
public class StreamDownloadTask extends DownloadTask {

    private static final int MAX_PLAYLIST_BYTES = 2 * 1024 * 1024;
    private static final int MAX_SEGMENT_ATTEMPTS = 3;
    private static final int COPY_BUFFER_BYTES = 64 * 1024;
    private static final int SNIFF_BYTES = 4096;

    private static final Pattern RESOLUTION_PATTERN =
            Pattern.compile("RESOLUTION=(\\d+x\\d+)");
    private static final Pattern BANDWIDTH_PATTERN =
            Pattern.compile("BANDWIDTH=(\\d+)");
    private static final Pattern ATTRIBUTE_PATTERN =
            Pattern.compile("(?:^|,)\\s*([A-Z0-9-]+)=(?:\"([^\"]*)\"|([^,\"]*))");

    private static final Pattern BYTERANGE_PATTERN = Pattern.compile("(\\d+)(?:@(\\d+))?");

    private long sessionId = -1;
    private long lastProgressUpdateTime = 0;
    private long lastDbUpdateTime = 0;

    public StreamDownloadTask(DownloadItem item, DownloadDbHelper dbHelper, TaskListener listener, SpeedLimiter globalLimiter) {
        super(item, dbHelper, listener, globalLimiter);
    }

    StreamDownloadTask(DownloadItem item, DownloadDbHelper dbHelper, TaskListener listener, SpeedLimiter globalLimiter,
                       RequestContext context) {
        super(item, dbHelper, listener, globalLimiter, context);
    }

    @Override
    protected void onStopRequested() {
        if (sessionId != -1) {
            try {
                FFmpegKitConfig.nativeFFmpegCancel(sessionId);
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void run() {
        File video = null;
        File audio = null;
        File staged = null;
        Exception failure = null;
        try {
            requireActive();
            String videoUrl = item.getUrl();
            String audioUrl = item.getAudioUrl();
            rejectDash(videoUrl, item.getMimeType());
            rejectDash(audioUrl, null);

            // The final MP4 name was reserved before this task started.
            File directory = outputDirectory();
            requireActive();

            boolean needsEmbeddedAudio = false;
            if (audioUrl != null && !audioUrl.trim().isEmpty()) {
                video = File.createTempFile("stream-", ".video.part", directory);
                audio = File.createTempFile("stream-", ".audio.part", directory);
                markRunning("Downloading video track…");
                // totalBytes is a UI estimate for both tracks, not the video response's length.
                long videoBytes = downloadDirectTrack(videoUrl, video, 0, -1);
                requireActive();
                markRunning("Downloading required audio track…");
                try {
                    downloadDirectTrack(audioUrl, audio, videoBytes, item.getAudioBytes());
                } catch (IOException e) {
                    throw audioFailure(e);
                }
            } else {
                HlsPlaylist media = fetchHlsPlaylist(videoUrl, requestContext);
                String audioPlaylistUrl = null;
                RequestContext masterContext = media.context;
                if (media.text.contains("#EXT-X-STREAM-INF:")) {
                    MasterPlaylist master = parseMasterPlaylist(media.url, media.text);
                    Variant variant = selectBestVariant(master.variants, item.getFileName());
                    if (variant == null) throw new IOException("The HLS playlist contains no playable variant.");
                    if (variant.audioGroupId != null) {
                        AudioRendition rendition = master.audioTracks.get(variant.audioGroupId);
                        if (rendition == null) throw new IOException("The HLS playlist is missing the required audio group.");
                        audioPlaylistUrl = rendition.uri;
                        needsEmbeddedAudio = audioPlaylistUrl == null;
                    }
                    media = fetchHlsPlaylist(variant.uri, masterContext);
                }

                video = File.createTempFile("stream-", ".video.part", directory);
                markRunning("Downloading stream segments…");
                downloadMediaPlaylist(media, video, true);
                requireActive();
                if (audioPlaylistUrl != null) {
                    audio = File.createTempFile("stream-", ".audio.part", directory);
                    markRunning("Downloading required audio track…");
                    try {
                        downloadMediaPlaylist(fetchHlsPlaylist(audioPlaylistUrl, masterContext), audio, false);
                    } catch (IOException e) {
                        throw audioFailure(e);
                    }
                }
            }

            requireActive();
            if (video.length() == 0) throw new IOException("The stream contains no video media.");
            if (audio != null && audio.length() == 0) throw new IOException("The required audio track is empty.");

            markRunning("Muxing stream to MP4…");
            staged = File.createTempFile("stream-", ".mux.part", directory);
            int exitCode = executeMux(muxArguments(video, audio, needsEmbeddedAudio, staged, false));
            requireActive();
            if (exitCode != 0 && audio != null) {
                // A different audio codec may work, but both tracks are still required.
                Files.delete(staged.toPath());
                staged = File.createTempFile("stream-", ".mux.part", directory);
                exitCode = executeMux(muxArguments(video, audio, needsEmbeddedAudio, staged, true));
                requireActive();
            }
            if (exitCode != 0) throw new IOException("Stream muxing failed (exit code " + exitCode + ").");
            long size = verifyStagedMp4(staged);
            bindStagingFile(staged);
            if (!publishOutput(staged, size, "video/mp4")) throw new InterruptedIOException("Stream download stopped.");
        } catch (Exception e) {
            failure = e;
        } finally {
            // These paths belong to this attempt. Never clean up the destination or
            // publish any of the raw inputs, even after a partially written native result.
            deleteTemp(video);
            deleteTemp(audio);
            if (failure != null) deleteTemp(staged); // The publisher cleans its committed source.
        }

        if (failure == null) {
            if (listener != null) listener.onTaskCompleted(item);
        } else {
            fail(failure.getMessage() != null ? failure.getMessage() : "Stream download failed.");
        }
    }

    private void requireActive() throws InterruptedIOException {
        if (isStopRequested()) throw new InterruptedIOException("Stream download stopped.");
    }

    private static void rejectDash(String url, String mimeType) throws TransferIntegrity.Failure {
        if (StreamDetector.isDash(url, mimeType)) throw new TransferIntegrity.Failure(StreamDetector.DASH_UNSUPPORTED);
    }

    private static IOException audioFailure(IOException cause) {
        return new IOException("Required audio track failed: " + cause.getMessage(), cause);
    }

    private static void deleteTemp(File file) {
        if (file != null) file.delete();
    }

    private static List<String> muxArguments(File video, File audio, boolean needsEmbeddedAudio, File output, boolean transcode) {
        List<String> args = new ArrayList<>();
        Collections.addAll(args, "-y", "-xerror", "-abort_on", "empty_output+empty_output_stream",
                "-i", video.getAbsolutePath());
        if (audio != null) {
            // Optional maps or auto-selection can silently drop the accompanying track.
            Collections.addAll(args, "-i", audio.getAbsolutePath(), "-map", "0:v:0", "-map", "1:a:0",
                    "-c:v", "copy", "-c:a", transcode ? "aac" : "copy");
            if (transcode) Collections.addAll(args, "-b:a", "192k");
        } else {
            if (needsEmbeddedAudio) Collections.addAll(args, "-map", "0:v:0?", "-map", "0:a:0");
            Collections.addAll(args, "-c", "copy", "-bsf:a", "aac_adtstoasc");
        }
        Collections.addAll(args, "-movflags", "+faststart", "-f", "mp4", output.getAbsolutePath());
        return args;
    }

    int executeMux(List<String> args) throws IOException {
        try {
            if (!FFmpegKitConfig.isLoaded()) throw new IOException("Stream muxing is unavailable on this device.");
            long currentSession;
            synchronized (this) {
                requireActive();
                currentSession = FFmpegKitConfig.newSessionId();
                sessionId = currentSession;
            }
            try {
                return FFmpegKitConfig.nativeFFmpegExecute(currentSession, args.toArray(new String[0]));
            } finally {
                FFmpegKitConfig.unregisterSession(currentSession);
                synchronized (this) {
                    sessionId = -1;
                }
            }
        } catch (LinkageError e) {
            throw new IOException("Stream muxing is unavailable on this device.", e);
        }
    }

    private static long verifyStagedMp4(File staged) throws IOException {
        if (!staged.isFile()) throw new IOException("Stream muxing failed: no MP4 output.");
        // Check the top-level box boundaries without reading media payloads. The
        // explicit FFmpeg maps and empty-stream checks, not these headers, enforce
        // track presence. This is not a playback check.
        try (RandomAccessFile in = new RandomAccessFile(staged, "r")) {
            long length = in.length();
            boolean hasMovie = false;
            boolean hasMedia = false;
            for (long offset = 0; offset < length; ) {
                in.seek(offset);
                long remaining = length - offset;
                if (remaining < 8) throw invalidMp4();
                long size = Integer.toUnsignedLong(in.readInt());
                int type = in.readInt();
                int headerSize = 8;
                if (size == 1) {
                    if (remaining < 16) throw invalidMp4();
                    size = in.readLong();
                    headerSize = 16;
                } else if (size == 0) {
                    size = remaining;
                }
                if (size < headerSize || size > remaining) throw invalidMp4();
                if (offset == 0 && (type != 0x66747970 /* ftyp */ || size - headerSize < 8)) throw invalidMp4();
                if (size > headerSize) {
                    hasMovie |= type == 0x6d6f6f76; // moov
                    hasMedia |= type == 0x6d646174; // mdat
                }
                offset += size;
            }
            if (!hasMovie || !hasMedia) throw invalidMp4();
            return length;
        }
    }

    private static IOException invalidMp4() {
        return new IOException("Stream muxing failed: no complete MP4 output.");
    }

    /**
     * Downloads all segments in an HLS media playlist, concatenating them into a local file.
     */
    private void downloadMediaPlaylist(HlsPlaylist playlist, File outputFile, boolean trackProgress) throws IOException {
        List<HlsSegment> segments = parseMediaPlaylist(playlist.url, playlist.text);
        if (segments.stream().noneMatch(segment -> !segment.initialization)) {
            throw new IOException("The HLS playlist contains no media segments.");
        }

        byte[] keyBytes = null;
        String currentKeyUri = null;

        long downloadedBytes = 0;
        long windowStartBytes = 0;
        long windowStartTime = System.currentTimeMillis();

        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        try (FileChannel out = FileChannel.open(outputFile.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            for (int i = 0; i < segments.size(); i++) {
                requireActive();
                HlsSegment seg = segments.get(i);
                boolean encrypted = "AES-128".equals(seg.keyMethod);
                if (encrypted && !seg.keyUri.equals(currentKeyUri)) {
                    currentKeyUri = seg.keyUri;
                    keyBytes = fetchKeyWithRetry(seg.keyUri, playlist.context);
                    if (keyBytes.length != 16) throw new IOException("The HLS segment has an invalid AES-128 key.");
                }

                downloadedBytes += appendSegmentWithRetry(seg, playlist.context, encrypted ? keyBytes : null, out, buffer);

                // Update progress
                if (trackProgress) {
                    long now = System.currentTimeMillis();
                    if (now - lastProgressUpdateTime >= 400 || i == segments.size() - 1) {
                        lastProgressUpdateTime = now;

                        double progressRatio = (double) (i + 1) / segments.size();
                        if (progressRatio > 0) {
                            long estTotal = (long) (downloadedBytes / progressRatio);
                            item.setTotalBytes(estTotal);
                        }
                        item.setDownloadedBytes(downloadedBytes);

                        long timeDiff = now - windowStartTime;
                        if (timeDiff >= 1000) {
                            long bytesDiff = downloadedBytes - windowStartBytes;
                            long speed = (bytesDiff * 1000) / timeDiff;
                            item.setSpeedBytesPerSec(speed);
                            windowStartTime = now;
                            windowStartBytes = downloadedBytes;
                        }

                        if (now - lastDbUpdateTime >= 2000 || i == segments.size() - 1) {
                            lastDbUpdateTime = now;
                            persistProgress(downloadedBytes);
                        }
                        if (listener != null) {
                            listener.onTaskUpdated(item);
                        }
                    }
                }
            }
        }
    }

    private long appendSegmentWithRetry(HlsSegment segment, RequestContext context, byte[] key, FileChannel out,
                                        byte[] buffer) throws IOException {
        long checkpoint = out.position();
        long start = segment.byteRangeOffset;
        long count = segment.byteRangeLength;
        for (int attempt = 1; ; attempt++) {
            requireActive();
            String range = count > 0 ? "bytes=" + start + "-" + (start + count - 1) : null;
            try (ScopedHttp.Response response = openConnection(segment.url, context, range)) {
                HttpURLConnection conn = response.connection;
                long expected = responseLength(conn, start, count);
                Cipher cipher = key == null ? null : aes128Cipher(key, segment.iv, segment.sequenceNumber);
                copyMediaBody(conn, expected, cipher, Channels.newOutputStream(out), buffer);
                return out.position() - checkpoint;
            } catch (TransferIntegrity.Failure e) {
                throw e;
            } catch (IOException e) {
                requireActive();
                if (attempt == MAX_SEGMENT_ATTEMPTS) throw e;
                // Only retry this segment. No bytes from its incomplete response may
                // precede the replacement, including decoded bytes from an AES segment.
                out.truncate(checkpoint);
                out.position(checkpoint);
            }
            waitForRetry(attempt);
        }
    }

    private void copyMediaBody(HttpURLConnection conn, long expected, Cipher cipher, OutputStream out,
                               byte[] buffer) throws IOException {
        long received = 0;
        MediaWriter media = new MediaWriter(out);
        // CBC can retain a partial block and the last full block for padding checks.
        byte[] clear = cipher == null ? null : new byte[buffer.length + 32];
        try (InputStream in = limitBody(conn.getInputStream())) {
            for (;;) {
                requireActive();
                int count = in.read(buffer);
                if (count == -1) break;
                requireActive();
                if (expected >= 0 && count > expected - received) {
                    throw new TransferIntegrity.Failure("The stream response is longer than expected.");
                }
                received += count;
                if (cipher == null) media.write(buffer, count);
                else media.write(clear, cipher.update(buffer, 0, count, clear, 0));
            }
            requireActive();
            requireCompleteBody(received, expected);
            // Verify the wire length before releasing the last decrypted bytes. Do not
            // use a CipherInputStream, which can hide errors in the final block.
            if (cipher != null) media.write(clear, cipher.doFinal(clear, 0));
            media.finish();
        } catch (GeneralSecurityException e) {
            throw new TransferIntegrity.Failure("Could not decrypt the HLS segment.");
        }
    }

    /** Inspect only a fixed cleartext prefix, even when the response has no length header. */
    private static final class MediaWriter {
        private final OutputStream out;
        private final byte[] prefix = new byte[SNIFF_BYTES];
        private int prefixLength;

        MediaWriter(OutputStream out) {
            this.out = out;
        }

        void write(byte[] bytes, int count) throws IOException {
            int start = 0;
            if (prefixLength < prefix.length) {
                start = Math.min(count, prefix.length - prefixLength);
                System.arraycopy(bytes, 0, prefix, prefixLength, start);
                prefixLength += start;
                if (prefixLength == prefix.length) writePrefix();
            }
            if (count > start) out.write(bytes, start, count - start);
        }

        void finish() throws IOException {
            if (prefixLength == 0) throw new TransferIntegrity.Failure("The HLS segment contains no media bytes.");
            if (prefixLength < prefix.length) writePrefix();
        }

        private void writePrefix() throws IOException {
            rejectNonMedia(prefix, prefixLength);
            out.write(prefix, 0, prefixLength);
        }
    }

    private static Cipher aes128Cipher(byte[] key, byte[] iv, long seqNum) throws IOException {
        if (iv == null) {
            // Standard HLS: sequence number encoded as a 16-byte big-endian integer.
            iv = ByteBuffer.allocate(16).putLong(8, seqNum).array();
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher;
        } catch (GeneralSecurityException e) {
            throw new TransferIntegrity.Failure("Could not decrypt the HLS segment.");
        }
    }

    private byte[] fetchKeyWithRetry(String url, RequestContext context) throws IOException {
        for (int attempt = 1; ; attempt++) {
            requireActive();
            try (ScopedHttp.Response response = openConnection(url, context, null)) {
                HttpURLConnection conn = response.connection;
                return readResponse(conn, responseLength(conn, 0, -1), 16);
            } catch (TransferIntegrity.Failure e) {
                throw e;
            } catch (IOException e) {
                requireActive();
                if (attempt == MAX_SEGMENT_ATTEMPTS) throw e;
            }
            waitForRetry(attempt);
        }
    }

    private static void waitForRetry(int attempt) throws InterruptedIOException {
        try {
            Thread.sleep(500L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Stream download interrupted.");
        }
    }

    private HlsPlaylist fetchHlsPlaylist(String url, RequestContext context) throws IOException {
        try (ScopedHttp.Response response = openConnection(url, context, null)) {
            long expected = responseLength(response.connection, 0, -1);
            String text = new String(readResponse(response.connection, expected, MAX_PLAYLIST_BYTES), StandardCharsets.UTF_8);
            if (StreamDetector.looksLikeDashManifest(text)) throw new IOException(StreamDetector.DASH_UNSUPPORTED);
            if (!isHlsPlaylist(text)) throw new IOException("The server did not return an HLS playlist.");
            return new HlsPlaylist(response.url(), text, response.context);
        }
    }

    private static boolean isHlsPlaylist(String text) {
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        int newline = text.indexOf('\n');
        return "#EXTM3U".equals((newline < 0 ? text : text.substring(0, newline)).trim());
    }

    private static long responseLength(HttpURLConnection conn, long start, long count) throws IOException {
        int status = conn.getResponseCode();
        rejectDash(conn.getURL().toString(), conn.getContentType());
        boolean ranged = count > 0;
        if (status != (ranged ? HttpURLConnection.HTTP_PARTIAL : HttpURLConnection.HTTP_OK)) {
            if (ranged && status == HttpURLConnection.HTTP_OK) throw new TransferIntegrity.Failure("The server ignored the HLS byte range.");
            throw new IOException("HTTP " + status + " fetching stream data.");
        }
        if (!TransferIntegrity.identityEncoding(HttpDownloadRequest.singleHeader(conn, "Content-Encoding"))) {
            throw new TransferIntegrity.Failure("The stream response is not identity encoded.");
        }
        String range = HttpDownloadRequest.singleHeader(conn, "Content-Range");
        long length = HttpDownloadRequest.contentLength(conn);
        if (HttpDownloadRequest.isMultipartRange(conn)) throw new TransferIntegrity.Failure("The server returned a multipart stream range.");
        if (ranged) {
            if (TransferIntegrity.matchingRangeTotal(range, start, start + count - 1) <= 0 || (length >= 0 && length != count)) {
                throw new TransferIntegrity.Failure("The server returned an invalid HLS byte range.");
            }
            return count;
        }
        if (range != null) throw new TransferIntegrity.Failure("The server returned a range for a full stream request.");
        return length;
    }

    private byte[] readResponse(HttpURLConnection conn, long expected, int byteLimit) throws IOException {
        if (expected > byteLimit) throw new TransferIntegrity.Failure("The stream response is too large.");
        try (InputStream in = limitBody(conn.getInputStream()); ByteArrayOutputStream data = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[Math.min(COPY_BUFFER_BYTES, byteLimit)];
            int count;
            while ((count = in.read(buffer)) != -1) {
                requireActive();
                long read = (long) data.size() + count;
                if (read > byteLimit) throw new TransferIntegrity.Failure("The stream response is too large.");
                if (expected >= 0 && read > expected) throw new TransferIntegrity.Failure("The stream response is longer than expected.");
                data.write(buffer, 0, count);
            }
            requireCompleteBody(data.size(), expected);
            return data.toByteArray();
        }
    }

    private static void requireCompleteBody(long bytes, long expected) throws TransferIntegrity.Failure {
        if (bytes == 0) throw new TransferIntegrity.Failure("The stream response is empty.");
        if (expected >= 0 && bytes != expected) throw new TransferIntegrity.Failure("The stream response ended before all expected bytes arrived.");
    }

    private static void rejectNonMedia(byte[] bytes, int count) throws TransferIntegrity.Failure {
        String prefix = new String(bytes, 0, Math.min(count, SNIFF_BYTES), StandardCharsets.UTF_8);
        if (StreamDetector.looksLikeDashManifest(prefix)) throw new TransferIntegrity.Failure(StreamDetector.DASH_UNSUPPORTED);
        if (isHlsPlaylist(prefix) || DownloadIntegrityVerifier.detectHtmlMasquerade("track.mp4", null, bytes, count)) {
            throw new TransferIntegrity.Failure("The server returned a playlist or error page instead of media.");
        }
    }

    private long downloadDirectTrack(String url, File output, long previousBytes, long knownLength) throws IOException {
        requireActive();
        try (ScopedHttp.Response response = openConnection(url, requestContext, null)) {
            HttpURLConnection conn = response.connection;
            long declared = responseLength(conn, 0, -1);
            if (knownLength > 0 && declared >= 0 && declared != knownLength) {
                throw new IOException("The stream track length does not match the selected track.");
            }
            long expected = knownLength > 0 ? knownLength : declared;
            long downloaded = 0;
            long windowStartTime = System.currentTimeMillis();
            long windowStartBytes = 0;
            try (InputStream in = limitBody(conn.getInputStream());
                 OutputStream out = Files.newOutputStream(output.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[COPY_BUFFER_BYTES];
                // Read a bounded prefix before writing so a manifest or an HTML error
                // page cannot be treated as a media track merely because it returned 200.
                int prefixLength = 0;
                while (prefixLength < SNIFF_BYTES) {
                    requireActive();
                    int count = in.read(buffer, prefixLength, SNIFF_BYTES - prefixLength);
                    if (count == -1) break;
                    prefixLength += count;
                }
                rejectNonMedia(buffer, prefixLength);
                int count = prefixLength;
                do {
                    requireActive();
                    if (expected >= 0 && count > expected - downloaded) throw new IOException("The stream response is longer than expected.");
                    out.write(buffer, 0, count);
                    downloaded += count;
                    long now = System.currentTimeMillis();
                    if (now - lastProgressUpdateTime >= 400) {
                        lastProgressUpdateTime = now;
                        item.setDownloadedBytes(previousBytes + downloaded);
                        long timeDiff = now - windowStartTime;
                        if (timeDiff >= 1000) {
                            item.setSpeedBytesPerSec((downloaded - windowStartBytes) * 1000 / timeDiff);
                            windowStartTime = now;
                            windowStartBytes = downloaded;
                        }
                        if (now - lastDbUpdateTime >= 2000) {
                            lastDbUpdateTime = now;
                            persistProgress(previousBytes + downloaded);
                        }
                        if (listener != null) listener.onTaskUpdated(item);
                    }
                } while ((count = in.read(buffer)) != -1);
            }
            requireActive();
            requireCompleteBody(downloaded, expected);
            item.setDownloadedBytes(previousBytes + downloaded);
            persistProgress(previousBytes + downloaded);
            if (listener != null) listener.onTaskUpdated(item);
            return downloaded;
        }
    }

    private ScopedHttp.Response openConnection(String url, RequestContext context, String range) throws IOException {
        rejectDash(url, null);
        return ScopedHttp.download(url, context, null, 12000, 15000, range, null, this::isStopRequested);
    }

    public static String resolveUrl(String baseUrl, String relativeUrl) {
        if (relativeUrl == null || relativeUrl.trim().isEmpty()) return baseUrl;
        String trimmed = relativeUrl.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        try {
            URL base = new URL(baseUrl);
            URL resolved = new URL(base, trimmed);
            return resolved.toString();
        } catch (Exception e) {
            return trimmed;
        }
    }

    // -------------------------------------------------------------
    // HLS Playlist Parsing
    // -------------------------------------------------------------

    private static class HlsPlaylist {
        final String url;
        final String text;
        final RequestContext context;

        HlsPlaylist(String url, String text, RequestContext context) {
            this.url = url;
            this.text = text;
            this.context = context;
        }
    }

    private static class MasterPlaylist {
        final List<Variant> variants = new ArrayList<>();
        final Map<String, AudioRendition> audioTracks = new HashMap<>();
    }

    private static class AudioRendition {
        final String uri; // No URI means the audio is in the variant's media.
        final boolean preferred;

        AudioRendition(String uri, boolean preferred) {
            this.uri = uri;
            this.preferred = preferred;
        }
    }

    private static class Variant {
        String uri;
        String resolution = "";
        long bandwidth = 0;
        String audioGroupId = null;
    }

    private static class HlsSegment {
        boolean initialization;
        String url;
        long sequenceNumber = 0;
        long byteRangeLength = -1;
        long byteRangeOffset = 0;
        String keyMethod = null;
        String keyUri = null;
        byte[] iv = null;
    }

    private MasterPlaylist parseMasterPlaylist(String baseUrl, String content) {
        MasterPlaylist master = new MasterPlaylist();
        String[] lines = content.split("[\r\n]+");

        // Attribute order is not fixed, and a rendition may omit its URI if the
        // audio is muxed into the variant. Missing groups are different from that.
        for (String line : lines) {
            line = line.trim();
            if (!line.startsWith("#EXT-X-MEDIA:")) continue;
            Map<String, String> attributes = parseAttributes(line);
            if (!"AUDIO".equals(attributes.get("TYPE"))) continue;
            String group = attributes.get("GROUP-ID");
            String uri = attributes.get("URI");
            if (group == null || group.isEmpty() || (uri != null && uri.trim().isEmpty())) continue;
            boolean preferred = "YES".equals(attributes.get("DEFAULT"));
            AudioRendition existing = master.audioTracks.get(group);
            if (existing == null || (!existing.preferred && preferred)) {
                master.audioTracks.put(group, new AudioRendition(uri == null ? null : resolveUrl(baseUrl, uri), preferred));
            }
        }

        // Second pass: extract variant stream tracks
        Variant currentVariant = null;
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                currentVariant = new Variant();
                Matcher rm = RESOLUTION_PATTERN.matcher(line);
                if (rm.find()) currentVariant.resolution = rm.group(1);

                Matcher bm = BANDWIDTH_PATTERN.matcher(line);
                if (bm.find()) {
                    try { currentVariant.bandwidth = Long.parseLong(bm.group(1)); } catch (Exception ignored) {}
                }

                currentVariant.audioGroupId = parseAttributes(line).get("AUDIO");

            } else if (!line.startsWith("#") && currentVariant != null) {
                currentVariant.uri = resolveUrl(baseUrl, line);
                master.variants.add(currentVariant);
                currentVariant = null;
            }
        }

        return master;
    }

    private static Map<String, String> parseAttributes(String tag) {
        Map<String, String> attributes = new HashMap<>();
        Matcher matcher = ATTRIBUTE_PATTERN.matcher(tag.substring(tag.indexOf(':') + 1));
        while (matcher.find()) {
            attributes.put(matcher.group(1), matcher.group(2) != null ? matcher.group(2) : matcher.group(3));
        }
        return attributes;
    }

    private Variant selectBestVariant(List<Variant> variants, String targetFileName) {
        if (variants == null || variants.isEmpty()) return null;

        // Try to match resolution specified in filename (e.g. "1080p", "720p")
        String fn = targetFileName != null ? targetFileName.toLowerCase(Locale.US) : "";
        String[] qualities = {"2160", "1440", "1080", "720", "540", "480", "360", "240"};
        String matchedQ = null;
        for (String q : qualities) {
            if (fn.contains(q)) {
                matchedQ = q;
                break;
            }
        }

        if (matchedQ != null) {
            for (Variant v : variants) {
                if (v.resolution.contains("x" + matchedQ) || v.resolution.endsWith(matchedQ)) {
                    return v;
                }
            }
        }

        // Otherwise pick the variant with the highest bandwidth
        Variant best = variants.get(0);
        for (Variant v : variants) {
            if (v.bandwidth > best.bandwidth) {
                best = v;
            }
        }
        return best;
    }

    private static List<HlsSegment> parseMediaPlaylist(String baseUrl, String content) throws IOException {
        List<HlsSegment> segments = new ArrayList<>();
        String keyMethod = null;
        String keyUri = null;
        byte[] iv = null;
        long sequence = 0;
        boolean awaitingSegment = false;
        String byteRange = null;
        HlsSegment previousMedia = null;

        for (String raw : content.split("[\\r\\n]+")) {
            String line = raw.trim();
            if (line.isEmpty() || line.equals("\uFEFF#EXTM3U")) continue;
            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                try {
                    sequence = Long.parseLong(line.substring("#EXT-X-MEDIA-SEQUENCE:".length()).trim());
                    if (sequence < 0) throw new NumberFormatException();
                } catch (NumberFormatException e) {
                    throw new IOException("The HLS media sequence is invalid.", e);
                }
            } else if (line.startsWith("#EXT-X-KEY:")) {
                Map<String, String> attributes = parseAttributes(line);
                keyMethod = attributes.get("METHOD");
                if ("NONE".equals(keyMethod)) {
                    keyUri = null;
                    iv = null;
                } else if ("AES-128".equals(keyMethod) && "identity".equals(attributes.getOrDefault("KEYFORMAT", "identity"))) {
                    String uri = attributes.get("URI");
                    if (uri == null || uri.trim().isEmpty()) throw new IOException("The HLS segment is missing its key.");
                    keyUri = resolveUrl(baseUrl, uri);
                    iv = parseIv(attributes.get("IV"));
                } else {
                    throw new IOException("The HLS segment uses unsupported encryption.");
                }
            } else if (line.startsWith("#EXT-X-MAP:")) {
                Map<String, String> attributes = parseAttributes(line);
                String uri = attributes.get("URI");
                if (uri == null || uri.trim().isEmpty()) throw new IOException("The HLS initialization segment has no URL.");
                if ("AES-128".equals(keyMethod) && iv == null) {
                    throw new IOException("The encrypted HLS initialization segment has no IV.");
                }
                HlsSegment init = new HlsSegment();
                init.url = resolveUrl(baseUrl, uri);
                init.initialization = true;
                init.keyMethod = keyMethod;
                init.keyUri = keyUri;
                init.iv = iv;
                applyByteRange(init, attributes.get("BYTERANGE"), 0L);
                segments.add(init);
            } else if (line.startsWith("#EXTINF:")) {
                if (awaitingSegment) throw missingSegmentUrl();
                awaitingSegment = true;
            } else if (line.startsWith("#EXT-X-BYTERANGE:")) {
                if (byteRange != null) throw new IOException("The HLS segment has multiple byte ranges.");
                byteRange = line.substring("#EXT-X-BYTERANGE:".length()).trim();
            } else if (line.equals("#EXT-X-GAP")) {
                throw new IOException("The HLS playlist marks a media segment as missing.");
            } else if (line.equals("#EXT-X-ENDLIST") && awaitingSegment) {
                throw missingSegmentUrl();
            } else if (!line.startsWith("#")) {
                if (!awaitingSegment) throw new IOException("Expected an HLS media playlist with segment entries.");
                HlsSegment segment = new HlsSegment();
                segment.url = resolveUrl(baseUrl, line);
                segment.sequenceNumber = sequence++;
                segment.keyMethod = keyMethod;
                segment.keyUri = keyUri;
                segment.iv = iv;
                Long implicitOffset = previousMedia != null && previousMedia.byteRangeLength > 0
                        && segment.url.equals(previousMedia.url)
                        ? previousMedia.byteRangeOffset + previousMedia.byteRangeLength : null;
                applyByteRange(segment, byteRange, implicitOffset);
                segments.add(segment);
                previousMedia = segment;
                awaitingSegment = false;
                byteRange = null;
            }
        }
        if (awaitingSegment || byteRange != null) throw missingSegmentUrl();
        return segments;
    }

    private static IOException missingSegmentUrl() {
        return new IOException("The HLS playlist lists a segment without a URL.");
    }

    private static void applyByteRange(HlsSegment segment, String value, Long implicitOffset) throws IOException {
        if (value == null) return;
        Matcher matcher = BYTERANGE_PATTERN.matcher(value);
        if (matcher.matches()) {
            try {
                long length = Long.parseLong(matcher.group(1));
                Long offset = matcher.group(2) == null ? implicitOffset : Long.valueOf(matcher.group(2));
                if (offset != null && length > 0 && length <= Long.MAX_VALUE - offset) {
                    segment.byteRangeOffset = offset;
                    segment.byteRangeLength = length;
                    return;
                }
            } catch (NumberFormatException ignored) {}
        }
        throw new IOException("The HLS segment has an invalid byte range.");
    }

    private static byte[] parseIv(String value) throws IOException {
        if (value == null) return null;
        if (!value.matches("0[xX][0-9a-fA-F]{1,32}")) throw new IOException("The HLS encryption IV is invalid.");
        byte[] iv = new byte[16];
        int nibble = 0;
        for (int i = value.length() - 1; i >= 2; i--, nibble++) {
            iv[15 - nibble / 2] |= (byte) (Character.digit(value.charAt(i), 16) << (4 * (nibble % 2)));
        }
        return iv;
    }
}
