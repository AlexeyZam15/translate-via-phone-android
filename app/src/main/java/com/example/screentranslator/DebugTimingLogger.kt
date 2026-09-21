package com.example.screentranslator

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object DebugTimingLogger {

    private const val TAG = "DebugTiming"
    private const val MAX_BUFFER = 500
    private const val FLUSH_INTERVAL_MS = 800L
    private const val MAX_BATCH = 200

    private val buffer = ConcurrentLinkedQueue<JSONObject>()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    @Volatile private var endpoint: String = Settings.DEFAULT_DEBUG_ENDPOINT
    @Volatile private var enabled: Boolean = Settings.DEFAULT_DEBUG_TIMING_ENABLED
    @Volatile private var verbose: Boolean = Settings.DEFAULT_DEBUG_VERBOSE
    @Volatile private var aggregateWindowMs: Long = Settings.DEFAULT_DEBUG_AGGREGATE_WINDOW_MS

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "debug-flush").apply { isDaemon = true }
        }
    private val started = AtomicBoolean(false)
    private val lazyInitDone = AtomicBoolean(false)

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        applySettingsFromPrefs(context.applicationContext)
        startSchedulerIfNeeded()
        logEvent("android.logger_init", mapOf("endpoint" to endpoint))
    }

    fun init(enabled: Boolean, verbose: Boolean, endpoint: String, aggregateWindowMs: Long) {
        this.enabled = enabled
        this.verbose = verbose
        this.endpoint = endpoint
        this.aggregateWindowMs = aggregateWindowMs
        startSchedulerIfNeeded()
    }

    private fun applySettingsFromPrefs(ctx: Context) {
        enabled = Settings.isDebugTimingEnabled(ctx)
        verbose = Settings.isDebugVerbose(ctx)
        endpoint = Settings.getDebugEndpoint(ctx)
        aggregateWindowMs = Settings.getDebugAggregateWindowMs(ctx)
    }

    private fun lazyInitIfNeeded() {
        if (lazyInitDone.get()) return
        val ctx = appContext ?: return
        if (lazyInitDone.compareAndSet(false, true)) {
            applySettingsFromPrefs(ctx)
            startSchedulerIfNeeded()
        }
    }

    private fun startSchedulerIfNeeded() {
        if (enabled && started.compareAndSet(false, true)) {
            scheduler.scheduleWithFixedDelay(
                { flush() },
                FLUSH_INTERVAL_MS,
                FLUSH_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            )
        }
    }

    fun logEvent(stage: String, data: Map<String, Any> = emptyMap()) {
        lazyInitIfNeeded()
        if (!enabled) return
        val rec = JSONObject().apply {
            put("ts", System.currentTimeMillis() / 1000.0)
            put("source", "android")
            put("stage", stage)
            put("type", "event")
            put("data", JSONObject(data))
        }
        enqueue(rec)
    }

    fun logTiming(stage: String, durationMs: Double, extra: Map<String, Any> = emptyMap()) {
        lazyInitIfNeeded()
        if (!enabled) return
        if (verbose) {
            val rec = JSONObject().apply {
                put("ts", System.currentTimeMillis() / 1000.0)
                put("source", "android")
                put("stage", stage)
                put("type", "timing")
                put("duration_ms", durationMs)
                put("extra", JSONObject(extra))
            }
            enqueue(rec)
        } else {
            Aggregator.add(stage, durationMs, extra)
            Aggregator.maybeFlush(this)
        }
    }

    private fun enqueue(rec: JSONObject) {
        if (buffer.size >= MAX_BUFFER) {
            buffer.poll()
        }
        buffer.offer(rec)
        Log.d(TAG, rec.toString())
    }

    fun flush() {
        if (!enabled || buffer.isEmpty()) return
        val arr = JSONArray()
        val batch = ArrayList<JSONObject>(MAX_BATCH)
        var count = 0
        while (count < MAX_BATCH) {
            val rec = buffer.poll() ?: break
            batch.add(rec)
            arr.put(rec)
            count++
        }
        if (arr.length() == 0) return
        try {
            val body = arr.toString().toRequestBody(jsonMedia)
            val req = Request.Builder().url(endpoint).post(body).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "flush failed: ${resp.code}, requeue ${batch.size}")
                    // Возвращаем записи в буфер (в начало), чтобы не потерять
                    for (rec in batch.asReversed()) {
                        if (buffer.size >= MAX_BUFFER) buffer.poll()
                        // ConcurrentLinkedQueue не даёт добавить в начало,
                        // но мы можем просто добавить в конец — порядок не критичен
                        buffer.offer(rec)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "flush error: ${e.message}, requeue ${batch.size}")
            for (rec in batch) {
                if (buffer.size >= MAX_BUFFER) buffer.poll()
                buffer.offer(rec)
            }
        }
    }

    fun shutdown() {
        flush()
        scheduler.shutdownNow()
    }

    inline fun <T> timed(stage: String, extra: Map<String, Any> = emptyMap(), block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            val ms = (System.nanoTime() - start) / 1_000_000.0
            logTiming(stage, ms, extra)
        }
    }

    private object Aggregator {
        private val lock = Any()
        private val buckets = HashMap<String, MutableList<Pair<Double, Map<String, Any>>>>()
        @Volatile private var lastFlush = System.currentTimeMillis()

        fun add(stage: String, durationMs: Double, extra: Map<String, Any>) {
            synchronized(lock) {
                buckets.getOrPut(stage) { mutableListOf() }.add(durationMs to extra)
            }
        }

        fun maybeFlush(logger: DebugTimingLogger) {
            val now = System.currentTimeMillis()
            if (now - lastFlush < logger.aggregateWindowMs) return
            val snapshot: Map<String, List<Pair<Double, Map<String, Any>>>>
            synchronized(lock) {
                if (buckets.isEmpty()) {
                    lastFlush = now
                    return
                }
                snapshot = buckets.mapValues { it.value.toList() }
                buckets.clear()
                lastFlush = now
            }
            for ((stage, items) in snapshot) {
                val durations = items.map { it.first }.sorted()
                val n = durations.size
                val p95Idx = minOf(n - 1, (n * 0.95).toInt())
                val rec = JSONObject().apply {
                    put("ts", now / 1000.0)
                    put("source", "android")
                    put("stage", stage)
                    put("type", "aggregate")
                    put("count", n)
                    put("avg_ms", durations.average())
                    put("min_ms", durations.first())
                    put("max_ms", durations.last())
                    put("p95_ms", durations[p95Idx])
                    put("window_ms", logger.aggregateWindowMs)
                }
                logger.enqueue(rec)
            }
        }
    }
}