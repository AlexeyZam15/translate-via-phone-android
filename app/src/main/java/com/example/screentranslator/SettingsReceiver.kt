package com.example.screentranslator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * Принимает настройки от ПК через ADB broadcast:
 *   adb shell am broadcast -a com.example.screentranslator.SETTINGS
 *       --es settings_json '{...}'
 *
 * Применяет значения в SharedPreferences.
 */
class SettingsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action != "com.example.screentranslator.SETTINGS") return

        val json = intent.getStringExtra("settings_json") ?: return
        try {
            val obj = JSONObject(json)
            if (obj.has("debug")) {
                Settings.setDebug(context, obj.optBoolean("debug"))
            }
            if (obj.has("translate_profile")) {
                Settings.setTranslateProfile(context, obj.optString("translate_profile"))
            }
            if (obj.has("confidence")) {
                Settings.setConfidence(context, obj.optInt("confidence"))
            }
            if (obj.has("ocr_padding")) {
                Settings.setOcrPadding(context, obj.optInt("ocr_padding"))
            }
            if (obj.has("overlay_alpha")) {
                Settings.setOverlayAlpha(context, obj.optInt("overlay_alpha"))
            }
            if (obj.has("source_lang")) {
                Settings.setSourceLang(context, obj.optString("source_lang"))
            }
            if (obj.has("target_lang")) {
                Settings.setTargetLang(context, obj.optString("target_lang"))
            }
            if (obj.has("auto_launch_android")) {
                Settings.setAutoLaunchAndroid(context, obj.optBoolean("auto_launch_android"))
            }
            DebugTimingLogger.logEvent("android.settings_received_from_pc")
        } catch (_: Exception) {
        }
    }
}