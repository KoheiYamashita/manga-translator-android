package com.manga.translate

import android.graphics.Bitmap
import android.util.Size
import com.radzivon.bartoshyk.avif.coder.HeifCoder
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

object AvifBitmapDecoder {
    private val coder = HeifCoder()

    fun decode(file: File): Bitmap? =
        runCatching {
            val bytes = file.readBytes()
            coder.decode(bytes)
        }.getOrNull()

    fun getSize(file: File): Size? =
        runCatching {
            val bytes = file.readBytes()
            coder.getSize(bytes)
        }.getOrNull()

    fun decodeSampled(file: File, targetWidth: Int, targetHeight: Int): Pair<Bitmap?, Size?> {
        val size = getSize(file)
        val bitmap = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val channel = raf.channel
                val mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                coder.decodeSampled(
                    mapped,
                    targetWidth.coerceAtLeast(1),
                    targetHeight.coerceAtLeast(1)
                )
            }
        }.getOrNull()
        return bitmap to size
    }
}
