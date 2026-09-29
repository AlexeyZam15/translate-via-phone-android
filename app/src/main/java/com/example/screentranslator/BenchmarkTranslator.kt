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
 *   - HTTP и ML Kit теперь независимы. isReady() = httpReady || mlkitReady.
 *   - prepare() сначала быстро пингует PC-эндпоинт (порт 5008).
 *     Если PC ответил — ready=true немедленно, ML Kit готовится в фоне.
 *   - Если ни HTTP, ни ML Kit не готовы — раз в 3 секунды перепинг PC.
 *   - HTTP-перевод больше не «отключается» при фейлах, просто пробуется снова.
 *   - Логи: benchmark.http_ping_ok / fail, benchmark.mlkit_ready, benchmark.is_ready.
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

    // Флаги готовности движков
    private val httpReady = AtomicBoolean(false)
    private val mlkitReady = AtomicBoolean(false)
    private val ready = AtomicBoolean(false)

    // Фоновый скоуп для ML Kit-подготовки и периодического перепинга
    private val bgScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mlkitJob: Job? = null
    private var pingJob: Job? = null

    // Антифлуд логов
    @Volatile private var lastPingLoggedOk = false
    @Volatile private var pingAttempts = 0

    suspend fun prepare() {
        // 1) Быстрый пинг PC-эндпоинта (порт 5008)
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

        // 2) ML Kit готовим в фоне, не блокируя ready
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

        // 3) Если HTTP не готов — запускаем периодический перепинг
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
            while (!httpReady.get() && ready.get().not()) {
                delay(3000L)
                if (httpReady.get()) break
                val ok = pingServer()
                if (ok) {
                    httpReady.set(true)
                    ready.set(true)
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

    fun isReady(): Boolean = ready.get() && (httpReady.get() || mlkitReady.get())

    suspend fun translateAll(texts: List<String>): List<Pair<String, String>> {
        if (!ready.get() || texts.isEmpty()) return emptyList()

        DebugTimingLogger.logEvent("benchmark.start",
            mapOf("count" to texts.size, "target" to targetLang,
                "http" to httpReady.get(), "mlkit" to mlkitReady.get()))

        // 1) HTTP первым, если готов
        if (httpReady.get()) {
            val (httpResult, _) = runHttp(texts)
            if (httpResult.isNotEmpty()) {
                DebugTimingLogger.logEvent("benchmark.done",
                    mapOf("engines" to "http", "fallback" to false))
                return httpResult
            } else {
                // HTTP был готов, но конкретный запрос провалился.
                // Не сбрасываем httpReady — возможно, временная проблема.
                DebugTimingLogger.logEvent("benchmark.http_empty_result")
            }
        }

        // 2) ML Kit, если готов
        if (mlkitReady.get() && mlkitTranslator.isReady()) {
            val t0 = System.nanoTime()
            val mlkitResult = try {
                mlkitTranslator.translateAll(texts)
            } catch (t: Throwable) {
                Log.w(tag, "ML Kit failed: ${t.message}")
                emptyList()
            }
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            DebugTimingLogger.logTiming("benchmark.mlkit", ms,
                mapOf(
                    "text_in" to (texts.firstOrNull() ?: "").take(200),
                    "text_out" to (mlkitResult.firstOrNull()?.second ?: "").take(200),
                    "count" to texts.size
                ))
            DebugTimingLogger.logEvent("benchmark.done",
                mapOf("engines" to "mlkit", "fallback" to true))
            return mlkitResult
        }

        // Ничего не готово — пусто
        DebugTimingLogger.logEvent("benchmark.done",
            mapOf("engines" to "none"))
        return emptyList()
    }

    private suspend fun runHttp(texts: List<String>): Pair<List<Pair<String, String>>, Double> {
        val t0 = System.nanoTime()
        val httpResult = try {
            translateHttp(texts)
        } catch (t: Throwable) {
            Log.w(tag, "HTTP failed: ${t.message}")
            emptyList()
        }
        val ms = (System.nanoTime() - t0) / 1_000_000.0

        DebugTimingLogger.logTiming("benchmark.http", ms,
            mapOf(
                "text_in" to (texts.firstOrNull() ?: "").take(200),
                "text_out" to (httpResult.firstOrNull()?.second ?: "").take(200),
                "count" to texts.size
            ))
        return httpResult to ms
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