package com.privacy.browser.media;

import android.net.Uri;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class MediaSnifferTest {

    private final String TAB_ID = "test_tab_1";

    @Before
    public void setUp() {
        MediaSniffer.clearTab(TAB_ID);
    }

    @After
    public void tearDown() {
        MediaSniffer.clearTab(TAB_ID);
    }

    @Test
    public void testIsVimeoUrl() {
        assertTrue(MediaSniffer.isVimeoUrl("https://vimeo.com/1231004615"));
        assertTrue(MediaSniffer.isVimeoUrl("https://player.vimeo.com/video/1231004615?h=abcd1234"));
        assertFalse(MediaSniffer.isVimeoUrl("https://example.com/video/123"));
    }

    @Test
    public void testIsYouTubeUrl() {
        assertTrue(MediaSniffer.isYouTubeUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertTrue(MediaSniffer.isYouTubeUrl("https://m.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertTrue(MediaSniffer.isYouTubeUrl("https://youtu.be/dQw4w9WgXcQ"));
        assertTrue(MediaSniffer.isYouTubeUrl("https://www.youtube.com/shorts/dQw4w9WgXcQ"));
        assertFalse(MediaSniffer.isYouTubeUrl("https://example.com/watch?v=dQw4w9WgXcQ"));
    }

    @Test
    public void testParseYouTubeStreamingData() {
        String sampleJson = "{" +
                "  \"videoDetails\": {\"videoId\": \"dQw4w9WgXcQ\", \"title\": \"Privacy Test Video\"}," +
                "  \"streamingData\": {" +
                "    \"formats\": [" +
                "      {\"url\": \"https://rr1---sn-test.googlevideo.com/videoplayback?itag=18\", \"qualityLabel\": \"360p\", \"contentLength\": \"10485760\"}" +
                "    ]," +
                "    \"adaptiveFormats\": [" +
                "      {\"url\": \"https://rr1---sn-test.googlevideo.com/videoplayback?itag=313\", \"mimeType\": \"video/mp4; codecs=vp9\", \"qualityLabel\": \"2160p\", \"contentLength\": \"524288000\"}," +
                "      {\"url\": \"https://rr1---sn-test.googlevideo.com/videoplayback?itag=137\", \"mimeType\": \"video/mp4; codecs=avc1\", \"qualityLabel\": \"1080p\", \"contentLength\": \"104857600\"}" +
                "    ]" +
                "  }" +
                "}";

        MediaSniffer.parseYouTubeData(TAB_ID, "https://m.youtube.com/watch?v=dQw4w9WgXcQ", "Test Page", sampleJson);

        List<MediaSniffer.SniffedMedia> media = MediaSniffer.getMediaForTab(TAB_ID);
        assertEquals(3, media.size());
        assertEquals("360p", media.get(0).getQuality());
        assertEquals("2160p", media.get(1).getQuality());
        assertEquals(524288000, media.get(1).getFileSize());
        assertEquals("1080p", media.get(2).getQuality());
        assertEquals(104857600, media.get(2).getFileSize());
    }

    @Test
    public void testDirectVideoRegistration() {
        MediaSniffer.registerMedia(TAB_ID, "https://example.com/video.mp4", "Demo Video", "video", "1080p", "https://example.com/", 50000000);

        List<MediaSniffer.SniffedMedia> media = MediaSniffer.getMediaForTab(TAB_ID);
        assertEquals(1, media.size());
        assertEquals("Demo Video", media.get(0).getTitle());
        assertEquals("1080p", media.get(0).getQuality());
        assertEquals(50000000, media.get(0).getFileSize());
        assertEquals("47.7 MB", media.get(0).getFormattedSize());
    }

    @Test
    public void testExtractVideoId() {
        assertEquals("yt_dQw4w9WgXcQ", MediaSniffer.extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertEquals("yt_dQw4w9WgXcQ", MediaSniffer.extractVideoId("https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=10s"));
        assertEquals("yt_dQw4w9WgXcQ", MediaSniffer.extractVideoId("https://youtu.be/dQw4w9WgXcQ"));
        assertEquals("yt_dQw4w9WgXcQ", MediaSniffer.extractVideoId("https://www.youtube.com/shorts/dQw4w9WgXcQ"));
        assertEquals("vimeo_123456789", MediaSniffer.extractVideoId("https://vimeo.com/123456789"));
        assertNull(MediaSniffer.extractVideoId("https://example.com/other-page"));
    }

    @Test
    public void testExtractJsonAfterMarker() {
        String script = "<script>var ytInitialPlayerResponse = {\"videoDetails\":{\"videoId\":\"abc12345678\"},\"nested\":{\"prop\":\"val\"}};</script>";
        String json = MediaSniffer.extractJsonAfterMarker(script, "ytInitialPlayerResponse");
        assertNotNull(json);
        assertTrue(json.startsWith("{\"videoDetails\""));
        assertTrue(json.endsWith("}}"));
    }

    @Test
    public void testStaleMediaPurgedOnNewVideo() {
        String videoA = "{" +
                "  \"videoDetails\": {\"videoId\": \"11111111111\", \"title\": \"First Video Title\"}," +
                "  \"streamingData\": {" +
                "    \"formats\": [{\"url\": \"https://stream-a.com/video\", \"qualityLabel\": \"720p\", \"contentLength\": \"1000\"}]" +
                "  }" +
                "}";

        MediaSniffer.parseYouTubeData(TAB_ID, "https://m.youtube.com/watch?v=11111111111", "First Video Title", videoA);
        List<MediaSniffer.SniffedMedia> firstCatch = MediaSniffer.getMediaForTab(TAB_ID);
        assertEquals(1, firstCatch.size());
        assertEquals("First Video Title (720p)", firstCatch.get(0).getTitle());

        String videoB = "{" +
                "  \"videoDetails\": {\"videoId\": \"22222222222\", \"title\": \"Second Video Title\"}," +
                "  \"streamingData\": {" +
                "    \"formats\": [{\"url\": \"https://stream-b.com/video\", \"qualityLabel\": \"1080p\", \"contentLength\": \"5000\"}]" +
                "  }" +
                "}";

        MediaSniffer.parseYouTubeData(TAB_ID, "https://m.youtube.com/watch?v=22222222222", "Second Video Title", videoB);
        List<MediaSniffer.SniffedMedia> secondCatch = MediaSniffer.getMediaForTab(TAB_ID);
        assertEquals(1, secondCatch.size());
        assertEquals("Second Video Title (1080p)", secondCatch.get(0).getTitle());
        assertEquals(5000, secondCatch.get(0).getFileSize());
    }
}
