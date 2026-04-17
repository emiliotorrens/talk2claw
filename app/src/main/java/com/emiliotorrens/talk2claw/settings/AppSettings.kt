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
    val ttsVoice: String = "es-ES-Neural2-B",
    val ttsLanguageCode: String = "es-ES",
    val googleCloudApiKey: String = "",
    val keepScreenOn: Boolean = true,
    val speakingRate: Float = 1.0f,
    /** Model alias to use: "flash", "sonnet" (default), or "opus". */
    val modelAlias: String = "sonnet",
    /** Whether thinking/reasoning mode is enabled. */
    val thinkingEnabled: Boolean = false,

    // ── Wake Word (Picovoice Porcupine) ──
    val wakeWordEnabled: Boolean = false,
    val picovoiceAccessKey: String = "",

    // ── Voice Match (Speaker Verification) ──
    val voiceMatchEnabled: Boolean = false,
    val voiceMatchThreshold: Float = 0.7f,
    /** Enrolled speaker embedding as JSON float array string. */
    val enrolledEmbedding: String = "",

    // ── Interruption Mode ──
    /** "auto" = interruption with headphones only, "always" = current behavior, "never" = wait for TTS */
    val interruptionMode: String = "auto",
)
