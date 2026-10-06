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

/**
 * Отдельный элемент OCR — слово или короткая фраза
 * с собственным bounding box. Используется для деления
 * compound-блоков (несколько независимых UI-элементов
 * в одном TextBlock от ML Kit).
 */
data class OcrElement(
    val text: String,
    val boundingBox: Rect
)

data class OcrBlock(
    val text: String,
    val boundingBox: Rect,
    val confidence: Float = 0f,
    val initialBoundingBox: Rect = boundingBox,
    val elements: List<OcrElement> = emptyList()
)