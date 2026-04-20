package com.emiliotorrens.talk2claw.voice

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
import kotlin.random.Random

/**
 * Unit tests for [SpeakerVerification] — feature extraction, cosine similarity,
 * and embedding consistency. Audio capture/enrollment require Android runtime
 * and are not tested here.
 */
class SpeakerVerificationTest {

    private val sv = SpeakerVerification()

    // ── Cosine similarity ─────────────────────────────────────────────────────

    @Test
    fun `cosine similarity of identical vectors is 1`() {
        val a = floatArrayOf(1f, 2f, 3f, 4f)
        assertEquals(1.0f, sv.cosineSimilarity(a, a.copyOf()), 0.001f)
    }

    @Test
    fun `cosine similarity of orthogonal vectors is 0`() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(0f, 1f)
        assertEquals(0.0f, sv.cosineSimilarity(a, b), 0.001f)
    }

    @Test
    fun `cosine similarity of opposite vectors is -1`() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(-1f, -2f, -3f)
        assertEquals(-1.0f, sv.cosineSimilarity(a, b), 0.001f)
    }

    @Test
    fun `cosine similarity of empty vectors is 0`() {
        assertEquals(0f, sv.cosineSimilarity(floatArrayOf(), floatArrayOf()), 0.001f)
    }

    @Test
    fun `cosine similarity of mismatched sizes is 0`() {
        val a = floatArrayOf(1f, 2f)
        val b = floatArrayOf(1f, 2f, 3f)
        assertEquals(0f, sv.cosineSimilarity(a, b), 0.001f)
    }

    @Test
    fun `cosine similarity is scale invariant`() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(100f, 200f, 300f)
        assertEquals(1.0f, sv.cosineSimilarity(a, b), 0.001f)
    }

    @Test
    fun `cosine similarity with zero vector is 0`() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(0f, 0f, 0f)
        assertEquals(0f, sv.cosineSimilarity(a, b), 0.001f)
    }

    // ── Feature extraction ────────────────────────────────────────────────────

    @Test
    fun `extractEmbedding returns null for too-short audio`() {
        val audio = ShortArray(100) { 1000 }  // < FRAME_SIZE (512)
        assertNull(sv.extractEmbedding(audio))
    }

    @Test
    fun `extractEmbedding returns null for silent audio`() {
        val audio = ShortArray(16000) { 0 }  // 1 second of silence
        assertNull(sv.extractEmbedding(audio))
    }

    @Test
    fun `extractEmbedding returns correct dimension for valid audio`() {
        // Generate a synthetic "voiced" signal — sine wave at 200Hz
        val sampleRate = 16000
        val duration = 1  // 1 second
        val freq = 200.0
        val audio = ShortArray(sampleRate * duration) { i ->
            (sin(2.0 * PI * freq * i / sampleRate) * 16000).toInt().toShort()
        }
        val embedding = sv.extractEmbedding(audio)
        assertNotNull("Should extract embedding from sine wave", embedding)
        assertEquals(SpeakerVerification.EMBEDDING_DIM, embedding!!.size)
    }

    @Test
    fun `extractEmbedding has no NaN or Inf values`() {
        val audio = generateSineWave(200.0, 16000, 1)
        val embedding = sv.extractEmbedding(audio)!!
        for (i in embedding.indices) {
            assertFalse("NaN at index $i", embedding[i].isNaN())
            assertFalse("Inf at index $i", embedding[i].isInfinite())
        }
    }

    @Test
    fun `same signal produces identical embeddings`() {
        val audio = generateSineWave(300.0, 16000, 1)
        val emb1 = sv.extractEmbedding(audio)!!
        val emb2 = sv.extractEmbedding(audio.copyOf())!!
        assertEquals(1.0f, sv.cosineSimilarity(emb1, emb2), 0.001f)
    }

    @Test
    fun `different frequencies produce different embeddings`() {
        val audio200 = generateSineWave(200.0, 16000, 1)
        val audio2000 = generateSineWave(2000.0, 16000, 1)
        val emb200 = sv.extractEmbedding(audio200)!!
        val emb2000 = sv.extractEmbedding(audio2000)!!
        val similarity = sv.cosineSimilarity(emb200, emb2000)
        // Different frequencies should produce noticeably different embeddings
        assertTrue(
            "200Hz vs 2000Hz similarity ($similarity) should be < 0.95",
            similarity < 0.95f,
        )
    }

    @Test
    fun `embedding is sensitive to amplitude changes`() {
        val loud = generateSineWave(300.0, 16000, 1, amplitude = 20000.0)
        val quiet = generateSineWave(300.0, 16000, 1, amplitude = 2000.0)
        val embLoud = sv.extractEmbedding(loud)!!
        val embQuiet = sv.extractEmbedding(quiet)!!
        val similarity = sv.cosineSimilarity(embLoud, embQuiet)
        // Same frequency but different amplitude — should differ somewhat
        // (RMS component changes, but spectral shape is similar)
        assertTrue(
            "Loud vs quiet similarity ($similarity) should be < 1.0",
            similarity < 0.999f,
        )
    }

    @Test
    fun `noise produces valid embedding`() {
        val rng = Random(42)
        val audio = ShortArray(16000) { (rng.nextInt(10000) - 5000).toShort() }
        val embedding = sv.extractEmbedding(audio)
        assertNotNull("Should extract embedding from noise", embedding)
        assertEquals(SpeakerVerification.EMBEDDING_DIM, embedding!!.size)
    }

    @Test
    fun `noise vs sine have low similarity`() {
        val rng = Random(42)
        val noise = ShortArray(16000) { (rng.nextInt(10000) - 5000).toShort() }
        val sine = generateSineWave(300.0, 16000, 1)
        val embNoise = sv.extractEmbedding(noise)!!
        val embSine = sv.extractEmbedding(sine)!!
        val similarity = sv.cosineSimilarity(embNoise, embSine)
        assertTrue(
            "Noise vs sine similarity ($similarity) should be < 0.9",
            similarity < 0.9f,
        )
    }

    // ── EMBEDDING_DIM constant consistency ────────────────────────────────────

    @Test
    fun `EMBEDDING_DIM is 36`() {
        assertEquals(36, SpeakerVerification.EMBEDDING_DIM)
    }

    // ── Enrollment phrases ────────────────────────────────────────────────────

    @Test
    fun `enrollment phrases are non-empty`() {
        assertTrue(SpeakerVerification.ENROLLMENT_PHRASES.isNotEmpty())
        SpeakerVerification.ENROLLMENT_PHRASES.forEach {
            assertTrue("Phrase should not be blank", it.isNotBlank())
        }
    }

    @Test
    fun `enrollment phrases are in Spanish`() {
        // Quick heuristic: Spanish phrases typically contain common Spanish words
        val spanishMarkers = listOf("oye", "buenos", "días", "necesito", "algo", "ayuda", "cómo")
        SpeakerVerification.ENROLLMENT_PHRASES.forEach { phrase ->
            val lower = phrase.lowercase()
            assertTrue(
                "Phrase '$phrase' should contain Spanish words",
                spanishMarkers.any { lower.contains(it) },
            )
        }
    }

    // ── JSON serialization round-trip ─────────────────────────────────────────

    @Test
    fun `embedding serializes and deserializes via JSON correctly`() {
        val audio = generateSineWave(300.0, 16000, 1)
        val embedding = sv.extractEmbedding(audio)!!

        // Serialize (same as enrollment code)
        val jsonArray = JSONArray()
        for (v in embedding) jsonArray.put(v.toDouble())
        val json = jsonArray.toString()

        // Deserialize (same as verify code)
        val parsed = JSONArray(json)
        val restored = FloatArray(parsed.length()) { parsed.getDouble(it).toFloat() }

        assertEquals(embedding.size, restored.size)
        for (i in embedding.indices) {
            assertEquals(
                "Mismatch at index $i",
                embedding[i],
                restored[i],
                0.0001f,
            )
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun generateSineWave(
        freq: Double,
        sampleRate: Int,
        durationSec: Int,
        amplitude: Double = 16000.0,
    ): ShortArray {
        return ShortArray(sampleRate * durationSec) { i ->
            (sin(2.0 * PI * freq * i / sampleRate) * amplitude).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
    }
}
