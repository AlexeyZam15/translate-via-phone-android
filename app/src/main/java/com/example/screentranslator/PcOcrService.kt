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
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.hypot

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

    private data class StableBlock(
        var text: String,
        var x: Int,
        var y: Int,
        var w: Int,
        var h: Int,
        var lastSeenFrame: Long,
        var pendingText: String?,
        var pendingCount: Int,
        var refined: Boolean,
        var refineCounter: Int
    )

    private data class RefinedResult(
        val text: String,
        val box: Rect,
        val geomValid: Boolean
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

    private val stableBlocks = mutableListOf<StableBlock>()
    private var frameCounter: Long = 0L

    @Volatile private var lastFrameHash: Long = 0L
    @Volatile private var stableFrameStreak: Int = 0

    private val translationCache = ConcurrentHashMap<String, String>()

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
            lastFrameHash = 0L
            stableFrameStreak = 0
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
            for (sb in stableBlocks) {
                sb.refined = false
                sb.refineCounter = 0
            }
        }
    }

    private fun deduplicateOverlapping(blocks: List<OcrBlock>): List<OcrBlock> {
        if (blocks.size <= 1) return blocks

        val sorted = blocks.sortedByDescending { b ->
            val r = b.boundingBox
            b.confidence.toDouble() * (r.width().toDouble() * r.height().toDouble())
        }

        val accepted = ArrayList<OcrBlock>(sorted.size)
        for (b in sorted) {
            if (!overlapsAnyBlock(b, accepted)) {
                accepted.add(b)
            }
        }
        return accepted
    }

    private fun overlapsAnyBlock(b: OcrBlock, already: List<OcrBlock>): Boolean {
        val r = b.boundingBox
        val areaB = r.width().toLong() * r.height().toLong()
        if (areaB <= 0) return false

        for (other in already) {
            val o = other.boundingBox
            val areaO = o.width().toLong() * o.height().toLong()
            if (areaO <= 0) continue

            val ix1 = maxOf(r.left, o.left)
            val iy1 = maxOf(r.top, o.top)
            val ix2 = minOf(r.right, o.right)
            val iy2 = minOf(r.bottom, o.bottom)
            val iw = ix2 - ix1
            val ih = iy2 - iy1
            if (iw <= 0 || ih <= 0) continue

            val inter = iw.toLong() * ih.toLong()
            val minArea = minOf(areaB, areaO)
            if (minArea <= 0) continue

            val ratio = inter.toDouble() / minArea.toDouble()
            if (ratio >= DEDUP_OVERLAP_RATIO) {
                return true
            }
        }
        return false
    }

    private fun normalizeText(raw: String): String {
        var t = raw.trim()
        if (t.isEmpty()) return t

        val leadMatch = NORMALIZE_LEAD_REGEX.find(t)
        if (leadMatch != null) {
            val prefix = leadMatch.groupValues[1]
            val rest = leadMatch.groupValues[2]
            if (prefix !in NORMALIZE_SKIP_WORDS) {
                t = rest
            }
        }

        t = stripTrailingVowel(t)

        val trailMatch = NORMALIZE_TRAIL_REGEX.find(t)
        if (trailMatch != null) {
            t = trailMatch.groupValues[1]
        }

        return t.trim()
    }

    private fun stripTrailingVowel(text: String): String {
        if (text.length < 2) return text

        val words = text.split(' ', '\t', '\n')
        val lastWord = words.lastOrNull() ?: return text
        if (lastWord.length < 3) return text

        val last = lastWord.last()
        val lastLower = last.lowercaseChar()
        if (lastLower !in TRAILING_VOWELS) return text

        val beforeLast = lastWord[lastWord.length - 2]
        val beforeLower = beforeLast.lowercaseChar()
        if (beforeLower !in CONSONANTS) return text

        val lastWordLower = lastWord.lowercase()
        if (lastWordLower in NORMALIZE_WHITELIST) return text
        if (hasNaturalSuffix(lastWordLower)) return text

        val trimmedWord = lastWord.dropLast(1)
        if (trimmedWord.isEmpty()) return text
        if (trimmedWord.length < 2) return text

        val prefix = text.substring(0, text.length - lastWord.length)
        return prefix + trimmedWord
    }

    private fun hasNaturalSuffix(word: String): Boolean {
        for (suf in ENG_SUFFIXES) {
            if (word.endsWith(suf) && word.length > suf.length) {
                return true
            }
        }
        return false
    }

    private fun isPercentBlock(text: String): Boolean {
        return text.contains('%')
    }

    private fun isContained(
        smallX: Int, smallY: Int, smallW: Int, smallH: Int,
        bigX: Int, bigY: Int, bigW: Int, bigH: Int,
        margin: Double
    ): Boolean {
        if (smallW <= 0 || smallH <= 0 || bigW <= 0 || bigH <= 0) return false

        val marginX = (bigW * margin).toInt()
        val marginY = (bigH * margin).toInt()

        val bigLeft = bigX - marginX
        val bigTop = bigY - marginY
        val bigRight = bigX + bigW + marginX
        val bigBottom = bigY + bigH + marginY

        val smallLeft = smallX
        val smallTop = smallY
        val smallRight = smallX + smallW
        val smallBottom = smallY + smallH

        return smallLeft >= bigLeft &&
                smallTop >= bigTop &&
                smallRight <= bigRight &&
                smallBottom <= bigBottom
    }

    private fun overlapsAnyStable(
        nb: OcrBlock,
        used: BooleanArray
    ): Boolean {
        val r = nb.boundingBox
        if (r.width() <= 0 || r.height() <= 0) return false

        for ((idx, sb) in stableBlocks.withIndex()) {
            val iou = computeIoU(
                sb.x, sb.y, sb.w, sb.h,
                r.left, r.top, r.width(), r.height()
            )
            if (iou > 0.0) return true

            if (isContained(
                    sb.x, sb.y, sb.w, sb.h,
                    r.left, r.top, r.width(), r.height(),
                    CONTAINMENT_MARGIN
                )) return true
            if (isContained(
                    r.left, r.top, r.width(), r.height(),
                    sb.x, sb.y, sb.w, sb.h,
                    CONTAINMENT_MARGIN
                )) return true
        }
        return false
    }

    private fun textsAreSimilar(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val la = a.trim().lowercase()
        val lb = b.trim().lowercase()
        if (la == lb) return true
        if (la.startsWith(lb) || lb.startsWith(la)) return true

        val shorter = if (la.length <= lb.length) la else lb
        val longer = if (la.length <= lb.length) lb else la

        var common = 0
        for (c in shorter) {
            if (c.isLetterOrDigit() && longer.contains(c)) {
                common++
            }
        }
        val lettersInShort = shorter.count { it.isLetterOrDigit() }
        if (lettersInShort == 0) return false
        val ratio = common.toDouble() / lettersInShort.toDouble()
        return ratio >= 0.6
    }

    private fun stabilizeBlocks(
        blocks: List<OcrBlock>,
        sceneChanged: Boolean
    ): List<OcrBlock> {
        if (blocks.isEmpty()) {
            pruneStableBlocks()
            return emptyList()
        }

        frameCounter++

        val used = BooleanArray(blocks.size) { false }
        val result = ArrayList<OcrBlock>(blocks.size)
        var matchedStableCount = 0

        for (sb in stableBlocks) {
            var bestIdx = -1
            var bestScore = 0.0
            for (i in blocks.indices) {
                if (used[i]) continue
                val nb = blocks[i]
                val r = nb.boundingBox
                if (r.width() <= 0 || r.height() <= 0) continue

                val iou = computeIoU(
                    sb.x, sb.y, sb.w, sb.h,
                    r.left, r.top, r.width(), r.height()
                )
                val dist = centerDistance(
                    sb.x, sb.y, sb.w, sb.h,
                    r.left, r.top, r.width(), r.height()
                )

                val matchByIou = iou >= STABLE_IOU_THRESHOLD
                val matchByCenter = dist < CENTER_MATCH_DIST
                val matchByContain =
                    isContained(
                        sb.x, sb.y, sb.w, sb.h,
                        r.left, r.top, r.width(), r.height(),
                        CONTAINMENT_MARGIN
                    ) ||
                            isContained(
                                r.left, r.top, r.width(), r.height(),
                                sb.x, sb.y, sb.w, sb.h,
                                CONTAINMENT_MARGIN
                            )

                val geometryMatch = matchByIou || matchByCenter || matchByContain
                if (!geometryMatch) continue

                val candidateText = normalizeText(nb.text)
                if (!textsAreSimilar(sb.text, candidateText)) continue

                var score = iou
                if (matchByCenter) {
                    score += (1.0 - dist / CENTER_MATCH_DIST) * 0.5
                }
                if (matchByContain) {
                    score += 0.2
                }
                if (score > bestScore) {
                    bestScore = score
                    bestIdx = i
                }
            }

            if (bestIdx < 0) {
                continue
            }

            used[bestIdx] = true
            matchedStableCount++
            val nb = blocks[bestIdx]
            val newText = normalizeText(nb.text)
            if (newText.isEmpty()) {
                sb.lastSeenFrame = frameCounter
                continue
            }

            // ИЗМЕНЕНО: точное сравнение текста, БЕЗ textsAreSimilar.
            // Если текст реально изменился (даже на 1 символ) —
            // pendingCount начинает накапливаться и через
            // STABLE_TEXT_UPDATE_THRESHOLD кадров sb.text обновится.
            // Раньше textsAreSimilar возвращал true для похожих текстов,
            // pendingCount сбрасывался, и старый текст оставался навсегда.
            if (newText == sb.text) {
                sb.pendingText = null
                sb.pendingCount = 0
            } else if (newText == sb.pendingText) {
                sb.pendingCount++
                if (sb.pendingCount >= STABLE_TEXT_UPDATE_THRESHOLD) {
                    sb.text = newText
                    sb.pendingText = null
                    sb.pendingCount = 0
                }
            } else {
                sb.pendingText = newText
                sb.pendingCount = 1
            }

            sb.lastSeenFrame = frameCounter
            sb.refineCounter++

            // Обновляем геометрию при сдвиге на 3+ пикселя
            // и изменении размера на 5+ пикселей.
            val nbR = nb.boundingBox
            if (abs(nbR.left - sb.x) >= POS_CHANGE_THRESHOLD) sb.x = nbR.left
            if (abs(nbR.top - sb.y) >= POS_CHANGE_THRESHOLD) sb.y = nbR.top
            if (abs(nbR.width() - sb.w) >= SIZE_CHANGE_THRESHOLD) sb.w = nbR.width()
            if (abs(nbR.height() - sb.h) >= SIZE_CHANGE_THRESHOLD) sb.h = nbR.height()

            result.add(
                OcrBlock(
                    text = sb.text,
                    boundingBox = Rect(sb.x, sb.y, sb.x + sb.w, sb.y + sb.h),
                    confidence = nb.confidence,
                    initialBoundingBox = Rect(sb.x, sb.y, sb.x + sb.w, sb.y + sb.h)
                )
            )
        }

        if (sceneChanged && matchedStableCount == 0 && stableBlocks.isNotEmpty()) {
            Log.d(TAG, "scene changed: no stable matched, clear stableBlocks")
            DebugTimingLogger.logEvent("android.pc_ocr_scene_changed",
                mapOf("new_blocks" to blocks.size))
            stableBlocks.clear()
        }

        for (i in blocks.indices) {
            if (used[i]) continue
            val nb = blocks[i]
            val r = nb.boundingBox
            if (r.width() <= 0 || r.height() <= 0) continue

            val newText = normalizeText(nb.text)
            if (newText.isEmpty()) continue

            if (overlapsAnyStable(nb, used)) {
                continue
            }

            val sb = StableBlock(
                text = newText,
                x = r.left,
                y = r.top,
                w = r.width(),
                h = r.height(),
                lastSeenFrame = frameCounter,
                pendingText = null,
                pendingCount = 0,
                refined = false,
                refineCounter = 0
            )
            stableBlocks.add(sb)

            result.add(
                OcrBlock(
                    text = sb.text,
                    boundingBox = Rect(sb.x, sb.y, sb.x + sb.w, sb.y + sb.h),
                    confidence = nb.confidence,
                    initialBoundingBox = Rect(sb.x, sb.y, sb.x + sb.w, sb.y + sb.h)
                )
            )
        }

        pruneStableBlocks()
        return result
    }

    private fun pruneStableBlocks() {
        val threshold = frameCounter - STABLE_MISS_LIMIT
        val it = stableBlocks.iterator()
        while (it.hasNext()) {
            val sb = it.next()
            if (sb.lastSeenFrame < threshold) {
                it.remove()
            }
        }
    }

    private fun computeIoU(
        ax: Int, ay: Int, aw: Int, ah: Int,
        bx: Int, by: Int, bw: Int, bh: Int
    ): Double {
        val ax2 = ax + aw
        val ay2 = ay + ah
        val bx2 = bx + bw
        val by2 = by + bh

        val ix1 = maxOf(ax, bx)
        val iy1 = maxOf(ay, by)
        val ix2 = minOf(ax2, bx2)
        val iy2 = minOf(ay2, by2)

        val iw = ix2 - ix1
        val ih = iy2 - iy1
        if (iw <= 0 || ih <= 0) return 0.0

        val inter = iw.toDouble() * ih.toDouble()
        val areaA = aw.toDouble() * ah.toDouble()
        val areaB = bw.toDouble() * bh.toDouble()
        val union = areaA + areaB - inter
        if (union <= 0) return 0.0
        return inter / union
    }

    private fun centerDistance(
        ax: Int, ay: Int, aw: Int, ah: Int,
        bx: Int, by: Int, bw: Int, bh: Int
    ): Double {
        val acx = ax + aw / 2.0
        val acy = ay + ah / 2.0
        val bcx = bx + bw / 2.0
        val bcy = by + bh / 2.0
        return hypot(acx - bcx, acy - bcy)
    }

    private suspend fun refineBlockText(
        bitmapCopy: Bitmap,
        sb: StableBlock
    ): RefinedResult? {
        try {
            val rawPadding = Settings.getOcrPadding(this).coerceIn(0, 50)
            val paddingPercent = minOf(rawPadding, REFINE_PADDING_MAX) / 100f
            val padX = (sb.w * paddingPercent).toInt()
            val padY = (sb.h * paddingPercent).toInt()

            val cropLeft = (sb.x - padX).coerceIn(0, bitmapCopy.width - 1)
            val cropTop = (sb.y - padY).coerceIn(0, bitmapCopy.height - 1)
            val cropRight = (sb.x + sb.w + padX).coerceIn(cropLeft + 1, bitmapCopy.width)
            val cropBottom = (sb.y + sb.h + padY).coerceIn(cropTop + 1, bitmapCopy.height)

            val w = cropRight - cropLeft
            val h = cropBottom - cropTop
            if (w <= 0 || h <= 0) return null

            val cropped = Bitmap.createBitmap(bitmapCopy, cropLeft, cropTop, w, h)
            val scale = if (REFINE_SCALE > 1) REFINE_SCALE else 1
            val processed = if (scale > 1) {
                Bitmap.createScaledBitmap(
                    cropped,
                    w * scale,
                    h * scale,
                    true
                )
            } else {
                cropped
            }

            val engine = ocrEngine ?: return null
            val result = try {
                engine.recognize(processed)
            } catch (_: Exception) {
                null
            } finally {
                if (processed !== cropped) {
                    try { processed.recycle() } catch (_: Throwable) {}
                }
                try { cropped.recycle() } catch (_: Throwable) {}
            }

            if (result == null || result.blocks.isEmpty()) return null

            var bestText: String? = null
            var bestBox: Rect? = null
            for (b in result.blocks) {
                val t = normalizeText(b.text)
                if (t.isEmpty()) continue
                if (!isMeaningfulText(t)) continue
                if (isPercentBlock(t)) continue
                if (bestText == null || t.length > bestText!!.length) {
                    bestText = t
                    bestBox = Rect(b.boundingBox)
                }
            }

            val refinedText = bestText ?: return null
            val refinedBox = bestBox ?: return null

            if (refinedText == sb.text && refinedText.length < sb.text.length) {
                return null
            }

            val scaledLeft = refinedBox.left / scale
            val scaledTop = refinedBox.top / scale
            val scaledRight = refinedBox.right / scale
            val scaledBottom = refinedBox.bottom / scale

            val origLeft = (cropLeft + scaledLeft).coerceIn(0, bitmapCopy.width)
            val origTop = (cropTop + scaledTop).coerceIn(0, bitmapCopy.height)
            val origRight = (cropLeft + scaledRight).coerceIn(origLeft + 1, bitmapCopy.width)
            val origBottom = (cropTop + scaledBottom).coerceIn(origTop + 1, bitmapCopy.height)

            val newBox = Rect(origLeft, origTop, origRight, origBottom)

            val dx = abs(newBox.left - sb.x)
            val dy = abs(newBox.top - sb.y)
            val dw = abs(newBox.width() - sb.w)
            val dh = abs(newBox.height() - sb.h)

            val maxDx = (sb.w * MAX_GEOM_DELTA_RATIO).toInt().coerceAtLeast(8)
            val maxDy = (sb.h * MAX_GEOM_DELTA_RATIO).toInt().coerceAtLeast(8)
            val maxDw = (sb.w * MAX_GEOM_DELTA_RATIO).toInt().coerceAtLeast(8)
            val maxDh = (sb.h * MAX_GEOM_DELTA_RATIO).toInt().coerceAtLeast(8)

            val geomValid = dx <= maxDx && dy <= maxDy &&
                    dw <= maxDw && dh <= maxDh

            if (!geomValid) {
                DebugTimingLogger.logEvent(
                    "android.pc_ocr_refine_geom_rejected",
                    mapOf(
                        "dx" to dx, "dy" to dy, "dw" to dw, "dh" to dh,
                        "max_dx" to maxDx, "max_dy" to maxDy,
                        "max_dw" to maxDw, "max_dh" to maxDh
                    )
                )
            }

            return RefinedResult(
                text = refinedText,
                box = newBox,
                geomValid = geomValid
            )
        } catch (_: Exception) {
            return null
        }
    }

    private fun scheduleRefine(bitmap: Bitmap) {
        var candidate: StableBlock? = null
        for (sb in stableBlocks) {
            val needsFirstRefine = !sb.refined
            val needsRefresh = sb.refineCounter >= REFINE_INTERVAL_FRAMES
            if (needsFirstRefine || needsRefresh) {
                candidate = sb
                break
            }
        }

        val target = candidate ?: return

        val bitmapCopy: Bitmap = try {
            bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
        } catch (_: Throwable) {
            return
        } ?: return

        scope.launch {
            try {
                val result = refineBlockText(bitmapCopy, target)
                if (result != null) {
                    val oldText = target.text
                    val newText = result.text
                    val newBox = result.box

                    if (newText != oldText) {
                        Log.d(TAG, "refine text: '$oldText' -> '$newText'")
                        DebugTimingLogger.logEvent(
                            "android.pc_ocr_refine",
                            mapOf("old" to oldText, "new" to newText)
                        )
                        target.text = newText
                        target.pendingText = null
                        target.pendingCount = 0
                    }

                    if (result.geomValid) {
                        val dx = abs(newBox.left - target.x)
                        val dy = abs(newBox.top - target.y)
                        val dw = abs(newBox.width() - target.w)
                        val dh = abs(newBox.height() - target.h)

                        val minDx = (target.w * GEOM_APPLY_THRESHOLD_RATIO).toInt().coerceAtLeast(3)
                        val minDy = (target.h * GEOM_APPLY_THRESHOLD_RATIO).toInt().coerceAtLeast(3)
                        val minDw = (target.w * GEOM_APPLY_THRESHOLD_RATIO).toInt().coerceAtLeast(3)
                        val minDh = (target.h * GEOM_APPLY_THRESHOLD_RATIO).toInt().coerceAtLeast(3)

                        if (dx >= minDx || dy >= minDy ||
                            dw >= minDw || dh >= minDh) {
                            target.x = newBox.left
                            target.y = newBox.top
                            target.w = newBox.width()
                            target.h = newBox.height()
                            DebugTimingLogger.logEvent(
                                "android.pc_ocr_refine_geom",
                                mapOf(
                                    "dx" to dx, "dy" to dy,
                                    "dw" to dw, "dh" to dh
                                )
                            )
                        }
                    }
                }
                target.refined = true
                target.refineCounter = 0
            } catch (e: Exception) {
                Log.w(TAG, "refine error: ${e.message}")
            } finally {
                try { bitmapCopy.recycle() } catch (_: Throwable) {}
            }
        }
    }

    private fun computeFrameHash(jpegBytes: ByteArray): Long {
        return try {
            val md = MessageDigest.getInstance("MD5")
            val size = jpegBytes.size
            val sizeBytes = byteArrayOf(
                ((size ushr 24) and 0xFF).toByte(),
                ((size ushr 16) and 0xFF).toByte(),
                ((size ushr 8) and 0xFF).toByte(),
                (size and 0xFF).toByte()
            )
            md.update(sizeBytes)
            val limit = minOf(jpegBytes.size, 4096)
            md.update(jpegBytes, 0, limit)
            val digest = md.digest()
            var hash: Long = 0
            for (i in 0 until 8) {
                hash = (hash shl 8) or (digest[i].toLong() and 0xFF)
            }
            hash
        } catch (_: Exception) {
            0L
        }
    }

    private fun onFrame(jpegBytes: ByteArray) {
        if (!ocrBusy.compareAndSet(false, true)) return

        scope.launch {
            var bitmapForRefine: Bitmap? = null
            try {
                val frameHash = computeFrameHash(jpegBytes)
                val isSameFrame = (frameHash == lastFrameHash) && (frameHash != 0L)

                if (isSameFrame && stableFrameStreak >= STABLE_FRAME_STREAK_LIMIT) {
                    return@launch
                }

                if (isSameFrame) {
                    stableFrameStreak++
                } else {
                    stableFrameStreak = 0
                    lastFrameHash = frameHash
                }

                val sceneChanged = !isSameFrame

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

                val filtered: List<OcrBlock> = if (result == null || result.blocks.isEmpty()) {
                    emptyList()
                } else {
                    val out = ArrayList<OcrBlock>(result.blocks.size)
                    for (b in result.blocks) {
                        if (!isMeaningfulText(b.text)) continue
                        if (confidenceThreshold > 0f &&
                            b.confidence < confidenceThreshold) continue
                        if (isPercentBlock(b.text)) continue
                        out.add(b)
                    }
                    out
                }

                val deduped = if (filtered.size > 1) {
                    deduplicateOverlapping(filtered)
                } else {
                    filtered
                }

                val stabilized = stabilizeBlocks(deduped, sceneChanged)

                if (stableBlocks.isNotEmpty()) {
                    val needRefine = stableBlocks.any {
                        !it.refined || it.refineCounter >= REFINE_INTERVAL_FRAMES
                    }
                    if (needRefine) {
                        bitmapForRefine = bitmap
                    }
                }

                val job = if (result == null || stabilized.isEmpty()) {
                    if (lastSentBlocksCount > 0) {
                        lastSentBlocksCount = 0
                        lastMode = ""
                        OcrJob(emptyList(), result?.text ?: "", fw, fh,
                            "translate", overlayAlpha, true)
                    } else null
                } else {
                    val mode = if (debugMode) "debug" else "translate"
                    OcrJob(stabilized, result.text, fw, fh, mode, overlayAlpha, false)
                }

                if (job != null) {
                    if (!job.isEmpty) {
                        val hash = computeBlocksHash(job.blocks, job.mode, job.overlayAlpha)
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
                val bmpRef = bitmapForRefine
                if (bmpRef != null) {
                    try {
                        scheduleRefine(bmpRef)
                    } catch (_: Throwable) {}
                }
                try { bmpRef?.recycle() } catch (_: Throwable) {}
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

            val outBlocks = ArrayList<OcrBlock>(job.blocks.size)
            val needTranslate = ArrayList<String>(job.blocks.size)
            val blockByText = HashMap<String, MutableList<OcrBlock>>(job.blocks.size)

            for (block in job.blocks) {
                val cached = translationCache[block.text]
                if (cached != null) {
                    outBlocks.add(block.copy(text = cached))
                } else {
                    needTranslate.add(block.text)
                    blockByText.getOrPut(block.text) { mutableListOf() }.add(block)
                }
            }

            if (needTranslate.isNotEmpty()) {
                val translated = benchmarkTranslator?.translateAll(needTranslate)
                    ?: emptyList()
                for ((orig, dst) in translated) {
                    if (dst.isBlank()) continue
                    translationCache[orig] = dst
                    val blocks = blockByText[orig]
                    if (blocks != null) {
                        for (b in blocks) {
                            outBlocks.add(b.copy(text = dst))
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

            blockCount = outBlocks.size

            if (outBlocks.isEmpty()) {
                DebugTimingLogger.logEvent("android.pc_ocr_no_translations",
                    mapOf("total" to job.blocks.size))
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
        stableBlocks.clear()
        translationCache.clear()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "PcOcrService"
        private const val NOTIFICATION_ID = 2
        const val EXTRA_ENGINE = "engine"
        private const val COORD_BUCKET = 2

        private const val STABLE_IOU_THRESHOLD = 0.3
        private const val CENTER_MATCH_DIST = 60.0
        private const val CONTAINMENT_MARGIN = 0.3
        private const val STABLE_MISS_LIMIT = 2L
        private const val STABLE_TEXT_UPDATE_THRESHOLD = 2

        private const val POS_CHANGE_THRESHOLD = 3
        private const val SIZE_CHANGE_THRESHOLD = 5

        private const val REFINE_INTERVAL_FRAMES = 60
        private const val REFINE_SCALE = 2

        private const val DEDUP_OVERLAP_RATIO = 0.5

        private const val STABLE_FRAME_STREAK_LIMIT = 4

        private const val TRANSLATION_CACHE_MAX = 1000

        private const val REFINE_PADDING_MAX = 15

        private const val MAX_GEOM_DELTA_RATIO = 0.5

        private const val GEOM_APPLY_THRESHOLD_RATIO = 0.1

        private val NORMALIZE_SKIP_WORDS = setOf(
            "A", "An", "The", "I"
        )

        private val NORMALIZE_LEAD_REGEX = Regex("^([A-Za-z0-9]{1,3})\\s+([A-Z].*)$")
        private val NORMALIZE_TRAIL_REGEX = Regex("^(.*[a-zа-яё.,!?])\\s+([A-Za-z0-9]{1,2})$")

        private val TRAILING_VOWELS = setOf(
            'a', 'o', 'e', 'u', 'i', 'y'
        )

        private val CONSONANTS = setOf(
            'r', 't', 'l', 'n', 'm', 'p', 'k', 'g', 'd', 'f', 'v', 's', 'z', 'h', 'j',
            'b', 'c', 'q', 'w', 'x'
        )

        private val NORMALIZE_WHITELIST = setOf(
            "menu", "game", "file", "mode", "code", "name", "time", "line",
            "size", "value", "image", "page", "message", "language",
            "move", "love", "live", "save", "have", "give", "take", "make",
            "like", "life", "wife", "knife", "safe", "cafe", "case", "base",
            "place", "space", "face", "race", "pace", "grace", "trace",
            "price", "nice", "rice", "ice", "slice", "twice", "once",
            "since", "hence", "whence", "thence",
            "true", "blue", "clue", "due", "hue", "sue", "cue", "argue",
            "value", "issue", "rescue", "venue", "avenue", "revenue",
            "continue", "menu", "venue", "residue", "statue", "virtue",
            "age", "image", "page", "stage", "rage", "cage", "wage",
            "language", "message", "village", "college", "knowledge",
            "manage", "package", "garbage", "cabbage", "sausage",
            "orange", "range", "change", "strange", "arrange",
            "large", "charge", "barge", "marge", "targe",
            "there", "where", "here", "mere", "sere", "were",
            "done", "gone", "none", "one", "some", "come", "become",
            "love", "above", "dove", "glove", "shove", "move", "prove",
            "have", "gave", "save", "wave", "cave", "pave", "rave", "brave",
            "leave", "weave", "heave", "cleave", "sleeve", "believe",
            "receive", "deceive", "perceive", "conceive",
            "write", "white", "quite", "quote", "note", "vote", "hope",
            "home", "come", "some", "done", "gone", "none", "one",
            "more", "before", "therefore", "wherefore", "store", "score",
            "shore", "snore", "swore", "tore", "wore", "bore", "core",
            "sure", "pure", "cure", "lure", "sure", "endure", "secure",
            "nature", "future", "picture", "culture", "structure",
            "figure", "failure", "measure", "treasure", "pleasure",
            "feature", "creature", "theatre", "centre", "metre",
            "nintendo", "wii", "remote", "chapter", "start", "select",
            "cancel", "confirm", "delete", "copy", "extras", "speed",
            "max", "radiant", "dawn", "emblem", "fire",
            "area", "idea", "media", "data", "extra", "ultra",
            "alpha", "beta", "gamma", "delta", "sigma", "omega",
            "anna", "ella", "emma", "olga", "irina", "sveta",
            "nata", "rita", "liza", "vika", "nina", "mila",
            "kira", "lara", "mara", "sara", "tara", "zara",
            "cara", "clara", "laura", "aura", "flora", "nora",
            "dora", "lora", "bora", "gora", "pora", "tora",
            "vora", "zora",
            "africa", "america", "asia", "europa", "russia",
            "china", "india", "japan", "korea", "canada",
            "australia", "brasil", "brazil", "argentina", "mexico",
            "cuba", "peru", "chile", "bolivia", "venezuela",
            "colombia", "ecuador", "uruguay", "paraguay", "panama",
            "costa", "rica", "guatemala", "honduras", "nicaragua",
            "salvador", "belize", "haiti", "jamaica", "bahamas",
            "barbados", "grenada", "dominica", "st", "lucia",
            "vincent", "grenadines", "antigua", "barbuda", "kitts",
            "nevis", "trinidad", "tobago", "guyana", "suriname",
            "french", "guiana", "falkland", "islands", "galapagos",
            "easter", "island", "hawaii", "alaska", "california",
            "florida", "texas", "montana", "indiana", "iowa",
            "ohio", "utah", "alabama", "arizona", "arkansas",
            "colorado", "connecticut", "delaware", "georgia", "idaho",
            "illinois", "kansas", "kentucky", "louisiana", "maine",
            "maryland", "massachusetts", "michigan", "minnesota",
            "mississippi", "missouri", "nebraska", "nevada", "new",
            "hampshire", "jersey", "mexico", "york", "carolina",
            "dakota", "oklahoma", "oregon", "pennsylvania", "rhode",
            "tennessee", "vermont", "virginia", "washington",
            "wisconsin", "wyoming"
        )

        private val URL_REGEX = Regex(
            """(https?://|www\.|[a-z0-9\-]+\.(com|ru|org|net|io|dev|edu|gov)\b)""",
            RegexOption.IGNORE_CASE
        )
        private val LOG_REGEX = Regex("""^\[\d{2}:\d{2}:\d{2}]\s*(OCR|TR)\b""")

        private const val VOWELS = "aeiouyAEIOUYаеёиоуыэюяАЕЁИОУЫЭЮЯ"

        private val ENG_BIGRAMS: Set<String> = hashSetOf(
            "th", "he", "in", "er", "an", "re", "on", "at", "en", "nd",
            "ti", "es", "or", "te", "of", "ed", "is", "it", "al", "ar",
            "st", "to", "nt", "ng", "se", "ha", "as", "ou", "io", "le",
            "ve", "co", "me", "de", "hi", "ri", "ro", "ic", "ne", "ea",
            "ra", "ce", "li", "ch", "ll", "be", "ma", "si", "om", "ur",
            "ay", "da", "od", "ad", "ai", "il", "ir", "el", "ly", "et",
            "ee", "oo", "ie", "ue", "ui", "oa", "ta", "ac", "ca", "nc",
            "no", "ot", "so", "us", "un", "up", "ut", "id", "im", "pe",
            "po", "pr", "pl", "tr", "tw", "wh", "sh", "ck", "gh", "ph",
            "qu", "ge", "gi", "gu", "ga", "go", "lo", "la", "lu", "mi",
            "mu", "na", "ni", "nu", "ob", "oc", "op", "pa", "pi", "pu",
            "sa", "sc", "sp", "su", "sy", "va", "vi", "vo", "wa", "we",
            "wi", "wo", "ye", "yo", "za", "ze", "zo",
            "sl", "ep", "py", "ap", "pp", "ys", "em", "tt", "ty", "ow",
            "bl", "cl", "fl", "gl", "br", "cr", "dr", "fr", "gr", "sw",
            "sk", "sm", "sn", "sq", "str", "scr", "spr", "spl", "thr",
            "shr", "squ", "ght", "igh", "ein", "eir", "our", "oul", "ous",
            "ing", "ion", "ent", "ant", "and", "old", "ild", "ind", "ill",
            "all", "ell", "oll", "ull", "ack", "eck", "ick", "ock", "uck",
            "ace", "ice", "oce", "uce", "age", "ige", "oge", "uge",
            "are", "ere", "ire", "ore", "ure", "ave", "eve", "ive", "ove",
            "ate", "ite", "ote", "ute", "ame", "eme", "ime", "ome", "ume",
            "ane", "ene", "ine", "one", "une", "ale", "ele", "ile", "ole",
            "ape", "ipe", "ope", "ale", "ile", "ole", "ule"
        )

        private val ENG_SUFFIXES: List<String> = listOf(
            "ing", "ed", "ly", "er", "est", "tion", "sion", "ous", "ful",
            "less", "able", "ible", "ment", "ness", "ity", "ty", "al",
            "ic", "ive", "ish", "en", "an", "ary", "ory", "ize", "ise",
            "ate", "y", "ies", "ied", "ier", "iest", "able", "ible"
        )

        fun isMeaningfulText(raw: String): Boolean {
            val t = raw.trim()
            val n = t.length
            if (n < 3) return false

            var letters = 0
            var digits = 0
            var nonAlnum = 0
            var latin = 0
            var cyrillic = 0
            var upper = 0
            var hasLetter = false
            var digitBetweenLetters = false
            var prevWasLetter = false
            var prevWasDigit = false

            for (i in 0 until n) {
                val c = t[i]
                val isLetter = when {
                    c in 'a'..'z' -> { latin++; true }
                    c in 'A'..'Z' -> { latin++; upper++; true }
                    c in 'а'..'я' || c in 'А'..'Я' -> {
                        cyrillic++
                        if (c in 'А'..'Я') upper++
                        true
                    }
                    c == 'ё' || c == 'Ё' -> {
                        cyrillic++
                        if (c == 'Ё') upper++
                        true
                    }
                    else -> false
                }
                val isDigit = c in '0'..'9'

                if (isLetter) {
                    letters++
                    hasLetter = true
                    if (prevWasDigit && i + 1 < n && t[i + 1].isLetter()) {
                        digitBetweenLetters = true
                    }
                } else if (isDigit) {
                    digits++
                    if (prevWasLetter && i + 1 < n && t[i + 1].isLetter()) {
                        digitBetweenLetters = true
                    }
                } else if (c != ' ' && c != '\t') {
                    nonAlnum++
                }

                prevWasLetter = isLetter
                prevWasDigit = isDigit
            }

            if (!hasLetter) return false

            if (LOG_REGEX.containsMatchIn(t)) return false
            if (URL_REGEX.containsMatchIn(t)) return false

            if (digitBetweenLetters) return false

            val totalNonSpace = letters + digits + nonAlnum
            if (totalNonSpace > 0 && nonAlnum * 100 / totalNonSpace > 40) return false

            if (latin > 0 && cyrillic > 0) {
                val totalLetters = latin + cyrillic
                if (latin * 100 / totalLetters > 30) return false
            }

            if (n >= 5 && letters > 0 && upper * 100 / letters > 50) return false

            for (i in 1 until n) {
                val c = t[i]
                val p = t[i - 1]
                if ((c in 'А'..'Я' || c == 'Ё') && (p in 'а'..'я' || p == 'ё')) {
                    return false
                }
            }

            var wordStart = -1
            var wordIsLatin = false
            var wordHasDigit = false

            for (i in 0 until n + 1) {
                val c = if (i < n) t[i] else ' '
                val isLetter = i < n && c.isLetter()
                val isDigit = i < n && c in '0'..'9'
                val isLatinLetter = i < n && (c in 'a'..'z' || c in 'A'..'Z')

                if (isLetter || isDigit) {
                    if (wordStart == -1) {
                        wordStart = i
                        wordIsLatin = true
                        wordHasDigit = false
                    }
                    if (isDigit) {
                        wordHasDigit = true
                    }
                    if (isLetter && !isLatinLetter) {
                        wordIsLatin = false
                    }
                } else {
                    if (wordStart != -1) {
                        val wordLen = i - wordStart

                        if (wordIsLatin && !wordHasDigit && wordLen >= 2) {
                            var hasUpperMid = false
                            var allUpper = true
                            for (j in wordStart until i) {
                                val cj = t[j]
                                if (cj in 'A'..'Z') {
                                    if (j > wordStart) hasUpperMid = true
                                } else {
                                    allUpper = false
                                }
                            }
                            if (hasUpperMid && !allUpper) return false
                        }

                        if (wordHasDigit && wordLen >= 4) {
                            return false
                        }
                        if (wordIsLatin && !wordHasDigit && wordLen >= 5) {
                            val word = t.substring(wordStart, i).lowercase()
                            var hasSuffix = false
                            for (suf in ENG_SUFFIXES) {
                                if (word.endsWith(suf) && word.length > suf.length) {
                                    hasSuffix = true
                                    break
                                }
                            }
                            if (!hasSuffix) {
                                var bigrams = 0
                                var j = wordStart
                                while (j < i - 1) {
                                    val c1 = t[j].lowercaseChar()
                                    val c2 = t[j + 1].lowercaseChar()
                                    if (c1 in 'a'..'z' && c2 in 'a'..'z') {
                                        val bi = "$c1$c2"
                                        if (bi in ENG_BIGRAMS) {
                                            bigrams++
                                        }
                                    }
                                    j++
                                }
                                if (bigrams < 3) return false
                            }
                        }
                        wordStart = -1
                        wordIsLatin = false
                        wordHasDigit = false
                    }
                }
            }

            val checkLen = if (n < 20) n else 20
            var hasVowel = false
            for (i in 0 until checkLen) {
                if (VOWELS.indexOf(t[i]) >= 0) {
                    hasVowel = true
                    break
                }
            }
            if (!hasVowel) return false

            return true
        }
    }
}