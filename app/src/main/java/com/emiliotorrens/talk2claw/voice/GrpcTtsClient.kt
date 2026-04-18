package com.emiliotorrens.talk2claw.voice

import android.util.Log
import com.emiliotorrens.talk2claw.settings.AppSettings
import com.google.cloud.texttospeech.v1.AudioConfig
import com.google.cloud.texttospeech.v1.AudioEncoding
import com.google.cloud.texttospeech.v1.SynthesisInput
import com.google.cloud.texttospeech.v1.SynthesizeSpeechRequest
import com.google.cloud.texttospeech.v1.TextToSpeechGrpc
import com.google.cloud.texttospeech.v1.VoiceSelectionParams
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.okhttp.OkHttpChannelBuilder
import java.util.concurrent.TimeUnit

/**
 * gRPC client for Google Cloud Text-to-Speech.
 *
 * Uses unary SynthesizeSpeech over HTTP/2 with persistent connections.
 * Benefits over REST:
 * - Connection reuse (no TLS handshake per request after first)
 * - Binary protobuf encoding (smaller payloads, no base64)
 * - HTTP/2 multiplexing
 *
 * Auth is via API key metadata header (x-goog-api-key).
 */
class GrpcTtsClient(private val settings: AppSettings) {

    companion object {
        private const val TAG = "GrpcTTS"
        private const val TTS_HOST = "texttospeech.googleapis.com"
        private const val TTS_PORT = 443
        private const val SAMPLE_RATE = 24000
    }

    @Volatile
    private var channel: ManagedChannel? = null

    /** Returns (or creates) a persistent gRPC channel. */
    private fun getChannel(): ManagedChannel {
        channel?.let { if (!it.isShutdown) return it }
        val apiKey = settings.googleCloudApiKey
        val ch = OkHttpChannelBuilder
            .forAddress(TTS_HOST, TTS_PORT)
            .useTransportSecurity()
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(10, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(false)
            .intercept(ApiKeyInterceptor(apiKey))
            .build()
        channel = ch
        Log.d(TAG, "gRPC channel created to $TTS_HOST:$TTS_PORT")
        return ch
    }

    /**
     * Synthesize a text chunk via gRPC unary call.
     * Returns raw PCM bytes (WAV header stripped) or null on failure.
     * Blocking — call from a background thread.
     */
    fun synthesize(text: String): ByteArray? {
        if (text.isBlank()) return null
        try {
            val ch = getChannel()
            val stub = TextToSpeechGrpc.newBlockingStub(ch)
                .withDeadlineAfter(30, TimeUnit.SECONDS)

            val request = SynthesizeSpeechRequest.newBuilder()
                .setInput(SynthesisInput.newBuilder().setText(text))
                .setVoice(
                    VoiceSelectionParams.newBuilder()
                        .setLanguageCode(settings.ttsLanguageCode)
                        .setName(settings.ttsVoice)
                )
                .setAudioConfig(
                    AudioConfig.newBuilder()
                        .setAudioEncoding(AudioEncoding.LINEAR16)
                        .setSampleRateHertz(SAMPLE_RATE)
                        .setSpeakingRate(settings.speakingRate.toDouble())
                )
                .build()

            val response = stub.synthesizeSpeech(request)
            val audioBytes = response.audioContent.toByteArray()

            if (audioBytes.isEmpty()) {
                Log.w(TAG, "gRPC: empty audio content")
                return null
            }

            // Strip WAV header (44 bytes) if present
            return if (audioBytes.size > 44 &&
                audioBytes[0] == 'R'.code.toByte() &&
                audioBytes[1] == 'I'.code.toByte()
            ) {
                audioBytes.copyOfRange(44, audioBytes.size)
            } else {
                audioBytes
            }
        } catch (e: Exception) {
            Log.e(TAG, "gRPC synthesize error: ${e.message}")
            return null
        }
    }

    /** Shut down the persistent channel. Call on app destroy. */
    fun shutdown() {
        try {
            channel?.shutdown()?.awaitTermination(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "Channel shutdown error: ${e.message}")
            try { channel?.shutdownNow() } catch (_: Exception) {}
        }
        channel = null
        Log.d(TAG, "gRPC channel shut down")
    }

    /**
     * Interceptor that adds API key as x-goog-api-key metadata header.
     */
    private class ApiKeyInterceptor(private val apiKey: String) : ClientInterceptor {
        private val keyHeader = Metadata.Key.of("x-goog-api-key", Metadata.ASCII_STRING_MARSHALLER)

        override fun <ReqT, RespT> interceptCall(
            method: MethodDescriptor<ReqT, RespT>,
            callOptions: CallOptions,
            next: Channel,
        ): ClientCall<ReqT, RespT> {
            return object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                next.newCall(method, callOptions)
            ) {
                override fun start(responseListener: Listener<RespT>, headers: Metadata) {
                    headers.put(keyHeader, apiKey)
                    super.start(responseListener, headers)
                }
            }
        }
    }
}
