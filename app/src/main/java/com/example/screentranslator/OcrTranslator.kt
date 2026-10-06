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
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

    private val languageIdentifier: LanguageIdentifier = LanguageIdentification.getClient()
    private val modelManager = RemoteModelManager.getInstance()
    private val modelReadyCache = ConcurrentHashMap<String, Boolean>()

    @Volatile private var detectedSourceLangCache: String? = null

    private val mlkitPoolSize = 6
    private val translatorPool = ConcurrentHashMap<String, ArrayDeque<Translator>>()
    private val poolLock = Any()

    private val ready = AtomicBoolean(false)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .writeTimeout(2000, TimeUnit.MILLISECONDS)
        .readTimeout(2000, TimeUnit.MILLISECONDS)
        .build()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val cache = ConcurrentHashMap<String, String>()

    private val failedCache = ConcurrentHashMap<String, Long>()

    private val WARMUP_TIMEOUT_MS = 3000L
    private val TRANSLATE_TIMEOUT_MS = 8000L
    private val FAILED_RETRY_DELAY_MS = 1500L
    private val FAILED_CACHE_MAX = 500

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
                        useServer = false
                    }
                }

                val initialSource = fixedSourceLang
                    ?.let { TranslateLanguage.fromLanguageTag(it) }
                    ?: TranslateLanguage.ENGLISH

                val langsToDownload = LinkedHashSet<String>()
                langsToDownload.add(targetLang)
                langsToDownload.add(initialSource)
                if (initialSource != TranslateLanguage.ENGLISH && targetLang != TranslateLanguage.ENGLISH) {
                    langsToDownload.add(TranslateLanguage.ENGLISH)
                }

                DebugTimingLogger.timed("android.translator_download_models") {
                    coroutineScope {
                        langsToDownload.map { lang ->
                            async(Dispatchers.IO) {
                                try {
                                    ensureModelDownloaded(lang)
                                } catch (t: Throwable) {
                                    Log.e(tag, "download $lang failed: ${t.message}")
                                }
                            }
                        }.awaitAll()
                    }
                }

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
            }
        }
    }

    private suspend fun ensureModelDownloaded(lang: String): Boolean {
        if (lang == "en") return true

        val cacheKey = "model:$lang"
        modelReadyCache[cacheKey]?.let { return it }

        val downloadedCache = Settings.getDownloadedModels(context)
        if (downloadedCache.contains(lang)) {
            modelReadyCache[cacheKey] = true
            return true
        }

        val model = TranslateRemoteModel.Builder(lang).build()

        val alreadyDownloaded = try {
            modelManager.isModelDownloaded(model).awaitT()
        } catch (t: Throwable) {
            false
        }
        if (alreadyDownloaded) {
            modelReadyCache[cacheKey] = true
            Settings.addDownloadedModel(context, lang)
            return true
        }

        val conditions = DownloadConditions.Builder().build()
        try {
            modelManager.download(model, conditions).awaitT()
            modelReadyCache[cacheKey] = true
            Settings.addDownloadedModel(context, lang)
            return true
        } catch (t: Throwable) {
            return false
        }
    }

    fun isReady(): Boolean = ready.get()

    private suspend fun pingServer(): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                val body = "{\"texts\":[],\"source\":\"auto\",\"target\":\"$targetLang\"}"
                    .toRequestBody(jsonMedia)
                val req = Request.Builder().url(serverEndpoint).post(body).build()
                httpClient.newCall(req).execute().use { resp -> resp.isSuccessful }
            }
        } catch (t: Throwable) {
            false
        }
    }

    private suspend fun ensurePool(source: String, target: String) {
        val key = "$source->$target"
        synchronized(poolLock) {
            if (translatorPool.containsKey(key)) return
        }

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(source)
            .setTargetLanguage(target)
            .build()

        val deque = ArrayDeque<Translator>(mlkitPoolSize)
        repeat(mlkitPoolSize) {
            deque.addLast(Translation.getClient(options))
        }

        DebugTimingLogger.timed("android.translator_warmup",
            mapOf("source" to source, "target" to target, "size" to mlkitPoolSize)) {
            try {
                withTimeoutOrNull(WARMUP_TIMEOUT_MS) {
                    coroutineScope {
                        deque.map { translator ->
                            async(Dispatchers.Default) {
                                try {
                                    translator.translate("a").awaitT()
                                } catch (t: Throwable) {
                                    Log.w(tag, "warmup $key failed: ${t.message}")
                                }
                            }
                        }.awaitAll()
                    }
                }
            } catch (t: Throwable) {
                Log.w(tag, "warmup $key overall failed: ${t.message}")
            }
        }

        synchronized(poolLock) {
            translatorPool[key] = deque
        }
    }

    private suspend fun borrowTranslator(source: String, target: String): Translator {
        val key = "$source->$target"
        val exists = synchronized(poolLock) { translatorPool.containsKey(key) }
        if (!exists) {
            ensurePool(source, target)
        }
        synchronized(poolLock) {
            val deque = translatorPool[key]
            if (deque != null && deque.isNotEmpty()) {
                return deque.removeFirst()
            }
        }
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

    private suspend fun detectSourceLang(text: String): String {
        if (fixedSourceLang != null) {
            return TranslateLanguage.fromLanguageTag(fixedSourceLang) ?: TranslateLanguage.ENGLISH
        }
        detectedSourceLangCache?.let { return it }

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
            detectedSourceLangCache = lang
            lang
        }
    }

    suspend fun translate(text: String): String? {
        if (text.isBlank()) return null
        if (!hasLetters(text)) return null
        if (text.length < 3) return null
        if (!ready.get()) return null

        cache[text]?.let { return it }

        val result = translateAll(listOf(text))
        return result.firstOrNull { it.first == text }?.second
    }

    suspend fun translateAll(texts: List<String>): List<Pair<String, String>> {
        val tTotal = System.nanoTime()
        try {
            if (!ready.get()) return emptyList()

            val valid = texts.filter { it.isNotBlank() && hasLetters(it) && it.length >= 3 }
            if (valid.isEmpty()) return emptyList()

            val now = System.currentTimeMillis()
            val result = ArrayList<Pair<String, String>>(valid.size)
            val toTranslate = ArrayList<String>(valid.size)

            for (t in valid) {
                val cached = cache[t]
                if (cached != null) {
                    result.add(t to cached)
                    continue
                }
                val failedAt = failedCache[t]
                if (failedAt != null && now - failedAt < FAILED_RETRY_DELAY_MS) {
                    continue
                }
                if (!toTranslate.contains(t)) {
                    toTranslate.add(t)
                }
            }
            if (toTranslate.isEmpty()) return result

            val sourceLang = DebugTimingLogger.timed("android.translator_detect_lang_batch") {
                detectSourceLang(toTranslate.first())
            }

            if (sourceLang == targetLang) {
                for (t in toTranslate) {
                    failedCache[t] = now
                }
                return result
            }

            ensurePool(sourceLang, targetLang)

            val translated = coroutineScope {
                toTranslate.map { t ->
                    async(Dispatchers.Default) {
                        var temp = false
                        val translator = borrowTranslator(sourceLang, targetLang)
                        temp = synchronized(poolLock) {
                            val key = "$sourceLang->$targetLang"
                            val deque = translatorPool[key]
                            deque == null || deque.none { it === translator }
                        }
                        try {
                            val r = try {
                                withTimeoutOrNull(TRANSLATE_TIMEOUT_MS) {
                                    translator.translate(t).awaitT()
                                }
                            } catch (ex: Throwable) {
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
                if (tr == null) {
                    failedCache[orig] = now
                    continue
                }
                val trimmed = tr.trim()
                if (trimmed.isEmpty()) {
                    failedCache[orig] = now
                    continue
                }
                if (trimmed.equals(orig.trim(), ignoreCase = true)) {
                    failedCache[orig] = now
                    continue
                }
                failedCache.remove(orig)
                cache[orig] = trimmed
                result.add(orig to trimmed)
            }

            if (failedCache.size > FAILED_CACHE_MAX) {
                val it = failedCache.entries.iterator()
                var toRemove = failedCache.size - FAILED_CACHE_MAX
                while (it.hasNext() && toRemove > 0) {
                    it.next()
                    it.remove()
                    toRemove--
                }
            }

            return result
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
        failedCache.clear()
        modelReadyCache.clear()
        detectedSourceLangCache = null
        ready.set(false)
    }
}

private suspend fun <T> Task<T>.awaitT(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}