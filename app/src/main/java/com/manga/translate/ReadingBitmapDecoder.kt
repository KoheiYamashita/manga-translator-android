package com.manga.translate

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Rect
import kotlin.math.max

data class DecodedReadingBitmap(
    val bitmap: Bitmap,
    val sourceWidth: Int,
    val sourceHeight: Int
)

object ReadingBitmapDecoder {
    private const val DETAIL_MULTIPLIER = 2
    private const val MAX_LONG_EDGE = 8192
    private const val MAX_TOTAL_PIXELS = 16_777_216 // ~16MP
    private const val TILE_DECODE_MIN_SOURCE_HEIGHT = 8192
    private const val TILE_OUTPUT_PIXEL_BUDGET = 4_194_304 // ~4MP per tile

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
        val bitmap = if (shouldUseTiledDecode(sourceWidth, sourceHeight, sampleSize)) {
            decodeTiled(imageFile, sourceWidth, sourceHeight, sampleSize)
        } else {
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            ImageProcessingGuards.withDecodePermit(
                width = sourceWidth,
                height = sourceHeight,
                tag = "ReadingDecoder"
            ) {
                BitmapFactory.decodeFile(imageFile.absolutePath, options)
            }
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

    private fun shouldUseTiledDecode(sourceWidth: Int, sourceHeight: Int, sampleSize: Int): Boolean {
        if (sampleSize <= 1) return sourceHeight >= TILE_DECODE_MIN_SOURCE_HEIGHT
        val decodedHeight = ceilDiv(sourceHeight, sampleSize)
        return sourceHeight >= TILE_DECODE_MIN_SOURCE_HEIGHT || decodedHeight >= TILE_DECODE_MIN_SOURCE_HEIGHT / 2
    }

    private suspend fun decodeTiled(
        imageFile: java.io.File,
        sourceWidth: Int,
        sourceHeight: Int,
        sampleSize: Int
    ): Bitmap? {
        val outputWidth = ceilDiv(sourceWidth, sampleSize)
        val outputHeight = ceilDiv(sourceHeight, sampleSize)
        return ImageProcessingGuards.withDecodePermit(
            width = outputWidth,
            height = outputHeight,
            tag = "ReadingDecoderTiled"
        ) {
            val regionDecoder = runCatching {
                BitmapRegionDecoder.newInstance(imageFile.absolutePath, false)
            }.getOrNull() ?: return@withDecodePermit null
            try {
                val targetBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.RGB_565)
                val canvas = Canvas(targetBitmap)
                val sourceTileHeight = computeSourceTileHeight(
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    sampleSize = sampleSize
                )
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                var sourceTop = 0
                var outputTop = 0
                while (sourceTop < sourceHeight) {
                    val sourceBottom = minOf(sourceTop + sourceTileHeight, sourceHeight)
                    val rect = Rect(0, sourceTop, sourceWidth, sourceBottom)
                    val tile = runCatching {
                        regionDecoder.decodeRegion(rect, options)
                    }.getOrNull() ?: return@withDecodePermit null
                    canvas.drawBitmap(tile, 0f, outputTop.toFloat(), null)
                    outputTop += tile.height
                    sourceTop = sourceBottom
                    tile.recycle()
                }
                targetBitmap
            } finally {
                regionDecoder.recycle()
            }
        }
    }

    private fun computeSourceTileHeight(
        sourceWidth: Int,
        sourceHeight: Int,
        sampleSize: Int
    ): Int {
        val safeWidth = sourceWidth.coerceAtLeast(1)
        val sampledBudget = TILE_OUTPUT_PIXEL_BUDGET.toLong() * sampleSize.toLong() * sampleSize.toLong()
        val rawHeight = (sampledBudget / safeWidth).toInt()
        val roundedHeight = (rawHeight / sampleSize).coerceAtLeast(1) * sampleSize
        return roundedHeight.coerceAtLeast(sampleSize * 256).coerceAtMost(sourceHeight)
    }

    private fun ceilDiv(value: Int, divisor: Int): Int {
        return (value + divisor - 1) / divisor
    }
}
