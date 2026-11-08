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
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// ---- palette (matches the deck render) ----
private val DeckBg  = Color(0xFFE4D2BC)   // cream paper, same as the deck art — seamless under it
private val Case    = Color(0xFFE7D9B8)   // dialog surface
private val Ink     = Color(0xFF2A1F14)   // dark-brown text
private val Dim     = Color(0xFF7A6A52)
private val Rec     = Color(0xFFB4342A)   // Digimax record red
private val LcdInk  = Color(0xFF22311A)   // counter digits on the green glass

private const val DECK_AR = 1024f / 1240f   // deck_top.webp
private const val NAV_AR  = 1024f / 132f    // nav_bar.webp

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
    val paused = RecState.paused

    var elapsed by remember { mutableStateOf(0L) }
    var tick by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var lastDone by remember { mutableStateOf<String?>(null) }
    var destEmail by remember { mutableStateOf(Prefs.getEmail(ctx)) }
    var showEmailDialog by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var pendingEmailWav by remember { mutableStateOf<File?>(null) }
    var detailWav by remember { mutableStateOf<File?>(null) }
    var deleteTarget by remember { mutableStateOf<File?>(null) }

    var update by remember { mutableStateOf<Updater.Update?>(null) }
    var updating by remember { mutableStateOf(false) }
    var dlPct by remember { mutableStateOf(0) }

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
                if (!RecState.paused) angle = (angle + 6f + l * 26f) % 360f
                delay(33)
            }
        } else { elapsed = 0; vu = 0f; tick++ }
    }

    LaunchedEffect(Unit) { update = Updater.check() }

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
            txtOf(wav).writeText(text); status = "Transcribed."
            if (destEmail.isBlank()) { pendingEmailWav = null; showEmailDialog = true }
        } else status = "No speech detected."
        tick++
    }

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

    // ---------- dialogs ----------
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
                Column {
                    Text(
                        "Records and transcribes on-device — your audio never leaves the phone. " +
                        "The only time it reaches the internet is to check for and download app " +
                        "updates. Transcripts open in your own email app. Keeps rolling with the " +
                        "screen locked.",
                        color = Dim, fontSize = 13.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("Version ${BuildConfig.VERSION_NAME}", color = Dim, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val u = Updater.check()
                        if (u != null) { update = u; showAbout = false }
                        else status = "You're on the latest version."
                    }
                }) { Text("Check for updates", color = Rec) }
            },
            dismissButton = { TextButton(onClick = { showAbout = false }) { Text("Close", color = Dim) } }
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
                    if (detailWav == wav) detailWav = null
                    deleteTarget = null; tick++
                }) { Text("Delete", color = Rec) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel", color = Dim) } }
        )
    }

    detailWav?.let { wav ->
        val txt = txtOf(wav)
        val emailed = emailedOf(wav).exists()
        AlertDialog(
            containerColor = Case,
            onDismissRequest = { detailWav = null },
            title = { Text(stamp.format(Date(wav.lastModified())), color = Ink) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("${wav.length() / 1024} KB · " +
                        when { emailed -> "EMAILED"; txt.exists() -> "TRANSCRIBED"; else -> "RECORDED" },
                        color = if (emailed) Rec else Dim, fontSize = 11.sp, letterSpacing = 1.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(if (txt.exists()) txt.readText() else "Not transcribed yet.", color = Ink, fontSize = 14.sp)
                }
            },
            confirmButton = {
                if (txt.exists())
                    TextButton(enabled = !busy, onClick = { emailRec(wav) }) { Text("Email", color = Rec, fontWeight = FontWeight.Bold) }
                else
                    TextButton(enabled = !busy, onClick = { scope.launch { lastDone = wav.absolutePath; transcribe(wav) } }) { Text("Transcribe", color = Rec, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { deleteTarget = wav }) { Text("Delete", color = Dim) }
                    TextButton(onClick = { detailWav = null }) { Text("Close", color = Dim) }
                }
            }
        )
    }

    // ---------- layout: photoreal deck (top) · tape library (fills) · nav (bottom) ----------
    val recPulse by rememberInfiniteTransition(label = "rec").animateFloat(
        0.25f, 0.95f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "recPulse"
    )
    val d = LocalDensity.current

    Column(Modifier.fillMaxSize().background(DeckBg)) {

        // ---- DECK (fixed aspect, overlays positioned by fraction) ----
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wPx = constraints.maxWidth.toFloat()
            val hPx = wPx / DECK_AR

            @Composable
            fun rectMod(fx0: Float, fy0: Float, fx1: Float, fy1: Float): Modifier {
                val x = fx0 * wPx; val y = fy0 * hPx
                val w = (fx1 - fx0) * wPx; val h = (fy1 - fy0) * hPx
                return Modifier
                    .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                    .size(with(d) { w.toDp() }, with(d) { h.toDp() })
            }

            Image(
                painter = painterResource(R.drawable.deck_top),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth()
            )

            // spinning cassette hubs — big photoreal hubs, clipped to the reel window so they
            // sit UNDER the cassette's window band (not floating on top of the layout)
            val hubPx = 0.20f * wPx
            val clipL = 0.168f * wPx; val clipT = 0.408f * hPx
            val clipW = 0.655f * wPx; val clipH = 0.108f * hPx
            Box(
                Modifier
                    .offset { IntOffset(clipL.roundToInt(), clipT.roundToInt()) }
                    .size(with(d) { clipW.toDp() }, with(d) { clipH.toDp() })
                    .clip(RoundedCornerShape(4.dp))
            ) {
                listOf(0.3755f to 0.4883f, 0.6255f to 0.4879f).forEach { (fcx, fcy) ->
                    Image(
                        painter = painterResource(R.drawable.hub), contentDescription = null,
                        modifier = Modifier
                            .offset { IntOffset((fcx * wPx - clipL - hubPx / 2f).roundToInt(), (fcy * hPx - clipT - hubPx / 2f).roundToInt()) }
                            .size(with(d) { hubPx.toDp() })
                            .graphicsLayer { rotationZ = angle }
                    )
                }
            }

            // live VU over the baked meter strip
            VuBar(rectMod(0.440f, 0.664f, 0.887f, 0.720f), vu)

            // COUNTER digits
            Box(rectMod(0.115f, 0.675f, 0.355f, 0.737f), contentAlignment = Alignment.Center) {
                Text(fmt(elapsed), color = LcdInk, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, fontSize = with(d) { (hPx * 0.026f).toSp() }, letterSpacing = 2.sp)
            }

            // transport keys
            KeyZone(rectMod(0.065f, 0.799f, 0.365f, 0.964f), enabled = !recording && !busy,
                sunk = recording && !paused, highlight = if (recording && !paused) recPulse else 0f) {
                if (!RecState.recording) RecorderService.start(ctx)
            }
            KeyZone(rectMod(0.375f, 0.799f, 0.625f, 0.964f), enabled = recording && !busy,
                sunk = paused, highlight = if (paused) recPulse else 0f) {
                if (RecState.recording) { if (RecState.paused) RecorderService.resume(ctx) else RecorderService.pause(ctx) }
            }
            KeyZone(rectMod(0.635f, 0.799f, 0.930f, 0.964f), enabled = recording && !busy,
                sunk = false, highlight = 0f) {
                if (RecState.recording) RecorderService.stop(ctx)
            }

            // update banner — floats over the top paper margin
            update?.let { up ->
                Column(
                    Modifier.align(Alignment.TopCenter).padding(top = 8.dp, start = 14.dp, end = 14.dp)
                        .zIndex(2f).clip(RoundedCornerShape(12.dp)).background(Rec).padding(12.dp)
                ) {
                    Text("Update ${up.name} available", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    if (up.notes.isNotBlank()) { Spacer(Modifier.height(2.dp)); Text(up.notes, color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp) }
                    Spacer(Modifier.height(10.dp))
                    if (updating) {
                        LinearProgressIndicator(progress = { dlPct / 100f }, color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.3f), modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp)); Text("Downloading… $dlPct%", color = Color.White, fontSize = 11.sp)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                scope.launch {
                                    updating = true; dlPct = 0
                                    val f = Updater.download(ctx, up) { dlPct = it }
                                    updating = false
                                    if (f != null) Updater.install(ctx, f) else status = "Update download failed — try again."
                                }
                            }, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Rec),
                                shape = RoundedCornerShape(8.dp)) { Text("Update", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                            OutlinedButton(onClick = { update = null }, shape = RoundedCornerShape(8.dp)) {
                                Text("Later", color = Color.White, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }

        // ---- status line ----
        val line = when {
            recording && paused -> "II  paused"
            recording -> "●  REC · rolling — lock the phone, it keeps going"
            status.isNotBlank() -> status
            else -> ""
        }
        Text(
            line, color = if (recording && !paused) Rec else Dim, fontSize = 11.sp,
            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp)
        )

        // ---- TAPES library (fills remaining space) ----
        Column(Modifier.weight(1f).fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 2.dp)) {
            Text("TAPES", color = Rec, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(5.dp))
            HorizontalDivider(color = Rec.copy(alpha = 0.6f))
            if (files.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("No tapes yet — press REC.", color = Dim, fontSize = 13.sp)
            } else {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    files.forEachIndexed { i, wav ->
                        if (i > 0) HorizontalDivider(color = Rec.copy(alpha = 0.12f))
                        TapeRow(i + 1, wav, 15.sp, 11.sp) { detailWav = wav }
                    }
                }
            }
        }

        // ---- NAV bar (static art + tap thirds), pinned to the bottom ----
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val navH = with(d) { (constraints.maxWidth.toFloat() / NAV_AR).toDp() }
            Image(
                painter = painterResource(R.drawable.nav_bar),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth().height(navH)) {
                Box(Modifier.weight(1f).fillMaxHeight().clickable { detailWav = null })
                Box(Modifier.weight(1f).fillMaxHeight().clickable { pendingEmailWav = null; showEmailDialog = true })
                Box(Modifier.weight(1f).fillMaxHeight().clickable { showAbout = true })
            }
        }
    }
}

