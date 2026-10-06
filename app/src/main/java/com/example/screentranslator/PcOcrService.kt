// PcOcrService.kt (полный файл)
package com.example.screentranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.screentranslator.engines.MlKitOcrEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * ИЗМЕНЕНО:
 *   - MIN_BLOCK_AREA = 4000. Блоки с площадью bbox меньше
 *     не отправляются на ПК. Это отсеивает мелкие "Speed:",
 *     "Max:", "Wii" и т.п.
 *   - isFragment: блок со строчной первой буквы — фрагмент
 *     только если длина < 15.
 *   - Downscale до MAX_OCR_WIDTH = 1600.
 *   - translationQueue: Channel<OcrJob>(capacity = 4).
 *   - splitBySingleLetters ОТКЛЮЧЁН.
 */
class PcOcrService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var mjpegReader: MJPEGReader? = null
    private var ocrEngine: OcrEngine? = null
    private var sender: OcrSender? = null

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

    private val translationQueue = Channel<OcrJob>(capacity = 4)
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

    private val stableBlocks = mutableListOf<OcrBlockStabilizer.StableBlock>()
    private var frameCounter: Long = 0L

    private val translationCache = ConcurrentHashMap<String, String>()

    private val debugDedup = DebugEventDedup()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engineName = intent?.getStringExtra(EXTRA_ENGINE) ?: OcrType.ML_KIT.name
        engineType = runCatching { OcrType.valueOf(engineName) }
            .getOrDefault(OcrType.ML_KIT)

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

        scope.launch {
            loadSettingsFromPc()
        }

        scope.launch {
            benchmarkTranslator?.prepare()

            withTimeoutOrNull(2000L) {
                while (benchmarkTranslator?.isReady() != true) {
                    delay(100)
                }
            }
            DebugTimingLogger.logEvent("android.pc_ocr_benchmark_ready_check",
                mapOf("ready" to (benchmarkTranslator?.isReady() == true)))
            mjpegReader = MJPEGReader(mjpegUrl) { jpegBytes -> onFrame(jpegBytes) }
            mjpegReader?.start()
        }

        Log.d(TAG, "PcOcrService started: CTranslate2 + MLKit fallback, " +
                "threshold=$confidenceThreshold, source=${sourceLang ?: "auto"}, " +
                "target=$targetLang, debug=$debugMode, alpha=$overlayAlpha")

        return START_NOT_STICKY
    }

    private suspend fun loadSettingsFromPc() {
        try {
            val endpoint = Settings.getSettingsEndpoint(this)
            val client = OkHttpClient.Builder()
                .connectTimeout(1500, TimeUnit.MILLISECONDS)
                .readTimeout(3000, TimeUnit.MILLISECONDS)
                .build()
            val req = Request.Builder().url(endpoint).get().build()
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val text = resp.body?.string() ?: return@use
                    val obj = JSONObject(text)
                    if (obj.has("debug")) {
                        Settings.setDebug(this@PcOcrService, obj.optBoolean("debug"))
                    }
                    if (obj.has("translate_profile")) {
                        Settings.setTranslateProfile(this@PcOcrService,
                            obj.optString("translate_profile"))
                    }
                    if (obj.has("confidence")) {
                        Settings.setConfidence(this@PcOcrService,
                            obj.optInt("confidence"))
                    }
                    if (obj.has("ocr_padding")) {
                        Settings.setOcrPadding(this@PcOcrService,
                            obj.optInt("ocr_padding"))
                    }
                    if (obj.has("overlay_alpha")) {
                        Settings.setOverlayAlpha(this@PcOcrService,
                            obj.optInt("overlay_alpha"))
                    }
                    if (obj.has("source_lang")) {
                        Settings.setSourceLang(this@PcOcrService,
                            obj.optString("source_lang"))
                    }
                    if (obj.has("target_lang")) {
                        Settings.setTargetLang(this@PcOcrService,
                            obj.optString("target_lang"))
                    }
                    DebugTimingLogger.logEvent("android.settings_loaded_from_pc")
                }
            }
            loadSettings()
            scope.launch {
                benchmarkTranslator?.close()
                benchmarkTranslator = BenchmarkTranslator(
                    this@PcOcrService, targetLang, sourceLang)
                benchmarkTranslator?.prepare()
                translationCache.clear()
            }
        } catch (_: Exception) {
            DebugTimingLogger.logEvent("android.settings_load_from_pc_failed")
        }
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
        val conf = Settings.getConfidence(this)
        confidenceThreshold = conf / 100f
        val srcRaw = Settings.getSourceLang(this)
        sourceLang = if (srcRaw.isEmpty() || srcRaw == "auto") null else srcRaw
        targetLang = Settings.getTargetLang(this)
        overlayAlpha = Settings.alphaFromPercent(Settings.getOverlayAlpha(this))
        Log.d(TAG, "loadSettings: debug=$debugMode, confidence=$conf, " +
                "threshold=$confidenceThreshold, source=${sourceLang ?: "auto"}, " +
                "target=$targetLang, alpha=$overlayAlpha")
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        loadSettings()
        if (key == Settings.KEY_DEBUG) {
            stableBlocks.clear()
            queuedHash = 0
            queuedMode = ""
        }
        if (key == Settings.KEY_SOURCE_LANG || key == Settings.KEY_TARGET_LANG) {
            scope.launch {
                benchmarkTranslator?.close()
                benchmarkTranslator = BenchmarkTranslator(
                    this@PcOcrService, targetLang, sourceLang)
                benchmarkTranslator?.prepare()
                translationCache.clear()
            }
        }
        if (key == Settings.KEY_OCR_PADDING) {
            (ocrEngine as? MlKitOcrEngine)?.invalidateCache()
            stableBlocks.clear()
            DebugTimingLogger.logEvent("android.pc_ocr_invalidate_on_padding")
        }
    }

    /**
     * Downscale bitmap до MAX_OCR_WIDTH перед ML Kit,
     * после распознавания — scale bbox'ы обратно.
     */
    private fun scaleBlocksToOriginal(
        blocks: List<OcrBlock>,
        scale: Float
    ): List<OcrBlock> {
        if (scale >= 1.0f) return blocks
        val invScale = 1.0f / scale
        return blocks.map { b ->
            val r = b.boundingBox
            val ir = b.initialBoundingBox
            b.copy(
                boundingBox = Rect(
                    (r.left * invScale).toInt(),
                    (r.top * invScale).toInt(),
                    (r.right * invScale).toInt(),
                    (r.bottom * invScale).toInt()
                ),
                initialBoundingBox = Rect(
                    (ir.left * invScale).toInt(),
                    (ir.top * invScale).toInt(),
                    (ir.right * invScale).toInt(),
                    (ir.bottom * invScale).toInt()
                )
            )
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

                val ocrScale: Float
                val ocrBitmap: Bitmap
                if (bitmap.width > MAX_OCR_WIDTH) {
                    ocrScale = MAX_OCR_WIDTH.toFloat() / bitmap.width.toFloat()
                    ocrBitmap = Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * ocrScale).toInt(),
                        (bitmap.height * ocrScale).toInt(),
                        true
                    )
                } else {
                    ocrScale = 1.0f
                    ocrBitmap = bitmap
                }

                val engine = ocrEngine ?: return@launch
                val result = DebugTimingLogger.timed("android.pc_ocr_recognize") {
                    try {
                        engine.recognize(ocrBitmap)
                    } catch (e: Exception) {
                        DebugTimingLogger.logEvent("android.pc_ocr_error",
                            mapOf("error" to e.message.orEmpty()))
                        null
                    }
                }

                if (ocrBitmap !== bitmap) {
                    ocrBitmap.recycle()
                }

                val fromEngine = result?.blocks?.size ?: 0

                val scaledResult: OcrResult? = if (result == null) null
                else OcrResult(
                    text = result.text,
                    blocks = scaleBlocksToOriginal(result.blocks, ocrScale)
                )

                val textsAfterNormalize = ArrayList<String>(fromEngine)
                val textsAfterMeaningful = ArrayList<String>(fromEngine)
                val textsAfterConfidence = ArrayList<String>(fromEngine)
                val textsAfterArea = ArrayList<String>(fromEngine)

                val filtered: List<OcrBlock> =
                    if (scaledResult == null || scaledResult.blocks.isEmpty()) {
                        emptyList()
                    } else {
                        val out = ArrayList<OcrBlock>(scaledResult.blocks.size)
                        for (b in scaledResult.blocks) {
                            val norm = OcrTextNormalizer.normalizeText(b.text)
                            if (norm.isEmpty()) continue
                            val normalizedBlock = b.copy(text = norm)
                            textsAfterNormalize.add(norm)

                            if (!OcrTextNormalizer.isMeaningfulText(
                                    normalizedBlock.text)) continue
                            textsAfterMeaningful.add(norm)

                            if (confidenceThreshold > 0f &&
                                normalizedBlock.confidence < confidenceThreshold) continue
                            textsAfterConfidence.add(norm)

                            // ИЗМЕНЕНО: отсев мелких блоков по площади.
                            val r = normalizedBlock.boundingBox
                            val area = r.width() * r.height()
                            if (area < MIN_BLOCK_AREA) {
                                debugDedup.logDedup("android.pc_ocr_drop_small",
                                    mapOf(
                                        "text" to normalizedBlock.text.take(40),
                                        "area" to area
                                    ))
                                continue
                            }
                            textsAfterArea.add(norm)

                            out.add(normalizedBlock)
                        }
                        out
                    }

                val afterFragmentFilter = ArrayList<OcrBlock>(filtered.size)
                val textsAfterFragment = ArrayList<String>(filtered.size)
                for (b in filtered) {
                    if (isFragment(b.text)) {
                        debugDedup.logDedup("android.pc_ocr_drop_fragment",
                            mapOf("text" to b.text.take(60)))
                        continue
                    }
                    afterFragmentFilter.add(b)
                    textsAfterFragment.add(b.text)
                }

                val afterSplitByNumbers = ArrayList<OcrBlock>(afterFragmentFilter.size)
                for (b in afterFragmentFilter) {
                    val splitParts = OcrTextNormalizer.splitByTextAndNumbers(b)
                    if (splitParts.size != 1 || splitParts[0] !== b) {
                        debugDedup.logDedup("android.pc_ocr_split_numbers",
                            mapOf(
                                "original" to b.text,
                                "parts" to splitParts.map { it.text }
                            ))
                    }
                    for (p in splitParts) {
                        afterSplitByNumbers.add(p)
                    }
                }

                frameCounter++
                val stabilized = OcrBlockStabilizer.stabilizeBlocks(
                    blocks = afterSplitByNumbers,
                    stableBlocks = stableBlocks,
                    frameCounter = frameCounter,
                    sceneChanged = false
                )

                val pipelineLost =
                    textsAfterNormalize.size != fromEngine ||
                            textsAfterMeaningful.size != textsAfterNormalize.size ||
                            textsAfterConfidence.size != textsAfterMeaningful.size ||
                            textsAfterArea.size != textsAfterConfidence.size ||
                            textsAfterFragment.size != textsAfterArea.size ||
                            afterSplitByNumbers.size != textsAfterFragment.size ||
                            stabilized.size != afterSplitByNumbers.size

                if (pipelineLost) {
                    debugDedup.logDedup("android.pc_ocr_pipeline",
                        mapOf(
                            "from_engine" to fromEngine,
                            "after_normalize" to textsAfterNormalize.size,
                            "after_meaningful" to textsAfterMeaningful.size,
                            "after_confidence" to textsAfterConfidence.size,
                            "after_area" to textsAfterArea.size,
                            "after_fragment" to textsAfterFragment.size,
                            "after_split_numbers" to afterSplitByNumbers.size,
                            "after_stabilize" to stabilized.size
                        ))
                }

                val job = if (scaledResult == null || stabilized.isEmpty()) {
                    if (lastSentBlocksCount > 0) {
                        lastSentBlocksCount = 0
                        lastMode = ""
                        OcrJob(emptyList(), scaledResult?.text ?: "", fw, fh,
                            "translate", overlayAlpha, true)
                    } else null
                } else {
                    val mode = if (debugMode) "debug" else "translate"
                    OcrJob(stabilized, scaledResult.text, fw, fh, mode,
                        overlayAlpha, false)
                }

                if (job != null) {
                    if (!job.isEmpty) {
                        val hash = computeBlocksHash(job.blocks, job.mode,
                            job.overlayAlpha)
                        if (hash == queuedHash && job.mode == queuedMode) {
                            // skip
                        } else {
                            queuedHash = hash
                            queuedMode = job.mode
                            if (!translationQueue.trySend(job).isSuccess) {
                                droppedCount.incrementAndGet()
                            }
                        }
                    } else {
                        if (!translationQueue.trySend(job).isSuccess) {
                            droppedCount.incrementAndGet()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "onFrame error", e)
            } finally {
                ocrBusy.set(false)
            }
        }
    }

    /**
     * Блок со строчной первой буквы считается фрагментом
     * ТОЛЬКО если его длина < MIN_FRAGMENT_LENGTH = 15.
     */
    private fun isFragment(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        if (t.length >= 35) return false

        val firstChar = t[0]
        if (firstChar.isLowerCase() && t.length < MIN_FRAGMENT_LENGTH) {
            return true
        }

        if (t.length < MIN_FRAGMENT_LENGTH) {
            val firstWord = t.substringBefore(' ').lowercase()
            val serviceWords = setOf(
                "through", "and", "for", "with", "to", "of", "in", "on", "by",
                "from", "as", "or", "but", "at", "the", "a", "an", "into",
                "over", "under", "onto", "off", "out", "up", "down", "that",
                "this", "it", "its", "is", "are", "was", "were", "be", "been"
            )
            if (firstWord in serviceWords) return true
        }

        return false
    }

    private suspend fun processJob(job: OcrJob) {
        val tStart = System.nanoTime()
        var blockCount = 0
        try {
            if (job.isEmpty || job.blocks.isEmpty()) {
                sender?.send(OcrResult(job.rawText, emptyList()),
                    job.frameW, job.frameH, "translate", job.overlayAlpha,
                    force = true)
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

            val outBlocks = arrayOfNulls<OcrBlock>(job.blocks.size)
            val needTranslate = ArrayList<String>(job.blocks.size)
            val blockIndexByText = HashMap<String, MutableList<Int>>(job.blocks.size)

            for ((idx, block) in job.blocks.withIndex()) {
                val cached = translationCache[block.text]
                if (cached != null && cached.isNotBlank()) {
                    outBlocks[idx] = block.copy(text = cached)
                } else {
                    if (!blockIndexByText.containsKey(block.text)) {
                        needTranslate.add(block.text)
                    }
                    blockIndexByText.getOrPut(block.text) { mutableListOf() }.add(idx)
                }
            }

            if (needTranslate.isNotEmpty()) {
                val translated = benchmarkTranslator?.translateAll(needTranslate)
                    ?: emptyList()
                val translatedMap = HashMap<String, String>(translated.size)
                for ((orig, dst) in translated) {
                    if (dst.isNotBlank()) {
                        translatedMap[orig] = dst
                        translationCache[orig] = dst
                    }
                }

                for ((orig, indices) in blockIndexByText) {
                    val dst = translatedMap[orig]
                    for (idx in indices) {
                        if (idx < 0 || idx >= job.blocks.size) continue
                        val block = job.blocks[idx]
                        if (dst != null && dst.isNotBlank()) {
                            outBlocks[idx] = block.copy(text = dst)
                        } else {
                            outBlocks[idx] = block
                        }
                    }
                }

                if (translationCache.size > TRANSLATION_CACHE_MAX) {
                    val it = translationCache.entries.iterator()
                    var toRemove = translationCache.size - TRANSLATION_CACHE_MAX
                    while (it.hasNext() && toRemove > 0) {
                        it.next()
                        it.remove()
                        toRemove--
                    }
                }
            }

            val finalBlocks = ArrayList<OcrBlock>(job.blocks.size)
            for (b in outBlocks) {
                if (b != null) {
                    finalBlocks.add(b)
                }
            }

            blockCount = finalBlocks.size

            if (finalBlocks.isEmpty()) {
                DebugTimingLogger.logEvent("android.pc_ocr_no_translations",
                    mapOf("total" to job.blocks.size))
                return
            }

            sender?.send(OcrResult(job.rawText, finalBlocks),
                job.frameW, job.frameH, "translate", job.overlayAlpha)
            lastSentBlocksCount = finalBlocks.size
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
        stableBlocks.clear()
        translationCache.clear()
        debugDedup.clear()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "PcOcrService"
        private const val NOTIFICATION_ID = 2
        const val EXTRA_ENGINE = "engine"
        private const val COORD_BUCKET = 2
        private const val TRANSLATION_CACHE_MAX = 1000

        private const val MAX_OCR_WIDTH = 1600

        private const val MIN_FRAGMENT_LENGTH = 15

        // ИЗМЕНЕНО: минимальная площадь bbox блока. Блоки меньше
        // не отправляются на ПК. Отсеивает мелкие "Speed:", "Max:", "Wii".
        private const val MIN_BLOCK_AREA = 4000
    }
}

/**
 * Дедупликатор debug-событий.
 */
class DebugEventDedup {
    private val lastHashes = HashMap<String, Int>()

    fun logDedup(stage: String, data: Map<String, Any>) {
        val hash = computeHash(stage, data)
        val prev = lastHashes[stage]
        if (prev == hash) return
        lastHashes[stage] = hash
        DebugTimingLogger.logEvent(stage, data)
    }

    fun clear() {
        lastHashes.clear()
    }

    private fun computeHash(stage: String, data: Map<String, Any>): Int {
        var h = stage.hashCode()
        val sortedKeys = data.keys.sorted()
        for (k in sortedKeys) {
            h = h * 31 + k.hashCode()
            val v = data[k]
            h = h * 31 + valueHash(v)
        }
        return h
    }

    private fun valueHash(v: Any?): Int {
        return when (v) {
            null -> 0
            is List<*> -> {
                var h = 1
                for (item in v) {
                    h = h * 31 + valueHash(item)
                }
                h
            }
            is Map<*, *> -> {
                var h = 2
                for (k in v.keys.sortedBy { it.toString() }) {
                    h = h * 31 + k.toString().hashCode()
                    h = h * 31 + valueHash(v[k])
                }
                h
            }
            else -> v.toString().hashCode()
        }
    }
}