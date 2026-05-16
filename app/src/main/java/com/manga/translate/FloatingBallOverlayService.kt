package com.manga.translate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import com.manga.translate.di.appContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class FloatingBallOverlayService : Service() {
    private class FloatingBallImageView(context: android.content.Context) : AppCompatImageView(context) {
        var onPerformClick: (() -> Unit)? = null

        override fun performClick(): Boolean {
            onPerformClick?.invoke()
            return super.performClick()
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val appContainer by lazy(LazyThreadSafetyMode.NONE) { applicationContext.appContainer }
    private val settingsStore by lazy(LazyThreadSafetyMode.NONE) { appContainer.settingsStore }
    private val floatingTranslationCacheStore by lazy(LazyThreadSafetyMode.NONE) {
        appContainer.floatingTranslationCacheStore
    }
    private val emptyBubbleCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        appContainer.createFloatingEmptyBubbleCoordinator()
    }
    private val floatingBubbleTranslationCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        appContainer.createFloatingBubbleTranslationCoordinator()
    }
    private val bubbleTextRecognizer by lazy(LazyThreadSafetyMode.NONE) {
        appContainer.bubbleTextRecognizer
    }
    private val llmClient by lazy(LazyThreadSafetyMode.NONE) { appContainer.llmClient }
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private var controllerRoot: LinearLayout? = null
    private var controllerLayoutParams: WindowManager.LayoutParams? = null
    private var controllerMenuPanel: LinearLayout? = null
    private var controllerBallView: View? = null
    private var detectionOverlayView: FloatingDetectionOverlayView? = null
    private var detectionLayoutParams: WindowManager.LayoutParams? = null
    private val screenCaptureSession by lazy {
        ProjectionCaptureSession(applicationContext) {
            scope.launch(Dispatchers.Main) {
                clearCurrentSession()
                releaseProjection()
                Toast.makeText(
                    this@FloatingBallOverlayService,
                    R.string.floating_capture_not_ready,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    private var textDetector: TextDetector? = null
    private var detectJob: Job? = null
    private var editModeToggleButton: AppCompatButton? = null
    private var swipeTranslateButton: AppCompatButton? = null
    private var addBubbleButton: AppCompatButton? = null
    private var confirmEditButton: AppCompatButton? = null
    private var cancelEditButton: AppCompatButton? = null
    private var progressStatusView: TextView? = null
    private var currentSession: TranslationResult? = null
    private var editSessionSnapshot: TranslationResult? = null
    private var currentSessionBitmap: Bitmap? = null
    private var editModeEnabled = false
    private var createBubbleModeEnabled = false
    private var editSessionDirty = false
    private var autoCloseReferenceFrame: ScreenChangeReferenceFrame? = null
    private var autoCloseCheckJob: Job? = null
    private var blankBubbleErrorDialog: AlertDialog? = null
    private val hideProgressStatusRunnable = Runnable {
        progressStatusView?.visibility = View.GONE
    }
    private val autoCloseCheckRunnable = object : Runnable {
        override fun run() {
            if (!shouldRunAutoCloseDetection()) return
            if (autoCloseCheckJob?.isActive == true) {
                mainHandler.postDelayed(this, AUTO_CLOSE_SCREEN_CHECK_INTERVAL_MS)
                return
            }
            autoCloseCheckJob = scope.launch(Dispatchers.Default) {
                try {
                    val changed = detectScreenChangeAgainstReference()
                    withContext(Dispatchers.Main) {
                        if (changed) {
                            AppLogger.log("FloatingOCR", "Auto close triggered by screen change")
                            clearCurrentSession()
                        } else {
                            scheduleNextAutoCloseCheck()
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.log("FloatingOCR", "Auto close screen check failed", e)
                    withContext(Dispatchers.Main) {
                        scheduleNextAutoCloseCheck()
                    }
                }
            }
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.log("FloatingOCR", "Service onStartCommand action=${intent?.action ?: "null"}")
        if (intent?.action == ACTION_STOP) {
            AppLogger.log("FloatingOCR", "Received stop action")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!canDrawOverlays()) {
            AppLogger.log("FloatingOCR", "Overlay permission missing, stop service")
            stopSelf()
            return START_NOT_STICKY
        }
        ensureForeground()
        ensureWindowManager()
        if (detectionOverlayView == null) {
            showDetectionOverlay()
        }
        if (controllerRoot == null) {
            showControllerOverlay()
        }
        if (intent?.action == ACTION_START) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
            val data = intent.getParcelableIntentExtraCompat(EXTRA_RESULT_DATA)
            if (resultCode != Int.MIN_VALUE && data != null) {
                AppLogger.log("FloatingOCR", "Prepare projection from start intent")
                prepareProjection(resultCode, data)
            } else {
                AppLogger.log("FloatingOCR", "Start intent missing projection extras")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        detectJob?.cancel()
        autoCloseCheckJob?.cancel()
        blankBubbleErrorDialog?.dismiss()
        blankBubbleErrorDialog = null
        clearCurrentSession()
        releaseProjection()
        removeOverlay()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private suspend fun recognizeFloatingBubbleText(
        crop: Bitmap,
        language: TranslationLanguage
    ): String = withContext(Dispatchers.Default) {
        val ocrSettings = settingsStore.loadOcrApiSettings()
        bubbleTextRecognizer.recognizeCrop(
            crop = crop,
            language = language,
            useLocalOcr = ocrSettings.useLocalOcr,
            logTag = "FloatingOCR"
        )
    }

    private fun canDrawOverlays(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    private fun ensureForeground() {
        val manager = getSystemService<NotificationManager>() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.floating_service_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.floating_service_title))
            .setContentText(getString(R.string.floating_service_message))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun showControllerOverlay() {
        ensureWindowManager()
        val density = resources.displayMetrics.density
        val ballSize = (56f * density).toInt()
        val margin = (8f * density).toInt()
        val menuButtonWidth = (156f * density).toInt()
        val screenWidth = resources.displayMetrics.widthPixels

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val progressView = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(
                (10f * density).toInt(),
                (6f * density).toInt(),
                (10f * density).toInt(),
                (6f * density).toInt()
            )
            background = GradientDrawable().apply {
                cornerRadius = 8f * density
                setColor(0xCC1B1B1B.toInt())
                setStroke((1f * density).toInt(), 0x44FFFFFF)
            }
            visibility = View.GONE
        }
        val floatingBall = FloatingBallImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageResource(R.drawable.ic_translate_overlay)
            imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            background = createFloatingBallBackground(pressed = false)
            elevation = 10f * density
            setPadding(
                (14f * density).toInt(),
                (14f * density).toInt(),
                (14f * density).toInt(),
                (14f * density).toInt()
            )
            contentDescription = getString(R.string.floating_service_message)
        }

        root.addView(
            progressView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (4f * density).toInt()
            }
        )
        val menuPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }
        val editButton = createMenuButton().apply {
            setOnClickListener { toggleEditMode() }
        }
        val swipeTranslateMenuButton = createMenuButton().apply {
            text = getString(R.string.overlay_swipe_translate_button)
            setOnClickListener {
                controllerMenuPanel?.visibility = View.GONE
                startSwipeTranslateMode()
            }
        }
        val addButton = createMenuButton().apply {
            text = getString(R.string.overlay_add_bubble_button)
            setOnClickListener { toggleCreateBubbleMode() }
        }
        val confirmButton = createMenuButton().apply {
            text = getString(R.string.overlay_confirm_button)
            setOnClickListener {
                controllerMenuPanel?.visibility = View.GONE
                confirmEditSession()
            }
        }
        val cancelButton = createMenuButton().apply {
            text = getString(R.string.overlay_cancel_button)
            setOnClickListener {
                controllerMenuPanel?.visibility = View.GONE
                cancelEditSession()
            }
        }
        val exitButton = createMenuButton().apply {
            text = getString(R.string.overlay_exit_button)
            setOnClickListener { stopSelf() }
        }
        editModeToggleButton = editButton
        swipeTranslateButton = swipeTranslateMenuButton
        addBubbleButton = addButton
        confirmEditButton = confirmButton
        cancelEditButton = cancelButton
        updateEditModeToggleButton()
        updateEditButtons()
        menuPanel.addView(
            editButton,
            createMenuButtonLayoutParams(menuButtonWidth)
        )
        menuPanel.addView(
            swipeTranslateMenuButton,
            createMenuButtonLayoutParams(menuButtonWidth, topMargin = 6f * density)
        )
        menuPanel.addView(
            addButton,
            createMenuButtonLayoutParams(menuButtonWidth, topMargin = 6f * density)
        )
        menuPanel.addView(
            confirmButton,
            createMenuButtonLayoutParams(menuButtonWidth, topMargin = 6f * density)
        )
        menuPanel.addView(
            cancelButton,
            createMenuButtonLayoutParams(menuButtonWidth, topMargin = 6f * density)
        )
        menuPanel.addView(
            exitButton,
            createMenuButtonLayoutParams(menuButtonWidth, topMargin = 6f * density)
        )

        root.addView(
            menuPanel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (4f * density).toInt()
            }
        )
        root.addView(
            floatingBall,
            LinearLayout.LayoutParams(ballSize, ballSize).apply {
                topMargin = margin
            }
        )

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth - ballSize - margin).coerceAtLeast(0)
            y = (180f * density).toInt()
        }

        attachBallGesture(floatingBall, menuPanel, params)
        windowManager.addView(root, params)
        AppLogger.log("FloatingOCR", "Controller overlay added")
        controllerRoot = root
        controllerLayoutParams = params
        controllerMenuPanel = menuPanel
        controllerBallView = floatingBall
        progressStatusView = progressView
    }

    private fun showDetectionOverlay() {
        ensureWindowManager()
        val overlay = FloatingDetectionOverlayView(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            buildDetectionFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        overlay.setFloatingBubbleRenderSettings(settingsStore.loadFloatingBubbleRenderSettings())
        overlay.setEditMode(editModeEnabled)
        overlay.setCreateBubbleMode(createBubbleModeEnabled)
        overlay.onBubblesChanged = { bubbles ->
            val session = currentSession
            if (session != null) {
                currentSession = session.copy(bubbles = bubbles)
            }
        }
        overlay.onBubbleDelete = { bubbleId ->
            val session = currentSession
            if (session != null) {
                currentSession = session.copy(bubbles = session.bubbles.filterNot { it.id == bubbleId })
                syncOverlaySession()
            }
        }
        overlay.onManualBubbleCreated = { rect ->
            appendManualBubble(rect)
        }
        overlay.onEditDirtyChanged = { dirty ->
            editSessionDirty = dirty
            updateEditButtons()
        }
        overlay.onCreateBubbleTouchActiveChanged = { active ->
            setFloatingBallHidden(active)
        }
        windowManager.addView(overlay, params)
        AppLogger.log("FloatingOCR", "Detection overlay added")
        detectionOverlayView = overlay
        detectionLayoutParams = params
        syncOverlaySession()
    }

    private fun ensureWindowManager() {
        if (!this::windowManager.isInitialized) {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        }
    }

    private fun buildDetectionFlags(): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (!editModeEnabled) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        return flags
    }

    private fun showProgressStatus(messageResId: Int, autoHide: Boolean = false) {
        showProgressStatus(getString(messageResId), autoHide)
    }

    private fun showProgressStatus(message: String, autoHide: Boolean = false) {
        val statusView = progressStatusView ?: return
        mainHandler.removeCallbacks(hideProgressStatusRunnable)
        statusView.text = message
        statusView.visibility = View.VISIBLE
        if (autoHide) {
            mainHandler.postDelayed(hideProgressStatusRunnable, FLOATING_PROGRESS_HIDE_DELAY_MS)
        }
    }

    private fun setFloatingBallHidden(hidden: Boolean) {
        controllerBallView?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }

    private fun ensureControllerOnTop() {
        val root = controllerRoot ?: return
        val params = controllerLayoutParams ?: return
        try {
            windowManager.removeView(root)
            windowManager.addView(root, params)
        } catch (_: Exception) {
        }
    }

    private fun updateEditModeToggleButton() {
        editModeToggleButton?.text = getString(
            R.string.overlay_edit_mode_option_format,
            if (editModeEnabled) getString(R.string.common_on) else getString(R.string.common_off)
        )
    }

    private fun updateEditButtons() {
        val isEditing = editModeEnabled
        swipeTranslateButton?.visibility = if (isEditing) View.GONE else View.VISIBLE
        addBubbleButton?.visibility = if (isEditing) View.VISIBLE else View.GONE
        confirmEditButton?.visibility = if (isEditing) View.VISIBLE else View.GONE
        cancelEditButton?.visibility = if (isEditing) View.VISIBLE else View.GONE
        addBubbleButton?.isEnabled = isEditing && currentSession != null
        confirmEditButton?.isEnabled = isEditing && currentSession != null
        cancelEditButton?.isEnabled = isEditing
        addBubbleButton?.alpha = if (addBubbleButton?.isEnabled == true) 1f else 0.5f
        confirmEditButton?.alpha = if (confirmEditButton?.isEnabled == true) 1f else 0.5f
        cancelEditButton?.alpha = if (cancelEditButton?.isEnabled == true) 1f else 0.5f
        addBubbleButton?.text = if (createBubbleModeEnabled) {
            getString(R.string.overlay_add_bubble_mode_active)
        } else {
            getString(R.string.overlay_add_bubble_button)
        }
    }

    private fun createFloatingBallBackground(pressed: Boolean): GradientDrawable {
        val density = resources.displayMetrics.density
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            if (pressed) {
                intArrayOf(
                    0xFF1AA7FF.toInt(),
                    0xFF6A5CFF.toInt()
                )
            } else {
                intArrayOf(
                    0xFF39C5FF.toInt(),
                    0xFF4F7BFF.toInt()
                )
            }
        ).apply {
            shape = GradientDrawable.OVAL
            gradientType = GradientDrawable.LINEAR_GRADIENT
            setStroke(
                (1.5f * density).toInt().coerceAtLeast(1),
                if (pressed) 0x99FFFFFF.toInt() else 0x66FFFFFF
            )
        }
    }

    private fun updateFloatingBallPressedState(
        target: AppCompatImageView,
        pressed: Boolean
    ) {
        target.isPressed = pressed
        target.background = createFloatingBallBackground(pressed)
        target.scaleX = if (pressed) 0.94f else 1f
        target.scaleY = if (pressed) 0.94f else 1f
        target.alpha = if (pressed) 0.96f else 1f
    }

    private fun createMenuButton(): AppCompatButton {
        val density = resources.displayMetrics.density
        return AppCompatButton(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFF1F1F1F.toInt())
            background = GradientDrawable().apply {
                cornerRadius = 12f * density
                setColor(0xF7FFFFFF.toInt())
                setStroke((1f * density).toInt(), 0x2A1F1F1F)
            }
            elevation = 6f * density
            minimumWidth = 0
            minWidth = 0
            setPadding(
                (14f * density).toInt(),
                (8f * density).toInt(),
                (14f * density).toInt(),
                (8f * density).toInt()
            )
        }
    }

    private fun createMenuButtonLayoutParams(
        width: Int,
        topMargin: Float = 0f
    ): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            width,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            this.topMargin = topMargin.toInt()
        }
    }

    private fun toggleEditMode() {
        if (editModeEnabled) {
            cancelEditSession()
            controllerMenuPanel?.visibility = View.GONE
            return
        }
        if (!enterEditMode()) {
            Toast.makeText(this, R.string.overlay_edit_requires_detection, Toast.LENGTH_SHORT).show()
        }
    }

    private fun enterEditMode(showToast: Boolean = true): Boolean {
        val session = currentSession ?: return false
        editModeEnabled = true
        createBubbleModeEnabled = false
        editSessionDirty = false
        editSessionSnapshot = session.deepCopy()
        setFloatingBallHidden(false)
        detectionOverlayView?.setEditMode(true)
        detectionOverlayView?.setCreateBubbleMode(false)
        refreshDetectionOverlayTouchability()
        updateAutoCloseDetectionState()
        updateEditModeToggleButton()
        updateEditButtons()
        if (showToast) {
            Toast.makeText(this, R.string.overlay_edit_mode_enabled, Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private fun startSwipeTranslateMode() {
        if (detectJob?.isActive == true) return
        if (!screenCaptureSession.isReady()) {
            AppLogger.log("FloatingOCR", "Swipe translate blocked: projection not ready")
            showProgressStatus(R.string.floating_capture_not_ready, autoHide = true)
            Toast.makeText(this, R.string.floating_capture_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        showProgressStatus(R.string.floating_progress_capturing)
        detectJob = scope.launch(Dispatchers.Default) {
            val runningJob = currentCoroutineContext()[Job]
            var bitmap: Bitmap? = null
            try {
                bitmap = screenCaptureSession.captureCurrentScreen()
                if (bitmap == null) {
                    withContext(Dispatchers.Main) {
                        showProgressStatus(R.string.floating_capture_not_ready, autoHide = true)
                        Toast.makeText(
                            this@FloatingBallOverlayService,
                            R.string.floating_capture_not_ready,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    currentSession = TranslationResult(
                        imageName = "",
                        width = bitmap.width,
                        height = bitmap.height,
                        bubbles = emptyList()
                    )
                    editSessionSnapshot = null
                    createBubbleModeEnabled = false
                    syncOverlaySession()
                    replaceCurrentSessionBitmap(bitmap)
                    rebuildAutoCloseReferenceFromCurrentSession()
                    bitmap = null
                    enterEditMode(showToast = false)
                    toggleCreateBubbleMode()
                    showProgressStatus(R.string.overlay_swipe_translate_ready, autoHide = true)
                    Toast.makeText(
                        this@FloatingBallOverlayService,
                        R.string.overlay_swipe_translate_ready,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                AppLogger.log("FloatingOCR", "Swipe translate mode ready")
            } catch (e: Exception) {
                AppLogger.log("FloatingOCR", "Swipe translate mode failed", e)
                withContext(Dispatchers.Main) {
                    showProgressStatus(R.string.floating_detect_failed, autoHide = true)
                    Toast.makeText(
                        this@FloatingBallOverlayService,
                        R.string.floating_detect_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                bitmap?.recycle()
                withContext(Dispatchers.Main) {
                    if (detectJob === runningJob) {
                        detectJob = null
                    }
                    updateAutoCloseDetectionState()
                }
            }
        }
    }

    private fun toggleCreateBubbleMode() {
        if (!editModeEnabled) return
        createBubbleModeEnabled = !createBubbleModeEnabled
        detectionOverlayView?.setCreateBubbleMode(createBubbleModeEnabled)
        if (!createBubbleModeEnabled) {
            setFloatingBallHidden(false)
        }
        updateAutoCloseDetectionState()
        updateEditButtons()
        if (createBubbleModeEnabled) {
            Toast.makeText(this, R.string.overlay_create_bubble_hint, Toast.LENGTH_SHORT).show()
        }
    }

    private fun cancelEditSession() {
        if (!editModeEnabled) return
        val restored = editSessionSnapshot?.deepCopy()
        editModeEnabled = false
        createBubbleModeEnabled = false
        editSessionDirty = false
        editSessionSnapshot = null
        setFloatingBallHidden(false)
        currentSession = restored ?: currentSession
        detectionOverlayView?.setEditMode(false)
        detectionOverlayView?.setCreateBubbleMode(false)
        syncOverlaySession()
        refreshDetectionOverlayTouchability()
        updateAutoCloseDetectionState()
        updateEditModeToggleButton()
        updateEditButtons()
        Toast.makeText(this, R.string.overlay_edit_canceled, Toast.LENGTH_SHORT).show()
    }

    private fun finishEditSession(showToast: Boolean) {
        editModeEnabled = false
        createBubbleModeEnabled = false
        editSessionDirty = false
        editSessionSnapshot = null
        setFloatingBallHidden(false)
        detectionOverlayView?.setEditMode(false)
        detectionOverlayView?.setCreateBubbleMode(false)
        refreshDetectionOverlayTouchability()
        updateAutoCloseDetectionState()
        updateEditModeToggleButton()
        updateEditButtons()
        if (showToast) {
            Toast.makeText(this, R.string.overlay_edit_applied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmEditSession() {
        if (!editModeEnabled) return
        val session = currentSession ?: run {
            finishEditSession(showToast = false)
            return
        }
        val bitmapSnapshot = currentSessionBitmap?.let { source ->
            runCatching { source.copy(source.config ?: Bitmap.Config.ARGB_8888, false) }.getOrNull()
        } ?: run {
            showProgressStatus(R.string.floating_capture_not_ready, autoHide = true)
            Toast.makeText(this, R.string.floating_capture_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        showProgressStatus(R.string.overlay_empty_bubble_translating)
        detectJob?.cancel()
        detectJob = scope.launch(Dispatchers.Default) {
            val runningJob = currentCoroutineContext()[Job]
            try {
                val floatingTimeoutMs =
                    settingsStore.loadFloatingTranslateApiSettings().timeoutSeconds * 1000
                val outcome = executeWithModelResponseRetries("FloatingEdit") {
                    emptyBubbleCoordinator.process(
                        bitmap = bitmapSnapshot,
                        baseTranslation = session,
                        timeoutMs = floatingTimeoutMs,
                        retryCount = FLOATING_TRANSLATE_RETRY_COUNT,
                        floatPromptAsset = FLOAT_PROMPT_ASSET,
                        floatVlPromptAsset = FLOAT_VL_PROMPT_ASSET,
                        maxVlConcurrency = MAX_FLOATING_TASK_CONCURRENCY
                    )
                }
                withContext(Dispatchers.Main) {
                    if (outcome.requiresVlModel) {
                        showProgressStatus(R.string.floating_vl_model_required, autoHide = true)
                        Toast.makeText(
                            this@FloatingBallOverlayService,
                            R.string.floating_vl_model_required,
                            Toast.LENGTH_LONG
                        ).show()
                        return@withContext
                    }
                    if (outcome.timedOut) {
                        showProgressStatus(R.string.floating_translate_timeout, autoHide = true)
                        Toast.makeText(
                            this@FloatingBallOverlayService,
                            R.string.floating_translate_timeout,
                            Toast.LENGTH_SHORT
                        ).show()
                        return@withContext
                    }
                    currentSession = outcome.translation
                    syncOverlaySession()
                    finishEditSession(showToast = false)
                    rebuildAutoCloseReferenceFromCurrentSession()
                    showProgressStatus(R.string.overlay_empty_bubble_translated, autoHide = true)
                    Toast.makeText(
                        this@FloatingBallOverlayService,
                        R.string.overlay_empty_bubble_translated,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: LlmResponseException) {
                AppLogger.log("FloatingOCR", "Floating edit model response invalid", e)
                withContext(Dispatchers.Main) {
                    showModelErrorDialog(
                        responseContent = e.responseContent,
                        onContinue = { confirmEditSession() }
                    )
                }
            } catch (e: LlmRequestException) {
                AppLogger.log("FloatingOCR", "Floating edit request failed", e)
                withContext(Dispatchers.Main) {
                    showApiErrorDialog(e.errorCode, e.responseBody)
                }
            } finally {
                bitmapSnapshot.recycle()
                withContext(Dispatchers.Main) {
                    if (detectJob === runningJob) {
                        detectJob = null
                    }
                    updateAutoCloseDetectionState()
                }
            }
        }
    }

    private fun appendManualBubble(rect: RectF) {
        val session = currentSession ?: return
        val nextId = (session.bubbles.maxOfOrNull { it.id } ?: -1) + 1
        val bubble = BubbleTranslation.pending(nextId, RectF(rect), "", BubbleSource.MANUAL)
        currentSession = session.copy(bubbles = session.bubbles + bubble)
        createBubbleModeEnabled = false
        setFloatingBallHidden(false)
        syncOverlaySession()
        detectionOverlayView?.setCreateBubbleMode(false)
        updateAutoCloseDetectionState()
        updateEditButtons()
    }

    private fun syncOverlaySession() {
        val session = currentSession
        if (session == null) {
            detectionOverlayView?.clearDetections()
            updateEditButtons()
            return
        }
        detectionOverlayView?.setTranslationSession(
            session.width,
            session.height,
            session.bubbles
        )
        updateEditButtons()
    }

    private fun refreshDetectionOverlayTouchability() {
        val params = detectionLayoutParams ?: return
        val newFlags = buildDetectionFlags()
        if (params.flags == newFlags) return
        params.flags = newFlags
        try {
            windowManager.updateViewLayout(detectionOverlayView, params)
        } catch (_: Exception) {
        }
        ensureControllerOnTop()
    }

    private fun replaceCurrentSessionBitmap(bitmap: Bitmap?) {
        currentSessionBitmap?.recycle()
        currentSessionBitmap = bitmap
    }

    private fun clearCurrentSession() {
        blankBubbleErrorDialog?.dismiss()
        blankBubbleErrorDialog = null
        currentSession = null
        editSessionSnapshot = null
        editModeEnabled = false
        createBubbleModeEnabled = false
        editSessionDirty = false
        detectionOverlayView?.setEditMode(false)
        detectionOverlayView?.setCreateBubbleMode(false)
        detectionOverlayView?.clearDetections()
        setFloatingBallHidden(false)
        replaceCurrentSessionBitmap(null)
        clearAutoCloseReference()
        refreshDetectionOverlayTouchability()
        updateEditModeToggleButton()
        updateEditButtons()
    }

    private fun toggleMenuVisibility(menuPanel: View) {
        menuPanel.isVisible = !menuPanel.isVisible
        if (menuPanel.isVisible) {
            updateEditButtons()
        }
    }

    private fun performFloatingBallGestureAction(
        action: FloatingBallGestureAction,
        menuPanel: View
    ) {
        when (action) {
            FloatingBallGestureAction.START_TRANSLATE -> {
                menuPanel.visibility = View.GONE
                runTextDetection()
            }

            FloatingBallGestureAction.OPEN_MENU -> {
                toggleMenuVisibility(menuPanel)
            }

            FloatingBallGestureAction.CLEAR_SCREEN -> {
                menuPanel.visibility = View.GONE
                clearCurrentSession()
            }

            FloatingBallGestureAction.NONE -> Unit

            FloatingBallGestureAction.SWIPE_TRANSLATE -> {
                menuPanel.visibility = View.GONE
                startSwipeTranslateMode()
            }
        }
    }

    private fun prepareProjection(resultCode: Int, data: Intent) {
        AppLogger.log("FloatingOCR", "Preparing projection")
        releaseProjection()
        val manager = getSystemService(MediaProjectionManager::class.java) ?: return
        if (!screenCaptureSession.prepare(manager, resultCode, data, resources.displayMetrics, PixelFormat.RGBA_8888)) {
            AppLogger.log("FloatingOCR", "Projection preparation failed")
        }
    }

    private fun runTextDetection() {
        if (detectJob?.isActive == true) return
        blankBubbleErrorDialog?.dismiss()
        blankBubbleErrorDialog = null
        if (editModeEnabled) {
            finishEditSession(showToast = false)
        }
        updateAutoCloseDetectionState()
        if (!screenCaptureSession.isReady()) {
            AppLogger.log("FloatingOCR", "Run detection blocked: projection not ready")
            showProgressStatus(R.string.floating_capture_not_ready, autoHide = true)
            Toast.makeText(this, R.string.floating_capture_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        AppLogger.log("FloatingOCR", "Run detection started")
        showProgressStatus(R.string.floating_progress_capturing)
        detectJob = scope.launch(Dispatchers.Default) {
            val runningJob = currentCoroutineContext()[Job]
            var bitmap: Bitmap? = null
            try {
                bitmap = screenCaptureSession.captureCurrentScreen()
                if (bitmap == null) {
                    AppLogger.log("FloatingOCR", "Capture screen returned null")
                    withContext(Dispatchers.Main) {
                        showProgressStatus(R.string.floating_capture_not_ready, autoHide = true)
                        Toast.makeText(
                            this@FloatingBallOverlayService,
                            R.string.floating_capture_not_ready,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    showProgressStatus(R.string.floating_progress_detecting)
                }
                val detector = textDetector ?: TextDetector(
                    applicationContext,
                    settingsStore = settingsStore
                ).also { textDetector = it }
                val detections = detector.detect(bitmap)
                AppLogger.log("FloatingOCR", "Raw detections count=${detections.size}")
                val deduplicatedRects = RectGeometryDeduplicator.mergeSupplementRects(
                    detections,
                    bitmap.width,
                    bitmap.height
                )
                if (deduplicatedRects.size < detections.size) {
                    AppLogger.log(
                        "FloatingOCR",
                        "Deduplicated overlapping detections: ${detections.size} -> ${deduplicatedRects.size}"
                    )
                }
                AppLogger.log("FloatingOCR", "Deduplicated detections count=${deduplicatedRects.size}")
                val floatingSettings = settingsStore.loadFloatingTranslateApiSettings()
                val floatingApiSettings = settingsStore.loadResolvedFloatingTranslateApiSettings()
                val floatingTimeoutMs = floatingSettings.timeoutSeconds * 1000
                val useVlDirectTranslate =
                    floatingSettings.useVlDirectTranslate &&
                        llmClient.isConfigured(floatingApiSettings)
                val vlOutcome = if (useVlDirectTranslate) {
                    withContext(Dispatchers.Main) {
                        showProgressStatus(
                            getString(R.string.floating_progress_vl_translating, deduplicatedRects.size)
                        )
                    }
                    floatingBubbleTranslationCoordinator.translateImageBubbles(
                        bitmap = bitmap,
                        bubbles = deduplicatedRects.mapIndexed { index, rect ->
                        BubbleTranslation(
                            id = index,
                            rect = rect,
                            originalText = "",
                            translatedText = "",
                            translationState = BubbleTranslationState.PENDING,
                            source = BubbleSource.TEXT_DETECTOR
                        )
                        },
                        timeoutMs = floatingTimeoutMs,
                        retryCount = FLOATING_TRANSLATE_RETRY_COUNT,
                        promptAsset = FLOAT_VL_PROMPT_ASSET,
                        apiSettings = floatingApiSettings,
                        concurrency = floatingSettings.ocrConcurrencyLimit,
                        maxConcurrency = MAX_FLOATING_TASK_CONCURRENCY
                    )
                } else {
                    null
                }
                if (vlOutcome?.requiresVlModel == true) {
                    withContext(Dispatchers.Main) {
                        showProgressStatus(R.string.floating_vl_model_required, autoHide = true)
                        Toast.makeText(
                            this@FloatingBallOverlayService,
                            R.string.floating_vl_model_required,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }
                val floatingLanguage = floatingSettings.language
                val translatedBubbles = if (useVlDirectTranslate) {
                    if (vlOutcome?.timedOut == true) {
                        null
                    } else {
                        vlOutcome?.bubbles ?: emptyList()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        showProgressStatus(
                            getString(R.string.floating_progress_recognizing, deduplicatedRects.size)
                        )
                    }
                    val bubbles = recognizeFloatingTextBubbles(
                        bitmap = bitmap,
                        rects = deduplicatedRects,
                        language = floatingLanguage,
                        concurrency = floatingSettings.ocrConcurrencyLimit
                    )
                    val mergedBubbles = RectGeometryDeduplicator.mergeShortTextDetectorOcrBubbles(
                        bubbles = bubbles.map { bubble ->
                            OcrBubble(
                                id = bubble.id,
                                rect = bubble.rect,
                                text = bubble.text,
                                source = bubble.source,
                                maskContour = bubble.maskContour
                            )
                        },
                        imageWidth = bitmap.width,
                        imageHeight = bitmap.height
                    ).map { bubble ->
                        BubbleTranslation(
                            id = bubble.id,
                            rect = bubble.rect,
                            originalText = bubble.text,
                            translatedText = "",
                            translationState = BubbleTranslationState.PENDING,
                            source = bubble.source,
                            maskContour = bubble.maskContour
                        )
                    }
                    if (mergedBubbles.size < bubbles.size) {
                        AppLogger.log(
                            "FloatingOCR",
                            "Merged short text detector OCR bubbles: ${bubbles.size} -> ${mergedBubbles.size}"
                        )
                    }
                    withContext(Dispatchers.Main) {
                        showProgressStatus(R.string.floating_progress_translating)
                    }
                    floatingBubbleTranslationCoordinator.translateTextBubbles(
                        bubbles = mergedBubbles,
                        timeoutMs = floatingTimeoutMs,
                        retryCount = FLOATING_TRANSLATE_RETRY_COUNT,
                        promptAsset = FLOAT_PROMPT_ASSET,
                        apiSettings = floatingApiSettings,
                        language = floatingLanguage
                    )
                }
                if (translatedBubbles == null) {
                    AppLogger.log("FloatingOCR", "Translate timeout")
                    withContext(Dispatchers.Main) {
                        showProgressStatus(R.string.floating_translate_timeout, autoHide = true)
                        Toast.makeText(
                            this@FloatingBallOverlayService,
                            R.string.floating_translate_timeout,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }
                val capturedBitmap = bitmap
                val resolvedTranslation = executeWithModelResponseRetries("FloatingOCR") {
                    val firstPass = TranslationResult(
                        imageName = "",
                        width = capturedBitmap.width,
                        height = capturedBitmap.height,
                        bubbles = translatedBubbles
                    )
                    if (firstPass.bubbles.any { it.needsTranslationRetry() }) {
                        emptyBubbleCoordinator.process(
                            bitmap = capturedBitmap,
                            baseTranslation = firstPass,
                            timeoutMs = floatingTimeoutMs,
                            retryCount = FLOATING_TRANSLATE_RETRY_COUNT,
                            floatPromptAsset = FLOAT_PROMPT_ASSET,
                            floatVlPromptAsset = FLOAT_VL_PROMPT_ASSET,
                            maxVlConcurrency = MAX_FLOATING_TASK_CONCURRENCY
                        ).let { outcome ->
                            if (outcome.requiresVlModel || outcome.timedOut) {
                                return@let firstPass
                            }
                            outcome.translation
                        }
                    } else {
                        firstPass
                    }
                }
                withContext(Dispatchers.Main) {
                    val proofreadingModeEnabled = settingsStore
                        .loadFloatingTranslateApiSettings()
                        .proofreadingModeEnabled
                    currentSession = TranslationResult(
                        imageName = "",
                        width = resolvedTranslation.width,
                        height = resolvedTranslation.height,
                        bubbles = resolvedTranslation.bubbles
                    )
                    editSessionSnapshot = null
                    createBubbleModeEnabled = false
                    syncOverlaySession()
                    replaceCurrentSessionBitmap(bitmap)
                    rebuildAutoCloseReferenceFromCurrentSession()
                    bitmap = null
                    if (proofreadingModeEnabled) {
                        enterEditMode(showToast = true)
                        controllerMenuPanel?.visibility = View.VISIBLE
                        updateEditButtons()
                        showProgressStatus(
                            getString(R.string.floating_progress_done, resolvedTranslation.bubbles.size),
                            autoHide = false
                        )
                    } else {
                        showProgressStatus(
                            getString(R.string.floating_progress_done, resolvedTranslation.bubbles.size),
                            autoHide = true
                        )
                        Toast.makeText(
                            this@FloatingBallOverlayService,
                            getString(R.string.floating_detected_count, resolvedTranslation.bubbles.size),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                AppLogger.log(
                    "FloatingOCR",
                    "Run detection finished bubbles=${resolvedTranslation.bubbles.size}"
                )
            } catch (e: LlmResponseException) {
                AppLogger.log("FloatingOCR", "Floating detection model response invalid", e)
                withContext(Dispatchers.Main) {
                    showModelErrorDialog(
                        responseContent = e.responseContent,
                        onContinue = { runTextDetection() }
                    )
                }
            } catch (e: LlmRequestException) {
                AppLogger.log("FloatingOCR", "Floating detection request failed", e)
                withContext(Dispatchers.Main) {
                    showApiErrorDialog(e.errorCode, e.responseBody)
                }
            } catch (e: Exception) {
                AppLogger.log("FloatingOCR", "Floating detection failed", e)
                withContext(Dispatchers.Main) {
                    showProgressStatus(R.string.floating_detect_failed, autoHide = true)
                    Toast.makeText(
                        this@FloatingBallOverlayService,
                        R.string.floating_detect_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                bitmap?.recycle()
                withContext(Dispatchers.Main) {
                    if (detectJob === runningJob) {
                        detectJob = null
                    }
                    updateAutoCloseDetectionState()
                }
            }
        }
    }

    private suspend fun recognizeFloatingTextBubbles(
        bitmap: Bitmap,
        rects: List<RectF>,
        language: TranslationLanguage,
        concurrency: Int
    ): List<BubbleTranslation> = coroutineScope {
        val semaphore = Semaphore(concurrency.coerceIn(1, MAX_FLOATING_TASK_CONCURRENCY))
        rects.mapIndexed { index, rect ->
            async(Dispatchers.Default) {
                semaphore.withPermit {
                    val crop = cropBitmap(bitmap, rect)
                    if (crop == null) {
                        return@withPermit BubbleTranslation.pending(
                            id = index,
                            rect = rect,
                            originalText = "",
                            source = BubbleSource.TEXT_DETECTOR
                        )
                    }
                    val text = try {
                        recognizeFloatingBubbleText(crop, language)
                    } catch (e: Exception) {
                        AppLogger.log(
                            "FloatingOCR",
                            "Floating OCR recognize failed language=${language.name}",
                            e
                        )
                        ""
                    } finally {
                        crop.recycleSafely()
                    }
                    BubbleTranslation.pending(
                        id = index,
                        rect = rect,
                        originalText = text,
                        source = BubbleSource.TEXT_DETECTOR
                    )
                }
            }
        }.awaitAll()
    }

    private fun showModelErrorDialog(
        responseContent: String,
        onContinue: (() -> Unit)?
    ) {
        blankBubbleErrorDialog?.dismiss()
        val dialog = com.manga.translate.showModelErrorDialog(
            context = this,
            responseContent = responseContent,
            onRetry = onContinue,
            windowType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
        )
        dialog.setOnDismissListener {
            if (blankBubbleErrorDialog === dialog) {
                blankBubbleErrorDialog = null
            }
        }
        blankBubbleErrorDialog = dialog
    }

    private fun showApiErrorDialog(
        errorCode: String,
        detail: String?
    ) {
        blankBubbleErrorDialog?.dismiss()
        val message = getString(
            R.string.api_request_failed_message,
            ErrorDialogFormatter.formatApiErrorMessage(this, errorCode, detail)
        )
        val dialog = createAlertDialogBuilder(this)
            .setTitle(R.string.api_request_failed_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .createWithScrollableMessage()
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.setType(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
        )
        dialog.setOnDismissListener {
            if (blankBubbleErrorDialog === dialog) {
                blankBubbleErrorDialog = null
            }
        }
        blankBubbleErrorDialog = dialog
        dialog.show()
    }

    private suspend fun <T> executeWithModelResponseRetries(
        logTag: String,
        block: suspend () -> T
    ): T {
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


    private fun attachBallGesture(
        target: FloatingBallImageView,
        menuPanel: View,
        params: WindowManager.LayoutParams
    ) {
        val touchSlop = (3f * resources.displayMetrics.density)
        val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        var downRawX = 0f
        var downRawY = 0f
        var downX = 0
        var downY = 0
        var dragging = false
        var isPointerDown = false
        var longPressTriggered = false
        var tapCount = 0
        val commitTapRunnable = Runnable {
            if (isPointerDown) {
                return@Runnable
            }
            val gestureSettings = settingsStore.loadFloatingTranslateApiSettings()
            when (tapCount.coerceAtMost(3)) {
                1 -> target.performClick()
                2 -> performFloatingBallGestureAction(gestureSettings.doubleTapAction, menuPanel)
                3 -> performFloatingBallGestureAction(gestureSettings.tripleTapAction, menuPanel)
            }
            tapCount = 0
        }
        val longPressRunnable = Runnable {
            if (!isPointerDown || dragging || tapCount != 0) {
                return@Runnable
            }
            longPressTriggered = true
            performFloatingBallGestureAction(
                settingsStore.loadFloatingTranslateApiSettings().longPressAction,
                menuPanel
            )
        }
        target.onPerformClick = {
            performFloatingBallGestureAction(
                settingsStore.loadFloatingTranslateApiSettings().singleTapAction,
                menuPanel
            )
        }
        target.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downX = params.x
                    downY = params.y
                    dragging = false
                    isPointerDown = true
                    longPressTriggered = false
                    updateFloatingBallPressedState(target, pressed = true)
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (tapCount == 0) {
                        mainHandler.postDelayed(longPressRunnable, longPressTimeout)
                    }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    val shouldStartDragging = abs(dx) > touchSlop || abs(dy) > touchSlop
                    if (menuPanel.isVisible && shouldStartDragging) {
                        menuPanel.visibility = View.GONE
                    }
                    if (!dragging && shouldStartDragging) {
                        mainHandler.removeCallbacks(longPressRunnable)
                        mainHandler.removeCallbacks(commitTapRunnable)
                        tapCount = 0
                        dragging = true
                        updateFloatingBallPressedState(target, pressed = false)
                    }
                    if (!dragging) {
                        return@setOnTouchListener true
                    }
                    params.x = (downX + dx).toInt().coerceAtLeast(0)
                    params.y = (downY + dy).toInt().coerceAtLeast(0)
                    windowManager.updateViewLayout(controllerRoot, params)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    isPointerDown = false
                    updateFloatingBallPressedState(target, pressed = false)
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (dragging) {
                        dragging = false
                        return@setOnTouchListener true
                    }
                    if (longPressTriggered) {
                        longPressTriggered = false
                        return@setOnTouchListener true
                    }
                    tapCount = (tapCount + 1).coerceAtMost(3)
                    mainHandler.removeCallbacks(commitTapRunnable)
                    if (tapCount >= 3) {
                        performFloatingBallGestureAction(
                            settingsStore.loadFloatingTranslateApiSettings().tripleTapAction,
                            menuPanel
                        )
                        tapCount = 0
                    } else {
                        mainHandler.postDelayed(commitTapRunnable, doubleTapTimeout)
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    isPointerDown = false
                    tapCount = 0
                    longPressTriggered = false
                    mainHandler.removeCallbacks(longPressRunnable)
                    mainHandler.removeCallbacks(commitTapRunnable)
                    dragging = false
                    updateFloatingBallPressedState(target, pressed = false)
                    false
                }

                else -> true
            }
        }
    }

    private fun removeOverlay() {
        stopAutoCloseDetection()
        val root = controllerRoot
        if (root != null) {
            try {
                windowManager.removeView(root)
            } catch (_: Exception) {
            }
        }
        val detection = detectionOverlayView
        if (detection != null) {
            try {
                windowManager.removeView(detection)
            } catch (_: Exception) {
            }
        }
        controllerRoot = null
        controllerLayoutParams = null
        controllerMenuPanel = null
        controllerBallView = null
        detectionOverlayView = null
        detectionLayoutParams = null
        editModeToggleButton = null
        swipeTranslateButton = null
        addBubbleButton = null
        confirmEditButton = null
        cancelEditButton = null
        progressStatusView = null
    }

    private fun releaseProjection() {
        screenCaptureSession.release()
    }

    private fun shouldRunAutoCloseDetection(): Boolean {
        val settings = settingsStore.loadFloatingTranslateApiSettings()
        return settings.autoCloseOnScreenChangeEnabled &&
            currentSession != null &&
            !editModeEnabled &&
            !createBubbleModeEnabled &&
            detectJob?.isActive != true &&
            screenCaptureSession.isReady() &&
            autoCloseReferenceFrame != null
    }

    private fun updateAutoCloseDetectionState() {
        if (shouldRunAutoCloseDetection()) {
            startAutoCloseDetection()
        } else {
            stopAutoCloseDetection()
        }
    }

    private fun startAutoCloseDetection() {
        if (!shouldRunAutoCloseDetection()) return
        mainHandler.removeCallbacks(autoCloseCheckRunnable)
        scheduleNextAutoCloseCheck()
    }

    private fun stopAutoCloseDetection() {
        mainHandler.removeCallbacks(autoCloseCheckRunnable)
        autoCloseCheckJob?.cancel()
        autoCloseCheckJob = null
    }

    private fun scheduleNextAutoCloseCheck() {
        if (!shouldRunAutoCloseDetection()) return
        mainHandler.removeCallbacks(autoCloseCheckRunnable)
        mainHandler.postDelayed(autoCloseCheckRunnable, AUTO_CLOSE_SCREEN_CHECK_INTERVAL_MS)
    }

    private fun rebuildAutoCloseReferenceFromCurrentSession() {
        val bitmap = currentSessionBitmap
        autoCloseReferenceFrame?.bitmap?.recycle()
        autoCloseReferenceFrame = bitmap?.let { createScreenChangeReferenceFrame(it) }
        updateAutoCloseDetectionState()
    }

    private fun clearAutoCloseReference() {
        autoCloseReferenceFrame?.bitmap?.recycle()
        autoCloseReferenceFrame = null
        stopAutoCloseDetection()
    }

    private suspend fun detectScreenChangeAgainstReference(): Boolean {
        val reference = autoCloseReferenceFrame ?: return false
        val currentScreen = screenCaptureSession.captureCurrentScreen(
            timeoutMs = AUTO_CLOSE_CAPTURE_TIMEOUT_MS,
            requireFreshFrame = true
        ) ?: return false
        try {
            val current = createScreenChangeReferenceFrame(currentScreen) ?: return false
            try {
                return hasMeaningfulScreenChange(reference, current)
            } finally {
                current.bitmap.recycle()
            }
        } finally {
            currentScreen.recycle()
        }
    }

    private fun createScreenChangeReferenceFrame(source: Bitmap): ScreenChangeReferenceFrame? {
        if (source.width <= 0 || source.height <= 0) return null
        val targetWidth = AUTO_CLOSE_REFERENCE_WIDTH
        val targetHeight = max(1, (targetWidth * source.height.toFloat() / source.width.toFloat()).toInt())
        val scaled = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
        val ignoreTop = (targetHeight * AUTO_CLOSE_IGNORE_TOP_RATIO).toInt()
        val ignoreBottom = (targetHeight * AUTO_CLOSE_IGNORE_BOTTOM_RATIO).toInt()
        val sideInset = (targetWidth * AUTO_CLOSE_IGNORE_SIDE_RATIO).toInt()
        val cropLeft = sideInset.coerceIn(0, targetWidth - 1)
        val cropTop = ignoreTop.coerceIn(0, targetHeight - 1)
        val cropRight = (targetWidth - sideInset).coerceIn(cropLeft + 1, targetWidth)
        val cropBottom = (targetHeight - ignoreBottom).coerceIn(cropTop + 1, targetHeight)
        return ScreenChangeReferenceFrame(
            bitmap = scaled,
            sampleRect = Rect(cropLeft, cropTop, cropRight, cropBottom)
        )
    }

    private fun hasMeaningfulScreenChange(
        reference: ScreenChangeReferenceFrame,
        current: ScreenChangeReferenceFrame
    ): Boolean {
        val left = max(reference.sampleRect.left, current.sampleRect.left)
        val top = max(reference.sampleRect.top, current.sampleRect.top)
        val right = min(reference.sampleRect.right, current.sampleRect.right)
        val bottom = min(reference.sampleRect.bottom, current.sampleRect.bottom)
        if (right <= left || bottom <= top) return false
        var sampled = 0
        var changed = 0
        var totalDelta = 0
        var rowsWithChange = 0
        val sampledRows = max(1, ((bottom - top) + AUTO_CLOSE_SAMPLE_STEP - 1) / AUTO_CLOSE_SAMPLE_STEP)
        val rowChangeThreshold = max(1, ((right - left) / AUTO_CLOSE_SAMPLE_STEP) / 4)
        for (y in top until bottom step AUTO_CLOSE_SAMPLE_STEP) {
            var rowChangedPixels = 0
            for (x in left until right step AUTO_CLOSE_SAMPLE_STEP) {
                val delta = pixelDelta(reference.bitmap.getPixel(x, y), current.bitmap.getPixel(x, y))
                sampled++
                totalDelta += delta
                if (delta >= AUTO_CLOSE_SIGNIFICANT_PIXEL_DELTA) {
                    changed++
                    rowChangedPixels++
                }
            }
            if (rowChangedPixels >= rowChangeThreshold) {
                rowsWithChange++
            }
        }
        if (sampled == 0) return false
        val changedRatio = changed.toFloat() / sampled.toFloat()
        val averageDelta = totalDelta.toFloat() / sampled.toFloat()
        val rowChangedRatio = rowsWithChange.toFloat() / sampledRows.toFloat()
        AppLogger.log(
            "FloatingOCR",
            "Auto close sampled=$sampled changedRatio=$changedRatio averageDelta=$averageDelta rowChangedRatio=$rowChangedRatio"
        )
        return changedRatio >= AUTO_CLOSE_CHANGED_PIXEL_RATIO_THRESHOLD &&
            averageDelta >= AUTO_CLOSE_AVERAGE_DELTA_THRESHOLD &&
            rowChangedRatio >= AUTO_CLOSE_CHANGED_ROW_RATIO_THRESHOLD
    }

    private fun pixelDelta(first: Int, second: Int): Int {
        val dr = abs(((first shr 16) and 0xFF) - ((second shr 16) and 0xFF))
        val dg = abs(((first shr 8) and 0xFF) - ((second shr 8) and 0xFF))
        val db = abs((first and 0xFF) - (second and 0xFF))
        return (dr + dg + db) / 3
    }

    private fun TranslationResult.deepCopy(): TranslationResult {
        return copy(
            bubbles = bubbles.map { bubble ->
                bubble.copy(rect = RectF(bubble.rect))
            }
        )
    }

    private object ServiceInfoForegroundTypes {
        const val MEDIA_PROJECTION = 0x00000020
    }

    companion object {
        const val ACTION_START = "com.manga.translate.action.FLOATING_START"
        const val ACTION_STOP = "com.manga.translate.action.FLOATING_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val FLOATING_PROGRESS_HIDE_DELAY_MS = 2_000L
        private const val CHANNEL_ID = "floating_detect_channel"
        private const val NOTIFICATION_ID = 2002
        private const val FLOAT_PROMPT_ASSET = "prompts/float_llm_prompts.json"
        private const val FLOAT_VL_PROMPT_ASSET = "prompts/vl_bubble_prompts.json"
        private const val FLOATING_TRANSLATE_RETRY_COUNT = 1
        private const val MODEL_RESPONSE_SILENT_RETRY_COUNT = 3
        private const val MAX_FLOATING_TASK_CONCURRENCY = 16
        private const val AUTO_CLOSE_SCREEN_CHECK_INTERVAL_MS = 900L
        private const val AUTO_CLOSE_CAPTURE_TIMEOUT_MS = 1200L
        private const val AUTO_CLOSE_REFERENCE_WIDTH = 180
        private const val AUTO_CLOSE_IGNORE_TOP_RATIO = 0.12f
        private const val AUTO_CLOSE_IGNORE_BOTTOM_RATIO = 0.14f
        private const val AUTO_CLOSE_IGNORE_SIDE_RATIO = 0.04f
        private const val AUTO_CLOSE_SAMPLE_STEP = 3
        private const val AUTO_CLOSE_SIGNIFICANT_PIXEL_DELTA = 32
        private const val AUTO_CLOSE_CHANGED_PIXEL_RATIO_THRESHOLD = 0.12f
        private const val AUTO_CLOSE_AVERAGE_DELTA_THRESHOLD = 14f
        private const val AUTO_CLOSE_CHANGED_ROW_RATIO_THRESHOLD = 0.18f
    }
}

private data class ScreenChangeReferenceFrame(
    val bitmap: Bitmap,
    val sampleRect: Rect
)

private fun Intent.getParcelableIntentExtraCompat(key: String): Intent? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, Intent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }
}
