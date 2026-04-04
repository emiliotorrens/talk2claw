package com.emiliotorrens.talk2claw.settings

/**
 * App configuration — stored in SharedPreferences via SettingsManager.
 * This data class is the single source of truth for all settings.
 */
data class AppSettings(
    val gatewayHost: String = "",
    val gatewayPort: Int = 18789,
    val gatewayToken: String = "",
    val ttsVoice: String = "es-ES-Wavenet-B",  // Male Spanish voice
    val ttsLanguageCode: String = "es-ES",
    val googleCloudApiKey: String = "",
    val usePushToTalk: Boolean = true,           // vs continuous listening
    val keepScreenOn: Boolean = true,
)
