package com.manga.translate

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal class FloatingBubbleTranslationCoordinator(
    private val llmClient: LlmClient,
    private val floatingTranslationCacheStore: FloatingTranslationCacheStore,
    private val settingsStore: SettingsStore
) {
    private val textBubbleTranslationCoordinator = TextBubbleTranslationCoordinator(
        llmClient = llmClient
    )

    suspend fun translateTextBubbles(
        bubbles: List<BubbleTranslation>,
        timeoutMs: Int,
        retryCount: Int,
        promptAsset: String,
        apiSettings: ApiSettings = settingsStore.loadResolvedFloatingTranslateApiSettings(),
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH,
        logTag: String = "FloatingOCR"
    ): List<BubbleTranslation>? {
        if (bubbles.isEmpty()) return bubbles
        val translatable = bubbles.filter { it.text.isNotBlank() }
        if (translatable.isEmpty()) {
            AppLogger.log(logTag, "Skip translate: no translatable text")
            return bubbles
        }
        if (!llmClient.isConfigured(apiSettings)) {
            AppLogger.log(logTag, "Missing translate API settings")
            throw LlmRequestException(
                "MISSING_TRANSLATE_API_SETTINGS"
            )
        }

        val translatedMap = HashMap<Int, String>(translatable.size)
        val cacheMisses = ArrayList<BubbleTranslation>(translatable.size)
        var exactCacheHits = 0
        var similarityCacheHits = 0
        for (bubble in translatable) {
            val cached = floatingTranslationCacheStore.findTextTranslation(
                text = bubble.text,
                language = language
            )
            if (cached == null) {
                cacheMisses.add(bubble)
                continue
            }
            translatedMap[bubble.id] = cached.translation
            if (cached.matchedBySimilarity) {
                similarityCacheHits++
            } else {
                exactCacheHits++
            }
        }
        AppLogger.log(
            "FloatingCache",
            "Text cache exactHits=$exactCacheHits similarityHits=$similarityCacheHits misses=${cacheMisses.size}"
        )

        fun merge(): List<BubbleTranslation> {
            return bubbles.map { bubble ->
                translatedMap[bubble.id]?.takeIf { it.isNotBlank() }?.let { translated ->
                    bubble.copy(text = translated)
                } ?: bubble
            }
        }

        if (cacheMisses.isEmpty()) {
            return merge()
        }

        return try {
            val result = textBubbleTranslationCoordinator.translateBubbles(
                bubbles = cacheMisses,
                glossary = emptyMap(),
                promptAsset = promptAsset,
                requestTimeoutMs = timeoutMs,
                retryCount = retryCount,
                apiSettings = apiSettings,
                language = language,
                logTag = logTag,
                translationMode = "floating_text"
            ) ?: return null
            for (bubble in result.bubbles) {
                if (bubble.text.isNotBlank()) {
                    translatedMap[bubble.id] = bubble.text
                    val source = cacheMisses.firstOrNull { it.id == bubble.id } ?: continue
                    floatingTranslationCacheStore.putTextTranslation(
                        text = source.text,
                        translation = bubble.text,
                        language = language
                    )
                }
            }
            val merged = merge()
            AppLogger.log(logTag, "Translate success segments=${translatedMap.size}")
            merged
        } catch (e: LlmRequestException) {
            if (e.errorCode == "TIMEOUT") {
                AppLogger.log(logTag, "LLM translate timeout")
                null
            } else {
                throw e
            }
        } catch (e: LlmResponseException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log(logTag, "LLM translate failed", e)
            throw e
        }
    }

    suspend fun translateImageBubbles(
        bitmap: Bitmap,
        bubbles: List<BubbleTranslation>,
        timeoutMs: Int,
        retryCount: Int,
        promptAsset: String,
        apiSettings: ApiSettings = settingsStore.loadResolvedFloatingTranslateApiSettings(),
        concurrency: Int,
        maxConcurrency: Int,
        useCache: Boolean = true,
        logTag: String = "FloatingOCR"
    ): FloatingBubbleImageTranslateOutcome = coroutineScope {
        val semaphore = Semaphore(concurrency.coerceIn(1, maxConcurrency))
        val language = settingsStore.loadFloatingTranslateApiSettings().language
        val tasks = bubbles.map { bubble ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val crop = cropBitmap(bitmap, bubble.rect)
                    if (crop == null) {
                        return@withPermit FloatingBubbleImageTranslateTaskResult(bubble = bubble)
                    }
                    val imageCacheKey = if (useCache) {
                        floatingTranslationCacheStore.createImageKey(crop)
                    } else {
                        null
                    }
                    val cachedTranslation = imageCacheKey?.let {
                        floatingTranslationCacheStore.findImageTranslation(it, language)
                    }
                    if (useCache && !cachedTranslation.isNullOrBlank()) {
                        AppLogger.log("FloatingCache", "VL cache hit bubble=${bubble.id}")
                        crop.recycleSafely()
                        return@withPermit FloatingBubbleImageTranslateTaskResult(
                            bubble = bubble.copy(text = cachedTranslation)
                        )
                    }
                    val translatedText = try {
                        llmClient.translateImageBubble(
                            image = crop,
                            promptAsset = promptAsset,
                            requestTimeoutMs = timeoutMs,
                            retryCount = retryCount,
                            apiSettings = apiSettings
                        ).orEmpty()
                    } catch (e: LlmRequestException) {
                        if (e.errorCode == "TIMEOUT") {
                            AppLogger.log(logTag, "VL direct translate timeout")
                            return@withPermit FloatingBubbleImageTranslateTaskResult(timedOut = true)
                        }
                        AppLogger.log(logTag, "VL direct translate request failed", e)
                        if (looksLikeVisionModelError(e)) {
                            return@withPermit FloatingBubbleImageTranslateTaskResult(requiresVlModel = true)
                        }
                        ""
                    } catch (e: Exception) {
                        AppLogger.log(logTag, "VL direct translate failed", e)
                        ""
                    } finally {
                        crop.recycleSafely()
                    }
                    if (useCache && translatedText.isNotBlank() && imageCacheKey != null) {
                        floatingTranslationCacheStore.putImageTranslation(
                            imageKey = imageCacheKey,
                            translation = translatedText,
                            language = language
                        )
                    }
                    if (translatedText.isBlank()) {
                        return@withPermit FloatingBubbleImageTranslateTaskResult(
                            responseException = LlmResponseException(
                                errorCode = "EMPTY_TRANSLATION_SEGMENT",
                                responseContent = buildBlankModelResponseMessage(
                                    bubbleCount = 1,
                                    mode = "image"
                                )
                            )
                        )
                    }
                    FloatingBubbleImageTranslateTaskResult(
                        bubble = bubble.copy(text = translatedText)
                    )
                }
            }
        }
        val results = tasks.awaitAll()
        if (results.any { it.requiresVlModel }) {
            return@coroutineScope FloatingBubbleImageTranslateOutcome(requiresVlModel = true)
        }
        if (results.any { it.timedOut }) {
            return@coroutineScope FloatingBubbleImageTranslateOutcome(timedOut = true)
        }
        results.firstNotNullOfOrNull { it.responseException }?.let { throw it }
        val translated = results.mapNotNull { it.bubble }
        AppLogger.log(logTag, "VL direct translate success segments=${translated.size}")
        return@coroutineScope FloatingBubbleImageTranslateOutcome(bubbles = translated)
    }

    fun looksLikeVisionModelError(error: LlmRequestException): Boolean {
        val body = error.responseBody.orEmpty().lowercase()
        val code = error.errorCode.lowercase()
        val hints = listOf(
            "image",
            "vision",
            "multimodal",
            "multi-modal",
            "image_url",
            "input_image",
            "does not support image",
            "unsupported content type"
        )
        return code.startsWith("http") && hints.any { it in body }
    }
}

private fun buildBlankModelResponseMessage(
    bubbleCount: Int,
    mode: String
): String {
    return "模型返回空白结果：$mode 模式下有 $bubbleCount 个气泡未返回有效翻译内容。"
}

internal data class FloatingBubbleImageTranslateOutcome(
    val bubbles: List<BubbleTranslation> = emptyList(),
    val timedOut: Boolean = false,
    val requiresVlModel: Boolean = false
)

private data class FloatingBubbleImageTranslateTaskResult(
    val bubble: BubbleTranslation? = null,
    val timedOut: Boolean = false,
    val requiresVlModel: Boolean = false,
    val responseException: LlmResponseException? = null
)
