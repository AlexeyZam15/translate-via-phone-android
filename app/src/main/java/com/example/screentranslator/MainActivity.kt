package com.example.screentranslator

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var checkDebug: CheckBox
    private lateinit var seekConfidence: SeekBar
    private lateinit var textConfidenceValue: TextView

    // ---- Старый сценарий: overlay OCR экрана телефона ----
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
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

        updateConfidenceLabel(seekConfidence.progress)
        seekConfidence.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                updateConfidenceLabel(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // --- Старый сценарий: OCR экрана телефона ---
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            requestNotificationIfNeeded()
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
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

        // --- Новый сценарий: OCR ПК через MJPEG (адрес — localhost через adb reverse) ---
        findViewById<Button>(R.id.btnStartPcOcr).setOnClickListener {
            requestNotificationIfNeeded()
            startPcOcrService()
        }

        findViewById<Button>(R.id.btnStopPcOcr).setOnClickListener {
            stopService(Intent(this, PcOcrService::class.java))
        }
    }

    private fun updateConfidenceLabel(progress: Int) {
        textConfidenceValue.text = "Порог уверенности: $progress%"
    }

    private fun requestNotificationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(
                android.Manifest.permission.POST_NOTIFICATIONS
            )
        }
    }

    // ---- Старый сценарий ----

    private fun requestMediaProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE)
                as android.media.projection.MediaProjectionManager
        mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun startOverlayService(resultCode: Int, data: Intent) {
        val debug = checkDebug.isChecked
        val threshold = seekConfidence.progress / 100f
        val intent = Intent(this, OverlayService::class.java).apply {
            putExtra(OverlayService.EXTRA_OCR_TYPE, OcrType.ML_KIT.name)
            putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
            putExtra(OverlayService.EXTRA_RESULT_DATA, data)
            putExtra(OverlayService.EXTRA_DEBUG, debug)
            putExtra(OverlayService.EXTRA_CONFIDENCE_THRESHOLD, threshold)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    // ---- Новый сценарий: OCR ПК ----

    private fun startPcOcrService() {
        val threshold = seekConfidence.progress / 100f
        val intent = Intent(this, PcOcrService::class.java).apply {
            putExtra(PcOcrService.EXTRA_ENGINE, OcrType.ML_KIT.name)
            putExtra(PcOcrService.EXTRA_CONFIDENCE_THRESHOLD, threshold)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}