package com.manga.translate

import android.content.Context
import android.content.Intent
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.manga.translate.databinding.FragmentLibraryBinding
import com.manga.translate.di.appContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.ArrayDeque

class LibraryFragment : Fragment() {
    private companion object {
        const val MODEL_ERROR_RESHOW_DELAY_MS = 15_000L
    }

    private data class PendingModelErrorDialog(
        val content: String,
        val onRetry: (() -> Unit)?,
        val onSkip: (() -> Unit)?,
        val useSystemOverlay: Boolean
    )

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private val readingSessionViewModel: ReadingSessionViewModel by activityViewModels()
    private val appContainer by lazy(LazyThreadSafetyMode.NONE) { requireContext().appContainer }
    private val repository by lazy(LazyThreadSafetyMode.NONE) { appContainer.libraryRepository }
    private val translationPipeline by lazy(LazyThreadSafetyMode.NONE) {
        appContainer.createTranslationPipeline()
    }
    private val translationStore by lazy(LazyThreadSafetyMode.NONE) { appContainer.translationStore }
    private val glossaryStore by lazy(LazyThreadSafetyMode.NONE) { appContainer.glossaryStore }
    private val extractStateStore by lazy(LazyThreadSafetyMode.NONE) { appContainer.extractStateStore }
    private val ocrStore by lazy(LazyThreadSafetyMode.NONE) { appContainer.ocrStore }
    private val readingProgressStore by lazy(LazyThreadSafetyMode.NONE) { appContainer.readingProgressStore }
    private val settingsStore by lazy(LazyThreadSafetyMode.NONE) { appContainer.settingsStore }
    private val dialogs = LibraryDialogs()

    private val prefs by lazy(LazyThreadSafetyMode.NONE) { appContainer.libraryPrefs }

    private lateinit var preferencesGateway: LibraryPreferencesGateway
    private lateinit var importExportCoordinator: LibraryImportExportCoordinator
    private lateinit var selectionController: LibrarySelectionController

    private var currentFolder: File? = null
    private var currentParentFolder: File? = null
    private var pendingChapterImportParent: File? = null
    private var isFolderTransitionRunning: Boolean = false
    private var isFolderTopBarVisible: Boolean = true
    private var lastFolderDetailScrollY: Int = 0
    private var folderTopBarScrollAccumulated: Int = 0
    private var folderDetailContentBaseTopPadding: Int = 0
    private var isChapterSelectionMode: Boolean = false
    private var isLibrarySelectionMode: Boolean = false
    private val pendingModelErrorDialogs = ArrayDeque<PendingModelErrorDialog>()
    private var activeModelErrorDialog: AlertDialog? = null
    private var activeModelErrorRequest: PendingModelErrorDialog? = null

    private val tutorialUrlGithub =
        "https://github.com/jedzqer/manga-translator/blob/main/Tutorial/简中教程.md"
    private val tutorialUrlGitee =
        "https://gitee.com/jedzqer/manga-translator/blob/main/Tutorial/简中教程.md"

    private val folderAdapter = LibraryFolderAdapter(
        onClick = { openFolder(it.folder) },
        onDelete = { confirmDeleteFolder(it.folder) },
        onRename = { showRenameFolderDialog(it.folder) },
        onMove = { showMoveFolderPicker(it.folder) },
        onSelectionChanged = { updateLibrarySelectionActions() },
        onItemLongPress = { enterLibrarySelectionMode(it.folder) }
    )

    private val imageAdapter = FolderImageAdapter(
        onSelectionChanged = { selectionController.updateSelectionActions() },
        onItemLongPress = { selectionController.enterSelectionMode(it.file) },
        onItemClick = { openImageInReader(it.file) }
    )

    private val chapterAdapter = LibraryFolderAdapter(
        onClick = { openChildFolder(it.folder) },
        onDelete = { confirmDeleteFolder(it.folder) },
        onRename = { showRenameFolderDialog(it.folder) },
        onMove = { showMoveFolderPicker(it.folder) },
        onSelectionChanged = { updateChapterSelectionActions() },
        onItemLongPress = { enterChapterSelectionMode(it.folder) }
    )

    private val uiCallbacks = object : LibraryUiCallbacks {
        override fun setFolderStatus(left: String, right: String) {
            _binding?.let {
                it.folderProgressLeft.text = left
                it.folderProgressRight.text = right
            }
        }

        override fun clearFolderStatus() {
            setFolderStatus("")
        }

        override fun setTranslationActionsEnabled(enabled: Boolean) {
            applyTranslationActionsEnabled(enabled)
        }

        override fun showToast(resId: Int) {
            if (!isAdded) return
            Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show()
        }

        override fun showToastMessage(message: String) {
            if (!isAdded) return
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }

        override fun showApiError(code: String, detail: String?) {
            if (!isAdded) return
            dialogs.showApiErrorDialog(requireContext(), code, detail)
        }

        override fun showModelError(
            content: String,
            useSystemOverlay: Boolean,
            onRetry: (() -> Unit)?,
            onSkip: (() -> Unit)?
        ) {
            if (!isAdded) {
                onSkip?.invoke()
                return
            }
            pendingModelErrorDialogs.addLast(
                PendingModelErrorDialog(
                    content = content,
                    onRetry = onRetry,
                    onSkip = onSkip,
                    useSystemOverlay = useSystemOverlay
                )
            )
            showNextModelErrorDialog()
        }

        override fun refreshFolders() {
            if (!isAdded || _binding == null) return
            loadFolders()
        }

        override fun refreshImages(folder: File) {
            if (!isAdded || _binding == null) return
            loadImages(folder)
        }

        override fun isUiAttached(): Boolean {
            return isAdded
        }

        override fun isFragmentActive(): Boolean {
            return isAdded && _binding != null
        }

        override fun isAppInForeground(): Boolean {
            val hostActivity = activity ?: return false
            return isAdded &&
                hostActivity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                !hostActivity.isFinishing &&
                !hostActivity.isDestroyed
        }

        override fun isLibraryInForeground(): Boolean {
            return isAdded && _binding != null && isResumed
        }

        override fun canShowSystemOverlay(): Boolean {
            return isAdded && canDrawOverlays()
        }
    }

