package com.emiliotorrens.talk2claw.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import android.util.Log
import com.emiliotorrens.talk2claw.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Google Cloud Text-to-Speech via REST API.
 * Returns LINEAR16 PCM audio for direct playback.
 *
 * Phase 2: Supports interruption — stop() is fast and clean.
 * Phase 3: Streaming mode — chunks text into sentences and pipelines
 *          synthesis + playback so the first chunk plays in ~300ms.
 * Phase 4: gRPC transport — uses persistent HTTP/2 connection with binary
 *          protobuf encoding for lower latency (~100ms). Falls back to REST.
 *
 * Exposes currentText so the ViewModel can perform echo cancellation.
 */
class TextToSpeech(private val settings: AppSettings) {

    companion object {
        private const val TAG = "TTS"
        private const val SAMPLE_RATE = 24000
        private const val TTS_URL = "https://texttospeech.googleapis.com/v1/text:synthesize"
        /** How often (ms) to check if playback was stopped externally. */
        private const val POLL_INTERVAL_MS = 50L
        /** Minimum chunk size in chars — merge smaller chunks to avoid excess API calls. */
        internal const val CHUNK_MIN = 20
        /** Maximum chunk size in chars — split larger text here. */
        internal const val CHUNK_MAX = 200

        // ── Markdown Cleanup ────────────────────────────────────────────
        /**
         * Strip markdown formatting so TTS doesn't read "asterisco asterisco".
         * Preserves the readable text content.
         */
        internal fun stripMarkdown(text: String): String {
            var s = text
            // Code blocks (``` ... ```)
            s = s.replace(Regex("```[\\s\\S]*?```"), " ")
            // Inline code (`...`)
            s = s.replace(Regex("`([^`]+)`"), "$1")
            // Bullet points (- or * at start of line) — BEFORE italic to avoid false matches
            s = s.replace(Regex("""^[\-*+]\s+""", RegexOption.MULTILINE), "")
            // Bold+italic (***text*** or ___text___)
            s = s.replace(Regex("""\*{3}(.+?)\*{3}"""), "$1")
            s = s.replace(Regex("""_{3}(.+?)_{3}"""), "$1")
            // Bold (**text** or __text__)
            s = s.replace(Regex("""\*{2}(.+?)\*{2}"""), "$1")
            s = s.replace(Regex("""_{2}(.+?)_{2}"""), "$1")
            // Italic (*text* or _text_)
            s = s.replace(Regex("""\*(.+?)\*"""), "$1")
            s = s.replace(Regex("""\b_(.+?)_\b"""), "$1")
            // Headers (# ## ### etc)
            s = s.replace(Regex("""^#{1,6}\s+""", RegexOption.MULTILINE), "")
            // Links [text](url) → text
            s = s.replace(Regex("""\[([^\]]+)\]\([^)]+\)"""), "$1")
            // Images ![alt](url) → remove
            s = s.replace(Regex("""!\[[^\]]*\]\([^)]+\)"""), "")
            // Numbered lists (1. 2. etc)
            s = s.replace(Regex("""^\d+\.\s+""", RegexOption.MULTILINE), "")
            // Horizontal rules (--- or ***)
            s = s.replace(Regex("""^[\-*_]{3,}$""", RegexOption.MULTILINE), "")
            // Blockquotes (> )
            s = s.replace(Regex("""^>\s?""", RegexOption.MULTILINE), "")
            // Strikethrough (~~text~~)
            s = s.replace(Regex("""~~(.+?)~~"""), "$1")
            // Final cleanup: any remaining lone asterisks or underscores used as formatting
            s = s.replace(Regex("""(?<=\s)\*(?=\S)"""), "")  // * at word start
            s = s.replace(Regex("""(?<=\S)\*(?=\s|$)"""), "")  // * at word end
            // Clean up extra whitespace
            s = s.replace(Regex("""\n{3,}"""), "\n\n")
            s = s.trim()
            return s
        }

        // ── Text Chunking (companion) ────────────────────────────────────────
        /**
         * Split text into sentence-sized chunks for streaming TTS.
         *
         * Strategy:
         * 1. Split on sentence boundaries: `.`, `?`, `!`, `;`, `\n` followed by whitespace.
         * 2. Merge small fragments (< CHUNK_MIN) into the next chunk.
         * 3. If a chunk would exceed CHUNK_MAX, flush the current accumulator first.
         * 4. Fallback: if no sentence boundaries found in long text, split on `, `.
         */
        internal fun chunkText(text: String, chunkMax: Int = CHUNK_MAX): List<String> {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return emptyList()
            if (trimmed.length <= chunkMax) return listOf(trimmed)

            // Split after sentence-ending punctuation followed by whitespace
            val splitRegex = Regex("(?<=[.!?;])\\s+|(?<=\\n)\\s*")
            val parts = trimmed.split(splitRegex)
                .map { it.trim() }
                .filter { it.isNotBlank() }

            return when {
                parts.size > 1 -> mergeSmallChunks(parts, CHUNK_MIN, chunkMax)
                else -> {
                    // No sentence boundaries — try comma splitting
                    val commaParts = trimmed.split(Regex(",\\s+"))
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                    if (commaParts.size > 1) mergeSmallChunks(commaParts, CHUNK_MIN, chunkMax)
                    else listOf(trimmed)
                }
            }
        }

        internal fun mergeSmallChunks(
            parts: List<String>,
            chunkMin: Int = CHUNK_MIN,
            chunkMax: Int = CHUNK_MAX,
        ): List<String> {
            val result = mutableListOf<String>()
            val current = StringBuilder()

            for (part in parts) {
                when {
                    current.isEmpty() -> current.append(part)
                    current.length + 1 + part.length > chunkMax -> {
                        result.add(current.toString())
                        current.clear()
                        current.append(part)
                    }
                    else -> current.append(" ").append(part)
                }
            }

            if (current.isNotEmpty()) {
                if (result.isNotEmpty() && current.length < chunkMin) {
                    result[result.size - 1] = "${result.last()} $current"
                } else {
                    result.add(current.toString())
                }
            }

            return result.filter { it.isNotBlank() }
        }
    }

