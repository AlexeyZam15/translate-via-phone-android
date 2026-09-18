package com.example.screentranslator

import android.content.Context
import com.example.screentranslator.engines.MlKitOcrEngine

enum class OcrType(val displayName: String) {
    ML_KIT("ML Kit")
}

object OcrEngineFactory {
    fun create(context: Context, type: OcrType): OcrEngine = when (type) {
        OcrType.ML_KIT -> MlKitOcrEngine(context)
    }
}