/** Transparent hit-target over a baked key. Darkens on press or while its state is active (sunk). */
@Composable
private fun KeyZone(modifier: Modifier, enabled: Boolean, sunk: Boolean, highlight: Float, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Box(
        modifier.clickable(interactionSource = src, indication = null, enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (pressed || sunk) Box(Modifier.matchParentSize().clip(RoundedCornerShape(12.dp)).background(Color(0x40000000)))
        if (highlight > 0f) Box(
            Modifier.matchParentSize().clip(RoundedCornerShape(12.dp))
                .border(2.dp, Rec.copy(alpha = highlight), RoundedCornerShape(12.dp))
        )
    }
}

private val HubGrey  = Color(0xFFCEC9C3)
private val HubGreyLo = Color(0xFFB2ADA4)
private val HubEdge  = Color(0xFFB0AAA2)
private val HubSocket = Color(0xFF342E28)
private val HubSpindle = Color(0xFF1A1613)
private val HubSpindleHi = Color(0xFF5F5952)
private val TapeDk  = Color(0xFF3C2A1C)
private val TapeLt  = Color(0xFF5A4030)

/** The exposed tape strip across the head, scrolling while recording. */
@Composable
private fun TapeBand(modifier: Modifier, shift: Float, moving: Boolean) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawRect(Brush.verticalGradient(listOf(TapeLt, TapeDk, Color(0xFF241810))))
        // fine vertical tape striations, scrolling horizontally with `shift`
        val sp = w * 0.055f
        var x = (shift % sp) - sp
        while (x < w) {
            drawLine(Color(0x33000000), Offset(x, 0f), Offset(x, h), strokeWidth = 1.5f)
            drawLine(Color(0x1AFFFFFF), Offset(x + sp * 0.5f, 0f), Offset(x + sp * 0.5f, h), strokeWidth = 1f)
            x += sp
        }
        // top highlight (light catching the moving tape)
        drawRect(Brush.verticalGradient(listOf(Color(0x3AFFFFFF), Color.Transparent)), size = Size(w, h * 0.4f))
    }
}