    private val _state = MutableStateFlow<TTSState>(TTSState.Idle)
    val state: StateFlow<TTSState> = _state.asStateFlow()

    /**
     * Text currently being synthesized or played.
     * Cleared when playback finishes or stop() is called.
     * Used by the ViewModel for echo cancellation heuristics.
     */
    private val _currentText = MutableStateFlow("")
    val currentText: StateFlow<String> = _currentText.asStateFlow()

    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    /** gRPC client for TTS — persistent HTTP/2 connection, binary protobuf. */
    private val grpcClient = GrpcTtsClient(settings)

    /** Whether gRPC has failed and we should fall back to REST for remaining chunks. */
    @Volatile private var grpcFailed = false

    private var audioTrack: AudioTrack? = null

    /** True while audio is actively playing. Set false to interrupt. */
    @Volatile private var isPlaying = false

    /** Set true when stop() is called, to abort synthesis early. */
    @Volatile private var stopRequested = false

    /** True when speakStreaming() is active (vs. regular speak()). */
    @Volatile private var streamingMode = false

    /** Total chunks for the current streaming TTS session. */
    @Volatile private var streamingChunksTotal = 0

    /** Number of chunks whose PCM has been fully written to AudioTrack. */
    @Volatile private var streamingChunksPlayed = 0

    /** Playback start timestamp (ms) — for estimating how much was heard. */
    @Volatile private var playbackStartTimeMs = 0L

    /** Total playback duration (ms) — for estimating how much was heard. */
    @Volatile private var totalDurationMs = 0L

