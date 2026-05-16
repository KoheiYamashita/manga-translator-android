package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

class FloatingEmptyBubbleCoordinator(
    context: Context,
    private val llmClient: LlmClient,
    private val floatingTranslationCacheStore: FloatingTranslationCacheStore,
    private val settingsStore: SettingsStore,
    private val bubbleTextRecognizer: BubbleTextRecognizer
) {
    private val floatingBubbleTranslationCoordinator = FloatingBubbleTranslationCoordinator(
        llmClient = llmClient,
        floatingTranslationCacheStore = floatingTranslationCacheStore,
        settingsStore = settingsStore
    )

    suspend fun process(
        bitmap: Bitmap,
        baseTranslation: TranslationResult,
        timeoutMs: Int,
        retryCount: Int,
        floatPromptAsset: String,
        floatVlPromptAsset: String,
        maxVlConcurrency: Int
    ): FloatingEmptyBubbleOutcome = withContext(Dispatchers.Default) {
        val targets = baseTranslation.bubbles.filter { it.needsTranslationRetry() }
        if (targets.isEmpty()) {
            return@withContext FloatingEmptyBubbleOutcome(baseTranslation)
        }

        val client = llmClient
        val floatingSettings = settingsStore.loadFloatingTranslateApiSettings()
        val floatingApiSettings = settingsStore.loadResolvedFloatingTranslateApiSettings()
        val useVlDirectTranslate =
            floatingSettings.useVlDirectTranslate && client.isConfigured(floatingApiSettings)

        val updatedBubbles = if (useVlDirectTranslate) {
            val outcome = translateBubbleImages(
                bitmap = bitmap,
                bubbles = targets,
                timeoutMs = timeoutMs,
                retryCount = retryCount,
                apiSettings = floatingApiSettings,
                promptAsset = floatVlPromptAsset,
                concurrency = floatingSettings.aiApiConcurrencyLimit,
                maxVlConcurrency = maxVlConcurrency
            )
            if (outcome.requiresVlModel || outcome.timedOut) {
                return@withContext FloatingEmptyBubbleOutcome(
                    translation = baseTranslation,
                    timedOut = outcome.timedOut,
                    requiresVlModel = outcome.requiresVlModel
                )
            }
            baseTranslation.bubbles.map { bubble ->
                outcome.bubbles.firstOrNull { it.id == bubble.id }?.let { bubble.withContentFrom(it) } ?: bubble
            }
        } else {
            val recognized = recognizeEmptyBubbles(bitmap, targets)
            val translated = translateRecognizedBubbles(
                bubbles = recognized,
                timeoutMs = timeoutMs,
                retryCount = retryCount,
                promptAsset = floatPromptAsset,
                apiSettings = floatingApiSettings
            ) ?: return@withContext FloatingEmptyBubbleOutcome(
                translation = baseTranslation,
                timedOut = true
            )
            val translationMap = translated.associateBy { it.id }
            baseTranslation.bubbles.map { bubble ->
                translationMap[bubble.id]?.let { bubble.withContentFrom(it) } ?: bubble
            }
        }

        FloatingEmptyBubbleOutcome(baseTranslation.copy(bubbles = updatedBubbles))
    }

    private suspend fun recognizeEmptyBubbles(
        bitmap: Bitmap,
        bubbles: List<BubbleTranslation>
    ): List<BubbleTranslation> = withContext(Dispatchers.Default) {
        val floatingLanguage = settingsStore.loadFloatingTranslateApiSettings().language
        val ocrSettings = settingsStore.loadOcrApiSettings()
        bubbles.map { bubble ->
            val crop = cropBitmap(bitmap, bubble.rect)
            val text = try {
                if (crop == null) {
                    ""
                } else {
                    recognizeBubble(crop, floatingLanguage, ocrSettings.useLocalOcr)
                }
            } catch (e: Exception) {
                AppLogger.log("FloatingOCR", "Recognize empty bubble failed id=${bubble.id}", e)
                ""
            } finally {
                crop?.recycle()
            }
            bubble.withRecognizedOriginalText(text)
        }
    }

    private suspend fun translateRecognizedBubbles(
        bubbles: List<BubbleTranslation>,
        timeoutMs: Int,
        retryCount: Int,
        promptAsset: String,
        apiSettings: ApiSettings
    ): List<BubbleTranslation>? {
        return floatingBubbleTranslationCoordinator.translateTextBubbles(
            bubbles = bubbles,
            timeoutMs = timeoutMs,
            retryCount = retryCount,
            promptAsset = promptAsset,
            apiSettings = apiSettings,
            language = settingsStore.loadFloatingTranslateApiSettings().language,
            logTag = "FloatingOCR"
        )
    }

    private suspend fun recognizeBubble(
        crop: Bitmap,
        language: TranslationLanguage,
        useLocalOcr: Boolean
    ): String = withContext(Dispatchers.Default) {
        bubbleTextRecognizer.recognizeCrop(
            crop = crop,
            language = language,
            useLocalOcr = useLocalOcr,
            logTag = "FloatingOCR"
        )
    }

    private suspend fun translateBubbleImages(
        bitmap: Bitmap,
        bubbles: List<BubbleTranslation>,
        timeoutMs: Int,
        retryCount: Int,
        apiSettings: ApiSettings,
        promptAsset: String,
        concurrency: Int,
        maxVlConcurrency: Int
    ): FloatingVlBubbleTranslateOutcome = coroutineScope {
        val outcome = floatingBubbleTranslationCoordinator.translateImageBubbles(
            bitmap = bitmap,
            bubbles = bubbles,
            timeoutMs = timeoutMs,
            retryCount = retryCount,
            promptAsset = promptAsset,
            apiSettings = apiSettings,
            concurrency = concurrency,
            maxConcurrency = maxVlConcurrency,
            logTag = "FloatingOCR"
        )
        return@coroutineScope FloatingVlBubbleTranslateOutcome(
            bubbles = outcome.bubbles,
            timedOut = outcome.timedOut,
            requiresVlModel = outcome.requiresVlModel
        )
    }

}

data class FloatingEmptyBubbleOutcome(
    val translation: TranslationResult,
    val timedOut: Boolean = false,
    val requiresVlModel: Boolean = false
)

private data class FloatingVlBubbleTranslateOutcome(
    val bubbles: List<BubbleTranslation> = emptyList(),
    val timedOut: Boolean = false,
    val requiresVlModel: Boolean = false
)
