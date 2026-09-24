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

    // Кэш скачанных моделей (Set<String> с кодами языков)
    const val KEY_DOWNLOADED_MODELS = "downloaded_models"

    // --- Debug timing ---
    const val KEY_DEBUG_TIMING_ENABLED = "debug_timing_enabled"
    const val KEY_DEBUG_VERBOSE = "debug_verbose"
    const val KEY_DEBUG_ENDPOINT = "debug_endpoint"
    const val KEY_DEBUG_AGGREGATE_WINDOW_MS = "debug_aggregate_window_ms"

    // --- Server translation ---
    const val KEY_TRANSLATE_ENDPOINT = "translate_endpoint"
    const val KEY_USE_SERVER_TRANSLATION = "use_server_translation"

    const val DEFAULT_DEBUG = false
    const val DEFAULT_CONFIDENCE = 42
    const val DEFAULT_SOURCE_LANG = ""
    const val DEFAULT_TARGET_LANG = "ru"
    const val DEFAULT_OVERLAY_ALPHA = 85
    const val DEFAULT_OCR_PADDING = 10

    const val DEFAULT_DEBUG_TIMING_ENABLED = true
    const val DEFAULT_DEBUG_VERBOSE = false
    const val DEFAULT_DEBUG_ENDPOINT = "http://localhost:5007/debug"
    const val DEFAULT_DEBUG_AGGREGATE_WINDOW_MS = 1000L

    const val DEFAULT_TRANSLATE_ENDPOINT = "http://localhost:5008/translate"
    const val DEFAULT_USE_SERVER_TRANSLATION = false

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

    /**
     * Преобразует значение слайдера "Прозрачность фона перевода" (0..100)
     * в alpha-канал фона (0..255).
     *
     * Слайдер управляет ПРОЗРАЧНОСТЬЮ:
     *   0%   → alpha=255 (полностью непрозрачный фон)
     *   100% → alpha=0   (полностью прозрачный фон, виден оригинальный текст)
     */
    fun alphaFromPercent(percent: Int): Int {
        val p = percent.coerceIn(0, 100)
        return (100 - p) * 255 / 100
    }

    fun getOcrPadding(context: Context): Int =
        prefs(context).getInt(KEY_OCR_PADDING, DEFAULT_OCR_PADDING)
    fun setOcrPadding(context: Context, value: Int) =
        prefs(context).edit { putInt(KEY_OCR_PADDING, value.coerceIn(0, 50)) }

    // ---------- Кэш скачанных моделей ----------

    /**
     * Возвращает set языков, для которых модели уже скачаны.
     * Используется, чтобы не проверять каждый раз через isModelDownloaded,
     * что занимает ~700 мс.
     */
    fun getDownloadedModels(context: Context): Set<String> {
        val p = prefs(context)
        // Возвращаем копию, чтобы избежать ConcurrentModificationException
        return try {
            p.getStringSet(KEY_DOWNLOADED_MODELS, emptySet())?.toSet() ?: emptySet()
        } catch (_: Throwable) {
            emptySet()
        }
    }

    fun addDownloadedModel(context: Context, lang: String) {
        if (lang.isBlank() || lang == "en") return
        val p = prefs(context)
        val current = getDownloadedModels(context).toMutableSet()
        if (current.add(lang)) {
            p.edit { putStringSet(KEY_DOWNLOADED_MODELS, current) }
        }
    }

    fun clearDownloadedModels(context: Context) {
        prefs(context).edit { remove(KEY_DOWNLOADED_MODELS) }
    }

    // ---------- Debug timing ----------

    fun isDebugTimingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DEBUG_TIMING_ENABLED, DEFAULT_DEBUG_TIMING_ENABLED)
    fun setDebugTimingEnabled(context: Context, value: Boolean) =
        prefs(context).edit { putBoolean(KEY_DEBUG_TIMING_ENABLED, value) }

    fun isDebugVerbose(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DEBUG_VERBOSE, DEFAULT_DEBUG_VERBOSE)
    fun setDebugVerbose(context: Context, value: Boolean) =
        prefs(context).edit { putBoolean(KEY_DEBUG_VERBOSE, value) }

    fun getDebugEndpoint(context: Context): String =
        prefs(context).getString(KEY_DEBUG_ENDPOINT, DEFAULT_DEBUG_ENDPOINT)
            ?: DEFAULT_DEBUG_ENDPOINT
    fun setDebugEndpoint(context: Context, value: String) =
        prefs(context).edit { putString(KEY_DEBUG_ENDPOINT, value) }

    fun getDebugAggregateWindowMs(context: Context): Long =
        prefs(context).getLong(KEY_DEBUG_AGGREGATE_WINDOW_MS, DEFAULT_DEBUG_AGGREGATE_WINDOW_MS)
    fun setDebugAggregateWindowMs(context: Context, value: Long) =
        prefs(context).edit { putLong(KEY_DEBUG_AGGREGATE_WINDOW_MS, value) }

    fun getTranslateEndpoint(context: Context): String =
        prefs(context).getString(KEY_TRANSLATE_ENDPOINT, DEFAULT_TRANSLATE_ENDPOINT)
            ?: DEFAULT_TRANSLATE_ENDPOINT
    fun setTranslateEndpoint(context: Context, value: String) =
        prefs(context).edit { putString(KEY_TRANSLATE_ENDPOINT, value) }

    fun isUseServerTranslation(context: Context): Boolean =
        prefs(context).getBoolean(KEY_USE_SERVER_TRANSLATION, DEFAULT_USE_SERVER_TRANSLATION)
    fun setUseServerTranslation(context: Context, value: Boolean) =
        prefs(context).edit { putBoolean(KEY_USE_SERVER_TRANSLATION, value) }
}