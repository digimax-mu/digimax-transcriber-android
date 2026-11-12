package com.digimax.transcriber

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/** Shared, Compose-observable recorder state. */
object RecState {
    var recording by mutableStateOf(false)
    var paused by mutableStateOf(false)
    var lastFile by mutableStateOf<String?>(null)
    var level by mutableStateOf(0f)          // live mic level 0..1 for the waveform
}

/**
 * Foreground service (type=microphone) so recording survives screen-lock and
 * app-switch — the whole reason this is native, not a web page.
 */
class RecorderService : Service() {

    private var recorder: AudioRecorder? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopRecording(); stopSelf() }
            ACTION_PAUSE -> setPaused(true)
            ACTION_RESUME -> setPaused(false)
            ACTION_START -> startRecording()
            // A null/unknown action is an OS restart after a low-memory kill —
            // do NOT resurrect recording (the old code did, draining the battery
            // and keeping the mic live after the user had stopped).
            else -> stopSelf()
        }
        // Never auto-restart: recording is always user-initiated, so a killed
        // service stays dead instead of silently coming back to record.
        return START_NOT_STICKY
    }

    private fun setPaused(p: Boolean) {
        recorder?.paused = p
        RecState.paused = p
    }

    private fun startRecording() {
        if (RecState.recording) return
        ensureChannel()
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        val dir = File(filesDir, "recordings").apply { mkdirs() }
        val f = File(dir, "rec-${System.currentTimeMillis()}.wav")
        recorder = AudioRecorder(f) { RecState.level = it }.also { it.start() }
        RecState.lastFile = f.absolutePath
        RecState.recording = true
        RecState.paused = false
        TranscriberWidgetProvider.refresh(this)   // flip the home-screen tile to RECORDING
    }

    private fun stopRecording() {
        recorder?.stop(); recorder = null
        RecState.recording = false
        RecState.paused = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        TranscriberWidgetProvider.refresh(this)   // flip the tile back to idle
    }

    override fun onDestroy() {
        if (RecState.recording) stopRecording()
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        @Suppress("DEPRECATION")
        val stopAction = Notification.Action.Builder(0, "Stop", stop).build()
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Recording…")
            .setContentText("Digimax Transcriber is recording in the background.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(stopAction)
            .build()
    }

    companion object {
        const val ACTION_START = "com.digimax.transcriber.START"
        const val ACTION_STOP = "com.digimax.transcriber.STOP"
        const val ACTION_PAUSE = "com.digimax.transcriber.PAUSE"
        const val ACTION_RESUME = "com.digimax.transcriber.RESUME"
        const val CHANNEL = "recording"
        const val NOTIF_ID = 1

        fun start(ctx: Context) {
            ctx.startForegroundService(
                Intent(ctx, RecorderService::class.java).setAction(ACTION_START)
            )
        }
        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, RecorderService::class.java).setAction(ACTION_STOP))
        }
        fun pause(ctx: Context) {
            ctx.startService(Intent(ctx, RecorderService::class.java).setAction(ACTION_PAUSE))
        }
        fun resume(ctx: Context) {
            ctx.startService(Intent(ctx, RecorderService::class.java).setAction(ACTION_RESUME))
        }
    }
}
