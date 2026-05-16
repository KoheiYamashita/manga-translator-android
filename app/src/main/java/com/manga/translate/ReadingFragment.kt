package com.manga.translate

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.util.TypedValue
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.manga.translate.databinding.FragmentReadingBinding
import com.manga.translate.di.appContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class ReadingFragment : Fragment() {
    private fun formatInt(value: Int): String = String.format(Locale.getDefault(), "%d", value)

    private fun resolveColorAttr(attrRes: Int): Int {
        val typedValue = android.util.TypedValue()
        requireContext().theme.resolveAttribute(attrRes, typedValue, true)
        return if (typedValue.resourceId != 0) {
            ContextCompat.getColor(requireContext(), typedValue.resourceId)
        } else {
            typedValue.data
        }
    }

    private var _binding: FragmentReadingBinding? = null
    private val binding get() = _binding!!
    private val readingSessionViewModel: ReadingSessionViewModel by activityViewModels()
    private val appContainer by lazy(LazyThreadSafetyMode.NONE) { requireContext().appContainer }
    private val dialogs = LibraryDialogs()
    private val translationPipeline by lazy(LazyThreadSafetyMode.NONE) {
        appContainer.createTranslationPipeline()
    }
    private val translationStore by lazy(LazyThreadSafetyMode.NONE) { appContainer.translationStore }
    private val settingsStore by lazy(LazyThreadSafetyMode.NONE) { appContainer.settingsStore }
    private val preferencesGateway by lazy(LazyThreadSafetyMode.NONE) {
        LibraryPreferencesGateway(
            context = requireContext().applicationContext,
            prefs = appContainer.libraryPrefs,
            repository = appContainer.libraryRepository
        )
    }
    private val readingProgressStore by lazy(LazyThreadSafetyMode.NONE) {
        appContainer.readingProgressStore
    }
    private var currentImageFile: java.io.File? = null
    private var currentTranslation: TranslationResult? = null
    private var translationWatchJob: Job? = null
    private var webtoonTranslationWatchJob: Job? = null
    private var currentBitmap: Bitmap? = null
    private var currentImageWidth: Int = 0
    private var currentImageHeight: Int = 0
    private lateinit var imageTransformController: ReadingImageTransformController
    private var readingDisplayMode = ReadingDisplayMode.FIT_WIDTH
    private var folderReadingMode = FolderReadingMode.STANDARD
    private var isEditMode = false
    private var resizeTargetId: Int? = null
    private var resizeBaseRect: RectF? = null
    private var resizeUpdatingWidthInput = false
    private var resizeUpdatingHeightInput = false
    private var resizeUpdatingWidthSlider = false
    private var resizeUpdatingHeightSlider = false
    private var resizeWidthPercent = 100
    private var resizeHeightPercent = 100
    private val resizeMinPercent = 50
    private val resizeMaxPercent = 500
    private val glossaryStore by lazy(LazyThreadSafetyMode.NONE) { appContainer.glossaryStore }
    private lateinit var emptyBubbleCoordinator: ReadingEmptyBubbleCoordinator
    private var emptyBubbleJob: Job? = null
    private var activeEmptyBubbleModelErrorDialog: AlertDialog? = null
    private lateinit var webtoonAdapter: WebtoonReadingAdapter
    private lateinit var webtoonLayoutManager: LockedWebtoonLinearLayoutManager
    private var webtoonProgrammaticScroll = false
    private var webtoonLockedPageIndex: Int? = null
    private var webtoonLockedPagePath: String? = null
    private val webtoonEditOffsets = mutableMapOf<Int, Pair<Float, Float>>()
    private var webtoonPreparingEdit = false
    private var activeWebtoonZoomHolder: WebtoonReadingAdapter.WebtoonPageViewHolder? = null
    private var webtoonTouchHolder: WebtoonReadingAdapter.WebtoonPageViewHolder? = null
    private var displayedPageIndex: Int? = null
    private var displayedImagePath: String? = null
    private var pageTransitionGeneration: Int = 0
    private val pageTransitionInterpolator = FastOutSlowInInterpolator()
    private val incomingPageParallaxDp = 28f

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReadingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        emptyBubbleCoordinator = appContainer.createReadingEmptyBubbleCoordinator()
        webtoonLayoutManager = LockedWebtoonLinearLayoutManager(requireContext())
        webtoonLayoutManager.initialPrefetchItemCount = 3
        webtoonAdapter = WebtoonReadingAdapter(
            scope = viewLifecycleOwner.lifecycleScope,
            loadTranslation = ::loadValidTranslationForCurrentFolder
        )
        webtoonAdapter.onLockedBubbleOffsetChanged = offsetChanged@{ bubbleId, offsetX, offsetY ->
            if (!isWebtoonEditSessionActive()) return@offsetChanged
            webtoonEditOffsets[bubbleId] = offsetX to offsetY
        }
        webtoonAdapter.onLockedBubbleRemove = { bubbleId ->
            handleBubbleRemove(bubbleId)
        }
        webtoonAdapter.onLockedBubbleTap = { bubbleId ->
            handleBubbleEdit(bubbleId)
        }
        webtoonAdapter.onLockedBubbleResizeTap = { bubbleId ->
            showResizePanel(bubbleId)
        }
        webtoonAdapter.onLockedBubbleLongPress = { bubbleId ->
            showBubbleActionDialog(bubbleId)
        }
        binding.readingWebtoonList.layoutManager = webtoonLayoutManager
        binding.readingWebtoonList.adapter = webtoonAdapter
        binding.readingWebtoonList.setItemViewCacheSize(4)
        binding.readingWebtoonList.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent): Boolean {
                if (folderReadingMode != FolderReadingMode.WEBTOON_SCROLL || isEditMode) return false
                val target = webtoonTouchHolder ?: findWebtoonTouchHolder(rv, event)
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    webtoonTouchHolder = target
                }
                val handled = target?.let { dispatchWebtoonTouch(it, event) } == true
                if (handled) {
                    webtoonTouchHolder = target
                } else if (
                    event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    webtoonTouchHolder = null
                }
                syncActiveWebtoonZoomHolder(target)
                return handled
            }

            override fun onTouchEvent(rv: RecyclerView, event: MotionEvent) {
                val target = webtoonTouchHolder ?: return
                dispatchWebtoonTouch(target, event)
                syncActiveWebtoonZoomHolder(target)
                if (
                    event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    webtoonTouchHolder = null
                }
            }

            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit
        })
        binding.readingWebtoonList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (folderReadingMode != FolderReadingMode.WEBTOON_SCROLL) return
                updateWebtoonPageInfo()
                if (!webtoonProgrammaticScroll) {
                    persistWebtoonProgress()
                }
            }
        })
        readingDisplayMode = settingsStore.loadReadingDisplayMode()
        folderReadingMode = readingSessionViewModel.readingMode.value ?: FolderReadingMode.STANDARD
        imageTransformController = ReadingImageTransformController(
            context = requireContext(),
            imageView = binding.readingImage,
            hasBubbleAt = { x, y -> binding.translationOverlay.hasBubbleAt(x, y) },
            onMatrixUpdated = { updateOverlayDisplayRect() }
        )
        binding.translationOverlay.onTap = { x ->
            handleTap(x)
        }
        binding.translationOverlay.onDoubleTap = { x, y ->
            handleDoubleTap(x, y)
        }
        binding.translationOverlay.onSwipe = { direction ->
            handleSwipe(direction)
        }
        binding.translationOverlay.onTransformTouch = { event ->
            imageTransformController.handleTouch(event)
        }
        binding.translationOverlay.onBubbleRemove = { bubbleId ->
            handleBubbleRemove(bubbleId)
        }
        binding.translationOverlay.onBubbleTap = { bubbleId ->
            handleBubbleEdit(bubbleId)
        }
        binding.translationOverlay.onBubbleResizeTap = { bubbleId ->
            showResizePanel(bubbleId)
        }
        binding.translationOverlay.onBubbleLongPress = { bubbleId ->
            showBubbleActionDialog(bubbleId)
        }
        binding.readingEditButton.setOnClickListener {
            toggleEditMode()
        }
        binding.readingAddButton.setOnClickListener {
            addNewBubble()
        }
        binding.readingClearButton.setOnClickListener {
            clearAllBubbles()
        }
        binding.readingResizeWidthSlider.max = resizeMaxPercent - resizeMinPercent
        binding.readingResizeHeightSlider.max = resizeMaxPercent - resizeMinPercent
        bindResizeControls(
            slider = binding.readingResizeWidthSlider,
            input = binding.readingResizeWidthInput,
            isInputUpdating = { resizeUpdatingWidthInput },
            setInputUpdating = { resizeUpdatingWidthInput = it },
            isSliderUpdating = { resizeUpdatingWidthSlider },
            setSliderUpdating = { resizeUpdatingWidthSlider = it },
            getPercent = { resizeWidthPercent },
            setPercent = { resizeWidthPercent = it }
        )
        bindResizeControls(
            slider = binding.readingResizeHeightSlider,
            input = binding.readingResizeHeightInput,
            isInputUpdating = { resizeUpdatingHeightInput },
            setInputUpdating = { resizeUpdatingHeightInput = it },
            isSliderUpdating = { resizeUpdatingHeightSlider },
            setSliderUpdating = { resizeUpdatingHeightSlider = it },
            getPercent = { resizeHeightPercent },
            setPercent = { resizeHeightPercent = it }
        )
        binding.readingResizeConfirm.setOnClickListener {
            saveCurrentTranslation()
            hideResizePanel()
        }
        updateEditButtonState()
        applyNormalBubbleRenderSettings()
        applyTextLayoutSetting()
        readingSessionViewModel.images.observe(viewLifecycleOwner) {
            reloadReadingContent()
        }
        readingSessionViewModel.index.observe(viewLifecycleOwner) {
            if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) {
                loadWebtoonReading()
            } else {
                loadCurrentImage()
                persistReadingProgress()
            }
        }
        readingSessionViewModel.readingMode.observe(viewLifecycleOwner) { mode ->
            val previousMode = folderReadingMode
            if (previousMode != mode && isEditMode) {
                setEditMode(false)
            }
            folderReadingMode = mode
            if (mode != FolderReadingMode.WEBTOON_SCROLL) {
                clearWebtoonEditSession()
            }
            applyFolderReadingMode()
            reloadReadingContent()
        }
        binding.readingImage.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (currentBitmap == null) return@addOnLayoutChangeListener
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) {
                    updateReadingContentLayout(currentBitmap)
                    updateOverlay(currentTranslation, currentBitmap)
                } else {
                    imageTransformController.reset(currentBitmap!!, readingDisplayMode)
                }
            }
        }
        applyFolderReadingMode()
    }

    override fun onResume() {
        super.onResume()
        applyNormalBubbleRenderSettings()
        applyTextLayoutSetting()
        applyFolderReadingMode()
        applyReadingDisplayMode()
        (activity as? MainActivity)?.setPagerSwipeEnabled(false)
    }

    override fun onPause() {
        super.onPause()
        (activity as? MainActivity)?.setPagerSwipeEnabled(true)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        translationWatchJob?.cancel()
        webtoonTranslationWatchJob?.cancel()
        emptyBubbleJob?.cancel()
        activeEmptyBubbleModelErrorDialog?.dismiss()
        activeEmptyBubbleModelErrorDialog = null
        cancelPageTransition()
        clearWebtoonEditSession(resetCurrentPage = true)
        binding.readingWebtoonList.adapter = null
        _binding = null
    }

    private fun reloadReadingContent() {
        if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) {
            loadWebtoonReading()
        } else {
            loadCurrentImage()
        }
    }

    private fun loadCurrentImage() {
        stopWebtoonTranslationWatcher()
        val images = readingSessionViewModel.images.value.orEmpty()
        val folder = readingSessionViewModel.currentFolder.value
        folderReadingMode = readingSessionViewModel.readingMode.value ?: FolderReadingMode.STANDARD
        if (images.isEmpty() || folder == null) {
            binding.readingEmptyHint.visibility = View.VISIBLE
            binding.readingPageInfo.visibility = View.GONE
            binding.translationOverlay.visibility = View.GONE
            binding.readingEditControls.visibility = View.GONE
            hideResizePanel()
            binding.readingImage.setImageDrawable(null)
            displayedImagePath = null
            displayedPageIndex = null
            currentBitmap = null
            currentImageWidth = 0
            currentImageHeight = 0
            imageTransformController.setCurrentBitmap(null)
            finishPageTransitionImmediately()
            binding.readingScrollContainer.scrollTo(0, 0)
            return
        }
        val index = (readingSessionViewModel.index.value ?: 0).coerceIn(0, images.lastIndex)
        val imageFile = images[index]
        currentImageFile = imageFile
        val previousBitmap = currentBitmap
        val previousDisplayedPath = displayedImagePath
        val previousDisplayedIndex = displayedPageIndex
        val previousPageSnapshot = captureCurrentPageSnapshot()
        binding.readingEmptyHint.visibility = View.GONE
        binding.readingPageInfo.visibility = View.VISIBLE
        updateEditButtonState()
        hideResizePanel()
        binding.readingPageInfo.text = getString(
            R.string.reading_page_info,
            folder.name,
            index + 1,
            images.size
        )
        val targetPath = imageFile.absolutePath
        val targetIndex = index
        viewLifecycleOwner.lifecycleScope.launch {
            val decoded = loadBitmap(imageFile)
            val bitmap = decoded?.bitmap
            val translation = withContext(Dispatchers.IO) {
                loadValidTranslationForCurrentFolder(imageFile)
            }
            val currentImages = readingSessionViewModel.images.value.orEmpty()
            val currentIndex = readingSessionViewModel.index.value ?: 0
            if (
                currentIndex != targetIndex ||
                currentImages.getOrNull(currentIndex)?.absolutePath != targetPath
            ) {
                return@launch
            }
            val shouldAnimate = bitmap != null &&
                previousBitmap != null &&
                previousPageSnapshot != null &&
                previousDisplayedPath != null &&
                previousDisplayedPath != targetPath &&
                folderReadingMode != FolderReadingMode.WEBTOON_SCROLL
            val direction = if ((previousDisplayedIndex ?: targetIndex) < targetIndex) -1 else 1
            binding.readingImage.translationX = 0f
            if (bitmap != null) {
                binding.readingImage.setImageBitmap(bitmap)
                currentBitmap = bitmap
                currentImageWidth = decoded.sourceWidth
                currentImageHeight = decoded.sourceHeight
                imageTransformController.setCurrentBitmap(bitmap)
                displayedImagePath = targetPath
                displayedPageIndex = targetIndex
            } else {
                binding.readingImage.setImageDrawable(null)
                currentBitmap = null
                currentImageWidth = 0
                currentImageHeight = 0
                imageTransformController.setCurrentBitmap(null)
                displayedImagePath = null
                displayedPageIndex = null
            }
            binding.readingScrollContainer.scrollTo(0, 0)
            binding.readingImage.post {
                if (bitmap != null) {
                    readingDisplayMode = settingsStore.loadReadingDisplayMode()
                    updateReadingContentLayout(bitmap)
                    if (folderReadingMode != FolderReadingMode.WEBTOON_SCROLL) {
                        binding.readingContentContainer.doOnLayout {
                            if (!isAdded || _binding == null || currentBitmap !== bitmap) return@doOnLayout
                            binding.readingScrollContainer.scrollTo(0, 0)
                            imageTransformController.reset(bitmap, readingDisplayMode)
                            updateOverlay(translation, bitmap)
                            if (shouldAnimate) {
                                startPageTransition(previousPageSnapshot, direction)
                            } else {
                                finishPageTransitionImmediately()
                            }
                        }
                        return@post
                    }
                }
                updateOverlay(translation, bitmap)
                if (shouldAnimate) {
                    startPageTransition(previousPageSnapshot, direction)
                } else {
                    finishPageTransitionImmediately()
                }
            }
            if (translation == null && bitmap != null) {
                startTranslationWatcher(imageFile)
            } else {
                translationWatchJob?.cancel()
            }
        }
    }

    private fun loadWebtoonReading() {
        val images = readingSessionViewModel.images.value.orEmpty()
        val folder = readingSessionViewModel.currentFolder.value
        folderReadingMode = readingSessionViewModel.readingMode.value ?: FolderReadingMode.STANDARD
        translationWatchJob?.cancel()
        stopWebtoonTranslationWatcher(clearCache = false)
        emptyBubbleJob?.cancel()
        hideResizePanel()
        currentImageFile = null
        currentTranslation = null
        currentBitmap = null
        currentImageWidth = 0
        currentImageHeight = 0
        displayedPageIndex = null
        displayedImagePath = null
        imageTransformController.setCurrentBitmap(null)
        finishPageTransitionImmediately()
        if (images.isEmpty() || folder == null) {
            binding.readingEmptyHint.visibility = View.VISIBLE
            binding.readingPageInfo.visibility = View.GONE
            updateEditButtonState()
            val bubbleRenderSettings = settingsStore.loadNormalBubbleRenderSettings()
            webtoonAdapter.submit(
                images = emptyList(),
                verticalLayoutEnabled = !bubbleRenderSettings.useHorizontalText,
                bubbleRenderSettings = bubbleRenderSettings
            )
            syncWebtoonEditSession()
            stopWebtoonTranslationWatcher()
            return
        }
        binding.readingEmptyHint.visibility = View.GONE
        binding.readingPageInfo.visibility = View.VISIBLE
        updateEditButtonState()
        val bubbleRenderSettings = settingsStore.loadNormalBubbleRenderSettings()
        webtoonAdapter.submit(
            images = images,
            verticalLayoutEnabled = !bubbleRenderSettings.useHorizontalText,
            bubbleRenderSettings = bubbleRenderSettings
        )
        syncWebtoonEditSession()
        val targetIndex = (readingSessionViewModel.index.value ?: 0).coerceIn(0, images.lastIndex)
        webtoonProgrammaticScroll = true
        binding.readingWebtoonList.post {
            if (!isAdded || _binding == null || folderReadingMode != FolderReadingMode.WEBTOON_SCROLL) {
                webtoonProgrammaticScroll = false
                return@post
            }
            webtoonLayoutManager.scrollToPositionWithOffset(targetIndex, 0)
            updateWebtoonPageInfo()
            persistWebtoonProgress()
            startWebtoonTranslationWatcher()
            binding.readingWebtoonList.post {
                webtoonProgrammaticScroll = false
            }
        }
    }

    private fun isWebtoonEditSessionActive(): Boolean {
        return folderReadingMode == FolderReadingMode.WEBTOON_SCROLL &&
            isEditMode &&
            webtoonLockedPageIndex != null &&
            webtoonLockedPagePath != null
    }

    private fun syncWebtoonEditSession() {
        val active = isWebtoonEditSessionActive()
        webtoonLayoutManager.setLockedPosition(if (active) webtoonLockedPageIndex else null)
        webtoonAdapter.updateEditSession(
            enabled = active,
            lockedImagePath = if (active) webtoonLockedPagePath else null,
            translation = if (active) currentTranslation else null,
            offsets = if (active) webtoonEditOffsets else emptyMap()
        )
    }

    private fun clearWebtoonEditSession(resetCurrentPage: Boolean = false) {
        webtoonPreparingEdit = false
        webtoonLockedPageIndex = null
        webtoonLockedPagePath = null
        webtoonEditOffsets.clear()
        syncWebtoonEditSession()
        if (resetCurrentPage) {
            currentImageFile = null
            currentTranslation = null
        }
    }

    private fun renderCurrentTranslation() {
        if (isWebtoonEditSessionActive()) {
            syncWebtoonEditSession()
        } else {
            binding.translationOverlay.setTranslations(currentTranslation)
        }
    }

    private fun currentOverlayOffsets(): MutableMap<Int, Pair<Float, Float>> {
        return if (isWebtoonEditSessionActive()) {
            webtoonEditOffsets.toMutableMap()
        } else {
            binding.translationOverlay.getOffsets().toMutableMap()
        }
    }

    private fun applyOverlayOffsets(offsets: Map<Int, Pair<Float, Float>>) {
        if (isWebtoonEditSessionActive()) {
            webtoonEditOffsets.clear()
            webtoonEditOffsets.putAll(offsets)
            syncWebtoonEditSession()
        } else {
            binding.translationOverlay.setOffsets(offsets)
        }
    }

    private fun resolveLockedWebtoonIndex(): Int? {
        val firstVisible = webtoonLayoutManager.findFirstVisibleItemPosition()
        if (firstVisible != RecyclerView.NO_POSITION) {
            return firstVisible
        }
        val images = readingSessionViewModel.images.value.orEmpty()
        if (images.isEmpty()) return null
        return (readingSessionViewModel.index.value ?: 0).coerceIn(0, images.lastIndex)
    }

    private suspend fun prepareWebtoonEditSession(index: Int): Boolean {
        val images = readingSessionViewModel.images.value.orEmpty()
        val imageFile = images.getOrNull(index) ?: return false
        val snapshot = webtoonAdapter.findBoundPageSnapshot(imageFile.absolutePath)
        val translation = snapshot?.translation ?: withContext(Dispatchers.IO) {
            loadValidTranslationForCurrentFolder(imageFile)
        }
        val bounds = readImageBounds(imageFile)
        val width = when {
            translation != null && translation.width > 0 -> translation.width
            snapshot != null && snapshot.sourceWidth > 0 -> snapshot.sourceWidth
            else -> bounds.first
        }
        val height = when {
            translation != null && translation.height > 0 -> translation.height
            snapshot != null && snapshot.sourceHeight > 0 -> snapshot.sourceHeight
            else -> bounds.second
        }
        if (width <= 0 || height <= 0) return false
        currentImageFile = imageFile
        currentTranslation = when {
            translation == null -> TranslationResult(imageFile.name, width, height, emptyList())
            translation.width == width && translation.height == height -> translation
            else -> translation.copy(width = width, height = height)
        }
        webtoonLockedPageIndex = index
        webtoonLockedPagePath = imageFile.absolutePath
        webtoonEditOffsets.clear()
        return true
    }

    private fun readImageBounds(imageFile: java.io.File): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(imageFile.absolutePath, options)
        return options.outWidth to options.outHeight
    }

    private fun updateOverlay(translation: TranslationResult?, bitmap: Bitmap?) {
        val rect = computeOverlayDisplayRect() ?: run {
            binding.translationOverlay.visibility = View.GONE
            return
        }
        val resolvedWidth = when {
            translation != null && translation.width > 0 -> translation.width
            currentImageWidth > 0 -> currentImageWidth
            else -> bitmap?.width ?: 0
        }
        val resolvedHeight = when {
            translation != null && translation.height > 0 -> translation.height
            currentImageHeight > 0 -> currentImageHeight
            else -> bitmap?.height ?: 0
        }
        if (resolvedWidth <= 0 || resolvedHeight <= 0) {
            binding.translationOverlay.visibility = View.GONE
            return
        }
        val normalized = when {
            translation == null -> TranslationResult("", resolvedWidth, resolvedHeight, emptyList())
            translation.width == resolvedWidth && translation.height == resolvedHeight -> translation
            else -> translation.copy(width = resolvedWidth, height = resolvedHeight)
        }
        currentTranslation = normalized
        binding.translationOverlay.setDisplayRect(rect)
        binding.translationOverlay.setContentZoomScale(imageTransformController.currentContentZoomScale())
        binding.translationOverlay.setTranslations(normalized)
        binding.translationOverlay.setOffsets(emptyMap())
        binding.translationOverlay.setEditMode(isEditMode)
        binding.translationOverlay.visibility = View.VISIBLE
    }

    private fun currentReadingPageAnimationMode(): ReadingPageAnimationMode {
        return settingsStore.loadReadingPageAnimationMode()
    }

    private fun startPageTransition(previousSnapshot: Bitmap, direction: Int) {
        if (currentReadingPageAnimationMode() != ReadingPageAnimationMode.HORIZONTAL_SLIDE) {
            finishPageTransitionImmediately()
            return
        }
        val width = binding.readingContentContainer.width
        if (width <= 0) {
            finishPageTransitionImmediately()
            return
        }
        cancelPageTransition()
        val generation = ++pageTransitionGeneration
        val parallaxOffset = resolveIncomingPageParallaxOffset(direction)
        binding.readingTransitionImage.setImageBitmap(previousSnapshot)
        binding.readingTransitionImage.scaleType = android.widget.ImageView.ScaleType.FIT_XY
        binding.readingTransitionImage.imageMatrix = Matrix()
        binding.readingTransitionImage.visibility = View.VISIBLE
        binding.readingTransitionImage.translationX = 0f
        binding.readingTransitionImage.alpha = 1f
        binding.translationOverlay.visibility = View.INVISIBLE
        binding.readingImage.translationX = parallaxOffset
        binding.readingImage.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        binding.readingTransitionImage.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        binding.readingImage.animate()
            .translationX(0f)
            .setDuration(260L)
            .setInterpolator(pageTransitionInterpolator)
            .setListener(null)
            .start()
        binding.readingTransitionImage.animate()
            .translationX(direction * width.toFloat())
            .setDuration(260L)
            .setInterpolator(pageTransitionInterpolator)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    completePageTransition(generation)
                }

                override fun onAnimationCancel(animation: Animator) {
                    completePageTransition(generation)
                }
            })
            .start()
    }

    private fun completePageTransition(generation: Int) {
        if (_binding == null || generation != pageTransitionGeneration) return
        finishPageTransitionImmediately()
        if (currentBitmap != null) {
            updateOverlay(currentTranslation, currentBitmap)
        }
    }

    private fun cancelPageTransition() {
        pageTransitionGeneration += 1
        if (_binding == null) return
        binding.readingImage.animate().cancel()
        binding.readingTransitionImage.animate().cancel()
    }

    private fun finishPageTransitionImmediately() {
        if (_binding == null) return
        binding.readingImage.animate().setListener(null)
        binding.readingTransitionImage.animate().setListener(null)
        binding.readingImage.translationX = 0f
        binding.readingImage.alpha = 1f
        binding.readingTransitionImage.translationX = 0f
        binding.readingTransitionImage.alpha = 1f
        binding.readingTransitionImage.visibility = View.GONE
        binding.readingImage.setLayerType(View.LAYER_TYPE_NONE, null)
        binding.readingTransitionImage.setLayerType(View.LAYER_TYPE_NONE, null)
        binding.readingTransitionImage.post {
            if (_binding == null) return@post
            if (binding.readingTransitionImage.visibility == View.GONE) {
                binding.readingTransitionImage.setImageDrawable(null)
            }
        }
    }

    private fun resolveIncomingPageParallaxOffset(direction: Int): Float {
        val px = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            incomingPageParallaxDp,
            resources.displayMetrics
        )
        return (-direction) * px
    }

    private fun captureReadingImageMatrix(): Matrix? {
        val drawable = binding.readingImage.drawable ?: return null
        if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) return null
        return Matrix(binding.readingImage.imageMatrix)
    }

    private fun captureCurrentPageSnapshot(): Bitmap? {
        val width = binding.readingContentContainer.width
        val height = binding.readingContentContainer.height
        if (width <= 0 || height <= 0) return null
        return try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                val canvas = Canvas(bitmap)
                binding.readingImage.draw(canvas)
            }
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private fun updateOverlayDisplayRect() {
        if (binding.translationOverlay.visibility != View.VISIBLE) return
        val rect = computeOverlayDisplayRect() ?: return
        binding.translationOverlay.setDisplayRect(rect)
        binding.translationOverlay.setContentZoomScale(imageTransformController.currentContentZoomScale())
    }

    private fun applyReadingDisplayMode() {
        if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) return
        val mode = settingsStore.loadReadingDisplayMode()
        if (mode == readingDisplayMode) return
        readingDisplayMode = mode
        val bitmap = currentBitmap ?: return
        imageTransformController.reset(bitmap, readingDisplayMode)
        updateOverlay(currentTranslation, bitmap)
    }

    private suspend fun loadBitmap(imageFile: java.io.File): DecodedReadingBitmap? = withContext(Dispatchers.IO) {
        val width = binding.readingImage.width
            .takeIf { it > 0 }
            ?: binding.readingRoot.width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val height = binding.readingImage.height
            .takeIf { it > 0 }
            ?: binding.readingRoot.height.takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels
        ReadingBitmapDecoder.decode(imageFile, width, height)
    }

    private fun handleTap(x: Float) {
        if (isEditMode) return
        if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) return
        if (imageTransformController.isZoomed()) return
        val width = binding.readingRoot.width
        if (width <= 0) return
        val ratio = x / width
        when {
            ratio < 0.33f -> {
                persistCurrentTranslation()
                readingSessionViewModel.prev()
            }
            ratio > 0.67f -> {
                persistCurrentTranslation()
                readingSessionViewModel.next()
            }
        }
    }

    private fun handleSwipe(direction: Int) {
        if (isEditMode) return
        if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) return
        if (imageTransformController.isZoomed()) return
        if (direction == 0) return
        persistCurrentTranslation()
        if (direction > 0) {
            readingSessionViewModel.prev()
        } else {
            readingSessionViewModel.next()
        }
    }

    private fun handleDoubleTap(x: Float, y: Float) {
        if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) return
        imageTransformController.toggleDoubleTapZoom(x, y)
    }

    private fun applyTextLayoutSetting() {
        val useHorizontal = settingsStore.loadNormalBubbleRenderSettings().useHorizontalText
        binding.translationOverlay.setVerticalLayoutEnabled(!useHorizontal)
        if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) {
            refreshWebtoonAdapterPresentation()
        }
    }

    private fun applyNormalBubbleRenderSettings() {
        binding.translationOverlay.setNormalBubbleRenderSettings(
            settingsStore.loadNormalBubbleRenderSettings()
        )
        if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) {
            refreshWebtoonAdapterPresentation()
        }
    }

    private fun toggleEditMode() {
        if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) {
            if (webtoonPreparingEdit) return
            if (isEditMode) {
                persistCurrentTranslation(forceSave = true)
                setEditMode(false)
                processEmptyBubbles()
                clearWebtoonEditSession()
            } else {
                viewLifecycleOwner.lifecycleScope.launch {
                    webtoonPreparingEdit = true
                    try {
                        val targetIndex = resolveLockedWebtoonIndex() ?: return@launch
                        val prepared = prepareWebtoonEditSession(targetIndex)
                        if (!prepared || !isAdded || _binding == null) return@launch
                        setEditMode(true)
                        webtoonProgrammaticScroll = true
                        binding.readingWebtoonList.post {
                            if (!isAdded || _binding == null || !isWebtoonEditSessionActive()) {
                                webtoonProgrammaticScroll = false
                                return@post
                            }
                            webtoonLayoutManager.scrollToPositionWithOffset(targetIndex, 0)
                            updateWebtoonPageInfo()
                            persistWebtoonProgress()
                            binding.readingWebtoonList.post {
                                webtoonProgrammaticScroll = false
                            }
                        }
                    } finally {
                        webtoonPreparingEdit = false
                    }
                }
            }
            return
        }
        if (isEditMode) {
            persistCurrentTranslation(forceSave = true)
            setEditMode(false)
            processEmptyBubbles()
        } else {
            setEditMode(true)
        }
    }

    private fun setEditMode(enabled: Boolean) {
        val nextEnabled = enabled
        if (isEditMode == nextEnabled) return
        isEditMode = nextEnabled
        binding.translationOverlay.setEditMode(nextEnabled && folderReadingMode != FolderReadingMode.WEBTOON_SCROLL)
        syncWebtoonEditSession()
        updateReadingInteractionState()
        if (!nextEnabled) {
            hideResizePanel()
        }
        updateEditButtonState()
    }

    private fun updateEditButtonState() {
        val hasImages = readingSessionViewModel.images.value.orEmpty().isNotEmpty()
        if (!hasImages) {
            binding.readingEditControls.visibility = View.GONE
            binding.readingAddButton.visibility = View.GONE
            binding.readingClearButton.visibility = View.GONE
            updateReadingInteractionState()
            return
        }
        binding.readingEditControls.visibility = View.VISIBLE
        val button = binding.readingEditButton
        val density = resources.displayMetrics.density
        if (isEditMode) {
            button.layoutParams = button.layoutParams.apply {
                width = (36f * density).toInt()
                height = (36f * density).toInt()
            }
            button.setPadding(
                (6f * density).toInt(),
                (6f * density).toInt(),
                (6f * density).toInt(),
                (6f * density).toInt()
            )
            button.setImageResource(R.drawable.ic_check)
            button.setColorFilter(0xFF22C55E.toInt())
            button.contentDescription = getString(R.string.reading_confirm_edit)
            binding.readingAddButton.visibility = View.VISIBLE
            binding.readingClearButton.visibility = View.VISIBLE
            binding.readingClearButton.setColorFilter(Color.WHITE)
        } else {
            button.layoutParams = button.layoutParams.apply {
                width = (18f * density).toInt()
                height = (18f * density).toInt()
            }
            button.setPadding(
                (3f * density).toInt(),
                (3f * density).toInt(),
                (3f * density).toInt(),
                (3f * density).toInt()
            )
            button.setImageResource(android.R.drawable.ic_menu_edit)
            button.setColorFilter(Color.WHITE)
            button.contentDescription = getString(R.string.reading_edit_bubbles)
            binding.readingAddButton.visibility = View.GONE
            binding.readingClearButton.visibility = View.GONE
        }
        updateReadingInteractionState()
    }

    private fun applyFolderReadingMode() {
        val isWebtoon = folderReadingMode == FolderReadingMode.WEBTOON_SCROLL
        binding.readingWebtoonList.visibility = if (isWebtoon) View.VISIBLE else View.GONE
        binding.readingScrollContainer.visibility = if (isWebtoon) View.GONE else View.VISIBLE
        binding.readingScrollContainer.scrollEnabled = !isWebtoon && !isEditMode
        binding.readingScrollContainer.isFillViewport = !isWebtoon
        if (isWebtoon) {
            binding.readingWebtoonList.post {
                if (!isAdded || _binding == null || folderReadingMode != FolderReadingMode.WEBTOON_SCROLL) return@post
                startWebtoonTranslationWatcher()
            }
        } else {
            stopWebtoonTranslationWatcher()
        }
        syncWebtoonEditSession()
        updateReadingContentLayout(currentBitmap)
        updateReadingInteractionState()
        updateEditButtonState()
    }

    private fun refreshWebtoonAdapterPresentation() {
        if (!::webtoonAdapter.isInitialized) return
        val images = readingSessionViewModel.images.value.orEmpty()
        webtoonAdapter.submit(
            images = images,
            verticalLayoutEnabled = !settingsStore.loadNormalBubbleRenderSettings().useHorizontalText,
            bubbleRenderSettings = settingsStore.loadNormalBubbleRenderSettings()
        )
        syncWebtoonEditSession()
    }

    private fun updateReadingInteractionState() {
        val isWebtoonScroll = folderReadingMode == FolderReadingMode.WEBTOON_SCROLL && !isEditMode
        binding.readingScrollContainer.scrollEnabled =
            folderReadingMode != FolderReadingMode.WEBTOON_SCROLL && !isEditMode
        binding.translationOverlay.setTouchPassthroughEnabled(isWebtoonScroll)
    }

    private fun updateReadingContentLayout(bitmap: Bitmap?) {
        val contentParams = binding.readingContentContainer.layoutParams as FrameLayout.LayoutParams
        val imageParams = binding.readingImage.layoutParams as FrameLayout.LayoutParams
        val transitionImageParams = binding.readingTransitionImage.layoutParams as FrameLayout.LayoutParams
        val overlayParams = binding.translationOverlay.layoutParams as FrameLayout.LayoutParams
        if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL && bitmap != null) {
            contentParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            imageParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            transitionImageParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            overlayParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            binding.readingImage.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            binding.readingTransitionImage.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            binding.readingImage.adjustViewBounds = true
            binding.readingTransitionImage.adjustViewBounds = true
            binding.readingContentContainer.layoutParams = contentParams
            binding.readingImage.layoutParams = imageParams
            binding.readingTransitionImage.layoutParams = transitionImageParams
            binding.translationOverlay.layoutParams = overlayParams
            binding.readingImage.requestLayout()
            binding.readingImage.doOnLayout {
                val imageHeight = binding.readingImage.height
                if (imageHeight > 0) {
                    updateWebtoonChildHeight(binding.readingContentContainer, imageHeight)
                    updateWebtoonChildHeight(binding.readingTransitionImage, imageHeight)
                    updateWebtoonChildHeight(binding.translationOverlay, imageHeight)
                    updateOverlay(currentTranslation, bitmap)
                }
            }
        } else {
            contentParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            imageParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            transitionImageParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            overlayParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            binding.readingImage.scaleType = android.widget.ImageView.ScaleType.MATRIX
            binding.readingTransitionImage.scaleType = android.widget.ImageView.ScaleType.MATRIX
            binding.readingImage.adjustViewBounds = false
            binding.readingTransitionImage.adjustViewBounds = false
            binding.readingContentContainer.layoutParams = contentParams
            binding.readingImage.layoutParams = imageParams
            binding.readingTransitionImage.layoutParams = transitionImageParams
            binding.translationOverlay.layoutParams = overlayParams
            binding.readingContentContainer.doOnLayout {
                if (!isAdded || _binding == null || folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) {
                    return@doOnLayout
                }
                binding.readingScrollContainer.scrollTo(0, 0)
            }
        }
    }

    private fun updateWebtoonChildHeight(view: View, height: Int) {
        val params = view.layoutParams
        if (params.height == height) return
        params.height = height
        view.layoutParams = params
    }

    private fun computeOverlayDisplayRect(): RectF? {
        return if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) {
            val width = binding.translationOverlay.width.toFloat()
            val height = binding.translationOverlay.height.toFloat()
            if (width <= 0f || height <= 0f) null else RectF(0f, 0f, width, height)
        } else {
            imageTransformController.computeImageDisplayRect()
        }
    }

    private fun bindResizeControls(
        slider: SeekBar,
        input: EditText,
        isInputUpdating: () -> Boolean,
        setInputUpdating: (Boolean) -> Unit,
        isSliderUpdating: () -> Boolean,
        setSliderUpdating: (Boolean) -> Unit,
        getPercent: () -> Int,
        setPercent: (Int) -> Unit
    ) {
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (isSliderUpdating()) return
                val percent = (progress + resizeMinPercent).coerceIn(resizeMinPercent, resizeMaxPercent)
                updateResizeInput(input, percent, isInputUpdating, setInputUpdating)
                setPercent(percent)
                applyResizePercent(resizeWidthPercent, resizeHeightPercent)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                saveCurrentTranslation()
            }
        })
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (isInputUpdating()) return
                val raw = s?.toString().orEmpty()
                val value = raw.toIntOrNull() ?: return
                val clamped = value.coerceIn(resizeMinPercent, resizeMaxPercent)
                if (clamped.toString() != raw) {
                    updateResizeInput(input, clamped, isInputUpdating, setInputUpdating)
                }
                setSliderUpdating(true)
                slider.progress = clamped - resizeMinPercent
                setSliderUpdating(false)
                setPercent(clamped)
                applyResizePercent(resizeWidthPercent, resizeHeightPercent)
                saveCurrentTranslation()
            }
        })
        input.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && getPercent() >= resizeMinPercent) {
                saveCurrentTranslation()
            }
        }
    }

    private fun updateResizeInput(
        input: EditText,
        percent: Int,
        isInputUpdating: () -> Boolean,
        setInputUpdating: (Boolean) -> Unit
    ) {
        if (isInputUpdating()) return
        setInputUpdating(true)
        input.setText(formatInt(percent))
        input.setSelection(input.text?.length ?: 0)
        setInputUpdating(false)
    }

    private fun startWebtoonTranslationWatcher() {
        if (folderReadingMode != FolderReadingMode.WEBTOON_SCROLL) return
        if (webtoonTranslationWatchJob?.isActive == true) return
        refreshVisibleWebtoonTranslations()
        webtoonTranslationWatchJob = viewLifecycleOwner.lifecycleScope.launch {
            translationStore.updates.collect { path ->
                if (!isAdded || _binding == null || folderReadingMode != FolderReadingMode.WEBTOON_SCROLL) {
                    return@collect
                }
                if (isVisibleWebtoonPath(path)) {
                    webtoonAdapter.notifyTranslationChanged(path)
                }
            }
        }
    }

    private fun refreshVisibleWebtoonTranslations() {
        val images = readingSessionViewModel.images.value.orEmpty()
        if (images.isEmpty()) return
        val firstVisible = webtoonLayoutManager.findFirstVisibleItemPosition()
        val lastVisible = webtoonLayoutManager.findLastVisibleItemPosition()
        if (firstVisible == RecyclerView.NO_POSITION || lastVisible == RecyclerView.NO_POSITION) return
        val start = firstVisible.coerceAtLeast(0)
        val end = lastVisible.coerceAtMost(images.lastIndex)
        if (start > end) return
        for (index in start..end) {
            val imageFile = images[index]
            webtoonAdapter.notifyTranslationChanged(imageFile.absolutePath)
        }
    }

    private fun stopWebtoonTranslationWatcher(clearCache: Boolean = true) {
        webtoonTranslationWatchJob?.cancel()
        webtoonTranslationWatchJob = null
    }

    private fun findWebtoonTouchHolder(
        recyclerView: RecyclerView,
        event: MotionEvent
    ): WebtoonReadingAdapter.WebtoonPageViewHolder? {
        val zoomedHolder = activeWebtoonZoomHolder
        if (zoomedHolder != null && zoomedHolder.bindingAdapterPosition != RecyclerView.NO_POSITION) {
            val itemView = zoomedHolder.itemView
            if (
                event.x >= itemView.x &&
                event.x <= itemView.x + itemView.width &&
                event.y >= itemView.y &&
                event.y <= itemView.y + itemView.height
            ) {
                return zoomedHolder
            }
        }
        val child = recyclerView.findChildViewUnder(event.x, event.y) ?: return null
        return recyclerView.getChildViewHolder(child) as? WebtoonReadingAdapter.WebtoonPageViewHolder
    }

    private fun dispatchWebtoonTouch(
        holder: WebtoonReadingAdapter.WebtoonPageViewHolder,
        event: MotionEvent
    ): Boolean {
        val localized = MotionEvent.obtain(event)
        localized.offsetLocation(-holder.itemView.x, -holder.itemView.y)
        return try {
            holder.handleTouchEvent(localized)
        } finally {
            localized.recycle()
        }
    }

    private fun syncActiveWebtoonZoomHolder(
        preferredHolder: WebtoonReadingAdapter.WebtoonPageViewHolder? = null
    ) {
        val preferredZoomed = preferredHolder?.takeIf {
            it.bindingAdapterPosition != RecyclerView.NO_POSITION && it.isZoomed()
        }
        if (preferredZoomed != null) {
            val previous = activeWebtoonZoomHolder
            if (previous != null && previous !== preferredZoomed && previous.isZoomed()) {
                previous.resetZoom()
            }
            activeWebtoonZoomHolder = preferredZoomed
            return
        }
        if (activeWebtoonZoomHolder?.isZoomed() != true) {
            activeWebtoonZoomHolder = null
        }
    }

    private fun isVisibleWebtoonPath(path: String): Boolean {
        val images = readingSessionViewModel.images.value.orEmpty()
        if (images.isEmpty()) return false
        val firstVisible = webtoonLayoutManager.findFirstVisibleItemPosition()
        val lastVisible = webtoonLayoutManager.findLastVisibleItemPosition()
        if (firstVisible == RecyclerView.NO_POSITION || lastVisible == RecyclerView.NO_POSITION) return false
        val start = firstVisible.coerceAtLeast(0)
        val end = lastVisible.coerceAtMost(images.lastIndex)
        if (start > end) return false
        for (index in start..end) {
            if (images[index].absolutePath == path) return true
        }
        return false
    }

    private fun startTranslationWatcher(imageFile: java.io.File) {
        if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) return
        translationWatchJob?.cancel()
        translationWatchJob = viewLifecycleOwner.lifecycleScope.launch {
            reloadCurrentImageTranslation(imageFile)
            translationStore.updates.collect { path ->
                if (path == imageFile.absolutePath) {
                    reloadCurrentImageTranslation(imageFile)
                    return@collect
                }
            }
        }
    }

    private suspend fun reloadCurrentImageTranslation(imageFile: java.io.File) {
        if (currentImageFile?.absolutePath != imageFile.absolutePath) return
        val translation = withContext(Dispatchers.IO) {
            loadValidTranslationForCurrentFolder(imageFile)
        }
        if (currentImageFile?.absolutePath != imageFile.absolutePath) return
        currentTranslation = translation
        binding.readingImage.post {
            updateOverlay(translation, currentBitmap)
        }
    }

    private fun loadValidTranslationForCurrentFolder(imageFile: java.io.File): TranslationResult? {
        val folder = readingSessionViewModel.currentFolder.value ?: return translationStore.load(imageFile)
        return translationPipeline.loadValidTranslation(
            imageFile = imageFile,
            fullTranslate = preferencesGateway.isFullTranslateEnabled(folder),
            useVlDirectTranslate = preferencesGateway.isVlDirectTranslateEnabled(folder),
            language = preferencesGateway.getTranslationLanguage(folder)
        )
    }

    private fun persistReadingProgress() {
        val folder = readingSessionViewModel.currentFolder.value ?: return
        val index = readingSessionViewModel.index.value ?: return
        readingProgressStore.save(folder, index)
    }

    private fun persistWebtoonProgress() {
        val folder = readingSessionViewModel.currentFolder.value ?: return
        val index = webtoonLayoutManager.findFirstVisibleItemPosition()
        if (index == RecyclerView.NO_POSITION) return
        readingProgressStore.save(folder, index)
    }

    private fun updateWebtoonPageInfo() {
        val folder = readingSessionViewModel.currentFolder.value ?: return
        val total = readingSessionViewModel.images.value.orEmpty().size
        if (total == 0) {
            binding.readingPageInfo.visibility = View.GONE
            return
        }
        val firstVisible = webtoonLayoutManager.findFirstVisibleItemPosition()
        val displayIndex = if (firstVisible == RecyclerView.NO_POSITION) {
            (readingSessionViewModel.index.value ?: 0).coerceIn(0, total - 1)
        } else {
            firstVisible.coerceIn(0, total - 1)
        }
        binding.readingPageInfo.visibility = View.VISIBLE
        binding.readingPageInfo.text = getString(
            R.string.reading_page_info,
            folder.name,
            displayIndex + 1,
            total
        )
    }

    private fun persistCurrentTranslation(forceSave: Boolean = false) {
        val imageFile = currentImageFile ?: return
        val translation = currentTranslation ?: return
        val offsets = currentOverlayOffsets()
        if (offsets.isEmpty() && !forceSave) return
        val updatedBubbles = translation.bubbles.map { bubble ->
            val offset = offsets[bubble.id] ?: (0f to 0f)
            bubble.copy(
                rect = RectF(
                    bubble.rect.left + offset.first,
                    bubble.rect.top + offset.second,
                    bubble.rect.right + offset.first,
                    bubble.rect.bottom + offset.second
                ),
                maskContour = BubbleShapePaths.translateMaskContour(
                    contour = bubble.maskContour,
                    deltaX = offset.first,
                    deltaY = offset.second,
                    sourceWidth = translation.width,
                    sourceHeight = translation.height
                )
            )
        }
        val updated = translation.copy(bubbles = updatedBubbles)
        translationStore.save(imageFile, updated)
        currentTranslation = updated
        if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) {
            webtoonAdapter.notifyTranslationChanged(imageFile.absolutePath)
        }
        applyOverlayOffsets(emptyMap())
        renderCurrentTranslation()
    }

    private fun handleBubbleRemove(bubbleId: Int) {
        if (!isEditMode) return
        val translation = currentTranslation ?: return
        val remaining = translation.bubbles.filterNot { it.id == bubbleId }
        if (remaining.size == translation.bubbles.size) return
        if (resizeTargetId == bubbleId) {
            hideResizePanel()
        }
        currentTranslation = translation.copy(bubbles = remaining)
        val offsets = currentOverlayOffsets()
        offsets.remove(bubbleId)
        applyOverlayOffsets(offsets)
        renderCurrentTranslation()
    }

    private fun clearAllBubbles() {
        if (!isEditMode) return
        val translation = currentTranslation ?: return
        if (translation.bubbles.isEmpty()) return
        hideResizePanel()
        currentTranslation = translation.copy(bubbles = emptyList())
        applyOverlayOffsets(emptyMap())
        renderCurrentTranslation()
        Toast.makeText(requireContext(), R.string.reading_clear_bubbles_done, Toast.LENGTH_SHORT).show()
    }

    private fun handleBubbleEdit(bubbleId: Int) {
        if (blockBubbleEditingWhileZoomed()) return
        if (!isEditMode) return
        val translation = currentTranslation ?: return
        val bubble = translation.bubbles.firstOrNull { it.id == bubbleId } ?: return
        val input = EditText(requireContext()).apply {
            setText(bubble.text)
            setSelection(text?.length ?: 0)
            minLines = 2
            if (!bubble.hasDisplayText()) {
                hint = getString(R.string.reading_empty_bubble_hint)
            }
            setTextColor(resolveColorAttr(R.attr.dialogTextColor))
            setHintTextColor(resolveColorAttr(R.attr.dialogHintTextColor))
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.reading_edit_bubble_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val updatedText = input.text?.toString().orEmpty()
                persistCurrentTranslation(forceSave = true)
                val refreshed = currentTranslation ?: return@setPositiveButton
                val updatedBubbles = refreshed.bubbles.map { current ->
                    if (current.id == bubbleId) {
                        current.withManualText(updatedText)
                    } else {
                        current
                    }
                }
                val updated = refreshed.copy(bubbles = updatedBubbles)
                currentTranslation = updated
                renderCurrentTranslation()
                saveCurrentTranslation()
            }
            .show()
    }

    private fun showBubbleActionDialog(bubbleId: Int) {
        if (blockBubbleEditingWhileZoomed()) return
        if (!isEditMode) return
        val dialogView = layoutInflater.inflate(R.layout.dialog_bubble_actions, null)
        val resizeButton = dialogView.findViewById<android.widget.Button>(R.id.bubbleActionResize)
        val deleteButton = dialogView.findViewById<android.widget.Button>(R.id.bubbleActionDelete)
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(true)
            .create()
        resizeButton.setOnClickListener {
            dialog.dismiss()
            showResizePanel(bubbleId)
        }
        deleteButton.setOnClickListener {
            dialog.dismiss()
            handleBubbleRemove(bubbleId)
        }
        dialog.show()
    }

    private fun showResizePanel(bubbleId: Int) {
        if (blockBubbleEditingWhileZoomed()) return
        if (!isEditMode) return
        persistCurrentTranslation(forceSave = true)
        val translation = currentTranslation ?: return
        val bubble = translation.bubbles.firstOrNull { it.id == bubbleId } ?: return
        resizeTargetId = bubbleId
        resizeBaseRect = RectF(bubble.rect)
        val percent = 100
        resizeWidthPercent = percent
        resizeHeightPercent = percent
        resizeUpdatingWidthSlider = true
        binding.readingResizeWidthSlider.progress = percent - resizeMinPercent
        resizeUpdatingWidthSlider = false
        resizeUpdatingHeightSlider = true
        binding.readingResizeHeightSlider.progress = percent - resizeMinPercent
        resizeUpdatingHeightSlider = false
        resizeUpdatingWidthInput = true
        binding.readingResizeWidthInput.setText(formatInt(percent))
        binding.readingResizeWidthInput.setSelection(binding.readingResizeWidthInput.text?.length ?: 0)
        resizeUpdatingWidthInput = false
        resizeUpdatingHeightInput = true
        binding.readingResizeHeightInput.setText(formatInt(percent))
        binding.readingResizeHeightInput.setSelection(binding.readingResizeHeightInput.text?.length ?: 0)
        resizeUpdatingHeightInput = false
        binding.readingResizePanel.visibility = View.VISIBLE
    }

    private fun hideResizePanel() {
        resizeTargetId = null
        resizeBaseRect = null
        binding.readingResizePanel.visibility = View.GONE
    }

    private fun blockBubbleEditingWhileZoomed(): Boolean {
        if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) return false
        if (!imageTransformController.isZoomed()) return false
        Toast.makeText(
            requireContext(),
            R.string.reading_exit_zoom_before_edit,
            Toast.LENGTH_SHORT
        ).show()
        return true
    }

    private fun applyResizePercent(widthPercent: Int?, heightPercent: Int?) {
        val id = resizeTargetId ?: return
        val base = resizeBaseRect ?: return
        val translation = currentTranslation ?: return
        val widthScale = (widthPercent ?: 100) / 100f
        val heightScale = (heightPercent ?: 100) / 100f
        val width = base.width() * widthScale
        val height = base.height() * heightScale
        if (width <= 1f || height <= 1f) return
        val centerX = base.centerX()
        val centerY = base.centerY()
        var left = centerX - width / 2f
        var top = centerY - height / 2f
        var right = centerX + width / 2f
        var bottom = centerY + height / 2f
        val imageWidth = translation.width.toFloat()
        val imageHeight = translation.height.toFloat()
        if (left < 0f) {
            right -= left
            left = 0f
        }
        if (top < 0f) {
            bottom -= top
            top = 0f
        }
        if (right > imageWidth) {
            left -= right - imageWidth
            right = imageWidth
        }
        if (bottom > imageHeight) {
            top -= bottom - imageHeight
            bottom = imageHeight
        }
        val updatedRect = RectF(left, top, right, bottom)
        val updatedBubbles = translation.bubbles.map { bubble ->
            if (bubble.id == id) {
                bubble.copy(rect = updatedRect)
            } else {
                bubble
            }
        }
        val updated = translation.copy(bubbles = updatedBubbles)
        currentTranslation = updated
        renderCurrentTranslation()
    }

    private fun saveCurrentTranslation() {
        val imageFile = currentImageFile ?: return
        val translation = currentTranslation ?: return
        translationStore.save(imageFile, translation)
        if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) {
            webtoonAdapter.notifyTranslationChanged(imageFile.absolutePath)
        }
    }

    private fun addNewBubble() {
        if (!isEditMode) return
        val translation = currentTranslation ?: return
        val width = translation.width.toFloat()
        val height = translation.height.toFloat()
        if (width <= 0f || height <= 0f) return
        val baseSize = minOf(width, height) * 0.18f
        val bubbleWidth = baseSize.coerceIn(80f, width * 0.6f)
        val bubbleHeight = (baseSize * 0.7f).coerceIn(60f, height * 0.6f)
        val left = (width - bubbleWidth) / 2f
        val top = (height - bubbleHeight) / 2f
        val rect = RectF(left, top, left + bubbleWidth, top + bubbleHeight)
        val nextId = (translation.bubbles.maxOfOrNull { it.id } ?: -1) + 1
        val newBubble = BubbleTranslation.pending(nextId, rect, "", BubbleSource.MANUAL)
        val updated = translation.copy(bubbles = translation.bubbles + newBubble)
        currentTranslation = updated
        renderCurrentTranslation()
        saveCurrentTranslation()
        showResizePanel(nextId)
    }

    private fun processEmptyBubbles() {
        val imageFile = currentImageFile ?: return
        val translation = currentTranslation ?: return
        val folder = readingSessionViewModel.currentFolder.value ?: return
        if (translation.bubbles.none { it.needsTranslationRetry() }) return
        Toast.makeText(requireContext(), R.string.reading_empty_bubble_translating, Toast.LENGTH_SHORT).show()
        emptyBubbleJob?.cancel()
        emptyBubbleJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val outcome = emptyBubbleCoordinator.process(
                    imageFile,
                    folder,
                    translation
                ) ?: return@launch
                if (currentImageFile?.absolutePath == imageFile.absolutePath) {
                    currentTranslation = outcome.updatedTranslation
                    if (folderReadingMode == FolderReadingMode.WEBTOON_SCROLL) {
                        webtoonAdapter.notifyTranslationChanged(imageFile.absolutePath)
                    } else {
                        binding.translationOverlay.setTranslations(outcome.updatedTranslation)
                    }
                    if (outcome.translatedByLlm) {
                        Toast.makeText(
                            requireContext(),
                            R.string.reading_empty_bubble_translated,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: LlmResponseException) {
                AppLogger.log("Reading", "Reading empty bubble model response invalid", e)
                showEmptyBubbleModelErrorDialog(e.responseContent)
            } catch (e: LlmRequestException) {
                AppLogger.log("Reading", "Reading empty bubble request failed", e)
                if (!isAdded) return@launch
                dialogs.showApiErrorDialog(requireContext(), e.errorCode, e.responseBody)
            }
        }
    }

    private fun showEmptyBubbleModelErrorDialog(responseContent: String) {
        if (!isAdded) return
        activeEmptyBubbleModelErrorDialog?.dismiss()
        activeEmptyBubbleModelErrorDialog = dialogs.showModelErrorDialog(
            context = requireContext(),
            responseContent = responseContent,
            onRetry = { processEmptyBubbles() },
            negativeButtonResId = R.string.close_action
        )
        activeEmptyBubbleModelErrorDialog?.setOnDismissListener {
            activeEmptyBubbleModelErrorDialog = null
        }
    }
}
