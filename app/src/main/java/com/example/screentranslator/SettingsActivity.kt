package com.example.screentranslator

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel

class SettingsActivity : AppCompatActivity() {

    private lateinit var checkDebug: CheckBox
    private lateinit var seekConfidence: SeekBar
    private lateinit var textConfidenceValue: TextView
    private lateinit var seekOcrPadding: SeekBar
    private lateinit var textOcrPaddingValue: TextView
    private lateinit var seekOverlayAlpha: SeekBar
    private lateinit var textOverlayAlphaValue: TextView
    private lateinit var spinnerSourceLang: Spinner
    private lateinit var spinnerTargetLang: Spinner
    private lateinit var spinnerTranslateEngine: Spinner
    private lateinit var textModelStatus: TextView
    private lateinit var btnDownloadModel: Button

    private val modelManager by lazy { RemoteModelManager.getInstance() }

    private val uiHandler = Handler(Looper.getMainLooper())
    private var pendingAlphaSave: Runnable? = null
    private var pendingPaddingSave: Runnable? = null

    private val sourceLanguages = listOf(
        null to "Автоопределение",
        "en" to "English",
        "ru" to "Русский",
        "de" to "Deutsch",
        "fr" to "Français",
        "es" to "Español",
        "it" to "Italiano",
        "zh" to "中文",
        "ja" to "日本語",
        "ko" to "한국어",
        "pt" to "Português",
        "pl" to "Polski",
        "tr" to "Türkçe",
        "uk" to "Українська"
    )

    private val targetLanguages = listOf(
        "ru" to "Русский",
        "en" to "English",
        "de" to "Deutsch",
        "fr" to "Français",
        "es" to "Español",
        "it" to "Italiano",
        "zh" to "中文",
        "ja" to "日本語",
        "ko" to "한국어",
        "pt" to "Português",
        "pl" to "Polski",
        "tr" to "Türkçe",
        "uk" to "Українська"
    )

    // ИЗМЕНЕНО: движки перевода
    private val translateEngines = listOf(
        "auto" to "Auto (HTTP + ML Kit fallback)",
        "http" to "HTTP (CTranslate2)",
        "mlkit" to "ML Kit (on-device)"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.title = "Настройки"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        checkDebug = findViewById(R.id.checkDebug)
        seekConfidence = findViewById(R.id.seekConfidence)
        textConfidenceValue = findViewById(R.id.textConfidenceValue)
        seekOcrPadding = findViewById(R.id.seekOcrPadding)
        textOcrPaddingValue = findViewById(R.id.textOcrPaddingValue)
        seekOverlayAlpha = findViewById(R.id.seekOverlayAlpha)
        textOverlayAlphaValue = findViewById(R.id.textOverlayAlphaValue)
        spinnerSourceLang = findViewById(R.id.spinnerSourceLang)
        spinnerTargetLang = findViewById(R.id.spinnerTargetLang)
        spinnerTranslateEngine = findViewById(R.id.spinnerTranslateEngine)
        textModelStatus = findViewById(R.id.textModelStatus)
        btnDownloadModel = findViewById(R.id.btnDownloadModel)

        // Source language
        val srcAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            sourceLanguages.map { it.second }
        )
        srcAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSourceLang.adapter = srcAdapter

