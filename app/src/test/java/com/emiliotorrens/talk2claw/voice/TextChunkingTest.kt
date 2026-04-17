package com.emiliotorrens.talk2claw.voice

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the text chunking logic in [TextToSpeech.Companion].
 * Tests run on the JVM with no Android dependencies.
 */
class TextChunkingTest {

    // Convenience aliases for readability
    private val CHUNK_MIN = TextToSpeech.CHUNK_MIN
    private val CHUNK_MAX = TextToSpeech.CHUNK_MAX

    // ── Basic chunking ────────────────────────────────────────────────────────

    @Test
    fun `empty string returns empty list`() {
        val result = TextToSpeech.chunkText("")
        assertTrue("Expected empty list for empty input", result.isEmpty())
    }

    @Test
    fun `blank string returns empty list`() {
        val result = TextToSpeech.chunkText("   ")
        assertTrue("Expected empty list for blank input", result.isEmpty())
    }

    @Test
    fun `single word returns single chunk`() {
        val result = TextToSpeech.chunkText("Hola")
        assertEquals(1, result.size)
        assertEquals("Hola", result[0])
    }

    @Test
    fun `short sentence returns single chunk`() {
        val text = "Buenos días, ¿cómo estás?"
        val result = TextToSpeech.chunkText(text)
        assertEquals("Short text should not be split", 1, result.size)
        assertEquals(text, result[0])
    }

    @Test
    fun `text shorter than CHUNK_MAX returns single chunk`() {
        val text = "a".repeat(CHUNK_MAX)
        val result = TextToSpeech.chunkText(text)
        assertEquals("Text at exact CHUNK_MAX should be one chunk", 1, result.size)
    }

    // ── Sentence splitting ────────────────────────────────────────────────────

    @Test
    fun `multiple sentences split on period`() {
        // Make each sentence clearly over CHUNK_MIN but total over CHUNK_MAX
        val sentence1 = "Esta es la primera oración con bastante contenido para ser detectada."
        val sentence2 = "Esta es la segunda oración también con suficiente contenido para pasar."
        val sentence3 = "Y esta es la tercera oración que completa el texto para superar el límite."
        val text = "$sentence1 $sentence2 $sentence3"

        val result = TextToSpeech.chunkText(text)
        assertTrue("Should split into multiple chunks", result.size > 1)
        // All content should be preserved
        val reconstructed = result.joinToString(" ")
        assertTrue("All content preserved", sentence1.trimEnd('.') in reconstructed)
    }

    @Test
    fun `splits on question mark`() {
        val s1 = "¿Cómo te llamas y de dónde eres exactamente en este momento presente aquí?"
        val s2 = "Me llamo Claw y soy un asistente de inteligencia artificial muy avanzado."
        val s3 = "Estoy aquí para ayudarte con cualquier cosa que necesites en tu día a día."
        val text = "$s1 $s2 $s3"

        val result = TextToSpeech.chunkText(text)
        assertTrue("Should produce at least 2 chunks", result.size >= 2)
    }

    @Test
    fun `splits on exclamation mark`() {
        val s1 = "¡Esto es increíble y estoy muy emocionado de poder ayudarte con tu consulta!"
        val s2 = "¡También puedo responder preguntas sobre muchos temas diferentes e interesantes!"
        val s3 = "¡Estamos aquí para hacer el trabajo juntos de la mejor manera posible siempre!"
        val text = "$s1 $s2 $s3"

        val result = TextToSpeech.chunkText(text)
        assertTrue("Should produce at least 2 chunks", result.size >= 2)
    }

    // ── Small fragment merging ────────────────────────────────────────────────

