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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
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
private val Case      = Color(0xFFE7D9B8)   // cream plastic body
private val Lid       = Color(0xFFEFE3C6)   // ivory lid (a touch lighter than body)
private val LidEdge   = Color(0xFFCBB686)   // lid bevel
private val CaseEdge  = Color(0xFF6B4F34)   // brown edge
private val Ink       = Color(0xFF3A2A1A)   // dark-brown text
private val Dim       = Color(0xFF7A6A52)
private val Window    = Color(0xFF1C140F)   // cutout window interior
private val Reel      = Color(0xFFEADFC4)   // reel spokes
private val Tape      = Color(0xFF4A3420)   // wound tape
private val Amber     = Color(0xFFD9A441)
private val Rec       = Color(0xFFC42B1C)   // record red
private val Lcd       = Color(0xFF9FB08A)   // LCD green-grey
private val LcdInk    = Color(0xFF223018)
private val SegOff    = Color(0x33000000)
private val SegGreen  = Color(0xFF4E7A3A)
private val KeyCream  = Color(0xFFD9C7A1)   // piano-key face
private val Gloss     = Color(0x33FFFFFF)   // plastic highlight
private val GlossSoft = Color(0x1AFFFFFF)
private val Shade     = Color(0x33000000)   // ambient shade

private fun Color.darken(f: Float = 0.80f) = Color(red * f, green * f, blue * f, alpha)
private fun Color.lighten(f: Float = 0.14f) =
    Color(red + (1 - red) * f, green + (1 - green) * f, blue + (1 - blue) * f, alpha)

