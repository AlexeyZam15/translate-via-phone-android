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
    private var translator: OcrTranslator? = null

    // OCR-задача для worker'а.
    private data class OcrJob(
        val blocks: List<OcrBlock>,
        val rawText: String,
        val frameW: Int,
        val frameH: Int,
        val mode: String,
        val overlayAlpha: Int,
        val isEmpty: Boolean
    )

    // Очередь переводов. Буфер 2 — меньше устаревших задач.
    private val translationQueue = Channel<OcrJob>(capacity = 2)
    private var translationWorker: Job? = null

    private val ocrBusy = AtomicBoolean(false)

    // Хэш блоков последней задачи в очереди — для дедупликации.
    @Volatile private var queuedHash: Int = 0
    @Volatile private var queuedMode: String = ""

    private var engineType: OcrType = OcrType.ML_KIT
    private var confidenceThreshold: Float = 0f
    private var sourceLang: String? = null
    private var targetLang: String = "ru"
    private var debugMode: Boolean = false
    private var overlayAlpha: Int = 217

    // ИЗМЕНЕНО: сколько блоков реально было отправлено в прошлый раз.
    // Нужно, чтобы понять, надо ли слать пустой job для очистки оверлея,
    // когда переводчик ещё/уже не готов.
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
        translator = OcrTranslator(this, targetLang, sourceLang)

        startTranslationWorker()

        // ИЗМЕНЕНО: MJPEG стартует после того, как переводчик готов
        // (или по таймауту 5 сек). Пока переводчик не готов — onFrame
        // всё равно ничего не отправляет (см. ниже), но так мы ещё и
        // не тратим OCR-циклы на кадры, которые не переведутся.
        scope.launch {
            launch { translator?.prepare() }
            withTimeoutOrNull(5000L) {
                while (translator?.isReady() != true) {
                    delay(50)
                }
            }
            DebugTimingLogger.logEvent("android.pc_ocr_translator_ready_check",
                mapOf("ready" to (translator?.isReady() == true)))
            mjpegReader = MJPEGReader(mjpegUrl) { jpegBytes -> onFrame(jpegBytes) }
            mjpegReader?.start()
        }

        Log.d(TAG, "PcOcrService started: threshold=$confidenceThreshold, " +
                "source=${sourceLang ?: "auto"}, target=$targetLang, " +
                "debug=$debugMode, alpha=$overlayAlpha")

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
        val newAlpha = Settings.alphaFromPercent(Settings.getOverlayAlpha(this))
        if (newAlpha != overlayAlpha) {
            Log.d(TAG, "overlayAlpha changed: $overlayAlpha -> $newAlpha")
        }
        overlayAlpha = newAlpha
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

                // ИЗМЕНЕНО: пока переводчик не готов и это не debug-режим —
                // НЕ отправляем блоки вообще. Оверлей на ПК остаётся пустым.
                // Если до этого что-то было отправлено (переводчик был готов,
                // потом сбросился) — шлём пустой job для очистки оверлея.
                val translatorReady = translator?.isReady() == true
                if (!debugMode && !translatorReady) {
                    if (lastSentBlocksCount > 0) {
                        lastSentBlocksCount = 0
                        lastMode = ""
                        val clearJob = OcrJob(
                            blocks = emptyList(),
                            rawText = "",
                            frameW = fw, frameH = fh,
                            mode = "translate",
                            overlayAlpha = overlayAlpha,
                            isEmpty = true
                        )
                        translationQueue.trySend(clearJob)
                    }
                    return@launch
                }

                val job = if (result == null) {
                    if (lastSentBlocksCount > 0) {
                        lastSentBlocksCount = 0
                        lastMode = ""
                        OcrJob(
                            blocks = emptyList(),
                            rawText = "",
                            frameW = fw, frameH = fh,
                            mode = "translate",
                            overlayAlpha = overlayAlpha,
                            isEmpty = true
                        )
                    } else null
                } else {
                    val filtered = if (debugMode) {
                        result.blocks
                    } else if (confidenceThreshold > 0f) {
                        result.blocks.filter { it.confidence >= confidenceThreshold }
                    } else {
                        result.blocks
                    }

                    if (filtered.isEmpty()) {
                        if (lastSentBlocksCount > 0) {
                            lastSentBlocksCount = 0
                            lastMode = ""
                            OcrJob(
                                blocks = emptyList(),
                                rawText = result.text,
                                frameW = fw, frameH = fh,
                                mode = "translate",
                                overlayAlpha = overlayAlpha,
                                isEmpty = true
                            )
                        } else null
                    } else {
                        // Режим: debug или translate. "ocr" больше не нужен —
                        // при !isReady() мы вообще ничего не шлём.
                        val mode = if (debugMode) "debug" else "translate"
                        OcrJob(
                            blocks = filtered,
                            rawText = result.text,
                            frameW = fw, frameH = fh,
                            mode = mode,
                            overlayAlpha = overlayAlpha,
                            isEmpty = false
                        )
                    }
                }

                if (job != null) {
                    if (!job.isEmpty) {
                        val hash = computeBlocksHash(job.blocks, job.mode, job.overlayAlpha)
                        if (hash == queuedHash && job.mode == queuedMode) {
                            return@launch
                        }
                        queuedHash = hash
                        queuedMode = job.mode
                    }

                    val sent = translationQueue.trySend(job).isSuccess
                    if (!sent) {
                        droppedCount.incrementAndGet()
                        if (droppedCount.get() % 5 == 1) {
                            DebugTimingLogger.logEvent("android.pc_ocr_queue_full",
                                mapOf("blocks" to job.blocks.size, "dropped" to droppedCount.get()))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "onFrame error", e)
                DebugTimingLogger.logEvent("android.pc_ocr_cycle_error",
                    mapOf("error" to e.message.orEmpty()))
            } finally {
                ocrBusy.set(false)
            }
        }
    }

    private suspend fun processJob(job: OcrJob) {
        val tStart = System.nanoTime()
        var blockCount = 0
        var sent = false

        try {
            if (job.isEmpty || job.blocks.isEmpty()) {
                DebugTimingLogger.timed("android.pc_ocr_send_empty") {
                    sender?.send(
                        OcrResult(job.rawText, emptyList()),
                        job.frameW, job.frameH,
                        "translate", job.overlayAlpha,
                        force = true
                    )
                }
                lastSentBlocksCount = 0
                lastMode = ""
                sent = true
                return
            }

            // debug — отправляем OCR-блоки как есть.
            if (job.mode == "debug") {
                blockCount = job.blocks.size
                DebugTimingLogger.timed("android.pc_ocr_send") {
                    sender?.send(
                        OcrResult(job.rawText, job.blocks),
                        job.frameW, job.frameH,
                        "debug", job.overlayAlpha
                    )
                }
                lastSentBlocksCount = job.blocks.size
                lastMode = "debug"
                sent = true
                return
            }

            // translate — переводим и отправляем.
            val translated = DebugTimingLogger.timed("android.pc_ocr_translate_all") {
                translator?.translateAll(job.blocks.map { it.text }) ?: emptyList()
            }
            val map = translated.toMap()
            val outBlocks = job.blocks.mapNotNull { block ->
                val t = map[block.text] ?: return@mapNotNull null
                block.copy(text = t)
            }

            blockCount = outBlocks.size

            if (outBlocks.isEmpty()) {
                DebugTimingLogger.timed("android.pc_ocr_send_empty") {
                    sender?.send(
                        OcrResult(job.rawText, emptyList()),
                        job.frameW, job.frameH,
                        "translate", job.overlayAlpha,
                        force = true
                    )
                }
                lastSentBlocksCount = 0
                lastMode = ""
                sent = true
                return
            }

            DebugTimingLogger.timed("android.pc_ocr_send") {
                sender?.send(
                    OcrResult(job.rawText, outBlocks),
                    job.frameW, job.frameH,
                    "translate", job.overlayAlpha
                )
            }
            lastSentBlocksCount = outBlocks.size
            lastMode = "translate"
            sent = true
        } finally {
            val ms = (System.nanoTime() - tStart) / 1_000_000.0
            DebugTimingLogger.logTiming(
                "android.pc_ocr_cycle_total", ms,
                mapOf(
                    "blocks" to blockCount,
                    "mode" to job.mode,
                    "sent" to sent
                )
            )
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
        translationWorker?.cancel()
        translationWorker = null
        translationQueue.close()
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

        // Округление координат в хэше: 10 px.
        private const val COORD_BUCKET = 10
    }
}