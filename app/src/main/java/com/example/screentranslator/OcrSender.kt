package com.example.screentranslator

import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OcrSender(private val pcUrl: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .writeTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val okCount = AtomicInteger(0)
    private val errCount = AtomicInteger(0)

    fun send(
        result: OcrResult,
        frameW: Int,
        frameH: Int,
        mode: String = "translate",
        overlayAlpha: Int = 217,
        force: Boolean = false
    ) {
        if (result.blocks.isEmpty() && !force) return

        val tBuild = System.nanoTime()
        val jsonStr: String
        val payloadBytes: Int
        try {
            val root = JSONObject()
            root.put("frame_width", frameW)
            root.put("frame_height", frameH)
            root.put("mode", mode)
            root.put("overlay_alpha", overlayAlpha)

            val blocksArr = JSONArray()
            for (block in result.blocks) {
                val r = block.boundingBox
                if (r.width() <= 0 || r.height() <= 0) continue

                val ir = block.initialBoundingBox

                val obj = JSONObject()
                obj.put("text", block.text)
                obj.put("x", r.left)
                obj.put("y", r.top)
                obj.put("w", r.width())
                obj.put("h", r.height())
                obj.put("confidence", block.confidence.toDouble())
                obj.put("initial_x", ir.left)
                obj.put("initial_y", ir.top)
                obj.put("initial_w", ir.width())
                obj.put("initial_h", ir.height())
                blocksArr.put(obj)
            }
            root.put("blocks", blocksArr)

            jsonStr = root.toString()
            payloadBytes = jsonStr.toByteArray(Charsets.UTF_8).size
            DebugTimingLogger.logTiming(
                "android.ocr_sender_build_json",
                (System.nanoTime() - tBuild) / 1_000_000.0,
                mapOf("blocks" to result.blocks.size)
            )
        } catch (e: Exception) {
            Log.e(TAG, "build json error", e)
            DebugTimingLogger.logEvent("android.ocr_sender_build_error",
                mapOf("error" to e.message.orEmpty()))
            return
        }

        val body = jsonStr.toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(pcUrl)
            .post(body)
            .build()

        val tStart = System.nanoTime()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                errCount.incrementAndGet()
                Log.w(TAG, "send failed: ${e.message}")
                DebugTimingLogger.logEvent("android.ocr_sender_error",
                    mapOf("error" to e.message.orEmpty()))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val ok = it.isSuccessful
                    if (ok) {
                        okCount.incrementAndGet()
                    } else {
                        errCount.incrementAndGet()
                        Log.w(TAG, "POST /ocr failed: ${it.code}")
                    }
                    val ms = (System.nanoTime() - tStart) / 1_000_000.0
                    DebugTimingLogger.logTiming(
                        "android.ocr_sender_http", ms,
                        mapOf(
                            "ok" to ok,
                            "payload_kb" to payloadBytes / 1024.0,
                            "ok_count" to okCount.get(),
                            "err_count" to errCount.get()
                        )
                    )
                }
            }
        })
    }

    companion object {
        private const val TAG = "OcrSender"
    }
}