package com.manga.translate

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.FileHeader

class LibraryRepository(private val context: Context) {
    private val rootDir: File = File(
        context.getExternalFilesDir(null) ?: context.filesDir,
        "manga_library"
    )

    init {
        if (!rootDir.exists()) {
            rootDir.mkdirs()
        }
    }

    fun listFolders(): List<File> {
        val folders = rootDir.listFiles { file -> file.isDirectory }?.toList().orEmpty()
        return folders.sortedBy { it.name.lowercase(Locale.getDefault()) }
    }

    fun listChildFolders(folder: File): List<File> {
        val folders = folder.listFiles { file -> file.isDirectory && !file.name.startsWith(".") }
            ?.toList()
            .orEmpty()
        return folders.sortedBy { it.name.lowercase(Locale.getDefault()) }
    }

    fun isCollectionFolder(folder: File): Boolean {
        if (collectionMarkerFile(folder).exists()) return true
        return listImages(folder).isEmpty() && listChildFolders(folder).isNotEmpty()
    }

    fun createFolder(name: String): File? {
        val trimmed = sanitizeFolderName(name) ?: return null
        val folder = File(rootDir, trimmed)
        if (folder.exists()) return null
        return if (folder.mkdirs()) folder else null
    }

    fun createCollection(name: String): File? {
        val folder = createFolder(name) ?: return null
        return if (runCatching { collectionMarkerFile(folder).writeText("1") }.isSuccess) {
            folder
        } else {
            folder.deleteRecursively()
            null
        }
    }

    fun createChildFolder(parent: File, name: String): File? {
        if (!parent.exists() || !parent.isDirectory) return null
        val trimmed = sanitizeFolderName(name) ?: return null
        val folder = File(parent, trimmed)
        if (folder.exists()) return null
        return if (folder.mkdirs()) folder else null
    }

    fun listImages(folder: File): List<File> {
        val images = folder.listFiles { file ->
            file.isFile && isImageFile(file.name)
        }?.toList().orEmpty()
        return images.sortedWith { first, second ->
            compareFileNamesNaturally(first.name, second.name)
        }
    }

