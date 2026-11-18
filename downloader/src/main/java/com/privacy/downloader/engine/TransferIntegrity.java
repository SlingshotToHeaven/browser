package com.privacy.downloader.engine;

import com.privacy.downloader.model.DownloadItem;
import com.privacy.downloader.model.DownloadSegment;
import com.privacy.downloader.model.DownloadStatus;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Direct HTTP transfers only; stream and blob downloads have separate protocols. */
final class TransferIntegrity {
    static final int MAX_SEGMENTS = 16;
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)", Pattern.CASE_INSENSITIVE);

    private TransferIntegrity() {}

    static final class Failure extends IOException {
        Failure(String message) {
            super(message);
        }
    }

    static Failure startOver(String reason) {
        return new Failure(reason + " Choose Start over to download from the beginning.");
    }

    static boolean isStrongEtag(String value) {
        if (value == null || value.length() < 2 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') {
            return false;
        }
        // RFC 9110 etagc: visible characters other than quotes, plus obs-text.
        for (int i = 1; i < value.length() - 1; i++) {
            char c = value.charAt(i);
            if (c < 0x21 || c == '"' || c == 0x7f || c > 0xff) return false;
        }
        return true;
    }

    static boolean hasVerifiedRanges(DownloadItem item) {
        return !item.isFullDownloadOnly() && item.isSupportsRange() && item.getTotalBytes() > 0 && isStrongEtag(item.getEtag())
                && item.getVerifiedRangeUrl() != null;
    }

    static void clearRepresentation(DownloadItem item) {
        item.setVerifiedRangeUrl(null);
        item.setEtag(null);
        item.setLastModified(null);
        item.setTotalBytes(-1);
        item.setSupportsRange(false);
    }

    static void resetForFullDownload(DownloadItem item) {
        clearRepresentation(item);
        item.setFullDownloadOnly(true); // Explicit Start over skips range negotiation.
        item.setNumSegments(1);
        item.setTempPath(null);
        item.setDownloadedBytes(0);
        item.setSpeedBytesPerSec(0);
        item.setFinishedTime(0);
        item.setSegments(Collections.emptyList());
        item.setStatus(DownloadStatus.PENDING);
        item.setErrorMessage(null);
    }

    static boolean isDirectHttp(DownloadItem item) {
        return isDirectHttp(item, item.getUrl());
    }

    static boolean isDirectHttp(DownloadItem item, String url) {
        String audio = item.getAudioUrl();
        return url != null && (url.startsWith("https://") || url.startsWith("http://"))
                && !StreamDetector.isStream(url, item.getMimeType()) && (audio == null || audio.trim().isEmpty());
    }

    /** Returns the representation length only for a response covering the exact requested interval. */
    static long matchingRangeTotal(String header, long start, long end) {
        if (header == null) return -1;
        Matcher range = CONTENT_RANGE.matcher(header.trim());
        if (!range.matches()) return -1;
        try {
            long actualStart = Long.parseLong(range.group(1));
            long actualEnd = Long.parseLong(range.group(2));
            long total = Long.parseLong(range.group(3));
            return start >= 0 && end >= start && actualStart == start && actualEnd == end && total > end ? total : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static boolean identityEncoding(String encoding) {
        return encoding == null || "identity".equalsIgnoreCase(encoding.trim());
    }

    /** Validate saved positions before creating (or changing the length of) a partial file. */
    static boolean reuseCheckpoint(DownloadItem item, File partial) throws Failure {
        if (partial != null && partial.exists() && !partial.isFile()) {
            throw new Failure("The partial download path is not a file.");
        }
        List<DownloadSegment> segments = item.getSegments();
        boolean hasProgress = item.getDownloadedBytes() > 0;
        if (segments != null) {
            for (DownloadSegment segment : segments) {
                if (segment.getCurrentByte() != segment.getStartByte() || segment.getStatus() == DownloadStatus.COMPLETED) {
                    hasProgress = true;
                    break;
                }
            }
        }
        long length = partial == null ? 0 : partial.length();
        if (!hasProgress && length == 0) return false;
        if (partial == null || !Files.isRegularFile(partial.toPath(), LinkOption.NOFOLLOW_LINKS) || length == 0) {
            throw startOver("The saved partial file is missing or truncated.");
        }
        if (!hasVerifiedRanges(item)) {
            throw startOver("The saved bytes could not be verified for this download link.");
        }
        if (length > item.getTotalBytes() || !hasCoverage(item, length, false)) {
            throw startOver("The partial file and its saved byte ranges do not match.");
        }
        return true;
    }

    static boolean readyToPublish(DownloadItem item, File partial) {
        long length = partial.length();
        return partial.isFile() && length > 0 && hasCoverage(item, length, true);
    }

    private static boolean hasCoverage(DownloadItem item, long length, boolean finished) {
        List<DownloadSegment> segments = item.getSegments();
        if (segments == null || segments.isEmpty() || segments.size() > MAX_SEGMENTS) return false;
        long total = item.getTotalBytes();
        if (total <= 0) {
            // A single full response with no known length is publishable only after EOF.
            DownloadSegment only = segments.get(0);
            return finished && segments.size() == 1 && Objects.equals(item.getId(), only.getTaskId())
                    && only.getIndex() == 0 && only.getStartByte() == 0 && only.getEndByte() == -1
                    && only.isComplete() && only.getCurrentByte() == length;
        }
        if (finished && total != length) return false;
        String taskId = item.getId();
        long next = 0;
        for (int i = 0; i < segments.size(); i++) {
            DownloadSegment segment = segments.get(i);
            synchronized (segment) {
                long end = segment.getEndByte();
                long position = segment.getCurrentByte();
                boolean claimedComplete = finished || position > end || segment.getStatus() == DownloadStatus.COMPLETED;
                if (!Objects.equals(taskId, segment.getTaskId()) || segment.getIndex() != i
                        || segment.getStartByte() != next || end < next || end >= total
                        || position < next || position > end + 1 || (position > next && position > length)
                        || (claimedComplete && !segment.isComplete())) {
                    return false;
                }
                next = end + 1;
            }
        }
        return next == total;
    }
}
