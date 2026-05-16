package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.Locale

class FloatingTranslationCacheStore(context: Context) {
    private val cacheFile = File(context.cacheDir, CACHE_FILE_NAME)
    private val textEntries =
        LinkedHashMap<String, TextCacheEntry>(TEXT_CACHE_LIMIT, 0.75f, true)
    private val imageEntries =
        LinkedHashMap<String, ImageCacheEntry>(IMAGE_CACHE_LIMIT, 0.75f, true)

    init {
        loadFromDisk()
    }

    @Synchronized
    fun findTextTranslation(
        text: String,
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH
    ): CacheLookupResult? {
        val exactKey = buildExactTextKey(text, language)
        if (exactKey.isBlank()) return null
        val exact = textEntries[exactKey]
        if (exact != null) {
            return CacheLookupResult(exact.translation, matchedBySimilarity = false)
        }
        val normalized = buildSimilarityTextKey(text)
        if (normalized.isBlank() || normalized.length < MIN_SIMILARITY_TEXT_LENGTH) {
            return null
        }
        val bestEntry = textEntries.values
            .asSequence()
            .filter { candidate ->
                val lengthRatio = normalized.length.toFloat() / candidate.normalized.length.toFloat()
                lengthRatio in MIN_SIMILARITY_LENGTH_RATIO..MAX_SIMILARITY_LENGTH_RATIO
            }
            .map { entry -> entry to normalizedSimilarity(normalized, entry.normalized) }
            .maxByOrNull { it.second }
            ?: return null
        val threshold = if (normalized.length >= LONG_TEXT_THRESHOLD) {
            LONG_TEXT_SIMILARITY_THRESHOLD
        } else {
            SHORT_TEXT_SIMILARITY_THRESHOLD
        }
        if (bestEntry.second < threshold) {
            return null
        }
        return CacheLookupResult(bestEntry.first.translation, matchedBySimilarity = true)
    }

    @Synchronized
    fun putTextTranslation(
        text: String,
        translation: String,
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH
    ) {
        val exactKey = buildExactTextKey(text, language)
        val normalized = buildSimilarityTextKey(text)
        val value = translation.trim()
        if (exactKey.isBlank() || normalized.isBlank() || value.isBlank()) return
        textEntries[exactKey] = TextCacheEntry(
            translation = value,
            normalized = normalized,
            updatedAt = System.currentTimeMillis()
        )
        trimToLimit(textEntries, TEXT_CACHE_LIMIT)
        saveToDisk()
    }

    @Synchronized
    fun findImageTranslation(
        imageKey: String,
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH
    ): String? {
        return imageEntries[buildImageEntryKey(imageKey, language)]?.translation
    }

    @Synchronized
    fun putImageTranslation(
        imageKey: String,
        translation: String,
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH
    ) {
        val key = buildImageEntryKey(imageKey, language)
        val value = translation.trim()
        if (key.isBlank() || value.isBlank()) return
        imageEntries[key] = ImageCacheEntry(
            translation = value,
            updatedAt = System.currentTimeMillis()
        )
        trimToLimit(imageEntries, IMAGE_CACHE_LIMIT)
        saveToDisk()
    }

