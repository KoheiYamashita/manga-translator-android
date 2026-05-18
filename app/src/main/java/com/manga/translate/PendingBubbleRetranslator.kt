package com.manga.translate

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal class PendingBubbleRetranslator(
    context: Context,
    private val settingsStore: SettingsStore = SettingsStore(context.applicationContext),
    private val bubbleTextRecognizer: BubbleTextRecognizer,
    private val textBubbleTranslationCoordinator: TextBubbleTranslationCoordinator
) {
    private val appContext = context.applicationContext

    suspend fun refill(
        imageFile: File,
        baseTranslation: TranslationResult,
        glossary: MutableMap<String, String>,
        language: TranslationLanguage,
        promptAsset: String,
        translationMode: String,
        logTag: String,
        discardShortOcr: Boolean
    ): RefillOutcome? = withContext(Dispatchers.Default) {
        val targets = baseTranslation.bubbles.filter { it.needsTranslationRetry() }
        if (targets.isEmpty()) {
            return@withContext RefillOutcome(translation = baseTranslation, translatedByLlm = false)
        }

        val ocrSettings = settingsStore.loadOcrApiSettings()
        val useLocalOcr = ocrSettings.useLocalOcr
        if (!useLocalOcr && !ocrSettings.isValid()) {
            AppLogger.log(logTag, "Refill skipped: missing OCR API settings")
            return@withContext null
        }
        if (!settingsStore.load().isValid()) {
            AppLogger.log(logTag, "Refill skipped: missing translate API settings")
            throw LlmRequestException(
                "MISSING_TRANSLATE_API_SETTINGS",
                appContext.getString(R.string.missing_translate_api_settings)
            )
        }

        val bitmap = if (ImageFileSupport.isAvifFile(imageFile.name)) {
            AvifBitmapDecoder.decode(imageFile)
        } else {
            BitmapFactory.decodeFile(imageFile.absolutePath)
        } ?: run {
            AppLogger.log(logTag, "Refill skipped: failed to decode ${imageFile.name}")
            return@withContext null
        }

        try {
            val candidates = ArrayList<OcrBubble>(targets.size)
            val removedIds = HashSet<Int>()
            for (bubble in targets) {
                val text = bubbleTextRecognizer.recognizeRegion(
                    source = bitmap,
                    rect = bubble.rect,
                    language = language,
                    useLocalOcr = useLocalOcr,
                    logTag = logTag
                ).trim()
                if (discardShortOcr && text.length <= 2) {
                    removedIds.add(bubble.id)
                } else if (text.isNotBlank()) {
                    candidates.add(OcrBubble(bubble.id, bubble.rect, text, bubble.source, bubble.maskContour))
                }
            }

            val remainingBubbles = baseTranslation.bubbles.filterNot { removedIds.contains(it.id) }
            if (candidates.isEmpty()) {
                val updated = baseTranslation.copy(
                    bubbles = remainingBubbles,
                    metadata = baseTranslation.metadata.copy(status = PageTranslationStatus.UNKNOWN)
                )
                return@withContext RefillOutcome(translation = updated, translatedByLlm = false)
            }

            val translated = try {
                textBubbleTranslationCoordinator.translateBubbles(
                    bubbles = candidates.map { ocr ->
                        BubbleTranslation.pending(
                            id = ocr.id,
                            rect = ocr.rect,
                            originalText = ocr.text,
                            source = ocr.source,
                            maskContour = ocr.maskContour
                        )
                    },
                    glossary = glossary,
                    promptAsset = promptAsset,
                    language = language,
                    logTag = logTag,
                    translationMode = translationMode
                )
            } catch (e: LlmResponseException) {
                throw e
            } ?: run {
                AppLogger.log(logTag, "Refill returned null translation result")
                return@withContext null
            }

            if (translated.glossaryUsed.isNotEmpty()) {
                glossary.putAll(translated.glossaryUsed)
            }

            val translationMap = translated.bubbles.associateBy { it.id }
            val merged = remainingBubbles.map { bubble ->
                translationMap[bubble.id]?.let { bubble.withContentFrom(it) } ?: bubble
            }
            val updated = baseTranslation.copy(
                bubbles = merged,
                metadata = baseTranslation.metadata.copy(status = PageTranslationStatus.UNKNOWN)
            )
            RefillOutcome(translation = updated, translatedByLlm = true, glossaryUsed = translated.glossaryUsed)
        } finally {
            bitmap.recycleSafely()
        }
    }

    data class RefillOutcome(
        val translation: TranslationResult,
        val translatedByLlm: Boolean,
        val glossaryUsed: Map<String, String> = emptyMap()
    )
}
