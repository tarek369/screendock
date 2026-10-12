package com.cressu.screendock.service

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log

/** Decodes cover-art bytes (JPEG/PNG) into a Bitmap of at most [MAX_DIM] px per side. Call off the main thread. */
object CoverArtDecoder {
    const val MAX_DIM = 512
    private const val MAX_BYTES = 8 * 1024 * 1024

    /** Smallest power-of-two sample size that brings the larger side to <= [maxDim]. */
    fun sampleSize(width: Int, height: Int, maxDim: Int = MAX_DIM): Int {
        if (width <= 0 || height <= 0 || maxDim <= 0) return 1
        var s = 1
        var big = maxOf(width, height)
        while (big / 2 >= maxDim) { big /= 2; s *= 2 }
        return s
    }

    fun decode(data: ByteArray): Bitmap? {
        if (data.isEmpty() || data.size > MAX_BYTES) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
            val bmp = BitmapFactory.decodeByteArray(data, 0, data.size, opts) ?: return null
            val big = maxOf(bmp.width, bmp.height)
            if (big <= MAX_DIM) bmp else {
                val f = MAX_DIM.toFloat() / big
                Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt().coerceAtLeast(1), (bmp.height * f).toInt().coerceAtLeast(1), true)
                    .also { if (it !== bmp) bmp.recycle() }
            }
        } catch (t: Throwable) {
            Log.w("AirPlayCover", "cover decode failed", t)
            null
        }
    }
}
