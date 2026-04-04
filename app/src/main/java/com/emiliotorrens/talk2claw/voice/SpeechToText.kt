package com.emiliotorrens.talk2claw.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android's built-in SpeechRecognizer for free on-device STT.
 * Supports continuous conversation mode — automatically restarts
 * listening after silence/result unless explicitly stopped.
 */
class SpeechToText(private val context: Context) {

    companion object {
        private const val TAG = "SpeechToText"
    }

    private var recognizer: SpeechRecognizer? = null

    private val _state = MutableStateFlow<STTState>(STTState.Idle)
    val state: StateFlow<STTState> = _state.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    /** Called when STT produces a final transcription */
    var onResult: ((String) -> Unit)? = null

    /** Called on errors (non-fatal ones like "no match" are filtered) */
    var onError: ((String) -> Unit)? = null

    /** If true, STT will auto-restart after results/silence (conversation mode) */
    @Volatile
    var continuousMode = false

    /** Called when STT is silent and no speech was detected — allows the loop to just re-listen */
    var onSilence: (() -> Unit)? = null

    private var languageCode = "es-ES"

    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun startListening(language: String = "es-ES") {
        languageCode = language
        doStartListening()
    }

    private fun doStartListening() {
        if (_state.value == STTState.Listening) return

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(createListener())
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCode)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Silence detection thresholds (ms)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
        }

        recognizer?.startListening(intent)
        _state.value = STTState.Starting
        Log.d(TAG, "Starting STT ($languageCode) continuous=$continuousMode")
    }

    private fun createListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _state.value = STTState.Listening
            _partialText.value = ""
            Log.d(TAG, "Ready for speech")
        }

        override fun onBeginningOfSpeech() {
            _state.value = STTState.Listening
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            _state.value = STTState.Processing
            Log.d(TAG, "End of speech detected")
        }

        override fun onError(error: Int) {
            val msg = sttErrorString(error)
            Log.w(TAG, "STT error: $msg ($error)")

            when (error) {
                // No speech detected — silence. In continuous mode, just re-listen.
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    if (continuousMode) {
                        Log.d(TAG, "Silence detected, re-listening...")
                        _state.value = STTState.Idle
                        onSilence?.invoke()
                        doStartListening()
                    } else {
                        _state.value = STTState.Idle
                    }
                }
                // Actual errors
                else -> {
                    _state.value = STTState.Idle
                    onError?.invoke(msg)
                    // In continuous mode, try to recover from transient errors
                    if (continuousMode && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        Log.d(TAG, "Error recovery, re-listening in continuous mode...")
                        doStartListening()
                    }
                }
            }
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""
            _partialText.value = ""

            if (text.isNotBlank()) {
                Log.d(TAG, "Final result: $text")
                _state.value = STTState.GotResult
                onResult?.invoke(text)
                // NOTE: In continuous mode, the ViewModel restarts listening
                // after TTS finishes — not here, to avoid listening during playback.
            } else {
                // Empty result — re-listen in continuous mode
                _state.value = STTState.Idle
                if (continuousMode) {
                    doStartListening()
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partial = matches?.firstOrNull() ?: ""
            if (partial.isNotBlank()) {
                _partialText.value = partial
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /** Resume listening after TTS playback (called by ViewModel in conversation loop) */
    fun resumeListening() {
        if (continuousMode) {
            _state.value = STTState.Idle
            doStartListening()
        }
    }

    fun stopListening() {
        recognizer?.stopListening()
        _state.value = STTState.Processing
    }

    fun cancel() {
        continuousMode = false
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        _state.value = STTState.Idle
        _partialText.value = ""
    }

    fun destroy() {
        continuousMode = false
        recognizer?.destroy()
        recognizer = null
        _state.value = STTState.Idle
    }

    private fun sttErrorString(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        SpeechRecognizer.ERROR_CLIENT -> "Client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Missing RECORD_AUDIO permission"
        SpeechRecognizer.ERROR_NETWORK -> "Network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
        SpeechRecognizer.ERROR_SERVER -> "Server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
        else -> "Unknown ($error)"
    }

    sealed class STTState {
        data object Idle : STTState()
        data object Starting : STTState()
        data object Listening : STTState()
        data object Processing : STTState()
        data object GotResult : STTState()
    }
}
