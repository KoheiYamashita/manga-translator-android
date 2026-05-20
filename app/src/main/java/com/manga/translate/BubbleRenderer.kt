package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import androidx.core.graphics.withTranslation
import kotlin.math.min

class BubbleRenderer(context: Context) {
    private val resources = context.resources
    private val bubbleRenderSettings = SettingsStore(context.applicationContext).loadNormalBubbleRenderSettings()
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1B1B1B.toInt()
    }
    private val minAreaPerCharSp = bubbleRenderSettings.minAreaPerCharSp
    private val hardMinTextSizePx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        4f,
        resources.displayMetrics
    )
    private val textSizeStepPx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        0.5f,
        resources.displayMetrics
    ).coerceAtLeast(0.5f)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val bubblePath = Path()
    private val bubbleBounds = RectF()
    private val textRect = RectF()

    suspend fun render(
        source: Bitmap,
        translation: TranslationResult,
        verticalLayoutEnabled: Boolean
    ): Bitmap {
        return ImageProcessingGuards.withRenderPermit(
            width = source.width,
            height = source.height,
            tag = "BubbleRenderer"
        ) {
            try {
                renderInternal(source, translation, verticalLayoutEnabled)
            } catch (e: OutOfMemoryError) {
                AppLogger.log(
                    "BubbleRenderer",
                    "Render OOM for ${source.width}x${source.height}, bubbles=${translation.bubbles.size}",
                    e
                )
                throw e
            }
        }
    }

    private fun renderInternal(
        source: Bitmap,
        translation: TranslationResult,
        verticalLayoutEnabled: Boolean
    ): Bitmap {
        val output = ensureMutableArgbBitmap(source)
        val canvas = Canvas(output)
        val scaleX = if (translation.width > 0) {
            output.width.toFloat() / translation.width.toFloat()
        } else {
            1f
        }
        val scaleY = if (translation.height > 0) {
            output.height.toFloat() / translation.height.toFloat()
        } else {
            1f
        }
        for (bubble in translation.bubbles) {
            val text = bubble.text.trim()
            if (text.isBlank()) continue
            fillPaint.alpha = resolveBubbleOpacityAlpha(bubble)
            BubbleShapePaths.buildPath(
                outPath = bubblePath,
                bubble = bubble,
                sourceWidth = translation.width,
                sourceHeight = translation.height,
                originX = 0f,
                originY = 0f,
                scaleX = scaleX,
                scaleY = scaleY,
                shrinkPercent = resolveBubbleShrinkPercent(bubble)
            )
            drawBubble(canvas, text, bubblePath, verticalLayoutEnabled)
        }
        return output
    }

    private fun ensureMutableArgbBitmap(source: Bitmap): Bitmap {
        return if (source.config == Bitmap.Config.ARGB_8888 && source.isMutable) {
            source
        } else {
            source.copy(Bitmap.Config.ARGB_8888, true)
                ?: throw OutOfMemoryError("Failed to allocate mutable ARGB_8888 bitmap copy")
        }
    }

    private fun resolveBubbleShrinkPercent(bubble: BubbleTranslation): Int {
        return if (bubble.source == BubbleSource.TEXT_DETECTOR) {
            bubbleRenderSettings.freeBubbleShrinkPercent
        } else {
            bubbleRenderSettings.shrinkPercent
        }
    }

    private fun resolveBubbleOpacityAlpha(bubble: BubbleTranslation): Int {
        val opacityPercent = if (bubble.source == BubbleSource.TEXT_DETECTOR) {
            bubbleRenderSettings.freeBubbleOpacityPercent
        } else {
            bubbleRenderSettings.opacityPercent
        }
        return ((opacityPercent.coerceIn(0, 100) / 100f) * 255f).toInt()
    }

    private fun drawBubble(canvas: Canvas, text: String, path: Path, verticalLayoutEnabled: Boolean) {
        path.computeBounds(bubbleBounds, true)
        if (bubbleBounds.width() <= 0f || bubbleBounds.height() <= 0f) return
        val textRect = BubbleTextScaling.resolveAreaAdjustedTextRect(
            text, path, minAreaPerCharSp, resources.displayMetrics.density
        )
        if (textRect.width() <= 0f || textRect.height() <= 0f) return
        canvas.drawPath(path, fillPaint)
        drawTextInRect(canvas, text, textRect, verticalLayoutEnabled)
    }

    private fun drawTextInRect(
        canvas: Canvas,
        text: String,
        rect: RectF,
        verticalLayoutEnabled: Boolean
    ) {
        if (verticalLayoutEnabled) {
            drawVerticalTextInRect(canvas, VerticalTextSymbolConverter.convert(text), rect)
        } else {
            val textSize = resolveHorizontalTextSize(rect, text)
            val layout = buildLayout(text, rect.width().toInt().coerceAtLeast(1), textSize)
            canvas.save()
            canvas.translate(rect.centerX(), rect.centerY())
            canvas.translate(-layout.width / 2f, -layout.height / 2f)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    private fun resolveHorizontalTextSize(rect: RectF, text: String): Float {
        return BubbleTextScaling.findDefaultHorizontalTextSize(
            text = text,
            maxWidth = rect.width().toInt().coerceAtLeast(1),
            maxHeight = rect.height().toInt().coerceAtLeast(1),
            minTextSizePx = hardMinTextSizePx,
            buildLayout = ::buildLayout,
            layoutFits = BubbleTextScaling::layoutFits
        )
    }

    private fun buildLayout(text: String, width: Int, textSize: Float): StaticLayout {
        textPaint.textSize = textSize
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .build()
    }

    private fun drawVerticalTextInRect(canvas: Canvas, text: String, rect: RectF) {
        val maxWidth = rect.width().toInt().coerceAtLeast(1)
        val maxHeight = rect.height().toInt().coerceAtLeast(1)
        val textSize = findDefaultVerticalTextSize(text, maxWidth, maxHeight, rect.width() / 2.2f)
        val layout = buildVerticalLayout(text, maxWidth, maxHeight, textSize)
        val dx = rect.right - ((rect.width() - layout.totalWidth) / 2f) - layout.columnWidth
        val dy = rect.top + ((rect.height() - layout.totalHeight) / 2f) - layout.fontMetrics.ascent
        var col = 0
        var row = 0
        for (ch in text) {
            if (ch == '\n') {
                col += 1
                row = 0
                continue
            }
            if (row >= layout.maxRows) {
                col += 1
                row = 0
            }
            if (col >= layout.columns) break
            val glyph = ch.toString()
            val charWidth = textPaint.measureText(glyph)
            val x = dx - col * layout.columnWidth + (layout.columnWidth - charWidth) / 2f
            val y = dy + row * layout.lineHeight
            canvas.drawText(glyph, x, y, textPaint)
            row += 1
        }
    }

    private fun findDefaultVerticalTextSize(
        text: String,
        maxWidth: Int,
        maxHeight: Int,
        initialSize: Float
    ): Float {
        val maxTextSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            42f,
            resources.displayMetrics
        )
        var textSize = initialSize.coerceIn(hardMinTextSizePx, maxTextSize)
        var layout = buildVerticalLayout(text, maxWidth, maxHeight, textSize)
        while ((layout.columnWidth <= 0f || layout.lineHeight <= 0f || !layout.fits) && textSize > hardMinTextSizePx) {
            textSize = (textSize - textSizeStepPx).coerceAtLeast(hardMinTextSizePx)
            layout = buildVerticalLayout(text, maxWidth, maxHeight, textSize)
        }
        return textSize
    }

    private fun buildVerticalLayout(
        text: String,
        maxWidth: Int,
        maxHeight: Int,
        textSize: Float
    ): VerticalLayout {
        textPaint.textSize = textSize
        val fontMetrics = textPaint.fontMetrics
        val lineHeight = (fontMetrics.descent - fontMetrics.ascent).coerceAtLeast(1f)
        val maxRows = (maxHeight / lineHeight).toInt().coerceAtLeast(1)
        val charCount = text.count { it != '\n' }.coerceAtLeast(1)
        var maxCharWidth = 0f
        for (ch in text) {
            if (ch == '\n') continue
            val width = textPaint.measureText(ch.toString())
            if (width > maxCharWidth) {
                maxCharWidth = width
            }
        }
        if (maxCharWidth <= 0f) {
            maxCharWidth = textPaint.measureText("国")
        }
        maxCharWidth = maxCharWidth.coerceAtLeast(1f)
        val columns = ((charCount + maxRows - 1) / maxRows).coerceAtLeast(1)
        val totalWidth = columns * maxCharWidth
        val totalHeight = maxRows * lineHeight
        val fits = totalWidth <= maxWidth && totalHeight <= maxHeight
        return VerticalLayout(
            columnWidth = maxCharWidth,
            lineHeight = lineHeight,
            maxRows = maxRows,
            columns = columns,
            totalWidth = totalWidth,
            totalHeight = totalHeight,
            fontMetrics = fontMetrics,
            fits = fits
        )
    }

    private data class VerticalLayout(
        val columnWidth: Float,
        val lineHeight: Float,
        val maxRows: Int,
        val columns: Int,
        val totalWidth: Float,
        val totalHeight: Float,
        val fontMetrics: Paint.FontMetrics,
        val fits: Boolean
    )
}
