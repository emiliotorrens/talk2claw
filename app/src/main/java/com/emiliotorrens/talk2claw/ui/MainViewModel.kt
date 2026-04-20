package com.emiliotorrens.talk2claw.ui

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.media.AudioManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.emiliotorrens.talk2claw.Talk2ClawApp
import com.emiliotorrens.talk2claw.data.TranscriptEntity
import com.emiliotorrens.talk2claw.openclaw.GatewayNode
import com.emiliotorrens.talk2claw.openclaw.OpenClawBridge
import com.emiliotorrens.talk2claw.settings.AppSettings
import com.emiliotorrens.talk2claw.settings.SettingsManager
import com.emiliotorrens.talk2claw.voice.SpeakerVerification
import com.emiliotorrens.talk2claw.voice.SpeechToText
import com.emiliotorrens.talk2claw.voice.TextToSpeech
import com.emiliotorrens.talk2claw.voice.VoicePreset
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch


class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "MainVM"

        /**
         * Minimum word-overlap ratio (STT words that appear in TTS text)
         * above which we treat the STT result as microphone echo and discard it.
         */
        internal const val ECHO_THRESHOLD = 0.65f

        /** Lower echo threshold when using speaker (no headphones). */
        internal const val ECHO_THRESHOLD_SPEAKER = 0.50f

        /**
         * Returns true if the STT result is likely microphone echo of the TTS output.
         * Uses exact word overlap + fuzzy matching (Levenshtein) + partial substring check.
         */
        internal fun isEcho(
            sttText: String,
            ttsText: String,
            threshold: Float = ECHO_THRESHOLD,
        ): Boolean {
            if (ttsText.isBlank() || sttText.isBlank()) return false

            val sttWords = sttText.lowercase()
                .split(Regex("\\W+"))
                .filter { it.length > 1 }

            if (sttWords.isEmpty()) return false

            val ttsWords = ttsText.lowercase()
                .split(Regex("\\W+"))
                .filter { it.length > 1 }
                .toSet()

            // Exact match count
            var matchCount = sttWords.count { it in ttsWords }

            // Fuzzy match: check unmatched STT words against TTS words with Levenshtein
            val unmatchedStt = sttWords.filter { it !in ttsWords }
            for (sttWord in unmatchedStt) {
                for (ttsWord in ttsWords) {
                    if (levenshteinDistance(sttWord, ttsWord) <= 1) {
                        matchCount++
                        break
                    }
                }
            }

            val overlap = matchCount.toFloat() / sttWords.size.toFloat()
            if (overlap > threshold) return true

            // Partial substring check: if STT text is largely contained in TTS text
            val sttLower = sttText.lowercase().trim()
            val ttsLower = ttsText.lowercase()
            if (sttLower.length > 5 && ttsLower.contains(sttLower)) return true

            return false
        }

        /**
         * Levenshtein edit distance between two strings.
         */
        internal fun levenshteinDistance(a: String, b: String): Int {
            val m = a.length
            val n = b.length
            if (m == 0) return n
            if (n == 0) return m

            var prev = IntArray(n + 1) { it }
            var curr = IntArray(n + 1)

            for (i in 1..m) {
                curr[0] = i
                for (j in 1..n) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
                }
                val tmp = prev; prev = curr; curr = tmp
            }
            return prev[n]
        }
    }

    // ── Components ──────────────────────────────────────────────

    val stt = SpeechToText(app.applicationContext)
    private var tts: TextToSpeech
    private val speakerVerification = SpeakerVerification()
    private val audioManager = app.getSystemService(AudioManager::class.java)

    /** Primary connection: WebSocket node to the gateway. */
    private val gatewayNode: GatewayNode
        get() = getApplication<Talk2ClawApp>().gatewayNode

    /** Fallback bridge (HTTP REST) — used when WebSocket is unavailable. */
    private var bridge: OpenClawBridge

    /** Room database for persistent history. */
    private val db get() = getApplication<Talk2ClawApp>().database

    // ── UI state ────────────────────────────────────────────────

    private val _settings = MutableStateFlow(SettingsManager.load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _conversationActive = MutableStateFlow(false)
    val conversationActive: StateFlow<Boolean> = _conversationActive.asStateFlow()

    /** When true, STT stops after one result instead of looping continuously. */
    private val _singleShotMode = MutableStateFlow(false)
    val singleShotMode: StateFlow<Boolean> = _singleShotMode.asStateFlow()

    private val _pipelineState = MutableStateFlow<PipelineState>(PipelineState.Idle)
    val pipelineState: StateFlow<PipelineState> = _pipelineState.asStateFlow()

    private val _transcript = MutableStateFlow<List<TranscriptEntry>>(emptyList())
    val transcript: StateFlow<List<TranscriptEntry>> = _transcript.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    /** One-shot snackbar messages (null = nothing pending). */
    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    /**
     * Set to true when the user interrupts TTS playback.
     * Prevents the original TTS-completion callback from restarting the pipeline.
     */
    @Volatile private var wasInterrupted = false

    /** Saved media volume level for restoration after TTS. */
    private var savedMediaVolume: Int = -1

    // ── Initialisation ──────────────────────────────────────────

    init {
        val s = _settings.value
        tts = TextToSpeech(s)
        bridge = OpenClawBridge(s)

        // Wire STT callbacks
        stt.onResult = { text -> onSpeechResult(text) }
        stt.onError = { err ->
            Log.w(TAG, "STT error: $err")
            _statusMessage.value = "STT: $err"
        }

        // Connect via WebSocket on startup
        viewModelScope.launch {
            if (gatewayNode.isConfigured) {
                gatewayNode.connect()
            } else {
                bridge.checkConnection()
            }
        }

        // Send model + reasoning commands whenever WebSocket connects (or reconnects)
        viewModelScope.launch {
            var wasConnected = false
            gatewayNode.connectionState.collect { state ->
                val nowConnected = state is GatewayNode.ConnectionState.Connected
                if (nowConnected && !wasConnected) {
                    applyModelAndReasoning()
                }
                wasConnected = nowConnected
            }
        }

        // Listen for wake word events from GatewayService
        viewModelScope.launch {
            getApplication<Talk2ClawApp>().wakeWordEvents.collect {
                Log.i(TAG, "Wake word event received — starting conversation")
                if (!_conversationActive.value) {
                    startConversation()
                }
            }
        }

        // Load persistent history from Room
        viewModelScope.launch {
            try {
                val entities = db.transcriptDao().getRecent(50)
                // getRecent returns DESC order, reverse for display
                val entries = entities.reversed().map { entity ->
                    when (entity.role) {
                        "user" -> TranscriptEntry.User(entity.text)
                        "claw" -> TranscriptEntry.Claw(entity.text)
                        else -> TranscriptEntry.Error(entity.text)
                    }
                }
                if (entries.isNotEmpty()) {
                    _transcript.value = entries
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load transcript from Room: ${e.message}")
            }
        }
    }

    // ── Headphone detection ─────────────────────────────────────

    /**
     * Returns true if headphones (wired or Bluetooth) are connected.
     */
    private fun isHeadphonesConnected(): Boolean {
        @Suppress("DEPRECATION")
        val wired = audioManager?.isWiredHeadsetOn == true
        val bluetooth = try {
            @Suppress("DEPRECATION")
            val adapter = BluetoothAdapter.getDefaultAdapter()
            adapter?.isEnabled == true && audioManager?.isBluetoothA2dpOn == true
        } catch (_: Exception) {
            false
        }
        return wired || bluetooth
    }

    /**
     * Returns whether voice interruption is allowed based on settings and hardware.
     */
    private fun isInterruptionAllowed(): Boolean {
        val mode = _settings.value.interruptionMode
        return when (mode) {
            "always" -> true
            "never" -> false
            else -> isHeadphonesConnected() // "auto"
        }
    }

    /**
     * Get the effective echo threshold — lower when using speaker (no headphones).
     */
    private fun getEchoThreshold(): Float {
        return if (isHeadphonesConnected()) ECHO_THRESHOLD else ECHO_THRESHOLD_SPEAKER
    }

    // ── Audio routing for echo reduction ─────────────────────────

    /**
     * Set communication audio mode (routes through earpiece, reducing echo).
     * Only applies when no headphones are connected.
     */
    private fun setEchoCancellationMode(enabled: Boolean) {
        if (isHeadphonesConnected()) return

        if (enabled) {
            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            // Lower media volume by 40%
            val maxVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
            val currentVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: maxVol
            savedMediaVolume = currentVol
            val loweredVol = (currentVol * 0.6f).toInt().coerceAtLeast(1)
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, loweredVol, 0)
        } else {
            audioManager?.mode = AudioManager.MODE_NORMAL
            if (savedMediaVolume >= 0) {
                audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, savedMediaVolume, 0)
                savedMediaVolume = -1
            }
        }
    }

    /**
     * Synthesize and play a short sample using the given voice preset.
     */
    fun previewVoice(preset: VoicePreset) {
        val previewSettings = _settings.value.copy(
            ttsVoice = preset.voiceName,
            ttsLanguageCode = preset.languageCode,
        )
        val previewTts = TextToSpeech(previewSettings)
        viewModelScope.launch {
            previewTts.speak("Hola, soy Claw, tu asistente de voz")
        }
    }

    fun updateSettings(newSettings: AppSettings) {
        SettingsManager.save(newSettings)
        _settings.value = newSettings
        tts = TextToSpeech(newSettings)
        bridge = OpenClawBridge(newSettings)
        getApplication<Talk2ClawApp>().applyNewSettings(newSettings)
    }

    /**
     * Send /model <alias> silently if connected AND persist the setting immediately.
     * This ensures that reconnects via [applyModelAndReasoning] use the new model.
     */
    fun sendModelCommand(alias: String) {
        // Persist immediately so reconnects don't revert
        val updated = _settings.value.copy(modelAlias = alias)
        SettingsManager.save(updated)
        _settings.value = updated

        viewModelScope.launch {
            if (gatewayNode.isConnected) {
                val ok = gatewayNode.sendSilentCommand("/model $alias")
                if (ok) {
                    val label = when (alias) {
                        "flash" -> "Flash (rápido)"
                        "sonnet" -> "Sonnet (equilibrado)"
                        "opus" -> "Opus (potente)"
                        else -> alias
                    }
                    _snackbarMessage.value = "Modelo: $label"
                }
            }
        }
    }

    /**
     * Send /reasoning on|off silently if connected AND persist the setting immediately.
     */
    fun sendThinkingCommand(enabled: Boolean) {
        // Persist immediately so reconnects don't revert
        val updated = _settings.value.copy(thinkingEnabled = enabled)
        SettingsManager.save(updated)
        _settings.value = updated

        viewModelScope.launch {
            if (gatewayNode.isConnected) {
                val cmd = if (enabled) "/reasoning on" else "/reasoning off"
                val ok = gatewayNode.sendSilentCommand(cmd)
                if (ok) {
                    _snackbarMessage.value = if (enabled) "Thinking: activado" else "Thinking: desactivado"
                }
            }
        }
    }

    fun consumeSnackbar() { _snackbarMessage.value = null }

    private fun applyModelAndReasoning() {
        viewModelScope.launch {
            val s = _settings.value
            gatewayNode.sendSilentCommand("/model ${s.modelAlias}")
            gatewayNode.sendSilentCommand(if (s.thinkingEnabled) "/reasoning on" else "/reasoning off")
        }
    }

    // ── Connection ──────────────────────────────────────────────

    val connectionState: StateFlow<GatewayNode.ConnectionState>
        get() = gatewayNode.connectionState

    fun reconnect() {
        viewModelScope.launch {
            if (gatewayNode.isConfigured) {
                gatewayNode.disconnect()
                gatewayNode.connect()
            } else {
                bridge.checkConnection()
            }
        }
    }

    // ── Conversation lifecycle ──────────────────────────────────

    fun toggleConversation() {
        if (_conversationActive.value) stopConversation() else startConversation()
    }

    fun toggleSingleShotMode() {
        _singleShotMode.value = !_singleShotMode.value
        if (_conversationActive.value) {
            stt.continuousMode = !_singleShotMode.value
        }
    }

    private fun startConversation() {
        Log.d(TAG, "Starting conversation (singleShot=${_singleShotMode.value})")
        _conversationActive.value = true
        wasInterrupted = false
        _statusMessage.value = ""
        stt.continuousMode = !_singleShotMode.value
        _pipelineState.value = PipelineState.Listening
        stt.startListening(_settings.value.ttsLanguageCode)
    }

    fun stopConversation() {
        Log.d(TAG, "Stopping conversation")
        _conversationActive.value = false
        wasInterrupted = false
        stt.cancel()
        tts.stop()
        setEchoCancellationMode(false)
        _pipelineState.value = PipelineState.Idle
    }

    // ── Pipeline: STT → Claw → TTS → listen ───────────────────

    private fun onSpeechResult(text: String) {
        if (!_conversationActive.value) return

        val currentState = _pipelineState.value

        // ── Interruption path: user speaks while Claw is talking ──
        if (currentState == PipelineState.Speaking) {
            val ttsText = tts.currentText.value

            if (isEcho(text, ttsText, getEchoThreshold())) {
                Log.d(TAG, "Echo filtered during Speaking: '${text.take(60)}'")
                return
            }

            // If interruption is not allowed, ignore
            if (!isInterruptionAllowed()) {
                Log.d(TAG, "Interruption not allowed (mode=${_settings.value.interruptionMode})")
                return
            }

            Log.d(TAG, "Interruption! User said: $text")
            wasInterrupted = true
            tts.stop()
            setEchoCancellationMode(false)

            addTranscript(TranscriptEntry.User(text))
            _pipelineState.value = PipelineState.Thinking

            viewModelScope.launch {
                sendAndSpeak(text, interruptionPrefix = true)
            }
            return
        }

        // ── Guard: only process speech when we're actually listening ──
        if (currentState != PipelineState.Listening) {
            Log.d(TAG, "Ignoring STT result in state $currentState: '${text.take(60)}'")
            return
        }

        // ── Speaker verification (if enabled) — runs IN PARALLEL with gateway call ──
        val s = _settings.value
        if (s.voiceMatchEnabled && s.enrolledEmbedding.isNotBlank()) {
            viewModelScope.launch {
                // Start verification and gateway request simultaneously
                wasInterrupted = false
                Log.d(TAG, "User said (pending verify): $text")
                addTranscript(TranscriptEntry.User(text))
                _pipelineState.value = PipelineState.Thinking

                val verifyJob = async {
                    speakerVerification.verify(
                        enrolledEmbeddingJson = s.enrolledEmbedding,
                        threshold = s.voiceMatchThreshold,
                    )
                }
                val gatewayJob = async {
                    sendMessageToGateway(text)
                }

                // Wait for verification first (2s) — if it fails, cancel gateway call
                val verified = verifyJob.await()
                if (!verified) {
                    gatewayJob.cancel()
                    Log.d(TAG, "Speaker verification failed — discarding response")
                    _statusMessage.value = "Voz no reconocida"
                    // Remove the user transcript entry we added
                    _transcript.value = _transcript.value.dropLast(1)
                    _pipelineState.value = PipelineState.Listening
                    if (_conversationActive.value) {
                        stt.resumeListening()
                    }
                    return@launch
                }

                // Verified — now wait for gateway and speak
                Log.d(TAG, "Speaker verified ✓ — waiting for gateway response")
                val result = gatewayJob.await()
                handleGatewayResponse(result)
            }
            return
        }

        processNormalSpeech(text)
    }

    private fun processNormalSpeech(text: String) {
        wasInterrupted = false
        Log.d(TAG, "User said: $text")
        addTranscript(TranscriptEntry.User(text))
        _pipelineState.value = PipelineState.Thinking

        viewModelScope.launch {
            sendAndSpeak(text, interruptionPrefix = false)
        }
    }

    /**
     * Send a message to the gateway, speak the response, then resume listening.
     */
    private suspend fun sendAndSpeak(userText: String, interruptionPrefix: Boolean) {
        val messageToSend = if (interruptionPrefix) {
            "[User interrupted previous response] $userText"
        } else {
            userText
        }

        val result = sendMessageToGateway(messageToSend)
        handleGatewayResponse(result)
    }

    /**
     * Handle a gateway response: speak it via TTS and resume listening.
     * Shared by both normal pipeline and parallel-verify pipeline.
     */
    private suspend fun handleGatewayResponse(result: Result<String>) {
        result.fold(
            onSuccess = { response ->
                if (!_conversationActive.value) return@fold

                Log.d(TAG, "Claw: ${response.take(100)}")
                addTranscript(TranscriptEntry.Claw(response))

                wasInterrupted = false
                _pipelineState.value = PipelineState.Speaking

                // Enable echo cancellation (earpiece routing + volume reduction)
                setEchoCancellationMode(true)

                // Start parallel STT only if interruption is allowed
                if (isInterruptionAllowed()) {
                    stt.resumeListening()
                }

                tts.speakStreaming(response).fold(
                    onSuccess = {
                        setEchoCancellationMode(false)
                        if (_conversationActive.value && !wasInterrupted) {
                            _pipelineState.value = PipelineState.Listening
                            stt.resumeListening()
                        }
                    },
                    onFailure = { err ->
                        setEchoCancellationMode(false)
                        if (!wasInterrupted) {
                            Log.w(TAG, "TTS failed: ${err.message}")
                            _statusMessage.value = "TTS: ${err.message}"
                            if (_conversationActive.value) {
                                _pipelineState.value = PipelineState.Listening
                                stt.resumeListening()
                            }
                        }
                    }
                )
            },
            onFailure = { err ->
                Log.e(TAG, "Claw error: ${err.message}")
                addTranscript(TranscriptEntry.Error("Error: ${err.message}"))
                _statusMessage.value = "Error: ${err.message}"
                if (_conversationActive.value) {
                    _pipelineState.value = PipelineState.Listening
                    stt.resumeListening()
                }
            }
        )
    }

    private suspend fun sendMessageToGateway(message: String): Result<String> {
        return if (gatewayNode.isConnected) {
            Log.d(TAG, "Sending via WebSocket")
            gatewayNode.sendChatMessage(message)
        } else {
            Log.d(TAG, "WebSocket unavailable — falling back to HTTP bridge")
            _statusMessage.value = "Usando HTTP fallback"
            bridge.sendMessage(message)
        }
    }

    // ── Transcript (with Room persistence) ──────────────────────

    private fun addTranscript(entry: TranscriptEntry) {
        _transcript.value = _transcript.value + entry

        // Persist to Room
        viewModelScope.launch {
            try {
                val role = when (entry) {
                    is TranscriptEntry.User -> "user"
                    is TranscriptEntry.Claw -> "claw"
                    is TranscriptEntry.Error -> "error"
                }
                val text = when (entry) {
                    is TranscriptEntry.User -> entry.text
                    is TranscriptEntry.Claw -> entry.text
                    is TranscriptEntry.Error -> entry.text
                }
                db.transcriptDao().insert(TranscriptEntity(role = role, text = text))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist transcript: ${e.message}")
            }
        }
    }

    fun clearTranscript() {
        _transcript.value = emptyList()
        bridge.resetConversation()
        viewModelScope.launch {
            try { db.transcriptDao().deleteAll() } catch (_: Exception) {}
        }
    }

    override fun onCleared() {
        stt.destroy()
        tts.stop()
        setEchoCancellationMode(false)
        gatewayNode.cancelPendingCalls()
        super.onCleared()
    }

    // ── Speaker enrollment (called from Settings) ──────────────

    /**
     * Start speaker enrollment process. Returns the embedding JSON or null.
     * @param onPhrase Called with (current, total, phraseText) — show phrase, give user 2s to prepare.
     * @param onRecord Called with (current, total) — recording starts now.
     */
    suspend fun enrollSpeaker(
        onPhrase: suspend (Int, Int, String) -> Unit = { _, _, _ -> },
        onRecord: suspend (Int, Int) -> Unit = { _, _ -> },
    ): String? {
        return speakerVerification.enroll(onPhrase, onRecord)
    }

    // ── Types ───────────────────────────────────────────────────

    sealed class PipelineState {
        data object Idle : PipelineState()
        data object Listening : PipelineState()
        data object Thinking : PipelineState()
        data object Speaking : PipelineState()

        val displayText: String get() = when (this) {
            Idle      -> "Toca para iniciar conversación"
            Listening -> "Escuchando..."
            Thinking  -> "Pensando..."
            Speaking  -> "Hablando... (habla para interrumpir)"
        }
    }

    sealed class TranscriptEntry {
        data class User(val text: String) : TranscriptEntry()
        data class Claw(val text: String) : TranscriptEntry()
        data class Error(val text: String) : TranscriptEntry()
    }
}
