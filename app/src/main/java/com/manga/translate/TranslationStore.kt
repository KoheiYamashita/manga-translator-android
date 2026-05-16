package com.manga.translate

import android.graphics.RectF
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class TranslationStore {
    private val updatesFlow = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 64
    )

    val updates: SharedFlow<String> = updatesFlow.asSharedFlow()

    fun load(imageFile: File, expectedMetadata: TranslationMetadata? = null): TranslationResult? {
        val jsonFile = translationFileFor(imageFile)
        if (!jsonFile.exists()) return null
        return try {
            val json = JSONObject(jsonFile.readText())
            val metadata = parseMetadata(json.optJSONObject("metadata"))
            if (expectedMetadata != null && !isMetadataUsable(imageFile, metadata, expectedMetadata)) {
                return null
            }
            val bubblesJson = json.optJSONArray("bubbles") ?: JSONArray()
            val bubbles = ArrayList<BubbleTranslation>(bubblesJson.length())
            for (i in 0 until bubblesJson.length()) {
                val item = bubblesJson.optJSONObject(i) ?: continue
                val id = if (item.has("id")) item.optInt("id") else i
                val rect = RectF(
                    item.optDouble("left").toFloat(),
                    item.optDouble("top").toFloat(),
                    item.optDouble("right").toFloat(),
                    item.optDouble("bottom").toFloat()
                )
                val source = BubbleSource.fromJson(if (item.has("source")) item.optString("source") else null)
                val originalText = if (item.has("originalText")) {
                    item.optString("originalText", "")
                } else {
                    ""
                }
                val translatedText = if (item.has("translatedText")) {
                    item.optString("translatedText", "")
                } else {
                    ""
                }
                val translationState = if (item.has("translationState")) {
                    BubbleTranslationState.fromJson(item.optString("translationState"))
                } else {
                    val legacyText = item.optString("text", "")
                    if (legacyText.isBlank()) {
                        BubbleTranslationState.PENDING
                    } else {
                        BubbleTranslationState.TRANSLATED
                    }
                }
                val maskContourJson = item.optJSONArray("maskContour")
                val maskContour = if (maskContourJson != null && maskContourJson.length() >= 6) {
                    FloatArray(maskContourJson.length()) { i -> maskContourJson.optDouble(i).toFloat() }
                } else null
                val bubble = if (
                    item.has("originalText") ||
                    item.has("translatedText") ||
                    item.has("translationState")
                ) {
                    BubbleTranslation(
                        id = id,
                        rect = rect,
                        originalText = originalText,
                        translatedText = translatedText,
                        translationState = translationState,
                        source = source,
                        maskContour = maskContour
                    )
                } else {
                    val legacyText = item.optString("text", "")
                    BubbleTranslation.translated(
                        id = id,
                        rect = rect,
                        translatedText = legacyText,
                        source = source,
                        maskContour = maskContour
                    )
                }
                bubbles.add(bubble)
            }
            val baseResult = TranslationResult(
                imageName = json.optString("image", imageFile.name),
                width = json.optInt("width", 0),
                height = json.optInt("height", 0),
                bubbles = bubbles,
                metadata = metadata
            )
            if (metadata.status == PageTranslationStatus.UNKNOWN) {
                baseResult.copy(metadata = metadata.copy(status = baseResult.deriveStatus()))
            } else {
                baseResult
            }
        } catch (e: Exception) {
            AppLogger.log("TranslationStore", "Failed to load ${jsonFile.name}", e)
            null
        }
    }

    fun save(imageFile: File, result: TranslationResult): File {
        val jsonFile = translationFileFor(imageFile)
        val metadata = normalizeMetadata(imageFile, result.metadata)
        val persistedStatus = if (metadata.status == PageTranslationStatus.UNKNOWN) {
            result.deriveStatus()
        } else {
            metadata.status
        }
        val json = JSONObject()
            .put("image", result.imageName)
            .put("width", result.width)
            .put("height", result.height)
            .put("metadata", JSONObject().apply {
                put("sourceLastModified", metadata.sourceLastModified)
                put("sourceFileSize", metadata.sourceFileSize)
                put("mode", metadata.mode)
                put("language", metadata.language)
                put("promptAsset", metadata.promptAsset)
                put("modelName", metadata.modelName)
                put("providerId", metadata.providerId)
                put("apiFormat", metadata.apiFormat)
                put("ocrCacheMode", metadata.ocrCacheMode)
                put("version", metadata.version)
                put("status", persistedStatus.jsonValue)
            })
        val bubbles = JSONArray()
        for (bubble in result.bubbles) {
            val item = JSONObject()
                .put("id", bubble.id)
                .put("left", bubble.rect.left)
                .put("top", bubble.rect.top)
                .put("right", bubble.rect.right)
                .put("bottom", bubble.rect.bottom)
                .put("text", bubble.text)
                .put("originalText", bubble.originalText)
                .put("translatedText", bubble.translatedText)
                .put("translationState", bubble.translationState.jsonValue)
                .put("source", bubble.source.jsonValue)
            if (bubble.maskContour != null) {
                val contourArr = JSONArray()
                for (v in bubble.maskContour) contourArr.put(v.toDouble())
                item.put("maskContour", contourArr)
            }
            bubbles.put(item)
        }
        json.put("bubbles", bubbles)
        val tmp = File(jsonFile.parentFile, "${jsonFile.name}.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(jsonFile)) {
            jsonFile.writeText(tmp.readText())
            tmp.delete()
        }
        updatesFlow.tryEmit(imageFile.absolutePath)
        return jsonFile
    }

    fun translationFileFor(imageFile: File): File {
        val name = imageFile.nameWithoutExtension + ".json"
        return File(imageFile.parentFile, name)
    }

    private fun parseMetadata(json: JSONObject?): TranslationMetadata {
        return TranslationMetadata(
            sourceLastModified = json?.optLong("sourceLastModified") ?: 0L,
            sourceFileSize = json?.optLong("sourceFileSize") ?: 0L,
            mode = json?.optString("mode").orEmpty(),
            language = json?.optString("language").orEmpty(),
            promptAsset = json?.optString("promptAsset").orEmpty(),
            modelName = json?.optString("modelName").orEmpty(),
            providerId = json?.optString("providerId").orEmpty(),
            apiFormat = json?.optString("apiFormat").orEmpty(),
            ocrCacheMode = json?.optString("ocrCacheMode").orEmpty(),
            version = json?.let { it.optInt("version", TranslationMetadata.CURRENT_VERSION) }
                ?: TranslationMetadata.CURRENT_VERSION,
            status = PageTranslationStatus.fromJson(json?.optString("status"))
        )
    }

    private fun normalizeMetadata(imageFile: File, metadata: TranslationMetadata): TranslationMetadata {
        val fingerprinted = metadata.withSourceFingerprint(imageFile)
        return if (fingerprinted.mode.isNotBlank()) {
            fingerprinted
        } else {
            fingerprinted.copy(mode = TranslationMetadata.MODE_MANUAL)
        }
    }

    private fun isMetadataUsable(
        imageFile: File,
        actual: TranslationMetadata,
        expected: TranslationMetadata
    ): Boolean {
        if (!actual.matchesSource(imageFile)) {
            return false
        }
        if (actual.isManual()) {
            return true
        }
        if (isLegacyTranslationMetadata(actual)) {
            return true
        }
        val allowedProviderIds = expected.providerId
            .split('|')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
        val allowedModelNames = expected.modelName
            .split('|')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
        val modelMatches = when {
            allowedModelNames.isEmpty() -> actual.modelName == expected.modelName
            else -> actual.modelName in allowedModelNames
        }
        val providerMatches = when {
            allowedProviderIds.isEmpty() -> actual.providerId.isBlank() || actual.providerId == expected.providerId
            actual.providerId.isBlank() -> true
            else -> actual.providerId in allowedProviderIds
        }
        return actual.version == expected.version &&
            actual.mode == expected.mode &&
            actual.language == expected.language &&
            actual.promptAsset == expected.promptAsset &&
            modelMatches &&
            providerMatches &&
            actual.apiFormat == expected.apiFormat &&
            actual.ocrCacheMode == expected.ocrCacheMode
    }

    private fun isLegacyTranslationMetadata(metadata: TranslationMetadata): Boolean {
        return metadata.mode.isNotBlank() &&
            (metadata.language.isBlank() ||
                metadata.promptAsset.isBlank() ||
                metadata.apiFormat.isBlank() ||
                (metadata.mode != TranslationMetadata.MODE_VL_DIRECT && metadata.ocrCacheMode.isBlank()))
    }
}
