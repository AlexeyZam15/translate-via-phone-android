package com.example.screentranslator

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Главный экран.
 *
 * ИЗМЕНЕНО:
 *   - Принимает extra auto_start_ocr=true и сразу запускает PcOcrService
 *     (используется при автозапуске с ПК).
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

        // Автозапуск OCR с ПК (extra auto_start_ocr=true)
        val autoStart = intent?.getBooleanExtra(EXTRA_AUTO_START_OCR, false) == true
        if (autoStart) {
            DebugTimingLogger.logEvent("android.auto_start_ocr_received")
            // Небольшая задержка, чтобы активити успела стать foreground
            btnStartStop.postDelayed({
                if (!isRunning) {
                    startPcOcr()
                }
            }, 300)
        }
    }

    override fun onResume() {
        super.onResume()
        updateButtonState()
    }

    private fun updateButtonState() {
        if (isRunning) {
            btnStartStop.text = "СТОП"
            btnStartStop.setBackgroundColor(
                androidx.core.content.ContextCompat.getColor(
                    this, android.R.color.holo_red_dark)
            )
        } else {
            btnStartStop.text = "НАЧАТЬ"
            btnStartStop.setBackgroundColor(
                androidx.core.content.ContextCompat.getColor(
                    this, android.R.color.holo_green_dark)
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

    companion object {
        const val EXTRA_AUTO_START_OCR = "auto_start_ocr"
    }
}