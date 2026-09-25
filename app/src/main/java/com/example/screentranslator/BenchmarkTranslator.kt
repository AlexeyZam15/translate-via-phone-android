package com.example.screentranslator

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Бенчмарк переводов: прогоняет один и тот же текст через несколько движков
 * и логирует статистику (время, результат) в DebugTimingLogger.
 *
 * Движки:
 *   - mlkit  : ML Kit Translate (on-device, baseline)
 *   - http   : HTTP-сервер на ПК (CTranslate2 + Opus-MT, офлайн, не банится)
 *
 * ПРИОРИТЕТ ДЛЯ ОВЕРЛЕЯ:
 *   1. HTTP (CTranslate2) — качество лучше (~150 ms).
 *   2. ML Kit — fallback, если HTTP не ответил.
 *
 * Оба движка ВСЕГДА логируются в DebugTimingLogger (benchmark.mlkit, benchmark.http).
 *
 * Zebra (Picovoice) удалён — библиотека не подключена.
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
        .connectTimeout(3000, TimeUnit.MILLISECONDS)
        .writeTimeout(10000, TimeUnit.MILLISECONDS)
        .readTimeout(10000, TimeUnit.MILLISECONDS)
        .build()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val ready = AtomicBoolean(false)

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
     * Прогоняет тексты через ML Kit и HTTP параллельно.
     * Логирует оба результата в DebugTimingLogger.
     *
     * ВОЗВРАЩАЕТ:
     *   1. Результат HTTP (CTranslate2), если он непустой.
     *   2. Иначе — результат ML Kit (fallback).
     *   3. Иначе — пустой список.
     *
     * Это значит, что оверлей на ПК покажет CTranslate2, если он доступен,
     * и автоматически переключится на ML Kit, если HTTP-сервер недоступен.
     */
    suspend fun translateAll(texts: List<String>): List<Pair<String, String>> {
        if (!ready.get() || texts.isEmpty()) return emptyList()

        DebugTimingLogger.logEvent("benchmark.start",
            mapOf("count" to texts.size, "target" to targetLang))

        // async внутри coroutineScope + явные типы
        val results: List<BenchmarkResult> = coroutineScope {
            val mlkitDeferred = async<BenchmarkResult>(Dispatchers.Default) {
                runBenchmark("mlkit") {
                    mlkitTranslator.translateAll(texts)
                }
            }
            val httpDeferred = async<BenchmarkResult>(Dispatchers.Default) {
                runBenchmark("http") { translateHttp(texts) }
            }
            awaitAll(mlkitDeferred, httpDeferred)
        }

        // Логируем оба движка
        for (r in results) {
            DebugTimingLogger.logTiming(
                "benchmark.${r.engine}",
                r.timeMs,
                mapOf(
                    "text_in" to r.textIn.take(200),
                    "text_out" to (r.textOut ?: "").take(200),
                    "error" to (r.error ?: "")
                )
            )
        }

        DebugTimingLogger.logEvent("benchmark.done",
            mapOf("engines" to results.joinToString(",") { it.engine }))

        // ИЗМЕНЕНО: приоритет — HTTP (CTranslate2), fallback — ML Kit
        val httpResult = results.firstOrNull {
            it.engine == "http" && !it.textOut.isNullOrBlank()
        }
        if (httpResult != null) {
            return listOf(httpResult.textIn to httpResult.textOut!!)
        }

        DebugTimingLogger.logEvent("benchmark.http_fallback_to_mlkit",
            mapOf("reason" to "http_empty_or_failed"))

        val mlkitResult = results.firstOrNull {
            it.engine == "mlkit" && !it.textOut.isNullOrBlank()
        }
        return if (mlkitResult != null) {
            listOf(mlkitResult.textIn to mlkitResult.textOut!!)
        } else {
            emptyList()
        }
    }

    private suspend fun runBenchmark(
        engine: String,
        block: suspend () -> List<Pair<String, String>>
    ): BenchmarkResult {
        val t0 = System.nanoTime()
        return try {
            val result = block()
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            val textIn = result.firstOrNull()?.first ?: ""
            val textOut = result.firstOrNull()?.second
            BenchmarkResult(engine, textIn, textOut, ms)
        } catch (t: Throwable) {
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            Log.w(tag, "$engine failed: ${t.message}")
            BenchmarkResult(engine, "", null, ms, t.message.orEmpty())
        }
    }

    /**
     * HTTP-перевод через CTranslate2 на ПК.
     * Endpoint: http://localhost:5008/translate (через ADB reverse).
     */
    private suspend fun translateHttp(texts: List<String>): List<Pair<String, String>> {
        if (texts.isEmpty()) return emptyList()
        return try {
            withContext(Dispatchers.IO) {
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
        } catch (t: Throwable) {
            Log.w(tag, "translateHttp failed: ${t.message}")
            emptyList()
        }
    }

    fun close() {
        mlkitTranslator.close()
        ready.set(false)
    }
}