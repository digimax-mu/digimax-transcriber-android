package com.digimax.transcriber

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Home-screen quick-record tile. Reflects [RecState.recording] and, on tap,
 * fires [RecordToggleActivity] — a translucent trampoline that starts or stops
 * the background [RecorderService]. Nothing records here; the widget only
 * toggles the service and repaints.
 */
class TranscriberWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, manager, it) }
    }

    companion object {
        private const val IDLE = "● TAP TO\nRECORD"
        private const val LIVE = "● RECORDING\nTAP TO STOP"
        private const val COLOR_IDLE = 0xFFE9E4DE.toInt()  // off-white
        private const val COLOR_LIVE = 0xFFE3262E.toInt()  // Digimax red

        /** Repaint every placed tile to match the current recording state. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val component = ComponentName(context, TranscriberWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { render(context, manager, it) }
        }

        private fun render(context: Context, manager: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_transcriber)
            val recording = RecState.recording
            views.setTextViewText(R.id.widget_label, if (recording) LIVE else IDLE)
            views.setTextColor(R.id.widget_label, if (recording) COLOR_LIVE else COLOR_IDLE)

            val toggle = PendingIntent.getActivity(
                context, 0,
                Intent(context, RecordToggleActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widget_root, toggle)
            manager.updateAppWidget(id, views)
        }
    }
}
