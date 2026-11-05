package com.ee.downloader;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class HlsPlaylistTest {
    @Test public void listsMasterVariantsWithRealResolution() {
        String playlist="#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1800000,RESOLUTION=1280x720\n720/index.m3u8\n"
                +"#EXT-X-STREAM-INF:BANDWIDTH=3400000,RESOLUTION=1920x1080\n1080/index.m3u8\n";
        List<HlsPlaylist.Variant> variants=HlsPlaylist.variants("https://example.com/master.m3u8",playlist);
        assertEquals(2,variants.size());
        assertEquals("720p",variants.get(0).label);
        assertEquals("https://example.com/720/index.m3u8",variants.get(0).url);
        assertEquals("1080p",variants.get(1).label);
    }
    @Test public void permitsOnlyUnencryptedTsVod() {
        HlsPlaylist.Media media=HlsPlaylist.media("https://example.com/hls/index.m3u8",
            "#EXTM3U\n#EXTINF:3.0,\na.ts\n#EXTINF:2.0,\nb.ts?token=1\n#EXT-X-ENDLIST\n");
        assertTrue(media.supported());
        assertEquals(2,media.segments.size());
        assertEquals("https://example.com/hls/b.ts?token=1",media.segments.get(1));
    }
    @Test public void rejectsEncryptedLiveAndFmp4() {
        assertFalse(HlsPlaylist.media("https://a.com/x.m3u8","#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\na.ts\n#EXT-X-ENDLIST").supported());
        assertFalse(HlsPlaylist.media("https://a.com/x.m3u8","#EXTM3U\na.ts\n").supported());
        assertFalse(HlsPlaylist.media("https://a.com/x.m3u8","#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\na.m4s\n#EXT-X-ENDLIST").supported());
    }
}
