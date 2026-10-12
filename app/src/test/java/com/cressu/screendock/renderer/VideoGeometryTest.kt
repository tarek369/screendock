package com.cressu.screendock.renderer

import org.junit.Assert.*
import org.junit.Test

class VideoGeometryTest {
    private val eps = 1e-4f

    @Test fun cropRemovesAlignmentPadding() {
        val s = VideoGeometry.visibleSize(1920, 1088, 0, 1919, 0, 1079)
        assertEquals(VideoSize(1920, 1080), s)
        assertEquals(16f / 9f, s.aspect, eps)
    }

    @Test fun missingCropFallsBackToBuffer() =
        assertEquals(VideoSize(1280, 720), VideoGeometry.visibleSize(1280, 720))

    @Test fun invalidCropIgnored() =
        assertEquals(VideoSize(100, 50), VideoGeometry.visibleSize(100, 50, 10, 5, -1, -1))

    @Test fun zeroSizeIsUnknown() {
        assertEquals(VideoSize.UNKNOWN, VideoGeometry.visibleSize(0, 0))
        assertEquals(0f, VideoSize.UNKNOWN.aspect, 0f)
    }

    @Test fun fitLetterboxesWideVideoInTallView() {
        val e = VideoGeometry.quadExtents(ScaleMode.FIT, 16f / 9f, 1000, 1000)
        assertEquals(1f, e.halfW, eps)
        assertEquals(9f / 16f, e.halfH, eps)
    }

    @Test fun fitPillarboxesTallVideoInWideView() {
        val e = VideoGeometry.quadExtents(ScaleMode.FIT, 1f, 2000, 1000)
        assertEquals(0.5f, e.halfW, eps)
        assertEquals(1f, e.halfH, eps)
    }

    @Test fun fillCoversViewport() {
        val wide = VideoGeometry.quadExtents(ScaleMode.FILL, 2f, 1000, 1000)
        assertEquals(2f, wide.halfW, eps); assertEquals(1f, wide.halfH, eps)
        val tall = VideoGeometry.quadExtents(ScaleMode.FILL, 0.5f, 1000, 1000)
        assertEquals(1f, tall.halfW, eps); assertEquals(2f, tall.halfH, eps)
    }

    @Test fun stretchAndUnknownAreFullViewport() {
        assertEquals(QuadExtents(1f, 1f), VideoGeometry.quadExtents(ScaleMode.STRETCH, 2f, 10, 10))
        assertEquals(QuadExtents(1f, 1f), VideoGeometry.quadExtents(ScaleMode.FIT, 0f, 10, 10))
        assertEquals(QuadExtents(1f, 1f), VideoGeometry.quadExtents(ScaleMode.FIT, 2f, 0, 10))
    }

    @Test fun matchingAspectFitEqualsFill() {
        assertEquals(QuadExtents(1f, 1f), VideoGeometry.quadExtents(ScaleMode.FIT, 2f, 200, 100))
        assertEquals(QuadExtents(1f, 1f), VideoGeometry.quadExtents(ScaleMode.FILL, 2f, 200, 100))
    }
}