/** A grey cassette hub with a 6-tooth splined drive socket, drawn over the baked hub (tight fit) and rotated. */
@Composable
private fun HubWheel(modifier: Modifier, angle: Float) {
    Canvas(modifier.graphicsLayer { rotationZ = angle }) {
        val cx = size.width / 2f; val cy = size.height / 2f
        val R = size.minDimension / 2f
        fun pt(rad: Float, a: Double) = Offset(cx + (rad * cos(a)).toFloat(), cy + (rad * sin(a)).toFloat())
        // grey hub body (covers the baked hub), flat so spinning shows no lighting seam
        drawCircle(Brush.radialGradient(listOf(HubGrey, HubGrey, HubGreyLo), center = Offset(cx, cy), radius = R), radius = R, center = Offset(cx, cy))
        drawCircle(HubEdge, radius = R * 0.93f, center = Offset(cx, cy), style = Stroke(width = R * 0.04f))
        // dark recessed socket the teeth sit in
        drawCircle(HubSocket, radius = R * 0.62f, center = Offset(cx, cy))
        // 6 thin inward teeth — symmetric
        val n = 6
        for (i in 0 until n) {
            val a = i * 2.0 * PI / n; val wv = 0.12
            val o1 = pt(R * 0.62f, a - wv); val o2 = pt(R * 0.62f, a + wv)
            val v1 = pt(R * 0.32f, a + wv); val v2 = pt(R * 0.32f, a - wv)
            val p = Path()
            p.moveTo(o1.x, o1.y); p.lineTo(o2.x, o2.y); p.lineTo(v1.x, v1.y); p.lineTo(v2.x, v2.y); p.close()
            drawPath(p, HubGrey)
        }
        // central black round drive spindle that grabs the teeth
        drawCircle(HubSpindle, radius = R * 0.28f, center = Offset(cx, cy))
        drawCircle(HubSpindleHi, radius = R * 0.28f, center = Offset(cx, cy), style = Stroke(width = R * 0.03f))
        drawCircle(HubSpindleHi, radius = R * 0.09f, center = Offset(cx, cy))
    }
}

