package com.emiliotorrens.talk2claw

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.emiliotorrens.talk2claw.openclaw.DeviceIdentity
import com.emiliotorrens.talk2claw.openclaw.GatewayNode
import com.emiliotorrens.talk2claw.settings.AppSettings
import com.emiliotorrens.talk2claw.settings.SettingsManager

/**
 * Application class — holds the shared GatewayNode instance so both the
 * ViewModel and GatewayService can access the same WebSocket connection.
 */
class Talk2ClawApp : Application() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "gateway_service"
    }

    /** Persistent device identity (Ed25519 keypair). Null if crypto unavailable. */
    var deviceIdentity: DeviceIdentity? = null
        private set

    /** Shared WebSocket node — re-created on settings change. */
    lateinit var gatewayNode: GatewayNode
        private set

    override fun onCreate() {
        super.onCreate()
        SettingsManager.init(this)
        deviceIdentity = DeviceIdentity.loadOrCreate(this)
        gatewayNode = GatewayNode(SettingsManager.load(), deviceIdentity)
        createNotificationChannel()
    }

    /** Replace the node with new settings and reconnect. */
    fun applyNewSettings(newSettings: AppSettings) {
        gatewayNode.updateSettings(newSettings)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Conexión Gateway",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Mantiene la conexión WebSocket con OpenClaw en segundo plano"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }
}
