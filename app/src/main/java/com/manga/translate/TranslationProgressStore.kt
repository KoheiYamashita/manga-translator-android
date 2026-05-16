package com.manga.translate

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

enum class PageProgressStatus(val jsonValue: String) {
    PENDING("pending"),
    OCR_DONE("ocr_done"),
    SAVED("saved"),
    SKIPPED("skipped"),
    FAILED("failed");

    companion object {
        fun fromJson(value: String?): PageProgressStatus? {
            if (value.isNullOrBlank()) return null
            return entries.firstOrNull { it.jsonValue.equals(value, ignoreCase = true) }
        }
    }
}

data class PageProgressEntry(
    val status: PageProgressStatus,
    val lastError: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

internal class TranslationProgressStore {
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    private fun mutexFor(folder: File): Mutex {
        return mutexes.computeIfAbsent(folder.absolutePath) { Mutex() }
    }

    fun fileFor(folder: File): File = File(folder, FILE_NAME)

    fun load(folder: File): Map<String, PageProgressEntry> {
        val file = fileFor(folder)
        if (!file.exists()) return emptyMap()
        return try {
            parse(file.readText())
        } catch (e: Exception) {
            AppLogger.log("Progress", "Failed to load progress for ${folder.name}", e)
            emptyMap()
        }
    }

    suspend fun update(
        folder: File,
        imageName: String,
        status: PageProgressStatus,
        error: String? = null
    ) {
        if (imageName.isBlank()) return
        mutexFor(folder).withLock {
            val current = LinkedHashMap(load(folder))
            current[imageName] = PageProgressEntry(
                status = status,
                lastError = error,
                updatedAt = System.currentTimeMillis()
            )
            writeAtomic(folder, current)
        }
    }

    suspend fun clear(folder: File) {
        mutexFor(folder).withLock {
            val file = fileFor(folder)
            if (file.exists()) {
                if (!file.delete()) {
                    AppLogger.log("Progress", "Failed to delete progress for ${folder.name}")
                }
            }
        }
    }

    private fun writeAtomic(folder: File, entries: Map<String, PageProgressEntry>) {
        val file = fileFor(folder)
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            AppLogger.log("Progress", "Cannot create parent directory for ${file.absolutePath}")
            return
        }
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        try {
            tmp.writeText(serialize(entries))
            if (!tmp.renameTo(file)) {
                if (file.exists()) file.delete()
                if (!tmp.renameTo(file)) {
                    AppLogger.log("Progress", "Atomic rename failed for ${file.absolutePath}")
                }
            }
        } catch (e: Exception) {
            AppLogger.log("Progress", "Failed to write progress for ${folder.name}", e)
            tmp.delete()
        }
    }

    private fun serialize(entries: Map<String, PageProgressEntry>): String {
        val pages = JSONObject()
        for ((name, entry) in entries) {
            pages.put(name, JSONObject().apply {
                put("status", entry.status.jsonValue)
                if (!entry.lastError.isNullOrBlank()) put("lastError", entry.lastError)
                put("updatedAt", entry.updatedAt)
            })
        }
        return JSONObject()
            .put("version", VERSION)
            .put("pages", pages)
            .toString()
    }

    private fun parse(raw: String): Map<String, PageProgressEntry> {
        val json = JSONObject(raw)
        val pages = json.optJSONObject("pages") ?: return emptyMap()
        val out = LinkedHashMap<String, PageProgressEntry>(pages.length())
        val keys = pages.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val obj = pages.optJSONObject(key) ?: continue
            val status = PageProgressStatus.fromJson(obj.optString("status")) ?: continue
            out[key] = PageProgressEntry(
                status = status,
                lastError = obj.optString("lastError").takeIf { it.isNotBlank() },
                updatedAt = obj.optLong("updatedAt", 0L)
            )
        }
        return out
    }

    companion object {
        private const val FILE_NAME = ".translation_progress.json"
        private const val VERSION = 1
    }
}
