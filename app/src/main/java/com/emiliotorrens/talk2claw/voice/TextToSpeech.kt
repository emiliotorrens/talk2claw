package com.emiliotorrens.talk2claw.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import android.util.Log
import com.emiliotorrens.talk2claw.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Google Cloud Text-to-Speech via REST API.
 * Returns LINEAR16 PCM audio for direct playback.
 */
class TextToSpeech(private val settings: AppSettings) {

    companion object {
        private const val TAG = "TTS"
        private const val SAMPLE_RATE = 24000
        private const val TTS_URL = "https://texttospeech.googleapis.com/v1/text:synthesize"
    }

    private val _state = MutableStateFlow<TTSState>(TTSState.Idle)
    val state: StateFlow<TTSState> = _state.asStateFlow()

    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private var audioTrack: AudioTrack? = null
    @Volatile private var isPlaying = false

    /**
     * Synthesize text and play it through the speaker.
     * Blocks until playback is complete.
     */
    suspend fun speak(text: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext Result.success(Unit)
        if (settings.googleCloudApiKey.isBlank()) {
            return@withContext Result.failure(Exception("Google Cloud API key not configured"))
        }

        _state.value = TTSState.Synthesizing
        Log.d(TAG, "Synthesizing: ${text.take(80)}...")

        try {
            // Build TTS request
            val body = JSONObject().apply {
                put("input", JSONObject().put("text", text))
                put("voice", JSONObject().apply {
                    put("languageCode", settings.ttsLanguageCode)
                    put("name", settings.ttsVoice)
                })
                put("audioConfig", JSONObject().apply {
                    put("audioEncoding", "LINEAR16")
                    put("sampleRateHertz", SAMPLE_RATE)
                    put("speakingRate", 1.0)
                })
            }

            val request = Request.Builder()
                .url("$TTS_URL?key=${settings.googleCloudApiKey}")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            val code = response.code
            response.close()

            if (code !in 200..299) {
                Log.w(TAG, "TTS error: HTTP $code")
                _state.value = TTSState.Idle
                return@withContext Result.failure(Exception("TTS HTTP $code: ${responseBody.take(200)}"))
            }

            val json = JSONObject(responseBody)
            val audioBase64 = json.optString("audioContent", "")
            if (audioBase64.isEmpty()) {
                _state.value = TTSState.Idle
                return@withContext Result.failure(Exception("No audio in response"))
            }

            // Decode and play
            val audioBytes = Base64.decode(audioBase64, Base64.DEFAULT)
            // Skip WAV header (44 bytes) if present
            val pcmData = if (audioBytes.size > 44 &&
                audioBytes[0] == 'R'.code.toByte() &&
                audioBytes[1] == 'I'.code.toByte()) {
                audioBytes.copyOfRange(44, audioBytes.size)
            } else {
                audioBytes
            }

            _state.value = TTSState.Playing
            playPcm(pcmData)
            _state.value = TTSState.Idle
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "TTS error: ${e.message}")
            _state.value = TTSState.Idle
            Result.failure(e)
        }
    }

    private fun playPcm(data: ByteArray) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(data.size)
            .build()

        audioTrack = track
        isPlaying = true

        track.write(data, 0, data.size)
        track.setNotificationMarkerPosition(data.size / 2) // 2 bytes per sample
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: AudioTrack?) {
                isPlaying = false
            }
            override fun onPeriodicNotification(t: AudioTrack?) {}
        })
        track.play()

        // Wait for playback to finish
        val durationMs = (data.size.toLong() * 1000) / (SAMPLE_RATE * 2)
        Thread.sleep(durationMs + 100) // small buffer

        track.stop()
        track.release()
        audioTrack = null
        isPlaying = false
    }

    fun stop() {
        isPlaying = false
        audioTrack?.pause()
        audioTrack?.flush()
        audioTrack?.release()
        audioTrack = null
        _state.value = TTSState.Idle
    }

    sealed class TTSState {
        data object Idle : TTSState()
        data object Synthesizing : TTSState()
        data object Playing : TTSState()
    }
}
