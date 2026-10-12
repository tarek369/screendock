package com.cressu.screendock.renderer

import org.junit.Assert.*
import org.junit.Test

class BoundedFrameQueueTest {
    private fun f(pts: Long, key: Boolean = false) = VideoFrame(byteArrayOf(1), pts, key)

    @Test fun fifoOrder() {
        val q = BoundedFrameQueue(4)
        q.offer(f(1, true)); q.offer(f(2)); q.offer(f(3))
        assertEquals(1L, q.poll()!!.ptsUs)
        assertEquals(2L, q.poll()!!.ptsUs)
        assertEquals(3L, q.poll()!!.ptsUs)
        assertNull(q.poll())
    }

    @Test fun overflowDropsBacklogAndAwaitsKeyframe() {
        val q = BoundedFrameQueue(2)
        q.offer(f(1, true)); q.offer(f(2))
        assertEquals(OfferResult.OVERFLOWED, q.offer(f(3)))
        assertEquals(0, q.size())
        assertTrue(q.isAwaitingKeyframe())
        assertEquals(OfferResult.DROPPED_AWAITING_KEYFRAME, q.offer(f(4)))
        assertEquals(OfferResult.ACCEPTED, q.offer(f(5, true)))
        assertFalse(q.isAwaitingKeyframe())
        assertEquals(OfferResult.ACCEPTED, q.offer(f(6)))
        assertEquals(5L, q.poll()!!.ptsUs)
    }

    @Test fun keyframeSupersedesFullQueue() {
        val q = BoundedFrameQueue(2)
        q.offer(f(1, true)); q.offer(f(2))
        assertEquals(OfferResult.ACCEPTED, q.offer(f(3, true)))
        assertEquals(1, q.size())
        assertEquals(3L, q.poll()!!.ptsUs)
        assertFalse(q.isAwaitingKeyframe())
    }

    @Test fun markCorruptThenClear() {
        val q = BoundedFrameQueue(2)
        q.offer(f(1, true))
        q.markCorrupt()
        assertEquals(0, q.size())
        assertEquals(OfferResult.DROPPED_AWAITING_KEYFRAME, q.offer(f(2)))
        q.clear()
        assertEquals(OfferResult.ACCEPTED, q.offer(f(3)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroCapacityRejected() { BoundedFrameQueue(0) }

    @Test fun byteCapOverflows() {
        val q = BoundedFrameQueue(100, maxBytes = 10)
        assertEquals(OfferResult.ACCEPTED, q.offer(VideoFrame(ByteArray(6), 1, true)))
        assertEquals(OfferResult.OVERFLOWED, q.offer(VideoFrame(ByteArray(6), 2, false)))
        assertEquals(0, q.size())
    }

    @Test fun byteAccountingReleasedOnPoll() {
        val q = BoundedFrameQueue(100, maxBytes = 10)
        q.offer(VideoFrame(ByteArray(6), 1, true)); q.poll()
        assertEquals(OfferResult.ACCEPTED, q.offer(VideoFrame(ByteArray(6), 2, false)))
    }

    /** Regression: macOS start-up burst (keyframe + ~30 tiny P-frames) must not evict the keyframe. */
    @Test fun startupBurstKeepsKeyframe() {
        val q = BoundedFrameQueue(240, 24L * 1024 * 1024)
        assertEquals(OfferResult.ACCEPTED, q.offer(VideoFrame(ByteArray(591), 0, true)))
        repeat(40) { assertEquals(OfferResult.ACCEPTED, q.offer(VideoFrame(ByteArray(110), it + 1L, false))) }
        assertTrue(q.poll()!!.isKeyframe)
    }
}
