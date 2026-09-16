package com.example.screentranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class PcOcrService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var mjpegReader: MJPEGReader? = null
    private var ocrEngine: OcrEngine? = null
    private var sender: OcrSender? = null

    private val isProcessing = AtomicBoolean(false)

    private var engineType: OcrType = OcrType.ML_KIT
    private var confidenceThreshold: Float = 0f

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engineName = intent?.getStringExtra(EXTRA_ENGINE) ?: OcrType.ML_KIT.name
        engineType = runCatching { OcrType.valueOf(engineName) }.getOrDefault(OcrType.ML_KIT)
        confidenceThreshold = intent?.getFloatExtra(EXTRA_CONFIDENCE_THRESHOLD, 0f) ?: 0f

        // Адрес ПК определяется автоматически через adb reverse:
        //   adb reverse tcp:5005 tcp:5005
        //   adb reverse tcp:5006 tcp:5006
        // Тогда localhost на телефоне указывает на localhost на ПК.
        val mjpegUrl = "http://localhost:5005/stream"
        val ocrUrl = "http://localhost:5006/ocr"

        startForeground(NOTIFICATION_ID, buildNotification())

        ocrEngine = OcrEngineFactory.create(this, engineType)
        sender = OcrSender(ocrUrl)

        mjpegReader = MJPEGReader(mjpegUrl) { jpegBytes ->
            onFrame(jpegBytes)
        }
        mjpegReader?.start()

        Log.d(TAG, "PcOcrService started: $mjpegUrl → $ocrUrl (threshold=$confidenceThreshold)")

        return START_NOT_STICKY
    }

    private fun onFrame(jpegBytes: ByteArray) {
        // Пропускаем кадр, если предыдущий ещё обрабатывается
        if (!isProcessing.compareAndSet(false, true)) return

        scope.launch {
            try {
                val bitmap: Bitmap? = BitmapFactory.decodeByteArray(
                    jpegBytes, 0, jpegBytes.size
                )
                if (bitmap == null) {
                    Log.w(TAG, "decode failed")
                    return@launch
                }

                val fw = bitmap.width
                val fh = bitmap.height

                val engine = ocrEngine ?: return@launch
                val result = try {
                    engine.recognize(bitmap)
                } catch (e: Exception) {
                    Log.e(TAG, "ocr error", e)
                    null
                }

                bitmap.recycle()

                if (result != null && result.blocks.isNotEmpty()) {
                    val filtered = if (confidenceThreshold > 0f) {
                        result.blocks.filter { it.confidence >= confidenceThreshold }
                    } else {
                        result.blocks
                    }
                    if (filtered.isNotEmpty()) {
                        sender?.send(OcrResult(result.text, filtered), fw, fh)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "onFrame error", e)
            } finally {
                delay(100)
                isProcessing.set(false)
            }
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "pc_ocr"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "PC OCR",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("ScreenTranslator")
            .setContentText("OCR ПК активен (localhost через ADB)")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        mjpegReader?.stop()
        mjpegReader = null
        ocrEngine?.close()
        ocrEngine = null
        sender = null
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
        Log.d(TAG, "PcOcrService stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "PcOcrService"
        private const val NOTIFICATION_ID = 2
        const val EXTRA_ENGINE = "engine"
        const val EXTRA_CONFIDENCE_THRESHOLD = "confidence_threshold"
    }
}