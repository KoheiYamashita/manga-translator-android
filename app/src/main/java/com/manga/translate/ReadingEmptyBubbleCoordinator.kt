package com.manga.translate

import android.content.Context
import android.content.SharedPreferences
import android.graphics.RectF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal class ReadingEmptyBubbleCoordinator(
    context: Context,
    private val translationStore: TranslationStore,
    private val glossaryStore: GlossaryStore,
    private val repository: LibraryRepository,
    private val libraryPrefs: SharedPreferences,
    private val settingsStore: SettingsStore = SettingsStore(context.applicationContext),
    private val bubbleTextRecognizer: BubbleTextRecognizer,
    private val textBubbleTranslationCoordinator: TextBubbleTranslationCoordinator,
    private val languageKeyPrefix: String = "translation_language_"
) {
    private val appContext = context.applicationContext

    suspend fun process(
        imageFile: File,
        folder: File,
        baseTranslation: TranslationResult
    ): EmptyBubbleProcessOutcome? = withContext(Dispatchers.Default) {
        val targets = baseTranslation.bubbles.filter { it.needsTranslationRetry() }
        if (targets.isEmpty()) return@withContext null
        if (!settingsStore.load().isValid()) {
            AppLogger.log("Reading", "Missing translate API settings for empty bubble translation")
            throw LlmRequestException(
                "MISSING_TRANSLATE_API_SETTINGS",
                appContext.getString(R.string.missing_translate_api_settings)
            )
        }

        val ocrSettings = settingsStore.loadOcrApiSettings()
        val useLocalOcr = ocrSettings.useLocalOcr
        val language = if (useLocalOcr) {
            getTranslationLanguage(folder)
        } else {
            TranslationLanguage.JA_TO_ZH
        }
        val glossary = glossaryStore.load(folder)
        val cropSource = PipelineBitmapDecoder.openCropSource(imageFile) ?: return@withContext null

        cropSource.use {
            val candidates = ArrayList<OcrBubble>(targets.size)
            val removedIds = HashSet<Int>()
            if (!ocrSettings.useLocalOcr && !ocrSettings.isValid()) {
                AppLogger.log("Reading", "Missing OCR API settings")
                return@withContext null
            }
            for (bubble in targets) {
                val text = ocrBubble(cropSource, bubble.rect, language, ocrSettings.useLocalOcr).trim()
                if (text.length <= 2) {
                    removedIds.add(bubble.id)
                } else {
                    candidates.add(OcrBubble(bubble.id, bubble.rect, text, bubble.source))
                }
            }

            val remainingBubbles = baseTranslation.bubbles.filterNot { removedIds.contains(it.id) }
            if (candidates.isEmpty()) {
                val updated = baseTranslation.copy(bubbles = remainingBubbles)
                withContext(Dispatchers.IO) {
                    translationStore.save(imageFile, updated)
                }
                return@withContext EmptyBubbleProcessOutcome(updated, translatedByLlm = false)
            }

            val translated = translateOcrBubbles(imageFile, candidates, glossary, language)
            if (translated == null) {
                AppLogger.log("Reading", "Empty bubble translation returned null, keep bubble empty")
                return@withContext null
            }
            if (translated.glossaryUsed.isNotEmpty()) {
                glossary.putAll(translated.glossaryUsed)
                withContext(Dispatchers.IO) {
                    glossaryStore.save(folder, glossary)
                }
            }
            val translationMap = translated.bubbles.associateBy { it.id }
            val merged = remainingBubbles.map { bubble ->
                translationMap[bubble.id]?.let { bubble.withContentFrom(it) } ?: bubble
            }
            val updated = baseTranslation.copy(bubbles = merged)
            withContext(Dispatchers.IO) {
                translationStore.save(imageFile, updated)
            }
            EmptyBubbleProcessOutcome(updated, translatedByLlm = true)
        }
    }

    private fun getTranslationLanguage(folder: File): TranslationLanguage {
        val settingsFolder = repository.resolveSettingsFolder(folder)
        val value = libraryPrefs.getString(languageKeyPrefix + settingsFolder.absolutePath, null)
        return TranslationLanguage.fromString(value)
    }

    private suspend fun translateOcrBubbles(
        imageFile: File,
        bubbles: List<OcrBubble>,
        glossary: Map<String, String>,
        language: TranslationLanguage
    ): TextBubbleTranslationBatchResult? = withContext(Dispatchers.IO) {
        val promptAsset = "prompts/llm_prompts.json"
        try {
            textBubbleTranslationCoordinator.translateBubbles(
                bubbles = bubbles.map { bubble ->
                    BubbleTranslation.pending(
                        id = bubble.id,
                        rect = bubble.rect,
                        originalText = bubble.text,
                        source = bubble.source
                    )
                },
                glossary = glossary,
                promptAsset = promptAsset,
                language = language,
                logTag = "Reading",
                translationMode = "reading_empty_bubble"
            )
        } catch (e: LlmResponseException) {
            throw e.withPageName(appContext, imageFile.name)
        }
    }

    private suspend fun ocrBubble(
        cropSource: BitmapCropSource,
        rect: RectF,
        language: TranslationLanguage,
        useLocalOcr: Boolean
    ): String {
        return bubbleTextRecognizer.recognizeRegion(
            cropSource = cropSource,
            rect = rect,
            language = language,
            useLocalOcr = useLocalOcr,
            logTag = "Reading"
        )
    }
}

private fun LlmResponseException.withPageName(context: Context, pageName: String): LlmResponseException {
    val pagePrefix = context.getString(R.string.error_page_prefix)
    if (responseContent.startsWith(pagePrefix)) return this
    return LlmResponseException(
        errorCode = errorCode,
        responseContent = "$pagePrefix$pageName\n$responseContent",
        cause = this
    )
}

data class EmptyBubbleProcessOutcome(
    val updatedTranslation: TranslationResult,
    val translatedByLlm: Boolean
)
