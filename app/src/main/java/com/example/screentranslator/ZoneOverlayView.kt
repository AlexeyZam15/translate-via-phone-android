package com.example.screentranslator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import kotlin.math.max
import kotlin.math.min

class ZoneOverlayView(context: Context) : View(context) {

    private var blocks: List<OcrBlock> = emptyList()
    private var sourceWidth: Int = 0
    private var sourceHeight: Int = 0

    private var showConfidence: Boolean = false
    private var showTranslation: Boolean = false
    private var bgAlpha: Int = 217

    /**
     * ИЗМЕНЕНО: отступ OCR в процентах (0..50).
     * Используется в debug-режиме для отрисовки зелёной рамки —
     * расширенного bbox, куда попадёт crop при refine.
     */
    private var ocrPadding: Int = 0

    private val boxPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val initialBoxPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 3f
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

    private val translationBgPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    private val translationTextPaint = TextPaint().apply {
        color = Color.WHITE
        isAntiAlias = true
        isFakeBoldText = true
    }

    private val minTextSize = 6f
    private val maxTextSize = 72f

    fun setBlocks(
        newBlocks: List<OcrBlock>,
        sourceW: Int,
        sourceH: Int,
        showConfidence: Boolean = false,
        showTranslation: Boolean = false,
        bgAlpha: Int = 217,
        ocrPadding: Int = 0
    ) {
        blocks = newBlocks
        sourceWidth = sourceW
        sourceHeight = sourceH
        this.showConfidence = showConfidence
        this.showTranslation = showTranslation
        this.bgAlpha = bgAlpha.coerceIn(0, 255)
        this.ocrPadding = ocrPadding.coerceIn(0, 50)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (sourceWidth <= 0 || sourceHeight <= 0) return

        val tStart = System.nanoTime()
        try {
            val scaleX = width.toFloat() / sourceWidth.toFloat()
            val scaleY = height.toFloat() / sourceHeight.toFloat()

            for (block in blocks) {
                val r: Rect = block.boundingBox
                if (r.width() <= 0 || r.height() <= 0) continue

                val left = r.left * scaleX
                val top = r.top * scaleY
                val right = r.right * scaleX
                val bottom = r.bottom * scaleY

                if (showTranslation) {
                    drawTranslation(canvas, block, left, top, right, bottom)
                } else if (showConfidence) {
                    drawDebugZone(canvas, block, left, top, right, bottom,
                        scaleX, scaleY)
                }
            }
        } finally {
            val ms = (System.nanoTime() - tStart) / 1_000_000.0
            DebugTimingLogger.logTiming(
                "android.zone_overlay_ondraw", ms,
                mapOf("blocks" to blocks.size,
                    "translation" to showTranslation,
                    "confidence" to showConfidence)
            )
        }
    }

    private fun drawTranslation(
        canvas: Canvas,
        block: OcrBlock,
        left: Float, top: Float, right: Float, bottom: Float
    ) {
        val text = block.text
        if (text.isBlank()) return

        val boxWidth = (right - left).coerceAtLeast(1f)
        val boxHeight = (bottom - top).coerceAtLeast(1f)

        translationBgPaint.color = Color.argb(bgAlpha, 0, 0, 0)
        canvas.drawRect(left, top, right, bottom, translationBgPaint)

        val padding = 4f
        var size = maxTextSize.coerceAtMost(boxHeight)
        size = size.coerceAtLeast(minTextSize)

        var layout: StaticLayout? = null
        while (size >= minTextSize) {
            translationTextPaint.textSize = size
            val l = buildLayout(text, translationTextPaint, boxWidth - padding * 2)
            if (l.height <= boxHeight - padding) {
                layout = l
                break
            }
            size -= 1f
        }
        if (layout == null) {
            translationTextPaint.textSize = minTextSize
            layout = buildLayout(text, translationTextPaint, boxWidth - padding * 2)
        }

        canvas.save()
        canvas.translate(left + padding, top + padding / 2f)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun buildLayout(text: String, paint: TextPaint, width: Float): StaticLayout {
        val w = width.coerceAtLeast(1f).toInt()
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            StaticLayout.Builder
                .obtain(text, 0, text.length, paint, w)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1f)
                .setIncludePad(false)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(text, paint, w, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false)
        }
    }

    /**
     * ИЗМЕНЕНО: в debug-режиме рисуем ДВЕ рамки:
     *   - красная — оригинальный bbox (boundingBox).
     *   - зелёная — bbox, расширенный на ocrPadding.
     *
     * Раньше зелёная рисовалась по initialBoundingBox (то, что было
     * до refine), что не соответствовало текущей настройке
     * "Отступ OCR". Теперь зелёная наглядно показывает, куда попадёт
     * crop при refine с текущим padding.
     */
    private fun drawDebugZone(
        canvas: Canvas,
        block: OcrBlock,
        left: Float, top: Float, right: Float, bottom: Float,
        scaleX: Float, scaleY: Float
    ) {
        // Зелёная рамка — bbox + padding.
        val pad = ocrPadding / 100f
        val r: Rect = block.boundingBox
        val padX = (r.width() * pad).toInt()
        val padY = (r.height() * pad).toInt()
        val paddedLeft = (r.left - padX) * scaleX
        val paddedTop = (r.top - padY) * scaleY
        val paddedRight = (r.right + padX) * scaleX
        val paddedBottom = (r.bottom + padY) * scaleY
        canvas.drawRect(paddedLeft, paddedTop, paddedRight, paddedBottom,
            initialBoxPaint)

        // Красная рамка — оригинальный bbox.
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

        val confText = "${(block.confidence * 100).toInt()}%"
        val confX = right + 8f
        val confY = top + confPaint.textSize
        canvas.drawText(confText, confX, confY, confPaint)
    }
}