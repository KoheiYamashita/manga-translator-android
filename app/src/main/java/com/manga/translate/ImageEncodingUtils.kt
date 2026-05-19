package com.manga.translate

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

internal object ImageEncodingUtils {
    fun encodeBitmapToBase64(bitmap: Bitmap, quality: Int = chooseJpegQuality(bitmap)): String? {
        val jpegBytes = compressBitmapToJpeg(bitmap, quality) ?: return null
        return Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
    }

    fun compressBitmapToJpeg(bitmap: Bitmap, quality: Int = chooseJpegQuality(bitmap)): ByteArray? {
        return runCatching {
            ByteArrayOutputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                    return null
                }
                output.toByteArray()
            }
        }.getOrNull()
    }

    fun chooseJpegQuality(bitmap: Bitmap): Int {
        val pixelCount = bitmap.width.toLong() * bitmap.height.toLong()
        return when {
            pixelCount >= 4_000_000L -> 75
            pixelCount >= 1_500_000L -> 80
            pixelCount >= 500_000L -> 85
            else -> 90
        }
    }
}
