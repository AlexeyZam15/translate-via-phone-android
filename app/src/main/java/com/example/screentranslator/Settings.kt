package com.example.screentranslator

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

object Settings {
    private const val PREFS = "screen_translator_prefs"

    const val KEY_DEBUG = "debug"
    const val KEY_CONFIDENCE = "confidence"
    const val KEY_SOURCE_LANG = "source_lang"
    const val KEY_TARGET_LANG = "target_lang"
    const val KEY_OVERLAY_ALPHA = "overlay_alpha"
    const val KEY_OCR_PADDING = "ocr_padding"

    const val DEFAULT_DEBUG = false
    const val DEFAULT_CONFIDENCE = 42
    const val DEFAULT_SOURCE_LANG = ""
    const val DEFAULT_TARGET_LANG = "ru"
    const val DEFAULT_OVERLAY_ALPHA = 85
    const val DEFAULT_OCR_PADDING = 10  // в процентах (0..50)

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isDebug(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DEBUG, DEFAULT_DEBUG)
    fun setDebug(context: Context, value: Boolean) =
        prefs(context).edit { putBoolean(KEY_DEBUG, value) }

    fun getConfidence(context: Context): Int =
        prefs(context).getInt(KEY_CONFIDENCE, DEFAULT_CONFIDENCE)
    fun setConfidence(context: Context, value: Int) =
        prefs(context).edit { putInt(KEY_CONFIDENCE, value) }

    fun getSourceLang(context: Context): String =
        prefs(context).getString(KEY_SOURCE_LANG, DEFAULT_SOURCE_LANG) ?: DEFAULT_SOURCE_LANG
    fun setSourceLang(context: Context, value: String) =
        prefs(context).edit { putString(KEY_SOURCE_LANG, value) }

    fun getTargetLang(context: Context): String =
        prefs(context).getString(KEY_TARGET_LANG, DEFAULT_TARGET_LANG) ?: DEFAULT_TARGET_LANG
    fun setTargetLang(context: Context, value: String) =
        prefs(context).edit { putString(KEY_TARGET_LANG, value) }

    fun getOverlayAlpha(context: Context): Int =
        prefs(context).getInt(KEY_OVERLAY_ALPHA, DEFAULT_OVERLAY_ALPHA)
    fun setOverlayAlpha(context: Context, value: Int) =
        prefs(context).edit { putInt(KEY_OVERLAY_ALPHA, value) }

    fun alphaFromPercent(percent: Int): Int {
        val p = percent.coerceIn(0, 100)
        return p * 255 / 100
    }

    // --- Отступ OCR (padding) в процентах ---
    fun getOcrPadding(context: Context): Int =
        prefs(context).getInt(KEY_OCR_PADDING, DEFAULT_OCR_PADDING)
    fun setOcrPadding(context: Context, value: Int) =
        prefs(context).edit { putInt(KEY_OCR_PADDING, value.coerceIn(0, 50)) }
}