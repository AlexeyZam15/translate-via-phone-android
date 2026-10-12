package com.example.screentranslator

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ИЗМЕНЕНО (устранение циклического мерцания debug/translate):
 *
 *   1) УБРАН вызов loadSettingsFromPcAsync() из onCreate.
 *      Раньше SettingsActivity при открытии делала GET /settings на ПК
 *      и ПРИМЕНЯЛА полученные настройки локально. Если на ПК было старое
 *      значение (например, debug=false), оно перезаписывало локальное
 *      (debug=true), выставленное пользователем. Затем schedulePushToPc
 *      отправлял это старое значение обратно на ПК, замыкая цикл.
 *      Теперь синхронизация односторонняя: ПК → Android через broadcast
 *      (SettingsReceiver). SettingsActivity только ОТОБРАЖАЕТ локальные
 *      настройки.
 *
 *   2) УБРАН вызов schedulePushToPc() из onItemSelected спиннеров.
 *      onItemSelected срабатывает не только когда пользователь выбирает
 *      элемент, но и при ПРОГРАММНОЙ установке (spinner.setSelection).
 *      Это приводило к ложным POST /settings на ПК.
 *      Теперь schedulePushToPc() вызывается только:
 *        - в checkDebug (пользователь кликает)
 *        - в SeekBar при fromUser=true (пользователь тянет ползунок)
 *
 *   Метод loadSettingsFromPcAsync и applySettingsFromJson удалены
 *   как неиспользуемые.
 */
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
    private lateinit var spinnerTranslateProfile: Spinner
    private lateinit var textModelStatus: TextView
    private lateinit var btnDownloadModel: Button

    private val modelManager by lazy { RemoteModelManager.getInstance() }

    private val uiHandler = Handler(Looper.getMainLooper())
    private var pendingAlphaSave: Runnable? = null
    private var pendingPaddingSave: Runnable? = null
    private var pendingPushToPc: Runnable? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(3000, TimeUnit.MILLISECONDS)
        .writeTimeout(3000, TimeUnit.MILLISECONDS)
        .build()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    @Volatile private var suppressCallbacks = false

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

    private val translateProfiles = listOf(
        "speed" to "Скорость",
        "balanced" to "Баланс",
        "quality" to "Качество"
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
        spinnerTranslateProfile = findViewById(R.id.spinnerTranslateProfile)
        textModelStatus = findViewById(R.id.textModelStatus)
        btnDownloadModel = findViewById(R.id.btnDownloadModel)

        val srcAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            sourceLanguages.map { it.second }
        )
        srcAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSourceLang.adapter = srcAdapter

        val tgtAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            targetLanguages.map { it.second }
        )
        tgtAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTargetLang.adapter = tgtAdapter

        val profAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            translateProfiles.map { it.second }
        )
        profAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTranslateProfile.adapter = profAdapter

        bindControlsFromPrefs()
        setupListeners()
        checkModelStatus(currentTargetLang())

        // ИЗМЕНЕНО: loadSettingsFromPcAsync() УБРАН.
        // Синхронизация односторонняя: ПК → Android (через broadcast).
        // SettingsActivity только отображает локальные настройки.
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        pendingAlphaSave?.let { uiHandler.removeCallbacks(it) }
        pendingPaddingSave?.let { uiHandler.removeCallbacks(it) }
        pendingPushToPc?.let { uiHandler.removeCallbacks(it) }
        super.onDestroy()
    }

    private fun bindControlsFromPrefs() {
        suppressCallbacks = true
        try {
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
            val tgtIndex = targetLanguages.indexOfFirst { it.first == savedTarget }
                .coerceAtLeast(0)
            spinnerTargetLang.setSelection(tgtIndex)

            val savedProfile = Settings.getTranslateProfile(this)
            val profIndex = translateProfiles.indexOfFirst { it.first == savedProfile }
                .coerceAtLeast(0)
            spinnerTranslateProfile.setSelection(profIndex)
        } finally {
            suppressCallbacks = false
        }
    }

    private fun setupListeners() {
        checkDebug.setOnCheckedChangeListener { _, isChecked ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            Settings.setDebug(this, isChecked)
            schedulePushToPc()
        }

        seekConfidence.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                updateConfidenceLabel(progress)
                if (fromUser && !suppressCallbacks) {
                    Settings.setConfidence(this@SettingsActivity, progress)
                    schedulePushToPc()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        seekOcrPadding.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                updateOcrPaddingLabel(progress)
                if (!fromUser || suppressCallbacks) return
                pendingPaddingSave?.let { uiHandler.removeCallbacks(it) }
                val r = Runnable {
                    Settings.setOcrPadding(this@SettingsActivity, progress)
                    schedulePushToPc()
                }
                pendingPaddingSave = r
                uiHandler.postDelayed(r, 500)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        seekOverlayAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                updateAlphaLabel(progress)
                if (!fromUser || suppressCallbacks) return
                pendingAlphaSave?.let { uiHandler.removeCallbacks(it) }
                val r = Runnable {
                    Settings.setOverlayAlpha(this@SettingsActivity, progress)
                    schedulePushToPc()
                }
                pendingAlphaSave = r
                uiHandler.postDelayed(r, 300)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // ИЗМЕНЕНО: в onItemSelected спиннеров НЕ вызываем schedulePushToPc().
        // onItemSelected срабатывает и при ПРОГРАММНОЙ установке значения
        // (spinner.setSelection), что приводит к ложным POST /settings на ПК.
        // Значения, применённые программно, не должны слать POST обратно.
        spinnerSourceLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: android.view.View?,
                position: Int, id: Long
            ) {
                if (suppressCallbacks) return
                val code = sourceLanguages[position].first ?: ""
                Settings.setSourceLang(this@SettingsActivity, code)
                // schedulePushToPc() НЕ вызываем — см. комментарий выше.
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerTargetLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: android.view.View?,
                position: Int, id: Long
            ) {
                if (suppressCallbacks) return
                val code = targetLanguages[position].first
                Settings.setTargetLang(this@SettingsActivity, code)
                checkModelStatus(code)
                // schedulePushToPc() НЕ вызываем.
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerTranslateProfile.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: android.view.View?,
                position: Int, id: Long
            ) {
                if (suppressCallbacks) return
                val profile = translateProfiles[position].first
                Settings.setTranslateProfile(this@SettingsActivity, profile)
                // schedulePushToPc() НЕ вызываем.
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnDownloadModel.setOnClickListener {
            downloadModel(currentTargetLang())
        }
    }

    private fun schedulePushToPc() {
        if (suppressCallbacks) return
        pendingPushToPc?.let { uiHandler.removeCallbacks(it) }
        val r = Runnable { pushSettingsToPcAsync() }
        pendingPushToPc = r
        uiHandler.postDelayed(r, 300)
    }

    private fun pushSettingsToPcAsync() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val endpoint = Settings.getSettingsEndpoint(this@SettingsActivity)
                val obj = JSONObject().apply {
                    put("debug", Settings.isDebug(this@SettingsActivity))
                    put("translate_profile",
                        Settings.getTranslateProfile(this@SettingsActivity))
                    put("confidence", Settings.getConfidence(this@SettingsActivity))
                    put("ocr_padding", Settings.getOcrPadding(this@SettingsActivity))
                    put("overlay_alpha", Settings.getOverlayAlpha(this@SettingsActivity))
                    put("source_lang", Settings.getSourceLang(this@SettingsActivity))
                    put("target_lang", Settings.getTargetLang(this@SettingsActivity))
                    put("auto_launch_android",
                        Settings.getAutoLaunchAndroid(this@SettingsActivity))
                }
                val body = obj.toString().toRequestBody(jsonMedia)
                val req = Request.Builder().url(endpoint).post(body).build()
                httpClient.newCall(req).execute().use { /* ignore */ }
            } catch (_: Exception) {
            }
        }
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