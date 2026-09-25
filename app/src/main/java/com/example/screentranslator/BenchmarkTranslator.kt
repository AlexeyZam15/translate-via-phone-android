package com.example.screentranslator

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
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
 * Бенчмарк переводов: HTTP (CTranslate2) — основной, ML Kit — fallback.
 *
 * ОПТИМИЗАЦИЯ:
 *   - HTTP пробуем ПЕРВЫМ. Если ответил — ML Kit НЕ запускаем (экономия CPU).
 *   - Если HTTP упал — запускаем ML Kit (fallback).
 *   - OkHttp keep-alive на 5 минут, connectionPool.
 *
 * Логируется в DebugTimingLogger:
 *   - benchmark.http  — всегда (если HTTP был вызван)
 *   - benchmark.mlkit — только если HTTP упал (fallback)
 */
class BenchmarkTranslator(
    private val context: Context,
    private val targetLang: String,
    private val sourceLang: String? = null
) {

    private val tag = "BenchmarkTranslator"

    // ML Kit (fallback)
    private val mlkitTranslator = OcrTranslator(context, targetLang, sourceLang)

    // ОПТИМИЗАЦИЯ: keep-alive 5 минут, connectionPool на 5 соединений
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .writeTimeout(5000, TimeUnit.MILLISECONDS)
        .readTimeout(5000, TimeUnit.MILLISECONDS)
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .build()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val ready = AtomicBoolean(false)

    // ОПТИМИЗАЦИЯ: считать, сколько раз HTTP упал подряд.
    // Если > 3 — переключиться на ML Kit на 30 секунд.
    @Volatile private var httpFailures = 0
    @Volatile private var httpDisabledUntil = 0L
    private val HTTP_FAILURE_LIMIT = 3
    private val HTTP_DISABLE_MS = 30_000L

    data class BenchmarkResult(
        val engine: String,
        val textIn: String,
        val textOut: String?,
        val timeMs: Double,
        val error: String? = null
    )

    suspend fun prepare() {
        mlkitTranslator.prepare()
        ready.set(true)
    }

    fun isReady(): Boolean = ready.get() && mlkitTranslator.isReady()

    /**
     * ОПТИМИЗИРОВАНО: HTTP сначала, ML Kit — только если HTTP упал.
     */
    suspend fun translateAll(texts: List<String>): List<Pair<String, String>> {
        if (!ready.get() || texts.isEmpty()) return emptyList()

        DebugTimingLogger.logEvent("benchmark.start",
            mapOf("count" to texts.size, "target" to targetLang))

        // 1. Пробуем HTTP (CTranslate2)
        val httpAllowed = System.currentTimeMillis() >= httpDisabledUntil
        if (httpAllowed) {
            val httpStart = System.nanoTime()
            val httpResult = try {
                translateHttp(texts)
            } catch (t: Throwable) {
                Log.w(tag, "HTTP failed: ${t.message}")
                emptyList()
            }
            val httpMs = (System.nanoTime() - httpStart) / 1_000_000.0

            // Логируем HTTP
            DebugTimingLogger.logTiming(
                "benchmark.http", httpMs,
                mapOf(
                    "text_in" to (texts.firstOrNull() ?: "").take(200),
                    "text_out" to (httpResult.firstOrNull()?.second ?: "").take(200),
                    "count" to texts.size
                )
            )

            if (httpResult.isNotEmpty()) {
                // HTTP успех — ML Kit НЕ запускаем
                httpFailures = 0
                DebugTimingLogger.logEvent("benchmark.done",
                    mapOf("engines" to "http", "fallback" to false))
                return httpResult
            }

            // HTTP упал
            httpFailures++
            DebugTimingLogger.logEvent("benchmark.http_failed",
                mapOf("failures" to httpFailures, "ms" to httpMs))
            if (httpFailures >= HTTP_FAILURE_LIMIT) {
                httpDisabledUntil = System.currentTimeMillis() + HTTP_DISABLE_MS
                DebugTimingLogger.logEvent("benchmark.http_disabled",
                    mapOf("until_ms" to HTTP_DISABLE_MS))
                httpFailures = 0
            }
        } else {
            DebugTimingLogger.logEvent("benchmark.http_skipped",
                mapOf("reason" to "temporarily_disabled"))
        }

        // 2. Fallback — ML Kit
        val mlkitStart = System.nanoTime()
        val mlkitResult = try {
            mlkitTranslator.translateAll(texts)
        } catch (t: Throwable) {
            Log.w(tag, "ML Kit failed: ${t.message}")
            emptyList()
        }
        val mlkitMs = (System.nanoTime() - mlkitStart) / 1_000_000.0

        DebugTimingLogger.logTiming(
            "benchmark.mlkit", mlkitMs,
            mapOf(
                "text_in" to (texts.firstOrNull() ?: "").take(200),
                "text_out" to (mlkitResult.firstOrNull()?.second ?: "").take(200),
                "count" to texts.size
            )
        )

        DebugTimingLogger.logEvent("benchmark.done",
            mapOf("engines" to "mlkit", "fallback" to true))

        return mlkitResult
    }

    /**
     * HTTP-перевод через CTranslate2 на ПК.
     * Endpoint: http://localhost:5008/translate (через ADB reverse).
     */
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

    fun close() {
        mlkitTranslator.close()
        try {
            httpClient.dispatcher.executorService.shutdown()
            httpClient.connectionPool.evictAll()
        } catch (_: Throwable) {}
        ready.set(false)
    }
}