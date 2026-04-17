package com.emiliotorrens.talk2claw.settings

/**
 * App configuration — stored in SharedPreferences via SettingsManager.
 * This data class is the single source of truth for all settings.
 */
data class AppSettings(
    val gatewayHost: String = "",
    val gatewayPort: Int = 18789,
    val gatewayToken: String = "",
    /** Persisted device token after first successful gateway pairing. */
    val deviceToken: String = "",
    val ttsVoice: String = "es-ES-Neural2-B",  // Male Spanish Neural2 voice (better quality)
    val ttsLanguageCode: String = "es-ES",
    val googleCloudApiKey: String = "",
    val keepScreenOn: Boolean = true,
    val speakingRate: Float = 1.0f,
)