    fun createImageKey(bitmap: Bitmap): String {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)
        return createImageKey(output.toByteArray())
    }

    fun createImageKey(jpegBytes: ByteArray): String {
        return sha256Hex(jpegBytes)
    }

    @Synchronized
    private fun loadFromDisk() {
        if (!cacheFile.exists()) return
        runCatching {
            val root = JSONObject(cacheFile.readText())
            val textArray = root.optJSONArray("text_entries") ?: JSONArray()
            for (index in 0 until textArray.length()) {
                val item = textArray.optJSONObject(index) ?: continue
                val key = item.optString("key").trim()
                val translation = item.optString("translation").trim()
                val normalized = item.optString("normalized").trim()
                if (key.isBlank() || translation.isBlank() || normalized.isBlank()) continue
                textEntries[key] = TextCacheEntry(
                    translation = translation,
                    normalized = normalized,
                    updatedAt = item.optLong("updated_at")
                )
            }
            val imageArray = root.optJSONArray("image_entries") ?: JSONArray()
            for (index in 0 until imageArray.length()) {
                val item = imageArray.optJSONObject(index) ?: continue
                val key = item.optString("key").trim()
                val translation = item.optString("translation").trim()
                if (key.isBlank() || translation.isBlank()) continue
                imageEntries[key] = ImageCacheEntry(
                    translation = translation,
                    updatedAt = item.optLong("updated_at")
                )
            }
            trimToLimit(textEntries, TEXT_CACHE_LIMIT)
            trimToLimit(imageEntries, IMAGE_CACHE_LIMIT)
        }.onFailure {
            AppLogger.log("FloatingCache", "Load cache failed", it)
        }
    }

    @Synchronized
    private fun saveToDisk() {
        runCatching {
            val root = JSONObject()
            val textArray = JSONArray()
            textEntries.forEach { (key, entry) ->
                textArray.put(
                    JSONObject()
                        .put("key", key)
                        .put("translation", entry.translation)
                        .put("normalized", entry.normalized)
                        .put("updated_at", entry.updatedAt)
                )
            }
            val imageArray = JSONArray()
            imageEntries.forEach { (key, entry) ->
                imageArray.put(
                    JSONObject()
                        .put("key", key)
                        .put("translation", entry.translation)
                        .put("updated_at", entry.updatedAt)
                )
            }
            root.put("text_entries", textArray)
            root.put("image_entries", imageArray)
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(root.toString())
        }.onFailure {
            AppLogger.log("FloatingCache", "Save cache failed", it)
        }
    }

    private fun buildExactTextKey(text: String, language: TranslationLanguage): String {
        val normalizedText = text.trim()
            .replace(Regex("\\s+"), " ")
        if (normalizedText.isBlank()) return ""
        return buildScopedKey(language, normalizedText)
    }

    private fun buildImageEntryKey(imageKey: String, language: TranslationLanguage): String {
        val normalizedKey = imageKey.trim()
        if (normalizedKey.isBlank()) return ""
        return buildScopedKey(language, normalizedKey)
    }

    private fun buildScopedKey(language: TranslationLanguage, rawKey: String): String {
        return "${language.name}|$rawKey"
    }

    private fun buildSimilarityTextKey(text: String): String {
        val normalized = buildString(text.length) {
            text.lowercase(Locale.ROOT).forEach { ch ->
                when {
                    ch.isWhitespace() -> Unit
                    ch in IGNORED_SIMILARITY_CHARS -> Unit
                    else -> append(ch)
                }
            }
        }
        return normalized
    }

    private fun normalizedSimilarity(a: String, b: String): Float {
        val maxLength = maxOf(a.length, b.length).coerceAtLeast(1)
        val distance = levenshteinDistance(a, b)
        return 1f - distance.toFloat() / maxLength.toFloat()
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val previous = IntArray(b.length + 1) { it }
        val current = IntArray(b.length + 1)
        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + cost
                )
            }
            current.copyInto(previous)
        }
        return previous[b.length]
    }

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    private fun <K, V> trimToLimit(map: LinkedHashMap<K, V>, limit: Int) {
        while (map.size > limit) {
            val iterator = map.entries.iterator()
            if (!iterator.hasNext()) return
            iterator.next()
            iterator.remove()
        }
    }

    companion object {
        private const val CACHE_FILE_NAME = "floating_translate_cache.json"
        private const val TEXT_CACHE_LIMIT = 300
        private const val IMAGE_CACHE_LIMIT = 128
        private const val MIN_SIMILARITY_TEXT_LENGTH = 6
        private const val LONG_TEXT_THRESHOLD = 12
        private const val SHORT_TEXT_SIMILARITY_THRESHOLD = 0.90f
        private const val LONG_TEXT_SIMILARITY_THRESHOLD = 0.84f
        private const val MIN_SIMILARITY_LENGTH_RATIO = 0.7f
        private const val MAX_SIMILARITY_LENGTH_RATIO = 1.3f
        private val IGNORED_SIMILARITY_CHARS = setOf(
            ' ',
            '　',
            '\n',
            '\r',
            '\t',
            ',',
            '.',
            '，',
            '。',
            '、',
            '!',
            '！',
            '?',
            '？',
            '…',
            '·',
            '・',
            '-',
            'ー',
            '～',
            '~',
            '"',
            '\'',
            '“',
            '”',
            '‘',
            '’'
        )
    }
}

data class CacheLookupResult(
    val translation: String,
    val matchedBySimilarity: Boolean
)

private data class TextCacheEntry(
    val translation: String,
    val normalized: String,
    val updatedAt: Long
)

private data class ImageCacheEntry(
    val translation: String,
    val updatedAt: Long
)
