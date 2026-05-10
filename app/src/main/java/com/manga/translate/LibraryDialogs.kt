package com.manga.translate

import android.content.Context
import android.content.res.ColorStateList
import android.text.InputType
import android.util.TypedValue
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import androidx.documentfile.provider.DocumentFile
import java.util.Locale

internal class LibraryDialogs {
    private fun formatInt(value: Int): String = String.format(Locale.getDefault(), "%d", value)
    private fun dp(context: Context, value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        context.resources.displayMetrics
    ).toInt()

    private fun showMessageDialog(
        context: Context,
        titleRes: Int,
        message: CharSequence,
        positiveRes: Int = android.R.string.ok,
        onPositive: (() -> Unit)? = null
    ) {
        AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setMessage(message)
            .setPositiveButton(positiveRes) { _, _ -> onPositive?.invoke() }
            .showWithScrollableMessage()
    }

    private fun showTextInputDialog(
        context: Context,
        titleRes: Int,
        initialText: String = "",
        trimResult: Boolean = false,
        onConfirm: (String) -> Unit
    ) {
        val input = EditText(context).apply {
            hint = context.getString(R.string.folder_name_hint)
            if (initialText.isNotEmpty()) {
                setText(initialText)
                setSelection(text.length)
            }
        }
        applyDialogTextColors(context, input)
        AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = input.text?.toString().orEmpty()
                onConfirm(if (trimResult) value.trim() else value)
            }
            .show()
    }

    private fun showSingleChoiceDialog(
        context: Context,
        titleRes: Int,
        items: Array<String>,
        checkedIndex: Int,
        onSelected: (Int) -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setSingleChoiceItems(items, checkedIndex) { dialog, which ->
                onSelected(which)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun buildDialogContainer(context: Context): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20f), dp(context, 12f), dp(context, 20f), dp(context, 12f))
        }
    }

    private fun matchWrapLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun resolveColorAttr(context: Context, attrRes: Int): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(attrRes, typedValue, true)
        return if (typedValue.resourceId != 0) {
            ContextCompat.getColor(context, typedValue.resourceId)
        } else {
            typedValue.data
        }
    }

    private fun applyDialogTextColors(
        context: Context,
        textView: TextView,
        useHintColor: Boolean = false
    ) {
        val textColorAttr = if (useHintColor) R.attr.dialogHintTextColor else R.attr.dialogTextColor
        textView.setTextColor(resolveColorAttr(context, textColorAttr))
        TextViewCompat.setCompoundDrawableTintList(
            textView,
            ColorStateList.valueOf(resolveColorAttr(context, R.attr.dialogTextColor))
        )
        if (textView is EditText) {
            textView.setHintTextColor(resolveColorAttr(context, R.attr.dialogHintTextColor))
        }
        if (textView is CheckBox) {
            textView.buttonTintList =
                ColorStateList.valueOf(resolveColorAttr(context, R.attr.dialogTextColor))
        }
    }

    fun showCreateFolderDialog(context: Context, onConfirm: (String) -> Unit) {
        showTextInputDialog(context, R.string.create_folder, onConfirm = onConfirm)
    }

    fun showCreateCollectionDialog(context: Context, onConfirm: (String) -> Unit) {
        showTextInputDialog(context, R.string.create_collection, onConfirm = onConfirm)
    }

    fun showCreateChapterDialog(context: Context, onConfirm: (String) -> Unit) {
        showTextInputDialog(context, R.string.create_chapter, onConfirm = onConfirm)
    }

    fun showCreateEntryDialog(
        context: Context,
        onCreateFolder: () -> Unit,
        onCreateCollection: () -> Unit
    ) {
        val items = arrayOf(
            context.getString(R.string.create_folder),
            context.getString(R.string.create_collection)
        )
        AlertDialog.Builder(context)
            .setTitle(R.string.create_entry_title)
            .setItems(items) { _, which ->
                if (which == 0) onCreateFolder() else onCreateCollection()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun confirmDeleteFolder(context: Context, folderName: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(R.string.folder_delete)
            .setMessage(context.getString(R.string.folder_delete_confirm, folderName))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.folder_delete) { _, _ -> onConfirm() }
            .showWithScrollableMessage()
    }

    fun showRenameFolderDialog(
        context: Context,
        oldName: String,
        onConfirm: (String) -> Unit
    ) {
        showTextInputDialog(context, R.string.folder_rename, initialText = oldName, onConfirm = onConfirm)
    }

    fun showMoveFolderDialog(
        context: Context,
        collections: List<String>,
        onSelected: (Int) -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle(R.string.folder_move_title)
            .setItems(collections.toTypedArray()) { _, which -> onSelected(which) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showFullTranslateInfo(context: Context) {
        showMessageDialog(
            context,
            R.string.folder_full_translate_info_title,
            context.getString(R.string.folder_full_translate_info)
        )
    }

    fun showLanguageSettingDialog(
        context: Context,
        currentLanguage: TranslationLanguage,
        onSelected: (TranslationLanguage) -> Unit
    ) {
        val languages = TranslationLanguage.values()
        val languageNames = languages.map { context.getString(it.displayNameResId) }.toTypedArray()
        val currentIndex = languages.indexOf(currentLanguage)
        showSingleChoiceDialog(context, R.string.folder_language_setting_title, languageNames, currentIndex) {
            onSelected(languages[it])
        }
    }

    fun showFixedLanguageDialog(context: Context) {
        showSingleChoiceDialog(
            context,
            R.string.folder_language_setting_title,
            arrayOf(context.getString(R.string.folder_language_to_zh)),
            0
        ) { }
    }

    fun showFolderReadingModeDialog(
        context: Context,
        currentMode: FolderReadingMode,
        onSelected: (FolderReadingMode) -> Unit
    ) {
        val modes = FolderReadingMode.entries
        val names = modes.map { context.getString(it.labelRes) }.toTypedArray()
        val currentIndex = modes.indexOf(currentMode)
        showSingleChoiceDialog(context, R.string.folder_reading_mode_title, names, currentIndex) {
            onSelected(modes[it])
        }
    }

    fun showApiErrorDialog(context: Context, errorCode: String, detail: String? = null) {
        showMessageDialog(
            context,
            R.string.api_request_failed_title,
            context.getString(
                R.string.api_request_failed_message,
                ErrorDialogFormatter.formatApiErrorMessage(context, errorCode, detail)
            )
        )
    }

    fun showModelErrorDialog(
        context: Context,
        responseContent: String,
        onRetry: (() -> Unit)? = null,
        onSkip: (() -> Unit)? = null,
        onUnresolvedDismiss: (() -> Unit)? = null,
        onDialogDismissed: (() -> Unit)? = null,
        negativeButtonResId: Int = R.string.translation_skip,
        windowType: Int? = null
    ): AlertDialog {
        return com.manga.translate.showModelErrorDialog(
            context = context,
            responseContent = responseContent,
            onRetry = onRetry,
            onSkip = onSkip,
            onUnresolvedDismiss = onUnresolvedDismiss,
            onDialogDismissed = onDialogDismissed,
            negativeButtonResId = negativeButtonResId,
            windowType = windowType
        )
    }

    fun showEhViewerSubfolderPicker(
        context: Context,
        folders: List<DocumentFile>,
        onPicked: (DocumentFile) -> Unit
    ) {
        val unnamed = context.getString(R.string.unnamed_folder)
        val names = folders.map { it.name ?: unnamed }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(R.string.ehviewer_select_folder)
            .setItems(names) { _, index -> onPicked(folders[index]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showDocumentFolderMultiPicker(
        context: Context,
        titleRes: Int,
        folders: List<DocumentFile>,
        onPicked: (List<DocumentFile>) -> Unit
    ) {
        val unnamed = context.getString(R.string.unnamed_folder)
        val names = folders.map { it.name ?: unnamed }.toTypedArray()
        val checked = BooleanArray(folders.size)
        AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val selected = folders.filterIndexed { index, _ -> checked[index] }
                onPicked(selected)
            }
            .show()
    }

    fun showEhViewerImportNameDialog(
        context: Context,
        defaultName: String,
        onConfirm: (String) -> Unit
    ) {
        showTextInputDialog(
            context,
            R.string.ehviewer_import_name_title,
            initialText = defaultName,
            trimResult = true
        ) { name ->
            if (name.isEmpty()) {
                Toast.makeText(context, R.string.folder_create_failed, Toast.LENGTH_SHORT).show()
            } else {
                onConfirm(name)
            }
        }
    }

    fun showExportSuccessDialog(context: Context, path: String) {
        showMessageDialog(
            context,
            R.string.export_success_title,
            context.getString(R.string.export_success_message, path)
        )
    }

    fun showExportOptionsDialog(
        context: Context,
        defaultThreads: Int,
        defaultExportFormat: LibraryImportExportCoordinator.ExportFormat,
        exportRootPathHint: String,
        onConfirm: (Int, LibraryImportExportCoordinator.ExportFormat) -> Unit
    ) {
        val input = EditText(context).apply {
            hint = context.getString(R.string.export_thread_hint)
            setText(formatInt(defaultThreads))
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        applyDialogTextColors(context, input)
        val formatGroup = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
        }
        val imageDirRadio = RadioButton(context).apply {
            id = android.view.View.generateViewId()
            text = context.getString(R.string.export_format_images_option)
        }
        val cbzRadio = RadioButton(context).apply {
            id = android.view.View.generateViewId()
            text = context.getString(R.string.export_format_cbz_option)
        }
        val pdfRadio = RadioButton(context).apply {
            id = android.view.View.generateViewId()
            text = context.getString(R.string.export_format_pdf_option)
        }
        listOf(imageDirRadio, cbzRadio, pdfRadio).forEach { radio ->
            applyDialogTextColors(context, radio)
            formatGroup.addView(radio, matchWrapLayoutParams())
        }
        formatGroup.check(
            when (defaultExportFormat) {
                LibraryImportExportCoordinator.ExportFormat.IMAGE_DIR -> imageDirRadio.id
                LibraryImportExportCoordinator.ExportFormat.CBZ -> cbzRadio.id
                LibraryImportExportCoordinator.ExportFormat.PDF -> pdfRadio.id
            }
        )
        val pathHintView = TextView(context).apply {
            setPadding(0, dp(context, 8f), 0, 0)
            text = context.getString(R.string.export_path_hint_format, exportRootPathHint)
        }
        applyDialogTextColors(context, pathHintView, useHintColor = true)
        val container = buildDialogContainer(context).apply {
            addView(input, matchWrapLayoutParams())
            addView(formatGroup, matchWrapLayoutParams())
            addView(pathHintView, matchWrapLayoutParams())
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.export_options_title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val threadCount = input.text?.toString()?.toIntOrNull()
                if (threadCount == null || threadCount !in 1..16) {
                    Toast.makeText(context, R.string.export_thread_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val exportFormat = when (formatGroup.checkedRadioButtonId) {
                    cbzRadio.id -> LibraryImportExportCoordinator.ExportFormat.CBZ
                    pdfRadio.id -> LibraryImportExportCoordinator.ExportFormat.PDF
                    else -> LibraryImportExportCoordinator.ExportFormat.IMAGE_DIR
                }
                onConfirm(threadCount, exportFormat)
            }
            .show()
    }

    fun confirmDeleteSelectedImages(
        context: Context,
        selectedCount: Int,
        onConfirm: () -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle(R.string.delete_selected)
            .setMessage(context.getString(R.string.delete_images_confirm, selectedCount))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete_selected) { _, _ -> onConfirm() }
            .showWithScrollableMessage()
    }

    fun confirmDeleteSelectedFolders(
        context: Context,
        selectedCount: Int,
        onConfirm: () -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle(R.string.delete_selected)
            .setMessage(context.getString(R.string.delete_chapters_confirm, selectedCount))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete_selected) { _, _ -> onConfirm() }
            .showWithScrollableMessage()
    }

    fun confirmDeleteSelectedLibraryFolders(
        context: Context,
        selectedCount: Int,
        onConfirm: () -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle(R.string.delete_selected)
            .setMessage(context.getString(R.string.delete_folders_confirm, selectedCount))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete_selected) { _, _ -> onConfirm() }
            .showWithScrollableMessage()
    }
}
