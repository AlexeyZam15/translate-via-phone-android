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
 * Бенчмарк переводов: HTTP (CTranslate2) + ML Kit.
 *
 * Режимы (Settings.getTranslateEngine):
 *   - "auto"  — HTTP основной, fallback на ML Kit (по умолчанию)
 *   - "http"  — только HTTP (CTranslate2)
 *   - "mlkit" — только ML Kit
 *
 * Логируется в DebugTimingLogger: benchmark.http, benchmark.mlkit.
 */
class BenchmarkTranslator(
    private val context: Context,
    private val targetLang: String,
    private val sourceLang: String? = null
) {

    private val tag = "BenchmarkTranslator"

    // ML Kit (baseline / fallback)
    private val mlkitTranslator = OcrTranslator(context, targetLang, sourceLang)

    // HTTP-клиент для ПК-сервера (CTranslate2)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .writeTimeout(5000, TimeUnit.MILLISECONDS)
        .readTimeout(5000, TimeUnit.MILLISECONDS)
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .build()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val ready = AtomicBoolean(false)

    // HTTP-отключается на 30 сек после 3 ошибок подряд
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
     * Переводит тексты согласно выбранному движку.
     */
    suspend fun translateAll(texts: List<String>): List<Pair<String, String>> {
        if (!ready.get() || texts.isEmpty()) return emptyList()

        val engine = Settings.getTranslateEngine(context)
        DebugTimingLogger.logEvent("benchmark.start",
            mapOf("count" to texts.size, "target" to targetLang, "engine" to engine))

        return when (engine) {
            "mlkit" -> {
                // Только ML Kit
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
                    mapOf("engines" to "mlkit", "fallback" to false))
                mlkitResult
            }

            "http" -> {
                // Только HTTP (без fallback)
                val (result, _) = runHttp(texts)
                DebugTimingLogger.logEvent("benchmark.done",
                    mapOf("engines" to "http", "fallback" to false))
                result
            }

            else -> {
                // "auto": HTTP основной, fallback на ML Kit
                val (httpResult, _) = runHttp(texts)
                if (httpResult.isNotEmpty()) {
                    DebugTimingLogger.logEvent("benchmark.done",
                        mapOf("engines" to "http", "fallback" to false))
                    return httpResult
                }

                // Fallback на ML Kit
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
                mlkitResult
            }
        }
    }

    /**
     * HTTP-перевод. Возвращает (результат, время_ms).
     */
    private suspend fun runHttp(texts: List<String>): Pair<List<Pair<String, String>>, Double> {
        val httpAllowed = System.currentTimeMillis() >= httpDisabledUntil
        if (!httpAllowed) {
            DebugTimingLogger.logEvent("benchmark.http_skipped",
                mapOf("reason" to "temporarily_disabled"))
            return emptyList<Pair<String, String>>() to 0.0
        }

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

        if (httpResult.isNotEmpty()) {
            httpFailures = 0
            return httpResult to ms
        }

        // HTTP упал
        httpFailures++
        DebugTimingLogger.logEvent("benchmark.http_failed",
            mapOf("failures" to httpFailures, "ms" to ms))
        if (httpFailures >= HTTP_FAILURE_LIMIT) {
            httpDisabledUntil = System.currentTimeMillis() + HTTP_DISABLE_MS
            DebugTimingLogger.logEvent("benchmark.http_disabled",
                mapOf("until_ms" to HTTP_DISABLE_MS))
            httpFailures = 0
        }
        return emptyList<Pair<String, String>>() to ms
    }

    /**
     * HTTP-запрос к CTranslate2 на ПК через ADB reverse.
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