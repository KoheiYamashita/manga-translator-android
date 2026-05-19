package com.manga.translate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.scale

internal data class LetterboxResult(
    val bitmap: Bitmap,
    val gain: Float,
    val padX: Float,
    val padY: Float,
    val inputWidth: Int,
    val inputHeight: Int
)

internal object OnnxImagePreprocessor {
    fun letterbox(
        bitmap: Bitmap,
        inputWidth: Int,
        inputHeight: Int,
        padColor: Int = Color.rgb(114, 114, 114),
        afterDraw: ((Canvas, Float, Float, Float) -> Unit)? = null
    ): LetterboxResult {
        val srcW = bitmap.width
        val srcH = bitmap.height
        val gain = kotlin.math.min(
            inputWidth.toFloat() / srcW,
            inputHeight.toFloat() / srcH
        ).coerceAtLeast(1e-6f)
        val newW = (srcW * gain).toInt().coerceAtLeast(1)
        val newH = (srcH * gain).toInt().coerceAtLeast(1)

        val resized = bitmap.scale(newW, newH)
        val padded = createBitmap(inputWidth, inputHeight)
        val canvas = Canvas(padded)
        canvas.drawColor(padColor)
        val padX = ((inputWidth - newW) / 2f).coerceAtLeast(0f)
        val padY = ((inputHeight - newH) / 2f).coerceAtLeast(0f)
        canvas.drawBitmap(resized, padX, padY, null)
        afterDraw?.invoke(canvas, gain, padX, padY)
        if (resized !== bitmap) {
            resized.recycle()
        }

        return LetterboxResult(
            bitmap = padded,
            gain = gain,
            padX = padX,
            padY = padY,
            inputWidth = inputWidth,
            inputHeight = inputHeight
        )
    }

    fun bitmapToRgbChwFloat(bitmap: Bitmap): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val planeSize = width * height
        val input = FloatArray(3 * planeSize)
        var offset = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap[x, y]
                input[offset] = ((pixel shr 16) and 0xFF) / 255f
                input[offset + planeSize] = ((pixel shr 8) and 0xFF) / 255f
                input[offset + 2 * planeSize] = (pixel and 0xFF) / 255f
                offset++
            }
        }
        return input
    }
}
