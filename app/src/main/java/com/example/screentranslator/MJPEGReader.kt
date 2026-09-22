package com.example.screentranslator

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.InputStream
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

    // --- Общий буфер для чтения ---
    // Читаем из потока блоками (64 КБ), но потребляем побайтово.
    // Так мы НЕ теряем байты после SOI, и получаем скорость
    // буферизованного чтения.
    private val readBuf = ByteArray(BUF_SIZE)
    private var bufPos = 0
    private var bufLen = 0

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

                // Сбрасываем буфер на всякий случай
                bufPos = 0
                bufLen = 0

                readMjpeg(stream)
            } catch (e: Exception) {
                Log.e(TAG, "MJPEG loop error", e)
                DebugTimingLogger.logEvent("android.mjpeg_error",
                    mapOf("error" to e.message.orEmpty()))
            } finally {
                running = false
                DebugTimingLogger.logEvent("android.mjpeg_stop")
            }
        }.start()
    }

    fun stop() {
        running = false
    }

    /**
     * Читает один байт. Если буфер исчерпан — добирает из потока.
     * Возвращает -1 при EOF или ошибке.
     */
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

    /**
     * Ищет маркер (двухбайтовую последовательность) в потоке.
     * Читает байт за байтом из буфера. Байты после маркера
     * остаются в буфере и будут прочитаны следующими вызовами.
     */
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

    /**
     * Читает тело JPEG до маркера EOI (0xFF 0xD9).
     * Всё прочитанное пишет в out. Байты после EOI
     * остаются в буфере.
     */
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
            // 1. Ищем SOI (0xFF 0xD8)
            val soiFound = findMarker(input, 0xFF, 0xD8)
            if (!soiFound) break

            // 2. Начинаем собирать JPEG (с SOI)
            buffer.reset()
            buffer.write(0xFF)
            buffer.write(0xD8)

            // 3. Читаем тело до EOI (0xFF 0xD9)
            val eoiFound = DebugTimingLogger.timed("android.mjpeg_read_body") {
                readJpegBody(input, buffer)
            }

            if (!eoiFound) {
                if (!running) break
                // Не нашли EOI — начинаем заново
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