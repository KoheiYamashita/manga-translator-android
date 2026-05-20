package com.manga.translate

import android.graphics.Bitmap
import android.util.Size
import com.radzivon.bartoshyk.avif.coder.HeifCoder
import java.io.File

object AvifBitmapDecoder {
    private val coder = HeifCoder()

    suspend fun decode(file: File): Bitmap? {
        val size = getSize(file) ?: return null
        return ImageProcessingGuards.withDecodePermit(
            width = size.width,
            height = size.height,
            tag = "AvifDecoder"
        ) {
            runCatching {
                val bytes = file.readBytes()
                coder.decode(bytes)
            }.getOrNull()
        }
    }

    fun getSize(file: File): Size? =
        runCatching {
            val bytes = file.readBytes()
            coder.getSize(bytes)
        }.getOrNull()

    suspend fun decodeSampled(file: File, targetWidth: Int, targetHeight: Int): Pair<Bitmap?, Size?> {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null to null
        val size = runCatching { coder.getSize(bytes) }.getOrNull()
        val guardWidth = size?.width ?: targetWidth
        val guardHeight = size?.height ?: targetHeight
        val bitmap = ImageProcessingGuards.withDecodePermit(
            width = guardWidth,
            height = guardHeight,
            tag = "AvifDecoder"
        ) {
            runCatching {
                coder.decodeSampled(
                    bytes,
                    targetWidth.coerceAtLeast(1),
                    targetHeight.coerceAtLeast(1)
                )
            }.getOrNull()
        }
        return bitmap to size
    }
}