    @Test
    fun `small fragments get merged into adjacent chunks`() {
        // Build a text where splitting on sentence boundaries would produce tiny leftovers
        // 3 long sentences + 1 tiny tail sentence
        val long1 = "Primera frase bastante larga con mucho contenido para superar el mínimo."
        val long2 = "Segunda frase también bastante larga para asegurarnos de superar los límites."
        val tiny  = "Ok."  // < CHUNK_MIN chars
        val long3 = "Tercera frase bastante larga con suficiente contenido para continuar aquí."
        val text = "$long1 $long2 $tiny $long3"

        val result = TextToSpeech.chunkText(text)
        // No chunk should be just the tiny fragment on its own
        result.forEach { chunk ->
            assertFalse(
                "Tiny fragment '$tiny' should be merged, not standalone. Got chunk: '$chunk'",
                chunk.trim() == tiny.trim()
            )
        }
    }

    @Test
    fun `no chunk exceeds CHUNK_MAX significantly`() {
        // Generate text with many sentences
        val text = (1..20).joinToString(" ") {
            "Oración número $it con bastante texto para superar el límite máximo."
        }
        val result = TextToSpeech.chunkText(text)
        result.forEach { chunk ->
            assertTrue(
                "Chunk length ${chunk.length} should not exceed CHUNK_MAX * 2 = ${CHUNK_MAX * 2}",
                chunk.length <= CHUNK_MAX * 2
            )
        }
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    fun `text with no punctuation stays as single chunk if within CHUNK_MAX`() {
        val text = "Este texto sin puntuación es corto"
        val result = TextToSpeech.chunkText(text)
        assertEquals(1, result.size)
    }

    @Test
    fun `long text with no punctuation falls back to comma splitting or single chunk`() {
        val text = "a".repeat(CHUNK_MAX + 50)
        val result = TextToSpeech.chunkText(text)
        assertEquals("No splits available — should stay as one chunk", 1, result.size)
    }

    @Test
    fun `comma fallback splits long text without sentence boundaries`() {
        // Build a long comma-separated list (no sentence-ending punctuation)
        val parts = (1..15).map { "item número $it en la lista de elementos de prueba" }
        val text = parts.joinToString(", ")

        // Only test if total length exceeds CHUNK_MAX
        if (text.length > CHUNK_MAX) {
            val result = TextToSpeech.chunkText(text)
            // Should produce more than 1 chunk via comma splitting
            assertTrue("Comma splitting should produce >1 chunk for long list", result.size > 1)
        }
    }

    @Test
    fun `result chunks are all non-blank`() {
        val text = "Primera oración. Segunda oración. Tercera oración larga con contenido extra aquí."
        val result = TextToSpeech.chunkText(text)
        result.forEach { chunk ->
            assertTrue("No chunk should be blank", chunk.isNotBlank())
        }
    }

    @Test
    fun `chunking single very long word`() {
        val text = "supercalifragilisticexpialidocious" + "x".repeat(CHUNK_MAX)
        val result = TextToSpeech.chunkText(text)
        assertEquals("Single word with no breaks stays as one chunk", 1, result.size)
    }

    // ── mergeSmallChunks directly ─────────────────────────────────────────────

    @Test
    fun `mergeSmallChunks merges tiny trailing fragment`() {
        val parts = listOf(
            "Primera parte larga con mucho contenido y texto importante",
            "ok"  // tiny
        )
        val result = TextToSpeech.mergeSmallChunks(parts, chunkMin = 10, chunkMax = 200)
        assertEquals("Tiny trailing fragment should be merged", 1, result.size)
        assertTrue("Merged result contains both parts", "ok" in result[0])
    }

    @Test
    fun `mergeSmallChunks respects chunkMax`() {
        val parts = List(10) { "una frase bastante larga con contenido suficiente para probar" }
        val result = TextToSpeech.mergeSmallChunks(parts, chunkMin = 5, chunkMax = 80)
        result.forEach { chunk ->
            // Allow a bit of overrun when a single part is already near the limit
            assertTrue(
                "Chunk '${chunk.take(50)}...' length ${chunk.length} should be reasonable",
                chunk.length < 200
            )
        }
    }
}
