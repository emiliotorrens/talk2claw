package com.emiliotorrens.talk2claw.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.emiliotorrens.talk2claw.Talk2ClawApp
import com.emiliotorrens.talk2claw.openclaw.GatewayNode
import com.emiliotorrens.talk2claw.openclaw.OpenClawBridge
import com.emiliotorrens.talk2claw.settings.AppSettings
import com.emiliotorrens.talk2claw.settings.SettingsManager
import com.emiliotorrens.talk2claw.voice.SpeechToText
import com.emiliotorrens.talk2claw.voice.TextToSpeech
import com.emiliotorrens.talk2claw.voice.VoicePreset
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
         * Conservative threshold — better to miss an interruption than to eat user speech.
         */
        internal const val ECHO_THRESHOLD = 0.65f

        /**
         * Returns true if the STT result is likely microphone echo of the TTS output.
         * Extracted to companion object for unit testability.
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

            val matchCount = sttWords.count { it in ttsWords }
            val overlap = matchCount.toFloat() / sttWords.size.toFloat()
            return overlap > threshold
        }
    }

    // ── Components ──────────────────────────────────────────────

    val stt = SpeechToText(app.applicationContext)
    private var tts: TextToSpeech

    /** Primary connection: WebSocket node to the gateway. */
    private val gatewayNode: GatewayNode
        get() = getApplication<Talk2ClawApp>().gatewayNode

    /** Fallback bridge (HTTP REST) — used when WebSocket is unavailable. */
    private var bridge: OpenClawBridge

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

    /**
     * Set to true when the user interrupts TTS playback.
     * Prevents the original TTS-completion callback from restarting the pipeline
     * (the interruption handler already launched a new coroutine for that).
     */
    @Volatile private var wasInterrupted = false

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
                // Fall back to HTTP bridge check
                bridge.checkConnection()
            }
        }
    }

    /**
     * Synthesize and play a short sample using the given voice preset.
     * Used from the Settings screen to preview a voice before saving.
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
        // Apply settings to WebSocket node (will reconnect)
        getApplication<Talk2ClawApp>().applyNewSettings(newSettings)
    }

    // ── Connection ──────────────────────────────────────────────

    /** Exposes the WebSocket connection state as primary indicator. */
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
        // Apply immediately if conversation is already active
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
        _pipelineState.value = PipelineState.Idle
    }

    // ── Pipeline: STT → Claw → TTS → listen ───────────────────

    private fun onSpeechResult(text: String) {
        if (!_conversationActive.value) return

        val currentState = _pipelineState.value

        // ── Interruption path: user speaks while Claw is talking ──
        if (currentState == PipelineState.Speaking) {
            val ttsText = tts.currentText.value

            if (isEcho(text, ttsText)) {
                Log.d(TAG, "Echo filtered during Speaking: '${text.take(60)}'")
                // STT will re-listen automatically via continuousMode silence recovery
                return
            }

            Log.d(TAG, "Interruption! User said: $text")
            wasInterrupted = true
            tts.stop() // cuts playback within ~50ms; speak() coroutine will unblock

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

        // ── Normal path ──
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
     * This is the core pipeline step, shared by normal and interruption flows.
     *
     * @param userText       The user's spoken text (shown in transcript).
     * @param interruptionPrefix If true, prepends "[User interrupted previous response]"
     *                           to the message sent to the gateway.
     */
    private suspend fun sendAndSpeak(userText: String, interruptionPrefix: Boolean) {
        val messageToSend = if (interruptionPrefix) {
            "[User interrupted previous response] $userText"
        } else {
            userText
        }

        val result = sendMessageToGateway(messageToSend)

        result.fold(
            onSuccess = { response ->
                if (!_conversationActive.value) return@fold

                Log.d(TAG, "Claw: ${response.take(100)}")
                addTranscript(TranscriptEntry.Claw(response))

                // Reset interruption flag for this new speaking turn
                wasInterrupted = false
                _pipelineState.value = PipelineState.Speaking

                // ── Phase 2: Start listening IN PARALLEL with TTS playback ──
                // This enables real-time interruption detection.
                // If STT fires during Speaking state, onSpeechResult() will handle it.
                stt.resumeListening()

                // Phase 3: use streaming TTS to reduce first-audio latency.
                // speakStreaming() falls back to speak() for single-sentence responses.
                tts.speakStreaming(response).fold(
                    onSuccess = {
                        // Only resume normal listening if we weren't interrupted.
                        // If wasInterrupted == true, the interruption handler already
                        // launched a new sendAndSpeak coroutine — don't interfere.
                        if (_conversationActive.value && !wasInterrupted) {
                            _pipelineState.value = PipelineState.Listening
                            stt.resumeListening()
                        }
                    },
                    onFailure = { err ->
                        if (!wasInterrupted) {
                            Log.w(TAG, "TTS failed: ${err.message}")
                            _statusMessage.value = "TTS: ${err.message}"
                            if (_conversationActive.value) {
                                _pipelineState.value = PipelineState.Listening
                                stt.resumeListening()
                            }
                        }
                        // If wasInterrupted, the interruption handler takes over — do nothing.
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

    /**
     * Sends a message using WebSocket if connected, falls back to HTTP bridge.
     */
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

    // ── Echo cancellation (logic in companion object) ─────────────────────────
    // isEcho() is in the companion object for unit testability.

    // ── Transcript ──────────────────────────────────────────────

    private fun addTranscript(entry: TranscriptEntry) {
        _transcript.value = _transcript.value + entry
    }

    fun clearTranscript() {
        _transcript.value = emptyList()
        bridge.resetConversation()
    }

    override fun onCleared() {
        stt.destroy()
        tts.stop()
        gatewayNode.cancelPendingCalls()
        super.onCleared()
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
