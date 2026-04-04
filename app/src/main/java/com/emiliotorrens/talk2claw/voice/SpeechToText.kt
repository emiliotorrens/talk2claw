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
 * Supports Spanish (es-ES) by default.
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

    var onResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun startListening(languageCode: String = "es-ES") {
        if (_state.value == STTState.Listening) return

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
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
                    Log.d(TAG, "End of speech")
                }

                override fun onError(error: Int) {
                    val msg = sttErrorString(error)
                    Log.w(TAG, "STT error: $msg ($error)")
                    _state.value = STTState.Idle
                    // Don't report "no speech" as error — it's normal
                    if (error != SpeechRecognizer.ERROR_NO_MATCH &&
                        error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        onError?.invoke(msg)
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    _state.value = STTState.Idle
                    _partialText.value = ""
                    if (text.isNotBlank()) {
                        Log.d(TAG, "Result: $text")
                        onResult?.invoke(text)
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
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCode)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        recognizer?.startListening(intent)
        _state.value = STTState.Starting
        Log.d(TAG, "Starting STT ($languageCode)")
    }

    fun stopListening() {
        recognizer?.stopListening()
        _state.value = STTState.Processing
    }

    fun cancel() {
        recognizer?.cancel()
        _state.value = STTState.Idle
        _partialText.value = ""
    }

    fun destroy() {
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
    }
}
