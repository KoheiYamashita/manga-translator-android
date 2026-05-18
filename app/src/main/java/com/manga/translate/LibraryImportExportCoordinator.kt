package com.manga.translate

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class LibraryImportExportCoordinator(
    context: Context,
    private val repository: LibraryRepository,
    private val translationStore: TranslationStore,
    private val settingsStore: SettingsStore,
    prefs: SharedPreferences,
    private val preferencesGateway: LibraryPreferencesGateway,
    private val dialogs: LibraryDialogs,
    private val ui: LibraryUiCallbacks
) {
    private val appContext = context.applicationContext
    private val prefsRef = prefs
    private var pendingExportAfterPermission = false
    private var pendingExportAfterExportTreeSelection = false
    private var pendingExportIsCollection = false
    private var pendingExportThreads = loadExportThreads()
    private var pendingExportFormat = loadExportFormatDefault()

    fun isPendingExportCollection(): Boolean = pendingExportIsCollection

    fun getExportThreadCount(): Int = loadExportThreads()
    fun getExportFormatDefault(): ExportFormat = loadExportFormatDefault()
    fun buildExportRootPathPreview(): String {
        val treeUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            preferencesGateway.getExportTreeUri()?.takeIf { preferencesGateway.hasExportPermission(it) }
        } else {
            null
        }
        return treeUri?.let(::buildExportRootPathHint) ?: "/Documents/manga-translate"
    }

    fun requestImportDirectory(
        requestImportPermission: (Uri?) -> Unit
    ) {
        requestImportPermission(preferencesGateway.buildImportInitialUri())
    }

    fun importFromArchiveOrPdf(
        uiContext: Context,
        uri: Uri,
        scope: CoroutineScope,
        onShowFolderList: () -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            val displayName = runCatching {
                uiContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                }
            }.getOrNull().orEmpty()
            val isPdf = displayName.substringAfterLast('.', "").lowercase() == "pdf"
            val result = if (isPdf) repository.importPdf(uri) else repository.importCbz(uri)
            withContext(Dispatchers.Main) {
                when {
                    result == null -> ui.showToast(
                        if (isPdf) R.string.pdf_import_failed else R.string.cbz_import_failed
                    )
                    result.importedCount <= 0 -> ui.showToast(
                        if (isPdf) R.string.pdf_import_no_images else R.string.cbz_import_no_images
                    )
                    else -> ui.showToastMessage(
                        uiContext.getString(
                            if (isPdf) R.string.pdf_import_done else R.string.cbz_import_done,
                            result.importedCount
                        )
                    )
                }
                ui.refreshFolders()
                onShowFolderList()
            }
        }
    }

    fun handleImportTreeSelection(
        uiContext: Context,
        uri: Uri,
        scope: CoroutineScope,
        onShowFolderList: () -> Unit
    ) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            uiContext.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            AppLogger.log("Library", "Persist import permission failed", e)
        }
        preferencesGateway.setImportTreeUri(uri)
        showImportFolderPicker(uiContext, uri, scope, onShowFolderList)
    }

    fun handleChapterImportTreeSelection(
        uiContext: Context,
        parentFolder: File,
        uri: Uri,
        scope: CoroutineScope
    ) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            uiContext.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            AppLogger.log("Library", "Persist chapter import permission failed", e)
        }
        preferencesGateway.setImportTreeUri(uri)
        showChapterImportFolderPicker(uiContext, parentFolder, uri, scope)
    }

    private fun showImportFolderPicker(
        uiContext: Context,
        treeUri: Uri,
        scope: CoroutineScope,
        onShowFolderList: () -> Unit
    ) {
        val root = DocumentFile.fromTreeUri(uiContext, treeUri)
        if (root == null || !root.canRead()) {
            ui.showToast(R.string.import_permission_required)
            return
        }
        val files = root.listFiles()
        val rootHasImages = files.any { it.isFile && isImageDocument(it) }
        val folders = files.filter { file ->
            file.isDirectory && file.listFiles().any { child -> child.isFile && isImageDocument(child) }
        }
        if (folders.isNotEmpty()) {
            val defaultName = root.name ?: ""
            dialogs.showEhViewerImportNameDialog(uiContext, defaultName) { importName ->
                importEhViewerCollection(uiContext, folders, importName, scope, onShowFolderList)
            }
            return
        }
        if (rootHasImages) {
            val defaultName = root.name ?: ""
            dialogs.showEhViewerImportNameDialog(uiContext, defaultName) { importName ->
                importEhViewerFolder(uiContext, root, importName, scope, onShowFolderList)
            }
            return
        }
        if (folders.isEmpty()) {
            ui.showToast(R.string.import_no_folders)
            return
        }
        dialogs.showEhViewerSubfolderPicker(uiContext, folders) { folder ->
            val defaultName = folder.name ?: ""
            dialogs.showEhViewerImportNameDialog(uiContext, defaultName) { importName ->
                importEhViewerFolder(uiContext, folder, importName, scope, onShowFolderList)
            }
        }
    }

    private fun importEhViewerFolder(
        uiContext: Context,
        source: DocumentFile,
        importName: String,
        scope: CoroutineScope,
        onShowFolderList: () -> Unit
    ) {
        val folder = repository.createFolder(importName)
        if (folder == null) {
            ui.showToast(R.string.import_folder_exists)
            return
        }
        val images = source.listFiles().filter { it.isFile && isImageDocument(it) }
        if (images.isEmpty()) {
            folder.deleteRecursively()
            ui.showToast(R.string.import_no_images)
            return
        }
        scope.launch(Dispatchers.IO) {
            val added = repository.addImages(folder, images.map { it.uri })
            withContext(Dispatchers.Main) {
                if (added.isEmpty()) {
                    folder.deleteRecursively()
                    ui.showToast(R.string.import_failed)
                } else {
                    ui.showToastMessage(uiContext.getString(R.string.import_done, added.size))
                }
                ui.refreshFolders()
                onShowFolderList()
            }
        }
    }

    private fun importEhViewerCollection(
        uiContext: Context,
        sources: List<DocumentFile>,
        importName: String,
        scope: CoroutineScope,
        onShowFolderList: () -> Unit
    ) {
        val collection = repository.createCollection(importName)
        if (collection == null) {
            ui.showToast(R.string.import_folder_exists)
            return
        }
        scope.launch(Dispatchers.IO) {
            var importedChapters = 0
            var importedImages = 0
            var skippedChapters = 0

            for (source in sources) {
                val sourceName = source.name?.trim().orEmpty()
                if (sourceName.isEmpty()) {
                    skippedChapters += 1
                    continue
                }
                val chapterFolder = repository.createChildFolder(collection, sourceName)
                if (chapterFolder == null) {
                    skippedChapters += 1
                    continue
                }
                val images = source.listFiles().filter { it.isFile && isImageDocument(it) }
                if (images.isEmpty()) {
                    chapterFolder.deleteRecursively()
                    skippedChapters += 1
                    continue
                }
                val added = repository.addImages(chapterFolder, images.map { it.uri })
                if (added.isEmpty()) {
                    chapterFolder.deleteRecursively()
                    skippedChapters += 1
                    continue
                }
                importedChapters += 1
                importedImages += added.size
            }

            withContext(Dispatchers.Main) {
                when {
                    importedChapters <= 0 -> {
                        collection.deleteRecursively()
                        ui.showToast(R.string.import_failed)
                    }
                    skippedChapters > 0 -> ui.showToastMessage(
                        uiContext.getString(
                            R.string.chapter_import_done_with_skipped,
                            importedChapters,
                            importedImages,
                            skippedChapters
                        )
                    )
                    else -> ui.showToastMessage(
                        uiContext.getString(
                            R.string.chapter_import_done,
                            importedChapters,
                            importedImages
                        )
                    )
                }
                ui.refreshFolders()
                onShowFolderList()
            }
        }
    }

    private fun showChapterImportFolderPicker(
        uiContext: Context,
        parentFolder: File,
        treeUri: Uri,
        scope: CoroutineScope
    ) {
        val root = DocumentFile.fromTreeUri(uiContext, treeUri)
        if (root == null || !root.canRead()) {
            ui.showToast(R.string.import_permission_required)
            return
        }
        val folders = root.listFiles().filter { folder ->
            folder.isDirectory && folder.listFiles().any { child -> child.isFile && isImageDocument(child) }
        }
        if (folders.isNotEmpty()) {
            dialogs.showDocumentFolderMultiPicker(
                context = uiContext,
                titleRes = R.string.chapter_import_select_folders,
                folders = folders
            ) { selected ->
                if (selected.isEmpty()) {
                    ui.showToast(R.string.chapter_import_no_folders)
                    return@showDocumentFolderMultiPicker
                }
                importChildChapters(uiContext, parentFolder, selected, scope)
            }
            return
        }
        val rootImages = root.listFiles().filter { it.isFile && isImageDocument(it) }
        if (rootImages.isEmpty()) {
            ui.showToast(R.string.chapter_import_no_folders)
            return
        }
        importChildChapters(uiContext, parentFolder, listOf(root), scope)
    }

    private fun importChildChapters(
        uiContext: Context,
        parentFolder: File,
        sources: List<DocumentFile>,
        scope: CoroutineScope
    ) {
        scope.launch(Dispatchers.IO) {
            var importedChapters = 0
            var importedImages = 0
            var skippedChapters = 0

            for (source in sources) {
                val sourceName = source.name?.trim().orEmpty()
                if (sourceName.isEmpty()) {
                    skippedChapters += 1
                    continue
                }
                val chapterFolder = repository.createChildFolder(parentFolder, sourceName)
                if (chapterFolder == null) {
                    skippedChapters += 1
                    continue
                }
                val images = source.listFiles().filter { it.isFile && isImageDocument(it) }
                if (images.isEmpty()) {
                    chapterFolder.deleteRecursively()
                    skippedChapters += 1
                    continue
                }
                val added = repository.addImages(chapterFolder, images.map { it.uri })
                if (added.isEmpty()) {
                    chapterFolder.deleteRecursively()
                    skippedChapters += 1
                    continue
                }
                importedChapters += 1
                importedImages += added.size
            }

            withContext(Dispatchers.Main) {
                when {
                    importedChapters <= 0 -> ui.showToast(R.string.import_failed)
                    skippedChapters > 0 -> ui.showToastMessage(
                        uiContext.getString(
                            R.string.chapter_import_done_with_skipped,
                            importedChapters,
                            importedImages,
                            skippedChapters
                        )
                    )
                    else -> ui.showToastMessage(
                        uiContext.getString(
                            R.string.chapter_import_done,
                            importedChapters,
                            importedImages
                        )
                    )
                }
                ui.refreshImages(parentFolder)
                ui.refreshFolders()
            }
        }
    }

    fun handleStoragePermissionResult(
        granted: Boolean,
        onGranted: () -> Unit
    ) {
        if (pendingExportAfterPermission && granted) {
            pendingExportAfterPermission = false
            onGranted()
            return
        }
        pendingExportAfterPermission = false
        pendingExportIsCollection = false
        if (!granted) {
            ui.showToast(R.string.export_permission_denied)
            ui.setFolderStatus(appContext.getString(R.string.export_permission_denied))
        }
    }

    fun handleExportTreeSelection(uri: Uri, onReady: () -> Unit) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            appContext.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            AppLogger.log("Library", "Persist export permission failed", e)
        }
        preferencesGateway.setExportTreeUri(uri)
        if (pendingExportAfterExportTreeSelection) {
            pendingExportAfterExportTreeSelection = false
            onReady()
        }
    }

    fun handleExportTreeCanceled() {
        pendingExportAfterExportTreeSelection = false
        pendingExportIsCollection = false
    }

    fun exportFolder(
        uiContext: Context,
        folder: File?,
        images: List<File>,
        scope: CoroutineScope,
        exportThreads: Int,
        exportFormat: ExportFormat,
        requestExportDirectoryPermission: (Uri?) -> Unit,
        requestLegacyPermission: () -> Unit,
        onExitSelectionMode: () -> Unit,
        onSetExportEnabled: (Boolean) -> Unit
    ) {
        if (folder == null) return
        pendingExportIsCollection = false
        pendingExportThreads = normalizeExportThreads(exportThreads)
        pendingExportFormat = exportFormat
        prefsRef.edit() {
            putInt(KEY_EXPORT_THREADS, pendingExportThreads)
            putString(KEY_EXPORT_FORMAT, pendingExportFormat.name)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val treeUri = preferencesGateway.getExportTreeUri()
            if (treeUri == null || !preferencesGateway.hasExportPermission(treeUri)) {
                pendingExportAfterExportTreeSelection = true
                ui.showToast(R.string.export_directory_required)
                requestExportDirectoryPermission(preferencesGateway.buildExportInitialUri())
                return
            }
        } else {
            val permission = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            val granted = ContextCompat.checkSelfPermission(
                uiContext,
                permission
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                pendingExportAfterPermission = true
                requestLegacyPermission()
                return
            }
        }
        exportFolderInternal(
            uiContext = uiContext,
            folder = folder,
            images = images,
            scope = scope,
            exportThreads = pendingExportThreads,
            exportFormat = pendingExportFormat,
            onExitSelectionMode = onExitSelectionMode,
            onSetExportEnabled = onSetExportEnabled
        )
    }

    fun exportFolderAfterPermission(
        uiContext: Context,
        folder: File?,
        images: List<File>,
        scope: CoroutineScope,
        onExitSelectionMode: () -> Unit,
        onSetExportEnabled: (Boolean) -> Unit
    ) {
        if (folder == null) return
        exportFolderInternal(
            uiContext = uiContext,
            folder = folder,
            images = images,
            scope = scope,
            exportThreads = pendingExportThreads,
            exportFormat = pendingExportFormat,
            onExitSelectionMode = onExitSelectionMode,
            onSetExportEnabled = onSetExportEnabled
        )
    }

    fun exportCollection(
        uiContext: Context,
        collectionFolder: File,
        chapterImages: List<Pair<File, List<File>>>,
        scope: CoroutineScope,
        exportThreads: Int,
        exportFormat: ExportFormat,
        requestExportDirectoryPermission: (Uri?) -> Unit,
        requestLegacyPermission: () -> Unit,
        onExitSelectionMode: () -> Unit,
        onSetExportEnabled: (Boolean) -> Unit
    ) {
        pendingExportIsCollection = true
        pendingExportThreads = normalizeExportThreads(exportThreads)
        pendingExportFormat = exportFormat
        prefsRef.edit() {
            putInt(KEY_EXPORT_THREADS, pendingExportThreads)
            putString(KEY_EXPORT_FORMAT, pendingExportFormat.name)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val treeUri = preferencesGateway.getExportTreeUri()
            if (treeUri == null || !preferencesGateway.hasExportPermission(treeUri)) {
                pendingExportAfterExportTreeSelection = true
                ui.showToast(R.string.export_directory_required)
                requestExportDirectoryPermission(preferencesGateway.buildExportInitialUri())
                return
            }
        } else {
            val permission = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            val granted = ContextCompat.checkSelfPermission(
                uiContext,
                permission
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                pendingExportAfterPermission = true
                requestLegacyPermission()
                return
            }
        }
        exportCollectionInternal(
            uiContext = uiContext,
            collectionFolder = collectionFolder,
            chapterImages = chapterImages,
            scope = scope,
            exportThreads = pendingExportThreads,
            exportFormat = pendingExportFormat,
            onExitSelectionMode = onExitSelectionMode,
            onSetExportEnabled = onSetExportEnabled
        )
    }

    fun exportCollectionAfterPermission(
        uiContext: Context,
        collectionFolder: File,
        chapterImages: List<Pair<File, List<File>>>,
        scope: CoroutineScope,
        onExitSelectionMode: () -> Unit,
        onSetExportEnabled: (Boolean) -> Unit
    ) {
        exportCollectionInternal(
            uiContext = uiContext,
            collectionFolder = collectionFolder,
            chapterImages = chapterImages,
            scope = scope,
            exportThreads = pendingExportThreads,
            exportFormat = pendingExportFormat,
            onExitSelectionMode = onExitSelectionMode,
            onSetExportEnabled = onSetExportEnabled
        )
    }

    private fun exportFolderInternal(
        uiContext: Context,
        folder: File,
        images: List<File>,
        scope: CoroutineScope,
        exportThreads: Int,
        exportFormat: ExportFormat,
        onExitSelectionMode: () -> Unit,
        onSetExportEnabled: (Boolean) -> Unit
    ) {
        onExitSelectionMode()
        val exportImages = images
        if (exportImages.isEmpty()) {
            ui.setFolderStatus(appContext.getString(R.string.folder_images_empty))
            return
        }
        val exportTreeUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            preferencesGateway.getExportTreeUri()?.takeIf { preferencesGateway.hasExportPermission(it) }
        } else {
            null
        }
        val verticalLayoutEnabled = !settingsStore.loadNormalBubbleRenderSettings().useHorizontalText

        onSetExportEnabled(false)
        TranslationKeepAliveService.start(
            appContext,
            appContext.getString(R.string.export_keepalive_title),
            appContext.getString(R.string.translation_keepalive_message),
            appContext.getString(R.string.exporting_progress, 0, exportImages.size)
        )
        TranslationKeepAliveService.updateStatus(
            appContext,
            appContext.getString(R.string.exporting_progress, 0, exportImages.size),
            appContext.getString(R.string.export_keepalive_title),
            appContext.getString(R.string.translation_keepalive_message)
        )

        scope.launch {
            var failed = false
            var exportDir: DocumentFile? = null
            var exportDirReady = true
            try {
                withContext(Dispatchers.IO) {
                    if (exportTreeUri != null && exportFormat == ExportFormat.IMAGE_DIR) {
                        exportDir = resolveExportDirectory(appContext, exportTreeUri, folder.name)
                        if (exportDir == null) {
                            exportDirReady = false
                        } else {
                            ensureNoMediaFile(exportDir)
                        }
                    } else if (exportFormat == ExportFormat.IMAGE_DIR) {
                        ensureNoMediaFile(appContext, folder.name)
                    }
                }
                if (!exportDirReady) {
                    failed = true
                    ui.setFolderStatus(appContext.getString(R.string.export_failed))
                    GlobalTaskProgressStore.fail(
                        appContext.getString(R.string.export_keepalive_title),
                        appContext.getString(R.string.export_failed)
                    )
                    return@launch
                }
                ui.setFolderStatus(appContext.getString(R.string.exporting_progress, 0, exportImages.size))
                var successPathHint: String? = null

                when (exportFormat) {
                    ExportFormat.CBZ -> {
                        val result = exportCbzWithBubbles(
                            context = appContext,
                            folder = folder,
                            images = exportImages,
                            verticalLayoutEnabled = verticalLayoutEnabled,
                            exportThreads = normalizeExportThreads(exportThreads),
                            exportTreeUri = exportTreeUri
                        ) { count ->
                            withContext(Dispatchers.Main) {
                                ui.setFolderStatus(
                                    appContext.getString(R.string.exporting_progress, count, exportImages.size)
                                )
                                TranslationKeepAliveService.updateProgress(
                                    appContext,
                                    count,
                                    exportImages.size,
                                    appContext.getString(R.string.exporting_progress, count, exportImages.size),
                                    appContext.getString(R.string.export_keepalive_title),
                                    appContext.getString(R.string.translation_keepalive_message)
                                )
                            }
                        }
                        failed = !result.success
                        successPathHint = result.pathHint
                    }
                    ExportFormat.PDF -> {
                        val result = exportPdfWithBubbles(
                            context = appContext,
                            folder = folder,
                            images = exportImages,
                            verticalLayoutEnabled = verticalLayoutEnabled,
                            exportThreads = normalizeExportThreads(exportThreads),
                            exportTreeUri = exportTreeUri
                        ) { count ->
                            withContext(Dispatchers.Main) {
                                ui.setFolderStatus(
                                    appContext.getString(R.string.exporting_progress, count, exportImages.size)
                                )
                                TranslationKeepAliveService.updateProgress(
                                    appContext,
                                    count,
                                    exportImages.size,
                                    appContext.getString(R.string.exporting_progress, count, exportImages.size),
                                    appContext.getString(R.string.export_keepalive_title),
                                    appContext.getString(R.string.translation_keepalive_message)
                                )
                            }
                        }
                        failed = !result.success
                        successPathHint = result.pathHint
                    }
                    ExportFormat.IMAGE_DIR -> {
                        val semaphore = Semaphore(normalizeExportThreads(exportThreads))
                        val exportedCount = AtomicInteger(0)
                        val hasFailures = AtomicBoolean(false)

                        coroutineScope {
                            val tasks = exportImages.map { image ->
                                async(Dispatchers.IO) {
                                    semaphore.withPermit {
                                        val renderer = BubbleRenderer(appContext)
                                        val success = exportImageWithBubbles(
                                            appContext,
                                            renderer,
                                            image,
                                            folder.name,
                                            verticalLayoutEnabled,
                                            exportDir
                                        )
                                        if (!success) {
                                            hasFailures.set(true)
                                        }
                                        val count = exportedCount.incrementAndGet()
                                        withContext(Dispatchers.Main) {
                                            ui.setFolderStatus(
                                                appContext.getString(R.string.exporting_progress, count, exportImages.size)
                                            )
                                            TranslationKeepAliveService.updateProgress(
                                                appContext,
                                                count,
                                                exportImages.size,
                                                appContext.getString(R.string.exporting_progress, count, exportImages.size),
                                                appContext.getString(R.string.export_keepalive_title),
                                                appContext.getString(R.string.translation_keepalive_message)
                                            )
                                        }
                                    }
                                }
                            }
                            tasks.awaitAll()
                        }
                        failed = hasFailures.get()
                    }
                }

                ui.setFolderStatus(
                    if (failed) appContext.getString(R.string.export_failed) else appContext.getString(R.string.export_done)
                )
                if (failed) {
                    GlobalTaskProgressStore.fail(
                        appContext.getString(R.string.export_keepalive_title),
                        appContext.getString(R.string.export_failed)
                    )
                } else {
                    GlobalTaskProgressStore.complete(
                        appContext.getString(R.string.export_keepalive_title),
                        appContext.getString(R.string.export_done)
                    )
                }
                if (!failed && ui.isFragmentActive()) {
                    val path = successPathHint ?: if (exportTreeUri != null) {
                        buildExportPathHint(exportTreeUri, folder.name)
                    } else {
                        "/Documents/manga-translate/${folder.name}"
                    }
                    dialogs.showExportSuccessDialog(uiContext, path)
                }
                AppLogger.log(
                    "Library",
                    "Export ${if (failed) "completed with failures" else "completed"}: ${folder.name}"
                )
            } finally {
                onSetExportEnabled(true)
                TranslationKeepAliveService.stop(appContext)
            }
        }
    }

    private fun exportCollectionInternal(
        uiContext: Context,
        collectionFolder: File,
        chapterImages: List<Pair<File, List<File>>>,
        scope: CoroutineScope,
        exportThreads: Int,
        exportFormat: ExportFormat,
        onExitSelectionMode: () -> Unit,
        onSetExportEnabled: (Boolean) -> Unit
    ) {
        onExitSelectionMode()
        val allImages = chapterImages.flatMap { it.second }
        if (allImages.isEmpty()) {
            ui.setFolderStatus(appContext.getString(R.string.folder_chapters_empty))
            return
        }
        val exportTreeUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            preferencesGateway.getExportTreeUri()?.takeIf { preferencesGateway.hasExportPermission(it) }
        } else {
            null
        }
        val verticalLayoutEnabled = !settingsStore.loadNormalBubbleRenderSettings().useHorizontalText

        onSetExportEnabled(false)
        TranslationKeepAliveService.start(
            appContext,
            appContext.getString(R.string.export_keepalive_title),
            appContext.getString(R.string.translation_keepalive_message),
            appContext.getString(R.string.exporting_progress, 0, allImages.size)
        )
        TranslationKeepAliveService.updateStatus(
            appContext,
            appContext.getString(R.string.exporting_progress, 0, allImages.size),
            appContext.getString(R.string.export_keepalive_title),
            appContext.getString(R.string.translation_keepalive_message)
        )

        scope.launch {
            var failed = false
            try {
                when (exportFormat) {
                    ExportFormat.IMAGE_DIR -> {
                        failed = exportCollectionAsImageDir(
                            context = appContext,
                            collectionName = collectionFolder.name,
                            chapterImages = chapterImages,
                            verticalLayoutEnabled = verticalLayoutEnabled,
                            exportThreads = normalizeExportThreads(exportThreads),
                            exportTreeUri = exportTreeUri,
                            totalImages = allImages.size
                        )
                    }
                    ExportFormat.CBZ -> {
                        val result = exportCollectionAsCbz(
                            context = appContext,
                            collectionFolder = collectionFolder,
                            chapterImages = chapterImages,
                            verticalLayoutEnabled = verticalLayoutEnabled,
                            exportThreads = normalizeExportThreads(exportThreads),
                            exportTreeUri = exportTreeUri
                        )
                        failed = !result.success
                        if (!failed) {
                            val path = result.pathHint ?: if (exportTreeUri != null) {
                                "${buildExportRootPathHint(exportTreeUri)}/${collectionFolder.name}.cbz"
                            } else {
                                "/Documents/manga-translate/${collectionFolder.name}.cbz"
                            }
                            if (ui.isFragmentActive()) {
                                dialogs.showExportSuccessDialog(uiContext, path)
                            }
                        }
                    }
                    ExportFormat.PDF -> {
                        val result = exportCollectionAsPdfWithOutlines(
                            context = appContext,
                            collectionFolder = collectionFolder,
                            chapterImages = chapterImages,
                            verticalLayoutEnabled = verticalLayoutEnabled,
                            exportThreads = normalizeExportThreads(exportThreads),
                            exportTreeUri = exportTreeUri
                        )
                        failed = !result.success
                        if (!failed) {
                            val path = result.pathHint ?: if (exportTreeUri != null) {
                                "${buildExportRootPathHint(exportTreeUri)}/${collectionFolder.name}.pdf"
                            } else {
                                "/Documents/manga-translate/${collectionFolder.name}.pdf"
                            }
                            if (ui.isFragmentActive()) {
                                dialogs.showExportSuccessDialog(uiContext, path)
                            }
                        }
                    }
                }

                ui.setFolderStatus(
                    if (failed) appContext.getString(R.string.export_failed) else appContext.getString(R.string.export_done)
                )
                if (failed) {
                    GlobalTaskProgressStore.fail(
                        appContext.getString(R.string.export_keepalive_title),
                        appContext.getString(R.string.export_failed)
                    )
                } else {
                    GlobalTaskProgressStore.complete(
                        appContext.getString(R.string.export_keepalive_title),
                        appContext.getString(R.string.export_done)
                    )
                }
                AppLogger.log(
                    "Library",
                    "Collection export ${if (failed) "completed with failures" else "completed"}: ${collectionFolder.name}"
                )
            } finally {
                onSetExportEnabled(true)
                TranslationKeepAliveService.stop(appContext)
            }
        }
    }

    private suspend fun exportCollectionAsImageDir(
        context: Context,
        collectionName: String,
        chapterImages: List<Pair<File, List<File>>>,
        verticalLayoutEnabled: Boolean,
        exportThreads: Int,
        exportTreeUri: Uri?,
        totalImages: Int
    ): Boolean {
        var collectionDir: DocumentFile? = null
        var collectionDirReady = true

        if (exportTreeUri != null) {
            collectionDir = resolveExportDirectory(context, exportTreeUri, collectionName)
            if (collectionDir == null) {
                collectionDirReady = false
            } else {
                ensureNoMediaFile(collectionDir)
            }
        } else {
            ensureNoMediaFile(context, collectionName)
        }

        if (!collectionDirReady) {
            return true
        }

        val semaphore = Semaphore(normalizeExportThreads(exportThreads))
        val exportedCount = AtomicInteger(0)
        val hasFailures = AtomicBoolean(false)

        coroutineScope {
            val tasks = chapterImages.flatMap { (chapter, images) ->
                images.map { image ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val renderer = BubbleRenderer(context)
                            val chapterExportDir = if (collectionDir != null) {
                                val existing = collectionDir.findFile(chapter.name)
                                when {
                                    existing == null -> collectionDir.createDirectory(chapter.name)
                                    existing.isDirectory -> existing
                                    else -> null
                                }
                            } else {
                                null
                            }
                            val success = exportImageWithBubbles(
                                context,
                                renderer,
                                image,
                                if (collectionDir != null) "" else "$collectionName/${chapter.name}",
                                verticalLayoutEnabled,
                                chapterExportDir
                            )
                            if (!success) {
                                hasFailures.set(true)
                            }
                            val count = exportedCount.incrementAndGet()
                            withContext(Dispatchers.Main) {
                                ui.setFolderStatus(
                                    appContext.getString(R.string.exporting_progress, count, totalImages)
                                )
                                TranslationKeepAliveService.updateProgress(
                                    appContext,
                                    count,
                                    totalImages,
                                    appContext.getString(R.string.exporting_progress, count, totalImages),
                                    appContext.getString(R.string.export_keepalive_title),
                                    appContext.getString(R.string.translation_keepalive_message)
                                )
                            }
                        }
                    }
                }
            }
            tasks.awaitAll()
        }

        if (!hasFailures.get() && ui.isFragmentActive()) {
            val path = if (exportTreeUri != null) {
                buildExportPathHint(exportTreeUri, collectionName)
            } else {
                "/Documents/manga-translate/$collectionName"
            }
            withContext(Dispatchers.Main) {
                dialogs.showExportSuccessDialog(appContext, path)
            }
        }
        return hasFailures.get()
    }

    private suspend fun exportCollectionAsCbz(
        context: Context,
        collectionFolder: File,
        chapterImages: List<Pair<File, List<File>>>,
        verticalLayoutEnabled: Boolean,
        exportThreads: Int,
        exportTreeUri: Uri?
    ): CbzExportResult {
        val preparedEntries = prepareCollectionArchiveEntries(
            context = context,
            chapterImages = chapterImages,
            verticalLayoutEnabled = verticalLayoutEnabled,
            exportThreads = exportThreads
        ) ?: return CbzExportResult(success = false, pathHint = null)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && exportTreeUri != null) {
            exportCbzToDocumentTree(
                context = context,
                folder = collectionFolder,
                exportTreeUri = exportTreeUri,
                preparedEntries = preparedEntries
            )
        } else {
            exportCbzToLegacyStorage(
                folder = collectionFolder,
                preparedEntries = preparedEntries
            )
        }
    }

    private suspend fun exportCollectionAsPdfWithOutlines(
        context: Context,
        collectionFolder: File,
        chapterImages: List<Pair<File, List<File>>>,
        verticalLayoutEnabled: Boolean,
        exportThreads: Int,
        exportTreeUri: Uri?
    ): CbzExportResult {
        val preparedEntries = prepareCollectionArchiveEntries(
            context = context,
            chapterImages = chapterImages,
            verticalLayoutEnabled = verticalLayoutEnabled,
            exportThreads = exportThreads
        ) ?: return CbzExportResult(success = false, pathHint = null)

        val sortedEntries = preparedEntries.sortedBy { it.index }
        val orderedImages = sortedEntries.map { it.tempFile }

        var pageIndex = 0
        val chapterOutlines = chapterImages.map { (chapter, images) ->
            val outline = chapter.name to pageIndex
            pageIndex += images.size
            outline
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && exportTreeUri != null) {
            writeCollectionPdfToDocumentTree(
                context = context,
                folder = collectionFolder,
                exportTreeUri = exportTreeUri,
                chapterOutlines = chapterOutlines,
                orderedImages = orderedImages,
                preparedEntries = preparedEntries
            )
        } else {
            writeCollectionPdfToLegacyStorage(
                folder = collectionFolder,
                chapterOutlines = chapterOutlines,
                orderedImages = orderedImages,
                preparedEntries = preparedEntries
            )
        }
    }

    private suspend fun writeCollectionPdfToDocumentTree(
        context: Context,
        folder: File,
        exportTreeUri: Uri,
        chapterOutlines: List<Pair<String, Int>>,
        orderedImages: List<File>,
        preparedEntries: List<PreparedCbzEntry>
    ): CbzExportResult {
        val root = DocumentFile.fromTreeUri(context, exportTreeUri)
        if (root == null || !root.canWrite()) {
            cleanupPreparedCbzEntries(preparedEntries)
            return CbzExportResult(success = false, pathHint = null)
        }
        val pdfName = resolveUniquePdfName(root, folder.name)
        val target = root.createFile("application/pdf", pdfName)
            ?: run {
                cleanupPreparedCbzEntries(preparedEntries)
                return CbzExportResult(success = false, pathHint = null)
            }
        val pathHint = "${buildExportRootPathHint(exportTreeUri)}/$pdfName"

        return try {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(target.uri, "wt")?.use { stream ->
                    if (!PdfImageCodec.writeImagesToPdfWithOutlines(chapterOutlines, orderedImages, stream)) {
                        return@withContext CbzExportResult(false, null)
                    }
                } ?: return@withContext CbzExportResult(false, null)
                CbzExportResult(success = true, pathHint = pathHint)
            }
        } catch (e: Exception) {
            AppLogger.log("Library", "Export PDF failed: ${folder.name}", e)
            runCatching { target.delete() }
            CbzExportResult(success = false, pathHint = null)
        } finally {
            cleanupPreparedCbzEntries(preparedEntries)
        }
    }

    private suspend fun writeCollectionPdfToLegacyStorage(
        folder: File,
        chapterOutlines: List<Pair<String, Int>>,
        orderedImages: List<File>,
        preparedEntries: List<PreparedCbzEntry>
    ): CbzExportResult {
        val root = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "manga-translate"
        )
        if (!root.exists() && !root.mkdirs()) {
            cleanupPreparedCbzEntries(preparedEntries)
            return CbzExportResult(success = false, pathHint = null)
        }
        val target = resolveUniqueFile(root, "${folder.name}.pdf")
        val pathHint = "/Documents/manga-translate/${target.name}"

        return try {
            withContext(Dispatchers.IO) {
                FileOutputStream(target).use { stream ->
                    if (!PdfImageCodec.writeImagesToPdfWithOutlines(chapterOutlines, orderedImages, stream)) {
                        return@withContext CbzExportResult(false, null)
                    }
                }
                CbzExportResult(success = true, pathHint = pathHint)
            }
        } catch (e: Exception) {
            AppLogger.log("Library", "Export PDF failed: ${folder.name}", e)
            runCatching { target.delete() }
            CbzExportResult(success = false, pathHint = null)
        } finally {
            cleanupPreparedCbzEntries(preparedEntries)
        }
    }

    private suspend fun prepareCollectionArchiveEntries(
        context: Context,
        chapterImages: List<Pair<File, List<File>>>,
        verticalLayoutEnabled: Boolean,
        exportThreads: Int
    ): List<PreparedCbzEntry>? {
        val allImages = chapterImages.flatMap { (chapter, images) ->
            images.map { chapter to it }
        }
        if (allImages.isEmpty()) return null

        val tempDir = File(context.cacheDir, "cbz_export_${System.currentTimeMillis()}_collection")
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            AppLogger.log("Library", "Create CBZ temp directory failed: ${tempDir.absolutePath}")
            return null
        }

        val semaphore = Semaphore(normalizeExportThreads(exportThreads))
        val renderedCount = AtomicInteger(0)
        val hasFailures = AtomicBoolean(false)
        val totalImages = allImages.size
        val entries = MutableList<PreparedCbzEntry?>(totalImages) { null }

        return try {
            coroutineScope {
                val tasks = allImages.mapIndexed { index, (chapter, image) ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val prepared = renderImageToTempFileWithPrefix(
                                context = context,
                                imageFile = image,
                                prefix = chapter.name,
                                verticalLayoutEnabled = verticalLayoutEnabled,
                                tempDir = tempDir,
                                index = index
                            )
                            if (prepared == null) {
                                hasFailures.set(true)
                            } else {
                                entries[index] = prepared
                            }
                            val count = renderedCount.incrementAndGet()
                            withContext(Dispatchers.Main) {
                                ui.setFolderStatus(
                                    appContext.getString(R.string.exporting_progress, count, totalImages)
                                )
                                TranslationKeepAliveService.updateProgress(
                                    appContext,
                                    count,
                                    totalImages,
                                    appContext.getString(R.string.exporting_progress, count, totalImages),
                                    appContext.getString(R.string.export_keepalive_title),
                                    appContext.getString(R.string.translation_keepalive_message)
                                )
                            }
                        }
                    }
                }
                tasks.awaitAll()
            }
            if (hasFailures.get() || entries.any { it == null }) {
                null
            } else {
                entries.filterNotNull()
            }
        } finally {
            if (hasFailures.get() || entries.any { it == null }) {
                runCatching { tempDir.deleteRecursively() }
            }
        }
    }

    private fun renderImageToTempFileWithPrefix(
        context: Context,
        imageFile: File,
        prefix: String,
        verticalLayoutEnabled: Boolean,
        tempDir: File,
        index: Int
    ): PreparedCbzEntry? {
        val renderer = BubbleRenderer(context)
        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return null
        val translation = translationStore.load(imageFile)
        val output = if (translation != null && translation.bubbles.any { it.text.isNotBlank() }) {
            renderer.render(bitmap, translation, verticalLayoutEnabled)
        } else {
            bitmap
        }
        val spec = resolveExportSpec(imageFile.name)
        val entryName = "$prefix/${spec.displayName}"
        val tempFile = File(tempDir, "entry_$index")
        val success = try {
            FileOutputStream(tempFile).use { outputStream ->
                output.compress(spec.format, spec.quality, outputStream)
            }
        } catch (e: Exception) {
            AppLogger.log("Library", "Write CBZ entry failed: ${imageFile.name}", e)
            false
        } finally {
            if (output !== bitmap) {
                output.recycle()
            }
            bitmap.recycle()
        }
        if (!success) return null
        return PreparedCbzEntry(index = index, entryName = entryName, tempFile = tempFile)
    }

    private fun resolveExportDirectory(
        context: Context,
        treeUri: Uri,
        folderName: String
    ): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        if (!root.canWrite()) {
            return null
        }
        val existing = root.findFile(folderName)
        return when {
            existing == null -> root.createDirectory(folderName)
            existing.isDirectory -> existing
            else -> null
        }
    }

    private fun buildExportPathHint(treeUri: Uri, folderName: String): String {
        val base = buildExportRootPathHint(treeUri)
        return "$base/$folderName"
    }

    private fun buildExportRootPathHint(treeUri: Uri): String {
        val docId = try {
            android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: Exception) {
            null
        }
        val base = docId?.let { id ->
            if (id.startsWith("primary:")) {
                "/storage/emulated/0/${id.removePrefix("primary:")}"
            } else {
                id
            }
        } ?: "所选目录"
        return base
    }

    private fun isImageDocument(file: DocumentFile): Boolean {
        val name = file.name.orEmpty()
        return ImageFileSupport.isSupportedSourceImageFileName(name)
    }

    private fun ensureNoMediaFile(context: Context, folderName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val relativePath = "Documents/manga-translate/$folderName/"
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?"
            val selectionArgs = arrayOf(relativePath, ".nomedia")
            val exists = resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                selection,
                selectionArgs,
                null
            )?.use { it.moveToFirst() } == true
            if (exists) {
                return
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, ".nomedia")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: return
            try {
                resolver.openOutputStream(uri)?.use { }
            } catch (e: Exception) {
                AppLogger.log("Library", "Create .nomedia failed: $relativePath", e)
                resolver.delete(uri, null, null)
                return
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val exportDir = File(root, "manga-translate/$folderName")
            if (!exportDir.exists() && !exportDir.mkdirs()) {
                return
            }
            val noMedia = File(exportDir, ".nomedia")
            if (!noMedia.exists()) {
                try {
                    noMedia.createNewFile()
                } catch (e: Exception) {
                    AppLogger.log("Library", "Create .nomedia failed: ${noMedia.absolutePath}", e)
                }
            }
        }
    }

    private fun ensureNoMediaFile(exportDir: DocumentFile) {
        if (exportDir.findFile(".nomedia") != null) return
        runCatching {
            exportDir.createFile("application/octet-stream", ".nomedia")
        }.onFailure { e ->
            AppLogger.log("Library", "Create .nomedia failed: ${exportDir.uri}", e)
        }
    }

    private fun exportImageWithBubbles(
        context: Context,
        renderer: BubbleRenderer,
        imageFile: File,
        folderName: String,
        verticalLayoutEnabled: Boolean,
        exportDir: DocumentFile?
    ): Boolean {
        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return false
        val translation = translationStore.load(imageFile)
        val output = if (translation != null && translation.bubbles.any { it.text.isNotBlank() }) {
            renderer.render(bitmap, translation, verticalLayoutEnabled)
        } else {
            bitmap
        }
        val spec = resolveExportSpec(imageFile.name)
        val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && exportDir != null) {
            saveBitmapToDocumentFile(context, output, spec, exportDir)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveBitmapToMediaStore(context, output, spec, folderName)
        } else {
            saveBitmapToLegacyStorage(output, spec, folderName)
        }
        if (output !== bitmap) {
            output.recycle()
        }
        bitmap.recycle()
        if (!success) {
            AppLogger.log("Library", "Export failed for ${imageFile.name}")
        }
        return success
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveBitmapToMediaStore(
        context: Context,
        bitmap: Bitmap,
        spec: ExportSpec,
        folderName: String
    ): Boolean {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val relativePathWithSlash = "Documents/manga-translate/$folderName/"
        val relativePathNoSlash = "Documents/manga-translate/$folderName"
        val selection =
            "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?) AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?"
        val selectionArgs = arrayOf(relativePathWithSlash, relativePathNoSlash, spec.displayName)
        val existingUri = resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                ContentUris.withAppendedId(collection, id)
            } else {
                null
            }
        }
        val values = ContentValues()
        val (uri, createdNew) = if (existingUri != null) {
            values.put(MediaStore.MediaColumns.IS_PENDING, 1)
            resolver.update(existingUri, values, null, null)
            existingUri to false
        } else {
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, spec.displayName)
            values.put(MediaStore.MediaColumns.MIME_TYPE, spec.mimeType)
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePathWithSlash)
            values.put(MediaStore.MediaColumns.IS_PENDING, 1)
            (resolver.insert(collection, values) ?: return false) to true
        }
        val success = try {
            resolver.openOutputStream(uri)?.use { output ->
                bitmap.compress(spec.format, spec.quality, output)
            } ?: false
        } catch (e: Exception) {
            AppLogger.log("Library", "Export write failed: ${spec.displayName}", e)
            false
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        if (!success && createdNew) {
            resolver.delete(uri, null, null)
        }
        return success
    }

    private fun saveBitmapToDocumentFile(
        context: Context,
        bitmap: Bitmap,
        spec: ExportSpec,
        exportDir: DocumentFile
    ): Boolean {
        val resolver = context.contentResolver
        val existing = exportDir.findFile(spec.displayName)
        val target = if (existing != null && existing.isFile) {
            existing
        } else {
            exportDir.createFile(spec.mimeType, spec.displayName)
        } ?: return false
        return try {
            resolver.openOutputStream(target.uri, "wt")?.use { output ->
                bitmap.compress(spec.format, spec.quality, output)
            } ?: false
        } catch (e: Exception) {
            AppLogger.log("Library", "Export write failed: ${spec.displayName}", e)
            false
        }
    }

    private fun saveBitmapToLegacyStorage(
        bitmap: Bitmap,
        spec: ExportSpec,
        folderName: String
    ): Boolean {
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val exportDir = File(root, "manga-translate/$folderName")
        if (!exportDir.exists() && !exportDir.mkdirs()) {
            AppLogger.log("Library", "Export directory create failed: ${exportDir.absolutePath}")
            return false
        }
        val target = resolveUniqueFile(exportDir, spec.displayName)
        return try {
            FileOutputStream(target).use { output ->
                bitmap.compress(spec.format, spec.quality, output)
            }
        } catch (e: Exception) {
            AppLogger.log("Library", "Export write failed: ${target.name}", e)
            false
        }
    }

    private fun resolveExportSpec(fileName: String): ExportSpec {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val format = when (ext) {
            "png" -> Bitmap.CompressFormat.PNG
            "webp" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSLESS
            } else {
                Bitmap.CompressFormat.WEBP
            }
            "avif" -> Bitmap.CompressFormat.PNG
            "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG
            else -> Bitmap.CompressFormat.JPEG
        }
        val mimeType = when (ext) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "avif" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            else -> "image/jpeg"
        }
        val displayName = when (ext) {
            "png", "webp", "jpg", "jpeg" -> fileName
            "avif" -> ImageFileSupport.resolveRenderedOutputName(fileName)
            else -> {
                val baseName = fileName.substringBeforeLast('.', fileName)
                "$baseName.jpg"
            }
        }
        val quality = when (format) {
            Bitmap.CompressFormat.PNG -> 100
            else -> 95
        }
        return ExportSpec(displayName, mimeType, format, quality)
    }

    private fun resolveUniqueFile(folder: File, fileName: String): File {
        val base = fileName.substringBeforeLast('.', fileName)
        val ext = fileName.substringAfterLast('.', "")
        var candidate = File(folder, fileName)
        var index = 1
        while (candidate.exists()) {
            val suffix = if (ext.isEmpty()) "" else ".$ext"
            candidate = File(folder, "${base}_$index$suffix")
            index += 1
        }
        return candidate
    }

    private data class ExportSpec(
        val displayName: String,
        val mimeType: String,
        val format: Bitmap.CompressFormat,
        val quality: Int
    )

    private data class CbzExportResult(
        val success: Boolean,
        val pathHint: String?
    )

    enum class ExportFormat {
        IMAGE_DIR,
        CBZ,
        PDF
    }

    private data class PreparedCbzEntry(
        val index: Int,
        val entryName: String,
        val tempFile: File
    )

    private suspend fun exportCbzWithBubbles(
        context: Context,
        folder: File,
        images: List<File>,
        verticalLayoutEnabled: Boolean,
        exportThreads: Int,
        exportTreeUri: Uri?,
        onProgress: suspend (Int) -> Unit
    ): CbzExportResult {
        val preparedEntries = prepareCbzEntries(
            context = context,
            folderName = folder.name,
            images = images,
            verticalLayoutEnabled = verticalLayoutEnabled,
            exportThreads = exportThreads,
            onProgress = onProgress
        ) ?: return CbzExportResult(success = false, pathHint = null)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && exportTreeUri != null) {
            exportCbzToDocumentTree(
                context = context,
                folder = folder,
                exportTreeUri = exportTreeUri,
                preparedEntries = preparedEntries
            )
        } else {
            exportCbzToLegacyStorage(
                folder = folder,
                preparedEntries = preparedEntries
            )
        }
    }

    private suspend fun exportPdfWithBubbles(
        context: Context,
        folder: File,
        images: List<File>,
        verticalLayoutEnabled: Boolean,
        exportThreads: Int,
        exportTreeUri: Uri?,
        onProgress: suspend (Int) -> Unit
    ): CbzExportResult {
        val preparedEntries = prepareCbzEntries(
            context = context,
            folderName = folder.name,
            images = images,
            verticalLayoutEnabled = verticalLayoutEnabled,
            exportThreads = exportThreads,
            onProgress = onProgress
        ) ?: return CbzExportResult(success = false, pathHint = null)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && exportTreeUri != null) {
            exportPdfToDocumentTree(
                context = context,
                folder = folder,
                exportTreeUri = exportTreeUri,
                preparedEntries = preparedEntries
            )
        } else {
            exportPdfToLegacyStorage(
                folder = folder,
                preparedEntries = preparedEntries
            )
        }
    }

    private suspend fun exportCbzToDocumentTree(
        context: Context,
        folder: File,
        exportTreeUri: Uri,
        preparedEntries: List<PreparedCbzEntry>
    ): CbzExportResult {
        val root = DocumentFile.fromTreeUri(context, exportTreeUri)
        if (root == null || !root.canWrite()) {
            cleanupPreparedCbzEntries(preparedEntries)
            return CbzExportResult(success = false, pathHint = null)
        }
        val cbzName = resolveUniqueCbzName(root, folder.name)
        val target = root.createFile("application/vnd.comicbook+zip", cbzName)
            ?: run {
                cleanupPreparedCbzEntries(preparedEntries)
                return CbzExportResult(success = false, pathHint = null)
            }
        val pathHint = "${buildExportRootPathHint(exportTreeUri)}/$cbzName"

        return try {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(target.uri, "wt")?.use { stream ->
                    ZipOutputStream(BufferedOutputStream(stream)).use { zip ->
                        for (entry in preparedEntries.sortedBy { it.index }) {
                            zip.putNextEntry(ZipEntry(entry.entryName))
                            FileInputStream(entry.tempFile).use { input ->
                                input.copyTo(zip)
                            }
                            zip.closeEntry()
                        }
                    }
                } ?: return@withContext CbzExportResult(false, null)
                CbzExportResult(success = true, pathHint = pathHint)
            }
        } catch (e: Exception) {
            AppLogger.log("Library", "Export CBZ failed: ${folder.name}", e)
            runCatching { target.delete() }
            CbzExportResult(success = false, pathHint = null)
        } finally {
            cleanupPreparedCbzEntries(preparedEntries)
        }
    }

    private suspend fun exportCbzToLegacyStorage(
        folder: File,
        preparedEntries: List<PreparedCbzEntry>
    ): CbzExportResult {
        val root = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "manga-translate"
        )
        if (!root.exists() && !root.mkdirs()) {
            cleanupPreparedCbzEntries(preparedEntries)
            return CbzExportResult(success = false, pathHint = null)
        }
        val target = resolveUniqueFile(root, "${folder.name}.cbz")
        val pathHint = "/Documents/manga-translate/${target.name}"

        return try {
            withContext(Dispatchers.IO) {
                FileOutputStream(target).use { stream ->
                    ZipOutputStream(BufferedOutputStream(stream)).use { zip ->
                        for (entry in preparedEntries.sortedBy { it.index }) {
                            zip.putNextEntry(ZipEntry(entry.entryName))
                            FileInputStream(entry.tempFile).use { input ->
                                input.copyTo(zip)
                            }
                            zip.closeEntry()
                        }
                    }
                }
                CbzExportResult(success = true, pathHint = pathHint)
            }
        } catch (e: Exception) {
            AppLogger.log("Library", "Export CBZ failed: ${folder.name}", e)
            runCatching { target.delete() }
            CbzExportResult(success = false, pathHint = null)
        } finally {
            cleanupPreparedCbzEntries(preparedEntries)
        }
    }

    private suspend fun exportPdfToDocumentTree(
        context: Context,
        folder: File,
        exportTreeUri: Uri,
        preparedEntries: List<PreparedCbzEntry>
    ): CbzExportResult {
        val root = DocumentFile.fromTreeUri(context, exportTreeUri)
        if (root == null || !root.canWrite()) {
            cleanupPreparedCbzEntries(preparedEntries)
            return CbzExportResult(success = false, pathHint = null)
        }
        val pdfName = resolveUniquePdfName(root, folder.name)
        val target = root.createFile("application/pdf", pdfName)
            ?: run {
                cleanupPreparedCbzEntries(preparedEntries)
                return CbzExportResult(success = false, pathHint = null)
            }
        val pathHint = "${buildExportRootPathHint(exportTreeUri)}/$pdfName"

        return try {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(target.uri, "wt")?.use { stream ->
                    val orderedImages = preparedEntries.sortedBy { it.index }.map { it.tempFile }
                    if (!PdfImageCodec.writeImagesToPdf(orderedImages, stream)) {
                        return@withContext CbzExportResult(false, null)
                    }
                } ?: return@withContext CbzExportResult(false, null)
                CbzExportResult(success = true, pathHint = pathHint)
            }
        } catch (e: Exception) {
            AppLogger.log("Library", "Export PDF failed: ${folder.name}", e)
            runCatching { target.delete() }
            CbzExportResult(success = false, pathHint = null)
        } finally {
            cleanupPreparedCbzEntries(preparedEntries)
        }
    }

    private suspend fun exportPdfToLegacyStorage(
        folder: File,
        preparedEntries: List<PreparedCbzEntry>
    ): CbzExportResult {
        val root = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "manga-translate"
        )
        if (!root.exists() && !root.mkdirs()) {
            cleanupPreparedCbzEntries(preparedEntries)
            return CbzExportResult(success = false, pathHint = null)
        }
        val target = resolveUniqueFile(root, "${folder.name}.pdf")
        val pathHint = "/Documents/manga-translate/${target.name}"

        return try {
            withContext(Dispatchers.IO) {
                FileOutputStream(target).use { stream ->
                    val orderedImages = preparedEntries.sortedBy { it.index }.map { it.tempFile }
                    if (!PdfImageCodec.writeImagesToPdf(orderedImages, stream)) {
                        return@withContext CbzExportResult(false, null)
                    }
                }
                CbzExportResult(success = true, pathHint = pathHint)
            }
        } catch (e: Exception) {
            AppLogger.log("Library", "Export PDF failed: ${folder.name}", e)
            runCatching { target.delete() }
            CbzExportResult(success = false, pathHint = null)
        } finally {
            cleanupPreparedCbzEntries(preparedEntries)
        }
    }

    private suspend fun prepareCbzEntries(
        context: Context,
        folderName: String,
        images: List<File>,
        verticalLayoutEnabled: Boolean,
        exportThreads: Int,
        onProgress: suspend (Int) -> Unit
    ): List<PreparedCbzEntry>? {
        val tempDir = File(context.cacheDir, "cbz_export_${System.currentTimeMillis()}_$folderName")
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            AppLogger.log("Library", "Create CBZ temp directory failed: ${tempDir.absolutePath}")
            return null
        }

        val semaphore = Semaphore(normalizeExportThreads(exportThreads))
        val renderedCount = AtomicInteger(0)
        val hasFailures = AtomicBoolean(false)
        val entries = MutableList<PreparedCbzEntry?>(images.size) { null }

        return try {
            coroutineScope {
                val tasks = images.mapIndexed { index, image ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val prepared = renderImageToTempFile(
                                context = context,
                                imageFile = image,
                                verticalLayoutEnabled = verticalLayoutEnabled,
                                tempDir = tempDir,
                                index = index
                            )
                            if (prepared == null) {
                                hasFailures.set(true)
                            } else {
                                entries[index] = prepared
                            }
                            onProgress(renderedCount.incrementAndGet())
                        }
                    }
                }
                tasks.awaitAll()
            }
            if (hasFailures.get() || entries.any { it == null }) {
                null
            } else {
                entries.filterNotNull()
            }
        } finally {
            if (hasFailures.get() || entries.any { it == null }) {
                runCatching { tempDir.deleteRecursively() }
            }
        }
    }

    private fun renderImageToTempFile(
        context: Context,
        imageFile: File,
        verticalLayoutEnabled: Boolean,
        tempDir: File,
        index: Int
    ): PreparedCbzEntry? {
        val renderer = BubbleRenderer(context)
        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return null
        val translation = translationStore.load(imageFile)
        val output = if (translation != null && translation.bubbles.any { it.text.isNotBlank() }) {
            renderer.render(bitmap, translation, verticalLayoutEnabled)
        } else {
            bitmap
        }
        val spec = resolveExportSpec(imageFile.name)
        val tempFile = File(tempDir, "entry_$index")
        val success = try {
            FileOutputStream(tempFile).use { outputStream ->
                output.compress(spec.format, spec.quality, outputStream)
            }
        } catch (e: Exception) {
            AppLogger.log("Library", "Write CBZ entry failed: ${imageFile.name}", e)
            false
        } finally {
            if (output !== bitmap) {
                output.recycle()
            }
            bitmap.recycle()
        }
        if (!success) return null
        return PreparedCbzEntry(index = index, entryName = spec.displayName, tempFile = tempFile)
    }

    private fun cleanupPreparedCbzEntries(entries: List<PreparedCbzEntry>) {
        val tempDir = entries.firstOrNull()?.tempFile?.parentFile ?: return
        runCatching { tempDir.deleteRecursively() }
    }

    private fun resolveUniqueCbzName(root: DocumentFile, folderName: String): String {
        var index = 0
        while (true) {
            val fileName = if (index == 0) "$folderName.cbz" else "${folderName}_$index.cbz"
            val existing = root.findFile(fileName)
            if (existing == null) {
                return fileName
            }
            index += 1
        }
    }

    private fun resolveUniquePdfName(root: DocumentFile, folderName: String): String {
        var index = 0
        while (true) {
            val fileName = if (index == 0) "$folderName.pdf" else "${folderName}_$index.pdf"
            val existing = root.findFile(fileName)
            if (existing == null) {
                return fileName
            }
            index += 1
        }
    }

    private fun loadExportThreads(): Int {
        val saved = prefsRef.getInt(KEY_EXPORT_THREADS, DEFAULT_EXPORT_THREADS)
        return normalizeExportThreads(saved)
    }

    private fun loadExportFormatDefault(): ExportFormat {
        val saved = prefsRef.getString(KEY_EXPORT_FORMAT, null)
        if (!saved.isNullOrBlank()) {
            return runCatching { ExportFormat.valueOf(saved) }.getOrDefault(ExportFormat.IMAGE_DIR)
        }
        return if (prefsRef.getBoolean(KEY_EXPORT_AS_CBZ, false)) {
            ExportFormat.CBZ
        } else {
            ExportFormat.IMAGE_DIR
        }
    }

    private fun normalizeExportThreads(value: Int): Int {
        return value.coerceIn(MIN_EXPORT_THREADS, MAX_EXPORT_THREADS)
    }

    companion object {
        private const val KEY_EXPORT_THREADS = "export_threads"
        private const val KEY_EXPORT_AS_CBZ = "export_as_cbz"
        private const val KEY_EXPORT_FORMAT = "export_format"
        private const val DEFAULT_EXPORT_THREADS = 2
        private const val MIN_EXPORT_THREADS = 1
        private const val MAX_EXPORT_THREADS = 16
    }
}
