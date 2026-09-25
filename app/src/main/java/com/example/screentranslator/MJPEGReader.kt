package com.example.screentranslator

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MJPEGReader с автоматическим переподключением.
 *
 * ИЗМЕНЕНО:
 *   - Автоматическое переподключение при обрыве с backoff 1-3 сек.
 *   - WinError 10053 (ConnectionAborted) игнорируется — это норма
 *     при смене активного окна на ПК.
 *   - Логирование переподключений в DebugTimingLogger.
 */
class MJPEGReader(
    private val url: String,
    private val onFrame: (ByteArray) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var running = false

    private val readBuf = ByteArray(BUF_SIZE)
    private var bufPos = 0
    private var bufLen = 0

    fun start() {
        if (running) return
        running = true
        Thread {
            DebugTimingLogger.logEvent("android.mjpeg_start", mapOf("url" to url))
            var attempt = 0
            while (running) {
                try {
                    attempt++
                    DebugTimingLogger.logEvent("android.mjpeg_connect_attempt",
                        mapOf("attempt" to attempt))
                    runOnce()
                    // Если runOnce завершился без ошибки, но running — переподключаемся
                    if (running) {
                        DebugTimingLogger.logEvent("android.mjpeg_reconnect",
                            mapOf("reason" to "stream_ended"))
                    }
                } catch (e: Exception) {
                    if (running) {
                        // WinError 10053 — норма при смене окна. Логируем тихо.
                        DebugTimingLogger.logEvent("android.mjpeg_disconnect",
                            mapOf(
                                "error" to (e.message ?: ""),
                                "type" to e.javaClass.simpleName,
                                "attempt" to attempt
                            ))
                        Log.w(TAG, "MJPEG disconnected (attempt $attempt): ${e.message}")
                    }
                }
                if (running) {
                    // Backoff: 1 сек, 2 сек, 3 сек, потом 3 сек
                    val backoff = minOf(3000L, 1000L * attempt)
                    try {
                        Thread.sleep(backoff)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
            DebugTimingLogger.logEvent("android.mjpeg_stop")
        }.start()
    }

    fun stop() {
        running = false
    }

    private fun runOnce() {
        val request = Request.Builder().url(url).build()
        val response = DebugTimingLogger.timed("android.mjpeg_connect") {
            client.newCall(request).execute()
        }
        val stream = response.body?.byteStream()
            ?: throw IllegalStateException("body == null")

        bufPos = 0
        bufLen = 0

        readMjpeg(stream)
    }

    private fun readByte(input: InputStream): Int {
        if (bufPos >= bufLen) {
            val n = try {
                input.read(readBuf, 0, readBuf.size)
            } catch (e: Exception) {
                -1
            }
            if (n <= 0) return -1
            bufPos = 0
            bufLen = n
        }
        return readBuf[bufPos++].toInt() and 0xFF
    }

    private fun findMarker(input: InputStream, m1: Int, m2: Int): Boolean {
        var prev = -1
        while (running) {
            val b = readByte(input)
            if (b < 0) return false
            if (prev == m1 && b == m2) return true
            prev = b
        }
        return false
    }

    private fun readJpegBody(input: InputStream, out: ByteArrayOutputStream): Boolean {
        var prev = -1
        while (running) {
            val b = readByte(input)
            if (b < 0) return false
            out.write(b)
            if (prev == 0xFF && b == 0xD9) return true
            prev = b
        }
        return false
    }

    private fun readMjpeg(input: InputStream) {
        val buffer = ByteArrayOutputStream(256 * 1024)

        while (running) {
            val soiFound = findMarker(input, 0xFF, 0xD8)
            if (!soiFound) break

            buffer.reset()
            buffer.write(0xFF)
            buffer.write(0xD8)

            val eoiFound = DebugTimingLogger.timed("android.mjpeg_read_body") {
                readJpegBody(input, buffer)
            }

            if (!eoiFound) {
                if (!running) break
                continue
            }

            val jpeg = buffer.toByteArray()
            if (jpeg.size > 100) {
                DebugTimingLogger.logTiming(
                    "android.mjpeg_frame_size_kb", jpeg.size / 1024.0
                )
                onFrame(jpeg)
            }
        }
    }

    companion object {
        private const val TAG = "MJPEGReader"
        private const val BUF_SIZE = 64 * 1024
    }
}