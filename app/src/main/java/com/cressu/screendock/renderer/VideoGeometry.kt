package com.cressu.screendock.renderer

/** How the decoded video is mapped onto the display surface. */
enum class ScaleMode {
    /** Letterbox/pillarbox: whole picture visible, aspect preserved (default). */
    FIT,
    /** Cover: aspect preserved, display fully covered, overflow cropped. */
    FILL,
    /** Distort the picture to the display's exact bounds. */
    STRETCH,
}

/** Visible (cropped) size of the decoded picture. */
data class VideoSize(val width: Int, val height: Int) {
    val aspect: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 0f

    companion object { val UNKNOWN = VideoSize(0, 0) }
}

/** Half-extents of the video quad in clip space ([-1,1]). Values > 1 are clipped by GL. */
data class QuadExtents(val halfW: Float, val halfH: Float)

internal object VideoGeometry {

    /**
     * Visible picture size from a decoder output format. [width]/[height] are the
     * aligned buffer dims (e.g. 1088); crop values are inclusive pixel coords and
     * pass -1 when absent. Falls back to the buffer size if crop is missing/invalid.
     */
    fun visibleSize(
        width: Int, height: Int,
        cropLeft: Int = -1, cropRight: Int = -1, cropTop: Int = -1, cropBottom: Int = -1,
    ): VideoSize {
        var w = width
        var h = height
        if (cropLeft >= 0 && cropRight >= cropLeft) w = cropRight - cropLeft + 1
        if (cropTop >= 0 && cropBottom >= cropTop) h = cropBottom - cropTop + 1
        return if (w > 0 && h > 0) VideoSize(w, h) else VideoSize.UNKNOWN
    }

    /**
     * Quad extents for [mode]. [videoAspect] <= 0 (unknown) or a degenerate
     * viewport yields full-viewport (1,1).
     */
    fun quadExtents(mode: ScaleMode, videoAspect: Float, viewW: Int, viewH: Int): QuadExtents {
        if (mode == ScaleMode.STRETCH || videoAspect <= 0f || viewW <= 0 || viewH <= 0) {
            return QuadExtents(1f, 1f)
        }
        val pa = viewW.toFloat() / viewH.toFloat()
        val videoWider = videoAspect >= pa
        return when (mode) {
            ScaleMode.FIT ->
                if (videoWider) QuadExtents(1f, pa / videoAspect) else QuadExtents(videoAspect / pa, 1f)
            ScaleMode.FILL ->
                if (videoWider) QuadExtents(videoAspect / pa, 1f) else QuadExtents(1f, pa / videoAspect)
            ScaleMode.STRETCH -> QuadExtents(1f, 1f)
        }
    }
}
