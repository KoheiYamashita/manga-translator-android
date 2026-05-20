package com.manga.translate

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.os.Build
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.scale
import java.io.Closeable
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class PipelineDetectionBitmap(
    val bitmap: Bitmap,
    val sourceWidth: Int,
    val sourceHeight: Int
) {
    val scaleX: Float
        get() = sourceWidth / bitmap.width.toFloat().coerceAtLeast(1f)

    val scaleY: Float
        get() = sourceHeight / bitmap.height.toFloat().coerceAtLeast(1f)
}

internal object PipelineBitmapDecoder {
    private const val DETECTION_MAX_EDGE = 1920
    private const val OCR_CROP_MAX_EDGE = 2048

    suspend fun decodeForDetection(
        imageFile: File,
        maxEdge: Int = DETECTION_MAX_EDGE
    ): PipelineDetectionBitmap? {
        if (ImageFileSupport.isAvifFile(imageFile.name)) {
            val size = AvifBitmapDecoder.getSize(imageFile) ?: return null
            val target = targetSize(size.width, size.height, maxEdge)
            val (bitmap, sourceSize) = AvifBitmapDecoder.decodeSampled(
                imageFile,
                target.first,
                target.second
            )
            val source = sourceSize ?: return null
            bitmap ?: return null
            return PipelineDetectionBitmap(
                bitmap = bitmap,
                sourceWidth = source.width,
                sourceHeight = source.height
            )
        }

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
        val sourceWidth = bounds.outWidth
        val sourceHeight = bounds.outHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        val sampleSize = calculateInSampleSizeForMaxEdge(sourceWidth, sourceHeight, maxEdge)
        val bitmap = ImageProcessingGuards.withDecodePermit(
            width = sourceWidth,
            height = sourceHeight,
            tag = "PipelineDecoder"
        ) {
            BitmapFactory.decodeFile(
                imageFile.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
            )
        } ?: return null
        return PipelineDetectionBitmap(bitmap, sourceWidth, sourceHeight)
    }

    suspend fun prepareDetectionBitmap(
        source: Bitmap,
        maxEdge: Int = DETECTION_MAX_EDGE
    ): PipelineDetectionBitmap {
        val maxSourceEdge = max(source.width, source.height)
        if (maxSourceEdge <= maxEdge) {
            return PipelineDetectionBitmap(source, source.width, source.height)
        }
        val scale = maxEdge / maxSourceEdge.toFloat()
        val targetWidth = max(1, (source.width * scale).roundToInt())
        val targetHeight = max(1, (source.height * scale).roundToInt())
        val scaled = ImageProcessingGuards.withDecodePermit(
            width = source.width,
            height = source.height,
            tag = "PipelineDecoder"
        ) {
            source.scale(targetWidth, targetHeight)
        }
        return PipelineDetectionBitmap(
            bitmap = scaled,
            sourceWidth = source.width,
            sourceHeight = source.height
        )
    }

    suspend fun openCropSource(imageFile: File): BitmapCropSource? {
        return if (ImageFileSupport.isAvifFile(imageFile.name)) {
            AvifBitmapCropSource(imageFile)
        } else {
            FileBitmapRegionCropSource(imageFile)
        }
    }

