package com.example.screentranslator

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class MJPEGReader(
    private val url: String,
    private val onFrame: (ByteArray) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        Thread {
            DebugTimingLogger.logEvent("android.mjpeg_start", mapOf("url" to url))
            try {
                val request = Request.Builder().url(url).build()
                val response = DebugTimingLogger.timed("android.mjpeg_connect") {
                    client.newCall(request).execute()
                }
                val stream = response.body?.byteStream()
                    ?: throw IllegalStateException("body == null")

                // Буфер 128 KB — меньше syscalls при чтении JPEG-кадров.
                val input = BufferedInputStream(stream, 128 * 1024)
                readMjpeg(input)
            } catch (e: Exception) {
                Log.e(TAG, "MJPEG loop error", e)
                DebugTimingLogger.logEvent("android.mjpeg_error", mapOf("error" to e.message.orEmpty()))
            } finally {
                running = false
                DebugTimingLogger.logEvent("android.mjpeg_stop")
            }
        }.start()
    }

    fun stop() {
        running = false
    }

    private fun readMjpeg(input: BufferedInputStream) {
        val sojMarker = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        val eojMarker = byteArrayOf(0xFF.toByte(), 0xD9.toByte())

        while (running) {
            val found = DebugTimingLogger.timed("android.mjpeg_find_soi") {
                findMarker(input, sojMarker)
            }
            if (!found) break

            val buffer = ByteArrayOutputStream()
            buffer.write(sojMarker)

            var prev = -1
            DebugTimingLogger.timed("android.mjpeg_read_body") {
                while (running) {
                    val b = input.read()
                    if (b == -1) return@timed
                    buffer.write(b)
                    if (prev == 0xFF && b == 0xD9) break
                    prev = b
                }
            }
            if (!running) break

            val jpeg = buffer.toByteArray()
            if (jpeg.size > 100) {
                DebugTimingLogger.logTiming(
                    "android.mjpeg_frame_size_kb", jpeg.size / 1024.0
                )
                onFrame(jpeg)
            }
        }
    }

    private fun findMarker(input: BufferedInputStream, marker: ByteArray): Boolean {
        var match = 0
        while (running) {
            val b = input.read()
            if (b == -1) return false
            if (b.toByte() == marker[match]) {
                match++
                if (match == marker.size) return true
            } else {
                match = if (b.toByte() == marker[0]) 1 else 0
            }
        }
        return false
    }

    companion object {
        private const val TAG = "MJPEGReader"
    }
}