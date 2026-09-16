package com.example.screentranslator.engines

import android.graphics.Bitmap
import android.graphics.Rect
import com.example.screentranslator.OcrBlock
import com.example.screentranslator.OcrEngine
import com.example.screentranslator.OcrResult
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MlKitOcrEngine : OcrEngine {

    override val name: String = "ML Kit"

    // Латиница + цифры + базовая пунктуация
    private val latinRecognizer = TextRecognition.getClient(
        TextRecognizerOptions.DEFAULT_OPTIONS
    )

    override suspend fun recognize(bitmap: Bitmap): OcrResult {
        val image = InputImage.fromBitmap(bitmap, 0)
        val visionText = latinRecognizer.process(image).await()

        val blocks = mutableListOf<OcrBlock>()

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val lineBox = line.boundingBox ?: continue
                if (lineBox.width() <= 0 || lineBox.height() <= 0) continue
                val lineText = line.text
                if (lineText.isBlank()) continue

                var sum = 0f
                var count = 0
                for (element in line.elements) {
                    val c = element.confidence ?: 0f
                    sum += c
                    count++
                }
                val avgConfidence = if (count > 0) sum / count else 0f

                blocks.add(
                    OcrBlock(
                        text = lineText,
                        boundingBox = lineBox,
                        confidence = avgConfidence
                    )
                )
            }
        }

        return OcrResult(visionText.text, blocks)
    }

    override fun close() {
        latinRecognizer.close()
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}