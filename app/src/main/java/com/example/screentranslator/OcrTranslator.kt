package com.example.screentranslator

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OcrTranslator(
    private val targetLang: String,
    private val fixedSourceLang: String? = null
) {

    private val tag = "OcrTranslator"
    private val languageIdentifier: LanguageIdentifier = LanguageIdentification.getClient()
    private val translators = ConcurrentHashMap<String, Translator>()
    private val ready = AtomicBoolean(false)
    private val modelManager = RemoteModelManager.getInstance()
    private val modelReadyCache = ConcurrentHashMap<String, Boolean>()

    /** Кэш перевода: null = не показывать. */
    private data class CacheEntry(val value: String?)
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /** Кэш source-языка по тексту. */
    private val sourceLangCache = ConcurrentHashMap<String, String>()

    suspend fun prepare() {
        try {
            val initialSource = fixedSourceLang
                ?.let { TranslateLanguage.fromLanguageTag(it) }
                ?: TranslateLanguage.ENGLISH

            if (!isModelReady(initialSource, targetLang)) {
                downloadModel(initialSource, targetLang)
            }

            val options = TranslatorOptions.Builder()
                .setSourceLanguage(initialSource)
                .setTargetLanguage(targetLang)
                .build()
            val translator = Translation.getClient(options)
            translators[initialSource] = translator
            ready.set(true)
            Log.d(tag, "Translator prepared: $initialSource → $targetLang")
        } catch (t: Throwable) {
            Log.e(tag, "prepare failed: ${t.message}")
            ready.set(false)
        }
    }

    fun isReady(): Boolean = ready.get()

    private suspend fun isModelReady(source: String, target: String): Boolean {
        val key = "$source->$target"
        modelReadyCache[key]?.let { return it }

        return try {
            val downloadedModels = modelManager
                .getDownloadedModels(TranslateRemoteModel::class.java)
                .await()
            val downloadedLangs = downloadedModels.map { it.language }.toSet()
            val sourceEnReady = source == "en" || downloadedLangs.contains(source)
            val enTargetReady = target == "en" || downloadedLangs.contains(target)
            val r = sourceEnReady && enTargetReady
            modelReadyCache[key] = r
            r
        } catch (t: Throwable) {
            Log.e(tag, "isModelReady check failed: ${t.message}")
            false
        }
    }

    private fun downloadModel(source: String, target: String) {
        if (source != "en") {
            val sourceModel = TranslateRemoteModel.Builder(source).build()
            modelManager.download(sourceModel, DownloadConditions.Builder().requireWifi().build())
                .addOnSuccessListener {
                    Log.d(tag, "Model downloaded: $source")
                    modelReadyCache.remove("$source->$target")
                }
                .addOnFailureListener { e ->
                    Log.e(tag, "Failed to download $source: ${e.message}")
                }
        }
        if (target != "en") {
            val targetModel = TranslateRemoteModel.Builder(target).build()
            modelManager.download(targetModel, DownloadConditions.Builder().requireWifi().build())
                .addOnSuccessListener {
                    Log.d(tag, "Model downloaded: $target")
                    modelReadyCache.remove("$source->$target")
                }
                .addOnFailureListener { e ->
                    Log.e(tag, "Failed to download $target: ${e.message}")
                }
        }
    }

    /** Определяет source-язык текста (с кэшем). */
    private suspend fun detectSourceLang(text: String): String {
        if (fixedSourceLang != null) {
            return TranslateLanguage.fromLanguageTag(fixedSourceLang) ?: TranslateLanguage.ENGLISH
        }
        sourceLangCache[text]?.let { return it }

        val tagLang = try {
            languageIdentifier.identifyLanguage(text).await()
        } catch (t: Throwable) {
            "und"
        }
        val lang = if (tagLang == "und" || tagLang == null) {
            TranslateLanguage.ENGLISH
        } else {
            TranslateLanguage.fromLanguageTag(tagLang) ?: TranslateLanguage.ENGLISH
        }
        sourceLangCache[text] = lang
        return lang
    }

    /**
     * Переводит один блок. Возвращает null, если блок не нужно показывать.
     * Оптимизация: без проверки языка результата (доверяем ML Kit Translate).
     */
    suspend fun translate(text: String): String? {
        if (text.isBlank()) return null
        if (!hasLetters(text)) return null
        if (!ready.get()) return null

        cache[text]?.let { return it.value }

        val sourceLang = detectSourceLang(text)

        if (sourceLang == targetLang) {
            cache[text] = CacheEntry(null)
            return null
        }

        if (!isModelReady(sourceLang, targetLang)) {
            downloadModel(sourceLang, targetLang)
            return null
        }

        val translator = translators.getOrPut(sourceLang) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceLang)
                .setTargetLanguage(targetLang)
                .build()
            Translation.getClient(options)
        }

        val result = try {
            translator.translate(text).await()
        } catch (t: Throwable) {
            Log.e(tag, "translate failed: ${t.message}")
            null
        }

        if (result == null) return null

        // Перевод не изменил текст — не показывать
        if (result.trim().equals(text.trim(), ignoreCase = true)) {
            cache[text] = CacheEntry(null)
            return null
        }

        cache[text] = CacheEntry(result)
        return result
    }

    /**
     * Параллельный перевод списка текстов. Возвращает список пар
     * (оригинал, перевод) — только для тех блоков, где перевод удался.
     */
    suspend fun translateAll(texts: List<String>): List<Pair<String, String>> = coroutineScope {
        val jobs = texts.map { t -> async { t to translate(t) } }
        jobs.mapNotNull { job ->
            val (orig, translated) = job.await()
            if (translated == null) null else orig to translated
        }
    }

    private fun hasLetters(s: String): Boolean {
        for (c in s) {
            if (c.isLetter()) return true
        }
        return false
    }

    fun close() {
        try { languageIdentifier.close() } catch (_: Throwable) {}
        for (t in translators.values) {
            try { t.close() } catch (_: Throwable) {}
        }
        translators.clear()
        cache.clear()
        sourceLangCache.clear()
        modelReadyCache.clear()
        ready.set(false)
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}