/** Live VU: keeps the baked meter art and just dims the segments above the level. */
@Composable
private fun VuBar(modifier: Modifier, level: Float) {
    Canvas(modifier) {
        val n = 16
        val lit = (level.coerceIn(0f, 1f) * n).toInt()
        if (lit < n) {                                               // darken the unlit segments; lit ones show the baked colour
            val x = size.width * (lit.toFloat() / n)
            drawRect(Color(0xD8140E09), topLeft = Offset(x, 0f), size = Size(size.width - x, size.height))
        }
    }
}

/** One compact row in the TAPES panel — red index, timestamp, state, chevron. */
@Composable
private fun TapeRow(n: Int, wav: File, title: TextUnit, sub: TextUnit, onClick: () -> Unit) {
    val txt = txtOf(wav)
    val emailed = emailedOf(wav).exists()
    val state = when { emailed -> "EMAILED"; txt.exists() -> "TRANSCRIBED"; else -> "RECORDED" }
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$n", color = Rec, fontWeight = FontWeight.Bold, fontSize = title)
        Spacer(Modifier.width(12.dp))
        Text(stamp.format(Date(wav.lastModified())), color = Ink, fontSize = title,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(state, color = if (emailed) Rec else Dim, fontSize = sub, letterSpacing = 1.sp)
        Spacer(Modifier.width(8.dp))
        Text("›", color = Rec, fontSize = title)
    }
}
