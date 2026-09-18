package com.example.screentranslator.engines

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.example.screentranslator.OcrBlock
import com.example.screentranslator.OcrEngine
import com.example.screentranslator.OcrResult
import com.example.screentranslator.Settings
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MlKitOcrEngine(private val context: Context) : OcrEngine {

    override val name: String = "ML Kit (2-pass)"

    private val POOL_SIZE = 4
    private val MIN_CROP_HEIGHT = 32

    private val paddingPercent: Float
        get() = Settings.getOcrPadding(context) / 100f

    private val recognizers: Array<TextRecognizer> = Array(POOL_SIZE) {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val poolMutex = Mutex()
    private val poolQueue = ArrayDeque<TextRecognizer>()

    init {
        recognizers.forEach { poolQueue.addLast(it) }
    }

    private suspend fun <T> withRecognizer(block: suspend (TextRecognizer) -> T): T {
        val recognizer = poolMutex.withLock {
            while (poolQueue.isEmpty()) {
                poolMutex.unlock()
                kotlinx.coroutines.yield()
                poolMutex.lock()
            }
            poolQueue.removeFirst()
        }
        return try {
            block(recognizer)
        } finally {
            poolMutex.withLock {
                poolQueue.addLast(recognizer)
            }
        }
    }

    override suspend fun recognize(bitmap: Bitmap): OcrResult {
        // ---------- Проход 1 ----------
        val image = InputImage.fromBitmap(bitmap, 0)
        val visionText = recognizers[0].process(image).await()

        val firstPass = mutableListOf<OcrBlock>()

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val text = line.text.trim()
                if (text.length <= 1) continue

                val box = line.boundingBox ?: continue
                if (box.width() <= 0 || box.height() <= 0) continue

                var sum = 0f
                var count = 0
                for (element in line.elements) {
                    val c = element.confidence ?: 0f
                    sum += c
                    count++
                }
                val confidence = if (count > 0) sum / count else 0f

                val r = Rect(box)
                firstPass.add(
                    OcrBlock(
                        text = text,
                        boundingBox = r,
                        confidence = confidence,
                        initialBoundingBox = Rect(r)
                    )
                )
            }
        }

        if (firstPass.isEmpty()) {
            return OcrResult(visionText.text, emptyList())
        }

        // ---------- Проход 2 ----------
        val semaphore = Semaphore(POOL_SIZE)
        val refined = coroutineScope {
            firstPass.map { block ->
                async {
                    semaphore.withPermit {
                        refineBlock(bitmap, block)
                    }
                }
            }.awaitAll()
        }

        val result = refined.filter { it.text.trim().length > 1 }
        return OcrResult(visionText.text, result)
    }

    private suspend fun refineBlock(originalBitmap: Bitmap, block: OcrBlock): OcrBlock {
        val box = block.boundingBox

        val pad = paddingPercent.coerceIn(0f, 0.5f)
        val padX = (box.width() * pad).toInt()
        val padY = (box.height() * pad).toInt()

        val cropLeft = (box.left - padX).coerceIn(0, originalBitmap.width - 1)
        val cropTop = (box.top - padY).coerceIn(0, originalBitmap.height - 1)
        val cropRight = (box.right + padX).coerceIn(cropLeft + 1, originalBitmap.width)
        val cropBottom = (box.bottom + padY).coerceIn(cropTop + 1, originalBitmap.height)

        // Изначальная рамка (с padding'ом) — то, что подаётся в OCR
        val initialBox = Rect(cropLeft, cropTop, cropRight, cropBottom)

        val w = cropRight - cropLeft
        val h = cropBottom - cropTop
        if (w <= 0 || h <= 0) {
            return block.copy(initialBoundingBox = initialBox)
        }

        return try {
            val cropped = Bitmap.createBitmap(originalBitmap, cropLeft, cropTop, w, h)
            val scale = if (h < MIN_CROP_HEIGHT) 2 else 1
            val processed = if (scale > 1) {
                Bitmap.createScaledBitmap(cropped, w * scale, h * scale, true)
            } else {
                cropped
            }

            val cropImage = InputImage.fromBitmap(processed, 0)
            val cropResult = withRecognizer { rec ->
                rec.process(cropImage).await()
            }

            val cropBlock = cropResult.textBlocks.firstOrNull()
                ?: return block.copy(initialBoundingBox = initialBox)
            val cropLine = cropBlock.lines.firstOrNull()
                ?: return block.copy(initialBoundingBox = initialBox)
            val refinedText = cropLine.text.trim()

            if (refinedText.length <= 1) {
                return block.copy(initialBoundingBox = initialBox)
            }

            val cropBox = cropLine.boundingBox
                ?: return block.copy(initialBoundingBox = initialBox)

            // Координаты текста в исходном экране
            val newLeft = cropLeft + (cropBox.left / scale)
            val newTop = cropTop + (cropBox.top / scale)
            val newRight = cropLeft + (cropBox.right / scale)
            val newBottom = cropTop + (cropBox.bottom / scale)

            // Расширяем конечную рамку на padding
            val finalLeft = (newLeft - padX).coerceIn(0, originalBitmap.width)
            val finalTop = (newTop - padY).coerceIn(0, originalBitmap.height)
            val finalRight = (newRight + padX).coerceIn(0, originalBitmap.width)
            val finalBottom = (newBottom + padY).coerceIn(0, originalBitmap.height)

            val finalBox = Rect(finalLeft, finalTop, finalRight, finalBottom)
            if (finalBox.width() <= 0 || finalBox.height() <= 0) {
                return block.copy(initialBoundingBox = initialBox)
            }

            OcrBlock(
                text = refinedText,
                boundingBox = finalBox,
                confidence = block.confidence,
                initialBoundingBox = initialBox
            )
        } catch (t: Throwable) {
            block.copy(initialBoundingBox = initialBox)
        }
    }

    override fun close() {
        recognizers.forEach {
            try { it.close() } catch (_: Throwable) {}
        }
        poolQueue.clear()
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}