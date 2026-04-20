package com.emiliotorrens.talk2claw.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import kotlin.math.*

/**
 * On-device speaker verification using spectral audio features.
 *
 * Approach: Extract a speaker "fingerprint" from audio using FFT-based
 * spectral band energies + RMS + ZCR, summarised as mean+std over frames.
 * This produces a 36-dimensional embedding that captures voice characteristics
 * well enough to distinguish the enrolled user from bystanders.
 *
 * No ONNX model required — lightweight and self-contained.
 *
 * Design note: verify() captures a fresh 2-second clip after STT fires.
 * This works best for filtering out bystanders who keep talking after
 * accidentally triggering the wake word. For active-user verification
 * accuracy, a lower threshold (0.5–0.65) is recommended.
 */
class SpeakerVerification {

    companion object {
        private const val TAG = "SpeakerVerification"
        private const val SAMPLE_RATE = 16000
        private const val CAPTURE_DURATION_MS = 2000

        // ── Feature extraction parameters ──────────────────────
        /** Frame size (power of 2 for FFT) — ~32ms at 16kHz */
        private const val FRAME_SIZE = 512
        /** Hop between frames — ~8ms */
        private const val HOP_SIZE = 128
        /** Number of log spectral energy bands */
        private const val NUM_SPECTRAL_BANDS = 16
        /** Features per frame: spectral bands + RMS + ZCR */
        private const val FEATURES_PER_FRAME = NUM_SPECTRAL_BANDS + 2
        /** Total embedding dimension: mean + std over frames */
        const val EMBEDDING_DIM = FEATURES_PER_FRAME * 2  // 36

        /** Minimum RMS energy (linear) below which audio is considered silence.
         *  If verification audio is silent, allow through (can't meaningfully verify). */
        private const val SILENCE_THRESHOLD = 0.002f

        /** Pre-emphasis filter coefficient (highlights high frequencies) */
        private const val PRE_EMPHASIS = 0.97f

        /** Delay (ms) between showing a phrase and starting to record. */
        const val PHRASE_PREP_DELAY_MS = 2000L

        /** Phrases to say during enrollment (shown in the UI). */
        val ENROLLMENT_PHRASES = listOf(
            "Oye Claw, ¿cómo estás hoy?",
            "Buenos días, cuéntame algo interesante",
            "Necesito tu ayuda con algo importante",
        )
    }

    // ── Audio capture ────────────────────────────────────────────────────────

    /**
     * Record a short audio clip from the microphone.
     * Returns raw PCM 16-bit mono audio, or null on failure.
     */
    suspend fun captureAudioClip(durationMs: Int = CAPTURE_DURATION_MS): ShortArray? =
        withContext(Dispatchers.IO) {
            try {
                val bufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                val samplesNeeded = SAMPLE_RATE * durationMs / 1000

                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(bufferSize, samplesNeeded * 2),
                )

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord failed to initialize — mic may be in use")
                    recorder.release()
                    return@withContext null
                }

                val buffer = ShortArray(samplesNeeded)
                recorder.startRecording()
                var offset = 0
                while (offset < samplesNeeded) {
                    val read = recorder.read(buffer, offset, samplesNeeded - offset)
                    if (read <= 0) break
                    offset += read
                }
                recorder.stop()
                recorder.release()

