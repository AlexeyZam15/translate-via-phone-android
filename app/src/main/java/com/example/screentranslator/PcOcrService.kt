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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PcOcrService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var mjpegReader: MJPEGReader? = null
    private var ocrEngine: OcrEngine? = null
    private var sender: OcrSender? = null

    // Всегда BenchmarkTranslator (CTranslate2 + ML Kit fallback)
    private var benchmarkTranslator: BenchmarkTranslator? = null

    private data class OcrJob(
        val blocks: List<OcrBlock>,
        val rawText: String,
        val frameW: Int,
        val frameH: Int,
        val mode: String,
        val overlayAlpha: Int,
        val isEmpty: Boolean
    )

    private val translationQueue = Channel<OcrJob>(capacity = 2)
    private var translationWorker: Job? = null

    private val ocrBusy = AtomicBoolean(false)

    @Volatile private var queuedHash: Int = 0
    @Volatile private var queuedMode: String = ""

    private var engineType: OcrType = OcrType.ML_KIT
    private var confidenceThreshold: Float = 0f
    private var sourceLang: String? = null
    private var targetLang: String = "ru"
    private var debugMode: Boolean = false
    private var overlayAlpha: Int = 217

    @Volatile private var lastSentBlocksCount: Int = 0
    @Volatile private var lastMode: String = ""

    private val droppedCount = AtomicInteger(0)

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

        benchmarkTranslator = BenchmarkTranslator(this, targetLang, sourceLang)

        startTranslationWorker()

        // MJPEG стартует после prepare() (или таймаута 8 сек)
        scope.launch {
            launch { benchmarkTranslator?.prepare() }
            withTimeoutOrNull(8000L) {
                while (benchmarkTranslator?.isReady() != true) {
                    delay(100)
                }
            }
            DebugTimingLogger.logEvent("android.pc_ocr_benchmark_ready_check",
                mapOf("ready" to (benchmarkTranslator?.isReady() == true)))
            mjpegReader = MJPEGReader(mjpegUrl) { jpegBytes -> onFrame(jpegBytes) }
            mjpegReader?.start()
        }

        Log.d(TAG, "PcOcrService started: CTranslate2+MLKit, " +
                "threshold=$confidenceThreshold, source=${sourceLang ?: "auto"}, " +
                "target=$targetLang, debug=$debugMode, alpha=$overlayAlpha")

        return START_NOT_STICKY
    }

    private fun startTranslationWorker() {
        translationWorker?.cancel()
        translationWorker = scope.launch {
            for (job in translationQueue) {
                try {
                    processJob(job)
                } catch (e: Exception) {
                    Log.e(TAG, "processJob error", e)
                    DebugTimingLogger.logEvent("android.pc_ocr_worker_error",
                        mapOf("error" to e.message.orEmpty()))
                }
            }
        }
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
                benchmarkTranslator?.close()
                benchmarkTranslator = BenchmarkTranslator(
                    this@PcOcrService, targetLang, sourceLang)
                benchmarkTranslator?.prepare()
            }
        }
    }

    private fun onFrame(jpegBytes: ByteArray) {
        if (!ocrBusy.compareAndSet(false, true)) return

        scope.launch {
            try {
                val bitmap: Bitmap? = DebugTimingLogger.timed("android.pc_ocr_decode") {
                    BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                }
                if (bitmap == null) {
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
                        DebugTimingLogger.logEvent("android.pc_ocr_error",
                            mapOf("error" to e.message.orEmpty()))
                        null
                    }
                }

                bitmap.recycle()

                // Бенчмарк (ML Kit + HTTP), результат — HTTP или ML Kit fallback
                if (result != null && result.blocks.isNotEmpty()) {
                    val texts = result.blocks.map { it.text }
                    DebugTimingLogger.timed("android.pc_ocr_benchmark") {
                        benchmarkTranslator?.translateAll(texts)
                    }
                }

                val benchmarkReady = benchmarkTranslator?.isReady() == true
                if (!debugMode && !benchmarkReady) {
                    if (lastSentBlocksCount > 0) {
                        lastSentBlocksCount = 0
                        lastMode = ""
                        translationQueue.trySend(OcrJob(
                            blocks = emptyList(), rawText = "",
                            frameW = fw, frameH = fh,
                            mode = "translate", overlayAlpha = overlayAlpha,
                            isEmpty = true
                        ))
                    }
                    return@launch
                }

                val job = if (result == null) {
                    if (lastSentBlocksCount > 0) {
                        lastSentBlocksCount = 0
                        lastMode = ""
                        OcrJob(emptyList(), "", fw, fh, "translate", overlayAlpha, true)
                    } else null
                } else {
                    val filtered = if (debugMode) result.blocks
                    else if (confidenceThreshold > 0f) result.blocks.filter { it.confidence >= confidenceThreshold }
                    else result.blocks

                    if (filtered.isEmpty()) {
                        if (lastSentBlocksCount > 0) {
                            lastSentBlocksCount = 0
                            lastMode = ""
                            OcrJob(emptyList(), result.text, fw, fh, "translate", overlayAlpha, true)
                        } else null
                    } else {
                        val mode = if (debugMode) "debug" else "translate"
                        OcrJob(filtered, result.text, fw, fh, mode, overlayAlpha, false)
                    }
                }

                if (job != null) {
                    if (!job.isEmpty) {
                        val hash = computeBlocksHash(job.blocks, job.mode, job.overlayAlpha)
                        if (hash == queuedHash && job.mode == queuedMode) return@launch
                        queuedHash = hash
                        queuedMode = job.mode
                    }
                    if (!translationQueue.trySend(job).isSuccess) {
                        droppedCount.incrementAndGet()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "onFrame error", e)
            } finally {
                ocrBusy.set(false)
            }
        }
    }

    private suspend fun processJob(job: OcrJob) {
        val tStart = System.nanoTime()
        var blockCount = 0
        try {
            if (job.isEmpty || job.blocks.isEmpty()) {
                sender?.send(OcrResult(job.rawText, emptyList()),
                    job.frameW, job.frameH, "translate", job.overlayAlpha, force = true)
                lastSentBlocksCount = 0
                lastMode = ""
                return
            }

            if (job.mode == "debug") {
                blockCount = job.blocks.size
                sender?.send(OcrResult(job.rawText, job.blocks),
                    job.frameW, job.frameH, "debug", job.overlayAlpha)
                lastSentBlocksCount = job.blocks.size
                lastMode = "debug"
                return
            }

            // translate — используем BenchmarkTranslator.
            // translateAll вернёт HTTP (CTranslate2), если он есть,
            // иначе — ML Kit (fallback).
            val translated = benchmarkTranslator?.translateAll(
                job.blocks.map { it.text }) ?: emptyList()
            val map = translated.toMap()
            val outBlocks = job.blocks.mapNotNull { block ->
                val t = map[block.text] ?: return@mapNotNull null
                block.copy(text = t)
            }

            blockCount = outBlocks.size
            if (outBlocks.isEmpty()) {
                sender?.send(OcrResult(job.rawText, emptyList()),
                    job.frameW, job.frameH, "translate", job.overlayAlpha, force = true)
                lastSentBlocksCount = 0
                lastMode = ""
                return
            }

            sender?.send(OcrResult(job.rawText, outBlocks),
                job.frameW, job.frameH, "translate", job.overlayAlpha)
            lastSentBlocksCount = outBlocks.size
            lastMode = "translate"
        } finally {
            val ms = (System.nanoTime() - tStart) / 1_000_000.0
            DebugTimingLogger.logTiming("android.pc_ocr_cycle_total", ms,
                mapOf("blocks" to blockCount, "mode" to job.mode))
        }
    }

    private fun computeBlocksHash(blocks: List<OcrBlock>, mode: String, alpha: Int): Int {
        var h = mode.hashCode()
        h = h * 31 + alpha
        for (b in blocks) {
            h = h * 31 + b.text.hashCode()
            val r = b.boundingBox
            h = h * 31 + (r.left / COORD_BUCKET)
            h = h * 31 + (r.top / COORD_BUCKET)
            h = h * 31 + (r.right / COORD_BUCKET)
            h = h * 31 + (r.bottom / COORD_BUCKET)
        }
        return h
    }

    private fun buildNotification(): Notification {
        val channelId = "pc_ocr"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "PC OCR",
                NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("ScreenTranslator")
            .setContentText("OCR ПК активен (CTranslate2 + ML Kit fallback)")
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
        translationWorker?.cancel()
        translationWorker = null
        translationQueue.close()
        ocrEngine?.close()
        ocrEngine = null
        sender = null
        benchmarkTranslator?.close()
        benchmarkTranslator = null
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "PcOcrService"
        private const val NOTIFICATION_ID = 2
        const val EXTRA_ENGINE = "engine"
        private const val COORD_BUCKET = 10
    }
}