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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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

private val Red = Color(0xFFE3262E)
private val Ink = Color(0xFF111111)
private val Grey = Color(0xFF5A5A5A)
private val Bg = Color(0xFFFAFAFA)
private val Panel = Color(0xFFFFFFFF)
private val Line = Color(0xFFECECEC)

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
    var status by remember { mutableStateOf("Tap Record, then lock your phone.") }
    var busy by remember { mutableStateOf(false) }
    var lastDone by remember { mutableStateOf<String?>(null) }
    var destEmail by remember { mutableStateOf(Prefs.getEmail(ctx)) }
    var showEmailDialog by remember { mutableStateOf(false) }
    var pendingEmailWav by remember { mutableStateOf<File?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<File?>(null) }

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
            txtOf(wav).writeText(text)
            expanded = wav.name
            status = "Transcribed."
            if (destEmail.isBlank()) { pendingEmailWav = null; showEmailDialog = true }
        } else status = "No speech detected."
        tick++
    }

    LaunchedEffect(recording) {
        if (recording) {
            val t0 = System.currentTimeMillis()
            while (RecState.recording) { elapsed = (System.currentTimeMillis() - t0) / 1000; delay(500) }
        } else {
            elapsed = 0; tick++
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
            onDismissRequest = { showEmailDialog = false; pendingEmailWav = null },
            title = { Text("Send transcripts to") },
            text = {
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it },
                    singleLine = true, placeholder = { Text("you@example.com") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    destEmail = draft.trim(); Prefs.setEmail(ctx, destEmail)
                    showEmailDialog = false
                    val w = pendingEmailWav; pendingEmailWav = null
                    if (destEmail.isNotBlank() && w != null) emailRec(w)
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showEmailDialog = false; pendingEmailWav = null }) { Text("Cancel") } }
        )
    }

    deleteTarget?.let { wav ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete recording?") },
            text = { Text("This removes the audio and its transcript from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    wav.delete(); txtOf(wav).delete(); emailedOf(wav).delete()
                    if (expanded == wav.name) expanded = null
                    deleteTarget = null; tick++
                }) { Text("Delete", color = Red) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }

    Surface(color = Bg, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {

            Image(
                painterResource(R.drawable.dm_logo), contentDescription = "Digimax",
                modifier = Modifier.height(26.dp)
            )
            Spacer(Modifier.height(14.dp))
            Text("Transcriber", color = Ink, fontWeight = FontWeight.Bold, fontSize = 34.sp)
            Text("Record, transcribe on-device, email it. Nothing leaves your phone.", color = Grey, fontSize = 14.sp)
            Spacer(Modifier.height(18.dp))

            Panel {
                Button(
                    onClick = { if (RecState.recording) RecorderService.stop(ctx) else RecorderService.start(ctx) },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (recording) Ink else Red, contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().height(68.dp)
                ) { Text(if (recording) "■  Stop" else "●  Record", fontSize = 22.sp, fontWeight = FontWeight.Bold) }

                Spacer(Modifier.height(14.dp))
                Waveform(active = recording)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (recording) "● recording" else status, color = if (busy || recording) Red else Grey, fontSize = 13.sp)
                    Text(fmt(elapsed), color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }

            Spacer(Modifier.height(16.dp))
            Panel {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Recordings", color = Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    if (destEmail.isNotBlank())
                        TextButton(onClick = { pendingEmailWav = null; showEmailDialog = true }) { Text("✉ $destEmail", fontSize = 11.sp, color = Grey) }
                }
                if (files.isEmpty()) {
                    Text("None yet.", color = Grey, fontSize = 13.sp)
                } else {
                    files.forEachIndexed { idx, wav ->
                        if (idx > 0) HorizontalDivider(color = Line)
                        RecRow(
                            wav = wav,
                            expanded = expanded == wav.name,
                            busy = busy,
                            onToggle = { expanded = if (expanded == wav.name) null else wav.name },
                            onTranscribe = { scope.launch { lastDone = wav.absolutePath; transcribe(wav) } },
                            onEmail = { emailRec(wav) },
                            onDelete = { deleteTarget = wav }
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "Stops when locked? Settings → Apps → Digimax Transcriber → Battery → Unrestricted.",
                color = Grey, fontSize = 12.sp
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
    val state = when { emailed -> "Emailed"; txt.exists() -> "Transcribed"; else -> "Recorded" }
    Column(Modifier.fillMaxWidth().clickable { onToggle() }.padding(vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(stamp.format(Date(wav.lastModified())), color = Ink, fontSize = 14.sp)
                Text("${wav.length() / 1024} KB · $state", color = if (emailed) Red else Grey, fontSize = 12.sp)
            }
            Text(if (expanded) "▲" else "▼", color = Grey, fontSize = 12.sp)
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Text(if (txt.exists()) txt.readText() else "Not transcribed yet.", color = Ink, fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (txt.exists()) {
                    Button(onClick = onEmail, enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White),
                        shape = RoundedCornerShape(8.dp)) { Text("Email", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                } else {
                    Button(onClick = onTranscribe, enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White),
                        shape = RoundedCornerShape(8.dp)) { Text("Transcribe", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                }
                OutlinedButton(onClick = onDelete, shape = RoundedCornerShape(8.dp)) { Text("Delete", fontSize = 13.sp, color = Red) }
            }
        }
    }
}

@Composable
private fun Waveform(active: Boolean) {
    val bars = remember { mutableStateListOf<Float>().apply { repeat(56) { add(0f) } } }
    LaunchedEffect(active) {
        if (active) {
            while (RecState.recording) {
                bars.removeAt(0); bars.add(RecState.level.coerceIn(0f, 1f)); delay(50)
            }
        }
        for (i in bars.indices) bars[i] = 0f
    }
    Canvas(Modifier.fillMaxWidth().height(52.dp)) {
        val n = bars.size
        val bw = size.width / n
        val mid = size.height / 2f
        bars.forEachIndexed { i, v ->
            val h = (v * size.height).coerceAtLeast(3f)
            drawRoundRect(
                color = if (active) Red else Line,
                topLeft = Offset(i * bw + bw * 0.25f, mid - h / 2f),
                size = Size(bw * 0.5f, h),
                cornerRadius = CornerRadius(2f, 2f)
            )
        }
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(18.dp)) { content() }
}
