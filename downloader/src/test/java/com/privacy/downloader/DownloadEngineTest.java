package com.privacy.downloader;

import com.privacy.downloader.model.DownloadItem;
import com.privacy.downloader.model.DownloadSegment;
import com.privacy.downloader.model.DownloadStatus;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class DownloadEngineTest {

    @Test
    public void testFormatBytes() {
        assertEquals("0 B", DownloadItem.formatBytes(0));
        assertEquals("500.0 B", DownloadItem.formatBytes(500));
        assertEquals("1.0 KB", DownloadItem.formatBytes(1024));
        assertEquals("1.5 KB", DownloadItem.formatBytes(1536));
        assertEquals("1.0 MB", DownloadItem.formatBytes(1048576));
        assertEquals("5.0 MB", DownloadItem.formatBytes(5242880));
        assertEquals("1.0 GB", DownloadItem.formatBytes(1073741824L));
    }

    @Test
    public void testEtaCalculation() {
        DownloadItem item = new DownloadItem();
        item.setTotalBytes(100 * 1024 * 1024); // 100 MB
        item.setDownloadedBytes(50 * 1024 * 1024); // 50 MB
        item.setSpeedBytesPerSec(5 * 1024 * 1024); // 5 MB/s -> 10 seconds remaining

        assertEquals("10s", item.getFormattedEta());

        item.setSpeedBytesPerSec(500 * 1024); // 500 KB/s -> ~102 seconds -> 1m 42s
        assertEquals("1m 42s", item.getFormattedEta());
    }

    @Test
    public void testProgressPercentage() {
        DownloadItem item = new DownloadItem();
        item.setTotalBytes(2000);
        item.setDownloadedBytes(500);
        assertEquals(25, item.getProgressPercentage());

        item.setDownloadedBytes(2000);
        assertEquals(100, item.getProgressPercentage());
    }

    @Test
    public void testSegmentBoundaryMath() {
        long totalBytes = 10000000L; // 10,000,000 bytes
        int numSegments = 4;

        long chunkSize = totalBytes / numSegments;
        List<DownloadSegment> segments = new ArrayList<>();

        for (int i = 0; i < numSegments; i++) {
            long start = i * chunkSize;
            long end = (i == numSegments - 1) ? (totalBytes - 1) : ((i + 1) * chunkSize - 1);
            segments.add(new DownloadSegment("task1", i, start, end, start, DownloadStatus.PENDING));
        }

        // Verify segment 0 starts at 0
        assertEquals(0, segments.get(0).getStartByte());
        // Verify contiguous boundaries: segment i+1 start == segment i end + 1
        for (int i = 0; i < numSegments - 1; i++) {
            assertEquals(segments.get(i).getEndByte() + 1, segments.get(i + 1).getStartByte());
        }
        // Verify last segment ends exactly at totalBytes - 1
        assertEquals(totalBytes - 1, segments.get(numSegments - 1).getEndByte());

        // Verify sum of segment total bytes equals totalBytes
        long sum = 0;
        for (DownloadSegment seg : segments) {
            sum += seg.getTotalBytes();
        }
        assertEquals(totalBytes, sum);
    }
}
