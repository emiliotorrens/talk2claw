package com.emiliotorrens.talk2claw.voice

import android.util.Log
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import ai.picovoice.porcupine.Porcupine

/**
 * Wake word detection engine using Picovoice Porcupine.
 *
 * Listens for a wake word in the background and fires [onWakeWordDetected]
 * when detected. Currently uses the built-in "porcupine" keyword as a
 * placeholder until a custom "Oye Claw" keyword file (.ppn) is created
 * via the Picovoice Console.
 *
 * TODO: Create custom "Oye Claw" keyword via Picovoice Console and replace
 *       the built-in keyword. Requires uploading a .ppn file to assets/.
 * TODO: Download Spanish model file (.pv) from
 *       https://github.com/Picovoice/porcupine/tree/master/lib/common
 *       and place in assets/ for better Spanish wake word accuracy.
 */
class WakeWordEngine(
    private val accessKey: String,
) {
    companion object {
        private const val TAG = "WakeWordEngine"
    }

    private var porcupineManager: PorcupineManager? = null

    @Volatile
    var isListening: Boolean = false
        private set

    /** Called when the wake word is detected. */
    var onWakeWordDetected: (() -> Unit)? = null

    /**
     * Start listening for wake word.
     * Must be called from a context with RECORD_AUDIO permission.
     *
     * @throws Exception if Porcupine fails to initialize (e.g., invalid access key).
     */
    fun start() {
        if (isListening) return
        if (accessKey.isBlank()) {
            Log.w(TAG, "Cannot start: Picovoice access key is empty")
            return
        }

        try {
            val callback = PorcupineManagerCallback { keywordIndex ->
                Log.i(TAG, "Wake word detected! (keyword index: $keywordIndex)")
                onWakeWordDetected?.invoke()
            }

            // TODO: Replace with custom "Oye Claw" .ppn keyword file
            // TODO: Add Spanish model path for better accuracy:
            //       .setModelPath("porcupine_params_es.pv")
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeyword(Porcupine.BuiltInKeyword.PORCUPINE)
                .setSensitivity(0.7f)
                .build(callback)

            porcupineManager?.start()
            isListening = true
            Log.i(TAG, "Wake word engine started (keyword: porcupine)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start wake word engine: ${e.message}", e)
            isListening = false
            throw e
        }
    }

    /** Stop listening for wake word. Safe to call multiple times. */
    fun stop() {
        try {
            porcupineManager?.stop()
            porcupineManager?.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping wake word engine: ${e.message}")
        }
        porcupineManager = null
        isListening = false
        Log.d(TAG, "Wake word engine stopped")
    }
}
