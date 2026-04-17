package com.emiliotorrens.talk2claw.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.emiliotorrens.talk2claw.MainActivity
import com.emiliotorrens.talk2claw.R

/**
 * 1x1 home screen widget with a mic button that launches Talk2Claw
 * and auto-starts a conversation.
 */
class Talk2ClawWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (widgetId in appWidgetIds) {
            val intent = Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_START_CONVERSATION, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val views = RemoteViews(context.packageName, R.layout.talk2claw_widget_layout)
            views.setOnClickPendingIntent(R.id.widget_mic_button, pendingIntent)

            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }
}
