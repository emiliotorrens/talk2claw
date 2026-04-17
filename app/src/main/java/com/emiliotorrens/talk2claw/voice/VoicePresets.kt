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

/** Spanish voice presets ordered by quality (Chirp3 HD > Studio > Neural2 > Wavenet > Standard). */
val VOICE_PRESETS: List<VoicePreset> = listOf(
    // Chirp 3 HD — latest generation, most natural and expressive
    VoicePreset("chirp3-m1", "Chirp3 Enceladus", "es-ES", "es-ES-Chirp3-HD-Enceladus", "Chirp3 HD", "Masculino"),
    VoicePreset("chirp3-m2", "Chirp3 Fenrir", "es-ES", "es-ES-Chirp3-HD-Fenrir", "Chirp3 HD", "Masculino"),
    VoicePreset("chirp3-m3", "Chirp3 Charon", "es-ES", "es-ES-Chirp3-HD-Charon", "Chirp3 HD", "Masculino"),
    VoicePreset("chirp3-m4", "Chirp3 Puck", "es-ES", "es-ES-Chirp3-HD-Puck", "Chirp3 HD", "Masculino"),
    VoicePreset("chirp3-f1", "Chirp3 Aoede", "es-ES", "es-ES-Chirp3-HD-Aoede", "Chirp3 HD", "Femenino"),
    VoicePreset("chirp3-f2", "Chirp3 Leda", "es-ES", "es-ES-Chirp3-HD-Leda", "Chirp3 HD", "Femenino"),
    VoicePreset("chirp3-f3", "Chirp3 Kore", "es-ES", "es-ES-Chirp3-HD-Kore", "Chirp3 HD", "Femenino"),
    VoicePreset("chirp3-f4", "Chirp3 Zephyr", "es-ES", "es-ES-Chirp3-HD-Zephyr", "Chirp3 HD", "Femenino"),
    // Chirp HD — previous generation HD voices
    VoicePreset("chirp-m", "Chirp HD Masculino", "es-ES", "es-ES-Chirp-HD-D", "Chirp HD", "Masculino"),
    VoicePreset("chirp-f1", "Chirp HD Femenino 1", "es-ES", "es-ES-Chirp-HD-F", "Chirp HD", "Femenino"),
    VoicePreset("chirp-f2", "Chirp HD Femenino 2", "es-ES", "es-ES-Chirp-HD-O", "Chirp HD", "Femenino"),
    // Studio — highest quality classic voices
    VoicePreset("studio-m", "Studio Masculino", "es-ES", "es-ES-Studio-F", "Studio", "Masculino"),
    VoicePreset("studio-f", "Studio Femenino", "es-ES", "es-ES-Studio-C", "Studio", "Femenino"),
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
