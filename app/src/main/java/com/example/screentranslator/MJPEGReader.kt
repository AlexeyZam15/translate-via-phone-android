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
            try {
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                val stream = response.body?.byteStream()
                    ?: throw IllegalStateException("body == null")

                val input = BufferedInputStream(stream, 64 * 1024)
                readMjpeg(input)
            } catch (e: Exception) {
                Log.e(TAG, "MJPEG loop error", e)
            } finally {
                running = false
            }
        }.start()
    }

    fun stop() {
        running = false
    }

    private fun readMjpeg(input: BufferedInputStream) {
        val sojMarker = byteArrayOf(0xFF.toByte(), 0xD8.toByte())  // SOI
        val eojMarker = byteArrayOf(0xFF.toByte(), 0xD9.toByte())  // EOI

        while (running) {
            if (!findMarker(input, sojMarker)) break

            val buffer = ByteArrayOutputStream()
            buffer.write(sojMarker)

            var prev = -1
            while (running) {
                val b = input.read()
                if (b == -1) return
                buffer.write(b)
                if (prev == 0xFF && b == 0xD9) break
                prev = b
            }
            if (!running) break

            val jpeg = buffer.toByteArray()
            if (jpeg.size > 100) {
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