// BenchmarkTranslator.kt (полный файл)
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
 * ИЗМЕНЕНО (устранение регрессии "оригинал вместо перевода"):
 *   - translateAll БОЛЬШЕ НЕ возвращает оригиналы при
 *     !ready && !httpReady. Теперь ВСЕГДА пытается отправить
 *     в translateHttp — если HTTP-сервер доступен, перевод пройдёт,
 *     даже если флаг httpReady не обновился.
 *   - translateHttp при успехе ставит httpReady = true.
 *   - translateAll: если HTTP вернул пустой результат и
 *     httpReady == false, делает одну повторную попытку
 *     (ping + translate).
 *   - pingServer при успехе ставит httpReady = true.
 *
 *   Ранее:
 *     - УБРАНО отбрасывание пар isSameText в translateHttp.
 *     - translateAll: merge HTTP + ML Kit сохраняет все блоки.
 *     - Fallback: для блоков без перевода — оставляем оригинал.
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
     * ИЗМЕНЕНО: БОЛЬШЕ НЕ возвращает оригиналы при !ready && !httpReady.
     *
     * Теперь ВСЕГДА пытается отправить в translateHttp. Если HTTP-сервер
     * доступен — перевод пройдёт, даже если флаг httpReady не обновился
     * (например, benchmarkTranslator был пересоздан и не успел прогреться).
     *
     * Если HTTP вернул пустой результат и httpReady == false —
     * делаем одну повторную попытку (ping + translate).
     */
    suspend fun translateAll(texts: List<String>): List<Pair<String, String>> {
        if (texts.isEmpty()) return emptyList()

        DebugTimingLogger.logEvent("benchmark.start",
            mapOf("count" to texts.size, "target" to targetLang,
                "http" to httpReady.get(), "mlkit" to mlkitReady.get()))

        val merged = LinkedHashMap<String, String>()

        // 1) HTTP — ВСЕГДА пытаемся, даже если httpReady == false.
        //    Если сервер доступен — перевод пройдёт.
        var httpResult = emptyList<Pair<String, String>>()
        try {
            val t0 = System.nanoTime()
            httpResult = translateHttp(texts)
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            DebugTimingLogger.logTiming("benchmark.http", ms,
                mapOf("count" to texts.size, "got" to httpResult.size))
            for ((src, dst) in httpResult) {
                if (!merged.containsKey(src)) {
                    merged[src] = dst
                }
            }
            // Успешный HTTP — обновляем флаг.
            if (httpResult.isNotEmpty()) {
                if (!httpReady.get()) {
                    httpReady.set(true)
                    ready.set(true)
                    DebugTimingLogger.logEvent("benchmark.http_became_ready_on_translate")
                }
            }
        } catch (t: Throwable) {
            Log.w(tag, "HTTP failed: ${t.message}")
            DebugTimingLogger.logEvent("benchmark.http_error",
                mapOf("error" to (t.message ?: "")))
        }

        // 2) Если HTTP вернул пустой результат и httpReady == false —
        //    одна повторная попытка (ping + translate).
        if (httpResult.isEmpty() && !httpReady.get()) {
            try {
                val ok = pingServer()
                if (ok) {
                    httpReady.set(true)
                    ready.set(true)
                    val t0 = System.nanoTime()
                    httpResult = translateHttp(texts)
                    val ms = (System.nanoTime() - t0) / 1_000_000.0
                    DebugTimingLogger.logTiming("benchmark.http_retry", ms,
                        mapOf("count" to texts.size, "got" to httpResult.size))
                    for ((src, dst) in httpResult) {
                        if (!merged.containsKey(src)) {
                            merged[src] = dst
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(tag, "HTTP retry failed: ${t.message}")
            }
        }

        // 3) ML Kit — для оставшихся блоков.
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

        // 4) Fallback: для блоков без перевода — оставляем оригинал.
        for (t in texts) {
            if (!merged.containsKey(t)) {
                merged[t] = t
            }
        }

        // Порядок — как в исходном списке texts.
        val result = ArrayList<Pair<String, String>>(texts.size)
        for (t in texts) {
            val dst = merged[t]
            if (dst != null && dst.isNotBlank()) {
                result.add(t to dst)
            } else {
                result.add(t to t)
            }
        }

        DebugTimingLogger.logEvent("benchmark.done",
            mapOf(
                "engines" to (if (httpReady.get()) "http+mlkit" else "mlkit"),
                "translated" to result.size,
                "total" to texts.size,
                "http_got" to httpResult.size
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
                    if (tr.isNotBlank()) {
                        out.add(texts[i] to tr)
                    }
                }
                out
            }
        }
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