                Log.d(TAG, "Captured $offset samples (${offset * 1000 / SAMPLE_RATE}ms)")
                buffer
            } catch (e: Exception) {
                Log.e(TAG, "Audio capture failed: ${e.message}", e)
                null
            }
        }

    // ── Feature extraction ───────────────────────────────────────────────────

    /**
     * Extract a speaker embedding from raw PCM audio.
     *
     * Algorithm:
     *  1. Pre-emphasis filter
     *  2. Frame with 512-sample Hamming-windowed frames
     *  3. FFT → power spectrum → log spectral band energies (16 bands)
     *  4. RMS energy (log) + zero-crossing rate per frame
     *  5. Mean + std over all frames → 36-dimensional embedding
     *
     * Returns null if audio is too short or all-silent.
     */
    fun extractEmbedding(audio: ShortArray): FloatArray? {
        if (audio.size < FRAME_SIZE) {
            Log.w(TAG, "Audio too short for feature extraction: ${audio.size} samples")
            return null
        }

        // Convert to float, normalize to [-1, 1]
        val signal = FloatArray(audio.size) { audio[it] / 32768f }

        // Check overall energy — reject silence
        val rmsOverall = sqrt(signal.map { it * it }.average().toFloat())
        if (rmsOverall < SILENCE_THRESHOLD) {
            Log.d(TAG, "Audio is silent (rms=${"%.4f".format(rmsOverall)}) — skipping embedding")
            return null
        }

        // Pre-emphasis: x[n] = x[n] - 0.97 * x[n-1]
        for (i in signal.size - 1 downTo 1) {
            signal[i] = signal[i] - PRE_EMPHASIS * signal[i - 1]
        }

        val allFeatures = mutableListOf<FloatArray>()
        var frameStart = 0

        while (frameStart + FRAME_SIZE <= signal.size) {
            val frame = FloatArray(FRAME_SIZE) { signal[frameStart + it] }

            // Hamming window: w[n] = 0.54 - 0.46 * cos(2π·n/(N-1))
            for (i in frame.indices) {
                frame[i] *= (0.54f - 0.46f * cos(2.0 * PI * i / (FRAME_SIZE - 1))).toFloat()
            }

            // Power spectrum via in-place FFT
            val powerSpec = fftPower(frame)

            // Log spectral band energies (16 bands, linear frequency scale)
            val bandSize = powerSpec.size / NUM_SPECTRAL_BANDS
            val bandFeatures = FloatArray(NUM_SPECTRAL_BANDS) { band ->
                val bStart = band * bandSize
                val bEnd = minOf(bStart + bandSize, powerSpec.size)
                var energy = 0f
                for (k in bStart until bEnd) energy += powerSpec[k]
                ln(energy + 1e-8f)  // log energy, floor at ~-18
            }

            // Log RMS energy
            var sumSq = 0f
            for (x in frame) sumSq += x * x
            val logRms = ln(sqrt(sumSq / FRAME_SIZE) + 1e-8f)

            // Zero crossing rate: fraction of sign changes
            var zc = 0
            for (i in 1 until frame.size) {
                if ((frame[i] >= 0f) != (frame[i - 1] >= 0f)) zc++
            }
            val zcr = zc.toFloat() / FRAME_SIZE

            // Combine: [band_0..band_15, logRms, zcr]
            val frameFeatures = FloatArray(FEATURES_PER_FRAME)
            bandFeatures.copyInto(frameFeatures, 0)
            frameFeatures[NUM_SPECTRAL_BANDS] = logRms
            frameFeatures[NUM_SPECTRAL_BANDS + 1] = zcr

            allFeatures.add(frameFeatures)
            frameStart += HOP_SIZE
        }

        if (allFeatures.isEmpty()) {
            Log.w(TAG, "No frames extracted from audio")
            return null
        }

        // Compute per-feature mean and standard deviation over frames
        val means = FloatArray(FEATURES_PER_FRAME)
        val stds = FloatArray(FEATURES_PER_FRAME)

        for (frame in allFeatures) {
            for (i in 0 until FEATURES_PER_FRAME) means[i] += frame[i]
        }
        for (i in 0 until FEATURES_PER_FRAME) means[i] /= allFeatures.size

        for (frame in allFeatures) {
            for (i in 0 until FEATURES_PER_FRAME) {
                val diff = frame[i] - means[i]
                stds[i] += diff * diff
            }
        }
        for (i in 0 until FEATURES_PER_FRAME) stds[i] = sqrt(stds[i] / allFeatures.size)

        // Concatenate [mean | std] → EMBEDDING_DIM-dimensional vector
        val embedding = FloatArray(EMBEDDING_DIM)
        means.copyInto(embedding, 0)
        stds.copyInto(embedding, FEATURES_PER_FRAME)

        Log.d(
            TAG,
            "Extracted embedding: dim=${embedding.size}, frames=${allFeatures.size}, " +
                "rms=${"%.3f".format(means[NUM_SPECTRAL_BANDS])}, " +
                "zcr=${"%.3f".format(means[NUM_SPECTRAL_BANDS + 1])}",
        )
        return embedding
    }

    /**
     * Compute power spectrum of a signal using Cooley-Tukey radix-2 FFT.
     * Input length must be a power of 2.
     * Returns the first N/2 power spectral values (one-sided spectrum).
     */
    private fun fftPower(frame: FloatArray): FloatArray {
        val n = frame.size
        val real = frame.copyOf()
        val imag = FloatArray(n)

        // Bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tmpR = real[i]; real[i] = real[j]; real[j] = tmpR
                val tmpI = imag[i]; imag[i] = imag[j]; imag[j] = tmpI
            }
        }

        // Butterfly operations
        var len = 2
        while (len <= n) {
            val halfLen = len shr 1
            val angle = -2.0 * PI / len
            val wRe = cos(angle).toFloat()
            val wIm = sin(angle).toFloat()
            var k = 0
            while (k < n) {
                var curRe = 1f
                var curIm = 0f
                for (m in 0 until halfLen) {
                    val uRe = real[k + m]
                    val uIm = imag[k + m]
                    val vRe = real[k + m + halfLen] * curRe - imag[k + m + halfLen] * curIm
                    val vIm = real[k + m + halfLen] * curIm + imag[k + m + halfLen] * curRe
                    real[k + m] = uRe + vRe
                    imag[k + m] = uIm + vIm
                    real[k + m + halfLen] = uRe - vRe
                    imag[k + m + halfLen] = uIm - vIm
                    val newRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = newRe
                }
                k += len
            }
            len = len shl 1
        }

        // One-sided power spectrum
        return FloatArray(n / 2) { real[it] * real[it] + imag[it] * imag[it] }
    }

    // ── Enrollment ───────────────────────────────────────────────────────────

    /**
     * Enroll a speaker by recording [ENROLLMENT_PHRASES.size] audio clips,
     * extracting embeddings, and averaging them.
     *
     * @param onPhrase  Called before each phrase with (index 1-based, total, phrase text).
     *                  Show the phrase to the user for [PHRASE_PREP_DELAY_MS] before recording.
     * @param onRecord  Called when recording starts for each phrase (index 1-based, total).
     * @return The averaged embedding as a JSON float array string, or null if failed.
     */
    suspend fun enroll(
        onPhrase: suspend (current: Int, total: Int, phrase: String) -> Unit = { _, _, _ -> },
        onRecord: suspend (current: Int, total: Int) -> Unit = { _, _ -> },
    ): String? {
        val count = ENROLLMENT_PHRASES.size
        val embeddings = mutableListOf<FloatArray>()

        for (i in 0 until count) {
            val phrase = ENROLLMENT_PHRASES[i]
            onPhrase(i + 1, count, phrase)          // "show phrase and prepare"
            delay(PHRASE_PREP_DELAY_MS)             // 2s for user to read and prepare
            onRecord(i + 1, count)                  // "recording starts now"

            val audio = captureAudioClip() ?: run {
                Log.e(TAG, "Enrollment failed: could not capture audio for phrase ${i + 1}")
                return null
            }
            val embedding = extractEmbedding(audio) ?: run {
                Log.e(TAG, "Enrollment failed: audio too quiet or too short for phrase ${i + 1}")
                return null
            }
            embeddings.add(embedding)
            Log.d(TAG, "Enrolled phrase ${i + 1}/$count ✓")
        }

        // Average embeddings across all phrases
        val embSize = embeddings[0].size
        val averaged = FloatArray(embSize)
        for (emb in embeddings) {
            for (j in averaged.indices) averaged[j] += emb[j]
        }
        for (j in averaged.indices) averaged[j] /= embeddings.size.toFloat()

        // Serialize as JSON array
        val jsonArray = JSONArray()
        for (v in averaged) jsonArray.put(v.toDouble())
        Log.i(TAG, "Enrollment complete — embedding dim=$embSize, phrases=${embeddings.size}")
        return jsonArray.toString()
    }

    // ── Verification ─────────────────────────────────────────────────────────

    /**
     * Verify if the current microphone audio matches the enrolled speaker.
     * Captures a fresh audio clip and compares via cosine similarity.
     *
     * Returns true if the speaker matches (or if verification is inconclusive
     * due to silent audio — we don't block the user for silence).
     */
    suspend fun verify(
        enrolledEmbeddingJson: String,
        threshold: Float = 0.7f,
    ): Boolean {
        if (enrolledEmbeddingJson.isBlank()) {
            Log.w(TAG, "No enrolled embedding — cannot verify")
            return false
        }

        val audio = captureAudioClip() ?: run {
            Log.w(TAG, "verify: could not capture audio (mic busy?) — allowing through")
            return true  // Graceful fallback: don't block if we can't verify
        }

        val currentEmbedding = extractEmbedding(audio) ?: run {
            // Audio was silent — can't extract meaningful features.
            // Allow through to avoid blocking enrolled user after they pause speaking.
            Log.d(TAG, "verify: audio is silent — allowing through")
            return true
        }

        val enrolledArray = try {
            val json = JSONArray(enrolledEmbeddingJson)
            FloatArray(json.length()) { json.getDouble(it).toFloat() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse enrolled embedding: ${e.message}")
            return false
        }

        val similarity = cosineSimilarity(currentEmbedding, enrolledArray)
        val passed = similarity >= threshold
        Log.d(
            TAG,
            "Speaker similarity: ${"%.3f".format(similarity)} " +
                "(threshold: $threshold) → ${if (passed) "✅ PASS" else "❌ REJECT"}",
        )
        return passed
    }

    // ── Utility ──────────────────────────────────────────────────────────────

    /**
     * Compute cosine similarity between two vectors.
     * Returns 0 if vectors are empty or have mismatched dimensions.
     */
    internal fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 0f) dot / denom else 0f
    }
}
