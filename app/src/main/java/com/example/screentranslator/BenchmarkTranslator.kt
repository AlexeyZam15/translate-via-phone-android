package com.example.screentranslator

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Переводчик: HTTP (CTranslate2 на ПК) + ML Kit (fallback).
 *
 * ИЗМЕНЕНО:
 *   - УБРАН второй проход ML Kit (mlkit_retry). Он дублировал работу
 *     и тратил время. Retry теперь реализован внутри OcrTranslator
 *     через failedCache с TTL.
 *   - HTTP watchdog работает, пока !httpReady, независимо от ready.
 *   - Возвращаются ТОЛЬКО переведённые пары.
 *   - ФИЛЬТР: пары, где перевод совпадает с оригиналом
 *     (Wii → Wii, Nintendo → Nintendo, EMBLEM → EMBLEM),
 *     НЕ добавляются в результат.
 */
class BenchmarkTranslator(
    private val context: Context,
    private val targetLang: String,
    private val sourceLang: String? = null
) {

    private val tag = "BenchmarkTranslator"

    private val mlkitTranslator = OcrTranslator(context, targetLang, sourceLang)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .writeTimeout(5000, TimeUnit.MILLISECONDS)
        .readTimeout(5000, TimeUnit.MILLISECONDS)
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .build()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val httpReady = AtomicBoolean(false)
    private val mlkitReady = AtomicBoolean(false)
    private val ready = AtomicBoolean(false)

    private val bgScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mlkitJob: Job? = null
    private var pingJob: Job? = null

    @Volatile private var lastPingLoggedOk = false
    @Volatile private var pingAttempts = 0

    suspend fun prepare() {
        val httpOk = pingServer()
        if (httpOk) {
            httpReady.set(true)
            ready.set(true)
            DebugTimingLogger.logEvent("benchmark.http_ping_ok",
                mapOf("endpoint" to Settings.getTranslateEndpoint(context)))
            Log.d(tag, "HTTP translator READY (PC available)")
        } else {
            DebugTimingLogger.logEvent("benchmark.http_ping_fail",
                mapOf("endpoint" to Settings.getTranslateEndpoint(context)))
            Log.w(tag, "HTTP translator not available at start")
        }

        mlkitJob?.cancel()
        mlkitJob = bgScope.launch {
            try {
                mlkitTranslator.prepare()
                if (mlkitTranslator.isReady()) {
                    mlkitReady.set(true)
                    ready.set(true)
                    DebugTimingLogger.logEvent("benchmark.mlkit_ready")
                    Log.d(tag, "ML Kit translator READY (background)")
                } else {
                    DebugTimingLogger.logEvent("benchmark.mlkit_not_ready")
                    Log.w(tag, "ML Kit prepare finished but not ready")
                }
            } catch (t: Throwable) {
                Log.w(tag, "ML Kit prepare failed: ${t.message}")
                DebugTimingLogger.logEvent("benchmark.mlkit_prepare_error",
                    mapOf("error" to (t.message ?: "")))
            }
        }

        if (!httpOk) {
            startHttpWatchdog()
        }

        DebugTimingLogger.logEvent("benchmark.is_ready",
            mapOf("ready" to ready.get(),
                "http" to httpReady.get(),
                "mlkit" to mlkitReady.get()))
    }

    private fun startHttpWatchdog() {
        pingJob?.cancel()
        pingJob = bgScope.launch {
            while (!httpReady.get()) {
                delay(3000L)
                if (httpReady.get()) break
                val ok = pingServer()
                if (ok) {
                    httpReady.set(true)
                    DebugTimingLogger.logEvent("benchmark.http_ping_late_ok",
                        mapOf("attempts" to pingAttempts))
                    Log.d(tag, "HTTP translator became READY (late)")
                    break
                } else {
                    DebugTimingLogger.logEvent("benchmark.http_ping_late_fail",
                        mapOf("attempts" to pingAttempts))
                }
            }
        }
    }

    fun isReady(): Boolean = httpReady.get() || mlkitReady.get()

    fun isHttpReady(): Boolean = httpReady.get()

    /**
     * Мерджит результаты HTTP и ML Kit.
     *
     *   1) HTTP переводит всё, что может.
     *   2) ML Kit переводит оставшиеся блоки.
     *   3) Результаты мерджатся в LinkedHashMap (сохраняя порядок).
     *
     * Возвращаются ТОЛЬКО переведённые пары.
     * Повторные попытки для непереведённых блоков реализованы
     * внутри OcrTranslator через failedCache (TTL 3 сек).
     */
    suspend fun translateAll(texts: List<String>): List<Pair<String, String>> {
        if (texts.isEmpty()) return emptyList()
        if (!ready.get() && !httpReady.get()) return emptyList()

        DebugTimingLogger.logEvent("benchmark.start",
            mapOf("count" to texts.size, "target" to targetLang,
                "http" to httpReady.get(), "mlkit" to mlkitReady.get()))

        val merged = LinkedHashMap<String, String>()

        // 1) HTTP — если готов.
        if (httpReady.get()) {
            try {
                val t0 = System.nanoTime()
                val httpResult = translateHttp(texts)
                val ms = (System.nanoTime() - t0) / 1_000_000.0
                DebugTimingLogger.logTiming("benchmark.http", ms,
                    mapOf("count" to texts.size))
                for ((src, dst) in httpResult) {
                    if (!merged.containsKey(src)) {
                        merged[src] = dst
                    }
                }
            } catch (t: Throwable) {
                Log.w(tag, "HTTP failed: ${t.message}")
                DebugTimingLogger.logEvent("benchmark.http_error",
                    mapOf("error" to (t.message ?: "")))
            }
        }

        // 2) ML Kit — для оставшихся блоков.
        // ИЗМЕНЕНО: без второго прохода (mlkit_retry).
        // Retry реализован внутри OcrTranslator через failedCache.
        val remaining = texts.filter { !merged.containsKey(it) }
        if (remaining.isNotEmpty() && mlkitReady.get() && mlkitTranslator.isReady()) {
            try {
                val t0 = System.nanoTime()
                val mlkitResult = mlkitTranslator.translateAll(remaining)
                val ms = (System.nanoTime() - t0) / 1_000_000.0
                DebugTimingLogger.logTiming("benchmark.mlkit", ms,
                    mapOf("count" to remaining.size))
                for ((src, dst) in mlkitResult) {
                    if (!merged.containsKey(src)) {
                        merged[src] = dst
                    }
                }
            } catch (t: Throwable) {
                Log.w(tag, "ML Kit failed: ${t.message}")
                DebugTimingLogger.logEvent("benchmark.mlkit_error",
                    mapOf("error" to (t.message ?: "")))
            }
        }

        val result = merged.entries.map { it.key to it.value }
        DebugTimingLogger.logEvent("benchmark.done",
            mapOf(
                "engines" to (if (httpReady.get()) "http+mlkit" else "mlkit"),
                "translated" to result.size,
                "total" to texts.size
            ))
        return result
    }

    private suspend fun translateHttp(texts: List<String>): List<Pair<String, String>> {
        if (texts.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            val endpoint = Settings.getTranslateEndpoint(context)
            val arr = JSONArray()
            for (t in texts) arr.put(t)
            val root = JSONObject()
            root.put("texts", arr)
            root.put("source", sourceLang ?: "auto")
            root.put("target", targetLang)
            val body = root.toString().toRequestBody(jsonMedia)
            val req = Request.Builder().url(endpoint).post(body).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(tag, "HTTP ${resp.code}")
                    return@withContext emptyList()
                }
                val txt = resp.body?.string() ?: return@withContext emptyList()
                val obj = JSONObject(txt)
                val transArr = obj.optJSONArray("translations")
                    ?: return@withContext emptyList()
                val out = ArrayList<Pair<String, String>>()
                for (i in 0 until minOf(transArr.length(), texts.size)) {
                    val tr = transArr.optString(i, "")
                    if (tr.isNotBlank() && !isSameText(texts[i], tr)) {
                        out.add(texts[i] to tr)
                    }
                }
                out
            }
        }
    }

    private fun isSameText(original: String, translated: String): Boolean {
        return original.trim().lowercase() == translated.trim().lowercase()
    }

    private suspend fun pingServer(): Boolean {
        pingAttempts++
        return try {
            withContext(Dispatchers.IO) {
                val endpoint = Settings.getTranslateEndpoint(context)
                val body = "{\"texts\":[\"ping\"],\"source\":\"auto\",\"target\":\"$targetLang\"}"
                    .toRequestBody(jsonMedia)
                val req = Request.Builder().url(endpoint).post(body).build()
                httpClient.newCall(req).execute().use { resp ->
                    val ok = resp.isSuccessful
                    if (ok && !lastPingLoggedOk) {
                        lastPingLoggedOk = true
                    }
                    ok
                }
            }
        } catch (t: Throwable) {
            false
        }
    }

    fun close() {
        try { mlkitJob?.cancel() } catch (_: Throwable) {}
        try { pingJob?.cancel() } catch (_: Throwable) {}
        mlkitTranslator.close()
        try {
            httpClient.dispatcher.executorService.shutdown()
            httpClient.connectionPool.evictAll()
        } catch (_: Throwable) {}
        httpReady.set(false)
        mlkitReady.set(false)
        ready.set(false)
    }
}