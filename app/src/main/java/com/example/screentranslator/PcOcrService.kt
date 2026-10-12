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
 * ИЗМЕНЕНО (устранение циклического мерцания debug/translate):
 *
 *   В onSharedPreferenceChanged при key == KEY_DEBUG теперь:
 *     - очищается translationQueue (drain всех pending job'ов);
 *     - сбрасываются queuedHash, queuedMode;
 *     - сбрасываются lastSentBlocksCount, lastMode;
 *     - сбрасываются stableBlocks, lastPreStabilizeCount.
 *
 *   Это гарантирует, что после переключения режима:
 *     1) старые translate-job'ы из очереди НЕ будут обработаны и
 *        отправлены на ПК (раньше они "догоняли" новые debug-job'ы,
 *        вызывая мерцание оверлея на ПК);
 *     2) следующий job сформируется с новым mode и будет отправлен
 *        даже если набор блоков не изменился.
 *
 *   Ранее (сохранено):
 *   - loadSettingsFromPc НЕ пересоздаёт benchmarkTranslator, если
 *     targetLang и sourceLang не изменились.
 *   - translationCache с TTL (60 секунд).
 *   - Детекция смены сцены по количеству блоков до стабилизации.
 *   - MIN_BLOCK_AREA = 4000.
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

    @Volatile private var lastPreStabilizeCount: Int = 0

    private data class CacheEntry(val value: String, val timestamp: Long)

    private val translationCache = ConcurrentHashMap<String, CacheEntry>()

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

            val oldTargetLang = targetLang
            val oldSourceLang = sourceLang

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

            val langChanged = (oldTargetLang != targetLang) ||
                    (oldSourceLang != sourceLang)
            if (langChanged) {
                DebugTimingLogger.logEvent("android.benchmark_recreate_on_lang_change",
                    mapOf(
                        "old_target" to oldTargetLang,
                        "new_target" to targetLang,
                        "old_source" to (oldSourceLang ?: ""),
                        "new_source" to (sourceLang ?: "")
                    ))
                Log.d(TAG, "loadSettingsFromPc: languages changed, " +
                        "recreating benchmarkTranslator")
                scope.launch {
                    benchmarkTranslator?.close()
                    benchmarkTranslator = BenchmarkTranslator(
                        this@PcOcrService, targetLang, sourceLang)
                    benchmarkTranslator?.prepare()
                    translationCache.clear()
                }
            } else {
                DebugTimingLogger.logEvent("android.benchmark_kept_on_settings_load",
                    mapOf(
                        "target" to targetLang,
                        "source" to (sourceLang ?: "")
                    ))
                Log.d(TAG, "loadSettingsFromPc: languages unchanged, " +
                        "keeping existing benchmarkTranslator")
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

    /**
     * ИЗМЕНЕНО: при key == KEY_DEBUG очищаем translationQueue от
     * старых job'ов и сбрасываем все счётчики режима. Это устраняет
     * ситуацию, когда старые translate-job'ы обрабатываются ПОСЛЕ
     * переключения в debug и отправляют mode=translate на ПК,
     * перезаписывая debug-оверлей (мерцание).
     */
    private fun drainTranslationQueue() {
        var drained = 0
        while (true) {
            val result = translationQueue.tryReceive()
            if (result.isSuccess) {
                drained++
            } else {
                break
            }
        }
        if (drained > 0) {
            DebugTimingLogger.logEvent("android.pc_ocr_queue_drained",
                mapOf("count" to drained))
            Log.d(TAG, "translationQueue drained: $drained jobs")
        }
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        loadSettings()

        if (key == Settings.KEY_DEBUG) {
            // ИЗМЕНЕНО: выкидываем старые job'ы из очереди,
            // сбрасываем счётчики режима и стабилизатор.
            drainTranslationQueue()
            stableBlocks.clear()
            lastPreStabilizeCount = 0
            queuedHash = 0
            queuedMode = ""
            lastSentBlocksCount = 0
            lastMode = ""
            DebugTimingLogger.logEvent("android.pc_ocr_debug_mode_changed",
                mapOf("debug" to debugMode))
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
            lastPreStabilizeCount = 0
            DebugTimingLogger.logEvent("android.pc_ocr_invalidate_on_padding")
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

                val filtered: List<OcrBlock> =
                    if (scaledResult == null || scaledResult.blocks.isEmpty()) {
                        emptyList()
                    } else {
                        val out = ArrayList<OcrBlock>(scaledResult.blocks.size)
                        for (b in scaledResult.blocks) {
                            val norm = OcrTextNormalizer.normalizeText(b.text)
                            if (norm.isEmpty()) continue
                            val normalizedBlock = b.copy(text = norm)

                            if (!OcrTextNormalizer.isMeaningfulText(
                                    normalizedBlock.text)) continue

                            if (confidenceThreshold > 0f &&
                                normalizedBlock.confidence < confidenceThreshold) continue

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

                            out.add(normalizedBlock)
                        }
                        out
                    }

                val afterFragmentFilter = ArrayList<OcrBlock>(filtered.size)
                for (b in filtered) {
                    if (isFragment(b.text)) {
                        debugDedup.logDedup("android.pc_ocr_drop_fragment",
                            mapOf("text" to b.text.take(60)))
                        continue
                    }
                    afterFragmentFilter.add(b)
                }

                val afterSplitByNumbers = ArrayList<OcrBlock>(afterFragmentFilter.size)
                for (b in afterFragmentFilter) {
                    val splitParts = OcrTextNormalizer.splitByTextAndNumbers(b)
                    for (p in splitParts) {
                        afterSplitByNumbers.add(p)
                    }
                }

                val preCount = afterSplitByNumbers.size
                val prevCount = lastPreStabilizeCount
                val bigDelta = kotlin.math.abs(preCount - prevCount)
                val sceneChanged =
                    prevCount > 0 && (
                            preCount == 0 ||
                                    bigDelta >= 5 ||
                                    (preCount >= prevCount * 2) ||
                                    (prevCount >= preCount * 2)
                            )
                if (sceneChanged) {
                    DebugTimingLogger.logEvent("android.pc_ocr_scene_reset",
                        mapOf(
                            "prev_count" to prevCount,
                            "new_count" to preCount
                        ))
                    stableBlocks.clear()
                }
                lastPreStabilizeCount = preCount

                frameCounter++
                val stabilized = OcrBlockStabilizer.stabilizeBlocks(
                    blocks = afterSplitByNumbers,
                    stableBlocks = stableBlocks,
                    frameCounter = frameCounter,
                    sceneChanged = false
                )

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
                val cached = getCachedTranslation(block.text)
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
                        putCachedTranslation(orig, dst)
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

                cleanupExpiredCache()
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

    private fun getCachedTranslation(text: String): String? {
        val entry = translationCache[text] ?: return null
        val age = System.currentTimeMillis() - entry.timestamp
        if (age > TRANSLATION_CACHE_TTL_MS) {
            translationCache.remove(text)
            return null
        }
        return entry.value
    }

    private fun putCachedTranslation(text: String, translation: String) {
        translationCache[text] = CacheEntry(
            value = translation,
            timestamp = System.currentTimeMillis()
        )
        if (translationCache.size > TRANSLATION_CACHE_MAX) {
            val sorted = translationCache.entries
                .sortedBy { it.value.timestamp }
            var toRemove = translationCache.size - TRANSLATION_CACHE_MAX
            for (e in sorted) {
                if (toRemove <= 0) break
                translationCache.remove(e.key)
                toRemove--
            }
        }
    }

    private fun cleanupExpiredCache() {
        val now = System.currentTimeMillis()
        val it = translationCache.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value.timestamp > TRANSLATION_CACHE_TTL_MS) {
                it.remove()
            }
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
        private const val TRANSLATION_CACHE_MAX = 500
        private const val TRANSLATION_CACHE_TTL_MS = 60_000L

        private const val MAX_OCR_WIDTH = 1600
        private const val MIN_FRAGMENT_LENGTH = 15
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