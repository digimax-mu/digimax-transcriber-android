package com.digimax.transcriber

import android.content.Context

/** One saved preference: the email address transcripts are sent to. Set once per phone. */
object Prefs {
    private const val FILE = "digimax-transcriber"
    private const val K_EMAIL = "dest_email"

    fun getEmail(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(K_EMAIL, "").orEmpty()

    fun setEmail(ctx: Context, email: String) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(K_EMAIL, email.trim()).apply()
}
