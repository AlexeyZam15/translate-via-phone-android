package com.example.screentranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class PcOcrService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var mjpegReader: MJPEGReader? = null
    private var ocrEngine: OcrEngine? = null
    private var sender: OcrSender? = null
    private var translator: OcrTranslator? = null

    private val isProcessing = AtomicBoolean(false)

    private var engineType: OcrType = OcrType.ML_KIT
    private var confidenceThreshold: Float = 0f
    private var sourceLang: String? = null
    private var targetLang: String = "ru"
    private var debugMode: Boolean = false
    private var overlayAlpha: Int = 217

    // Кэш предыдущего результата — чтобы не отправлять неизменённое
    @Volatile private var lastBlocksHash: Int = 0
    @Volatile private var lastMode: String = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engineName = intent?.getStringExtra(EXTRA_ENGINE) ?: OcrType.ML_KIT.name
        engineType = runCatching { OcrType.valueOf(engineName) }.getOrDefault(OcrType.ML_KIT)

        DebugTimingLogger.init(applicationContext)
        DebugTimingLogger.logEvent("android.pc_ocr_service_start",
            mapOf("engine" to engineType.name))

        loadSettings()
        Settings.prefs(this).registerOnSharedPreferenceChangeListener(this)

        val mjpegUrl = "http://localhost:5005/stream"
        val ocrUrl = "http://localhost:5006/ocr"

        startForeground(NOTIFICATION_ID, buildNotification())

        ocrEngine = OcrEngineFactory.create(this, engineType)
        sender = OcrSender(ocrUrl)
        translator = OcrTranslator(this, targetLang, sourceLang)

        scope.launch { translator?.prepare() }

        mjpegReader = MJPEGReader(mjpegUrl) { jpegBytes -> onFrame(jpegBytes) }
        mjpegReader?.start()

        Log.d(TAG, "PcOcrService started: threshold=$confidenceThreshold, " +
                "source=${sourceLang ?: "auto"}, target=$targetLang, " +
                "debug=$debugMode, alpha=$overlayAlpha")

        return START_NOT_STICKY
    }

    private fun loadSettings() {
        debugMode = Settings.isDebug(this)
        confidenceThreshold = Settings.getConfidence(this) / 100f
        val srcRaw = Settings.getSourceLang(this)
        sourceLang = if (srcRaw.isEmpty()) null else srcRaw
        targetLang = Settings.getTargetLang(this)
        overlayAlpha = Settings.alphaFromPercent(Settings.getOverlayAlpha(this))
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        loadSettings()
        if (key == Settings.KEY_SOURCE_LANG || key == Settings.KEY_TARGET_LANG) {
            scope.launch {
                translator?.close()
                translator = OcrTranslator(this@PcOcrService, targetLang, sourceLang)
                translator?.prepare()
            }
        }
    }

    private fun onFrame(jpegBytes: ByteArray) {
        if (!isProcessing.compareAndSet(false, true)) return

        val tCycleStart = System.nanoTime()
        scope.launch {
            var blockCount = 0
            var mode = "translate"
            var sent = false
            try {
                val bitmap: Bitmap? = DebugTimingLogger.timed("android.pc_ocr_decode") {
                    BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                }
                if (bitmap == null) {
                    Log.w(TAG, "decode failed")
                    DebugTimingLogger.logEvent("android.pc_ocr_decode_failed")
                    return@launch
                }

                val fw = bitmap.width
                val fh = bitmap.height

                val engine = ocrEngine ?: return@launch
                val result = DebugTimingLogger.timed("android.pc_ocr_recognize") {
                    try {
                        engine.recognize(bitmap)
                    } catch (e: Exception) {
                        Log.e(TAG, "ocr error", e)
                        DebugTimingLogger.logEvent("android.pc_ocr_error",
                            mapOf("error" to e.message.orEmpty()))
                        null
                    }
                }

                bitmap.recycle()

                if (result == null || result.blocks.isEmpty()) return@launch

                val filtered = if (debugMode) {
                    result.blocks
                } else if (confidenceThreshold > 0f) {
                    result.blocks.filter { it.confidence >= confidenceThreshold }
                } else {
                    result.blocks
                }

                if (filtered.isEmpty()) return@launch

                mode = if (debugMode) "debug" else "translate"

                if (debugMode || translator?.isReady() != true) {
                    blockCount = filtered.size
                    val hash = computeBlocksHash(filtered, mode)
                    if (hash == lastBlocksHash && mode == lastMode) {
                        // Ничего не изменилось — не отправляем
                        return@launch
                    }
                    lastBlocksHash = hash
                    lastMode = mode

                    DebugTimingLogger.timed("android.pc_ocr_send") {
                        sender?.send(OcrResult(result.text, filtered), fw, fh, mode, overlayAlpha)
                    }
                    sent = true
                    return@launch
                }

                val translated = DebugTimingLogger.timed("android.pc_ocr_translate_all") {
                    translator?.translateAll(filtered.map { it.text }) ?: emptyList()
                }
                val map = translated.toMap()
                val outBlocks = filtered.mapNotNull { block ->
                    val t = map[block.text] ?: return@mapNotNull null
                    block.copy(text = t)
                }

                blockCount = outBlocks.size

                if (outBlocks.isNotEmpty()) {
                    val hash = computeBlocksHash(outBlocks, mode)
                    if (hash == lastBlocksHash && mode == lastMode) {
                        // Ничего не изменилось — не отправляем
                        return@launch
                    }
                    lastBlocksHash = hash
                    lastMode = mode

                    DebugTimingLogger.timed("android.pc_ocr_send") {
                        sender?.send(OcrResult(result.text, outBlocks), fw, fh, mode, overlayAlpha)
                    }
                    sent = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "onFrame error", e)
                DebugTimingLogger.logEvent("android.pc_ocr_cycle_error",
                    mapOf("error" to e.message.orEmpty()))
            } finally {
                val ms = (System.nanoTime() - tCycleStart) / 1_000_000.0
                DebugTimingLogger.logTiming(
                    "android.pc_ocr_cycle_total", ms,
                    mapOf(
                        "blocks" to blockCount,
                        "mode" to mode,
                        "jpeg_kb" to jpegBytes.size / 1024,
                        "sent" to sent
                    )
                )
                // УБРАН delay(100) — следующий кадр обрабатывается сразу,
                // как только MJPEGReader получит новый.
                isProcessing.set(false)
            }
        }
    }

    /**
     * Хэш блоков: текст + координаты + уверенность.
     * Если блоки не изменились — хэш совпадёт, и отправка пропускается.
     */
    private fun computeBlocksHash(blocks: List<OcrBlock>, mode: String): Int {
        var h = mode.hashCode()
        for (b in blocks) {
            h = h * 31 + b.text.hashCode()
            val r = b.boundingBox
            h = h * 31 + r.left
            h = h * 31 + r.top
            h = h * 31 + r.right
            h = h * 31 + r.bottom
        }
        return h
    }

    private fun buildNotification(): Notification {
        val channelId = "pc_ocr"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "PC OCR", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        val mode = if (debugMode) "debug" else "translate"
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("ScreenTranslator")
            .setContentText("OCR ПК активен ($mode, localhost через ADB)")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        DebugTimingLogger.logEvent("android.pc_ocr_service_stop")
        try {
            Settings.prefs(this).unregisterOnSharedPreferenceChangeListener(this)
        } catch (_: Exception) {}
        mjpegReader?.stop()
        mjpegReader = null
        ocrEngine?.close()
        ocrEngine = null
        sender = null
        translator?.close()
        translator = null
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
        Log.d(TAG, "PcOcrService stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "PcOcrService"
        private const val NOTIFICATION_ID = 2
        const val EXTRA_ENGINE = "engine"
    }
}