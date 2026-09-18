package com.example.screentranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class OverlayService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private var zoneOverlay: ZoneOverlayView? = null
    private var captureHelper: ScreenCaptureHelper? = null
    private var ocrEngine: OcrEngine? = null
    private var translator: OcrTranslator? = null

    private var ocrType: OcrType = OcrType.ML_KIT
    private var zonesVisible = false
    private var isProcessing = false

    private var debugMode: Boolean = false
    private var confidenceThreshold: Float = 0f
    private var sourceLang: String? = null
    private var targetLang: String = "ru"
    private var overlayAlpha: Int = 217  // 85% от 255

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ocrType = intent?.getStringExtra(EXTRA_OCR_TYPE)
            ?.let { runCatching { OcrType.valueOf(it) }.getOrNull() }
            ?: OcrType.ML_KIT

        // Читаем настройки из SharedPreferences
        loadSettings()

        // Регистрируем listener для real-time изменений
        Settings.prefs(this).registerOnSharedPreferenceChangeListener(this)

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        if (resultData != null) {
            initProjection(resultCode, resultData)
        } else {
            Log.e(TAG, "MediaProjection resultData == null")
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun loadSettings() {
        debugMode = Settings.isDebug(this)
        confidenceThreshold = Settings.getConfidence(this) / 100f
        val srcRaw = Settings.getSourceLang(this)
        sourceLang = if (srcRaw.isEmpty()) null else srcRaw
        targetLang = Settings.getTargetLang(this)
        overlayAlpha = Settings.alphaFromPercent(Settings.getOverlayAlpha(this))
        Log.d(TAG, "Settings loaded: debug=$debugMode, conf=$confidenceThreshold, " +
                "source=${sourceLang ?: "auto"}, target=$targetLang, alpha=$overlayAlpha")
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        Log.d(TAG, "Preference changed: $key")
        loadSettings()
        // Обновляем оверлей, если он виден
        serviceScope.launch {
            if (zonesVisible) {
                runOcrAndShow()
            }
        }
    }

    private fun initProjection(resultCode: Int, resultData: Intent) {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection: MediaProjection = mpm.getMediaProjection(resultCode, resultData)
            ?: run {
                Log.e(TAG, "MediaProjection == null")
                stopSelf()
                return
            }

        val helper = ScreenCaptureHelper(this)
        helper.start(projection)
        captureHelper = helper

        ocrEngine = OcrEngineFactory.create(this, ocrType)
        translator = OcrTranslator(targetLang, sourceLang)

        serviceScope.launch {
            translator?.prepare()
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createFloatingIcon()
    }

    private fun createFloatingIcon() {
        floatingView = LayoutInflater.from(this)
            .inflate(R.layout.floating_icon, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80
            y = 200
        }

        var isDrag = false
        var downTime = 0L
        var downX = 0f
        var downY = 0f

        floatingView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downTime = System.currentTimeMillis()
                    downX = event.rawX
                    downY = event.rawY
                    isDrag = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isDrag = true
                        params.x += dx
                        params.y += dy
                        downX = event.rawX
                        downY = event.rawY
                        windowManager.updateViewLayout(floatingView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = System.currentTimeMillis() - downTime
                    if (!isDrag) {
                        if (elapsed > 600) {
                            runOcrAndShow()
                        } else {
                            toggleZones()
                        }
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(floatingView, params)
    }

    private fun toggleZones() {
        if (zonesVisible) {
            hideZones()
        } else {
            runOcrAndShow()
        }
    }

    private fun hideZones() {
        zoneOverlay?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        zoneOverlay = null
        zonesVisible = false
    }

    private fun runOcrAndShow() {
        if (isProcessing) return
        isProcessing = true

        serviceScope.launch {
            delay(200)

            val bitmap: Bitmap? = withContext(Dispatchers.Default) {
                captureHelper?.capture()
            }
            if (bitmap == null) {
                isProcessing = false
                return@launch
            }

            val bmpW = bitmap.width
            val bmpH = bitmap.height

            val result = try {
                withContext(Dispatchers.Default) {
                    ocrEngine?.recognize(bitmap)
                }
            } catch (e: Exception) {
                bitmap.recycle()
                isProcessing = false
                return@launch
            }
            bitmap.recycle()

            if (result == null || result.blocks.isEmpty()) {
                isProcessing = false
                return@launch
            }

            val filtered = if (debugMode) {
                result.blocks
            } else if (confidenceThreshold > 0f) {
                result.blocks.filter { it.confidence >= confidenceThreshold }
            } else {
                result.blocks
            }

            if (filtered.isEmpty()) {
                isProcessing = false
                return@launch
            }

            if (debugMode || translator?.isReady() != true) {
                showZones(filtered, bmpW, bmpH,
                    showConfidence = true, showTranslation = false)
            } else {
                val translated = withContext(Dispatchers.Default) {
                    translator?.translateAll(filtered.map { it.text }) ?: emptyList()
                }
                val map = translated.toMap()
                val translatedBlocks = filtered.mapNotNull { block ->
                    val t = map[block.text] ?: return@mapNotNull null
                    block.copy(text = t)
                }

                if (translatedBlocks.isEmpty()) {
                    hideZones()
                } else {
                    showZones(translatedBlocks, bmpW, bmpH,
                        showConfidence = false, showTranslation = true)
                }
            }

            isProcessing = false
        }
    }

    private fun showZones(
        blocks: List<OcrBlock>,
        sourceW: Int,
        sourceH: Int,
        showConfidence: Boolean,
        showTranslation: Boolean
    ) {
        hideZones()

        val view = ZoneOverlayView(this)
        view.setBlocks(blocks, sourceW, sourceH, showConfidence, showTranslation, overlayAlpha)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(view, params)
        zoneOverlay = view
        zonesVisible = true
    }

    private fun buildNotification(): Notification {
        val channelId = "ocr_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "OCR Overlay",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }

        val modeSuffix = if (debugMode) "debug" else "translate"
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("ScreenTranslator")
            .setContentText("Активен: ${ocrType.displayName} | $modeSuffix")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        try {
            Settings.prefs(this).unregisterOnSharedPreferenceChangeListener(this)
        } catch (_: Exception) {}
        try {
            if (::floatingView.isInitialized) windowManager.removeView(floatingView)
        } catch (_: Exception) {
        }
        hideZones()
        captureHelper?.stop()
        captureHelper = null
        ocrEngine?.close()
        ocrEngine = null
        translator?.close()
        translator = null
        serviceScope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "OverlayService"
        const val EXTRA_OCR_TYPE = "ocr_type"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val NOTIFICATION_ID = 1
    }
}