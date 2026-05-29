package com.manga.translate

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal data class FolderTranslationTask(
    val folder: File,
    val images: List<File>,
    val force: Boolean,
    val fullTranslate: Boolean,
    val glossaryProcessingEnabled: Boolean,
    val useVlDirectTranslate: Boolean,
    val language: TranslationLanguage
)

internal class FolderTranslationCoordinator(
    context: Context,
    private val translationPipeline: TranslationPipeline,
    private val glossaryStore: GlossaryStore,
    private val extractStateStore: ExtractStateStore,
    private val translationStore: TranslationStore,
    private val settingsStore: SettingsStore,
    private val llmClient: LlmClient,
    private val ui: LibraryUiCallbacks,
    private val progressStore: TranslationProgressStore = TranslationProgressStore(),
    private val pendingBubbleRetranslator: PendingBubbleRetranslator? = null
) {
    private data class ResumeTranslationTask(
        val folder: File,
        val images: List<File>,
        val force: Boolean,
        val fullTranslate: Boolean,
        val glossaryProcessingEnabled: Boolean,
        val useVlDirectTranslate: Boolean,
        val language: TranslationLanguage,
        val onTranslateEnabled: (Boolean) -> Unit
    )

    private data class PreparedCollectionTask(
        val folder: File,
        val pendingImages: List<File>,
        val force: Boolean,
        val fullTranslate: Boolean,
        val glossaryProcessingEnabled: Boolean,
        val useVlDirectTranslate: Boolean,
        val language: TranslationLanguage
    )

    private enum class CollectionTaskResult {
        SUCCESS,
        FAILED,
        ABORTED
    }

    private data class PageTranslationExecutionResult(
        val result: TranslationResult? = null,
        val glossaryUsed: Map<String, String> = emptyMap(),
        val recoveredFromModelError: Boolean = false
    )

    private data class PreparedStandardPage(
        val image: File,
        val ocrResult: PageOcrResult?
    )

    private enum class ModelErrorAction {
        RETRY,
        SKIP
    }

    private val appContext = context.applicationContext
    private val translationRunning = AtomicBoolean(false)
    private val cancellationRequested = AtomicBoolean(false)
    @Volatile
    private var activeJob: Job? = null
    @Volatile
    private var resumableTask: ResumeTranslationTask? = null

    fun translateFolder(
        scope: CoroutineScope,
        folder: File,
        images: List<File>,
        force: Boolean,
        fullTranslate: Boolean,
        glossaryProcessingEnabled: Boolean,
        useVlDirectTranslate: Boolean,
        language: TranslationLanguage,
        onTranslateEnabled: (Boolean) -> Unit
    ): Job? {
        resumableTask = ResumeTranslationTask(
            folder = folder,
            images = images.toList(),
            force = force,
            fullTranslate = fullTranslate,
            glossaryProcessingEnabled = glossaryProcessingEnabled,
            useVlDirectTranslate = useVlDirectTranslate,
            language = language,
            onTranslateEnabled = onTranslateEnabled
        )
        if (fullTranslate) {
            return translateFolderFull(scope, folder, images, force, language, onTranslateEnabled)
        } else {
            return translateFolderStandard(
                scope,
                folder,
                images,
                force,
                glossaryProcessingEnabled,
                useVlDirectTranslate,
                language,
                onTranslateEnabled
            )
        }
    }

    fun translateCollection(
        scope: CoroutineScope,
        collectionFolder: File,
        tasks: List<FolderTranslationTask>,
        onTranslateEnabled: (Boolean) -> Unit
    ): Job? {
        return translateTaskBatch(
            scope = scope,
            tasks = tasks,
            onTranslateEnabled = onTranslateEnabled,
            onFinished = { ui.refreshImages(collectionFolder) }
        )
    }

    fun translateBatch(
        scope: CoroutineScope,
        tasks: List<FolderTranslationTask>,
        onTranslateEnabled: (Boolean) -> Unit
    ): Job? {
        return translateTaskBatch(
            scope = scope,
            tasks = tasks,
            onTranslateEnabled = onTranslateEnabled,
            onFinished = { ui.refreshFolders() }
        )
    }

    private fun translateTaskBatch(
        scope: CoroutineScope,
        tasks: List<FolderTranslationTask>,
        onTranslateEnabled: (Boolean) -> Unit,
        onFinished: () -> Unit
    ): Job? {
        if (tasks.isEmpty()) {
            ui.setFolderStatus(appContext.getString(R.string.folder_chapters_empty))
            return null
        }
        val preparedTasks = tasks.mapNotNull { task ->
            val pendingImages = resolvePendingImages(
                images = task.images,
                force = task.force,
                fullTranslate = task.fullTranslate,
                useVlDirectTranslate = task.useVlDirectTranslate,
                language = task.language
            )
            if (pendingImages.isEmpty()) {
                null
            } else {
                PreparedCollectionTask(
                    folder = task.folder,
                    pendingImages = pendingImages,
                    force = task.force,
                    fullTranslate = task.fullTranslate,
                    glossaryProcessingEnabled = task.glossaryProcessingEnabled,
                    useVlDirectTranslate = task.useVlDirectTranslate,
                    language = task.language
                )
            }
        }
        if (preparedTasks.isEmpty()) {
            ui.setFolderStatus(appContext.getString(R.string.translation_done))
            onFinished()
            return null
        }
        if (!llmClient.isConfigured()) {
            ui.setFolderStatus(appContext.getString(R.string.missing_api_settings))
            return null
        }
        if (!translationRunning.compareAndSet(false, true)) {
            ui.setFolderStatus(appContext.getString(R.string.translation_preparing))
            return activeJob
        }

        resumableTask = null
        cancellationRequested.set(false)
        TranslationCancellationRegistry.register { cancelActiveTranslation() }
        onTranslateEnabled(false)
        try {
            AppLogger.log(
                "Library",
                "Start translating task batch, ${preparedTasks.size} folders"
            )

            val totalImages = preparedTasks.sumOf { it.pendingImages.size }.coerceAtLeast(1)
            val job = scope.launch {
                var failed = false
                try {
                    var translatedImages = 0
                    for ((index, task) in preparedTasks.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        val result = if (task.fullTranslate) {
                            translateCollectionFolderFull(
                                scope = scope,
                                task = task,
                                chapterIndex = index,
                                chapterTotal = preparedTasks.size,
                                translatedImages = translatedImages,
                                totalImages = totalImages
                            )
                        } else {
                            translateCollectionFolderStandard(
                                scope = scope,
                                task = task,
                                chapterIndex = index,
                                chapterTotal = preparedTasks.size,
                                translatedImages = translatedImages,
                                totalImages = totalImages
                            )
                        }
                        when (result) {
                            CollectionTaskResult.SUCCESS -> {
                                translatedImages += task.pendingImages.size
                            }
                            CollectionTaskResult.FAILED -> {
                                translatedImages += task.pendingImages.size
                                failed = true
                            }
                            CollectionTaskResult.ABORTED -> {
                                failed = true
                                break
                            }
                        }
                    }
                    ui.setFolderStatus(
                        if (failed) appContext.getString(R.string.translation_failed) else appContext.getString(
                            R.string.translation_done
                        )
                    )
                    if (failed) {
                        GlobalTaskProgressStore.fail(
                            appContext.getString(R.string.translation_keepalive_title),
                            appContext.getString(R.string.translation_failed)
                        )
                    } else {
                        GlobalTaskProgressStore.complete(
                            appContext.getString(R.string.translation_keepalive_title),
                            appContext.getString(R.string.translation_done)
                        )
                    }
                    onFinished()
                } catch (e: CancellationException) {
                    if (cancellationRequested.get()) {
                        AppLogger.log("Library", "Batch translation canceled")
                        ui.setFolderStatus(appContext.getString(R.string.translation_canceled))
                        ui.showToast(R.string.translation_canceled)
                        GlobalTaskProgressStore.complete(
                            appContext.getString(R.string.translation_keepalive_title),
                            appContext.getString(R.string.translation_canceled)
                        )
                        onFinished()
                    } else {
                        throw e
                    }
                } finally {
                    activeJob = null
                    TranslationCancellationRegistry.clear()
                    cancellationRequested.set(false)
                    onTranslateEnabled(true)
                    translationRunning.set(false)
                }
            }
            activeJob = job
            if (cancellationRequested.get()) {
                job.cancel(CancellationException(USER_CANCELED_REASON))
            }
            return job
        } catch (e: Exception) {
            activeJob = null
            TranslationCancellationRegistry.clear()
            cancellationRequested.set(false)
            onTranslateEnabled(true)
            translationRunning.set(false)
            AppLogger.log("Library", "Failed to start batch translation", e)
            ui.setFolderStatus(appContext.getString(R.string.translation_failed))
            GlobalTaskProgressStore.fail(
                appContext.getString(R.string.translation_keepalive_title),
                appContext.getString(R.string.translation_failed)
            )
            return null
        }
    }

    private fun translateFolderStandard(
        scope: CoroutineScope,
        folder: File,
        images: List<File>,
        force: Boolean,
        glossaryProcessingEnabled: Boolean,
        useVlDirectTranslate: Boolean,
        language: TranslationLanguage,
        onTranslateEnabled: (Boolean) -> Unit
    ): Job? {
        if (images.isEmpty()) {
            ui.setFolderStatus(appContext.getString(R.string.folder_images_empty))
            return null
        }
        val pendingImages = resolvePendingImages(
            images = images,
            force = force,
            fullTranslate = false,
            useVlDirectTranslate = useVlDirectTranslate,
            language = language
        )
        if (pendingImages.isEmpty()) {
            ui.setFolderStatus(appContext.getString(R.string.translation_done))
            return null
        }
        if (!llmClient.isConfigured()) {
            ui.setFolderStatus(appContext.getString(R.string.missing_api_settings))
            return null
        }
        if (!translationRunning.compareAndSet(false, true)) {
            ui.setFolderStatus(appContext.getString(R.string.translation_preparing))
            return activeJob
        }

        cancellationRequested.set(false)
        TranslationCancellationRegistry.register { cancelActiveTranslation() }
        onTranslateEnabled(false)
        try {
            AppLogger.log(
                "Library",
                "Start translating folder ${folder.name}, ${pendingImages.size} images"
            )

            val job = scope.launch {
                var failed = false
                try {
                    val glossary = glossaryStore.load(folder).toMutableMap()
                    val glossaryMutex = Mutex()
                    ui.setFolderStatus(appContext.getString(R.string.translation_preparing))
                    failed = executeConcurrentStandardPages(
                        pages = pendingImages,
                        folder = folder,
                        force = force,
                        glossaryProcessingEnabled = glossaryProcessingEnabled,
                        useVlDirectTranslate = useVlDirectTranslate,
                        language = language,
                        glossary = glossary,
                        glossaryMutex = glossaryMutex,
                        onCountUpdated = { translatedCount ->
                            ui.setFolderStatus(
                                appContext.getString(
                                    R.string.folder_translation_count,
                                    translatedCount,
                                    pendingImages.size
                                )
                            )
                            TranslationKeepAliveService.updateProgress(
                                appContext,
                                translatedCount,
                                pendingImages.size
                            )
                        }
                    )
                    ui.setFolderStatus(
                        if (failed) appContext.getString(R.string.translation_failed) else appContext.getString(
                            R.string.translation_done
                        )
                    )
                    if (failed) {
                        GlobalTaskProgressStore.fail(
                            appContext.getString(R.string.translation_keepalive_title),
                            appContext.getString(R.string.translation_failed)
                        )
                    } else {
                        GlobalTaskProgressStore.complete(
                            appContext.getString(R.string.translation_keepalive_title),
                            appContext.getString(R.string.translation_done)
                        )
                    }
                    AppLogger.log(
                        "Library",
                        "Folder translation ${if (failed) "completed with failures" else "completed"}: ${folder.name}"
                    )
                    if (!failed) {
                        resumableTask = null
                    }
                    if (glossary.isNotEmpty()) {
                        glossaryStore.save(folder, glossary)
                    }
                    finalizeFolderProgress(folder, failed)
                    ui.refreshImages(folder)
                } catch (e: LlmRequestException) {
                    failed = true
                    AppLogger.log("Library", "Translation aborted for folder ${folder.name}", e)
                    ui.showApiError(e.errorCode, e.responseBody)
                    ui.setFolderStatus(appContext.getString(R.string.translation_failed))
                    GlobalTaskProgressStore.fail(
                        appContext.getString(R.string.translation_keepalive_title),
                        appContext.getString(R.string.translation_failed)
                    )
                    ui.refreshImages(folder)
                } catch (e: CancellationException) {
                    if (cancellationRequested.get()) {
                        AppLogger.log("Library", "Folder translation canceled: ${folder.name}")
                        ui.setFolderStatus(appContext.getString(R.string.translation_canceled))
                        ui.showToast(R.string.translation_canceled)
                        GlobalTaskProgressStore.complete(
                            appContext.getString(R.string.translation_keepalive_title),
                            appContext.getString(R.string.translation_canceled)
                        )
                        ui.refreshImages(folder)
                    } else {
                        throw e
                    }
                } finally {
                    activeJob = null
                    TranslationCancellationRegistry.clear()
                    cancellationRequested.set(false)
                    onTranslateEnabled(true)
                    translationRunning.set(false)
                }
            }
            activeJob = job
            if (cancellationRequested.get()) {
                job.cancel(CancellationException(USER_CANCELED_REASON))
            }
            return job
        } catch (e: Exception) {
            activeJob = null
            TranslationCancellationRegistry.clear()
            cancellationRequested.set(false)
            onTranslateEnabled(true)
            translationRunning.set(false)
            AppLogger.log("Library", "Failed to start folder translation ${folder.name}", e)
            ui.setFolderStatus(appContext.getString(R.string.translation_failed))
            GlobalTaskProgressStore.fail(
                appContext.getString(R.string.translation_keepalive_title),
                appContext.getString(R.string.translation_failed)
            )
            return null
        }
    }

    private fun translateFolderFull(
        scope: CoroutineScope,
        folder: File,
        images: List<File>,
        force: Boolean,
        language: TranslationLanguage,
        onTranslateEnabled: (Boolean) -> Unit
    ): Job? {
        if (images.isEmpty()) {
            ui.setFolderStatus(appContext.getString(R.string.folder_images_empty))
            return null
        }
        val pendingImages = resolvePendingImages(
            images = images,
            force = force,
            fullTranslate = true,
            useVlDirectTranslate = false,
            language = language
        )
        if (pendingImages.isEmpty()) {
            ui.setFolderStatus(appContext.getString(R.string.translation_done))
            return null
        }
        if (!llmClient.isConfigured()) {
            ui.setFolderStatus(appContext.getString(R.string.missing_api_settings))
            return null
        }
        if (!translationRunning.compareAndSet(false, true)) {
            ui.setFolderStatus(appContext.getString(R.string.translation_preparing))
            return activeJob
        }

        cancellationRequested.set(false)
        TranslationCancellationRegistry.register { cancelActiveTranslation() }
        onTranslateEnabled(false)
        try {
            AppLogger.log(
                "Library",
                "Start full-page translating folder ${folder.name}, ${pendingImages.size} images"
            )

            val job = scope.launch {
                var failed = false
                try {
                    val glossary = glossaryStore.load(folder).toMutableMap()
                    val extractState = extractStateStore.load(folder)
                    val ocrResults = ArrayList<PageOcrResult>(pendingImages.size)
                    reportPreprocessProgress(
                        stage = appContext.getString(R.string.folder_preprocess_stage_ocr),
                        processed = 0,
                        total = pendingImages.size
                    )
                    for ((index, image) in pendingImages.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        reportPreprocessProgress(
                            stage = appContext.getString(R.string.folder_preprocess_stage_ocr),
                            processed = index,
                            total = pendingImages.size,
                            imageName = image.name
                        )
                        val result = try {
                            translationPipeline.ocrImage(image, force, language) { }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLogger.log("Library", "OCR failed for ${image.name}", e)
                            null
                        }
                        if (result != null) {
                            ocrResults.add(result)
                        } else {
                            failed = true
                        }
                        reportPreprocessProgress(
                            stage = appContext.getString(R.string.folder_preprocess_stage_ocr),
                            processed = index + 1,
                            total = pendingImages.size,
                            imageName = image.name
                        )
                    }

                    val glossaryPages = ocrResults.filterNot {
                        translationPipeline.hasValidTranslation(
                            imageFile = it.imageFile,
                            fullTranslate = true,
                            useVlDirectTranslate = false,
                            language = language
                        ) ||
                            extractState.contains(it.imageFile.name)
                    }
                    val glossaryText = buildGlossaryText(glossaryPages)
                    if (glossaryText.isNotBlank()) {
                        val glossaryStage = appContext.getString(R.string.folder_preprocess_stage_glossary)
                        val glossaryImage = glossaryPages.firstOrNull()?.imageFile?.name
                        reportPreprocessProgress(
                            stage = glossaryStage,
                            processed = 0,
                            total = 1,
                            imageName = glossaryImage.orEmpty()
                        )
                        val abstractPromptAsset = "prompts/llm_prompts_abstract.json"
                        while (true) {
                            try {
                                val extracted =
                                    llmClient.extractGlossary(
                                        glossaryText,
                                        glossary,
                                        language.resolvePromptAsset(abstractPromptAsset)
                                    )
                                if (extracted != null) {
                                    if (extracted.isNotEmpty()) {
                                        for ((key, value) in extracted) {
                                            if (!glossary.containsKey(key)) {
                                                glossary[key] = value
                                            }
                                        }
                                        glossaryStore.save(folder, glossary)
                                    }
                                    for (page in glossaryPages) {
                                        extractState.add(page.imageFile.name)
                                    }
                                    extractStateStore.save(folder, extractState)
                                }
                                break
                            } catch (e: LlmRequestException) {
                                throw e
                            } catch (e: LlmResponseException) {
                                AppLogger.log("Library", "Full-page glossary response invalid", e)
                                if (reportModelError(e.responseContent) == ModelErrorAction.SKIP) {
                                    failed = true
                                    break
                                }
                            }
                        }
                        reportPreprocessProgress(
                            stage = glossaryStage,
                            processed = 1,
                            total = 1,
                            imageName = glossaryImage.orEmpty()
                        )
                    }

                    val glossaryMutex = Mutex()
                    ui.setFolderStatus(appContext.getString(R.string.translation_preparing))
                    failed = failed || executeConcurrentFullPages(
                        pages = ocrResults,
                        folder = folder,
                        promptAsset = "prompts/llm_prompts_FullTrans.json",
                        language = language,
                        glossary = glossary,
                        glossaryMutex = glossaryMutex,
                        onCountUpdated = { processedCount ->
                            withContext(Dispatchers.Main) {
                                ui.setFolderStatus(
                                    appContext.getString(
                                        R.string.folder_translation_count,
                                        processedCount,
                                        pendingImages.size
                                    )
                                )
                                TranslationKeepAliveService.updateProgress(
                                    appContext,
                                    processedCount,
                                    pendingImages.size
                                )
                            }
                        }
                    )
                    ui.setFolderStatus(
                        if (failed) appContext.getString(R.string.translation_failed) else appContext.getString(
                            R.string.translation_done
                        )
                    )
                    if (failed) {
                        GlobalTaskProgressStore.fail(
                            appContext.getString(R.string.translation_keepalive_title),
                            appContext.getString(R.string.translation_failed)
                        )
                    } else {
                        GlobalTaskProgressStore.complete(
                            appContext.getString(R.string.translation_keepalive_title),
                            appContext.getString(R.string.translation_done)
                        )
                    }
                    AppLogger.log(
                        "Library",
                        "Full-page translation ${if (failed) "completed with failures" else "completed"}: ${folder.name}"
                    )
                    if (!failed) {
                        resumableTask = null
                    }
                    finalizeFolderProgress(folder, failed)
                    ui.refreshImages(folder)
                } catch (e: LlmRequestException) {
                    AppLogger.log("Library", "Full-page translation aborted", e)
                    ui.showApiError(e.errorCode, e.responseBody)
                    ui.setFolderStatus(appContext.getString(R.string.translation_failed))
                    GlobalTaskProgressStore.fail(
                        appContext.getString(R.string.translation_keepalive_title),
                        appContext.getString(R.string.translation_failed)
                    )
                    ui.refreshImages(folder)
                } catch (e: CancellationException) {
                    if (cancellationRequested.get()) {
                        AppLogger.log("Library", "Full-page translation canceled: ${folder.name}")
                        ui.setFolderStatus(appContext.getString(R.string.translation_canceled))
                        ui.showToast(R.string.translation_canceled)
                        GlobalTaskProgressStore.complete(
                            appContext.getString(R.string.translation_keepalive_title),
                            appContext.getString(R.string.translation_canceled)
                        )
                        ui.refreshImages(folder)
                    } else {
                        throw e
                    }
                } finally {
                    activeJob = null
                    TranslationCancellationRegistry.clear()
                    cancellationRequested.set(false)
                    onTranslateEnabled(true)
                    translationRunning.set(false)
                }
            }
            activeJob = job
            if (cancellationRequested.get()) {
                job.cancel(CancellationException(USER_CANCELED_REASON))
            }
            return job
        } catch (e: Exception) {
            activeJob = null
            TranslationCancellationRegistry.clear()
            cancellationRequested.set(false)
            onTranslateEnabled(true)
            translationRunning.set(false)
            AppLogger.log("Library", "Failed to start full-page translation ${folder.name}", e)
            ui.setFolderStatus(appContext.getString(R.string.translation_failed))
            GlobalTaskProgressStore.fail(
                appContext.getString(R.string.translation_keepalive_title),
                appContext.getString(R.string.translation_failed)
            )
            return null
        }
    }

    private suspend fun translateCollectionFolderStandard(
        scope: CoroutineScope,
        task: PreparedCollectionTask,
        chapterIndex: Int,
        chapterTotal: Int,
        translatedImages: Int,
        totalImages: Int
    ): CollectionTaskResult {
        val glossary = glossaryStore.load(task.folder).toMutableMap()
        val glossaryMutex = Mutex()
        return try {
            val failed = executeConcurrentStandardPages(
                pages = task.pendingImages,
                folder = task.folder,
                force = task.force,
                glossaryProcessingEnabled = task.glossaryProcessingEnabled,
                useVlDirectTranslate = task.useVlDirectTranslate,
                language = task.language,
                glossary = glossary,
                glossaryMutex = glossaryMutex,
                onCountUpdated = { processedCount ->
                    val imageName = task.pendingImages
                        .getOrNull((processedCount - 1).coerceAtLeast(0))
                        ?.name
                        .orEmpty()
                    reportCollectionProgress(
                        chapterIndex = chapterIndex,
                        chapterTotal = chapterTotal,
                        imageIndex = translatedImages + processedCount,
                        imageTotal = totalImages,
                        chapterName = task.folder.name,
                        imageName = imageName
                    )
                }
            )
            if (glossary.isNotEmpty()) {
                glossaryStore.save(task.folder, glossary)
            }
            finalizeFolderProgress(task.folder, failed)
            if (failed) CollectionTaskResult.FAILED else CollectionTaskResult.SUCCESS
        } catch (e: LlmRequestException) {
            AppLogger.log("Library", "Collection translation aborted for ${task.folder.name}", e)
            ui.showApiError(e.errorCode, e.responseBody)
            CollectionTaskResult.ABORTED
        }
    }

    private suspend fun translateCollectionFolderFull(
        scope: CoroutineScope,
        task: PreparedCollectionTask,
        chapterIndex: Int,
        chapterTotal: Int,
        translatedImages: Int,
        totalImages: Int
    ): CollectionTaskResult {
        var failed = false
        val glossary = glossaryStore.load(task.folder).toMutableMap()
        val extractState = extractStateStore.load(task.folder)
        val ocrResults = ArrayList<PageOcrResult>(task.pendingImages.size)
        for ((index, image) in task.pendingImages.withIndex()) {
            currentCoroutineContext().ensureActive()
            reportCollectionProgress(
                chapterIndex = chapterIndex,
                chapterTotal = chapterTotal,
                imageIndex = translatedImages + index,
                imageTotal = totalImages,
                chapterName = task.folder.name,
                imageName = image.name
            )
            val result = try {
                translationPipeline.ocrImage(image, task.force, task.language) { }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.log("Library", "Collection OCR failed for ${image.name}", e)
                null
            }
            if (result != null) {
                ocrResults.add(result)
            } else {
                failed = true
            }
        }

        val glossaryPages = ocrResults.filterNot {
            translationPipeline.hasValidTranslation(
                imageFile = it.imageFile,
                fullTranslate = true,
                useVlDirectTranslate = false,
                language = task.language
            ) || extractState.contains(it.imageFile.name)
        }
        val glossaryText = buildGlossaryText(glossaryPages)
        if (glossaryText.isNotBlank()) {
            val abstractPromptAsset = "prompts/llm_prompts_abstract.json"
            while (true) {
                try {
                    val extracted = llmClient.extractGlossary(
                        glossaryText,
                        glossary,
                        task.language.resolvePromptAsset(abstractPromptAsset)
                    )
                    if (extracted != null) {
                        if (extracted.isNotEmpty()) {
                            for ((key, value) in extracted) {
                                if (!glossary.containsKey(key)) {
                                    glossary[key] = value
                                }
                            }
                            glossaryStore.save(task.folder, glossary)
                        }
                        for (page in glossaryPages) {
                            extractState.add(page.imageFile.name)
                        }
                        extractStateStore.save(task.folder, extractState)
                    }
                    break
                } catch (e: LlmRequestException) {
                    AppLogger.log("Library", "Collection glossary extraction aborted", e)
                    ui.showApiError(e.errorCode, e.responseBody)
                    return CollectionTaskResult.ABORTED
                } catch (e: LlmResponseException) {
                    AppLogger.log("Library", "Collection glossary response invalid", e)
                    if (reportModelError(e.responseContent) == ModelErrorAction.SKIP) {
                        failed = true
                        break
                    }
                }
            }
        }

        val glossaryMutex = Mutex()
        return try {
            failed = failed || executeConcurrentFullPages(
                pages = ocrResults,
                folder = task.folder,
                promptAsset = "prompts/llm_prompts_FullTrans.json",
                language = task.language,
                glossary = glossary,
                glossaryMutex = glossaryMutex,
                onCountUpdated = { processedCount ->
                    val imageName = ocrResults
                        .getOrNull((processedCount - 1).coerceAtLeast(0))
                        ?.imageFile
                        ?.name
                        .orEmpty()
                    reportCollectionProgress(
                        chapterIndex = chapterIndex,
                        chapterTotal = chapterTotal,
                        imageIndex = translatedImages + processedCount,
                        imageTotal = totalImages,
                        chapterName = task.folder.name,
                        imageName = imageName
                    )
                }
            )
            finalizeFolderProgress(task.folder, failed)
            if (failed) CollectionTaskResult.FAILED else CollectionTaskResult.SUCCESS
        } catch (e: LlmRequestException) {
            AppLogger.log("Library", "Collection full translation aborted for ${task.folder.name}", e)
            ui.showApiError(e.errorCode, e.responseBody)
            CollectionTaskResult.ABORTED
        }
    }

    private fun reportCollectionProgress(
        chapterIndex: Int,
        chapterTotal: Int,
        imageIndex: Int,
        imageTotal: Int,
        chapterName: String,
        imageName: String
    ) {
        val safeChapterIndex = (chapterIndex + 1).coerceIn(1, chapterTotal.coerceAtLeast(1))
        val safeChapterTotal = chapterTotal.coerceAtLeast(1)
        val safeImageIndex = imageIndex.coerceIn(0, imageTotal.coerceAtLeast(1))
        val safeImageTotal = imageTotal.coerceAtLeast(1)
        val left = appContext.getString(
            R.string.folder_collection_translation_progress,
            safeChapterIndex,
            safeChapterTotal,
            safeImageIndex,
            safeImageTotal
        )
        val right = appContext.getString(
            R.string.folder_collection_translation_target,
            chapterName,
            imageName
        )
        ui.setFolderStatus(left, right)
        TranslationKeepAliveService.updateProgress(
            appContext,
            safeImageIndex,
            safeImageTotal,
            "$left  $chapterName / $imageName",
            appContext.getString(R.string.translation_keepalive_title),
            appContext.getString(R.string.translation_keepalive_message)
        )
    }

    private suspend fun reportModelError(content: String): ModelErrorAction {
        val resolution = CompletableDeferred<ModelErrorAction>()
        if (!ui.isUiAttached()) {
            AppLogger.log("Library", "Model error dialog skipped because UI is detached")
            return ModelErrorAction.SKIP
        }
        val appInForeground = ui.isAppInForeground()
        val useSystemOverlay = !appInForeground && ui.canShowSystemOverlay()
        if (!appInForeground && !useSystemOverlay) {
            AppLogger.log(
                "Library",
                "Model error dialog queued for foreground display because library is in background and overlay is unavailable"
            )
            TranslationKeepAliveService.notifyModelErrorNeedsAttention(appContext)
        }
        withContext(Dispatchers.Main) {
            ui.showModelError(
                content = content,
                useSystemOverlay = useSystemOverlay,
                onRetry = {
                    TranslationKeepAliveService.clearModelErrorAttention(appContext)
                    resolution.complete(ModelErrorAction.RETRY)
                },
                onSkip = {
                    TranslationKeepAliveService.clearModelErrorAttention(appContext)
                    resolution.complete(ModelErrorAction.SKIP)
                }
            )
        }
        return resolution.await()
    }

    private suspend fun executeConcurrentStandardPages(
        pages: List<File>,
        folder: File,
        force: Boolean,
        glossaryProcessingEnabled: Boolean,
        useVlDirectTranslate: Boolean,
        language: TranslationLanguage,
        glossary: MutableMap<String, String>,
        glossaryMutex: Mutex,
        onCountUpdated: suspend (Int) -> Unit
    ): Boolean {
        val maxConcurrency = settingsStore.loadMaxConcurrency()
        val apiSemaphore = Semaphore(maxConcurrency)
        val translatedCount = AtomicInteger(0)
        val hasFailures = AtomicBoolean(false)
        val requestFailed = AtomicBoolean(false)
        val requestException = AtomicReference<LlmRequestException?>(null)
        val scheduler = WeightedTranslationProviderScheduler(settingsStore.loadMainTranslationProviderPool())
        val preparedPages = ArrayList<PreparedStandardPage>(pages.size)

        for (image in pages) {
            currentCoroutineContext().ensureActive()
            val prepared = try {
                prepareStandardPageForTranslation(
                    image = image,
                    force = force,
                    useVlDirectTranslate = useVlDirectTranslate,
                    language = language
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.log("Library", "Prepare standard page failed for ${image.name}", e)
                null
            }
            if (prepared == null) {
                hasFailures.set(true)
                recordPageFailure(folder, image, null)
            } else {
                preparedPages.add(prepared)
            }
        }
        supervisorScope {
            val tasks = preparedPages.map { prepared ->
                async {
                    apiSemaphore.withPermit {
                        currentCoroutineContext().ensureActive()
                        val image = prepared.image
                        if (requestFailed.get()) {
                            markPageAborted(folder, image, hasFailures, requestException)
                            return@withPermit
                        }
                        progressStore.update(folder, image.name, PageProgressStatus.PENDING)
                        if (requestFailed.get()) {
                            markPageAborted(folder, image, hasFailures, requestException)
                            return@withPermit
                        }
                        var failureMessage: String? = null
                        val execution = try {
                            if (useVlDirectTranslate) {
                                executeVlPageTranslation(image, language)
                            } else {
                                executeStandardPageTranslation(
                                    folder = folder,
                                    image = image,
                                    page = prepared.ocrResult,
                                    force = force,
                                    glossaryProcessingEnabled = glossaryProcessingEnabled,
                                    language = language,
                                    scheduler = scheduler,
                                    glossary = glossary,
                                    glossaryMutex = glossaryMutex
                                )
                            }
                        } catch (e: LlmRequestException) {
                            requestException.compareAndSet(null, e)
                            requestFailed.set(true)
                            AppLogger.log("Library", "Translation aborted for ${image.name}", e)
                            failureMessage = e.message
                            null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLogger.log("Library", "Translation failed for ${image.name}", e)
                            failureMessage = e.message
                            null
                        }
                        if (execution?.result != null) {
                            translationPipeline.saveResult(image, execution.result)
                            val savedStatus = execution.result.metadata.status
                            progressStore.update(
                                folder,
                                image.name,
                                if (savedStatus == PageTranslationStatus.SKIPPED) {
                                    PageProgressStatus.SKIPPED
                                } else {
                                    PageProgressStatus.SAVED
                                }
                            )
                            translatedCount.incrementAndGet()
                        } else if (execution?.recoveredFromModelError == true) {
                            // Skip path persisted SKIPPED metadata in skipStandardImage; mark progress.
                            progressStore.update(folder, image.name, PageProgressStatus.SKIPPED)
                            translatedCount.incrementAndGet()
                        } else {
                            hasFailures.set(true)
                            recordPageFailure(
                                folder,
                                image,
                                failureMessage ?: requestException.get()?.message
                            )
                        }
                        onCountUpdated(translatedCount.get())
                    }
                }
            }
            tasks.awaitAll()
        }
        requestException.get()?.let { throw it }
        return hasFailures.get()
    }

    private suspend fun prepareStandardPageForTranslation(
        image: File,
        force: Boolean,
        useVlDirectTranslate: Boolean,
        language: TranslationLanguage
    ): PreparedStandardPage? {
        if (useVlDirectTranslate) {
            return PreparedStandardPage(image = image, ocrResult = null)
        }
        if (!force && hasRefillablePartialTranslation(image)) {
            return PreparedStandardPage(image = image, ocrResult = null)
        }
        val ocrResult = translationPipeline.ocrImage(image, force, language) { } ?: return null
        return PreparedStandardPage(image = image, ocrResult = ocrResult)
    }

    private suspend fun executeConcurrentFullPages(
        pages: List<PageOcrResult>,
        folder: File,
        promptAsset: String,
        language: TranslationLanguage,
        glossary: MutableMap<String, String>,
        glossaryMutex: Mutex,
        onCountUpdated: suspend (Int) -> Unit
    ): Boolean {
        val maxConcurrency = settingsStore.loadMaxConcurrency()
        val semaphore = Semaphore(maxConcurrency)
        val translatedCount = AtomicInteger(0)
        val hasFailures = AtomicBoolean(false)
        val requestFailed = AtomicBoolean(false)
        val requestException = AtomicReference<LlmRequestException?>(null)
        val scheduler = WeightedTranslationProviderScheduler(settingsStore.loadMainTranslationProviderPool())
        supervisorScope {
            val tasks = pages.map { page ->
                async {
                    semaphore.withPermit {
                        currentCoroutineContext().ensureActive()
                        if (requestFailed.get()) {
                            markPageAborted(folder, page.imageFile, hasFailures, requestException)
                            return@withPermit
                        }
                        progressStore.update(folder, page.imageFile.name, PageProgressStatus.PENDING)
                        if (requestFailed.get()) {
                            markPageAborted(folder, page.imageFile, hasFailures, requestException)
                            return@withPermit
                        }
                        var failureMessage: String? = null
                        val execution = try {
                            executeFullPageTranslation(
                                folder = folder,
                                page = page,
                                promptAsset = promptAsset,
                                language = language,
                                scheduler = scheduler,
                                glossary = glossary,
                                glossaryMutex = glossaryMutex
                            )
                        } catch (e: LlmRequestException) {
                            requestException.compareAndSet(null, e)
                            requestFailed.set(true)
                            AppLogger.log("Library", "Full-page translation aborted for ${page.imageFile.name}", e)
                            failureMessage = e.message
                            null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLogger.log("Library", "Full-page translation failed for ${page.imageFile.name}", e)
                            failureMessage = e.message
                            null
                        }
                        if (execution?.result != null) {
                            translationPipeline.saveResult(page.imageFile, execution.result)
                            val savedStatus = execution.result.metadata.status
                            progressStore.update(
                                folder,
                                page.imageFile.name,
                                if (savedStatus == PageTranslationStatus.SKIPPED) {
                                    PageProgressStatus.SKIPPED
                                } else {
                                    PageProgressStatus.SAVED
                                }
                            )
                            translatedCount.incrementAndGet()
                        } else if (execution?.recoveredFromModelError == true) {
                            progressStore.update(folder, page.imageFile.name, PageProgressStatus.SKIPPED)
                            translatedCount.incrementAndGet()
                        } else {
                            hasFailures.set(true)
                            recordPageFailure(
                                folder,
                                page.imageFile,
                                failureMessage ?: requestException.get()?.message
                            )
                        }
                        onCountUpdated(translatedCount.get())
                    }
                }
            }
            tasks.awaitAll()
        }
        requestException.get()?.let { throw it }
        return hasFailures.get()
    }

    private suspend fun recordPageFailure(folder: File, image: File, errorMessage: String?) {
        progressStore.update(folder, image.name, PageProgressStatus.FAILED, errorMessage)
        val existing = translationPipeline.loadAnyTranslation(image)
        if (existing != null && existing.metadata.status == PageTranslationStatus.SUCCESS) {
            return
        }
        if (existing != null && existing.metadata.isManual()) {
            return
        }
        if (existing == null) {
            // No prior translation file; progress.json alone records the failure.
            return
        }
        val placeholder = existing.copy(
            metadata = existing.metadata.copy(status = PageTranslationStatus.FAILED)
        )
        try {
            translationPipeline.saveResult(image, placeholder)
        } catch (e: Exception) {
            AppLogger.log("Library", "Failed to record FAILED status for ${image.name}", e)
        }
    }

    private suspend fun markPageAborted(
        folder: File,
        image: File,
        hasFailures: AtomicBoolean,
        requestException: AtomicReference<LlmRequestException?>
    ) {
        hasFailures.set(true)
        recordPageFailure(folder, image, requestException.get()?.message)
    }

    private suspend fun finalizeFolderProgress(folder: File, failed: Boolean) {
        if (failed) return
        val progress = progressStore.load(folder)
        val keep = progress.values.any {
            it.status == PageProgressStatus.SKIPPED || it.status == PageProgressStatus.FAILED
        }
        if (!keep) {
            progressStore.clear(folder)
        }
    }

    private suspend fun executeStandardPageTranslation(
        folder: File,
        image: File,
        page: PageOcrResult?,
        force: Boolean,
        glossaryProcessingEnabled: Boolean,
        language: TranslationLanguage,
        scheduler: WeightedTranslationProviderScheduler,
        glossary: MutableMap<String, String>,
        glossaryMutex: Mutex
    ): PageTranslationExecutionResult {
        val orderedProviders = scheduler.orderedCandidatesForPage()
        if (orderedProviders.isEmpty()) {
            throw LlmRequestException("MISSING_API_SETTINGS", "No configured translation provider")
        }
        if (!force) {
            tryRefillPartial(
                folder = folder,
                image = image,
                language = language,
                promptAsset = STANDARD_PROMPT_ASSET,
                translationMode = "standard",
                glossary = glossary,
                glossaryMutex = glossaryMutex,
                glossaryProcessingEnabled = glossaryProcessingEnabled
            )?.let { return it }
        }
        val resolvedPage = page ?: translationPipeline.ocrImage(image, force, language) { }
            ?: return PageTranslationExecutionResult()
        var lastResponseException: LlmResponseException? = null
        var lastRequestException: LlmRequestException? = null
        orderedProviders.forEach { providerContext ->
            try {
                val glossarySnapshot = glossaryMutex.withLock { LinkedHashMap(glossary) }
                val result = translationPipeline.translateStandardPage(
                    page = resolvedPage,
                    imageFile = image,
                    glossary = glossarySnapshot,
                    language = language,
                    providerContext = providerContext
                ) { }
                if (result != null) {
                    val glossaryUsed = if (glossaryProcessingEnabled) {
                        glossarySnapshot.filterKeys { key ->
                            glossary[key] != glossarySnapshot[key]
                        }
                    } else {
                        emptyMap()
                    }
                    if (glossaryProcessingEnabled) {
                        mergeGlossary(glossary, glossaryUsed, glossaryMutex, folder)
                    }
                    AppLogger.log(
                        "Library",
                        "Translated ${image.name} via ${providerContext.displayName}"
                    )
                    return PageTranslationExecutionResult(
                        result = result,
                        glossaryUsed = glossaryUsed
                    )
                }
            } catch (e: LlmRequestException) {
                lastRequestException = e
                AppLogger.log(
                    "Library",
                    "Provider ${providerContext.displayName} request failed for ${image.name}",
                    e
                )
            } catch (e: LlmResponseException) {
                lastResponseException = e
                AppLogger.log(
                    "Library",
                    "Provider ${providerContext.displayName} returned invalid response for ${image.name}",
                    e
                )
            }
        }
        if (lastResponseException != null) {
            AppLogger.log("Library", "Invalid model response for ${image.name}", lastResponseException)
            return when (reportModelError(lastResponseException.responseContent)) {
                ModelErrorAction.RETRY -> {
                    val retried = retryStandardImage(
                        folder = folder,
                        image = image,
                        force = force,
                        glossaryProcessingEnabled = glossaryProcessingEnabled,
                        language = language,
                        scheduler = scheduler
                    )
                    PageTranslationExecutionResult(recoveredFromModelError = retried)
                }
                ModelErrorAction.SKIP -> {
                    skipStandardImage(folder, resolvedPage, language)
                    PageTranslationExecutionResult(recoveredFromModelError = true)
                }
            }
        }
        lastRequestException?.let { throw it }
        return PageTranslationExecutionResult()
    }

    private fun hasRefillablePartialTranslation(image: File): Boolean {
        if (pendingBubbleRetranslator == null) return false
        val existing = translationPipeline.loadAnyTranslation(image) ?: return false
        return existing.metadata.status == PageTranslationStatus.PARTIAL &&
            existing.metadata.matchesSource(image)
    }

    private suspend fun executeFullPageTranslation(
        folder: File,
        page: PageOcrResult,
        promptAsset: String,
        language: TranslationLanguage,
        scheduler: WeightedTranslationProviderScheduler,
        glossary: MutableMap<String, String>,
        glossaryMutex: Mutex
    ): PageTranslationExecutionResult {
        val orderedProviders = scheduler.orderedCandidatesForPage()
        if (orderedProviders.isEmpty()) {
            throw LlmRequestException("MISSING_API_SETTINGS", "No configured translation provider")
        }
        tryRefillPartial(
            folder = folder,
            image = page.imageFile,
            language = language,
            promptAsset = promptAsset,
            translationMode = "full_page",
            glossary = glossary,
            glossaryMutex = glossaryMutex,
            glossaryProcessingEnabled = true
        )?.let { return it }
        var lastResponseException: LlmResponseException? = null
        var lastRequestException: LlmRequestException? = null
        orderedProviders.forEach { providerContext ->
            try {
                val glossarySnapshot = glossaryMutex.withLock { LinkedHashMap(glossary) }
                val result = translationPipeline.translateFullPage(
                    page = page,
                    glossary = glossarySnapshot,
                    promptAsset = promptAsset,
                    language = language,
                    providerContext = providerContext
                ) { }
                if (result != null) {
                    val glossaryUsed = glossarySnapshot.filterKeys { key ->
                        glossary[key] != glossarySnapshot[key]
                    }
                    mergeGlossary(glossary, glossaryUsed, glossaryMutex, folder)
                    AppLogger.log(
                        "Library",
                        "Translated ${page.imageFile.name} via ${providerContext.displayName}"
                    )
                    return PageTranslationExecutionResult(
                        result = result,
                        glossaryUsed = glossaryUsed
                    )
                }
            } catch (e: LlmRequestException) {
                lastRequestException = e
                AppLogger.log(
                    "Library",
                    "Provider ${providerContext.displayName} request failed for ${page.imageFile.name}",
                    e
                )
            } catch (e: LlmResponseException) {
                lastResponseException = e
                AppLogger.log(
                    "Library",
                    "Provider ${providerContext.displayName} returned invalid response for ${page.imageFile.name}",
                    e
                )
            }
        }
        if (lastResponseException != null) {
            AppLogger.log("Library", "Invalid model response for ${page.imageFile.name}", lastResponseException)
            return when (reportModelError(lastResponseException.responseContent)) {
                ModelErrorAction.RETRY -> {
                    val retried = retryFullPageImage(folder, page, promptAsset, language, scheduler)
                    PageTranslationExecutionResult(recoveredFromModelError = retried)
                }
                ModelErrorAction.SKIP -> {
                    skipFullPageImage(folder, page, promptAsset, language)
                    PageTranslationExecutionResult(recoveredFromModelError = true)
                }
            }
        }
        lastRequestException?.let { throw it }
        return PageTranslationExecutionResult()
    }

    private suspend fun executeVlPageTranslation(
        image: File,
        language: TranslationLanguage
    ): PageTranslationExecutionResult {
        val outcome = translationPipeline.translateImageWithVl(
            imageFile = image,
            language = language
        )
        return when {
            outcome.requiresVlModel -> {
                ui.showToast(R.string.folder_vl_model_required)
                throw LlmRequestException("VL_MODEL_REQUIRED", image.name)
            }
            outcome.timedOut -> {
                ui.showToast(R.string.floating_translate_timeout)
                throw LlmRequestException("TIMEOUT", image.name)
            }
            outcome.result != null -> {
                PageTranslationExecutionResult(result = outcome.result)
            }
            else -> PageTranslationExecutionResult()
        }
    }

    private suspend fun tryRefillPartial(
        folder: File,
        image: File,
        language: TranslationLanguage,
        promptAsset: String,
        translationMode: String,
        glossary: MutableMap<String, String>,
        glossaryMutex: Mutex,
        glossaryProcessingEnabled: Boolean
    ): PageTranslationExecutionResult? {
        val retranslator = pendingBubbleRetranslator ?: return null
        val existing = translationPipeline.loadAnyTranslation(image) ?: return null
        if (existing.metadata.status != PageTranslationStatus.PARTIAL) return null
        if (!existing.metadata.matchesSource(image)) return null
        val glossarySnapshot = glossaryMutex.withLock { LinkedHashMap(glossary) }
        val outcome = try {
            retranslator.refill(
                imageFile = image,
                baseTranslation = existing,
                glossary = glossarySnapshot,
                language = language,
                promptAsset = promptAsset,
                translationMode = translationMode,
                logTag = "Library",
                discardShortOcr = false
            )
        } catch (e: LlmResponseException) {
            AppLogger.log("Library", "Partial refill rejected for ${image.name}", e)
            return null
        } catch (e: LlmRequestException) {
            throw e
        } ?: return null

        val glossaryUsed: Map<String, String> =
            if (glossaryProcessingEnabled) outcome.glossaryUsed else emptyMap()
        if (glossaryProcessingEnabled && glossaryUsed.isNotEmpty()) {
            mergeGlossary(glossary, glossaryUsed, glossaryMutex, folder)
        }
        AppLogger.log("Library", "Partial refill applied for ${image.name}")
        return PageTranslationExecutionResult(
            result = outcome.translation,
            glossaryUsed = glossaryUsed
        )
    }

    private suspend fun mergeGlossary(
        glossary: MutableMap<String, String>,
        additions: Map<String, String>,
        glossaryMutex: Mutex,
        folder: File
    ) {
        if (additions.isEmpty()) return
        val snapshotToSave = glossaryMutex.withLock {
            var changed = false
            additions.forEach { (key, value) ->
                if (key.isBlank() || value.isBlank()) return@forEach
                if (glossary[key] != value) {
                    glossary[key] = value
                    changed = true
                }
            }
            if (changed) LinkedHashMap(glossary) else null
        }
        if (snapshotToSave != null) {
            withContext(Dispatchers.IO) {
                glossaryStore.save(folder, snapshotToSave)
            }
        }
    }

    private suspend fun skipStandardImage(
        folder: File,
        page: PageOcrResult,
        language: TranslationLanguage
    ) {
        val blank = translationPipeline.buildBlankTranslationResult(
            page = page,
            mode = TranslationMetadata.MODE_STANDARD,
            promptAsset = STANDARD_PROMPT_ASSET,
            language = language
        )
        val skipped = blank.copy(
            metadata = blank.metadata.copy(status = PageTranslationStatus.SKIPPED)
        )
        translationPipeline.saveResult(page.imageFile, skipped)
        withContext(Dispatchers.Main) {
            ui.refreshImages(folder)
        }
    }

    private suspend fun retryStandardImage(
        folder: File,
        image: File,
        force: Boolean,
        glossaryProcessingEnabled: Boolean,
        language: TranslationLanguage,
        scheduler: WeightedTranslationProviderScheduler = WeightedTranslationProviderScheduler(
            settingsStore.loadMainTranslationProviderPool()
        )
    ): Boolean {
        val glossary = glossaryStore.load(folder).toMutableMap()
        val glossaryMutex = Mutex()
        val page = try {
            translationPipeline.ocrImage(image, force, language) { }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("Library", "Retry OCR failed for ${image.name}", e)
            null
        } ?: return false
        val execution = try {
            executeStandardPageTranslation(
                folder = folder,
                image = image,
                page = page,
                force = force,
                glossaryProcessingEnabled = glossaryProcessingEnabled,
                language = language,
                scheduler = scheduler,
                glossary = glossary,
                glossaryMutex = glossaryMutex
            )
        } catch (e: LlmRequestException) {
            AppLogger.log("Library", "Retry failed for ${image.name}", e)
            return false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("Library", "Retry failed for ${image.name}", e)
            return false
        }
        if (execution.result == null && !execution.recoveredFromModelError) {
            return false
        }
        execution.result?.let { translationPipeline.saveResult(image, it) }
        if (glossary.isNotEmpty()) {
            glossaryStore.save(folder, glossary)
        }
        withContext(Dispatchers.Main) {
            ui.refreshImages(folder)
        }
        return execution.result != null || execution.recoveredFromModelError
    }

    private suspend fun retryFullPageImage(
        folder: File,
        page: PageOcrResult,
        promptAsset: String,
        language: TranslationLanguage,
        scheduler: WeightedTranslationProviderScheduler = WeightedTranslationProviderScheduler(
            settingsStore.loadMainTranslationProviderPool()
        )
    ): Boolean {
        val glossary = glossaryStore.load(folder).toMutableMap()
        val glossaryMutex = Mutex()
        val execution = try {
            executeFullPageTranslation(
                folder = folder,
                page = page,
                promptAsset = promptAsset,
                language = language,
                scheduler = scheduler,
                glossary = glossary,
                glossaryMutex = glossaryMutex
            )
        } catch (e: LlmRequestException) {
            AppLogger.log("Library", "Retry failed for ${page.imageFile.name}", e)
            return false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("Library", "Retry failed for ${page.imageFile.name}", e)
            return false
        }
        if (execution.result == null && !execution.recoveredFromModelError) {
            return false
        }
        execution.result?.let { translationPipeline.saveResult(page.imageFile, it) }
        withContext(Dispatchers.Main) {
            ui.refreshImages(folder)
        }
        return execution.result != null || execution.recoveredFromModelError
    }

    private suspend fun skipFullPageImage(
        folder: File,
        page: PageOcrResult,
        promptAsset: String,
        language: TranslationLanguage
    ) {
        val blank = translationPipeline.buildBlankTranslationResult(
            page = page,
            mode = TranslationMetadata.MODE_FULL_PAGE,
            promptAsset = promptAsset,
            language = language
        )
        val skipped = blank.copy(
            metadata = blank.metadata.copy(status = PageTranslationStatus.SKIPPED)
        )
        translationPipeline.saveResult(page.imageFile, skipped)
        withContext(Dispatchers.Main) {
            ui.refreshImages(folder)
        }
    }

    private fun buildGlossaryText(pages: List<PageOcrResult>): String {
        val builder = StringBuilder()
        for (page in pages) {
            for (bubble in page.bubbles) {
                val text = bubble.text.trim()
                if (text.isNotBlank()) {
                    builder.append("<b>").append(text).append("</b>\n")
                }
            }
        }
        return builder.toString().trim()
    }

    private fun resolvePendingImages(
        images: List<File>,
        force: Boolean,
        fullTranslate: Boolean,
        useVlDirectTranslate: Boolean,
        language: TranslationLanguage
    ): List<File> {
        return if (force) {
            images
        } else {
            images.filterNot {
                translationPipeline.hasValidTranslation(
                    imageFile = it,
                    fullTranslate = fullTranslate,
                    useVlDirectTranslate = useVlDirectTranslate,
                    language = language
                )
            }
        }
    }

    private fun reportPreprocessProgress(
        stage: String,
        processed: Int,
        total: Int,
        imageName: String = ""
    ) {
        val safeTotal = total.coerceAtLeast(1)
        val safeProcessed = processed.coerceIn(0, safeTotal)
        val left = appContext.getString(
            R.string.folder_preprocess_progress,
            stage,
            safeProcessed,
            safeTotal
        )
        ui.setFolderStatus(left, imageName)
        val content = if (imageName.isBlank()) left else "$left  $imageName"
        TranslationKeepAliveService.updateProgress(
            appContext,
            safeProcessed,
            safeTotal,
            content,
            appContext.getString(R.string.translation_keepalive_title),
            appContext.getString(R.string.translation_keepalive_message)
        )
    }

    private fun cancelActiveTranslation(): Boolean {
        if (!translationRunning.get()) {
            return false
        }
        cancellationRequested.set(true)
        activeJob?.cancel(CancellationException(USER_CANCELED_REASON))
        return true
    }

    private companion object {
        private const val USER_CANCELED_REASON = "user_canceled_translation"
        private const val STANDARD_PROMPT_ASSET = "prompts/llm_prompts.json"
    }
}
