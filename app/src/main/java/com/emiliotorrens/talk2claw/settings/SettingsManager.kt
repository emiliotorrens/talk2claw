package com.emiliotorrens.talk2claw.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists AppSettings via SharedPreferences.
 */
object SettingsManager {
    private const val PREFS = "talk2claw_settings"
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun load(): AppSettings = AppSettings(
        gatewayHost = prefs.getString("gateway_host", "") ?: "",
        gatewayPort = prefs.getInt("gateway_port", 18789),
        gatewayToken = prefs.getString("gateway_token", "") ?: "",
        deviceToken = prefs.getString("device_token", "") ?: "",
        ttsVoice = prefs.getString("tts_voice", "es-ES-Neural2-B") ?: "es-ES-Neural2-B",
        ttsLanguageCode = prefs.getString("tts_lang", "es-ES") ?: "es-ES",
        googleCloudApiKey = prefs.getString("gcloud_api_key", "") ?: "",
        keepScreenOn = prefs.getBoolean("keep_screen_on", true),
        speakingRate = prefs.getFloat("speaking_rate", 1.0f),
        modelAlias = prefs.getString("model_alias", "sonnet") ?: "sonnet",
        thinkingEnabled = prefs.getBoolean("thinking_enabled", false),
    )

    fun save(s: AppSettings) {
        prefs.edit()
            .putString("gateway_host", s.gatewayHost)
            .putInt("gateway_port", s.gatewayPort)
            .putString("gateway_token", s.gatewayToken)
            .putString("device_token", s.deviceToken)
            .putString("tts_voice", s.ttsVoice)
            .putString("tts_lang", s.ttsLanguageCode)
            .putString("gcloud_api_key", s.googleCloudApiKey)
            .putBoolean("keep_screen_on", s.keepScreenOn)
            .putFloat("speaking_rate", s.speakingRate)
            .putString("model_alias", s.modelAlias)
            .putBoolean("thinking_enabled", s.thinkingEnabled)
            .apply()
    }
}
