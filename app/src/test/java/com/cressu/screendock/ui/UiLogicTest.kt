package com.cressu.screendock.ui

import com.cressu.screendock.renderer.ScaleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiLogicTest {

    @Test fun countdownFormatsAndRoundsUp() {
        assertEquals("0:00", CountdownFormatter.format(0))
        assertEquals("0:00", CountdownFormatter.format(-5_000))
        assertEquals("0:01", CountdownFormatter.format(1))
        assertEquals("1:00", CountdownFormatter.format(60_000))
        assertEquals("9:59", CountdownFormatter.format(598_100))
        assertEquals("10:00", CountdownFormatter.format(600_000))
    }

    @Test fun clockFormats() {
        assertEquals("0:00", ClockFormatter.format(-1f))
        assertEquals("0:00", ClockFormatter.format(Float.NaN))
        assertEquals("3:05", ClockFormatter.format(185f))
        assertEquals("1:01:01", ClockFormatter.format(3661f))
    }

    @Test fun fractionClamps() {
        assertEquals(0f, ClockFormatter.fraction(10f, 0f), 0f)
        assertEquals(0.5f, ClockFormatter.fraction(50f, 100f), 0f)
        assertEquals(1f, ClockFormatter.fraction(500f, 100f), 0f)
    }

    @Test fun reviewAfterGoodSession() {
        val now = 1_000_000_000_000L
        val fresh = ReviewHistory()
        assertTrue(ReviewPolicy.shouldRequest(1, fresh, now, mediaActive = false))
        assertFalse(ReviewPolicy.shouldRequest(0, fresh, now, mediaActive = false))
        assertFalse(ReviewPolicy.shouldRequest(3, fresh, now, mediaActive = true))
        assertFalse(ReviewPolicy.shouldRequest(3, fresh, now, mediaActive = false, paywallOrCooldown = true))
    }

    @Test fun reviewRetriesAfterIntervalWithNewSession() {
        val now = 1_000_000_000_000L
        val asked = ReviewHistory(askCount = 1, lastAskedMs = now, sessionsAtLastAsk = 2)
        // no new good session since the last ask
        assertFalse(ReviewPolicy.shouldRequest(2, asked, now + ReviewPolicy.RETRY_INTERVAL_MS, false))
        // new session but too soon
        assertFalse(ReviewPolicy.shouldRequest(3, asked, now + ReviewPolicy.RETRY_INTERVAL_MS - 1, false))
        // new session and interval passed
        assertTrue(ReviewPolicy.shouldRequest(3, asked, now + ReviewPolicy.RETRY_INTERVAL_MS, false))
        // capped
        val maxed = asked.copy(askCount = ReviewPolicy.MAX_ASKS)
        assertFalse(ReviewPolicy.shouldRequest(9, maxed, now + 10 * ReviewPolicy.RETRY_INTERVAL_MS, false))
    }

    @Test fun legacyPromptedFlagIsRetriedImmediately() {
        // migrated legacy flag: askCount = 1, no timestamp, no session snapshot
        val legacy = ReviewHistory(askCount = 1, lastAskedMs = 0L, sessionsAtLastAsk = 0)
        assertTrue(ReviewPolicy.shouldRequest(1, legacy, 1_000_000_000_000L, false))
    }

    @Test fun reviewAfterPurchase() {
        val fresh = ReviewHistory()
        assertTrue(ReviewPolicy.shouldRequestAfterPurchase(wasPro = false, isPro = true, history = fresh))
        assertFalse(ReviewPolicy.shouldRequestAfterPurchase(wasPro = true, isPro = true, history = fresh))
        assertFalse(ReviewPolicy.shouldRequestAfterPurchase(wasPro = false, isPro = false, history = fresh))
        assertFalse(ReviewPolicy.shouldRequestAfterPurchase(false, true, ReviewHistory(askCount = ReviewPolicy.MAX_ASKS)))
    }

    @Test fun pinValidation() {
        assertTrue(PinValidator.isValidFixedPin("0123"))
        assertFalse(PinValidator.isValidFixedPin("123"))
        assertFalse(PinValidator.isValidFixedPin("12345"))
        assertFalse(PinValidator.isValidFixedPin("12a4"))
        assertEquals("1234", PinValidator.sanitize("1a2b3c4d5"))
        assertEquals("", PinValidator.sanitize("abc"))
    }

    @Test fun scaleModeCycleIsClosed() {
        assertEquals(ScaleMode.FILL, ScaleModes.next(ScaleMode.FIT))
        assertEquals(ScaleMode.STRETCH, ScaleModes.next(ScaleMode.FILL))
        assertEquals(ScaleMode.FIT, ScaleModes.next(ScaleMode.STRETCH))
        assertEquals(ScaleMode.FILL, ScaleModes.parse("fill"))
        assertEquals(ScaleMode.FIT, ScaleModes.parse("garbage"))
        assertEquals(ScaleMode.FIT, ScaleModes.parse(null))
    }

    @Test fun touchMapperFitIgnoresLetterbox() {
        // 16:9 video in a 1000x1000 view: bars top and bottom.
        assertNull(TouchMapper.normalize(500f, 50f, 1000, 1000, ScaleMode.FIT, 16f / 9f))
        val c = TouchMapper.normalize(500f, 500f, 1000, 1000, ScaleMode.FIT, 16f / 9f)
        assertNotNull(c)
        assertEquals(0.5, c!!.first, 1e-4)
        assertEquals(0.5, c.second, 1e-4)
    }

    @Test fun touchMapperStretchMapsLinearly() {
        val p = TouchMapper.normalize(250f, 750f, 1000, 1000, ScaleMode.STRETCH, 16f / 9f)!!
        assertEquals(0.25, p.first, 1e-4)
        assertEquals(0.75, p.second, 1e-4)
    }

    @Test fun touchMapperFillCropsEdges() {
        // 16:9 video filling a square: full height, width cropped; view center is video center,
        // view left edge maps to a positive offset into the picture.
        val left = TouchMapper.normalize(0f, 500f, 1000, 1000, ScaleMode.FILL, 16f / 9f)!!
        assertTrue(left.first > 0.2)
        assertEquals(0.5, left.second, 1e-4)
    }
}
