package com.cressu.screendock.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSourceTest {
    @Test fun classifiesHls() {
        assertEquals(VideoKind.HLS, VideoSource.classify("https://cdn.example.com/a/master.m3u8"))
        assertEquals(VideoKind.HLS, VideoSource.classify("http://192.168.1.5:7000/stream.M3U8?token=1"))
        assertEquals(VideoKind.HLS, VideoSource.classify("http://localhost:7000/master.m3u8"))
        assertEquals(VideoKind.HLS, VideoSource.classify("https://x.example/redirect?u=media.m3u8"))
    }

    @Test fun classifiesProgressive() {
        assertEquals(VideoKind.PROGRESSIVE, VideoSource.classify("https://h/movie.mp4"))
        assertEquals(VideoKind.PROGRESSIVE, VideoSource.classify("http://10.0.0.2:8080/clip.MOV"))
        assertEquals(VideoKind.PROGRESSIVE, VideoSource.classify("http://mac.local/song.m4a"))
    }

    @Test fun unknownAndInvalid() {
        assertEquals(VideoKind.UNKNOWN, VideoSource.classify("http://10.0.0.2:8080/stream"))
        assertEquals(VideoKind.UNKNOWN, VideoSource.classify("not a url"))
        assertEquals(VideoKind.UNKNOWN, VideoSource.classify(""))
    }

    @Test fun httpsAlwaysAllowed() {
        assertTrue(VideoSource.isAllowed("https://cdn.example.com/v.m3u8"))
    }

    @Test fun cleartextOnlyToLocalHosts() {
        assertTrue(VideoSource.isAllowed("http://localhost:7000/master.m3u8"))
        assertTrue(VideoSource.isAllowed("http://127.0.0.1:7000/master.m3u8"))
        assertTrue(VideoSource.isAllowed("http://192.168.20.5:49152/v.mp4"))
        assertTrue(VideoSource.isAllowed("http://10.41.52.3/v.mp4"))
        assertTrue(VideoSource.isAllowed("http://172.16.0.9/v.mp4"))
        assertTrue(VideoSource.isAllowed("http://172.31.255.1/v.mp4"))
        assertTrue(VideoSource.isAllowed("http://169.254.10.10/v.mp4"))
        assertTrue(VideoSource.isAllowed("http://Toms-Mac.local:8080/v.mp4"))
        assertTrue(VideoSource.isAllowed("http://macbook:8080/v.mp4"))
        assertTrue(VideoSource.isAllowed("http://[fe80::1c2b:3d4e:5f60:7182]:8080/v.mp4"))
        assertTrue(VideoSource.isAllowed("http://[fd12:3456::1]:8080/v.mp4"))
        assertTrue(VideoSource.isAllowed("http://[::1]:8080/v.mp4"))
    }

    @Test fun cleartextToPublicHostsRefused() {
        assertFalse(VideoSource.isAllowed("http://example.com/v.mp4"))
        assertFalse(VideoSource.isAllowed("http://8.8.8.8/v.mp4"))
        assertFalse(VideoSource.isAllowed("http://172.32.0.1/v.mp4"))
        assertFalse(VideoSource.isAllowed("http://172.15.0.1/v.mp4"))
        assertFalse(VideoSource.isAllowed("http://192.169.1.1/v.mp4"))
        assertFalse(VideoSource.isAllowed("http://[2001:db8::1]/v.mp4"))
        assertFalse(VideoSource.isAllowed("http://192.168.1.5.evil.com/v.mp4"))
    }

    @Test fun otherSchemesAndGarbageRefused() {
        assertFalse(VideoSource.isAllowed("file:///etc/passwd"))
        assertFalse(VideoSource.isAllowed("content://media/1"))
        assertFalse(VideoSource.isAllowed("rtsp://192.168.1.5/x"))
        assertFalse(VideoSource.isAllowed("mlhls://localhost/x"))
        assertFalse(VideoSource.isAllowed("http:///nohost"))
        assertFalse(VideoSource.isAllowed("garbage"))
    }

    @Test fun decodesStartPosition() {
        assertEquals(StartPosition.Seconds(42.5), StartPosition.decode(42.5f))
        assertEquals(StartPosition.Fraction(0.25), StartPosition.decode(-0.25f))
        assertEquals(StartPosition.ZERO, StartPosition.decode(0f))
        assertEquals(StartPosition.ZERO, StartPosition.decode(Float.NaN))
        assertEquals(StartPosition.ZERO, StartPosition.decode(Float.POSITIVE_INFINITY))
        // a fraction beyond the end is clamped, not honoured as "seconds"
        assertEquals(StartPosition.Fraction(1.0), StartPosition.decode(-7f))
    }

    @Test fun resolvesStartPosition() {
        assertEquals(12_500L, StartPosition.Seconds(12.5).resolveMs(null))
        assertEquals(12_500L, StartPosition.Seconds(12.5).resolveMs(60_000))
        assertNull(StartPosition.Fraction(0.5).resolveMs(null))
        assertNull(StartPosition.Fraction(0.5).resolveMs(0))
        assertEquals(30_000L, StartPosition.Fraction(0.5).resolveMs(60_000))
        assertEquals(0L, StartPosition.Fraction(0.0).resolveMs(60_000))
    }
}
