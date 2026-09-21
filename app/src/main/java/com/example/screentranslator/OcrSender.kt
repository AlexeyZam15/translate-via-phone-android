package com.example.screentranslator

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class OcrSender(private val pcUrl: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun send(
        result: OcrResult,
        frameW: Int,
        frameH: Int,
        mode: String = "translate",
        overlayAlpha: Int = 217
    ) {
        val tTotal = System.nanoTime()
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

            val jsonStr = root.toString()
            val payloadBytes = jsonStr.toByteArray(Charsets.UTF_8).size
            DebugTimingLogger.logTiming(
                "android.ocr_sender_payload_kb", payloadBytes / 1024.0,
                mapOf("blocks" to result.blocks.size)
            )

            val body = jsonStr.toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url(pcUrl)
                .post(body)
                .build()

            DebugTimingLogger.timed("android.ocr_sender_http") {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "POST /ocr failed: ${response.code}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "send error", e)
            DebugTimingLogger.logEvent("android.ocr_sender_error", mapOf("error" to e.message.orEmpty()))
        } finally {
            val ms = (System.nanoTime() - tTotal) / 1_000_000.0
            DebugTimingLogger.logTiming("android.ocr_sender_total", ms)
        }
    }

    companion object {
        private const val TAG = "OcrSender"
    }
}