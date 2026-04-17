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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop

/**
 * Foreground service that keeps the GatewayNode WebSocket alive while the app
 * is in the background. It observes the connection state and updates the
 * persistent notification accordingly.
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

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        startForeground(NOTIFICATION_ID, buildNotification("Conectando..."))
        observeConnectionState()
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
        scope.cancel()
        super.onDestroy()
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
