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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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

                val filtered: List<OcrBlock> = if (result == null || result.blocks.isEmpty()) {
                    emptyList()
                } else {
                    val out = ArrayList<OcrBlock>(result.blocks.size)
                    for (b in result.blocks) {
                        if (!isMeaningfulText(b.text)) continue
                        if (confidenceThreshold > 0f &&
                            b.confidence < confidenceThreshold) continue
                        out.add(b)
                    }
                    out
                }

                if (result != null && filtered.isNotEmpty()) {
                    val texts = filtered.map { it.text }
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

                val job = if (result == null || filtered.isEmpty()) {
                    if (lastSentBlocksCount > 0) {
                        lastSentBlocksCount = 0
                        lastMode = ""
                        OcrJob(emptyList(), result?.text ?: "", fw, fh,
                            "translate", overlayAlpha, true)
                    } else null
                } else {
                    val mode = if (debugMode) "debug" else "translate"
                    OcrJob(filtered, result.text, fw, fh, mode, overlayAlpha, false)
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

        private val URL_REGEX = Regex(
            """(https?://|www\.|[a-z0-9\-]+\.(com|ru|org|net|io|dev|edu|gov)\b)""",
            RegexOption.IGNORE_CASE
        )
        private val LOG_REGEX = Regex("""^\[\d{2}:\d{2}:\d{2}]\s*(OCR|TR)\b""")

        private const val VOWELS = "aeiouyAEIOUYаеёиоуыэюяАЕЁИОУЫЭЮЯ"

        // Расширенный список частых английских биграмм.
        private val ENG_BIGRAMS: Set<String> = hashSetOf(
            // Базовые
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
            // Расширение: частые сочетания
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

        // Английские суффиксы. Если слово оканчивается на суффикс —
        // считаем его английским, биграммы не проверяем.
        private val ENG_SUFFIXES: List<String> = listOf(
            "ing", "ed", "ly", "er", "est", "tion", "sion", "ous", "ful",
            "less", "able", "ible", "ment", "ness", "ity", "ty", "al",
            "ic", "ive", "ish", "en", "an", "ary", "ory", "ize", "ise",
            "ate", "y", "ies", "ied", "ier", "iest", "able", "ible"
        )

        /**
         * Быстрый фильтр мусора OCR.
         *
         * Отсеивает:
         *   • длину < 3;
         *   • строки без букв;
         *   • наши логи ([HH:MM:SS] OCR/TR);
         *   • URL;
         *   • > 40% не-буквенно-цифровых;
         *   • смесь латиницы и кириллицы (латиницы > 30%);
         *   • цифру между буквами;
         *   • > 50% заглавных при длине >= 5;
         *   • нет гласных в первых 20 символах;
         *   • заглавную кириллицу после строчной кириллицы;
         *   • латинское слово длиной >= 5 с < 3 английскими биграммами
         *     (если слово НЕ оканчивается на английский суффикс);
         *   • латинское слово длиной >= 6 с 2+ заглавными не в первой позиции;
         *   • слово длиной >= 4 с цифрой внутри.
         *
         * Работает быстро: ранние выходы, один проход по строке.
         */
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

            // Правило 11: заглавная кириллица после строчной кириллицы
            for (i in 1 until n) {
                val c = t[i]
                val p = t[i - 1]
                if ((c in 'А'..'Я' || c == 'Ё') && (p in 'а'..'я' || p == 'ё')) {
                    return false
                }
            }

            // Правило 12: для каждого латинского слова длиной >= 5 —
            // проверяем суффикс (если есть — ок), иначе минимум 3 биграммы
            // и не более 1 заглавной не в первой позиции.
            // Правило 13: слово длиной >= 4 с цифрой внутри — отсеивать.
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
                        // Правило 13: цифра внутри слова длиной >= 4
                        if (wordHasDigit && wordLen >= 4) {
                            return false
                        }
                        // Правило 12: латинское слово длиной >= 5
                        if (wordIsLatin && !wordHasDigit && wordLen >= 5) {
                            val word = t.substring(wordStart, i).lowercase()
                            // Проверка суффикса
                            var hasSuffix = false
                            for (suf in ENG_SUFFIXES) {
                                if (word.endsWith(suf) && word.length > suf.length) {
                                    hasSuffix = true
                                    break
                                }
                            }
                            if (!hasSuffix) {
                                // Проверка биграмм
                                var bigrams = 0
                                var upperMid = 0
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
                                j = wordStart + 1
                                while (j < i) {
                                    if (t[j] in 'A'..'Z') upperMid++
                                    j++
                                }
                                if (bigrams < 3) return false
                                if (upperMid >= 2) return false
                            }
                        }
                        wordStart = -1
                        wordIsLatin = false
                        wordHasDigit = false
                    }
                }
            }

            // Нет гласных в первых 20 символах
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