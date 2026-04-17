package com.emiliotorrens.talk2claw.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import kotlin.math.sqrt

/**
 * On-device speaker verification framework using ONNX Runtime.
 *
 * Design: When wake word fires, captures a short (~2s) audio clip via AudioRecord,
 * extracts a speaker embedding using an ONNX model, and compares it against the
 * enrolled speaker embedding via cosine similarity.
 *
 * Current state: FRAMEWORK ONLY — the ONNX model is NOT bundled.
 * The enrollment and verification methods will work end-to-end once a speaker
 * embedding model (e.g., ECAPA-TDNN, ~20MB) is added to assets/.
 *
 * TODO: Download a speaker embedding ONNX model (e.g., SpeechBrain ECAPA-TDNN)
 *       and place in assets/speaker_verification.onnx
 * TODO: Implement actual ONNX inference in [extractEmbedding]
 */
class SpeakerVerification {

    companion object {
        private const val TAG = "SpeakerVerification"
        private const val SAMPLE_RATE = 16000
        private const val CAPTURE_DURATION_MS = 2000
        private const val EMBEDDING_DIM = 192  // ECAPA-TDNN default dimension
        private const val ENROLLMENT_PHRASES = 3
    }

    /**
     * Record a short audio clip (~2s) from the microphone.
     * Returns raw PCM 16-bit mono audio data, or null on failure.
     */
    suspend fun captureAudioClip(): ShortArray? = withContext(Dispatchers.IO) {
        try {
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val samplesNeeded = SAMPLE_RATE * CAPTURE_DURATION_MS / 1000

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(bufferSize, samplesNeeded * 2),
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
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

            Log.d(TAG, "Captured ${offset} samples (${offset * 1000 / SAMPLE_RATE}ms)")
            buffer
        } catch (e: Exception) {
            Log.e(TAG, "Audio capture failed: ${e.message}", e)
            null
        }
    }

    /**
     * Extract a speaker embedding from raw audio.
     *
     * TODO: Implement actual ONNX Runtime inference here.
     * Currently returns null (model not bundled).
     */
    private fun extractEmbedding(audio: ShortArray): FloatArray? {
        // TODO: Load ONNX model from assets and run inference
        // val session = OrtEnvironment.getEnvironment().createSession(modelBytes)
        // val inputTensor = OnnxTensor.createTensor(env, audioFloat)
        // val result = session.run(mapOf("input" to inputTensor))
        // return result[0].value as FloatArray
        Log.w(TAG, "extractEmbedding: ONNX model not bundled — returning null")
        return null
    }

    /**
     * Enroll a speaker by recording [ENROLLMENT_PHRASES] audio clips,
     * extracting embeddings, and averaging them.
     *
     * @param onProgress Called with (current phrase index, total) for UI feedback.
     * @return The averaged embedding as a JSON float array string, or null if failed.
     */
    suspend fun enroll(
        onProgress: suspend (current: Int, total: Int) -> Unit = { _, _ -> },
    ): String? {
        val embeddings = mutableListOf<FloatArray>()

        for (i in 0 until ENROLLMENT_PHRASES) {
            onProgress(i + 1, ENROLLMENT_PHRASES)
            val audio = captureAudioClip() ?: run {
                Log.e(TAG, "Enrollment failed: could not capture audio for phrase ${i + 1}")
                return null
            }
            val embedding = extractEmbedding(audio) ?: run {
                Log.e(TAG, "Enrollment failed: could not extract embedding for phrase ${i + 1}")
                return null
            }
            embeddings.add(embedding)
        }

        // Average the embeddings
        val averaged = FloatArray(EMBEDDING_DIM)
        for (emb in embeddings) {
            for (j in averaged.indices) {
                averaged[j] += emb[j]
            }
        }
        for (j in averaged.indices) {
            averaged[j] /= embeddings.size.toFloat()
        }

        // Serialize as JSON array
        val jsonArray = JSONArray()
        for (v in averaged) jsonArray.put(v.toDouble())
        return jsonArray.toString()
    }

    /**
     * Verify if the given audio clip matches the enrolled speaker.
     *
     * @param enrolledEmbeddingJson The enrolled embedding as a JSON float array string.
     * @param threshold Cosine similarity threshold (default 0.7).
     * @return True if the speaker matches, false if not or if verification fails.
     */
    suspend fun verify(
        enrolledEmbeddingJson: String,
        threshold: Float = 0.7f,
    ): Boolean {
        if (enrolledEmbeddingJson.isBlank()) {
            Log.w(TAG, "No enrolled embedding — cannot verify")
            return false
        }

        val audio = captureAudioClip() ?: return false
        val currentEmbedding = extractEmbedding(audio) ?: return false

        // Parse enrolled embedding
        val enrolledArray = try {
            val json = JSONArray(enrolledEmbeddingJson)
            FloatArray(json.length()) { json.getDouble(it).toFloat() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse enrolled embedding: ${e.message}")
            return false
        }

        val similarity = cosineSimilarity(currentEmbedding, enrolledArray)
        Log.d(TAG, "Speaker similarity: $similarity (threshold: $threshold)")
        return similarity >= threshold
    }

    /**
     * Compute cosine similarity between two vectors.
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
