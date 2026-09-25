package com.example.screentranslator

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Главный экран.
 *
 * ИЗМЕНЕНО:
 *   - Огромная кнопка START/STOP (зелёная/красная).
 *   - Кнопка-шестерёнка → SettingsActivity.
 *   - Убраны отдельные кнопки «Старт OCR ПК» / «Стоп OCR ПК».
 *
 * Логика:
 *   START → запускает PcOcrService (OCR ПК через MJPEG + HTTP).
 *   STOP  → останавливает PcOcrService.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var btnStartStop: Button
    private lateinit var btnSettings: Button

    @Volatile private var isRunning = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* не критично */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        DebugTimingLogger.init(applicationContext)

        btnStartStop = findViewById(R.id.btnStartStop)
        btnSettings = findViewById(R.id.btnSettings)

        // Начальное состояние
        updateButtonState()

        btnStartStop.setOnClickListener {
            if (isRunning) {
                stopPcOcr()
            } else {
                startPcOcr()
            }
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // При возврате из настроек обновляем кнопку (на случай смены настроек)
        updateButtonState()
    }

    private fun updateButtonState() {
        if (isRunning) {
            btnStartStop.text = "СТОП"
            btnStartStop.setBackgroundColor(
                androidx.core.content.ContextCompat.getColor(this, android.R.color.holo_red_dark)
            )
        } else {
            btnStartStop.text = "НАЧАТЬ"
            btnStartStop.setBackgroundColor(
                androidx.core.content.ContextCompat.getColor(this, android.R.color.holo_green_dark)
            )
        }
    }

    private fun startPcOcr() {
        requestNotificationIfNeeded()

        val intent = Intent(this, PcOcrService::class.java).apply {
            putExtra(PcOcrService.EXTRA_ENGINE, OcrType.ML_KIT.name)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        isRunning = true
        updateButtonState()
        Toast.makeText(this, "OCR ПК запущен", Toast.LENGTH_SHORT).show()
    }

    private fun stopPcOcr() {
        stopService(Intent(this, PcOcrService::class.java))
        isRunning = false
        updateButtonState()
        Toast.makeText(this, "OCR ПК остановлен", Toast.LENGTH_SHORT).show()
    }

    private fun requestNotificationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(
                android.Manifest.permission.POST_NOTIFICATIONS
            )
        }
    }
}