    fun addImages(folder: File, uris: List<Uri>): List<File> {
        if (isCollectionFolder(folder)) {
            AppLogger.log("LibraryRepo", "Reject adding images into collection ${folder.name}")
            return emptyList()
        }
        val added = ArrayList<File>()
        for (uri in uris) {
            val fileName = queryDisplayName(uri) ?: "image_${System.currentTimeMillis()}.jpg"
            val dest = resolveUniqueFile(folder, fileName)
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
                added.add(dest)
            } catch (e: Exception) {
                AppLogger.log("LibraryRepo", "Failed to copy $fileName", e)
            }
        }
        return added
    }

    fun importCbz(uri: Uri): CbzImportResult? {
        val archiveName = queryDisplayName(uri) ?: "cbz_import_${System.currentTimeMillis()}.cbz"
        val folderName = archiveName.substringBeforeLast('.', archiveName).trim().ifEmpty { "cbz_import" }
        val folder = createUniqueFolder(folderName) ?: return null

        val archiveExt = archiveName.substringAfterLast('.', "").lowercase(Locale.US)
        val tempSuffix = if (archiveExt == "zip") ".zip" else ".cbz"
        val tempFile = File(context.cacheDir, "temp_cbz_${System.currentTimeMillis()}$tempSuffix")
        var importedCount = 0
        
        try {
            AppLogger.log("LibraryRepo", "Archive import started: $archiveName")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                AppLogger.log("LibraryRepo", "Archive import failed: cannot open input stream")
                folder.deleteRecursively()
                return null
            }
            
            AppLogger.log("LibraryRepo", "Archive copied to temp file: ${tempFile.length()} bytes")

            ZipFile(tempFile).use { zipFile ->
                val headers = zipFile.fileHeaders.orEmpty()
                AppLogger.log("LibraryRepo", "Archive total entries: ${headers.size}")

                for (header in headers) {
                    if (header.isDirectory) continue

                    val entryName = extractImportImageName(header.fileName)
                    AppLogger.log(
                        "LibraryRepo",
                        "Archive entry: ${header.fileName} -> ${entryName ?: "(skipped)"}"
                    )

                    if (entryName == null) continue

                    try {
                        val dest = resolveUniqueFile(folder, entryName)
                        zipFile.getInputStream(header).use { input ->
                            FileOutputStream(dest).use { output ->
                                input.copyTo(output)
                            }
                        }
                        importedCount += 1
                        AppLogger.log(
                            "LibraryRepo",
                            "Archive imported: $entryName (${dest.length()} bytes)"
                        )
                    } catch (e: Exception) {
                        logArchiveEntryImportFailure(header, e)
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.log("LibraryRepo", "Archive import failed: $archiveName", e)
            folder.deleteRecursively()
            return null
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }

        AppLogger.log("LibraryRepo", "Archive import completed: $importedCount images")
        if (importedCount == 0) {
            folder.deleteRecursively()
            return CbzImportResult(folder = null, importedCount = 0)
        }
        return CbzImportResult(folder = folder, importedCount = importedCount)
    }

    fun importPdf(uri: Uri): CbzImportResult? {
        val pdfName = queryDisplayName(uri) ?: "pdf_import_${System.currentTimeMillis()}.pdf"
        val folderName = pdfName.substringBeforeLast('.', pdfName).trim().ifEmpty { "pdf_import" }
        val folder = createUniqueFolder(folderName) ?: return null
        return try {
            val importedCount = PdfImageCodec.renderPdfToImages(
                contentResolver = context.contentResolver,
                uri = uri,
                outputDir = folder
            )
            if (importedCount <= 0) {
                folder.deleteRecursively()
                CbzImportResult(folder = null, importedCount = 0)
            } else {
                CbzImportResult(folder = folder, importedCount = importedCount)
            }
        } catch (e: Exception) {
            AppLogger.log("LibraryRepo", "PDF import failed: $pdfName", e)
            folder.deleteRecursively()
            null
        }
    }

    fun deleteFolder(folder: File): Boolean {
        if (!folder.exists()) return false
        return folder.deleteRecursively()
    }

    fun renameFolder(folder: File, newName: String): File? {
        if (!folder.exists() || !folder.isDirectory) return null
        val trimmed = sanitizeFolderName(newName) ?: return null
        if (trimmed == folder.name) return folder
        val target = File(folder.parentFile, trimmed)
        if (target.exists()) return null
        return if (folder.renameTo(target)) target else null
    }

    fun moveFolderToCollection(folder: File, collection: File): File? {
        if (!folder.exists() || !folder.isDirectory) return null
        if (!collection.exists() || !collection.isDirectory) return null
        if (!isCollectionFolder(collection)) return null
        if (isCollectionFolder(folder)) return null
        val currentParent = folder.parentFile ?: return null
        if (currentParent.absolutePath == collection.absolutePath) return folder
        if (folder.absolutePath == collection.absolutePath) return null
        val target = File(collection, folder.name)
        if (target.exists()) return null
        return if (folder.renameTo(target)) target else null
    }

    fun resolveSettingsFolder(folder: File): File {
        val parent = folder.parentFile
        return if (
            parent != null &&
            parent.exists() &&
            parent.isDirectory &&
            parent.absolutePath != rootDir.absolutePath &&
            isCollectionFolder(parent)
        ) {
            parent
        } else {
            folder
        }
    }

    private fun isImageFile(name: String): Boolean {
        return ImageFileSupport.isSupportedSourceImageFileName(name)
    }

    private fun resolveUniqueFile(folder: File, fileName: String): File {
        val base = fileName.substringBeforeLast('.')
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

    private fun createUniqueFolder(baseName: String): File? {
        val sanitized = sanitizeFolderName(baseName) ?: return null
        var index = 0
        while (true) {
            val candidateName = if (index == 0) sanitized else "${sanitized}_$index"
            val folder = File(rootDir, candidateName)
            if (!folder.exists()) {
                return if (folder.mkdirs()) folder else null
            }
            index += 1
        }
    }

    private fun collectionMarkerFile(folder: File): File {
        return File(folder, COLLECTION_MARKER_FILE_NAME)
    }

    private fun sanitizeFolderName(name: String): String? {
        val trimmed = name.trim().replace("/", "_").replace("\\", "_")
        return trimmed.takeIf { it.isNotEmpty() && !it.contains("..") }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    cursor.getString(index)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    data class CbzImportResult(
        val folder: File?,
        val importedCount: Int
    )

    companion object {
        private const val COLLECTION_MARKER_FILE_NAME = ".folder-collection"
        private val CONTROL_CHARS_REGEX = Regex("[\\u0000-\\u001F]")

        internal fun extractImportImageName(entryName: String?): String? {
            val normalized = entryName
                ?.replace('\\', '/')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return null
            val fileName = normalized.substringAfterLast('/').trim()
            if (fileName.isEmpty() || fileName == "." || fileName == "..") {
                return null
            }
            val sanitized = fileName.replace(CONTROL_CHARS_REGEX, "_")
            return if (ImageFileSupport.isSupportedSourceImageFileName(sanitized)) {
                sanitized
            } else {
                null
            }
        }

        internal fun compareFileNamesNaturally(first: String, second: String): Int {
            var firstIndex = 0
            var secondIndex = 0

            while (firstIndex < first.length && secondIndex < second.length) {
                val firstChar = first[firstIndex]
                val secondChar = second[secondIndex]
                val firstIsDigit = firstChar.isDigit()
                val secondIsDigit = secondChar.isDigit()

                if (firstIsDigit && secondIsDigit) {
                    val firstEnd = first.consumeDigits(firstIndex)
                    val secondEnd = second.consumeDigits(secondIndex)
                    val numberComparison = compareNumericChunks(
                        first.substring(firstIndex, firstEnd),
                        second.substring(secondIndex, secondEnd)
                    )
                    if (numberComparison != 0) return numberComparison
                    firstIndex = firstEnd
                    secondIndex = secondEnd
                    continue
                }

                val charComparison = firstChar.lowercaseChar().compareTo(secondChar.lowercaseChar())
                if (charComparison != 0) return charComparison

                firstIndex += 1
                secondIndex += 1
            }

            return first.length.compareTo(second.length)
        }

        private fun String.consumeDigits(startIndex: Int): Int {
            var index = startIndex
            while (index < length && this[index].isDigit()) {
                index += 1
            }
            return index
        }

        private fun compareNumericChunks(first: String, second: String): Int {
            val normalizedFirst = first.trimStart('0').ifEmpty { "0" }
            val normalizedSecond = second.trimStart('0').ifEmpty { "0" }

            val lengthComparison = normalizedFirst.length.compareTo(normalizedSecond.length)
            if (lengthComparison != 0) return lengthComparison

            val valueComparison = normalizedFirst.compareTo(normalizedSecond)
            if (valueComparison != 0) return valueComparison

            return first.length.compareTo(second.length)
        }
    }

    private fun logArchiveEntryImportFailure(header: FileHeader, error: Exception) {
        AppLogger.log("LibraryRepo", "Archive entry import failed: ${header.fileName}", error)
    }
}
