package com.digimax.transcriber

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.content.ContextCompat

/**
 * Invisible trampoline launched by the home-screen widget tap.
 *
 * Why an Activity and not a direct service PendingIntent: Android 12+ blocks
 * starting a `microphone` foreground service from the background, and a widget
 * click counts as background. Launching an Activity brings the app to the
 * foreground, so the service start is allowed. The Activity draws nothing and
 * finishes immediately — the user sees only their home screen.
 *
 * It also owns the permission gate: if RECORD_AUDIO isn't granted yet, a
 * headless service can't ask, so we open the main app to request it.
 */
class RecordToggleActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)

        val micGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!micGranted) {
            // Can't request a permission from an invisible finishing activity cleanly —
            // open the app, which has the mic-permission flow and the record button.
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
            finish()
            overridePendingTransition(0, 0)
            return
        }

        if (RecState.recording) RecorderService.stop(this) else RecorderService.start(this)
        TranscriberWidgetProvider.refresh(this)

        finish()
        overridePendingTransition(0, 0)
    }
}
