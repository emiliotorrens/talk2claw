package com.emiliotorrens.talk2claw.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.emiliotorrens.talk2claw.openclaw.OpenClawBridge
import com.emiliotorrens.talk2claw.settings.AppSettings
import com.emiliotorrens.talk2claw.settings.SettingsManager
import com.emiliotorrens.talk2claw.voice.SpeechToText
import com.emiliotorrens.talk2claw.voice.TextToSpeech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "MainVM"
    }

    // Components
    val stt = SpeechToText(app.applicationContext)
    private var tts: TextToSpeech
    private var bridge: OpenClawBridge

    // UI state
    private val _settings = MutableStateFlow(SettingsManager.load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _conversationActive = MutableStateFlow(false)
    val conversationActive: StateFlow<Boolean> = _conversationActive.asStateFlow()

    private val _pipelineState = MutableStateFlow<PipelineState>(PipelineState.Idle)
    val pipelineState: StateFlow<PipelineState> = _pipelineState.asStateFlow()

    private val _transcript = MutableStateFlow<List<TranscriptEntry>>(emptyList())
    val transcript: StateFlow<List<TranscriptEntry>> = _transcript.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

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

        // Check gateway on start
        viewModelScope.launch {
            bridge.checkConnection()
        }
    }

    fun updateSettings(newSettings: AppSettings) {
        SettingsManager.save(newSettings)
        _settings.value = newSettings
        tts = TextToSpeech(newSettings)
        bridge = OpenClawBridge(newSettings)
        viewModelScope.launch { bridge.checkConnection() }
    }

    // ── Conversation lifecycle ──────────────────────────────────

    /** Toggle conversation on/off */
    fun toggleConversation() {
        if (_conversationActive.value) {
            stopConversation()
        } else {
            startConversation()
        }
    }

    /** Start continuous conversation mode */
    private fun startConversation() {
        Log.d(TAG, "Starting conversation")
        _conversationActive.value = true
        _statusMessage.value = ""
        stt.continuousMode = true
        _pipelineState.value = PipelineState.Listening
        stt.startListening(_settings.value.ttsLanguageCode)
    }

    /** Stop conversation mode entirely */
    fun stopConversation() {
        Log.d(TAG, "Stopping conversation")
        _conversationActive.value = false
        stt.cancel()
        tts.stop()
        _pipelineState.value = PipelineState.Idle
    }

    // ── Pipeline: STT result → Claw → TTS → resume listening ──

    /** Called when STT produces a final result */
    private fun onSpeechResult(text: String) {
        if (!_conversationActive.value) return

        Log.d(TAG, "User said: $text")
        addTranscript(TranscriptEntry.User(text))
        _pipelineState.value = PipelineState.Thinking

        viewModelScope.launch {
            // Send to OpenClaw
            val result = bridge.sendMessage(text)
            result.fold(
                onSuccess = { response ->
                    if (!_conversationActive.value) return@fold

                    Log.d(TAG, "Claw: ${response.take(100)}")
                    addTranscript(TranscriptEntry.Claw(response))
                    _pipelineState.value = PipelineState.Speaking

                    // Speak the response
                    tts.speak(response).fold(
                        onSuccess = {
                            // Resume listening after TTS finishes
                            if (_conversationActive.value) {
                                _pipelineState.value = PipelineState.Listening
                                stt.resumeListening()
                            }
                        },
                        onFailure = { err ->
                            Log.w(TAG, "TTS failed: ${err.message}")
                            _statusMessage.value = "TTS: ${err.message}"
                            // Still resume listening even if TTS fails
                            if (_conversationActive.value) {
                                _pipelineState.value = PipelineState.Listening
                                stt.resumeListening()
                            }
                        }
                    )
                },
                onFailure = { err ->
                    Log.e(TAG, "Claw error: ${err.message}")
                    addTranscript(TranscriptEntry.Error("Error: ${err.message}"))
                    _statusMessage.value = "Error: ${err.message}"
                    // Resume listening on error too
                    if (_conversationActive.value) {
                        _pipelineState.value = PipelineState.Listening
                        stt.resumeListening()
                    }
                }
            )
        }
    }

    // ── Transcript ──────────────────────────────────────────────

    private fun addTranscript(entry: TranscriptEntry) {
        _transcript.value = _transcript.value + entry
    }

    fun clearTranscript() {
        _transcript.value = emptyList()
        bridge.resetConversation()
    }

    // ── Connection ──────────────────────────────────────────────

    val connectionState get() = bridge.connectionState

    fun recheckConnection() {
        viewModelScope.launch { bridge.checkConnection() }
    }

    override fun onCleared() {
        stt.destroy()
        tts.stop()
        super.onCleared()
    }

    // ── Types ───────────────────────────────────────────────────

    sealed class PipelineState {
        data object Idle : PipelineState()
        data object Listening : PipelineState()
        data object Thinking : PipelineState()
        data object Speaking : PipelineState()

        val displayText: String get() = when (this) {
            Idle -> "Toca para iniciar conversación"
            Listening -> "Escuchando..."
            Thinking -> "Pensando..."
            Speaking -> "Hablando..."
        }
    }

    sealed class TranscriptEntry {
        data class User(val text: String) : TranscriptEntry()
        data class Claw(val text: String) : TranscriptEntry()
        data class Error(val text: String) : TranscriptEntry()
    }
}
