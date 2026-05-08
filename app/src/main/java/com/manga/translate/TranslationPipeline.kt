package com.manga.translate

import android.content.Context
import android.graphics.RectF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal class TranslationPipeline(
    context: Context,
    private val llmClient: LlmClient = LlmClient(context.applicationContext),
    private val settingsStore: SettingsStore = SettingsStore(context.applicationContext),
    private val store: TranslationStore = TranslationStore(),
    private val ocrStore: OcrStore = OcrStore(),
    private val ocrEngineRegistry: OcrEngineRegistry =
        OcrEngineRegistry(context.applicationContext, settingsStore),
    private val bubbleTextRecognizer: BubbleTextRecognizer =
        BubbleTextRecognizer(llmClient, ocrEngineRegistry, settingsStore),
    private val textBubbleTranslationCoordinator: TextBubbleTranslationCoordinator =
        TextBubbleTranslationCoordinator(llmClient = llmClient),
    private val floatingBubbleTranslationCoordinator: FloatingBubbleTranslationCoordinator =
        FloatingBubbleTranslationCoordinator(
            llmClient = llmClient,
            floatingTranslationCacheStore = FloatingTranslationCacheStore(context.applicationContext),
            settingsStore = settingsStore
        ),
    private val pageRegionDetector: PageRegionDetector =
        PageRegionDetector(context.applicationContext, settingsStore)
) {
    private val appContext = context.applicationContext

    suspend fun translateImage(
        imageFile: File,
        glossary: MutableMap<String, String>,
        forceOcr: Boolean,
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH,
        providerContext: PageTranslationProviderContext? = null,
        onProgress: (String) -> Unit
    ): TranslationResult? = withContext(Dispatchers.Default) {
        val resolvedApiSettings = providerContext?.apiSettings
        if (!llmClient.isConfigured(resolvedApiSettings)) {
            onProgress(appContext.getString(R.string.missing_api_settings))
            AppLogger.log("Pipeline", "Missing API settings")
            return@withContext null
        }
        val page = ocrImage(imageFile, forceOcr, language, onProgress) ?: return@withContext null
        val metadata = buildTranslationMetadata(
            imageFile = imageFile,
            language = language,
            mode = TranslationMetadata.MODE_STANDARD,
            promptAsset = STANDARD_PROMPT_ASSET,
            ocrCacheMode = page.cacheMode,
            providerContext = providerContext
        )
        AppLogger.log("Pipeline", "Translate image ${imageFile.name}")
        val translatable = page.bubbles.filter { it.text.isNotBlank() }
        if (translatable.isEmpty()) {
            val emptyTranslations = page.bubbles.map {
                BubbleTranslation(it.id, it.rect, "", it.source, it.maskContour)
            }
            return@withContext TranslationResult(
                imageFile.name,
                page.width,
                page.height,
                emptyTranslations,
                metadata
            )
        }
        onProgress(appContext.getString(R.string.translating_bubbles))
        val promptAsset = STANDARD_PROMPT_ASSET
        val translatedBubbles = try {
            val translated = executeWithModelResponseRetries("Pipeline") {
                textBubbleTranslationCoordinator.translateBubbles(
                    bubbles = translatable.map {
                        BubbleTranslation(it.id, it.rect, it.text, it.source, it.maskContour)
                    },
                    glossary = glossary,
                    promptAsset = promptAsset,
                    apiSettings = resolvedApiSettings,
                    language = language,
                    logTag = "Pipeline",
                    translationMode = "standard"
                )
            } ?: return@withContext null
            if (translated.glossaryUsed.isNotEmpty()) {
                glossary.putAll(translated.glossaryUsed)
            }
            translated.bubbles
        } catch (e: LlmResponseException) {
            throw e.withPageName(imageFile.name)
        }
        val translationMap = translatedBubbles.associateBy({ it.id }, { it.text })
        val bubbles = page.bubbles.map { bubble ->
            val text = translationMap[bubble.id] ?: ""
            BubbleTranslation(bubble.id, bubble.rect, text, bubble.source, bubble.maskContour)
        }
        AppLogger.log("Pipeline", "Translation finished for ${imageFile.name}")
        TranslationResult(imageFile.name, page.width, page.height, bubbles, metadata)
    }

    suspend fun ocrImage(
        imageFile: File,
        forceOcr: Boolean,
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH,
        onProgress: (String) -> Unit
    ): PageOcrResult? = withContext(Dispatchers.Default) {
        val ocrSettings = settingsStore.loadOcrApiSettings()
        val cacheMode = buildOcrCacheMode(ocrSettings.useLocalOcr, language)
        val expectedMetadata = buildOcrMetadata(imageFile, language, ocrSettings, cacheMode)
        if (!forceOcr) {
            val cached = ocrStore.load(imageFile, expectedMetadata = expectedMetadata)
            if (cached != null) {
                AppLogger.log("Pipeline", "Reuse OCR for ${imageFile.name}")
                return@withContext cached
            }
        }
        val useLocalOcr = ocrSettings.useLocalOcr
        val ocrEngine: OcrEngine? = if (useLocalOcr) {
            bubbleTextRecognizer.getLocalOcrEngine(language, "Pipeline")
        } else {
            null
        }
        if (!useLocalOcr && !llmClient.isOcrConfigured()) {
            onProgress(appContext.getString(R.string.missing_ocr_api_settings))
            AppLogger.log("Pipeline", "Missing OCR API settings")
            return@withContext null
        }
        if (useLocalOcr && ocrEngine == null) {
            return@withContext null
        }
        val bitmap = android.graphics.BitmapFactory.decodeFile(imageFile.absolutePath)
            ?: run {
                AppLogger.log("Pipeline", "Failed to decode ${imageFile.name}")
                return@withContext null
            }
        try {
            onProgress(appContext.getString(R.string.detecting_bubbles))
            val pageRegions = pageRegionDetector.detect(bitmap, logTag = "Pipeline")
                ?: return@withContext null
            val regions = pageRegions.regions
            AppLogger.log("Pipeline", "Detected ${regions.size} regions in ${imageFile.name}")
            if (regions.isEmpty()) {
                val emptyResult = PageOcrResult(
                    imageFile,
                    bitmap.width,
                    bitmap.height,
                    emptyList(),
                    cacheMode,
                    expectedMetadata
                )
                ocrStore.save(imageFile, emptyResult)
                return@withContext emptyResult
            }
            val bubbles = ArrayList<OcrBubble>(regions.size)
            for (region in regions) {
                val text = bubbleTextRecognizer.recognizeRegion(
                    source = bitmap,
                    rect = region.rect,
                    language = language,
                    useLocalOcr = useLocalOcr,
                    logTag = "Pipeline"
                )
                if (text.isBlank() && !useLocalOcr) {
                    continue
                }
                bubbles.add(
                    OcrBubble(
                        id = region.id,
                        rect = region.rect,
                        text = text,
                        source = region.source,
                        maskContour = region.maskContour
                    )
                )
            }
            val mergedBubbles = RectGeometryDeduplicator.mergeShortTextDetectorOcrBubbles(
                bubbles = bubbles,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height
            )
            if (mergedBubbles.size < bubbles.size) {
                AppLogger.log(
                    "Pipeline",
                    "Merged short text detector OCR bubbles: ${bubbles.size} -> ${mergedBubbles.size}"
                )
            }
            val result = PageOcrResult(
                imageFile,
                bitmap.width,
                bitmap.height,
                mergedBubbles,
                cacheMode,
                expectedMetadata
            )
            ocrStore.save(imageFile, result)
            result
        } finally {
            bitmap.recycleSafely()
        }
    }

    suspend fun translateFullPage(
        page: PageOcrResult,
        glossary: Map<String, String>,
        promptAsset: String,
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH,
        providerContext: PageTranslationProviderContext? = null,
        onProgress: (String) -> Unit
    ): TranslationResult? = withContext(Dispatchers.Default) {
        val metadata = buildTranslationMetadata(
            imageFile = page.imageFile,
            language = language,
            mode = TranslationMetadata.MODE_FULL_PAGE,
            promptAsset = promptAsset,
            ocrCacheMode = page.cacheMode,
            providerContext = providerContext
        )
        val translatable = page.bubbles.filter { it.text.isNotBlank() }
        if (translatable.isEmpty()) {
            val emptyTranslations = page.bubbles.map {
                BubbleTranslation(it.id, it.rect, "", it.source, it.maskContour)
            }
            return@withContext TranslationResult(
                page.imageFile.name,
                page.width,
                page.height,
                emptyTranslations,
                metadata
            )
        }
        onProgress(appContext.getString(R.string.translating_bubbles))
        val translatedBubbles = try {
            val translated = executeWithModelResponseRetries("Pipeline") {
                textBubbleTranslationCoordinator.translateBubbles(
                    bubbles = translatable.map {
                        BubbleTranslation(it.id, it.rect, it.text, it.source, it.maskContour)
                    },
                    glossary = glossary,
                    promptAsset = promptAsset,
                    apiSettings = providerContext?.apiSettings,
                    language = language,
                    logTag = "Pipeline",
                    translationMode = "full_page"
                )
            } ?: return@withContext null
            translated.bubbles
        } catch (e: LlmResponseException) {
            throw e.withPageName(page.imageFile.name)
        }
        val translationMap = translatedBubbles.associateBy({ it.id }, { it.text })
        val bubbles = page.bubbles.map { bubble ->
            val text = translationMap[bubble.id] ?: ""
            BubbleTranslation(bubble.id, bubble.rect, text, bubble.source, bubble.maskContour)
        }
        TranslationResult(page.imageFile.name, page.width, page.height, bubbles, metadata)
    }

    suspend fun translateImageWithVl(
        imageFile: File,
        language: TranslationLanguage
    ): FolderVlTranslateOutcome =
        withContext(Dispatchers.Default) {
            if (!llmClient.isConfigured()) {
                AppLogger.log("Pipeline", "Missing API settings for VL direct translate")
                return@withContext FolderVlTranslateOutcome()
            }
            val page = detectImageBubbles(imageFile) ?: return@withContext FolderVlTranslateOutcome()
            if (page.bubbles.isEmpty()) {
                return@withContext FolderVlTranslateOutcome(
                    result = TranslationResult(
                        imageFile.name,
                        page.width,
                        page.height,
                        emptyList(),
                        buildTranslationMetadata(
                            imageFile = imageFile,
                            language = language,
                            mode = TranslationMetadata.MODE_VL_DIRECT,
                            promptAsset = VL_PROMPT_ASSET,
                            ocrCacheMode = "",
                            providerContext = null
                        )
                    )
                )
            }
            val bitmap = android.graphics.BitmapFactory.decodeFile(imageFile.absolutePath)
                ?: run {
                    AppLogger.log("Pipeline", "Failed to decode ${imageFile.name} for VL direct translate")
                    return@withContext FolderVlTranslateOutcome()
                }
            try {
                val floatingSettings = settingsStore.loadFloatingTranslateApiSettings()
                val outcome = floatingBubbleTranslationCoordinator.translateImageBubbles(
                    bitmap = bitmap,
                    bubbles = page.bubbles.map { bubble ->
                        BubbleTranslation(bubble.id, bubble.rect, "", bubble.source, bubble.maskContour)
                    },
                    timeoutMs = settingsStore.loadApiTimeoutMs(),
                    retryCount = 3,
                    promptAsset = VL_PROMPT_ASSET,
                    apiSettings = settingsStore.load(),
                    concurrency = floatingSettings.vlTranslateConcurrency,
                    maxConcurrency = 16,
                    useCache = false,
                    logTag = "Pipeline"
                )
                if (outcome.requiresVlModel || outcome.timedOut) {
                    return@withContext FolderVlTranslateOutcome(
                        timedOut = outcome.timedOut,
                        requiresVlModel = outcome.requiresVlModel
                    )
                }
                FolderVlTranslateOutcome(
                    result = TranslationResult(
                        imageFile.name,
                        page.width,
                        page.height,
                        outcome.bubbles,
                        buildTranslationMetadata(
                            imageFile = imageFile,
                            language = language,
                            mode = TranslationMetadata.MODE_VL_DIRECT,
                            promptAsset = VL_PROMPT_ASSET,
                            ocrCacheMode = "",
                            providerContext = null
                        )
                    )
                )
            } finally {
                bitmap.recycleSafely()
            }
        }

    fun hasValidTranslation(
        imageFile: File,
        fullTranslate: Boolean,
        useVlDirectTranslate: Boolean,
        language: TranslationLanguage
    ): Boolean {
        return loadValidTranslation(
            imageFile = imageFile,
            fullTranslate = fullTranslate,
            useVlDirectTranslate = useVlDirectTranslate,
            language = language
        ) != null
    }

    fun loadValidTranslation(
        imageFile: File,
        fullTranslate: Boolean,
        useVlDirectTranslate: Boolean,
        language: TranslationLanguage
    ): TranslationResult? {
        val expected = buildExpectedTranslationMetadata(
            imageFile = imageFile,
            fullTranslate = fullTranslate,
            useVlDirectTranslate = useVlDirectTranslate,
            language = language
        )
        return store.load(imageFile, expectedMetadata = expected)
    }

    fun saveResult(imageFile: File, result: TranslationResult): File {
        val saved = store.save(imageFile, result)
        val ocrFile = ocrStore.ocrFileFor(imageFile)
        if (ocrFile.exists()) {
            ocrFile.delete()
        }
        return saved
    }

    suspend fun buildBlankTranslationResult(
        imageFile: File,
        forceOcr: Boolean,
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH
    ): TranslationResult? = withContext(Dispatchers.Default) {
        val page = ocrImage(imageFile, forceOcr, language) { } ?: return@withContext null
        buildBlankTranslationResult(
            page = page,
            mode = TranslationMetadata.MODE_STANDARD,
            promptAsset = STANDARD_PROMPT_ASSET,
            language = language
        )
    }

    fun buildBlankTranslationResult(
        page: PageOcrResult,
        mode: String,
        promptAsset: String,
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH
    ): TranslationResult {
        val metadata = buildTranslationMetadata(
            imageFile = page.imageFile,
            language = language,
            mode = mode,
            promptAsset = promptAsset,
            ocrCacheMode = page.cacheMode,
            providerContext = null
        )
        val bubbles = page.bubbles.map { bubble ->
            BubbleTranslation(bubble.id, bubble.rect, "", bubble.source, bubble.maskContour)
        }
        return TranslationResult(
            imageName = page.imageFile.name,
            width = page.width,
            height = page.height,
            bubbles = bubbles,
            metadata = metadata
        )
    }

    fun translationFileFor(imageFile: File): File {
        return store.translationFileFor(imageFile)
    }

    private suspend fun detectImageBubbles(imageFile: File): PageOcrResult? =
        withContext(Dispatchers.Default) {
            val bitmap = android.graphics.BitmapFactory.decodeFile(imageFile.absolutePath)
                ?: run {
                    AppLogger.log("Pipeline", "Failed to decode ${imageFile.name}")
                    return@withContext null
                }
            try {
                val pageRegions = pageRegionDetector.detect(bitmap, logTag = "Pipeline")
                    ?: return@withContext null
                val bubbles = pageRegions.regions.map { region ->
                    OcrBubble(
                        id = region.id,
                        rect = region.rect,
                        text = "",
                        source = region.source,
                        maskContour = region.maskContour
                    )
                }
                PageOcrResult(imageFile, bitmap.width, bitmap.height, bubbles)
            } finally {
                bitmap.recycleSafely()
            }
        }

    companion object {
        private const val STANDARD_PROMPT_ASSET = "prompts/llm_prompts.json"
        private const val FULL_TRANS_PROMPT_ASSET = "prompts/llm_prompts_FullTrans.json"
        private const val VL_PROMPT_ASSET = "prompts/vl_bubble_prompts.json"
        private const val MODEL_RESPONSE_SILENT_RETRY_COUNT = 3
    }

    private fun buildExpectedTranslationMetadata(
        imageFile: File,
        fullTranslate: Boolean,
        useVlDirectTranslate: Boolean,
        language: TranslationLanguage
    ): TranslationMetadata {
        val baseMetadata = when {
            useVlDirectTranslate -> buildTranslationMetadata(
                imageFile = imageFile,
                language = language,
                mode = TranslationMetadata.MODE_VL_DIRECT,
                promptAsset = VL_PROMPT_ASSET,
                ocrCacheMode = "",
                providerContext = null
            )
            fullTranslate -> buildTranslationMetadata(
                imageFile = imageFile,
                language = language,
                mode = TranslationMetadata.MODE_FULL_PAGE,
                promptAsset = FULL_TRANS_PROMPT_ASSET,
                ocrCacheMode = buildOcrCacheMode(settingsStore.loadOcrApiSettings().useLocalOcr, language),
                providerContext = null
            )
            else -> buildTranslationMetadata(
                imageFile = imageFile,
                language = language,
                mode = TranslationMetadata.MODE_STANDARD,
                promptAsset = STANDARD_PROMPT_ASSET,
                ocrCacheMode = buildOcrCacheMode(settingsStore.loadOcrApiSettings().useLocalOcr, language),
                providerContext = null
            )
        }
        if (useVlDirectTranslate) {
            return baseMetadata
        }
        val providerPool = settingsStore.loadMainTranslationProviderPool()
        if (providerPool.isEmpty()) {
            return baseMetadata
        }
        return baseMetadata.copy(
            modelName = providerPool.map { it.settings.modelName.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString("|"),
            providerId = providerPool.map { it.providerId }
                .distinct()
                .joinToString("|")
        )
    }

    private fun buildTranslationMetadata(
        imageFile: File,
        language: TranslationLanguage,
        mode: String,
        promptAsset: String,
        ocrCacheMode: String,
        providerContext: PageTranslationProviderContext?
    ): TranslationMetadata {
        val availableProviderIds = settingsStore.loadMainTranslationProviderPool()
            .map { it.providerId }
            .distinct()
        val apiSettings = providerContext?.apiSettings ?: settingsStore.load()
        return TranslationMetadata(
            sourceLastModified = imageFile.lastModified(),
            sourceFileSize = imageFile.length(),
            mode = mode,
            language = language.name,
            promptAsset = promptAsset,
            modelName = apiSettings.modelName,
            providerId = when {
                providerContext != null -> providerContext.providerId
                availableProviderIds.isNotEmpty() -> availableProviderIds.joinToString("|")
                else -> PRIMARY_PROVIDER_ID
            },
            apiFormat = apiSettings.apiFormat.prefValue,
            ocrCacheMode = ocrCacheMode
        )
    }

    private fun buildOcrMetadata(
        imageFile: File,
        language: TranslationLanguage,
        ocrSettings: OcrApiSettings,
        cacheMode: String
    ): OcrMetadata {
        val engineModel = if (ocrSettings.useLocalOcr) {
            "local:$cacheMode"
        } else {
            val customParamsFingerprint = settingsStore.loadCustomRequestParameters()
                .asSequence()
                .filter { it.enabled && it.targetProviderId == OCR_PROVIDER_ID }
                .map {
                    buildString {
                        append(it.key.trim())
                        append('=')
                        append(it.value.trim())
                    }
                }
                .sorted()
                .joinToString("&")
            if (customParamsFingerprint.isBlank()) {
                "api:${ocrSettings.modelName}"
            } else {
                "api:${ocrSettings.modelName}?$customParamsFingerprint"
            }
        }
        return OcrMetadata(
            sourceLastModified = imageFile.lastModified(),
            sourceFileSize = imageFile.length(),
            cacheMode = cacheMode,
            language = language.name,
            engineModel = engineModel
        )
    }

    private fun buildOcrCacheMode(
        useLocalOcr: Boolean,
        language: TranslationLanguage
    ): String {
        return if (!useLocalOcr) {
            "api"
        } else {
            val ocrSettings = settingsStore.loadOcrApiSettings()
            when (language) {
                TranslationLanguage.JA_TO_ZH -> when (ocrSettings.japaneseLocalOcrEngine) {
                    JapaneseLocalOcrEngine.MANGA_OCR_MOBILE -> "local_ja_mangaocr_mobile"
                }
                TranslationLanguage.EN_TO_ZH -> "local_en"
                TranslationLanguage.KO_TO_ZH -> "local_ko"
            }
        }
    }

    private suspend fun <T> executeWithModelResponseRetries(
        logTag: String,
        block: suspend () -> T?
    ): T? {
        var lastError: LlmResponseException? = null
        repeat(MODEL_RESPONSE_SILENT_RETRY_COUNT) { attempt ->
            try {
                return block()
            } catch (e: LlmResponseException) {
                lastError = e
                AppLogger.log(
                    logTag,
                    "Model response invalid, retry ${attempt + 1}/$MODEL_RESPONSE_SILENT_RETRY_COUNT",
                    e
                )
            }
        }
        throw requireNotNull(lastError)
    }

    private fun LlmResponseException.withPageName(pageName: String): LlmResponseException {
        if (responseContent.startsWith("页面：")) return this
        return LlmResponseException(
            errorCode = errorCode,
            responseContent = "页面：$pageName\n$responseContent",
            cause = this
        )
    }

}

data class OcrBubble(
    val id: Int,
    val rect: RectF,
    val text: String,
    val source: BubbleSource = BubbleSource.UNKNOWN,
    val maskContour: FloatArray? = null
)

data class PageOcrResult(
    val imageFile: File,
    val width: Int,
    val height: Int,
    val bubbles: List<OcrBubble>,
    val cacheMode: String = "",
    val metadata: OcrMetadata = OcrMetadata()
)

data class FolderVlTranslateOutcome(
    val result: TranslationResult? = null,
    val timedOut: Boolean = false,
    val requiresVlModel: Boolean = false
)
