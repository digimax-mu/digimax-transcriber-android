package com.digimax.transcriber

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

// ---- full-retro cassette palette ----
private val Desk      = Color(0xFFBFA373)   // tan "desk" background
private val Case      = Color(0xFFE7D9B8)   // cream plastic
private val CaseEdge  = Color(0xFF6B4F34)   // brown edge
private val Ink       = Color(0xFF3A2A1A)   // dark-brown text
private val Dim       = Color(0xFF7A6A52)
private val Window    = Color(0xFF221812)   // cassette window
private val Reel      = Color(0xFFEADFC4)   // reel spokes
private val Tape      = Color(0xFF4A3420)   // wound tape
private val Amber     = Color(0xFFD9A441)
private val Rec       = Color(0xFFC42B1C)   // record red
private val Lcd       = Color(0xFF9FB08A)   // LCD green-grey
private val LcdInk    = Color(0xFF223018)
private val SegOff    = Color(0x33000000)
private val SegGreen  = Color(0xFF4E7A3A)
private val KeyCream  = Color(0xFFD9C7A1)   // piano-key face
private fun Color.darken(f: Float = 0.80f) = Color(red * f, green * f, blue * f, alpha)

private fun txtOf(wav: File) = File(wav.parentFile, wav.nameWithoutExtension + ".txt")
private fun emailedOf(wav: File) = File(wav.parentFile, wav.nameWithoutExtension + ".emailed")
private fun fmt(s: Long) = "%02d:%02d".format(s / 60, s % 60)
private val stamp = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())

class MainActivity : ComponentActivity() {
    private val perms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        val missing = need.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) perms.launch(missing.toTypedArray())
        setContent { App() }
    }
}