// reusable vertical "moulded plastic" brush: lit top, body, shaded bottom
private fun plastic(c: Color) = Brush.verticalGradient(
    0f to c.lighten(0.20f), 0.5f to c, 1f to c.darken(0.86f)
)

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
    var showAbout by remember { mutableStateOf(false) }
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

    if (showAbout) {
        AlertDialog(
            containerColor = Case,
            onDismissRequest = { showAbout = false },
            title = { Text("Digimax Transcriber", color = Ink) },
            text = {
                Text(
                    "Records and transcribes on-device. No internet permission — nothing " +
                    "leaves the phone. Transcripts open in your own email app. " +
                    "Keeps rolling with the screen locked.",
                    color = Dim, fontSize = 13.sp
                )
            },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("Close", color = Rec) } }
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

    // desk background with a soft top-lit gradient
    val deskBrush = Brush.verticalGradient(listOf(Desk.lighten(0.10f), Desk, Desk.darken(0.90f)))
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().background(deskBrush)
                .verticalScroll(rememberScrollState()).padding(18.dp)
        ) {

            // top strip — logo now lives on the lid, so this is just the model line
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text("PORTABLE TRANSCRIBER", color = Dim, fontSize = 10.sp, letterSpacing = 2.sp)
            }
            Spacer(Modifier.height(12.dp))

            // ---- the deck ----
            Column(
                Modifier.fillMaxWidth()
                    .shadow(12.dp, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp))
                    .background(Brush.verticalGradient(listOf(Case.lighten(0.12f), Case, Case.darken(0.92f))))
                    .border(2.dp, Brush.verticalGradient(listOf(CaseEdge.lighten(0.3f), CaseEdge)), RoundedCornerShape(20.dp))
                    .padding(16.dp)
            ) {
                CassetteLid(recording = recording, angle = angle)
                Spacer(Modifier.height(16.dp))

                // COUNTER + VU row
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LcdBox(fmt(elapsed))
                        Spacer(Modifier.height(3.dp))
                        Text("COUNTER", color = Dim, fontSize = 8.sp, letterSpacing = 2.sp)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        VuMeter(vu)
                        Spacer(Modifier.height(3.dp))
                        Text("VU", color = Dim, fontSize = 8.sp, letterSpacing = 2.sp)
                    }
                }

                Spacer(Modifier.height(18.dp))
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
                Spacer(Modifier.height(10.dp))
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
                    .shadow(6.dp, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.verticalGradient(listOf(Case.lighten(0.08f), Case)))
                    .border(1.dp, CaseEdge.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                    .padding(16.dp)
            ) {
                Text("TAPES", color = Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 2.sp)
                Spacer(Modifier.height(6.dp))
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

            Spacer(Modifier.height(14.dp))

            // ---- bottom nav ----
            HorizontalDivider(color = Rec.copy(alpha = 0.5f))
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically) {
                NavItem("TAPES", active = true) { expanded = null }
                NavItem("SETTINGS", active = false) { pendingEmailWav = null; showEmailDialog = true }
                NavItem("ABOUT", active = false) { showAbout = true }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Closed opaque ivory lid: hinges, DIGIMAX logo, and a middle-band window onto the reels. */
@Composable
private fun CassetteLid(recording: Boolean, angle: Float) {
    Column(
        Modifier.fillMaxWidth()
            .shadow(5.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(Lid.lighten(0.10f), Lid, LidEdge.lighten(0.25f))))
            .border(1.5.dp, Brush.verticalGradient(listOf(Lid.lighten(0.3f), LidEdge)), RoundedCornerShape(14.dp))
            .padding(top = 10.dp, bottom = 16.dp, start = 16.dp, end = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // hinge tabs
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            repeat(2) {
                Box(Modifier.width(40.dp).height(8.dp)
                    .background(
                        Brush.verticalGradient(listOf(LidEdge.darken(0.9f), LidEdge)),
                        RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp)
                    ))
            }
        }
        Spacer(Modifier.height(12.dp))

        // logo on the lid
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(28.dp)
                    .shadow(2.dp, RoundedCornerShape(6.dp))
                    .background(Brush.verticalGradient(listOf(Rec.lighten(0.18f), Rec, Rec.darken(0.85f))), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("D", color = Lid, fontWeight = FontWeight.Black, fontSize = 20.sp)
            }
            Spacer(Modifier.width(7.dp))
            Text("DIGIMAX", color = Rec, fontWeight = FontWeight.Black, fontSize = 22.sp, letterSpacing = 1.sp)
        }
        Spacer(Modifier.height(12.dp))

        // middle-band window onto the reels
        Box(
            Modifier.fillMaxWidth().height(118.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Brush.verticalGradient(listOf(Window.lighten(0.14f), Window, Window.darken(0.75f))))
                .border(2.dp, Brush.verticalGradient(listOf(LidEdge, LidEdge.darken(0.8f))), RoundedCornerShape(10.dp))
        ) {
            Canvas(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp)) {
                val w = size.width; val h = size.height
                val r = h * 0.40f
                val ly = h * 0.52f
                val lx = w * 0.27f; val rx = w * 0.73f
                // exposed tape between reels
                drawLine(Tape, Offset(lx, ly), Offset(rx, ly), strokeWidth = h * 0.05f)
                listOf(lx, rx).forEach { cx ->
                    val c = Offset(cx, ly)
                    // wound tape with radial shading
                    drawCircle(
                        Brush.radialGradient(
                            listOf(Tape.lighten(0.12f), Tape, Tape.darken(0.7f)),
                            center = c, radius = r
                        ), radius = r, center = c
                    )
                    // hub, lit from top-left
                    drawCircle(
                        Brush.radialGradient(
                            listOf(Reel.lighten(0.2f), Reel, Reel.darken(0.82f)),
                            center = Offset(cx - r * 0.15f, ly - r * 0.15f), radius = r * 0.5f
                        ), radius = r * 0.34f, center = c
                    )
                    // spokes rotating
                    val spokes = 6
                    for (k in 0 until spokes) {
                        val a = Math.toRadians((angle + k * 360f / spokes).toDouble())
                        drawLine(
                            Reel,
                            Offset(cx + cos(a).toFloat() * r * 0.34f, ly + sin(a).toFloat() * r * 0.34f),
                            Offset(cx + cos(a).toFloat() * r * 0.92f, ly + sin(a).toFloat() * r * 0.92f),
                            strokeWidth = h * 0.035f
                        )
                    }
                    drawCircle(Reel, radius = r, center = c, style = Stroke(width = 2f))
                    drawCircle(Reel, radius = r * 0.18f, center = c)
                    // specular highlight arc, top-left
                    drawCircle(Gloss, radius = r * 0.9f, center = c,
                        style = Stroke(width = h * 0.02f))
                }
            }
            // diagonal plastic gloss over the top of the window
            Box(
                Modifier.fillMaxWidth().fillMaxHeight(0.5f).align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(GlossSoft, Color.Transparent)))
            )
            // REC lamp with glow
            if (recording) Box(
                Modifier.align(Alignment.TopEnd).padding(10.dp).size(11.dp)
                    .shadow(6.dp, RoundedCornerShape(6.dp), ambientColor = Rec, spotColor = Rec)
                    .background(Brush.radialGradient(listOf(Rec.lighten(0.3f), Rec)), RoundedCornerShape(6.dp))
            )
        }
    }
}

