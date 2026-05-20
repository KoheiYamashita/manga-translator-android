package com.manga.translate

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.max

data class DecodedReadingBitmap(
    val bitmap: Bitmap,
    val sourceWidth: Int,
    val sourceHeight: Int
)

object ReadingBitmapDecoder {
    private const val DETAIL_MULTIPLIER = 1
    private const val MAX_LONG_EDGE = 4096
    private const val MAX_TOTAL_PIXELS = 4_194_304 // ~4MP

    suspend fun decode(imageFile: java.io.File, targetWidth: Int, targetHeight: Int): DecodedReadingBitmap? {
        if (ImageFileSupport.isAvifFile(imageFile.name)) {
            val safeWidth = targetWidth.coerceAtLeast(1) * DETAIL_MULTIPLIER
            val safeHeight = targetHeight.coerceAtLeast(1) * DETAIL_MULTIPLIER
            val (bitmap, size) = AvifBitmapDecoder.decodeSampled(imageFile, safeWidth, safeHeight)
            if (bitmap == null || size == null) return null
            return DecodedReadingBitmap(
                bitmap = bitmap,
                sourceWidth = size.width,
                sourceHeight = size.height
            )
        }
        val safeTargetWidth = targetWidth.coerceAtLeast(1)
        val safeTargetHeight = targetHeight.coerceAtLeast(1)
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
        val sourceWidth = bounds.outWidth
        val sourceHeight = bounds.outHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        val sampleSize = calculateInSampleSize(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            targetWidth = safeTargetWidth * DETAIL_MULTIPLIER,
            targetHeight = safeTargetHeight * DETAIL_MULTIPLIER
        )
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bitmap = ImageProcessingGuards.withDecodePermit(
            width = sourceWidth,
            height = sourceHeight,
            tag = "ReadingDecoder"
        ) {
            BitmapFactory.decodeFile(imageFile.absolutePath, options)
        } ?: return null
        return DecodedReadingBitmap(
            bitmap = bitmap,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight
        )
    }

    private fun calculateInSampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Int {
        var sample = 1
        while (
            sourceWidth / (sample * 2) >= targetWidth &&
            sourceHeight / (sample * 2) >= targetHeight
        ) {
            sample *= 2
        }
        while (
            sourceWidth / (sample * 2) >= MAX_LONG_EDGE ||
            sourceHeight / (sample * 2) >= MAX_LONG_EDGE
        ) {
            sample *= 2
        }
        while (
            sourceWidth.toLong() * sourceHeight.toLong() / ((sample * 2).toLong() * (sample * 2).toLong())
            > MAX_TOTAL_PIXELS
        ) {
            sample *= 2
        }
        return max(sample, 1)
    }
}
