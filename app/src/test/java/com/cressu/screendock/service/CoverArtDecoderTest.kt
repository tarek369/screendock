package com.cressu.screendock.service

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverArtDecoderTest {
    @Test fun `small image not subsampled`() = assertEquals(1, CoverArtDecoder.sampleSize(300, 300))
    @Test fun `1024 halves to 512`() = assertEquals(2, CoverArtDecoder.sampleSize(1024, 800))
    @Test fun `large uses power of two`() = assertEquals(4, CoverArtDecoder.sampleSize(2400, 2400))
    @Test fun `bad dims are safe`() = assertEquals(1, CoverArtDecoder.sampleSize(0, 0))
}