@Composable
private fun LcdBox(text: String) {
    Box(
        Modifier.clip(RoundedCornerShape(5.dp))
            .background(Brush.verticalGradient(listOf(Lcd.darken(0.9f), Lcd.lighten(0.08f), Lcd.darken(0.92f))))
            .border(1.dp, LcdInk.copy(alpha = 0.5f), RoundedCornerShape(5.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(text, color = LcdInk, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = 2.sp)
    }
}

@Composable
private fun VuMeter(level: Float) {
    Canvas(Modifier.fillMaxWidth().height(22.dp)) {
        val n = 22; val gap = 3f
        val segW = (size.width - gap * (n - 1)) / n
        val lit = (level.coerceIn(0f, 1f) * n).toInt()
        for (i in 0 until n) {
            val c = when {
                i >= n * 0.85 -> Rec
                i >= n * 0.60 -> Amber
                else -> SegGreen
            }
            val x = i * (segW + gap)
            if (i < lit) {
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(c.lighten(0.35f), c, c.darken(0.8f))),
                    topLeft = Offset(x, 0f), size = Size(segW, size.height),
                    cornerRadius = CornerRadius(2f, 2f)
                )
            } else {
                drawRoundRect(
                    color = SegOff, topLeft = Offset(x, 0f), size = Size(segW, size.height),
                    cornerRadius = CornerRadius(2f, 2f)
                )
            }
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
private fun NavItem(label: String, active: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clickable { onClick() }.padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(22.dp).background(
            if (active) Rec else Dim.copy(alpha = 0.5f), RoundedCornerShape(4.dp)
        ))
        Spacer(Modifier.height(4.dp))
        Text(label, color = if (active) Rec else Dim, fontSize = 10.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, letterSpacing = 1.sp)
    }
}

@Composable
private fun Transport(
    recording: Boolean, paused: Boolean, busy: Boolean,
    onRec: () -> Unit, onPause: () -> Unit, onStop: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TKey(Modifier.weight(1f), "REC", "●", sunk = recording && !paused, color = Rec,
            glyphColor = Color.White, enabled = !recording && !busy, onClick = onRec)
        TKey(Modifier.weight(1f), "PAUSE", "II", sunk = paused, color = KeyCream,
            glyphColor = Ink, enabled = recording && !busy, onClick = onPause)
        TKey(Modifier.weight(1f), "STOP", "■", sunk = false, color = KeyCream,
            glyphColor = Ink, enabled = recording && !busy, onClick = onStop)
    }
}

/**
 * Flat mechanical lever: hinged at its TOP edge, the free bottom edge angles down
 * into the chassis. Pressed = tilts further down + a deeper drop shadow in the slot.
 * The face is a moulded-plastic gradient with a top highlight and a shaded front lip.
 */
@Composable
private fun TKey(
    modifier: Modifier, label: String, glyph: String, sunk: Boolean,
    color: Color, glyphColor: Color, enabled: Boolean, onClick: () -> Unit
) {
    val faceColor = if (sunk) color.darken() else color
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Ink, fontSize = 9.sp, maxLines = 1)
        Spacer(Modifier.height(5.dp))
        // dark recess the lever sits in (inner shadow look)
        Box(
            Modifier.fillMaxWidth().height(64.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Brush.verticalGradient(listOf(CaseEdge.darken(0.7f), CaseEdge, CaseEdge.lighten(0.15f))))
                .padding(top = 3.dp, start = 3.dp, end = 3.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                Modifier.fillMaxWidth().height(52.dp)
                    .shadow(
                        elevation = if (sunk) 14.dp else 5.dp,
                        shape = RoundedCornerShape(7.dp), clip = false,
                        ambientColor = Shade, spotColor = Shade
                    )
                    .graphicsLayer {
                        // pivot on the top edge; negative rotationX dips the bottom edge back/down
                        transformOrigin = TransformOrigin(0.5f, 0f)
                        rotationX = if (sunk) -26f else -13f
                        cameraDistance = 14f * density
                    }
                    .clip(RoundedCornerShape(7.dp))
                    .background(plastic(faceColor))
                    .clickable(enabled = enabled) { onClick() }
            ) {
                // top specular highlight
                Box(
                    Modifier.fillMaxWidth().fillMaxHeight(0.42f).align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(listOf(Gloss, Color.Transparent)),
                            RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp)
                        )
                )
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.weight(1f))
                    Text(glyph, color = glyphColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(5.dp))
                    // front lip: a shaded band to read as the lever's leading face
                    Box(Modifier.fillMaxWidth().height(5.dp)
                        .background(Brush.verticalGradient(listOf(faceColor.darken(0.78f), faceColor.darken(0.6f)))))
                }
            }
        }
    }
}
