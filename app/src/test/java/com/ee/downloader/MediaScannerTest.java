package com.ee.downloader;

import org.junit.Test;
import static org.junit.Assert.*;

public class MediaScannerTest {
    @Test public void acceptsRealHttpUrlsOnly() {
        assertTrue(MediaScanner.isHttpUrl("https://example.org/a.mp4"));
        assertFalse(MediaScanner.isHttpUrl("javascript:alert(1)"));
        assertFalse(MediaScanner.isHttpUrl("file:///sdcard/a.mp4"));
        assertFalse(MediaScanner.isHttpUrl("blob:https://example.org/123"));
    }

    @Test public void findsVideoAndAudioLinks() {
        assertTrue(MediaScanner.looksLikeMedia("https://example.org/clips/720p.mp4?token=x"));
        assertTrue(MediaScanner.looksLikeMedia("https://example.org/audio/music.mp3"));
        assertFalse(MediaScanner.looksLikeMedia("https://example.org/watch?v=123"));
    }

    @Test public void classifiesManifestsWithoutPretendingDirectDownloadWorks() {
        assertTrue(MediaScanner.isStreamManifest("https://example.org/master.m3u8?access=1"));
        assertTrue(MediaScanner.isStreamManifest("https://example.org/stream.mpd"));
        assertFalse(MediaScanner.isStreamManifest("https://example.org/song.mp3"));
    }

    @Test public void detectsQualityWhenPresent() {
        assertEquals("720p", MediaScanner.quality("https://example.org/video_720p.mp4"));
        assertEquals("1080p", MediaScanner.quality("https://example.org/v.mp4?quality=1080"));
    }

    @Test public void cleansNames() {
        assertEquals("video.mp4", MediaScanner.safeFilename("https://example.org/path/video.mp4?x=1"));
        assertEquals("v.mp3", MediaScanner.safeFilename("https://example.org/v.mp3"));
    }
}
