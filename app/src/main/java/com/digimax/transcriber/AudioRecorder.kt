package com.digimax.transcriber

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Captures the mic as 16 kHz mono PCM-16 and streams it straight to a .wav file
 * as it records — so a process kill never loses what was already captured.
 * 16 kHz mono is exactly what Whisper/Vosk want, so there's no resample later.
 */
class AudioRecorder(
    private val outFile: File,
    private val onLevel: (Float) -> Unit = {}
) {

    companion object {
        const val SAMPLE_RATE = 16_000
    }

    @Volatile private var running = false
    @Volatile var paused = false      // while true, audio is dropped (recording held, same file)
    private var worker: Thread? = null

    @SuppressLint("MissingPermission") // caller guarantees RECORD_AUDIO is granted
    fun start() {
        if (running) return
        running = true
        worker = thread(name = "audio-rec") {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(SAMPLE_RATE) // ~1s floor

            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )

            val raf = RandomAccessFile(outFile, "rw")
            writeWavHeader(raf, 0) // placeholder sizes; patched on stop
            var dataBytes = 0L
            val buf = ByteArray(minBuf)

            try {
                record.startRecording()
                while (running) {
                    val n = record.read(buf, 0, buf.size)
                    if (n > 0) {
                        if (paused) {
                            onLevel(0f)                      // hold: drop audio, flatten meter
                        } else {
                            raf.write(buf, 0, n)
                            dataBytes += n
                            onLevel(rms(buf, n))
                            // flush periodically so the file on disk stays current
                            if (dataBytes % (SAMPLE_RATE * 2L) < buf.size) raf.fd.sync()
                        }
                    }
                }
            } catch (_: Throwable) {
                // fall through to finalise whatever we have
            } finally {
                try { record.stop() } catch (_: Throwable) {}
                record.release()
                patchWavSizes(raf, dataBytes)
                raf.fd.sync()
                raf.close()
            }
        }
    }

    fun stop() {
        running = false
        try { worker?.join(2_000) } catch (_: Throwable) {}
        worker = null
        onLevel(0f)
    }

    /** RMS of a PCM-16 LE buffer, amplified to 0..1 for the meter (speech is quiet). */
    private fun rms(buf: ByteArray, n: Int): Float {
        var sum = 0.0
        var i = 0
        while (i + 1 < n) {
            val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort().toInt()
            sum += (s * s).toDouble()
            i += 2
        }
        val count = (n / 2).coerceAtLeast(1)
        val r = sqrt(sum / count) / 32768.0
        return (r * 6.0).coerceIn(0.0, 1.0).toFloat()
    }

    private fun writeWavHeader(raf: RandomAccessFile, dataLen: Int) {
        val channels = 1
        val byteRate = SAMPLE_RATE * channels * 2
        raf.seek(0)
        raf.writeBytes("RIFF")
        raf.writeIntLE(36 + dataLen)
        raf.writeBytes("WAVE")
        raf.writeBytes("fmt ")
        raf.writeIntLE(16)
        raf.writeShortLE(1)                  // PCM
        raf.writeShortLE(channels)
        raf.writeIntLE(SAMPLE_RATE)
        raf.writeIntLE(byteRate)
        raf.writeShortLE(channels * 2)       // block align
        raf.writeShortLE(16)                 // bits per sample
        raf.writeBytes("data")
        raf.writeIntLE(dataLen)
    }

    private fun patchWavSizes(raf: RandomAccessFile, dataBytes: Long) {
        val dataLen = dataBytes.toInt()
        raf.seek(4);  raf.writeIntLE(36 + dataLen)
        raf.seek(40); raf.writeIntLE(dataLen)
    }

    private fun RandomAccessFile.writeIntLE(v: Int) {
        write(v and 0xff); write((v shr 8) and 0xff); write((v shr 16) and 0xff); write((v shr 24) and 0xff)
    }
    private fun RandomAccessFile.writeShortLE(v: Int) {
        write(v and 0xff); write((v shr 8) and 0xff)
    }
}
