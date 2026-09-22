package com.example.screentranslator

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OcrTranslator(
    private val context: Context,
    private val targetLang: String,
    private val fixedSourceLang: String? = null
) {

    private val tag = "OcrTranslator"

    @Volatile private var useServer: Boolean = Settings.isUseServerTranslation(context)
    @Volatile private var serverEndpoint: String = Settings.getTranslateEndpoint(context)

    private val httpFailures = AtomicInteger(0)
    private val MAX_HTTP_FAILURES = 3

    // ML Kit
    private val languageIdentifier: LanguageIdentifier = LanguageIdentification.getClient()
    private val modelManager = RemoteModelManager.getInstance()
    private val modelReadyCache = ConcurrentHashMap<String, Boolean>()

    // Пул переводчиков. Ключ — "src->tgt". Значение — очередь свободных инстансов.
    private val mlkitPoolSize = 4
    private val translatorPool = ConcurrentHashMap<String, ArrayDeque<Translator>>()
    private val poolLock = Any()

    private val ready = AtomicBoolean(false)

    // HTTP-клиент с короткими таймаутами
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .writeTimeout(2000, TimeUnit.MILLISECONDS)
        .readTimeout(2000, TimeUnit.MILLISECONDS)
        .build()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private data class CacheEntry(val value: String?)
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val sourceLangCache = ConcurrentHashMap<String, String>()

    suspend fun prepare() {
        DebugTimingLogger.timed("android.translator_prepare") {
            try {
                if (useServer && serverEndpoint.isNotBlank()) {
                    val ok = pingServer()
                    if (ok) {
                        ready.set(true)
                        Log.d(tag, "Server translation ready: $serverEndpoint")
                        DebugTimingLogger.logEvent(
                            "android.translator_ready",
                            mapOf("mode" to "server", "endpoint" to serverEndpoint)
                        )
                        return@timed
                    } else {
                        Log.w(tag, "Server unavailable, fallback to ML Kit")
                        DebugTimingLogger.logEvent(
                            "android.translator_server_unavailable",
                            mapOf("endpoint" to serverEndpoint)
                        )
                        useServer = false
                    }
                }

                val initialSource = fixedSourceLang
                    ?.let { TranslateLanguage.fromLanguageTag(it) }
                    ?: TranslateLanguage.ENGLISH

                ensurePool(initialSource, targetLang)
                if (initialSource != TranslateLanguage.ENGLISH) {
                    ensurePool(TranslateLanguage.ENGLISH, targetLang)
                }
                ready.set(true)
                Log.d(tag, "ML Kit ready: $initialSource → $targetLang")
                DebugTimingLogger.logEvent(
                    "android.translator_ready",
                    mapOf("mode" to "mlkit", "source" to initialSource, "target" to targetLang)
                )
            } catch (t: Throwable) {
                Log.e(tag, "prepare failed: ${t.message}")
                ready.set(false)
                DebugTimingLogger.logEvent(
                    "android.translator_prepare_error",
                    mapOf("error" to t.message.orEmpty())
                )
            }
        }
    }

    fun isReady(): Boolean = ready.get()

    // ----------------------------------------------------------
    // HTTP (опциональный путь)
    // ----------------------------------------------------------

    private suspend fun pingServer(): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                val body = "{\"texts\":[],\"source\":\"auto\",\"target\":\"$targetLang\"}"
                    .toRequestBody(jsonMedia)
                val req = Request.Builder().url(serverEndpoint).post(body).build()
                httpClient.newCall(req).execute().use { resp -> resp.isSuccessful }
            }
        } catch (t: Throwable) {
            Log.w(tag, "pingServer failed: ${t.message}")
            false
        }
    }

    private suspend fun translateHttp(texts: List<String>): List<String>? {
        if (texts.isEmpty()) return emptyList()
        return try {
            withContext(Dispatchers.IO) {
                DebugTimingLogger.timed("android.translator_http_request",
                    mapOf("count" to texts.size)) {
                    val arr = JSONArray()
                    for (t in texts) arr.put(t)
                    val root = JSONObject()
                    root.put("texts", arr)
                    root.put("source", fixedSourceLang ?: "auto")
                    root.put("target", targetLang)
                    val body = root.toString().toRequestBody(jsonMedia)
                    val req = Request.Builder().url(serverEndpoint).post(body).build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            Log.w(tag, "HTTP ${resp.code}")
                            return@timed null
                        }
                        val txt = resp.body?.string() ?: return@timed null
                        val obj = JSONObject(txt)
                        val transArr = obj.optJSONArray("translations") ?: return@timed null
                        val out = ArrayList<String>(transArr.length())
                        for (i in 0 until transArr.length()) {
                            out.add(transArr.optString(i, ""))
                        }
                        out
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(tag, "translateHttp failed: ${t.message}")
            null
        }
    }

    // ----------------------------------------------------------
    // ML Kit (пул)
    // ----------------------------------------------------------

    private suspend fun ensurePool(source: String, target: String) {
        val key = "$source->$target"
        synchronized(poolLock) {
            if (translatorPool.containsKey(key)) return
        }

        if (!isModelReady(source, target)) {
            downloadModel(source, target)
        }
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(source)
            .setTargetLanguage(target)
            .build()
        val deque = ArrayDeque<Translator>(mlkitPoolSize)
        repeat(mlkitPoolSize) {
            deque.addLast(Translation.getClient(options))
        }
        synchronized(poolLock) {
            translatorPool[key] = deque
        }
    }

    /**
     * Забирает переводчик из пула. Если свободных нет — создаёт временный
     * (не кладёт в пул, чтобы не превысить размер).
     */
    private suspend fun borrowTranslator(source: String, target: String): Translator {
        val key = "$source->$target"
        ensurePool(source, target)
        synchronized(poolLock) {
            val deque = translatorPool[key]
            if (deque != null && deque.isNotEmpty()) {
                return deque.removeFirst()
            }
        }
        // Все заняты — создаём одноразовый
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(source)
            .setTargetLanguage(target)
            .build()
        return Translation.getClient(options)
    }

    private fun returnTranslator(source: String, target: String, t: Translator, temporary: Boolean) {
        if (temporary) {
            try { t.close() } catch (_: Throwable) {}
            return
        }
        val key = "$source->$target"
        synchronized(poolLock) {
            val deque = translatorPool[key]
            if (deque != null && deque.size < mlkitPoolSize) {
                deque.addLast(t)
            } else {
                try { t.close() } catch (_: Throwable) {}
            }
        }
    }

    private suspend fun isModelReady(source: String, target: String): Boolean {
        val key = "$source->$target"
        modelReadyCache[key]?.let { return it }

        return DebugTimingLogger.timed("android.translator_is_model_ready") {
            try {
                val downloadedModels = modelManager
                    .getDownloadedModels(TranslateRemoteModel::class.java)
                    .awaitT()
                val langs = downloadedModels.map { it.language }.toSet()
                val ok = (source == "en" || langs.contains(source)) &&
                        (target == "en" || langs.contains(target))
                modelReadyCache[key] = ok
                ok
            } catch (t: Throwable) {
                Log.e(tag, "isModelReady failed: ${t.message}")
                false
            }
        }
    }

    private fun downloadModel(source: String, target: String) {
        val key = "$source->$target"
        if (source != "en") {
            val m = TranslateRemoteModel.Builder(source).build()
            modelManager.download(m, DownloadConditions.Builder().requireWifi().build())
                .addOnSuccessListener {
                    modelReadyCache[key] = true
                    DebugTimingLogger.logEvent("android.translator_model_downloaded",
                        mapOf("lang" to source))
                }
                .addOnFailureListener { e ->
                    DebugTimingLogger.logEvent("android.translator_model_download_error",
                        mapOf("lang" to source, "error" to e.message.orEmpty()))
                }
        }
        if (target != "en") {
            val m = TranslateRemoteModel.Builder(target).build()
            modelManager.download(m, DownloadConditions.Builder().requireWifi().build())
                .addOnSuccessListener {
                    modelReadyCache[key] = true
                    DebugTimingLogger.logEvent("android.translator_model_downloaded",
                        mapOf("lang" to target))
                }
                .addOnFailureListener { e ->
                    DebugTimingLogger.logEvent("android.translator_model_download_error",
                        mapOf("lang" to target, "error" to e.message.orEmpty()))
                }
        }
    }

    private suspend fun detectSourceLang(text: String): String {
        if (fixedSourceLang != null) {
            return TranslateLanguage.fromLanguageTag(fixedSourceLang) ?: TranslateLanguage.ENGLISH
        }
        sourceLangCache[text]?.let { return it }

        return DebugTimingLogger.timed("android.translator_detect_lang") {
            val tagLang = try {
                languageIdentifier.identifyLanguage(text).awaitT()
            } catch (t: Throwable) {
                "und"
            }
            val lang = if (tagLang == "und" || tagLang == null) {
                TranslateLanguage.ENGLISH
            } else {
                TranslateLanguage.fromLanguageTag(tagLang) ?: TranslateLanguage.ENGLISH
            }
            if (sourceLangCache.size < 200) {
                sourceLangCache[text] = lang
            }
            lang
        }
    }

    // ----------------------------------------------------------
    // Public API
    // ----------------------------------------------------------

    suspend fun translate(text: String): String? {
        if (text.isBlank()) return null
        if (!hasLetters(text)) return null
        if (text.length < 3) return null
        if (!ready.get()) return null

        cache[text]?.let { return it.value }

        val result = translateAll(listOf(text))
        val found = result.firstOrNull { it.first == text }?.second
        if (cache.size < 2000) cache[text] = CacheEntry(found)
        return found
    }

    suspend fun translateAll(texts: List<String>): List<Pair<String, String>> {
        val tTotal = System.nanoTime()
        try {
            if (!ready.get()) return emptyList()

            val valid = texts.filter { it.isNotBlank() && hasLetters(it) && it.length >= 3 }
            if (valid.isEmpty()) return emptyList()

            val result = ArrayList<Pair<String, String>>(valid.size)
            val toTranslate = ArrayList<String>(valid.size)
            for (t in valid) {
                val cached = cache[t]
                if (cached != null) {
                    if (cached.value != null) result.add(t to cached.value!!)
                } else if (!toTranslate.contains(t)) {
                    toTranslate.add(t)
                }
            }
            if (toTranslate.isEmpty()) return result

            // ---- Опциональный HTTP-путь ----
            val httpAllowed = useServer
                    && serverEndpoint.isNotBlank()
                    && httpFailures.get() < MAX_HTTP_FAILURES

            if (httpAllowed) {
                val httpResult = DebugTimingLogger.timed(
                    "android.translator_translate_all_http",
                    mapOf("count" to toTranslate.size)
                ) {
                    translateHttp(toTranslate)
                }

                if (httpResult != null && httpResult.size == toTranslate.size) {
                    httpFailures.set(0)
                    for (i in toTranslate.indices) {
                        val orig = toTranslate[i]
                        val tr = httpResult[i]
                        if (tr.isBlank() || tr.trim().equals(orig.trim(), ignoreCase = true)) {
                            cache[orig] = CacheEntry(null)
                        } else {
                            cache[orig] = CacheEntry(tr)
                            result.add(orig to tr)
                        }
                    }
                    return result
                } else {
                    val fails = httpFailures.incrementAndGet()
                    Log.w(tag, "HTTP translate failed ($fails/$MAX_HTTP_FAILURES), fallback to ML Kit")
                    DebugTimingLogger.logEvent("android.translator_http_fallback",
                        mapOf("fails" to fails))
                    if (fails >= MAX_HTTP_FAILURES) {
                        Log.w(tag, "Too many HTTP failures — switching to ML Kit permanently")
                        DebugTimingLogger.logEvent("android.translator_force_mlkit")
                        useServer = false
                    }
                }
            }

            // ---- ML Kit (параллельно) ----
            return DebugTimingLogger.timed(
                "android.translator_translate_all_mlkit",
                mapOf("count" to toTranslate.size)
            ) {
                val sourceLang = DebugTimingLogger.timed("android.translator_detect_lang_batch") {
                    detectSourceLang(toTranslate.first())
                }

                if (sourceLang == targetLang) {
                    for (t in toTranslate) cache[t] = CacheEntry(null)
                    return@timed result
                }

                // Параллельная обработка: на каждый текст берём свой переводчик из пула.
                val translated = coroutineScope {
                    toTranslate.map { t ->
                        async(Dispatchers.Default) {
                            var temp = false
                            val translator = borrowTranslator(sourceLang, targetLang)
                            // Если вернули не из пула — временный (пул пуст)
                            temp = synchronized(poolLock) {
                                val key = "$sourceLang->$targetLang"
                                val deque = translatorPool[key]
                                deque == null || deque.none { it === translator }
                            }
                            try {
                                val r = try {
                                    translator.translate(t).awaitT()
                                } catch (ex: Throwable) {
                                    Log.e(tag, "mlkit translate failed: ${ex.message}")
                                    null
                                }
                                t to r
                            } finally {
                                returnTranslator(sourceLang, targetLang, translator, temp)
                            }
                        }
                    }.awaitAll()
                }

                for ((orig, tr) in translated) {
                    if (tr == null || tr.trim().equals(orig.trim(), ignoreCase = true)) {
                        cache[orig] = CacheEntry(null)
                    } else {
                        cache[orig] = CacheEntry(tr)
                        result.add(orig to tr)
                    }
                }
                result
            }
        } finally {
            val ms = (System.nanoTime() - tTotal) / 1_000_000.0
            DebugTimingLogger.logTiming("android.translator_translate_all_total", ms)
        }
    }

    private fun hasLetters(s: String): Boolean {
        for (c in s) if (c.isLetter()) return true
        return false
    }

    fun close() {
        try { languageIdentifier.close() } catch (_: Throwable) {}
        synchronized(poolLock) {
            for (deque in translatorPool.values) {
                for (t in deque) {
                    try { t.close() } catch (_: Throwable) {}
                }
                deque.clear()
            }
            translatorPool.clear()
        }
        cache.clear()
        sourceLangCache.clear()
        modelReadyCache.clear()
        ready.set(false)
    }
}

private suspend fun <T> Task<T>.awaitT(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}