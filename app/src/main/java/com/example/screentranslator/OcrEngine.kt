package com.example.screentranslator

import android.graphics.Bitmap
import android.graphics.Rect

interface OcrEngine {
    val name: String
    suspend fun recognize(bitmap: Bitmap): OcrResult
    fun close()
}

data class OcrResult(
    val text: String,
    val blocks: List<OcrBlock>
)

data class OcrBlock(
    val text: String,
    val boundingBox: Rect,
    val confidence: Float = 0f
)