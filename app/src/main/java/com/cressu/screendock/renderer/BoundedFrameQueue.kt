package com.cressu.screendock.renderer

/** One encoded NAL bundle awaiting decode. */
internal class VideoFrame(val data: ByteArray, val ptsUs: Long, val isKeyframe: Boolean)

internal enum class OfferResult { ACCEPTED, DROPPED_AWAITING_KEYFRAME, OVERFLOWED }

/**
 * Bounded FIFO of encoded frames with a "never corrupt the picture" overflow
 * policy: when full, the backlog is discarded and everything is dropped until the
 * next keyframe (a keyframe may also supersede a full queue). Thread-safe.
 */
internal class BoundedFrameQueue(
    private val capacity: Int,
    private val maxBytes: Long = Long.MAX_VALUE,
) {
    init { require(capacity > 0 && maxBytes > 0) }

    private val items = ArrayDeque<VideoFrame>()
    private var bytes = 0L
    private var awaitingKeyframe = false

    @Synchronized
    fun offer(frame: VideoFrame): OfferResult {
        if (awaitingKeyframe && !frame.isKeyframe) return OfferResult.DROPPED_AWAITING_KEYFRAME
        if (items.size >= capacity || bytes + frame.data.size > maxBytes) {
            items.clear(); bytes = 0
            if (!frame.isKeyframe) {
                awaitingKeyframe = true
                return OfferResult.OVERFLOWED
            }
        }
        if (frame.isKeyframe) awaitingKeyframe = false
        items.addLast(frame)
        bytes += frame.data.size
        return OfferResult.ACCEPTED
    }

    @Synchronized fun peek(): VideoFrame? = items.firstOrNull()

    @Synchronized fun poll(): VideoFrame? = items.removeFirstOrNull()?.also { bytes -= it.data.size }

    /** Discard the backlog and drop until the next keyframe (e.g. a frame was lost). */
    @Synchronized fun markCorrupt() { items.clear(); bytes = 0; awaitingKeyframe = true }

    /** Discard the backlog and accept anything again (decoder restart). */
    @Synchronized fun clear() { items.clear(); bytes = 0; awaitingKeyframe = false }

    @Synchronized fun size(): Int = items.size
    @Synchronized fun isAwaitingKeyframe(): Boolean = awaitingKeyframe
}
