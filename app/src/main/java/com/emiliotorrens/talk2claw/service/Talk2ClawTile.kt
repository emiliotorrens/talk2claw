package com.emiliotorrens.talk2claw.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.emiliotorrens.talk2claw.MainActivity

/**
 * Quick Settings tile that launches Talk2Claw with auto-start conversation.
 *
 * Tapping the tile opens the app and triggers a new conversation immediately.
 */
class Talk2ClawTile : TileService() {

    companion object {
        private const val TAG = "Talk2ClawTile"
    }

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "Talk2Claw"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = "Toca para hablar"
            }
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        Log.d(TAG, "Tile clicked — launching conversation")

        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_START_CONVERSATION, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
