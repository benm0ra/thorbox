// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Field recorder: the Thor's mic → a 48 kHz mono WAV in Music/Clamshell Beats/Field Recordings, then
 * straight into the current sampler track. Uses the UNPROCESSED source when the device offers it
 * (no AGC / noise suppression: what you hear is what you sample), falling back to MIC. Capped at
 * 10 minutes so memory can't run away. Recording runs on its own thread; state is read by the UI.
 */
class MicRecorder(private val app: Context) {
    val recording = mutableStateOf(false)
    val level = mutableFloatStateOf(0f)          // 0..1 peak, for the meter
    val seconds = mutableFloatStateOf(0f)
    @Volatile private var stop = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")   // callers check RECORD_AUDIO first (AppState.requestMic)
    fun start(onDone: (Uri?, String) -> Unit) {
        if (recording.value) return
        val rate = 48000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
        val rec = runCatching { AudioRecord(MediaRecorder.AudioSource.UNPROCESSED, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 4) }
            .getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            ?: runCatching { AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 4) }
                .getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        if (rec == null) { onDone(null, "Couldn't open the microphone"); return }
        stop = false; recording.value = true; seconds.floatValue = 0f
        thread = Thread({
            // PCM goes to a private temp file as it's captured: memory stays flat for 10 minutes.
            val tmp = java.io.File(app.cacheDir, "mic-${System.nanoTime()}.pcm")
            var bytes = 0L
            val buf = ShortArray(min / 2)
            val bb = ByteBuffer.allocate(buf.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            val maxBytes = rate * 2L * 600
            var uri: Uri? = null
            val name = "Field " + SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date())
            try {
                java.io.BufferedOutputStream(java.io.FileOutputStream(tmp), 1 shl 16).use { out ->
                    rec.startRecording()
                    while (!stop && bytes < maxBytes) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n <= 0) continue
                        var pk = 0
                        bb.clear()
                        for (i in 0 until n) { val v = buf[i].toInt(); if (abs(v) > pk) pk = abs(v); bb.putShort(buf[i]) }
                        out.write(bb.array(), 0, n * 2)
                        bytes += n * 2
                        level.floatValue = pk / 32768f
                        seconds.floatValue = bytes / (rate * 2f)
                    }
                }
                if (bytes > rate / 10) uri = save(name, tmp, bytes, rate)
            } catch (_: Exception) {
                uri = null
            } finally {
                runCatching { rec.stop() }; rec.release()
                tmp.delete()                                         // never leave raw audio behind
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                recording.value = false; level.floatValue = 0f
                onDone(uri, if (uri != null) "$name.wav" else "Recording too short")
            }
        }, "clamshell-mic").also { it.start() }
    }

    fun stop() { stop = true }

    private fun save(name: String, pcm: java.io.File, pcmBytes: Long, rate: Int): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$name.wav")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Clamshell Beats/Field Recordings")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val r = app.contentResolver
        val uri = r.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        val ok = runCatching {
            r.openOutputStream(uri)?.use { out ->
                out.write(wavHeader(pcmBytes.toInt(), rate))
                pcm.inputStream().use { it.copyTo(out, 1 shl 16) }
            } ?: error("no stream")
            values.clear(); values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            r.update(uri, values, null, null)
        }.isSuccess
        if (!ok) { runCatching { r.delete(uri, null, null) }; return null }   // no half-written, pending files left around
        return uri
    }

    private fun wavHeader(pcmSize: Int, rate: Int): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + pcmSize); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1); h.putInt(rate); h.putInt(rate * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(pcmSize)
        return h.array()
    }
}