@Composable
private fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val recording = RecState.recording

    var elapsed by remember { mutableStateOf(0L) }
    var tick by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("Press REC. Lock the phone — it keeps rolling.") }
    var busy by remember { mutableStateOf(false) }
    var lastDone by remember { mutableStateOf<String?>(null) }
    var destEmail by remember { mutableStateOf(Prefs.getEmail(ctx)) }
    var showEmailDialog by remember { mutableStateOf(false) }
    var pendingEmailWav by remember { mutableStateOf<File?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<File?>(null) }

    // reel rotation + smoothed VU, driven by the live mic level
    var angle by remember { mutableStateOf(0f) }
    var vu by remember { mutableStateOf(0f) }
    LaunchedEffect(recording) {
        if (recording) {
            var acc = 0L; var last = System.currentTimeMillis()
            while (RecState.recording) {
                val now = System.currentTimeMillis()
                if (!RecState.paused) acc += now - last
                last = now
                elapsed = acc / 1000
                val l = if (RecState.paused) 0f else RecState.level
                vu = if (l > vu) l else vu * 0.82f
                if (!RecState.paused) angle = (angle + 2.5f + l * 14f) % 360f
                delay(33)
            }
        } else {
            vu = 0f; elapsed = 0; tick++
        }
    }

    fun emailRec(wav: File) {
        val txt = txtOf(wav)
        if (!txt.exists()) return
        if (destEmail.isBlank()) { pendingEmailWav = wav; showEmailDialog = true; return }
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(destEmail))
            putExtra(Intent.EXTRA_SUBJECT, "Transcript — " + stamp.format(Date(wav.lastModified())))
            putExtra(Intent.EXTRA_TEXT, txt.readText())
        }
        runCatching {
            ctx.startActivity(Intent.createChooser(intent, "Email transcript"))
            emailedOf(wav).writeText("1"); tick++
        }.onFailure { status = "No email app found." }
    }

    suspend fun transcribe(wav: File) {
        if (!wav.exists() || wav.length() <= 44L) { status = "Nothing recorded."; return }
        busy = true; status = "Transcribing on-device…"
        val text = runCatching { Transcriber.transcribe(ctx, wav) }.getOrElse { "Error: ${it.message}" }
        busy = false
        if (text.isNotBlank()) {
            txtOf(wav).writeText(text); expanded = wav.name; status = "Transcribed."
            if (destEmail.isBlank()) { pendingEmailWav = null; showEmailDialog = true }
        } else status = "No speech detected."
        tick++
    }

    // transcribe automatically once a recording finishes
    LaunchedEffect(recording) {
        if (!recording) {
            val f = RecState.lastFile
            if (f != null && f != lastDone) { lastDone = f; transcribe(File(f)) }
        }
    }

    val files = remember(tick) {
        File(ctx.filesDir, "recordings").listFiles()
            ?.filter { it.name.endsWith(".wav") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    if (showEmailDialog) {
        var draft by remember { mutableStateOf(destEmail) }
        AlertDialog(
            containerColor = Case,
            onDismissRequest = { showEmailDialog = false; pendingEmailWav = null },
            title = { Text("Send transcripts to", color = Ink) },
            text = {
                OutlinedTextField(draft, { draft = it }, singleLine = true,
                    placeholder = { Text("you@example.com") })
            },
            confirmButton = {
                TextButton(onClick = {
                    destEmail = draft.trim(); Prefs.setEmail(ctx, destEmail)
                    showEmailDialog = false
                    val w = pendingEmailWav; pendingEmailWav = null
                    if (destEmail.isNotBlank() && w != null) emailRec(w)
                }) { Text("Save", color = Rec) }
            },
            dismissButton = { TextButton(onClick = { showEmailDialog = false; pendingEmailWav = null }) { Text("Cancel", color = Dim) } }
        )
    }

    deleteTarget?.let { wav ->
        AlertDialog(
            containerColor = Case,
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete tape?", color = Ink) },
            text = { Text("Removes the audio and its transcript from this phone.", color = Dim) },
            confirmButton = {
                TextButton(onClick = {
                    wav.delete(); txtOf(wav).delete(); emailedOf(wav).delete()
                    if (expanded == wav.name) expanded = null
                    deleteTarget = null; tick++
                }) { Text("Delete", color = Rec) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel", color = Dim) } }
        )
    }

    Surface(color = Desk, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {

            Text("DIGIMAX", color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 4.sp)
            Text("PORTABLE TRANSCRIBER · MOD 4", color = Dim, fontSize = 10.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(14.dp))

            // ---- the deck ----
            Column(
                Modifier.fillMaxWidth()
                    .background(Case, RoundedCornerShape(18.dp))
                    .border(2.dp, CaseEdge, RoundedCornerShape(18.dp))
                    .padding(16.dp)
            ) {
                CassetteWindow(recording = recording, angle = angle, elapsed = elapsed)
                Spacer(Modifier.height(14.dp))
                Text("REC LEVEL", color = Dim, fontSize = 9.sp, letterSpacing = 2.sp)
                Spacer(Modifier.height(4.dp))
                VuMeter(vu)
                Spacer(Modifier.height(16.dp))
                Transport(
                    recording = recording,
                    paused = RecState.paused,
                    busy = busy,
                    onRec = { if (!RecState.recording) RecorderService.start(ctx) },
                    onPause = {
                        if (RecState.recording)
                            if (RecState.paused) RecorderService.resume(ctx) else RecorderService.pause(ctx)
                    },
                    onStop = { if (RecState.recording) RecorderService.stop(ctx) }
                )
                Spacer(Modifier.height(8.dp))
                val line = when {
                    recording && RecState.paused -> "II  paused"
                    recording -> "●  REC · rolling"
                    else -> status
                }
                Text(line, color = if (recording && !RecState.paused) Rec else if (recording) Amber else Dim, fontSize = 12.sp)
            }

            Spacer(Modifier.height(16.dp))

            // ---- tape library ----
            Column(
                Modifier.fillMaxWidth()
                    .background(Case, RoundedCornerShape(14.dp))
                    .border(1.dp, CaseEdge.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                    .padding(16.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("TAPES", color = Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 2.sp)
                    if (destEmail.isNotBlank())
                        TextButton(onClick = { pendingEmailWav = null; showEmailDialog = true }) {
                            Text("✉ $destEmail", fontSize = 11.sp, color = Dim)
                        }
                }
                if (files.isEmpty()) {
                    Text("No tapes yet.", color = Dim, fontSize = 13.sp)
                } else {
                    files.forEachIndexed { idx, wav ->
                        if (idx > 0) HorizontalDivider(color = CaseEdge.copy(alpha = 0.2f))
                        RecRow(wav, expanded == wav.name, busy,
                            onToggle = { expanded = if (expanded == wav.name) null else wav.name },
                            onTranscribe = { scope.launch { lastDone = wav.absolutePath; transcribe(wav) } },
                            onEmail = { emailRec(wav) },
                            onDelete = { deleteTarget = wav })
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("On-device · no internet. Stops when locked? Battery → Unrestricted.",
                color = Dim, fontSize = 11.sp)
        }
    }
}

@Composable
private fun CassetteWindow(recording: Boolean, angle: Float, elapsed: Long) {
    Box(Modifier.fillMaxWidth().height(170.dp).background(Window, RoundedCornerShape(12.dp))) {
        Canvas(Modifier.fillMaxSize().padding(14.dp)) {
            val w = size.width; val h = size.height
            val r = h * 0.30f
            val ly = h * 0.42f
            val lx = w * 0.30f; val rx = w * 0.70f
            // exposed tape between reels
            drawLine(Tape, Offset(lx, ly + r), Offset(rx, ly + r), strokeWidth = h * 0.04f)
            listOf(lx, rx).forEach { cx ->
                // wound tape
                drawCircle(Tape, radius = r, center = Offset(cx, ly))
                // hub
                drawCircle(Case, radius = r * 0.34f, center = Offset(cx, ly))
                // spokes rotating
                val spokes = 6
                for (k in 0 until spokes) {
                    val a = Math.toRadians((angle + k * 360f / spokes).toDouble())
                    drawLine(
                        Reel,
                        Offset(cx + cos(a).toFloat() * r * 0.34f, ly + sin(a).toFloat() * r * 0.34f),
                        Offset(cx + cos(a).toFloat() * r * 0.92f, ly + sin(a).toFloat() * r * 0.92f),
                        strokeWidth = h * 0.03f
                    )
                }
                drawCircle(Reel, radius = r, center = Offset(cx, ly), style = Stroke(width = 2f))
                drawCircle(Reel, radius = r * 0.18f, center = Offset(cx, ly))
            }
        }
        // REC lamp
        if (recording) Box(
            Modifier.align(Alignment.TopEnd).padding(10.dp).size(12.dp)
                .background(Rec, RoundedCornerShape(6.dp))
        )
        // LCD counter
        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
                .background(Lcd, RoundedCornerShape(5.dp)).padding(horizontal = 14.dp, vertical = 4.dp)
        ) {
            Text(fmt(elapsed), color = LcdInk, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = 2.sp)
        }
    }
}

@Composable
private fun VuMeter(level: Float) {
    Canvas(Modifier.fillMaxWidth().height(20.dp)) {
        val n = 22; val gap = 3f
        val segW = (size.width - gap * (n - 1)) / n
        val lit = (level.coerceIn(0f, 1f) * n).toInt()
        for (i in 0 until n) {
            val c = when {
                i >= n * 0.85 -> Rec
                i >= n * 0.60 -> Amber
                else -> SegGreen
            }
            drawRoundRect(
                color = if (i < lit) c else SegOff,
                topLeft = Offset(i * (segW + gap), 0f),
                size = Size(segW, size.height),
                cornerRadius = CornerRadius(2f, 2f)
            )
        }
    }
}

@Composable
private fun RecRow(
    wav: File, expanded: Boolean, busy: Boolean,
    onToggle: () -> Unit, onTranscribe: () -> Unit, onEmail: () -> Unit, onDelete: () -> Unit
) {
    val txt = txtOf(wav)
    val emailed = emailedOf(wav).exists()
    val state = when { emailed -> "EMAILED"; txt.exists() -> "TRANSCRIBED"; else -> "RECORDED" }
    Column(Modifier.fillMaxWidth().clickable { onToggle() }.padding(vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(stamp.format(Date(wav.lastModified())), color = Ink, fontSize = 14.sp)
                Text("${wav.length() / 1024} KB · $state", color = if (emailed) Rec else Dim,
                    fontSize = 11.sp, letterSpacing = 1.sp)
            }
            Text(if (expanded) "▲" else "▼", color = Dim, fontSize = 12.sp)
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Text(if (txt.exists()) txt.readText() else "Not transcribed yet.", color = Ink, fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (txt.exists()) {
                    Button(onClick = onEmail, enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = Rec, contentColor = Case),
                        shape = RoundedCornerShape(8.dp)) { Text("Email", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                } else {
                    Button(onClick = onTranscribe, enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Case),
                        shape = RoundedCornerShape(8.dp)) { Text("Transcribe", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                }
                OutlinedButton(onClick = onDelete, shape = RoundedCornerShape(8.dp)) { Text("Delete", fontSize = 13.sp, color = Rec) }
            }
        }
    }
}

@Composable
private fun Transport(
    recording: Boolean, paused: Boolean, busy: Boolean,
    onRec: () -> Unit, onPause: () -> Unit, onStop: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TKey(Modifier.weight(1f), "REC", "●", sunk = recording, color = Rec,
            glyphColor = Color.White, enabled = !recording && !busy, onClick = onRec)
        TKey(Modifier.weight(1f), "PAUSE", "II", sunk = paused, color = KeyCream,
            glyphColor = Ink, enabled = recording && !busy, onClick = onPause)
        TKey(Modifier.weight(1f), "STOP", "■", sunk = false, color = KeyCream,
            glyphColor = Ink, enabled = recording && !busy, onClick = onStop)
        TKey(Modifier.weight(1f), "REW", "◀◀", sunk = false, color = KeyCream,
            glyphColor = Dim, enabled = false, dim = true, onClick = {})
        TKey(Modifier.weight(1f), "F.FWD", "▶▶", sunk = false, color = KeyCream,
            glyphColor = Dim, enabled = false, dim = true, onClick = {})
    }
}

@Composable
private fun TKey(
    modifier: Modifier, label: String, glyph: String, sunk: Boolean,
    color: Color, glyphColor: Color, enabled: Boolean, dim: Boolean = false, onClick: () -> Unit
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = if (dim) Dim.copy(alpha = 0.6f) else Ink, fontSize = 9.sp, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.fillMaxWidth().height(52.dp).background(CaseEdge, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                Modifier.fillMaxWidth().height(47.dp)
                    .offset(y = if (sunk) 5.dp else 0.dp)
                    .background(
                        if (sunk) color.darken() else if (dim) color.copy(alpha = 0.55f) else color,
                        RoundedCornerShape(6.dp)
                    )
                    .clickable(enabled = enabled) { onClick() },
                contentAlignment = Alignment.Center
            ) {
                Text(glyph, color = glyphColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
