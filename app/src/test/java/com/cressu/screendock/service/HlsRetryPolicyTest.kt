package com.cressu.screendock.service

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsRetryPolicyTest {

    @Test fun `decoder errors retry once with H264`() {
        assertTrue(HlsRetryPolicy.shouldRetryWithH264(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, false))
        assertTrue(HlsRetryPolicy.shouldRetryWithH264(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, false))
        assertFalse(HlsRetryPolicy.shouldRetryWithH264(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, true))
    }

    @Test fun `network and source errors do not retry`() {
        assertFalse(HlsRetryPolicy.shouldRetryWithH264(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, false))
        assertFalse(HlsRetryPolicy.shouldRetryWithH264(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, false))
    }

    @Test fun `clip paused at its end still reports ready with its duration`() {
        assertTrue(HlsRetryPolicy.reportReady(Player.STATE_ENDED, live = false, durationSec = 30.0))
        assertTrue(HlsRetryPolicy.reportReady(Player.STATE_READY, live = false, durationSec = 30.0))
    }

    @Test fun `not ready while loading or without duration`() {
        assertFalse(HlsRetryPolicy.reportReady(Player.STATE_BUFFERING, live = false, durationSec = 30.0))
        assertFalse(HlsRetryPolicy.reportReady(Player.STATE_READY, live = false, durationSec = 0.0))
        assertFalse(HlsRetryPolicy.reportReady(Player.STATE_IDLE, live = false, durationSec = 30.0))
    }

    @Test fun `live streams are ready when the player is`() {
        assertTrue(HlsRetryPolicy.reportReady(Player.STATE_READY, live = true, durationSec = 0.0))
        assertFalse(HlsRetryPolicy.reportReady(Player.STATE_ENDED, live = true, durationSec = 0.0))
    }
}
