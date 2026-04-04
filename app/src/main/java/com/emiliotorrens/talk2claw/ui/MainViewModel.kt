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
        stt.onError = { err -> _statusMessage.value = "STT: $err" }

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

    /** Start the voice pipeline — user presses the talk button */
    fun startListening() {
        if (_pipelineState.value != PipelineState.Idle) return
        tts.stop() // Stop any ongoing playback
        _pipelineState.value = PipelineState.Listening
        stt.startListening(_settings.value.ttsLanguageCode)
    }

    /** Stop listening — user releases the talk button */
    fun stopListening() {
        stt.stopListening()
        _pipelineState.value = PipelineState.ProcessingSTT
    }

    /** Cancel current interaction */
    fun cancel() {
        stt.cancel()
        tts.stop()
        _pipelineState.value = PipelineState.Idle
    }

    /** Called when STT produces a final result */
    private fun onSpeechResult(text: String) {
        Log.d(TAG, "User said: $text")
        addTranscript(TranscriptEntry.User(text))
        _pipelineState.value = PipelineState.SendingToClaw

        viewModelScope.launch {
            // Send to OpenClaw
            val result = bridge.sendMessage(text)
            result.fold(
                onSuccess = { response ->
                    Log.d(TAG, "Claw: ${response.take(100)}")
                    addTranscript(TranscriptEntry.Claw(response))
                    _pipelineState.value = PipelineState.Speaking

                    // Speak the response
                    tts.speak(response).fold(
                        onSuccess = {
                            _pipelineState.value = PipelineState.Idle
                        },
                        onFailure = { err ->
                            Log.w(TAG, "TTS failed: ${err.message}")
                            _statusMessage.value = "TTS: ${err.message}"
                            _pipelineState.value = PipelineState.Idle
                        }
                    )
                },
                onFailure = { err ->
                    Log.e(TAG, "Claw error: ${err.message}")
                    addTranscript(TranscriptEntry.Error("Error: ${err.message}"))
                    _statusMessage.value = "Error: ${err.message}"
                    _pipelineState.value = PipelineState.Idle
                }
            )
        }
    }

    private fun addTranscript(entry: TranscriptEntry) {
        _transcript.value = _transcript.value + entry
    }

    fun clearTranscript() {
        _transcript.value = emptyList()
        bridge.resetConversation()
    }

    val connectionState get() = bridge.connectionState

    fun recheckConnection() {
        viewModelScope.launch { bridge.checkConnection() }
    }

    override fun onCleared() {
        stt.destroy()
        tts.stop()
        super.onCleared()
    }

    // --- Types ---

    sealed class PipelineState {
        data object Idle : PipelineState()
        data object Listening : PipelineState()
        data object ProcessingSTT : PipelineState()
        data object SendingToClaw : PipelineState()
        data object Speaking : PipelineState()

        val displayText: String get() = when (this) {
            Idle -> "Toca para hablar"
            Listening -> "Escuchando..."
            ProcessingSTT -> "Procesando voz..."
            SendingToClaw -> "Pensando..."
            Speaking -> "Hablando..."
        }

        val isActive: Boolean get() = this != Idle
    }

    sealed class TranscriptEntry {
        data class User(val text: String) : TranscriptEntry()
        data class Claw(val text: String) : TranscriptEntry()
        data class Error(val text: String) : TranscriptEntry()
    }
}
