package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

internal class PageRegionDetector(
    context: Context,
    private val settingsStore: SettingsStore = SettingsStore(context.applicationContext)
) {
    private val appContext = context.applicationContext
    private var bubbleDetector: BubbleDetector? = null
    private var textDetector: TextDetector? = null

    fun detect(bitmap: Bitmap, logTag: String = "PageRegionDetector"): PageRegionDetectionResult? {
        val bubbleDetector = getBubbleDetector(logTag) ?: return null
        val detections = filterTinyBubbleDetections(
            detections = bubbleDetector.detect(bitmap),
            bitmap = bitmap,
            logTag = logTag
        )
        val bubbleRects = detections.map { it.rect }
        val textRects = detectSupplementTextRects(bitmap, detections)
        if (textRects.isNotEmpty()) {
            AppLogger.log(logTag, "Supplemented ${textRects.size} text boxes")
        }
        val regions = buildRegions(detections, bubbleRects, textRects)
        return PageRegionDetectionResult(
            width = bitmap.width,
            height = bitmap.height,
            bubbleDetections = detections,
            textRects = textRects,
            regions = regions
        )
    }

    private fun getBubbleDetector(logTag: String): BubbleDetector? {
        if (bubbleDetector != null) return bubbleDetector
        return try {
            bubbleDetector = BubbleDetector(appContext, settingsStore = settingsStore)
            bubbleDetector
        } catch (e: Exception) {
            AppLogger.log(logTag, "Failed to init bubble detector", e)
            null
        }
    }

    private fun getTextDetector(logTag: String): TextDetector? {
        if (textDetector != null) return textDetector
        return try {
            textDetector = TextDetector(appContext, settingsStore = settingsStore)
            textDetector
        } catch (e: Exception) {
            AppLogger.log(logTag, "Failed to init text detector", e)
            null
        }
    }

    private fun detectSupplementTextRects(
        bitmap: Bitmap,
        detections: List<BubbleDetection>
    ): List<RectF> {
        val textDetector = getTextDetector("PageRegionDetector") ?: return emptyList()
        val bubbleRects = detections.map { it.rect }
        val rawTextRects = textDetector.detect(bitmap, buildSuppressionMasks(detections, bitmap))
        val filtered = filterOverlapping(rawTextRects, bubbleRects, TEXT_IOU_THRESHOLD)
        return RectGeometryDeduplicator.mergeSupplementRects(filtered, bitmap.width, bitmap.height)
    }

    private fun buildRegions(
        detections: List<BubbleDetection>,
        bubbleRects: List<RectF>,
        textRects: List<RectF>
    ): List<PageRegion> {
        val allRects = ArrayList<RectF>(bubbleRects.size + textRects.size)
        allRects.addAll(bubbleRects)
        allRects.addAll(textRects)
        val bubbleDetectorCount = bubbleRects.size
        return allRects.mapIndexed { index, rect ->
            PageRegion(
                id = index,
                rect = rect,
                source = if (index < bubbleDetectorCount) {
                    BubbleSource.BUBBLE_DETECTOR
                } else {
                    BubbleSource.TEXT_DETECTOR
                },
                maskContour = if (index < bubbleDetectorCount) {
                    detections.getOrNull(index)?.maskContour
                } else {
                    null
                }
            )
        }
    }

    private fun buildSuppressionMasks(
        detections: List<BubbleDetection>,
        bitmap: Bitmap
    ): List<TextSuppressionMask> {
        if (detections.isEmpty()) return emptyList()
        return detections.map { det ->
            val contour = det.maskContour
            if (contour != null && contour.size >= 6) {
                TextSuppressionMask.Contour(contour)
            } else {
                TextSuppressionMask.Rect(
                    padRect(det.rect, bitmap.width, bitmap.height, MASK_EXPAND_RATIO, MASK_EXPAND_MIN)
                )
            }
        }
    }

    private fun padRect(rect: RectF, width: Int, height: Int, ratio: Float, minPad: Float): RectF {
        val h = max(1f, rect.height())
        val pad = max(minPad, ratio * h)
        val left = (rect.left - pad).coerceIn(0f, width.toFloat())
        val top = (rect.top - pad).coerceIn(0f, height.toFloat())
        val right = (rect.right + pad).coerceIn(0f, width.toFloat())
        val bottom = (rect.bottom + pad).coerceIn(0f, height.toFloat())
        return RectF(left, top, right, bottom)
    }

    private fun filterOverlapping(
        textRects: List<RectF>,
        bubbleRects: List<RectF>,
        threshold: Float
    ): List<RectF> {
        if (bubbleRects.isEmpty()) return textRects
        val filtered = ArrayList<RectF>(textRects.size)
        for (rect in textRects) {
            var overlapped = false
            for (bubble in bubbleRects) {
                if (iou(rect, bubble) >= threshold || contains(bubble, rect)) {
                    overlapped = true
                    break
                }
            }
            if (!overlapped) {
                filtered.add(rect)
            }
        }
        return filtered
    }

    private fun filterTinyBubbleDetections(
        detections: List<BubbleDetection>,
        bitmap: Bitmap,
        logTag: String
    ): List<BubbleDetection> {
        if (detections.isEmpty()) return detections
        val filtered = detections.filterNot { isTinyErrorBubble(it.rect, bitmap) }
        val removedCount = detections.size - filtered.size
        if (removedCount > 0) {
            AppLogger.log(
                logTag,
                "Filtered $removedCount tiny bubble false positives, kept ${filtered.size}"
            )
        }
        return filtered
    }

    private fun isTinyErrorBubble(rect: RectF, bitmap: Bitmap): Boolean {
        val width = rect.width().coerceAtLeast(0f)
        val height = rect.height().coerceAtLeast(0f)
        if (width <= 0f || height <= 0f) return true

        val shortSide = min(width, height)
        val longSide = max(width, height)
        val imageMinSide = min(bitmap.width, bitmap.height).toFloat().coerceAtLeast(1f)
        val imageArea = (bitmap.width.toFloat() * bitmap.height.toFloat()).coerceAtLeast(1f)
        val areaRatio = (width * height) / imageArea

        val maxShortSide = max(TINY_BUBBLE_SHORT_SIDE_MIN_PX, imageMinSide * TINY_BUBBLE_SHORT_SIDE_RATIO)
        val maxLongSide = max(TINY_BUBBLE_LONG_SIDE_MIN_PX, imageMinSide * TINY_BUBBLE_LONG_SIDE_RATIO)

        return shortSide <= maxShortSide &&
            longSide <= maxLongSide &&
            areaRatio <= TINY_BUBBLE_MAX_AREA_RATIO
    }

    private fun iou(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val inter = max(0f, right - left) * max(0f, bottom - top)
        val areaA = max(0f, a.width()) * max(0f, a.height())
        val areaB = max(0f, b.width()) * max(0f, b.height())
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
    }

    private fun contains(outer: RectF, inner: RectF): Boolean {
        return outer.left <= inner.left &&
            outer.top <= inner.top &&
            outer.right >= inner.right &&
            outer.bottom >= inner.bottom
    }

    companion object {
        private const val TEXT_IOU_THRESHOLD = 0.2f
        private const val MASK_EXPAND_RATIO = 0.1f
        private const val MASK_EXPAND_MIN = 4f
        private const val TINY_BUBBLE_SHORT_SIDE_MIN_PX = 26f
        private const val TINY_BUBBLE_LONG_SIDE_MIN_PX = 56f
        private const val TINY_BUBBLE_SHORT_SIDE_RATIO = 0.032f
        private const val TINY_BUBBLE_LONG_SIDE_RATIO = 0.075f
        private const val TINY_BUBBLE_MAX_AREA_RATIO = 0.0022f
    }
}

data class PageRegion(
    val id: Int,
    val rect: RectF,
    val source: BubbleSource,
    val maskContour: FloatArray? = null
)

data class PageRegionDetectionResult(
    val width: Int,
    val height: Int,
    val bubbleDetections: List<BubbleDetection>,
    val textRects: List<RectF>,
    val regions: List<PageRegion>
)
