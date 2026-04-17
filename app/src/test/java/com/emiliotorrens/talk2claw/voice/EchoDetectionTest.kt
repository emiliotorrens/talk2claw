package com.emiliotorrens.talk2claw.voice

import com.emiliotorrens.talk2claw.ui.MainViewModel
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the echo cancellation heuristic in [MainViewModel.Companion.isEcho].
 * Tests run on the JVM with no Android dependencies.
 */
class EchoDetectionTest {

    // Helper: call isEcho with default threshold
    private fun isEcho(stt: String, tts: String) = MainViewModel.isEcho(stt, tts)

    // ── Exact echo ────────────────────────────────────────────────────────────

    @Test
    fun `exact match of TTS text is detected as echo`() {
        val tts = "Hola, soy Claw, tu asistente de voz favorito."
        assertTrue("Exact repetition should be echo", isEcho(tts, tts))
    }

    @Test
    fun `casing difference still detected as echo`() {
        val tts = "Buenos días, espero que estés bien hoy."
        val stt = "BUENOS DÍAS ESPERO QUE ESTÉS BIEN HOY"
        assertTrue("Case-insensitive match should be echo", isEcho(stt, tts))
    }

    // ── High overlap ─────────────────────────────────────────────────────────

    @Test
    fun `high overlap over threshold is detected as echo`() {
        val tts = "El clima en Palma de Mallorca es soleado y cálido hoy martes."
        // STT picks up most words but garbles a couple
        val stt = "el clima en palma de mallorca es soleado y cálido hoy"
        assertTrue("High overlap (>65%) should be echo", isEcho(stt, tts))
    }

    @Test
    fun `overlap just above threshold is echo`() {
        // 7 out of 10 meaningful words match = 70% overlap > 65%
        val tts = "uno dos tres cuatro cinco seis siete ocho nueve diez"
        val stt = "uno dos tres cuatro cinco seis siete"  // 7/7 = 100% overlap
        assertTrue("70%+ overlap should be echo", isEcho(stt, tts))
    }

    // ── Low overlap ───────────────────────────────────────────────────────────

    @Test
    fun `completely different text is not echo`() {
        val tts = "El vuelo sale a las ocho de la mañana desde Barcelona."
        val stt = "Reserva una mesa para dos personas en el restaurante italiano."
        assertFalse("Unrelated text should not be echo", isEcho(stt, tts))
    }

    @Test
    fun `overlap below threshold is not echo`() {
        // Only 1 out of 6 words match = 16% < 65%
        val tts = "Me gustan las películas de ciencia ficción"
        val stt = "Películas antiguas del oeste americano son interesantes y entretenidas"
        assertFalse("Low overlap should not be echo", isEcho(stt, tts))
    }

    @Test
    fun `partial overlap that stays below threshold is not echo`() {
        val tts = "La inteligencia artificial tiene muchas aplicaciones en la medicina moderna."
        val stt = "Los avances en tecnología informática son muy rápidos actualmente."
        assertFalse("Partial overlap below threshold is not echo", isEcho(stt, tts))
    }

    // ── Short common words ────────────────────────────────────────────────────

    @Test
    fun `single common short word is not echo`() {
        // "el" is 2 chars and should pass the length filter... let's test 1-char words
        val tts = "Sí, claro que puedo ayudarte con eso perfectamente."
        val stt = "a"  // single 1-char word: filtered out by length > 1 rule
        assertFalse("Single 1-char word should not be echo", isEcho(stt, tts))
    }

    @Test
    fun `only 1-char words in STT is not echo`() {
        val tts = "Tengo muchas respuestas interesantes para tus preguntas."
        val stt = "a o y e"  // all 1-char — all filtered, sttWords becomes empty
        assertFalse("All 1-char STT words should not trigger echo", isEcho(stt, tts))
    }

    @Test
    fun `user says common word that appears in TTS is not echo`() {
        // "bien" appears in TTS but user said multiple unique words
        val tts = "Todo está muy bien organizado aquí en la oficina central de operaciones."
        val stt = "bien gracias estoy perfectamente"
        // "bien" matches (1 of 3 meaningful words = 33%) — not echo
        assertFalse("Low word overlap should not be echo", isEcho(stt, tts))
    }

    // ── Empty / null-like edge cases ──────────────────────────────────────────

    @Test
    fun `empty STT text is not echo`() {
        val tts = "Hay mucha información disponible sobre este tema tan interesante."
        assertFalse("Empty STT should return false (no echo)", isEcho("", tts))
    }

    @Test
    fun `empty TTS text is not echo`() {
        val stt = "¿Puedes ayudarme con algo importante que necesito saber?"
        assertFalse("Empty TTS should return false (nothing playing)", isEcho(stt, ""))
    }

    @Test
    fun `both empty strings is not echo`() {
        assertFalse("Both empty should return false", isEcho("", ""))
    }

    @Test
    fun `blank strings are not echo`() {
        assertFalse("Blank strings should return false", isEcho("   ", "   "))
    }

    // ── Custom threshold ──────────────────────────────────────────────────────

    @Test
    fun `custom lower threshold detects more echoes`() {
        val tts = "Puedo ayudarte con muchas cosas diferentes y variadas hoy."
        // STT: 3 words match TTS (ayudarte, con, cosas), 2 don't (nuevas, ahora) → 3/5 = 60%
        val stt = "ayudarte con cosas nuevas ahora"
        // Default threshold (65%): 60% < 65% → NOT echo
        assertFalse("60% overlap < 65% threshold should not be echo", isEcho(stt, tts))
        // Lower threshold (40%): 60% > 40% → IS echo
        assertTrue("60% overlap > 40% threshold should be echo", MainViewModel.isEcho(stt, tts, 0.40f))
    }

    @Test
    fun `custom higher threshold requires more overlap`() {
        val tts = "Hola esto es una prueba completa del sistema de detección."
        // STT: 7 words match TTS, 1 does not ("extra") → 7/8 = 87.5%
        val stt = "hola esto prueba completa del sistema detección extra"
        // Default threshold (65%): 87.5% > 65% → echo
        assertTrue("87.5% overlap > 65% should be echo", isEcho(stt, tts))
        // Very high threshold (90%): 87.5% < 90% → NOT echo
        assertFalse("87.5% overlap < 90% threshold should NOT be echo",
            MainViewModel.isEcho(stt, tts, 0.90f))
    }

    // ── Interruption scenario ─────────────────────────────────────────────────

    @Test
    fun `real interruption while claw speaks is not echo`() {
        val tts = "La capital de España es Madrid, una ciudad con mucha historia y cultura."
        // User interrupts asking something unrelated
        val stt = "Para para espera un momento"
        assertFalse("Genuine interruption should not be filtered as echo", isEcho(stt, tts))
    }

    @Test
    fun `mic picks up tts clearly during playback is echo`() {
        val tts = "Mañana tendremos reunión con el equipo de desarrollo de producto digital."
        // Mic captures exactly what's playing
        val stt = "Mañana tendremos reunión con el equipo de desarrollo de producto"
        assertTrue("Mic pickup of TTS should be detected as echo", isEcho(stt, tts))
    }
}
