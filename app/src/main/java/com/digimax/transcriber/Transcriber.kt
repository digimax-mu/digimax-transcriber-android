package com.digimax.transcriber

import android.content.Context
import android.content.res.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileInputStream

/**
 * On-device speech-to-text with Vosk. Fully offline — the model is bundled in
 * the APK (assets/model) and copied to internal storage on first use.
 * Audio never leaves the phone.
 */
object Transcriber {

    private const val MODEL_ASSET = "model"          // app/src/main/assets/model/*
    private const val MARKER = "conf/mfcc.conf"      // a file that exists in the model

    /** Copy the bundled model out of assets (first run only), returns its dir. */
    private fun ensureModel(ctx: Context): File {
        val dst = File(ctx.filesDir, "vosk-model")
        if (File(dst, MARKER).exists()) return dst
        copyAsset(ctx.assets, MODEL_ASSET, dst)
        return dst
    }

    private fun copyAsset(am: AssetManager, path: String, dst: File) {
        val children = am.list(path) ?: emptyArray()
        if (children.isEmpty()) {
            // it's a file
            dst.parentFile?.mkdirs()
            am.open(path).use { input -> dst.outputStream().use { input.copyTo(it) } }
        } else {
            dst.mkdirs()
            for (c in children) copyAsset(am, "$path/$c", File(dst, c))
        }
    }

    /** Transcribe a 16 kHz mono PCM-16 WAV file to plain text. */
    suspend fun transcribe(ctx: Context, wav: File): String = withContext(Dispatchers.Default) {
        val modelDir = ensureModel(ctx)
        val model = Model(modelDir.absolutePath)
        val rec = Recognizer(model, 16_000.0f)
        val sb = StringBuilder()
        try {
            FileInputStream(wav).use { ins ->
                ins.skip(44)                      // WAV header
                val buf = ByteArray(8192)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    if (rec.acceptWaveForm(buf, n)) {
                        appendText(sb, rec.result)
                    }
                }
            }
            appendText(sb, rec.finalResult)
        } finally {
            rec.close()
            model.close()
        }
        sb.toString().trim()
    }

    private fun appendText(sb: StringBuilder, json: String) {
        val t = runCatching { JSONObject(json).optString("text", "") }.getOrDefault("")
        if (t.isNotBlank()) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(t)
        }
    }
}
