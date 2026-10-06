// MlKitOcrEngine.kt (полный файл)
package com.example.screentranslator.engines

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.example.screentranslator.DebugTimingLogger
import com.example.screentranslator.OcrBlock
import com.example.screentranslator.OcrElement
import com.example.screentranslator.OcrEngine
import com.example.screentranslator.OcrResult
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ML Kit OCR без refine.
 *
 * ИЗМЕНЕНО (кардинально):
 *   - REFINE ПОЛНОСТЬЮ УБРАН. ML Kit сам корректно определяет bbox
 *     TextBlock в первом проходе по всему экрану. Refine (второй
 *     проход по crop-зоне) ломал bbox: если ML Kit в crop-зоне
 *     разбивал многострочный блок на строки, выбирался один
 *     TextBlock, и bbox обрезался до одной строки (в 2-3 раза
 *     меньше по высоте).
 *   - Теперь bbox блока — это bbox от ML Kit из первого прохода.
 *     Никаких пересчётов, никаких crop, никаких union.
 *   - Убраны: refineZone, refineCache, ZoneKey, selectBestCropBlock,
 *     computeIou, centerDistance, unionBoundingBoxes, paddingPercent,
 *     MAX_PAD_PX, MIN_CROP_HEIGHT, REFINE_CACHE_MAX.
 */
class MlKitOcrEngine(private val context: Context) : OcrEngine {

    override val name: String = "ML Kit (single-pass, no refine)"

    private val MIN_BLOCK_TEXT_LENGTH = 3

    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Собирает все слова (Element) со своими bbox из переданного TextBlock.
     */
    private fun collectElements(block: com.google.mlkit.vision.text.Text.TextBlock): List<OcrElement> {
        val out = ArrayList<OcrElement>()
        for (line in block.lines) {
            for (el in line.elements) {
                val t = el.text.trim()
                if (t.isEmpty()) continue
                val r = el.boundingBox ?: continue
                if (r.width() <= 0 || r.height() <= 0) continue
                out.add(OcrElement(text = t, boundingBox = Rect(r)))
            }
        }
        return out
    }

    /**
     * Единственный проход ML Kit по всему экрану.
     * Bbox блоков — как их вернул ML Kit, без изменений.
     */
    override suspend fun recognize(bitmap: Bitmap): OcrResult {
        val tTotal = System.nanoTime()
        try {
            val visionText = DebugTimingLogger.timed("android.ocr_pass1") {
                val image = InputImage.fromBitmap(bitmap, 0)
                recognizer.process(image).awaitSync()
            }

            val blocks = mutableListOf<OcrBlock>()

            for (block in visionText.textBlocks) {
                val text = block.text.trim()
                if (text.length < MIN_BLOCK_TEXT_LENGTH) continue

                val box = block.boundingBox ?: continue
                if (box.width() <= 0 || box.height() <= 0) continue

                var sum = 0f
                var count = 0
                for (line in block.lines) {
                    for (element in line.elements) {
                        val c = element.confidence ?: 0f
                        sum += c
                        count++
                    }
                }
                val confidence = if (count > 0) sum / count else 0f

                val elements = collectElements(block)

                val r = Rect(box)
                blocks.add(
                    OcrBlock(
                        text = text,
                        boundingBox = r,
                        confidence = confidence,
                        initialBoundingBox = Rect(r),
                        elements = elements
                    )
                )
            }

            DebugTimingLogger.logTiming(
                "android.ocr_pass1_blocks", blocks.size.toDouble()
            )

            return OcrResult(visionText.text, blocks)
        } finally {
            val ms = (System.nanoTime() - tTotal) / 1_000_000.0
            DebugTimingLogger.logTiming("android.ocr_total", ms)
        }
    }

    /**
     * ИЗМЕНЕНО: invalidateCache теперь no-op — кэша больше нет.
     * Оставлено для совместимости с PcOcrService (при смене padding).
     */
    fun invalidateCache() {
        // no-op: refine убран, кэша нет.
        DebugTimingLogger.logEvent("android.ocr_zone_cache_invalidated")
    }

    override fun close() {
        try { recognizer.close() } catch (_: Throwable) {}
    }
}

private suspend fun <T> Task<T>.awaitSync(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}