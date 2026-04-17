package com.emiliotorrens.talk2claw.voice

/**
 * Available Google Cloud TTS voice presets for Spanish (es-ES),
 * ranked by quality tier from highest to lowest.
 */
data class VoicePreset(
    val id: String,           // Unique identifier e.g. "neural2-m"
    val name: String,         // Display name e.g. "Neural2 (Masculino)"
    val languageCode: String,
    val voiceName: String,    // Google Cloud voice name e.g. "es-ES-Neural2-B"
    val tier: String,         // "Studio", "Neural2", "Wavenet", "Standard"
    val gender: String,       // "Masculino", "Femenino"
)

/** Spanish voice presets ordered by quality (Studio > Neural2 > Wavenet > Standard). */
val VOICE_PRESETS: List<VoicePreset> = listOf(
    // Studio — highest quality, most natural (premium pricing)
    VoicePreset("studio-m", "Studio Masculino", "es-ES", "es-ES-Studio-B", "Studio", "Masculino"),
    VoicePreset("studio-f", "Studio Femenino", "es-ES", "es-ES-Studio-A", "Studio", "Femenino"),
    // Neural2 — very good quality (default)
    VoicePreset("neural2-m", "Neural2 Masculino", "es-ES", "es-ES-Neural2-B", "Neural2", "Masculino"),
    VoicePreset("neural2-f", "Neural2 Femenino", "es-ES", "es-ES-Neural2-A", "Neural2", "Femenino"),
    // Wavenet — good quality
    VoicePreset("wavenet-m", "Wavenet Masculino", "es-ES", "es-ES-Wavenet-B", "Wavenet", "Masculino"),
    VoicePreset("wavenet-f", "Wavenet Femenino", "es-ES", "es-ES-Wavenet-A", "Wavenet", "Femenino"),
    // Standard — basic quality (free tier)
    VoicePreset("standard-m", "Standard Masculino", "es-ES", "es-ES-Standard-B", "Standard", "Masculino"),
    VoicePreset("standard-f", "Standard Femenino", "es-ES", "es-ES-Standard-A", "Standard", "Femenino"),
)

/**
 * Returns the [VoicePreset] matching a given voice name (e.g. "es-ES-Neural2-B"),
 * or the default Neural2 male preset if not found (backward compatibility).
 */
fun findPresetByVoiceName(voiceName: String): VoicePreset =
    VOICE_PRESETS.firstOrNull { it.voiceName == voiceName }
        ?: VOICE_PRESETS.first { it.id == "neural2-m" }
