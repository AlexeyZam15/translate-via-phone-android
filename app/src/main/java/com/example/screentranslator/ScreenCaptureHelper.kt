package com.example.screentranslator

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import java.nio.ByteBuffer

class ScreenCaptureHelper(private val context: Context) {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    var capturedWidth: Int = 0
        private set
    var capturedHeight: Int = 0
        private set

    @SuppressLint("WrongConstant")
    fun start(mediaProjection: MediaProjection) {
        projection = mediaProjection

        val metrics = DisplayMetrics()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        capturedWidth = metrics.widthPixels
        capturedHeight = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(
            capturedWidth,
            capturedHeight,
            PixelFormat.RGBA_8888,
            2
        )

        virtualDisplay = projection?.createVirtualDisplay(
            "ScreenCapture",
            capturedWidth,
            capturedHeight,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            Handler(Looper.getMainLooper())
        )
    }

    fun capture(): Bitmap? {
        val reader = imageReader ?: return null
        val image: Image = reader.acquireLatestImage() ?: return null

        return try {
            val plane = image.planes[0]
            val buffer: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val widthWithPadding = image.width + rowPadding / pixelStride

            val bmpWithPadding = Bitmap.createBitmap(
                widthWithPadding,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bmpWithPadding.copyPixelsFromBuffer(buffer)

            // Обрезаем padding ровно до размеров Image
            val cropped = Bitmap.createBitmap(bmpWithPadding, 0, 0, image.width, image.height)
            if (cropped != bmpWithPadding) bmpWithPadding.recycle()

            // Обновляем фактические размеры — они могут отличаться от запрошенных
            capturedWidth = cropped.width
            capturedHeight = cropped.height

            cropped
        } catch (e: Exception) {
            null
        } finally {
            image.close()
        }
    }

    fun stop() {
        try { virtualDisplay?.release() } catch (_: Exception) { }
        try { imageReader?.close() } catch (_: Exception) { }
        try { projection?.stop() } catch (_: Exception) { }
        virtualDisplay = null
        imageReader = null
        projection = null
    }
}