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
    /** Saving the WAV + loading it onto the pads (between ■ and the pads lighting up). */
    val saving = mutableStateOf(false)
    // Written by the capture thread, read by the UI every frame. Plain volatiles, not Compose state:
    // the recorder never writes snapshot state off the main thread.
    @Volatile var level = 0f; private set          // 0..1 peak of the latest block, for the meter
    @Volatile var seconds = 0f; private set
    @Volatile var failure: String? = null; private set
    /**
     * Live waveform: one (min, max) pair per [WAVE_STEP] samples, newest last, in a ring of
     * [WAVE_COLS] columns. [waveCount] is how many columns exist so far; the UI draws the last N.
     */
    val wave = FloatArray(WAVE_COLS * 2)
    @Volatile var waveCount = 0; private set
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
        stop = false; recording.value = true; seconds = 0f; level = 0f; waveCount = 0; failure = null
        thread = Thread({
            // PCM goes to a private temp file as it's captured: memory stays flat for 10 minutes.
            val tmp = java.io.File(app.cacheDir, "mic-${System.nanoTime()}.pcm")
            var bytes = 0L
            val buf = ShortArray(min / 2)
            val bb = ByteBuffer.allocate(buf.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            val maxBytes = rate * 2L * 600
            var uri: Uri? = null
            val name = "Field " + SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date())
            var colMin = 0; var colMax = 0; var colN = 0
            try {
                java.io.BufferedOutputStream(java.io.FileOutputStream(tmp), 1 shl 16).use { out ->
                    rec.startRecording()
                    if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) error("the mic didn't start (is another app using it?)")
                    var errors = 0
                    while (!stop && bytes < maxBytes) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n <= 0) {                                   // ERROR_DEAD_OBJECT etc: don't spin forever
                            if (++errors > 50) error("the mic stopped sending audio ($n)")
                            Thread.sleep(5); continue
                        }
                        errors = 0
                        var pk = 0
                        bb.clear()
                        for (i in 0 until n) {
                            val v = buf[i].toInt()
                            if (abs(v) > pk) pk = abs(v)
                            bb.putShort(buf[i])
                            if (v < colMin) colMin = v; if (v > colMax) colMax = v
                            if (++colN == WAVE_STEP) {
                                val c = waveCount % WAVE_COLS
                                wave[2 * c] = colMin / 32768f; wave[2 * c + 1] = colMax / 32768f
                                waveCount++; colMin = 0; colMax = 0; colN = 0
                            }
                        }
                        out.write(bb.array(), 0, n * 2)
                        bytes += n * 2
                        level = pk / 32768f
                        seconds = bytes / (rate * 2f)
                    }
                }
                if (bytes > rate / 10) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post { saving.value = true }
                    uri = save(name, tmp, bytes, rate)
                    if (uri == null) failure = "Couldn't save the recording"
                }
            } catch (e: Exception) {
                android.util.Log.e("ClamshellMic", "recording failed", e)
                failure = e.message ?: e.javaClass.simpleName
                uri = null
            } finally {
                runCatching { rec.stop() }; rec.release()
                tmp.delete()                                         // never leave raw audio behind
            }
            val msg = failure?.let { "Recording failed: $it" } ?: "Recording too short"
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                recording.value = false; saving.value = false; level = 0f
                runCatching { onDone(uri, if (uri != null) "$name.wav" else msg) }
                    .onFailure { android.util.Log.e("ClamshellMic", "loading the recording failed", it) }
            }
        }, "clamshell-mic").also { it.start() }
    }

    fun stop() { stop = true }

    companion object {
        const val WAVE_STEP = 480          // 10 ms per column at 48 kHz
        const val WAVE_COLS = 1200         // the last 12 s
    }

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
