package com.emiliotorrens.talk2claw.settings

import com.emiliotorrens.talk2claw.voice.VOICE_PRESETS
import com.emiliotorrens.talk2claw.voice.findPresetByVoiceName
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [AppSettings] defaults and [VoicePresets] lookup logic.
 * Pure Kotlin — no Android framework needed.
 */
class SettingsTest {

    // ── AppSettings defaults ──────────────────────────────────────────────────

    @Test
    fun `default gatewayHost is empty`() {
        assertEquals("", AppSettings().gatewayHost)
    }

    @Test
    fun `default gatewayPort is 18789`() {
        assertEquals(18789, AppSettings().gatewayPort)
    }

    @Test
    fun `default gatewayToken is empty`() {
        assertEquals("", AppSettings().gatewayToken)
    }

    @Test
    fun `default deviceToken is empty`() {
        assertEquals("", AppSettings().deviceToken)
    }

    @Test
    fun `default ttsVoice is Neural2 male`() {
        assertEquals("es-ES-Neural2-B", AppSettings().ttsVoice)
    }

    @Test
    fun `default ttsLanguageCode is es-ES`() {
        assertEquals("es-ES", AppSettings().ttsLanguageCode)
    }

    @Test
    fun `default googleCloudApiKey is empty`() {
        assertEquals("", AppSettings().googleCloudApiKey)
    }

    @Test
    fun `default keepScreenOn is true`() {
        assertTrue(AppSettings().keepScreenOn)
    }

    @Test
    fun `default speakingRate is 1_0`() {
        assertEquals(1.0f, AppSettings().speakingRate, 0.001f)
    }

    @Test
    fun `copy preserves unchanged fields`() {
        val original = AppSettings(
            gatewayHost = "http://myserver.local",
            gatewayToken = "secret",
            ttsVoice = "es-ES-Studio-B",
        )
        val updated = original.copy(speakingRate = 1.2f)

        assertEquals("host preserved", "http://myserver.local", updated.gatewayHost)
        assertEquals("token preserved", "secret", updated.gatewayToken)
        assertEquals("voice preserved", "es-ES-Studio-B", updated.ttsVoice)
        assertEquals("rate updated", 1.2f, updated.speakingRate, 0.001f)
    }

    @Test
    fun `two default AppSettings instances are equal`() {
        assertEquals(AppSettings(), AppSettings())
    }

    // ── VoicePresets ──────────────────────────────────────────────────────────

    @Test
    fun `VOICE_PRESETS list is not empty`() {
        assertTrue("VOICE_PRESETS should have entries", VOICE_PRESETS.isNotEmpty())
    }

    @Test
    fun `VOICE_PRESETS contains Studio tier`() {
        assertTrue(VOICE_PRESETS.any { it.tier == "Studio" })
    }

    @Test
    fun `VOICE_PRESETS contains Neural2 tier`() {
        assertTrue(VOICE_PRESETS.any { it.tier == "Neural2" })
    }

    @Test
    fun `VOICE_PRESETS contains Wavenet tier`() {
        assertTrue(VOICE_PRESETS.any { it.tier == "Wavenet" })
    }

    @Test
    fun `VOICE_PRESETS contains Standard tier`() {
        assertTrue(VOICE_PRESETS.any { it.tier == "Standard" })
    }

    @Test
    fun `all presets have non-blank fields`() {
        VOICE_PRESETS.forEach { preset ->
            assertFalse("id should not be blank: $preset", preset.id.isBlank())
            assertFalse("name should not be blank: $preset", preset.name.isBlank())
            assertFalse("languageCode should not be blank: $preset", preset.languageCode.isBlank())
            assertFalse("voiceName should not be blank: $preset", preset.voiceName.isBlank())
            assertFalse("tier should not be blank: $preset", preset.tier.isBlank())
            assertFalse("gender should not be blank: $preset", preset.gender.isBlank())
        }
    }

    @Test
    fun `all preset IDs are unique`() {
        val ids = VOICE_PRESETS.map { it.id }
        assertEquals("All preset IDs must be unique", ids.distinct().size, ids.size)
    }

    @Test
    fun `all preset voice names are unique`() {
        val names = VOICE_PRESETS.map { it.voiceName }
        assertEquals("All voiceNames must be unique", names.distinct().size, names.size)
    }

    // ── findPresetByVoiceName ─────────────────────────────────────────────────

    @Test
    fun `findPresetByVoiceName returns correct preset for Neural2 male`() {
        val preset = findPresetByVoiceName("es-ES-Neural2-B")
        assertEquals("neural2-m", preset.id)
        assertEquals("es-ES-Neural2-B", preset.voiceName)
        assertEquals("Neural2", preset.tier)
        assertEquals("Masculino", preset.gender)
    }

    @Test
    fun `findPresetByVoiceName returns correct preset for Studio female`() {
        val preset = findPresetByVoiceName("es-ES-Studio-A")
        assertEquals("studio-f", preset.id)
        assertEquals("Studio", preset.tier)
        assertEquals("Femenino", preset.gender)
    }

    @Test
    fun `findPresetByVoiceName returns correct preset for Wavenet male`() {
        val preset = findPresetByVoiceName("es-ES-Wavenet-B")
        assertEquals("wavenet-m", preset.id)
    }

    @Test
    fun `findPresetByVoiceName returns correct preset for Standard female`() {
        val preset = findPresetByVoiceName("es-ES-Standard-A")
        assertEquals("standard-f", preset.id)
    }

    @Test
    fun `findPresetByVoiceName returns Neural2 male as default for unknown voice`() {
        val preset = findPresetByVoiceName("unknown-voice-xyz")
        assertEquals(
            "Unknown voice should fall back to neural2-m",
            "neural2-m",
            preset.id
        )
    }

    @Test
    fun `findPresetByVoiceName returns default for empty string`() {
        val preset = findPresetByVoiceName("")
        assertEquals("Empty voice name should return default (neural2-m)", "neural2-m", preset.id)
    }

    @Test
    fun `findPresetByVoiceName is exact match not prefix match`() {
        // "es-ES-Neural2-B-Extra" should NOT match "es-ES-Neural2-B"
        val preset = findPresetByVoiceName("es-ES-Neural2-B-Extra")
        // Should fall back to default since exact match fails
        assertEquals("Partial match should not succeed", "neural2-m", preset.id)
    }

    @Test
    fun `all voice names in VOICE_PRESETS are findable`() {
        VOICE_PRESETS.forEach { expected ->
            val found = findPresetByVoiceName(expected.voiceName)
            assertEquals(
                "Voice '${expected.voiceName}' should be findable",
                expected.id,
                found.id
            )
        }
    }

    // ── Default voice consistency ─────────────────────────────────────────────

    @Test
    fun `default AppSettings ttsVoice matches a known preset`() {
        val defaultVoice = AppSettings().ttsVoice
        val preset = VOICE_PRESETS.find { it.voiceName == defaultVoice }
        assertNotNull(
            "Default ttsVoice '$defaultVoice' should exist in VOICE_PRESETS",
            preset
        )
    }

    @Test
    fun `default AppSettings ttsLanguageCode matches preset languageCode`() {
        val settings = AppSettings()
        val preset = findPresetByVoiceName(settings.ttsVoice)
        assertEquals(
            "Default language code should match preset's languageCode",
            settings.ttsLanguageCode,
            preset.languageCode
        )
    }
}