    /**
     * Returns the fraction [0.0, 1.0] of the current TTS that has been played.
     * Useful for estimating how much of the response the user actually heard.
     *
     * In streaming mode uses chunk progress; otherwise uses elapsed time.
     */
    fun getPlayedFraction(): Float {
        if (streamingMode) {
            val total = streamingChunksTotal
            if (total <= 0 || !isPlaying) return 0f
            return (streamingChunksPlayed.toFloat() / total).coerceIn(0f, 1f)
        }
        val dur = totalDurationMs
        if (dur <= 0L || !isPlaying) return 0f
        val elapsed = System.currentTimeMillis() - playbackStartTimeMs
        return (elapsed.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
    }

    // ── Text Chunking (delegates to companion) ──────────────────────────────
    // Instance method kept for call-site compatibility; logic lives in companion.

    // ── Synthesis (gRPC with REST fallback) ───────────────────────────────

    /**
     * Synthesize a single text chunk, preferring gRPC over REST.
     * gRPC benefits: persistent HTTP/2 connection, binary protobuf, no base64.
     * Falls back to REST if gRPC fails (and stays on REST for the session).
     * Returns decoded PCM bytes (WAV header stripped) or null on failure.
     * Blocking — must run on a background thread.
     */
    private fun synthesizeChunkWithFallback(chunk: String): ByteArray? {
        if (chunk.isBlank()) return null

        // Try gRPC first (unless it previously failed)
        if (!grpcFailed) {
            try {
                val pcm = grpcClient.synthesize(chunk)
                if (pcm != null) return pcm
                Log.w(TAG, "gRPC returned null, falling back to REST")
            } catch (e: Exception) {
                Log.w(TAG, "gRPC failed: ${e.message}, falling back to REST")
            }
            grpcFailed = true
        }

        // REST fallback
        return synthesizeChunkRest(chunk)
    }

    // ── REST Synthesis (fallback) ─────────────────────────────────────────

    /**
     * Synthesize a single text chunk via Google Cloud TTS.
     * REST fallback for synthesis.
     * Returns decoded PCM bytes (WAV header stripped) or null on failure.
     * This is a blocking network call — must run on a background thread.
     */
    private fun synthesizeChunkRest(chunk: String): ByteArray? {
        if (chunk.isBlank()) return null
        try {
            val body = JSONObject().apply {
                put("input", JSONObject().put("text", chunk))
                put("voice", JSONObject().apply {
                    put("languageCode", settings.ttsLanguageCode)
                    put("name", settings.ttsVoice)
                })
                put("audioConfig", JSONObject().apply {
                    put("audioEncoding", "LINEAR16")
                    put("sampleRateHertz", SAMPLE_RATE)
                    put("speakingRate", settings.speakingRate.toDouble())
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
                Log.w(TAG, "TTS chunk error: HTTP $code")
                return null
            }

            val json = JSONObject(responseBody)
            val audioBase64 = json.optString("audioContent", "")
            if (audioBase64.isEmpty()) {
                Log.w(TAG, "TTS chunk: empty audioContent")
                return null
            }

            val audioBytes = Base64.decode(audioBase64, Base64.DEFAULT)
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
            Log.e(TAG, "synthesizeChunk error: ${e.message}")
            return null
        }
    }

    // ── speak() — original non-streaming path ────────────────────────────────

    /**
     * Synthesize text and play it through the speaker (non-streaming).
     * Suspends until playback is complete or stop() is called.
     * Used as fallback for very short responses (< 1 sentence).
     */
    suspend fun speak(text: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext Result.success(Unit)
        val cleanText = stripMarkdown(text)
        if (cleanText.isBlank()) return@withContext Result.success(Unit)
        if (settings.googleCloudApiKey.isBlank()) {
            return@withContext Result.failure(Exception("Google Cloud API key not configured"))
        }

        stopRequested = false
        isPlaying = true          // mark as playing BEFORE synthesis so stop() works immediately
        streamingMode = false
        _currentText.value = cleanText
        _state.value = TTSState.Synthesizing
        Log.d(TAG, "Synthesizing: ${text.take(80)}...")

        try {
            val pcmData = synthesizeChunkWithFallback(cleanText)

            // Check if interrupted during synthesis
            if (stopRequested || !isPlaying) {
                _currentText.value = ""
                _state.value = TTSState.Idle
                return@withContext Result.success(Unit)
            }

            if (pcmData == null) {
                _currentText.value = ""
                _state.value = TTSState.Idle
                isPlaying = false
                return@withContext Result.failure(Exception("TTS synthesis failed"))
            }

            _state.value = TTSState.Playing
            playPcm(pcmData)
            _currentText.value = ""
            _state.value = TTSState.Idle
            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "TTS error: ${e.message}")
            _currentText.value = ""
            _state.value = TTSState.Idle
            isPlaying = false
            Result.failure(e)
        }
    }

    private fun playPcm(data: ByteArray) {
        // Double-check we haven't been stopped between synthesis and playback
        if (!isPlaying || stopRequested) return

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

        try {
            track.write(data, 0, data.size)
            track.play()

            totalDurationMs = (data.size.toLong() * 1000) / (SAMPLE_RATE * 2)
            playbackStartTimeMs = System.currentTimeMillis()

            Log.d(TAG, "Playing PCM: ${totalDurationMs}ms, ${data.size} bytes")

            // Interruptible wait loop: checks isPlaying every POLL_INTERVAL_MS
            while (isPlaying) {
                val elapsed = System.currentTimeMillis() - playbackStartTimeMs
                if (elapsed >= totalDurationMs + 300) break
                Thread.sleep(POLL_INTERVAL_MS)
            }

            if (isPlaying) {
                // Normal completion — clean up ourselves
                isPlaying = false
                audioTrack = null
                try { track.stop() } catch (_: Exception) {}
                try { track.release() } catch (_: Exception) {}
            }
            // If !isPlaying: stop() was called externally and already released the track

        } catch (e: Exception) {
            Log.w(TAG, "playPcm error: ${e.message}")
            isPlaying = false
            audioTrack = null
            try { track.release() } catch (_: Exception) {}
        }
    }

    // ── speakStreaming() — Phase 3 pipeline ──────────────────────────────────

    /**
     * Streaming TTS: chunk text into sentences, pipeline synthesis + playback.
     *
     * The first chunk starts playing as soon as it is synthesized (~300ms for
     * typical sentence lengths), while the second chunk is being synthesized
     * in parallel. This eliminates the "download everything first" latency.
     *
     * Falls back to [speak] for single-sentence responses or on error.
     *
     * Interruption (Phase 2) continues to work: [stop] sets stopRequested and
     * releases the AudioTrack, causing write() to return an error and the
     * pipeline to unwind.
     */
    suspend fun speakStreaming(text: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext Result.success(Unit)
        val cleanText = stripMarkdown(text)
        if (cleanText.isBlank()) return@withContext Result.success(Unit)
        if (settings.googleCloudApiKey.isBlank()) {
            return@withContext Result.failure(Exception("Google Cloud API key not configured"))
        }

        val chunks = chunkText(cleanText)
        Log.d(TAG, "Streaming TTS: ${chunks.size} chunks for ${cleanText.length} chars")

        // Single chunk — delegate to regular speak() (no pipeline benefit)
        if (chunks.size <= 1) {
            return@withContext speak(cleanText)
        }

        stopRequested = false
        isPlaying = true
        streamingMode = true
        streamingChunksTotal = chunks.size
        streamingChunksPlayed = 0
        totalDurationMs = 0L
        grpcFailed = false  // Reset gRPC state for new session
        _currentText.value = text
        _state.value = TTSState.Synthesizing

        // Streaming AudioTrack: buffer ~2s of audio up-front so write() rarely blocks
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf * 8, 131072) // at least 128 KB ≈ 2.7s @ 24kHz mono 16-bit

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
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufSize)
            .build()

        audioTrack = track

        // Bounded channel: synthesizer can stay at most 2 chunks ahead of playback
        val pcmChannel = Channel<ByteArray>(capacity = 2)
        var synthError: Exception? = null

        // ── Synthesizer coroutine (child of this withContext scope) ──────────
        // Runs on IO thread pool in parallel with the playback loop below.
        // Closes the channel when done so the consumer loop terminates naturally.
        launch {
            try {
                for ((idx, chunk) in chunks.withIndex()) {
                    if (stopRequested) break
                    Log.d(TAG, "Synthesizing chunk ${idx + 1}/${chunks.size}: ${chunk.take(60)}")
                    val pcm = synthesizeChunkWithFallback(chunk)
                    if (pcm == null || stopRequested) break
                    pcmChannel.send(pcm)  // suspends if consumer is behind (backpressure)
                }
            } catch (e: CancellationException) {
                // Channel cancelled by consumer (e.g. stop() was called) — normal
            } catch (e: Exception) {
                Log.e(TAG, "Synthesis pipeline error: ${e.message}")
                synthError = e
            } finally {
                pcmChannel.close()
            }
        }

        // ── Playback loop (main body of withContext) ─────────────────────────
        var trackStarted = false
        var totalBytesWritten = 0L

        try {
            for (pcm in pcmChannel) {
                if (stopRequested) {
                    pcmChannel.cancel()  // also cancels the producer coroutine
                    break
                }

                // Start AudioTrack on first chunk — this is where latency is saved
                if (!trackStarted) {
                    track.play()
                    trackStarted = true
                    playbackStartTimeMs = System.currentTimeMillis()
                    _state.value = TTSState.Playing
                    Log.d(TAG, "Streaming playback started (first chunk ready)")
                }

                // Write PCM to the streaming AudioTrack incrementally
                var offset = 0
                while (offset < pcm.size && !stopRequested) {
                    val written = track.write(pcm, offset, pcm.size - offset)
                    if (written <= 0) break   // error (e.g. track released by stop())
                    offset += written
                }

                totalBytesWritten += pcm.size
                // Update running duration estimate for getPlayedFraction()
                totalDurationMs = (totalBytesWritten * 1000L) / (SAMPLE_RATE * 2)
                streamingChunksPlayed++
                Log.d(TAG, "Chunk written: $streamingChunksPlayed/${streamingChunksTotal}")
            }
        } catch (e: Exception) {
            if (e !is CancellationException) {
                Log.w(TAG, "Playback loop error: ${e.message}")
            }
        }

        // Wait for AudioTrack internal buffer to drain completely (normal completion only)
        if (trackStarted && !stopRequested && isPlaying) {
            while (isPlaying && !stopRequested) {
                val elapsed = System.currentTimeMillis() - playbackStartTimeMs
                if (elapsed >= totalDurationMs + 300) break
                Thread.sleep(POLL_INTERVAL_MS)
            }
        }

        // Normal completion: release AudioTrack ourselves
        if (isPlaying && !stopRequested) {
            isPlaying = false
            val t = audioTrack
            audioTrack = null
            try { t?.stop() } catch (_: Exception) {}
            try { t?.release() } catch (_: Exception) {}
        }
        // Abnormal: stop() was called — it already released the track

        _currentText.value = ""
        _state.value = TTSState.Idle
        streamingMode = false

        val err = synthError
        if (err != null) {
            Log.w(TAG, "Streaming had synthesis error — result: failure")
            Result.failure(err)
        } else {
            Result.success(Unit)
        }
    }

