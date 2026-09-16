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

    fun send(result: OcrResult, frameW: Int, frameH: Int) {
        try {
            val root = JSONObject()
            root.put("frame_width", frameW)
            root.put("frame_height", frameH)

            val blocksArr = JSONArray()
            for (block in result.blocks) {
                val r = block.boundingBox
                if (r.width() <= 0 || r.height() <= 0) continue

                val obj = JSONObject()
                obj.put("text", block.text)
                obj.put("x", r.left)
                obj.put("y", r.top)
                obj.put("w", r.width())
                obj.put("h", r.height())
                obj.put("confidence", block.confidence.toDouble())
                blocksArr.put(obj)
            }
            root.put("blocks", blocksArr)

            val body = root.toString().toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url(pcUrl)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "POST /ocr failed: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "send error", e)
        }
    }

    companion object {
        private const val TAG = "OcrSender"
    }
}