        // Target language
        val tgtAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            targetLanguages.map { it.second }
        )
        tgtAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTargetLang.adapter = tgtAdapter

        // Translate engine
        val engAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            translateEngines.map { it.second }
        )
        engAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTranslateEngine.adapter = engAdapter

        // Загрузка текущих значений
        checkDebug.isChecked = Settings.isDebug(this)
        seekConfidence.progress = Settings.getConfidence(this)
        seekOcrPadding.progress = Settings.getOcrPadding(this)
        seekOverlayAlpha.progress = Settings.getOverlayAlpha(this)

        updateConfidenceLabel(seekConfidence.progress)
        updateOcrPaddingLabel(seekOcrPadding.progress)
        updateAlphaLabel(seekOverlayAlpha.progress)

        val savedSource = Settings.getSourceLang(this)
        val srcIndex = sourceLanguages.indexOfFirst { (code, _) ->
            (code ?: "") == savedSource
        }.coerceAtLeast(0)
        spinnerSourceLang.setSelection(srcIndex)

        val savedTarget = Settings.getTargetLang(this)
        val tgtIndex = targetLanguages.indexOfFirst { it.first == savedTarget }.coerceAtLeast(0)
        spinnerTargetLang.setSelection(tgtIndex)

        val savedEngine = Settings.getTranslateEngine(this)
        val engIndex = translateEngines.indexOfFirst { it.first == savedEngine }
            .coerceAtLeast(0)
        spinnerTranslateEngine.setSelection(engIndex)

        // Слушатели
        checkDebug.setOnCheckedChangeListener { _, isChecked ->
            Settings.setDebug(this, isChecked)
        }

        seekConfidence.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                updateConfidenceLabel(progress)
                if (fromUser) Settings.setConfidence(this@SettingsActivity, progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        seekOcrPadding.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                updateOcrPaddingLabel(progress)
                if (!fromUser) return
                pendingPaddingSave?.let { uiHandler.removeCallbacks(it) }
                val r = Runnable {
                    Settings.setOcrPadding(this@SettingsActivity, progress)
                }
                pendingPaddingSave = r
                uiHandler.postDelayed(r, 1000)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        seekOverlayAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                updateAlphaLabel(progress)
                if (!fromUser) return
                pendingAlphaSave?.let { uiHandler.removeCallbacks(it) }
                val r = Runnable {
                    Settings.setOverlayAlpha(this@SettingsActivity, progress)
                }
                pendingAlphaSave = r
                uiHandler.postDelayed(r, 300)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        spinnerSourceLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: android.view.View?,
                position: Int, id: Long
            ) {
                val code = sourceLanguages[position].first ?: ""
                Settings.setSourceLang(this@SettingsActivity, code)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerTargetLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: android.view.View?,
                position: Int, id: Long
            ) {
                val code = targetLanguages[position].first
                Settings.setTargetLang(this@SettingsActivity, code)
                checkModelStatus(code)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerTranslateEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: android.view.View?,
                position: Int, id: Long
            ) {
                val engine = translateEngines[position].first
                Settings.setTranslateEngine(this@SettingsActivity, engine)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnDownloadModel.setOnClickListener {
            downloadModel(currentTargetLang())
        }

        checkModelStatus(currentTargetLang())
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        pendingAlphaSave?.let { uiHandler.removeCallbacks(it) }
        pendingPaddingSave?.let { uiHandler.removeCallbacks(it) }
        super.onDestroy()
    }

    private fun currentTargetLang(): String {
        return targetLanguages[spinnerTargetLang.selectedItemPosition].first
    }

    private fun checkModelStatus(langCode: String) {
        val mlkitLang = TranslateLanguage.fromLanguageTag(langCode) ?: run {
            textModelStatus.text = "Модель перевода: язык не поддерживается"
            btnDownloadModel.isEnabled = false
            return
        }

        textModelStatus.text = "Модель перевода ($langCode): проверка…"
        btnDownloadModel.isEnabled = false

        val model = TranslateRemoteModel.Builder(mlkitLang).build()
        modelManager.isModelDownloaded(model)
            .addOnSuccessListener { downloaded ->
                if (downloaded) {
                    textModelStatus.text = "✅ Модель перевода ($langCode): скачана"
                    btnDownloadModel.isEnabled = false
                } else {
                    textModelStatus.text = "⚠️ Модель перевода ($langCode): не скачана"
                    btnDownloadModel.isEnabled = true
                }
            }
            .addOnFailureListener { e ->
                textModelStatus.text = "❌ Ошибка проверки модели: ${e.message}"
                btnDownloadModel.isEnabled = true
            }
    }

    private fun downloadModel(langCode: String) {
        val mlkitLang = TranslateLanguage.fromLanguageTag(langCode) ?: run {
            textModelStatus.text = "Модель перевода: язык не поддерживается"
            return
        }

        textModelStatus.text = "⬇ Загрузка модели ($langCode)…"
        btnDownloadModel.isEnabled = false

        val model = TranslateRemoteModel.Builder(mlkitLang).build()
        val conditions = DownloadConditions.Builder().requireWifi().build()

        modelManager.download(model, conditions)
            .addOnSuccessListener {
                textModelStatus.text = "✅ Модель перевода ($langCode): скачана"
                Toast.makeText(this, "Модель скачана", Toast.LENGTH_SHORT).show()
                btnDownloadModel.isEnabled = false
            }
            .addOnFailureListener { e ->
                textModelStatus.text = "❌ Не удалось скачать модель: ${e.message}"
                Toast.makeText(this, "Ошибка загрузки модели", Toast.LENGTH_LONG).show()
                btnDownloadModel.isEnabled = true
            }
    }

    private fun updateConfidenceLabel(progress: Int) {
        textConfidenceValue.text = "Порог уверенности: $progress%"
    }

    private fun updateOcrPaddingLabel(progress: Int) {
        textOcrPaddingValue.text = "Отступ OCR: $progress%"
    }

    private fun updateAlphaLabel(progress: Int) {
        textOverlayAlphaValue.text = "Прозрачность фона перевода: $progress%"
    }
}