    // ── speakFlow() — streaming TTS from a sentence Flow ──────────────────────────────────

    /**
     * Streaming TTS from a [Flow] of sentences.
     *
     * Synthesizes each sentence as it arrives from the flow and plays it immediately,
     * while the next sentence is being synthesized in parallel.  Used by MainViewModel
     * to start speaking the first sentence before the gateway has finished responding.
     *
     * The pipeline mirrors [speakStreaming] but accepts a live [Flow] of sentences
     * instead of a pre-computed list.  Interruption via [stop] works the same way.
     */
    suspend fun speakFlow(sentences: Flow<String>): Result<Unit> = withContext(Dispatchers.IO) {
        if (settings.googleCloudApiKey.isBlank()) {
            return@withContext Result.failure(Exception("Google Cloud API key not configured"))
        }

        stopRequested = false
        isPlaying = true
        streamingMode = true
        streamingChunksTotal = 0      // incremented as sentences arrive
        streamingChunksPlayed = 0
        totalDurationMs = 0L
        grpcFailed = false
        _currentText.value = ""
        _state.value = TTSState.Synthesizing

        // Bounded channel: synthesizer stays at most 2 chunks ahead of playback
        val pcmChannel = Channel<ByteArray>(capacity = 2)
        var synthError: Exception? = null
        val accumulatedText = StringBuilder()

        // ── Synthesizer coroutine ────────────────────────────────────────────
        launch {
            try {
                sentences.collect { sentence ->
                    if (stopRequested) return@collect
                    val clean = stripMarkdown(sentence).trim()
                    if (clean.isBlank()) return@collect

                    streamingChunksTotal++
                    accumulatedText.append(sentence).append(" ")
                    _currentText.value = accumulatedText.toString()

                    Log.d(TAG, "speakFlow: synthesizing '${clean.take(60)}'")
                    val pcm = synthesizeChunkWithFallback(clean)
                    if (pcm != null && !stopRequested) {
                        pcmChannel.send(pcm)  // back-pressure: suspends if playback is behind
                    }
                }
            } catch (e: CancellationException) {
                // Normal — flow cancelled (e.g. stop() called)
            } catch (e: Exception) {
                Log.e(TAG, "speakFlow synthesis error: ${e.message}")
                synthError = e
            } finally {
                pcmChannel.close()
            }
        }

        // ── Streaming AudioTrack ─────────────────────────────────────────────
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf * 8, 131072)

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
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufSize)
            .build()

        audioTrack = track

        // ── Playback loop ────────────────────────────────────────────────────
        var trackStarted = false
        var totalBytesWritten = 0L

        try {
            for (pcm in pcmChannel) {
                if (stopRequested) {
                    pcmChannel.cancel()
                    break
                }
                if (!trackStarted) {
                    track.play()
                    trackStarted = true
                    playbackStartTimeMs = System.currentTimeMillis()
                    _state.value = TTSState.Playing
                    Log.d(TAG, "speakFlow: playback started (first sentence synthesized)")
                }
                var offset = 0
                while (offset < pcm.size && !stopRequested) {
                    val written = track.write(pcm, offset, pcm.size - offset)
                    if (written <= 0) break
                    offset += written
                }
                totalBytesWritten += pcm.size
                totalDurationMs = (totalBytesWritten * 1000L) / (SAMPLE_RATE * 2)
                streamingChunksPlayed++
            }
        } catch (e: Exception) {
            if (e !is CancellationException) Log.w(TAG, "speakFlow playback error: ${e.message}")
        }

        // Drain remaining audio from AudioTrack buffer (normal completion only)
        if (trackStarted && !stopRequested && isPlaying) {
            while (isPlaying && !stopRequested) {
                val elapsed = System.currentTimeMillis() - playbackStartTimeMs
                if (elapsed >= totalDurationMs + 300) break
                Thread.sleep(POLL_INTERVAL_MS)
            }
        }

        // Normal completion: release AudioTrack ourselves
        if (isPlaying && !stopRequested) {
            isPlaying = false
            val t = audioTrack
            audioTrack = null
            try { t?.stop() } catch (_: Exception) {}
            try { t?.release() } catch (_: Exception) {}
        }

        _currentText.value = ""
        _state.value = TTSState.Idle
        streamingMode = false

        val err = synthError
        if (err != null) Result.failure(err) else Result.success(Unit)
    }

    // ── stop() ───────────────────────────────────────────────────────────────

    /**
     * Stop playback immediately. Safe to call from any thread.
     * Works for both speak() and speakStreaming():
     * - Sets stopRequested / isPlaying flags
     * - Releases AudioTrack (causes pending write() to return error)
     * - The pipeline unwinds within ~50ms
     */
    fun stop() {
        stopRequested = true
        isPlaying = false
        streamingMode = false
        val track = audioTrack
        audioTrack = null
        _currentText.value = ""
        _state.value = TTSState.Idle

        // Release the AudioTrack — wrapped defensively since state may be partial
        try { track?.pause() } catch (_: Exception) {}
        try { track?.flush() } catch (_: Exception) {}
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}

        Log.d(TAG, "TTS stopped")
    }

    /**
     * Shut down the gRPC channel. Call when the app is being destroyed.
     */
    fun shutdown() {
        stop()
        grpcClient.shutdown()
        Log.d(TAG, "TTS shutdown complete")
    }

    sealed class TTSState {
        data object Idle : TTSState()
        data object Synthesizing : TTSState()
        data object Playing : TTSState()
    }
}
