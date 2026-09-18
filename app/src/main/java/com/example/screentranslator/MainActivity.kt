package com.example.screentranslator

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel

class MainActivity : AppCompatActivity() {

    private lateinit var checkDebug: CheckBox
    private lateinit var seekConfidence: SeekBar
    private lateinit var textConfidenceValue: TextView
    private lateinit var seekOcrPadding: SeekBar
    private lateinit var textOcrPaddingValue: TextView
    private lateinit var seekOverlayAlpha: SeekBar
    private lateinit var textOverlayAlphaValue: TextView
    private lateinit var spinnerSourceLang: Spinner
    private lateinit var spinnerTargetLang: Spinner
    private lateinit var textModelStatus: TextView
    private lateinit var btnDownloadModel: Button

    private val modelManager by lazy { RemoteModelManager.getInstance() }

    // Debounce
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

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (AndroidSettings.canDrawOverlays(this)) {
            requestMediaProjection()
        } else {
            Toast.makeText(this, "Разрешение overlay не выдано", Toast.LENGTH_SHORT).show()
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startOverlayService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "MediaProjection отклонён", Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* не критично */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkDebug = findViewById(R.id.checkDebug)
        seekConfidence = findViewById(R.id.seekConfidence)
        textConfidenceValue = findViewById(R.id.textConfidenceValue)
        seekOcrPadding = findViewById(R.id.seekOcrPadding)
        textOcrPaddingValue = findViewById(R.id.textOcrPaddingValue)
        seekOverlayAlpha = findViewById(R.id.seekOverlayAlpha)
        textOverlayAlphaValue = findViewById(R.id.textOverlayAlphaValue)
        spinnerSourceLang = findViewById(R.id.spinnerSourceLang)
        spinnerTargetLang = findViewById(R.id.spinnerTargetLang)
        textModelStatus = findViewById(R.id.textModelStatus)
        btnDownloadModel = findViewById(R.id.btnDownloadModel)

        val srcNames = sourceLanguages.map { it.second }
        val srcAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, srcNames)
        srcAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSourceLang.adapter = srcAdapter

        val tgtNames = targetLanguages.map { it.second }
        val tgtAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, tgtNames)
        tgtAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTargetLang.adapter = tgtAdapter

        // Восстанавливаем настройки
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

        // --- Слушатели ---

        checkDebug.setOnCheckedChangeListener { _, isChecked ->
            Settings.setDebug(this, isChecked)
        }

        seekConfidence.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                updateConfidenceLabel(progress)
                if (fromUser) Settings.setConfidence(this@MainActivity, progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Отступ OCR — debounce 1 сек
        seekOcrPadding.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                updateOcrPaddingLabel(progress)
                if (!fromUser) return

                pendingPaddingSave?.let { uiHandler.removeCallbacks(it) }
                val r = Runnable {
                    Settings.setOcrPadding(this@MainActivity, progress)
                }
                pendingPaddingSave = r
                uiHandler.postDelayed(r, 1000)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Прозрачность — debounce 2 сек
        seekOverlayAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                updateAlphaLabel(progress)
                if (!fromUser) return

                pendingAlphaSave?.let { uiHandler.removeCallbacks(it) }
                val r = Runnable {
                    Settings.setOverlayAlpha(this@MainActivity, progress)
                }
                pendingAlphaSave = r
                uiHandler.postDelayed(r, 2000)
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
                Settings.setSourceLang(this@MainActivity, code)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerTargetLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: android.view.View?,
                position: Int, id: Long
            ) {
                val code = targetLanguages[position].first
                Settings.setTargetLang(this@MainActivity, code)
                checkModelStatus(code)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnDownloadModel.setOnClickListener {
            downloadModel(currentTargetLang())
        }

        checkModelStatus(currentTargetLang())

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            requestNotificationIfNeeded()
            if (!AndroidSettings.canDrawOverlays(this)) {
                val intent = Intent(
                    AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayPermissionLauncher.launch(intent)
            } else {
                requestMediaProjection()
            }
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
        }

        findViewById<Button>(R.id.btnStartPcOcr).setOnClickListener {
            requestNotificationIfNeeded()
            startPcOcrService()
        }

        findViewById<Button>(R.id.btnStopPcOcr).setOnClickListener {
            stopService(Intent(this, PcOcrService::class.java))
        }
    }

    override fun onDestroy() {
        pendingAlphaSave?.let { uiHandler.removeCallbacks(it) }
        pendingPaddingSave?.let { uiHandler.removeCallbacks(it) }
        super.onDestroy()
    }

    private fun currentSourceLang(): String? {
        return sourceLanguages[spinnerSourceLang.selectedItemPosition].first
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

    private fun requestNotificationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(
                android.Manifest.permission.POST_NOTIFICATIONS
            )
        }
    }

    private fun requestMediaProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE)
                as android.media.projection.MediaProjectionManager
        mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun startOverlayService(resultCode: Int, data: Intent) {
        val intent = Intent(this, OverlayService::class.java).apply {
            putExtra(OverlayService.EXTRA_OCR_TYPE, OcrType.ML_KIT.name)
            putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
            putExtra(OverlayService.EXTRA_RESULT_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun startPcOcrService() {
        val intent = Intent(this, PcOcrService::class.java).apply {
            putExtra(PcOcrService.EXTRA_ENGINE, OcrType.ML_KIT.name)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}