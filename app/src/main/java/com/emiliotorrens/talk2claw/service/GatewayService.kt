package com.emiliotorrens.talk2claw.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.emiliotorrens.talk2claw.MainActivity
import com.emiliotorrens.talk2claw.R
import com.emiliotorrens.talk2claw.Talk2ClawApp
import com.emiliotorrens.talk2claw.openclaw.GatewayNode
import com.emiliotorrens.talk2claw.settings.SettingsManager
import com.emiliotorrens.talk2claw.voice.WakeWordEngine
import kotlinx.coroutines.*

/**
 * Foreground service that keeps the GatewayNode WebSocket alive while the app
 * is in the background. Also manages wake word detection when enabled.
 */
class GatewayService : Service() {

    companion object {
        private const val TAG = "GatewayService"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, GatewayService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GatewayService::class.java))
        }
    }

    inner class GatewayBinder : Binder() {
        val service: GatewayService get() = this@GatewayService
    }

    private val binder = GatewayBinder()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val gatewayNode: GatewayNode
        get() = (application as Talk2ClawApp).gatewayNode

    private var wakeWordEngine: WakeWordEngine? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        startForeground(NOTIFICATION_ID, buildNotification("Conectando..."))
        observeConnectionState()
        startWakeWordIfEnabled()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        // Connect if not already connected
        if (!gatewayNode.isConnected) {
            gatewayNode.connect()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        stopWakeWord()
        scope.cancel()
        super.onDestroy()
    }

    // ── Wake Word ────────────────────────────────────────────

    private fun startWakeWordIfEnabled() {
        val settings = SettingsManager.load()
        if (!settings.wakeWordEnabled || settings.picovoiceAccessKey.isBlank()) return

        try {
            val engine = WakeWordEngine(settings.picovoiceAccessKey)
            engine.onWakeWordDetected = {
                Log.i(TAG, "Wake word detected — notifying app")
                (application as Talk2ClawApp).onWakeWordDetected()
            }
            engine.start()
            wakeWordEngine = engine
            Log.i(TAG, "Wake word engine started in service")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start wake word in service: ${e.message}")
        }
    }

    private fun stopWakeWord() {
        wakeWordEngine?.stop()
        wakeWordEngine = null
    }

    /** Called when settings change to restart/stop wake word engine. */
    fun restartWakeWord() {
        stopWakeWord()
        startWakeWordIfEnabled()
    }

    // ── Notification ─────────────────────────────────────────

    private fun observeConnectionState() {
        scope.launch {
            gatewayNode.connectionState.collect { state ->
                val text = when (state) {
                    GatewayNode.ConnectionState.Connected    -> "Talk2Claw conectado"
                    GatewayNode.ConnectionState.Connecting  -> "Conectando..."
                    GatewayNode.ConnectionState.Disconnected -> "Desconectado"
                    is GatewayNode.ConnectionState.Error    -> "Error: ${state.message}"
                }
                updateNotification(text)
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, Talk2ClawApp.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Talk2Claw 🐾")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