    private fun targetSize(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
        val longestEdge = max(width, height).coerceAtLeast(1)
        if (longestEdge <= maxEdge) return width to height
        val scale = maxEdge / longestEdge.toFloat()
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    private fun calculateInSampleSizeForMaxEdge(
        sourceWidth: Int,
        sourceHeight: Int,
        maxEdge: Int
    ): Int {
        var sample = 1
        while (
            sourceWidth / (sample * 2) >= maxEdge ||
            sourceHeight / (sample * 2) >= maxEdge
        ) {
            sample *= 2
        }
        return max(sample, 1)
    }

    private fun calculateCropSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= maxEdge || height / (sample * 2) >= maxEdge) {
            sample *= 2
        }
        return max(sample, 1)
    }

    private class FileBitmapRegionCropSource(
        imageFile: File
    ) : BitmapCropSource {
        private val decoder = createBitmapRegionDecoder(imageFile)
        private val decodeLock = Any()

        override val width: Int = decoder.width
        override val height: Int = decoder.height

        override suspend fun decodeRegion(rect: RectF, maxEdge: Int): Bitmap? {
            val bounds = rect.toDecodeRect(width, height) ?: return null
            val sampleSize = calculateCropSampleSize(bounds.width(), bounds.height(), maxEdge)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            return ImageProcessingGuards.withDecodePermit(
                width = bounds.width(),
                height = bounds.height(),
                tag = "PipelineDecoder"
            ) {
                synchronized(decodeLock) {
                    runCatching { decoder.decodeRegion(bounds, options) }.getOrNull()
                }
            }
        }

        override fun close() {
            decoder.recycle()
        }

        private fun createBitmapRegionDecoder(imageFile: File): BitmapRegionDecoder {
            val path = imageFile.absolutePath
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(path)
            } else {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(path, false)
            }
        }
    }

    private class AvifBitmapCropSource(
        private val imageFile: File
    ) : BitmapCropSource {
        private var bitmap: Bitmap? = null

        override val width: Int by lazy {
            AvifBitmapDecoder.getSize(imageFile)?.width ?: 0
        }

        override val height: Int by lazy {
            AvifBitmapDecoder.getSize(imageFile)?.height ?: 0
        }

        override suspend fun decodeRegion(rect: RectF, maxEdge: Int): Bitmap? {
            val source = ensureBitmap() ?: return null
            val crop = cropBitmap(source, rect) ?: return null
            return scaleDownIfNeeded(crop, maxEdge)
        }

        override fun close() {
            bitmap.recycleSafely()
            bitmap = null
        }

        private suspend fun ensureBitmap(): Bitmap? {
            if (bitmap != null && bitmap?.isRecycled == false) return bitmap
            bitmap = AvifBitmapDecoder.decode(imageFile)
            return bitmap
        }
    }

    internal suspend fun scaleDownIfNeeded(bitmap: Bitmap, maxEdge: Int = OCR_CROP_MAX_EDGE): Bitmap {
        val longestEdge = max(bitmap.width, bitmap.height).coerceAtLeast(1)
        if (longestEdge <= maxEdge) return bitmap
        val scale = maxEdge / longestEdge.toFloat()
        val targetWidth = max(1, (bitmap.width * scale).roundToInt())
        val targetHeight = max(1, (bitmap.height * scale).roundToInt())
        val scaled = ImageProcessingGuards.withDecodePermit(
            width = bitmap.width,
            height = bitmap.height,
            tag = "PipelineDecoder"
        ) {
            bitmap.scale(targetWidth, targetHeight)
        }
        if (scaled !== bitmap) {
            bitmap.recycleSafely()
        }
        return scaled
    }

    internal fun clampRect(rect: RectF, width: Int, height: Int): RectF? {
        if (width <= 0 || height <= 0) return null
        val left = rect.left.coerceIn(0f, width.toFloat())
        val top = rect.top.coerceIn(0f, height.toFloat())
        val right = rect.right.coerceIn(0f, width.toFloat())
        val bottom = rect.bottom.coerceIn(0f, height.toFloat())
        if (right - left < 2f || bottom - top < 2f) return null
        return RectF(left, top, right, bottom)
    }
}

internal interface BitmapCropSource : Closeable {
    val width: Int
    val height: Int

    suspend fun decodeRegion(rect: RectF, maxEdge: Int = 2048): Bitmap?
}

internal fun PageRegionDetectionResult.remapToSource(
    sourceWidth: Int,
    sourceHeight: Int
): PageRegionDetectionResult {
    if (width <= 0 || height <= 0) return this
    if (width == sourceWidth && height == sourceHeight) return this
    val scaleX = sourceWidth / width.toFloat()
    val scaleY = sourceHeight / height.toFloat()
    return PageRegionDetectionResult(
        width = sourceWidth,
        height = sourceHeight,
        bubbleDetections = bubbleDetections.map { detection ->
            detection.copy(rect = detection.rect.scaleBy(scaleX, scaleY))
        },
        textRects = textRects.map { rect -> rect.scaleBy(scaleX, scaleY) },
        regions = regions.map { region ->
            region.copy(rect = region.rect.scaleBy(scaleX, scaleY))
        }
    )
}

private fun RectF.scaleBy(scaleX: Float, scaleY: Float): RectF {
    return RectF(
        left * scaleX,
        top * scaleY,
        right * scaleX,
        bottom * scaleY
    )
}

private fun RectF.toDecodeRect(bitmapWidth: Int, bitmapHeight: Int): Rect? {
    val left = left.toInt().coerceIn(0, max(0, bitmapWidth - 1))
    val top = top.toInt().coerceIn(0, max(0, bitmapHeight - 1))
    val right = max(left + 1, min(bitmapWidth, right.toInt().coerceAtLeast(1)))
    val bottom = max(top + 1, min(bitmapHeight, bottom.toInt().coerceAtLeast(1)))
    if (right <= left || bottom <= top) return null
    return Rect(left, top, right, bottom)
}
