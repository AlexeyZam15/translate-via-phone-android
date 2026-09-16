package com.example.screentranslator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View

class ZoneOverlayView(context: Context) : View(context) {

    private var blocks: List<OcrBlock> = emptyList()

    // Размеры исходного Bitmap, в системе координат которого пришли boundingBox
    private var sourceWidth: Int = 0
    private var sourceHeight: Int = 0

    private var showConfidence: Boolean = false

    private val boxPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val labelBgPaint = Paint().apply {
        color = Color.argb(200, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val labelPaint = Paint().apply {
        color = Color.WHITE
        textSize = 32f
        isAntiAlias = true
    }

    private val confPaint = Paint().apply {
        color = Color.GREEN
        textSize = 28f
        isAntiAlias = true
        isFakeBoldText = true
    }

    fun setBlocks(
        newBlocks: List<OcrBlock>,
        sourceW: Int,
        sourceH: Int,
        showConfidence: Boolean = false
    ) {
        blocks = newBlocks
        sourceWidth = sourceW
        sourceHeight = sourceH
        this.showConfidence = showConfidence
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (sourceWidth <= 0 || sourceHeight <= 0) return

        // Коэффициенты масштабирования: из координат Bitmap в координаты View
        val scaleX = width.toFloat() / sourceWidth.toFloat()
        val scaleY = height.toFloat() / sourceHeight.toFloat()

        for (block in blocks) {
            val r: Rect = block.boundingBox
            if (r.width() <= 0 || r.height() <= 0) continue

            val left = r.left * scaleX
            val top = r.top * scaleY
            val right = r.right * scaleX
            val bottom = r.bottom * scaleY

            canvas.drawRect(left, top, right, bottom, boxPaint)

            val label = block.text.take(30)
            if (label.isNotBlank()) {
                val textWidth = labelPaint.measureText(label)
                val textHeight = labelPaint.textSize

                val labelLeft = left
                val labelTop = (top - textHeight - 8f).coerceAtLeast(0f)
                val labelRight = labelLeft + textWidth + 12f
                val labelBottom = labelTop + textHeight + 8f

                canvas.drawRect(labelLeft, labelTop, labelRight, labelBottom, labelBgPaint)
                canvas.drawText(label, labelLeft + 6f, labelBottom - 6f, labelPaint)
            }

            if (showConfidence) {
                val confText = "${(block.confidence * 100).toInt()}%"
                val confX = right + 8f
                val confY = top + confPaint.textSize
                canvas.drawText(confText, confX, confY, confPaint)
            }
        }
    }
}