    private val requestStoragePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        importExportCoordinator.handleStoragePermissionResult(granted) {
            val folder = currentFolder ?: return@handleStoragePermissionResult
            if (importExportCoordinator.isPendingExportCollection()) {
                val chapterImages = buildChapterImagesForCollection(folder)
                importExportCoordinator.exportCollectionAfterPermission(
                    uiContext = requireContext(),
                    collectionFolder = folder,
                    chapterImages = chapterImages,
                    scope = viewLifecycleOwner.lifecycleScope,
                    onExitSelectionMode = { selectionController.exitSelectionMode() },
                    onSetExportEnabled = { enabled -> _binding?.folderExportCollection?.isEnabled = enabled }
                )
            } else {
                importExportCoordinator.exportFolderAfterPermission(
                    uiContext = requireContext(),
                    folder = folder,
                    images = repository.listImages(folder),
                    scope = viewLifecycleOwner.lifecycleScope,
                    onExitSelectionMode = { selectionController.exitSelectionMode() },
                    onSetExportEnabled = { enabled -> _binding?.folderExport?.isEnabled = enabled }
                )
            }
        }
    }

    private val pickImages = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            addImagesToFolder(uris)
        }
    }

    private val pickImportTree = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            importExportCoordinator.handleImportTreeSelection(
                uiContext = requireContext(),
                uri = uri,
                scope = viewLifecycleOwner.lifecycleScope,
                onShowFolderList = { showFolderList() }
            )
        }
    }

    private val pickChapterImportTree = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val parentFolder = pendingChapterImportParent
        pendingChapterImportParent = null
        if (uri != null && parentFolder != null) {
            importExportCoordinator.handleChapterImportTreeSelection(
                uiContext = requireContext(),
                parentFolder = parentFolder,
                uri = uri,
                scope = viewLifecycleOwner.lifecycleScope
            )
        }
    }

    private val pickArchiveOrPdfFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importFromArchiveOrPdf(uri)
        }
    }

    private val pickExportTree = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            importExportCoordinator.handleExportTreeSelection(uri) {
                val folder = currentFolder ?: return@handleExportTreeSelection
                if (importExportCoordinator.isPendingExportCollection()) {
                    val chapterImages = buildChapterImagesForCollection(folder)
                    importExportCoordinator.exportCollectionAfterPermission(
                        uiContext = requireContext(),
                        collectionFolder = folder,
                        chapterImages = chapterImages,
                        scope = viewLifecycleOwner.lifecycleScope,
                        onExitSelectionMode = { selectionController.exitSelectionMode() },
                        onSetExportEnabled = { enabled -> _binding?.folderExportCollection?.isEnabled = enabled }
                    )
                } else {
                    importExportCoordinator.exportFolderAfterPermission(
                        uiContext = requireContext(),
                        folder = folder,
                        images = repository.listImages(folder),
                        scope = viewLifecycleOwner.lifecycleScope,
                        onExitSelectionMode = { selectionController.exitSelectionMode() },
                        onSetExportEnabled = { enabled -> _binding?.folderExport?.isEnabled = enabled }
                    )
                }
            }
        } else {
            importExportCoordinator.handleExportTreeCanceled()
        }
    }

    private val requestOverlayPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (!isAdded) return@registerForActivityResult
        if (canDrawOverlays()) {
            launchScreenCapturePermissionRequest()
            return@registerForActivityResult
        }
        showOverlayPermissionFailedDialog()
    }

    private val requestScreenCapturePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (!isAdded) return@registerForActivityResult
        val data = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
            startFloatingTranslateEntry(result.resultCode, data)
            return@registerForActivityResult
        }
        showScreenCapturePermissionFailedDialog()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        LibraryUiBridge.register(uiCallbacks)
        preferencesGateway = LibraryPreferencesGateway(requireContext(), prefs, repository)
        importExportCoordinator = LibraryImportExportCoordinator(
            context = requireContext(),
            repository = repository,
            translationStore = translationStore,
            settingsStore = settingsStore,
            prefs = prefs,
            preferencesGateway = preferencesGateway,
            dialogs = dialogs,
            ui = uiCallbacks
        )
        selectionController = LibrarySelectionController(
            imageAdapter = imageAdapter,
            translationStore = translationStore,
            ocrStore = ocrStore,
            repository = repository,
            ui = uiCallbacks,
            dialogs = dialogs,
            bindingProvider = { _binding },
            contextProvider = { if (isAdded) requireContext() else null },
            onRetranslateRequested = { folder, selected, force ->
                runTranslation(folder, selected, force)
            }
        )

        binding.folderList.layoutManager = LinearLayoutManager(requireContext())
        binding.folderList.isNestedScrollingEnabled = false
        binding.folderList.adapter = folderAdapter
        (binding.folderList.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.root.setOnClickListener { folderAdapter.clearActionSelection() }
        binding.folderList.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: android.view.MotionEvent): Boolean {
                folderAdapter.clearActionSelectionIfTouchedOutside(rv, e)
                return false
            }
        })
        binding.folderImageList.layoutManager = LinearLayoutManager(requireContext())
        binding.folderImageList.isNestedScrollingEnabled = false
        binding.folderImageList.adapter = imageAdapter
        binding.folderChapterList.layoutManager = LinearLayoutManager(requireContext())
        binding.folderChapterList.isNestedScrollingEnabled = false
        binding.folderChapterList.adapter = chapterAdapter
        setupFolderTopBarOverlay()
        setupFolderDetailScrollBehavior()

        binding.addFolderFab.setOnClickListener { showCreateEntryDialog() }
        binding.importEhviewerButton.setOnClickListener { importFromEhViewer() }
        binding.floatingTranslateButton.setOnClickListener { handleFloatingTranslateClick() }
        binding.importCbzButton.setOnClickListener {
            pickArchiveOrPdfFile.launch(
                arrayOf(
                    "application/vnd.comicbook+zip",
                    "application/x-cbz",
                    "application/zip",
                    "application/pdf"
                )
            )
        }
        binding.tutorialButton.setOnClickListener { openTutorial() }
        binding.librarySelectAll.setOnClickListener { toggleSelectAllLibraryFolders() }
        binding.libraryTranslateSelected.setOnClickListener { translateSelectedLibraryFolders() }
        binding.libraryRenameSelected.setOnClickListener { renameSelectedLibraryFolder() }
        binding.libraryDeleteSelected.setOnClickListener { confirmDeleteSelectedLibraryFolders() }
        binding.libraryCancelSelection.setOnClickListener { exitLibrarySelectionMode() }
        binding.folderBackButton.setOnClickListener { navigateBackFromDetail() }
        binding.folderAddImages.setOnClickListener { handleAddContentClick() }
        binding.folderCollectionAddChapter.setOnClickListener { handleAddContentClick() }
        binding.folderImportChapters.setOnClickListener { importChildChapters() }
        binding.folderExportCollection.setOnClickListener { exportCollection() }
        binding.folderTranslateCollection.setOnClickListener { translateFolder() }
        binding.folderExport.setOnClickListener { exportFolder() }
        binding.folderTranslate.setOnClickListener { translateFolder() }
        binding.folderRead.setOnClickListener { startReading() }
        binding.folderCollectionRead.setOnClickListener { startReading() }
        binding.folderSelectAll.setOnClickListener { handleSelectAllClick() }
        binding.folderDeleteSelected.setOnClickListener { handleDeleteSelectedClick() }
        binding.folderRenameSelected.setOnClickListener { renameSelectedChapter() }
        binding.folderCancelSelection.setOnClickListener { exitActiveSelectionMode() }
        binding.folderRetranslateSelected.setOnClickListener {
            val folder = currentFolder
            val enabled = folder?.let { preferencesGateway.isFullTranslateEnabled(it) } ?: true
            selectionController.retranslateSelectedImages(folder, enabled)
        }
        binding.folderFullTranslateInfo.setOnClickListener { showFullTranslateInfo() }
        binding.folderLanguageSetting.setOnClickListener { showLanguageSettingDialog() }
        binding.folderReadingModeButton.setOnClickListener { showFolderReadingModeDialog() }
        binding.folderFullTranslateSwitch.setOnCheckedChangeListener { _, isChecked ->
            currentFolder?.let { folder ->
                preferencesGateway.setFullTranslateEnabled(folder, isChecked)
                if (isChecked && preferencesGateway.isVlDirectTranslateEnabled(folder)) {
                    binding.folderVlDirectTranslateSwitch.isChecked = false
                }
                updateFolderTranslationSwitchStates(folder)
            }
        }
        binding.folderGlossaryProcessingSwitch.setOnCheckedChangeListener { _, isChecked ->
            currentFolder?.let { preferencesGateway.setGlossaryProcessingEnabled(it, isChecked) }
        }
        binding.folderVlDirectTranslateSwitch.setOnCheckedChangeListener { _, isChecked ->
            currentFolder?.let { folder ->
                preferencesGateway.setVlDirectTranslateEnabled(folder, isChecked)
                if (isChecked) {
                    Toast.makeText(
                        requireContext(),
                        R.string.folder_use_vl_direct_translate_warning,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (selectionController.isSelectionMode) {
                        selectionController.exitSelectionMode()
                        return
                    }
                    if (isLibrarySelectionMode) {
                        exitLibrarySelectionMode()
                        return
                    }
                    if (binding.folderDetailContainer.isVisible) {
                        navigateBackFromDetail()
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )

        loadFolders()
        showFolderList()
    }

    private fun handleFloatingTranslateClick() {
        if (canDrawOverlays()) {
            launchScreenCapturePermissionRequest()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.overlay_permission_required_title))
            .setMessage(getString(R.string.overlay_permission_required_message))
            .setPositiveButton(android.R.string.ok) { _, _ -> openOverlayPermissionSettings() }
            .setNegativeButton(android.R.string.cancel, null)
            .showWithScrollableMessage()
    }

    private fun openOverlayPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:${requireContext().packageName}".toUri()
        )
        requestOverlayPermission.launch(intent)
    }

    private fun launchScreenCapturePermissionRequest() {
        val manager = requireContext().getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            showScreenCapturePermissionFailedDialog()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.screen_capture_permission_required_title))
            .setMessage(getString(R.string.screen_capture_permission_required_message))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                requestScreenCapturePermissionLauncher.launch(manager.createScreenCaptureIntent())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showWithScrollableMessage()
    }

    private fun showOverlayPermissionFailedDialog() {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.overlay_permission_failed_title))
            .setMessage(getString(R.string.overlay_permission_failed_message))
            .setPositiveButton(android.R.string.ok, null)
            .showWithScrollableMessage()
    }

    private fun showScreenCapturePermissionFailedDialog() {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.screen_capture_permission_required_title))
            .setMessage(getString(R.string.screen_capture_permission_failed))
            .setPositiveButton(android.R.string.ok, null)
            .showWithScrollableMessage()
    }

    private fun startFloatingTranslateEntry(resultCode: Int, resultData: Intent) {
        val context = requireContext()
        ContextCompat.startForegroundService(
            context,
            Intent(context, FloatingBallOverlayService::class.java).apply {
                action = FloatingBallOverlayService.ACTION_START
                putExtra(FloatingBallOverlayService.EXTRA_RESULT_CODE, resultCode)
                putExtra(FloatingBallOverlayService.EXTRA_RESULT_DATA, resultData)
            }
        )
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(homeIntent)
    }

    private fun canDrawOverlays(): Boolean {
        return Settings.canDrawOverlays(requireContext())
    }

    override fun onDestroyView() {
        LibraryUiBridge.unregister(uiCallbacks)
        activeModelErrorDialog?.dismiss()
        activeModelErrorDialog = null
        activeModelErrorRequest = null
        super.onDestroyView()
        _binding = null
    }

    override fun onResume() {
        super.onResume()
        showNextModelErrorDialog()
    }

    private fun showNextModelErrorDialog() {
        if (!isAdded || activeModelErrorDialog != null) return
        if (pendingModelErrorDialogs.isEmpty()) return
        val request = pendingModelErrorDialogs.first()
        if (!request.useSystemOverlay && !uiCallbacks.isAppInForeground()) return
        pendingModelErrorDialogs.removeFirst()
        val dialog = dialogs.showModelErrorDialog(
            requireContext(),
            request.content,
            onRetry = request.onRetry,
            onSkip = request.onSkip,
            onUnresolvedDismiss = {
                requeueModelErrorDialog(request)
            },
            onDialogDismissed = {
                activeModelErrorRequest = null
                activeModelErrorDialog = null
                showNextModelErrorDialog()
            },
            windowType = if (request.useSystemOverlay) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
            } else {
                null
            }
        )
        activeModelErrorRequest = request
        activeModelErrorDialog = dialog
    }

    private fun requeueModelErrorDialog(request: PendingModelErrorDialog) {
        if (!isAdded) return
        activeModelErrorRequest = null
        activeModelErrorDialog = null
        viewLifecycleOwner.lifecycleScope.launch {
            delay(MODEL_ERROR_RESHOW_DELAY_MS)
            if (!isAdded || _binding == null) return@launch
            pendingModelErrorDialogs.addFirst(request)
            showNextModelErrorDialog()
        }
    }

    private fun showFolderList() {
        currentFolder = null
        currentParentFolder = null
        resetFolderTopBar(forceVisible = true)
        uiCallbacks.clearFolderStatus()
        exitActiveSelectionMode()
        exitLibrarySelectionMode()
        folderAdapter.clearActionSelection()
        chapterAdapter.clearActionSelection()
        loadFolders()
        if (!binding.folderDetailContainer.isVisible) {
            applyFolderListVisibleState()
            return
        }
        animateFolderTransition(showDetail = false)
    }

    private fun showFolderDetail(folder: File, parentFolder: File? = null) {
        currentFolder = folder
        currentParentFolder = parentFolder
        resetFolderTopBar(forceVisible = true)
        binding.folderTitle.text = buildFolderTitle(folder)
        binding.folderFullTranslateSwitch.isChecked = preferencesGateway.isFullTranslateEnabled(folder)
        binding.folderGlossaryProcessingSwitch.isChecked =
            preferencesGateway.isGlossaryProcessingEnabled(folder)
        binding.folderVlDirectTranslateSwitch.isChecked =
            preferencesGateway.isVlDirectTranslateEnabled(folder)
        updateFolderTranslationSwitchStates(folder)
        updateLanguageSettingButton(folder)
        updateReadingModeButton(folder)
        updateFolderContentMode(folder)
        exitActiveSelectionMode()
        loadImages(folder)
        if (binding.folderDetailContainer.isVisible && !binding.libraryListContainer.isVisible) {
            binding.folderDetailContainer.alpha = 1f
            binding.folderDetailContainer.translationY = 0f
            AppLogger.log("Library", "Opened folder ${folder.name}")
            return
        }
        animateFolderTransition(showDetail = true)
        AppLogger.log("Library", "Opened folder ${folder.name}")
    }

    private fun animateFolderTransition(showDetail: Boolean) {
        if (isFolderTransitionRunning) return
        isFolderTransitionRunning = true

        val outgoing = if (showDetail) binding.libraryListContainer else binding.folderDetailContainer
        val incoming = if (showDetail) binding.folderDetailContainer else binding.libraryListContainer
        val offset = resources.displayMetrics.density * 24

        outgoing.animate().cancel()
        incoming.animate().cancel()
        binding.addFolderFab.animate().cancel()

        incoming.visibility = View.VISIBLE
        incoming.alpha = 0f
        incoming.translationY = if (showDetail) offset else -offset * 0.5f

        outgoing.visibility = View.VISIBLE
        outgoing.alpha = 1f
        outgoing.translationY = 0f

        if (showDetail) {
            binding.addFolderFab.animate()
                .alpha(0f)
                .setDuration(120L)
                .withEndAction { binding.addFolderFab.visibility = View.GONE }
                .start()
        } else {
            binding.addFolderFab.visibility = View.VISIBLE
            binding.addFolderFab.alpha = 0f
            binding.addFolderFab.translationY = offset
            binding.addFolderFab.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(180L)
                .start()
        }

        outgoing.animate()
            .alpha(0f)
            .translationY(if (showDetail) -offset * 0.35f else offset * 0.35f)
            .setDuration(170L)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    outgoing.visibility = View.GONE
                    outgoing.alpha = 1f
                    outgoing.translationY = 0f
                    outgoing.animate().setListener(null)
                }

                override fun onAnimationCancel(animation: Animator) {
                    outgoing.visibility = View.GONE
                    outgoing.alpha = 1f
                    outgoing.translationY = 0f
                    outgoing.animate().setListener(null)
                }
            })
            .start()

        incoming.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220L)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    incoming.alpha = 1f
                    incoming.translationY = 0f
                    incoming.animate().setListener(null)
                    isFolderTransitionRunning = false
                }

                override fun onAnimationCancel(animation: Animator) {
                    incoming.alpha = 1f
                    incoming.translationY = 0f
                    incoming.animate().setListener(null)
                    isFolderTransitionRunning = false
                }
            })
            .start()
    }

    private fun applyFolderListVisibleState() {
        binding.libraryListContainer.animate().cancel()
        binding.folderDetailContainer.animate().cancel()
        binding.addFolderFab.animate().cancel()
        resetFolderTopBar(forceVisible = true)
        binding.libraryListContainer.visibility = View.VISIBLE
        binding.libraryListContainer.alpha = 1f
        binding.libraryListContainer.translationY = 0f
        binding.folderDetailContainer.visibility = View.GONE
        binding.folderDetailContainer.alpha = 1f
        binding.folderDetailContainer.translationY = 0f
        binding.addFolderFab.visibility = View.VISIBLE
        binding.addFolderFab.alpha = 1f
        binding.addFolderFab.translationY = 0f
        isFolderTransitionRunning = false
    }

    private fun setupFolderDetailScrollBehavior() {
        val threshold = (resources.displayMetrics.density * 20).toInt().coerceAtLeast(1)
        binding.folderDetailScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            if (!binding.folderDetailContainer.isVisible) {
                lastFolderDetailScrollY = scrollY
                return@setOnScrollChangeListener
            }
            val delta = scrollY - lastFolderDetailScrollY
            lastFolderDetailScrollY = scrollY
            if (scrollY <= 0) {
                folderTopBarScrollAccumulated = 0
                setFolderTopBarVisible(true)
                return@setOnScrollChangeListener
            }
            if (delta == 0) return@setOnScrollChangeListener
            val sameDirection =
                (delta > 0 && folderTopBarScrollAccumulated > 0) ||
                    (delta < 0 && folderTopBarScrollAccumulated < 0)
            folderTopBarScrollAccumulated = if (folderTopBarScrollAccumulated == 0 || sameDirection) {
                folderTopBarScrollAccumulated + delta
            } else {
                delta
            }
            if (folderTopBarScrollAccumulated >= threshold) {
                setFolderTopBarVisible(false)
                folderTopBarScrollAccumulated = 0
            } else if (folderTopBarScrollAccumulated <= -threshold) {
                setFolderTopBarVisible(true)
                folderTopBarScrollAccumulated = 0
            }
        }
    }

    private fun setupFolderTopBarOverlay() {
        folderDetailContentBaseTopPadding = binding.folderDetailContent.paddingTop
        binding.folderTopBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (_binding == null) return@addOnLayoutChangeListener
            updateFolderDetailContentTopInset()
        }
        binding.folderTopBar.doOnLayout {
            if (_binding == null) return@doOnLayout
            updateFolderDetailContentTopInset()
        }
    }

    private fun updateFolderDetailContentTopInset() {
        val topBar = binding.folderTopBar
        topBar.bringToFront()
        val content = binding.folderDetailContent
        val targetTopPadding = folderDetailContentBaseTopPadding + topBar.height
        if (content.paddingTop == targetTopPadding) return
        content.setPadding(
            content.paddingLeft,
            targetTopPadding,
            content.paddingRight,
            content.paddingBottom
        )
    }

    private fun resetFolderTopBar(forceVisible: Boolean) {
        lastFolderDetailScrollY = binding.folderDetailScroll.scrollY
        folderTopBarScrollAccumulated = 0
        if (forceVisible) {
            binding.folderTopBar.doOnLayout {
                if (_binding == null) return@doOnLayout
                setFolderTopBarVisible(true, immediate = true)
            }
        }
    }

    private fun setFolderTopBarVisible(visible: Boolean, immediate: Boolean = false) {
        if (isFolderTopBarVisible == visible && !immediate) return
        isFolderTopBarVisible = visible
        val topBar = binding.folderTopBar
        topBar.animate().cancel()
        topBar.bringToFront()
        if (immediate) {
            topBar.alpha = if (visible) 1f else 0f
            topBar.translationY = if (visible) 0f else -topBar.height.toFloat()
            return
        }
        topBar.animate()
            .alpha(if (visible) 1f else 0f)
            .translationY(if (visible) 0f else -topBar.height.toFloat())
            .setDuration(180L)
            .start()
    }

    private fun loadFolders() {
        val folders = repository.listFolders()
        val items = folders.map(::buildFolderItem)
        folderAdapter.submit(items)
        binding.libraryEmpty.text = getString(R.string.folder_empty)
        binding.libraryEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        if (isLibrarySelectionMode) {
            updateLibrarySelectionActions()
        }
    }

    private fun loadImages(folder: File) {
        if (repository.isCollectionFolder(folder)) {
            val chapters = repository.listChildFolders(folder)
            chapterAdapter.submit(chapters.map(::buildFolderItem))
            imageAdapter.submit(emptyList())
            binding.folderChapterList.visibility = View.VISIBLE
            binding.folderImageList.visibility = View.GONE
            binding.folderImagesEmpty.text = getString(R.string.folder_chapters_empty)
            binding.folderImagesEmpty.visibility = if (chapters.isEmpty()) View.VISIBLE else View.GONE
            uiCallbacks.clearFolderStatus()
            return
        }
        val images = repository.listImages(folder)
        val items = images.map { file ->
            ImageItem(
                file = file,
                translated = isImageTranslated(file, folder)
            )
        }
        imageAdapter.submit(items)
        chapterAdapter.submit(emptyList())
        binding.folderChapterList.visibility = View.GONE
        binding.folderImageList.visibility = View.VISIBLE
        binding.folderImagesEmpty.text = getString(R.string.folder_images_empty)
        binding.folderImagesEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        if (selectionController.isSelectionMode) {
            selectionController.updateSelectionActions()
        } else {
            uiCallbacks.clearFolderStatus()
        }
    }

    private fun openFolder(folder: File) {
        folderAdapter.clearActionSelection()
        showFolderDetail(folder)
    }

    private fun openChildFolder(folder: File) {
        if (isChapterSelectionMode) return
        chapterAdapter.clearActionSelection()
        val parent = currentFolder ?: return
        showFolderDetail(folder, parent)
    }

    private fun openTutorial() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.tutorial_open_title)
            .setMessage(R.string.tutorial_open_message)
            .setPositiveButton(R.string.tutorial_open_mirror) { _, _ ->
                openUrlOrToast(tutorialUrlGitee)
            }
            .setNegativeButton(R.string.tutorial_open_github) { _, _ ->
                openUrlOrToast(tutorialUrlGithub)
            }
            .setNeutralButton(android.R.string.cancel, null)
            .showWithScrollableMessage()
    }

    private fun openUrlOrToast(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        val manager = requireContext().packageManager
        if (intent.resolveActivity(manager) != null) {
            startActivity(intent)
        } else {
            Toast.makeText(requireContext(), url, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showCreateEntryDialog() {
        dialogs.showCreateEntryDialog(
            context = requireContext(),
            onCreateFolder = { showCreateFolderDialog() },
            onCreateCollection = { showCreateCollectionDialog() }
        )
    }

    private fun showCreateFolderDialog() {
        dialogs.showCreateFolderDialog(requireContext()) { name ->
            val folder = repository.createFolder(name)
            if (folder == null) {
                AppLogger.log("Library", "Create folder failed: $name")
                Toast.makeText(requireContext(), R.string.folder_create_failed, Toast.LENGTH_SHORT).show()
            } else {
                AppLogger.log("Library", "Created folder ${folder.name}")
                loadFolders()
            }
        }
    }

    private fun showCreateCollectionDialog() {
        dialogs.showCreateCollectionDialog(requireContext()) { name ->
            val folder = repository.createCollection(name)
            if (folder == null) {
                AppLogger.log("Library", "Create collection failed: $name")
                Toast.makeText(requireContext(), R.string.folder_create_failed, Toast.LENGTH_SHORT).show()
            } else {
                AppLogger.log("Library", "Created collection ${folder.name}")
                loadFolders()
            }
        }
    }

    private fun showCreateChapterDialog(parent: File) {
        dialogs.showCreateChapterDialog(requireContext()) { name ->
            val folder = repository.createChildFolder(parent, name)
            if (folder == null) {
                AppLogger.log("Library", "Create chapter failed in ${parent.name}: $name")
                Toast.makeText(requireContext(), R.string.folder_create_failed, Toast.LENGTH_SHORT).show()
            } else {
                AppLogger.log("Library", "Created chapter ${folder.name} in ${parent.name}")
                loadImages(parent)
                loadFolders()
            }
        }
    }

    private fun addImagesToFolder(uris: List<Uri>) {
        val folder = currentFolder ?: return
        val added = repository.addImages(folder, uris)
        AppLogger.log("Library", "Added ${added.size} images to ${folder.name}")
        loadImages(folder)
        loadFolders()
    }

    private fun handleAddContentClick() {
        val folder = currentFolder ?: return
        if (repository.isCollectionFolder(folder)) {
            showCreateChapterDialog(folder)
        } else {
            pickImages.launch(arrayOf("image/*"))
        }
    }

    private fun navigateBackFromDetail() {
        val parentFolder = currentParentFolder
        if (parentFolder != null) {
            showFolderDetail(parentFolder)
        } else {
            showFolderList()
        }
    }

    private fun importChildChapters() {
        val parentFolder = currentFolder ?: return
        if (!repository.isCollectionFolder(parentFolder)) return
        pendingChapterImportParent = parentFolder
        importExportCoordinator.requestImportDirectory { initialUri ->
            pickChapterImportTree.launch(initialUri)
        }
    }

    private fun importFromEhViewer() {
        importExportCoordinator.requestImportDirectory { initialUri ->
            pickImportTree.launch(initialUri)
        }
    }

    private fun importFromArchiveOrPdf(uri: Uri) {
        importExportCoordinator.importFromArchiveOrPdf(
            uiContext = requireContext(),
            uri = uri,
            scope = viewLifecycleOwner.lifecycleScope,
            onShowFolderList = { showFolderList() }
        )
    }

    private fun confirmDeleteFolder(folder: File) {
        dialogs.confirmDeleteFolder(requireContext(), folder.name) {
            val deleted = repository.deleteFolder(folder)
            if (!deleted) {
                AppLogger.log("Library", "Delete folder failed: ${folder.name}")
                Toast.makeText(requireContext(), R.string.folder_delete_failed, Toast.LENGTH_SHORT).show()
            } else {
                AppLogger.log("Library", "Deleted folder ${folder.name}")
            }
            refreshFolderViewsAfterMutation(folder)
        }
    }

    private fun showRenameFolderDialog(folder: File) {
        dialogs.showRenameFolderDialog(requireContext(), folder.name) { inputName ->
            val renamed = repository.renameFolder(folder, inputName)
            if (renamed == null) {
                AppLogger.log("Library", "Rename folder failed: ${folder.name} -> $inputName")
                Toast.makeText(requireContext(), R.string.folder_rename_failed, Toast.LENGTH_SHORT).show()
            } else {
                preferencesGateway.migrateFolderSettings(folder, renamed)
                AppLogger.log("Library", "Renamed folder ${folder.name} -> ${renamed.name}")
                refreshFolderViewsAfterMutation(folder, renamed)
            }
        }
    }

    private fun showMoveFolderPicker(folder: File) {
        val collections = repository
            .listFolders()
            .filter { it.absolutePath != folder.absolutePath }
            .filter { repository.isCollectionFolder(it) }
            .filter { it.absolutePath != folder.parentFile?.absolutePath }
        if (collections.isEmpty()) {
            Toast.makeText(requireContext(), R.string.folder_move_no_collections, Toast.LENGTH_SHORT).show()
            return
        }
        dialogs.showMoveFolderDialog(requireContext(), collections.map { buildFolderTitle(it) }) { index ->
            val targetCollection = collections.getOrNull(index) ?: return@showMoveFolderDialog
            val moved = repository.moveFolderToCollection(folder, targetCollection)
            if (moved == null) {
                AppLogger.log("Library", "Move folder failed: ${folder.name} -> ${targetCollection.name}")
                Toast.makeText(requireContext(), R.string.folder_move_failed, Toast.LENGTH_SHORT).show()
            } else {
                AppLogger.log("Library", "Moved folder ${folder.name} -> ${targetCollection.name}/${moved.name}")
                refreshFolderViewsAfterMutation(folder, moved)
            }
        }
    }

    private fun refreshFolderViewsAfterMutation(original: File, updated: File? = null) {
        val visibleFolder = currentFolder
        val visibleParent = currentParentFolder
        val currentPath = visibleFolder?.absolutePath
        val parentPath = visibleParent?.absolutePath
        val originalPath = original.absolutePath
        val originalParentPath = original.parentFile?.absolutePath

        when {
            currentPath == originalPath -> {
                if (updated == null) {
                    showFolderList()
                } else {
                    showFolderDetail(updated, visibleParent)
                }
            }
            parentPath == originalPath -> {
                if (updated == null) {
                    showFolderList()
                } else {
                    showFolderDetail(visibleFolder ?: updated, updated)
                }
            }
            currentPath != null && originalParentPath == currentPath -> {
                loadImages(visibleFolder)
                loadFolders()
            }
            else -> loadFolders()
        }
    }

    private fun refreshFolderViewsAfterBatchMutation(deletedFolders: List<File>) {
        val currentPath = currentFolder?.absolutePath
        val parentPath = currentParentFolder?.absolutePath
        val deletedPaths = deletedFolders.map { it.absolutePath }.toHashSet()
        val deletedParentPaths = deletedFolders.mapNotNull { it.parentFile?.absolutePath }.toHashSet()

        when {
            currentPath != null && deletedPaths.contains(currentPath) -> showFolderList()
            parentPath != null && deletedPaths.contains(parentPath) -> showFolderList()
            currentPath != null && deletedParentPaths.contains(currentPath) -> {
                currentFolder?.let(::loadImages)
                loadFolders()
            }
            else -> loadFolders()
        }
    }

    private fun translateFolder() {
        val folder = currentFolder ?: return
        selectionController.exitSelectionMode()
        if (repository.isCollectionFolder(folder)) {
            runCollectionTranslation(folder, force = false)
            return
        }
        runTranslation(folder, repository.listImages(folder), force = false)
    }

    private fun runTranslation(folder: File, images: List<File>, force: Boolean) {
        val fullTranslate = preferencesGateway.isFullTranslateEnabled(folder)
        val glossaryProcessingEnabled = preferencesGateway.isGlossaryProcessingEnabled(folder)
        val useVlDirectTranslate = preferencesGateway.isVlDirectTranslateEnabled(folder)
        val useLocalOcr = settingsStore.loadOcrApiSettings().useLocalOcr
        val language = if (useLocalOcr) {
            preferencesGateway.getTranslationLanguage(folder)
        } else {
            TranslationLanguage.JA_TO_ZH
        }
        _binding?.folderTranslate?.isEnabled = false
        TranslationKeepAliveService.startTranslationTask(
            requireContext(),
            TranslationTaskPersistence.fromFolder(
                folder = folder,
                images = images,
                force = force,
                fullTranslate = fullTranslate,
                glossaryProcessingEnabled = glossaryProcessingEnabled,
                useVlDirectTranslate = useVlDirectTranslate,
                language = language
            )
        )
    }

    private fun runCollectionTranslation(collectionFolder: File, force: Boolean) {
        val tasks = buildTranslationTasksForFolder(collectionFolder, force)
        _binding?.folderImportChapters?.isEnabled = false
        _binding?.folderExportCollection?.isEnabled = false
        _binding?.folderTranslateCollection?.isEnabled = false
        _binding?.folderCollectionAddChapter?.isEnabled = false
        TranslationKeepAliveService.startTranslationTask(
            requireContext(),
            TranslationTaskPersistence.fromCollection(collectionFolder, tasks)
        )
    }

    private fun buildTranslationTasksForFolder(folder: File, force: Boolean): List<FolderTranslationTask> {
        if (!repository.isCollectionFolder(folder)) {
            val useLocalOcr = settingsStore.loadOcrApiSettings().useLocalOcr
            val language = if (useLocalOcr) {
                preferencesGateway.getTranslationLanguage(folder)
            } else {
                TranslationLanguage.JA_TO_ZH
            }
            return listOf(
                FolderTranslationTask(
                    folder = folder,
                    images = repository.listImages(folder),
                    force = force,
                    fullTranslate = preferencesGateway.isFullTranslateEnabled(folder),
                    glossaryProcessingEnabled = preferencesGateway.isGlossaryProcessingEnabled(folder),
                    useVlDirectTranslate = preferencesGateway.isVlDirectTranslateEnabled(folder),
                    language = language
                )
            )
        }
        return repository.listChildFolders(folder).map { chapter ->
            val useLocalOcr = settingsStore.loadOcrApiSettings().useLocalOcr
            val language = if (useLocalOcr) {
                preferencesGateway.getTranslationLanguage(chapter)
            } else {
                TranslationLanguage.JA_TO_ZH
            }
            FolderTranslationTask(
                folder = chapter,
                images = repository.listImages(chapter),
                force = force,
                fullTranslate = preferencesGateway.isFullTranslateEnabled(chapter),
                glossaryProcessingEnabled = preferencesGateway.isGlossaryProcessingEnabled(chapter),
                useVlDirectTranslate = preferencesGateway.isVlDirectTranslateEnabled(chapter),
                language = language
            )
        }
    }

    private fun updateGlossaryProcessingSwitchState(folder: File) {
        val enabled = !preferencesGateway.isFullTranslateEnabled(folder)
        binding.folderGlossaryProcessingSwitch.isEnabled = enabled
        binding.folderGlossaryProcessingNote.alpha = if (enabled) 1f else 0.5f
    }

    private fun updateVlDirectTranslateSwitchState(folder: File) {
        val enabled = !preferencesGateway.isFullTranslateEnabled(folder)
        if (!enabled && preferencesGateway.isVlDirectTranslateEnabled(folder)) {
            preferencesGateway.setVlDirectTranslateEnabled(folder, false)
            if (binding.folderVlDirectTranslateSwitch.isChecked) {
                binding.folderVlDirectTranslateSwitch.isChecked = false
            }
        }
        binding.folderVlDirectTranslateSwitch.isEnabled = enabled
        binding.folderVlDirectTranslateNote.alpha = if (enabled) 1f else 0.5f
    }

    private fun updateFolderTranslationSwitchStates(folder: File) {
        updateGlossaryProcessingSwitchState(folder)
        updateVlDirectTranslateSwitchState(folder)
    }

    private fun exportFolder() {
        val folder = currentFolder ?: return
        val images = repository.listImages(folder)
        dialogs.showExportOptionsDialog(
            context = requireContext(),
            defaultThreads = importExportCoordinator.getExportThreadCount(),
            defaultExportFormat = importExportCoordinator.getExportFormatDefault(),
            exportRootPathHint = importExportCoordinator.buildExportRootPathPreview()
        ) { exportThreads, exportFormat ->
            importExportCoordinator.exportFolder(
                uiContext = requireContext(),
                folder = folder,
                images = images,
                scope = viewLifecycleOwner.lifecycleScope,
                exportThreads = exportThreads,
                exportFormat = exportFormat,
                requestExportDirectoryPermission = { initialUri -> pickExportTree.launch(initialUri) },
                requestLegacyPermission = {
                    requestStoragePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                },
                onExitSelectionMode = { selectionController.exitSelectionMode() },
                onSetExportEnabled = { enabled -> _binding?.folderExport?.isEnabled = enabled }
            )
        }
    }

    private fun exportCollection() {
        val folder = currentFolder ?: return
        val chapterImages = buildChapterImagesForCollection(folder)
        if (chapterImages.isEmpty()) {
            uiCallbacks.setFolderStatus(getString(R.string.folder_chapters_empty))
            return
        }
        dialogs.showExportOptionsDialog(
            context = requireContext(),
            defaultThreads = importExportCoordinator.getExportThreadCount(),
            defaultExportFormat = importExportCoordinator.getExportFormatDefault(),
            exportRootPathHint = importExportCoordinator.buildExportRootPathPreview()
        ) { exportThreads, exportFormat ->
            importExportCoordinator.exportCollection(
                uiContext = requireContext(),
                collectionFolder = folder,
                chapterImages = chapterImages,
                scope = viewLifecycleOwner.lifecycleScope,
                exportThreads = exportThreads,
                exportFormat = exportFormat,
                requestExportDirectoryPermission = { initialUri -> pickExportTree.launch(initialUri) },
                requestLegacyPermission = {
                    requestStoragePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                },
                onExitSelectionMode = { selectionController.exitSelectionMode() },
                onSetExportEnabled = { enabled -> _binding?.folderExportCollection?.isEnabled = enabled }
            )
        }
    }

    private fun buildChapterImagesForCollection(collectionFolder: File): List<Pair<File, List<File>>> {
        return repository.listChildFolders(collectionFolder).map { chapter ->
            chapter to repository.listImages(chapter)
        }.filter { it.second.isNotEmpty() }
    }

    private fun startReading() {
        val folder = currentFolder ?: return
        exitActiveSelectionMode()
        if (repository.isCollectionFolder(folder)) {
            val images = repository.listChildFolders(folder).flatMap { repository.listImages(it) }
            if (images.isEmpty()) {
                uiCallbacks.setFolderStatus(getString(R.string.folder_chapters_empty))
                return
            }
            val startIndex = readingProgressStore.load(folder)
            val readingMode = preferencesGateway.getReadingMode(folder)
            readingSessionViewModel.setFolder(folder, images, startIndex, readingMode)
            (activity as? MainActivity)?.switchToTab(MainPagerAdapter.READING_INDEX)
            return
        }
        val images = repository.listImages(folder)
        if (images.isEmpty()) {
            uiCallbacks.setFolderStatus(getString(R.string.folder_images_empty))
            return
        }
        AppLogger.log("Library", "Start reading ${folder.name}, ${images.size} images")
        val startIndex = readingProgressStore.load(folder)
        val readingMode = preferencesGateway.getReadingMode(folder)
        readingSessionViewModel.setFolder(folder, images, startIndex, readingMode)
        (activity as? MainActivity)?.switchToTab(MainPagerAdapter.READING_INDEX)
    }

    private fun openImageInReader(imageFile: File) {
        val folder = currentFolder ?: return
        if (selectionController.isSelectionMode) return
        val images = repository.listImages(folder)
        if (images.isEmpty()) {
            uiCallbacks.setFolderStatus(getString(R.string.folder_images_empty))
            return
        }
        val startIndex = images.indexOfFirst { it.absolutePath == imageFile.absolutePath }
        if (startIndex < 0) return
        AppLogger.log("Library", "Open image ${imageFile.name} at index $startIndex in ${folder.name}")
        val readingMode = preferencesGateway.getReadingMode(folder)
        readingSessionViewModel.setFolder(folder, images, startIndex, readingMode)
        (activity as? MainActivity)?.switchToTab(MainPagerAdapter.READING_INDEX)
    }

    private fun showFullTranslateInfo() {
        dialogs.showFullTranslateInfo(requireContext())
    }

    private fun updateLanguageSettingButton(folder: File) {
        val useLocalOcr = settingsStore.loadOcrApiSettings().useLocalOcr
        val displayName = if (useLocalOcr) {
            val language = preferencesGateway.getTranslationLanguage(folder)
            getString(language.displayNameResId)
        } else {
            getString(R.string.folder_language_to_zh)
        }
        binding.folderLanguageSetting.text = getString(R.string.folder_language_setting, displayName)
    }

    private fun updateReadingModeButton(folder: File) {
        val mode = preferencesGateway.getReadingMode(folder)
        binding.folderReadingModeButton.text = getString(
            R.string.folder_reading_mode_format,
            getString(mode.labelRes)
        )
    }

    private fun showLanguageSettingDialog() {
        val folder = currentFolder ?: return
        if (!settingsStore.loadOcrApiSettings().useLocalOcr) {
            dialogs.showFixedLanguageDialog(requireContext())
            return
        }
        val currentLanguage = preferencesGateway.getTranslationLanguage(folder)
        dialogs.showLanguageSettingDialog(requireContext(), currentLanguage) { selectedLanguage ->
            preferencesGateway.setTranslationLanguage(folder, selectedLanguage)
            updateLanguageSettingButton(folder)
            AppLogger.log("Library", "Set language for ${folder.name}: ${selectedLanguage.name}")
        }
    }

    private fun showFolderReadingModeDialog() {
        val folder = currentFolder ?: return
        val currentMode = preferencesGateway.getReadingMode(folder)
        dialogs.showFolderReadingModeDialog(requireContext(), currentMode) { selectedMode ->
            preferencesGateway.setReadingMode(folder, selectedMode)
            readingSessionViewModel.updateReadingMode(folder, selectedMode)
            updateReadingModeButton(folder)
            AppLogger.log("Library", "Set reading mode for ${folder.name}: ${selectedMode.prefValue}")
        }
    }

    private fun buildFolderItem(folder: File): FolderItem {
        val chapters = repository.listChildFolders(folder)
        val isCollection = repository.isCollectionFolder(folder)
        val images = if (isCollection) {
            chapters.flatMap { repository.listImages(it) }
        } else {
            repository.listImages(folder)
        }
        return FolderItem(
            folder = folder,
            imageCount = images.size,
            chapterCount = chapters.size,
            isCollection = isCollection,
            status = resolveFolderStatus(folder, images)
        )
    }

    private fun resolveFolderStatus(folder: File, images: List<File>): FolderStatus {
        if (images.isEmpty()) return FolderStatus.UNTRANSLATED
        val allTranslated = images.all { image -> isImageTranslated(image, folder) }
        return if (allTranslated) FolderStatus.TRANSLATED else FolderStatus.UNTRANSLATED
    }

    private fun isImageTranslated(image: File, folder: File): Boolean {
        return translationPipeline.hasValidTranslation(
            imageFile = image,
            fullTranslate = preferencesGateway.isFullTranslateEnabled(folder),
            useVlDirectTranslate = preferencesGateway.isVlDirectTranslateEnabled(folder),
            language = preferencesGateway.getTranslationLanguage(folder)
        )
    }

    private fun buildFolderTitle(folder: File): String {
        return if (repository.isCollectionFolder(folder)) {
            folder.name + getString(R.string.folder_collection_title_suffix)
        } else {
            folder.name
        }
    }

    private fun updateFolderContentMode(folder: File) {
        val isCollection = repository.isCollectionFolder(folder)
        val useParentCollectionSettings =
            repository.resolveSettingsFolder(folder).absolutePath != folder.absolutePath
        binding.folderAddImages.visibility = if (isCollection) View.GONE else View.VISIBLE
        binding.folderRead.visibility = if (isCollection) View.GONE else View.VISIBLE
        binding.folderCollectionActions.visibility = if (isCollection) View.VISIBLE else View.GONE
        binding.folderExport.visibility = if (isCollection) View.GONE else View.VISIBLE
        binding.folderTranslate.visibility = if (isCollection) View.GONE else View.VISIBLE
        binding.folderTranslationSettings.visibility =
            if (useParentCollectionSettings) View.GONE else View.VISIBLE
        binding.folderReadingSettings.visibility =
            if (useParentCollectionSettings) View.GONE else View.VISIBLE
        binding.folderSelectionActions.visibility = View.GONE
        binding.folderRenameSelected.visibility = View.GONE
        binding.folderRetranslateSelected.visibility = if (isCollection) View.GONE else View.VISIBLE
        if (isCollection) {
            selectionController.exitSelectionMode()
        }
    }

    private fun handleSelectAllClick() {
        if (isChapterSelectionMode) {
            if (chapterAdapter.areAllSelected()) {
                chapterAdapter.clearSelection()
            } else {
                chapterAdapter.selectAll()
            }
            return
        }
        selectionController.toggleSelectAllImages()
    }

    private fun enterLibrarySelectionMode(target: File) {
        if (!isLibrarySelectionMode) {
            isLibrarySelectionMode = true
            folderAdapter.setSelectionMode(true)
            binding.librarySelectionActions.visibility = View.VISIBLE
            uiCallbacks.clearFolderStatus()
        }
        folderAdapter.toggleSelectionAndNotify(target)
    }

    private fun exitLibrarySelectionMode() {
        if (!isLibrarySelectionMode) return
        isLibrarySelectionMode = false
        folderAdapter.setSelectionMode(false)
        binding.librarySelectionActions.visibility = View.GONE
        uiCallbacks.clearFolderStatus()
    }

    private fun updateLibrarySelectionActions() {
        if (!isLibrarySelectionMode || _binding == null) return
        val count = folderAdapter.selectedCount()
        uiCallbacks.setFolderStatus(getString(R.string.library_selection_count, count))
        binding.librarySelectAll.text = getString(
            if (folderAdapter.areAllSelected()) R.string.clear_all else R.string.select_all
        )
        binding.libraryRenameSelected.visibility = if (count == 1) View.VISIBLE else View.GONE
    }

    private fun applyTranslationActionsEnabled(enabled: Boolean) {
        val binding = _binding ?: return
        binding.folderTranslate.isEnabled = enabled
        binding.folderImportChapters.isEnabled = enabled
        binding.folderExportCollection.isEnabled = enabled
        binding.folderTranslateCollection.isEnabled = enabled
        binding.folderCollectionAddChapter.isEnabled = enabled
        if (isLibrarySelectionMode) {
            binding.librarySelectAll.isEnabled = enabled
            binding.libraryTranslateSelected.isEnabled = enabled
            binding.libraryDeleteSelected.isEnabled = enabled
            binding.libraryCancelSelection.isEnabled = enabled
        }
    }

    private fun toggleSelectAllLibraryFolders() {
        if (!isLibrarySelectionMode) return
        if (folderAdapter.areAllSelected()) {
            folderAdapter.clearSelection()
        } else {
            folderAdapter.selectAll()
        }
    }

    private fun translateSelectedLibraryFolders() {
        val selected = folderAdapter.getSelectedFolders()
        if (selected.isEmpty()) {
            uiCallbacks.setFolderStatus(getString(R.string.translate_folders_empty))
            return
        }
        val tasks = selected.flatMap { buildTranslationTasksForFolder(it, force = false) }
        _binding?.librarySelectAll?.isEnabled = false
        _binding?.libraryTranslateSelected?.isEnabled = false
        _binding?.libraryDeleteSelected?.isEnabled = false
        _binding?.libraryCancelSelection?.isEnabled = false
        TranslationKeepAliveService.startTranslationTask(
            requireContext(),
            TranslationTaskPersistence.fromBatch(tasks)
        )
    }

    private fun confirmDeleteSelectedLibraryFolders() {
        val selected = folderAdapter.getSelectedFolders()
        if (selected.isEmpty()) {
            uiCallbacks.setFolderStatus(getString(R.string.folder_delete_empty))
            return
        }
        dialogs.confirmDeleteSelectedLibraryFolders(requireContext(), selected.size) {
            var failed = false
            selected.forEach { folder ->
                if (!repository.deleteFolder(folder)) {
                    failed = true
                }
            }
            if (failed) {
                AppLogger.log("Library", "Delete selected root folders failed")
                Toast.makeText(requireContext(), R.string.delete_folders_failed, Toast.LENGTH_SHORT).show()
            } else {
                AppLogger.log("Library", "Deleted ${selected.size} root folders")
            }
            exitLibrarySelectionMode()
            refreshFolderViewsAfterBatchMutation(selected)
        }
    }

    private fun renameSelectedLibraryFolder() {
        val selected = folderAdapter.getSelectedFolders()
        if (selected.size != 1) return
        exitLibrarySelectionMode()
        showRenameFolderDialog(selected.first())
    }

    private fun handleDeleteSelectedClick() {
        if (isChapterSelectionMode) {
            confirmDeleteSelectedChapters()
            return
        }
        selectionController.confirmDeleteSelectedImages(currentFolder)
    }

    private fun exitActiveSelectionMode() {
        if (isChapterSelectionMode) {
            exitChapterSelectionMode()
        }
        selectionController.exitSelectionMode()
    }

    private fun enterChapterSelectionMode(target: File) {
        if (!isChapterSelectionMode) {
            isChapterSelectionMode = true
            selectionController.exitSelectionMode()
            chapterAdapter.setSelectionMode(true)
            binding.folderSelectionActions.visibility = View.VISIBLE
            binding.folderRetranslateSelected.visibility = View.GONE
        }
        chapterAdapter.toggleSelectionAndNotify(target)
    }

    private fun exitChapterSelectionMode() {
        if (!isChapterSelectionMode) return
        isChapterSelectionMode = false
        chapterAdapter.setSelectionMode(false)
        binding.folderSelectionActions.visibility = View.GONE
        binding.folderRenameSelected.visibility = View.GONE
        binding.folderRetranslateSelected.visibility = View.GONE
        uiCallbacks.clearFolderStatus()
    }

    private fun updateChapterSelectionActions() {
        if (!isChapterSelectionMode) return
        val count = chapterAdapter.selectedCount()
        uiCallbacks.setFolderStatus(getString(R.string.chapter_selection_count, count))
        binding.folderSelectAll.text = getString(
            if (chapterAdapter.areAllSelected()) R.string.clear_all else R.string.select_all
        )
        binding.folderRenameSelected.visibility = if (count == 1) View.VISIBLE else View.GONE
    }

    private fun confirmDeleteSelectedChapters() {
        val folder = currentFolder ?: return
        val selected = chapterAdapter.getSelectedFolders()
        if (selected.isEmpty()) {
            uiCallbacks.setFolderStatus(getString(R.string.delete_chapters_empty))
            return
        }
        dialogs.confirmDeleteSelectedFolders(requireContext(), selected.size) {
            var failed = false
            selected.forEach { child ->
                if (!repository.deleteFolder(child)) {
                    failed = true
                }
            }
            if (failed) {
                AppLogger.log("Library", "Delete selected chapters failed in ${folder.name}")
                Toast.makeText(requireContext(), R.string.delete_chapters_failed, Toast.LENGTH_SHORT).show()
            } else {
                AppLogger.log("Library", "Deleted ${selected.size} chapters from ${folder.name}")
            }
            exitChapterSelectionMode()
            loadImages(folder)
            loadFolders()
        }
    }

    private fun renameSelectedChapter() {
        val selected = chapterAdapter.getSelectedFolders()
        if (selected.size != 1) return
        exitChapterSelectionMode()
        showRenameFolderDialog(selected.first())
    }

}
