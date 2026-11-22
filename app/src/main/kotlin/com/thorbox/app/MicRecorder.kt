// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.AudioManager
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
import kotlin.math.log10

/**
 * Field recorder: the Thor's mic → a take in a private temp file. Stopping leaves the take on the
 * table for review: KEEP saves a 48 kHz mono WAV to Music/Clamshell Beats/Field Recordings (then the
 * app chops it onto the pads), DISCARD deletes it, REDO deletes it and records again.
 *
 * Input: the normal MIC source first (the HAL applies the mic's analog gain there). The raw
 * UNPROCESSED source is only a last resort: on the Thor it delivers about −52 dBFS with someone
 * talking, i.e. an empty-looking take. If the raw source is used and is near-silent for the first
 * half second, the recorder reopens on MIC. [gainDb] (0…+24 dB) lifts a quiet source; it's applied
 * before saving and soft-limited, so it can't wrap.
 * Capped at 10 minutes. Capture runs on its own thread; the UI reads the volatiles every frame.
 */
class MicRecorder(private val app: Context) {
    val recording = mutableStateOf(false)
    /** Saving the WAV (between KEEP and the pads lighting up). */
    val saving = mutableStateOf(false)
    /** A finished take waiting for KEEP / DISCARD. */
    val take = mutableStateOf<Take?>(null)
    /** Input gain in dB (0…24), applied to the take and the meters. */
    val gainDb = androidx.compose.runtime.mutableFloatStateOf(0f)

    class Take(val file: java.io.File, val bytes: Long, val seconds: Float, val name: String, val peakDb: Float, val source: String)

    // Written by the capture thread, read by the UI every frame (plain volatiles, not Compose state).
    @Volatile var level = 0f; private set          // 0..1 peak of the latest block (after gain)
    @Volatile var peakHold = 0f; private set       // decaying peak hold for the meter
    @Volatile var seconds = 0f; private set
    @Volatile var failure: String? = null; private set
    @Volatile var source = ""; private set          // "RAW" or "MIC": which input we ended up on
    /** One line for the sheet: what the recorder is doing right now (source, state, frames, raw peak). */
    @Volatile var status = "idle"; private set
    @Volatile var framesRead = 0L; private set
    private fun log(msg: String) { status = msg; android.util.Log.i(TAG, msg) }
    /** Live waveform: one (min, max) pair per [WAVE_STEP] samples, in a ring of [WAVE_COLS]. */
    val wave = FloatArray(WAVE_COLS * 2)
    @Volatile var waveCount = 0; private set
    /** The whole take, coarse: one |peak| per [OVERVIEW_STEP] samples (for the review screen). */
    val overview = FloatArray(OVERVIEW_COLS)
    @Volatile var overviewCount = 0; private set
    @Volatile private var stop = false
    private var thread: Thread? = null

    private fun rawSupported(): Boolean = runCatching {
        (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
    }.getOrDefault(false)

    @SuppressLint("MissingPermission")   // callers check RECORD_AUDIO first (AppState.requestMic)
    private fun open(src: Int, rate: Int, min: Int): AudioRecord? =
        runCatching { AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 4) }
            .getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }

    /** Start a new take (any take still under review is discarded). [onError] runs on the main thread. */
    fun start(onError: (String) -> Unit) {
        if (recording.value || saving.value) return
        discard()                                                   // also stops a preview
        val rate = 48000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
        val tryRaw = rawSupported()
        log("start: permission ok, raw source supported=$tryRaw, min buffer=$min")
        var src = "MIC"
        var rec = open(MediaRecorder.AudioSource.MIC, rate, min)
        if (rec == null) rec = open(MediaRecorder.AudioSource.CAMCORDER, rate, min)?.also { src = "CAM" }
        if (rec == null) rec = open(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, min)?.also { src = "VOICE" }
        if (rec == null && tryRaw) rec = open(MediaRecorder.AudioSource.UNPROCESSED, rate, min)?.also { src = "RAW" }
        if (rec == null) { log("start: no input source would open"); onError("Couldn't open the microphone (no input source would open)"); return }
        log("start: opened $src, ${rec.sampleRate} Hz, state=${rec.state}")
        framesRead = 0L
        stop = false; recording.value = true; seconds = 0f; level = 0f; peakHold = 0f; waveCount = 0; overviewCount = 0; failure = null; source = src
        val name = "Field " + SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date())
        thread = Thread({
            val tmp = java.io.File(app.cacheDir, "mic-${System.nanoTime()}.pcm")
            var bytes = 0L
            val buf = ShortArray(min / 2)
            val bb = ByteBuffer.allocate(buf.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            val maxBytes = rate * 2L * 600
            var colMin = 0f; var colMax = 0f; var colN = 0
            var ovPk = 0f; var ovN = 0
            var takePeak = 0f
            var r: AudioRecord = rec
            var out = java.io.BufferedOutputStream(java.io.FileOutputStream(tmp), 1 shl 16)
            var ok = false
            try {
                r.startRecording()
                log("capture: startRecording → recordingState=${r.recordingState} (3 = recording), device=${runCatching { r.routedDevice?.productName }.getOrNull()}")
                if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) error("the mic didn't start (is another app using it?)")
                var nextLog = rate.toLong()
                var errors = 0
                var probe = src == "RAW"                       // still checking the raw source isn't silent
                var probePeak = 0
                while (!stop && bytes < maxBytes) {
                    val n = r.read(buf, 0, buf.size)
                    if (n <= 0) {                                   // ERROR_DEAD_OBJECT etc: don't spin forever
                        if (errors == 0) log("capture: read returned $n")
                        if (++errors > 50) error("the mic stopped sending audio ($n)")
                        Thread.sleep(5); continue
                    }
                    errors = 0
                    if (probe) {
                        for (i in 0 until n) probePeak = maxOf(probePeak, abs(buf[i].toInt()))
                        if (bytes + n * 2 >= rate) {             // half a second of raw input
                            probe = false
                            log("probe: raw peak after 0.5 s = $probePeak / 32767")
                            if (probePeak < 24) {                // < −62 dBFS: this HAL's raw source is dead; use MIC
                                log("probe: raw source silent: switching to MIC")
                                runCatching { r.stop() }; r.release()
                                r = open(MediaRecorder.AudioSource.MIC, rate, min) ?: error("Couldn't open the microphone")
                                r.startRecording()
                                src = "MIC"; source = src
                                out.close(); out = java.io.BufferedOutputStream(java.io.FileOutputStream(tmp), 1 shl 16)
                                bytes = 0; waveCount = 0; overviewCount = 0; takePeak = 0f
                                continue
                            }
                        }
                    }
                    val g = Math.pow(10.0, gainDb.floatValue / 20.0).toFloat()
                    var pk = 0f
                    bb.clear()
                    for (i in 0 until n) {
                        var v = buf[i] / 32768f * g
                        if (g > 1f && abs(v) > 0.9f) v = (if (v > 0) 1f else -1f) * (0.9f + 0.1f * kotlin.math.tanh((abs(v) - 0.9f) / 0.1f))
                        val av = abs(v)
                        if (av > pk) pk = av
                        bb.putShort((v * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                        if (v < colMin) colMin = v; if (v > colMax) colMax = v
                        if (++colN == WAVE_STEP) {
                            val c = waveCount % WAVE_COLS
                            wave[2 * c] = colMin; wave[2 * c + 1] = colMax
                            waveCount++; colMin = 0f; colMax = 0f; colN = 0
                        }
                        if (av > ovPk) ovPk = av
                        if (++ovN == OVERVIEW_STEP) {
                            if (overviewCount < OVERVIEW_COLS) { overview[overviewCount] = ovPk; overviewCount++ }
                            ovPk = 0f; ovN = 0
                        }
                    }
                    out.write(bb.array(), 0, n * 2)
                    bytes += n * 2
                    framesRead += n
                    if (framesRead >= nextLog) {
                        nextLog += rate
                        log("capture: $src ${"%.1f".format(Locale.US, framesRead / rate.toFloat())} s, block peak ${if (pk > 0f) "%.1f".format(Locale.US, 20f * log10(pk)) else "-inf"} dBFS, gain ${gainDb.floatValue} dB")
                    }
                    level = pk
                    peakHold = maxOf(pk, peakHold * 0.97f)
                    if (pk > takePeak) takePeak = pk
                    seconds = bytes / (rate * 2f)
                }
                ok = true
            } catch (e: Exception) {
                android.util.Log.e(TAG, "recording failed", e)
                failure = e.message ?: e.javaClass.simpleName
            } finally {
                runCatching { out.close() }
                runCatching { r.stop() }; r.release()
            }
            log("stop: ok=$ok, ${bytes / 2} frames, peak ${"%.1f".format(Locale.US, if (takePeak > 0f) 20f * log10(takePeak) else -96f)} dBFS${failure?.let { ", failure: $it" } ?: ""}")
            val result = if (ok && bytes > rate / 10) Take(tmp, bytes, bytes / (rate * 2f), name, if (takePeak > 0f) 20f * log10(takePeak) else -96f, src) else null
            if (result == null) tmp.delete()                 // never leave raw audio behind
            val msg = failure?.let { "Recording failed: $it" } ?: "Recording too short"
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                recording.value = false; level = 0f
                if (result != null) take.value = result else onError(msg)
                android.util.Log.i(TAG, "ui: recording=false, take=${result?.let { "%.1f s".format(Locale.US, it.seconds) } ?: "none ($msg)"}")
            }
        }, "clamshell-mic").also { it.start() }
    }

    fun stop() { stop = true }

    /** Throw the take away (temp file deleted). */
    // ---- PREVIEW: play the take back before deciding (streams the temp file; nothing is saved) ----
    val previewing = mutableStateOf(false)
    /** 0..1 through the take while previewing (for the playhead). */
    @Volatile var previewPos = 0f; private set
    @Volatile private var previewStop = false

    fun togglePreview() { if (previewing.value) stopPreview() else startPreview() }
    fun stopPreview() { previewStop = true }

    private fun startPreview() {
        val t = take.value ?: return
        if (previewing.value || saving.value) return
        previewStop = false; previewing.value = true; previewPos = 0f
        Thread({
            val rate = 48000
            val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
            var at: AudioTrack? = null
            try {
                at = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(min * 2).setTransferMode(AudioTrack.MODE_STREAM).build()
                at.play()
                java.io.FileInputStream(t.file).use { inp ->     // the file stays readable even if the take is discarded meanwhile
                    val buf = ByteArray(min)
                    var done = 0L
                    while (!previewStop) {
                        val n = inp.read(buf)
                        if (n <= 0) break
                        at.write(buf, 0, n)
                        done += n
                        previewPos = (done.toFloat() / t.bytes.coerceAtLeast(1L)).coerceIn(0f, 1f)
                    }
                }
                if (!previewStop) Thread.sleep(min * 1000L / (rate * 2) + 50)   // let the last buffer play out
            } catch (e: Exception) {
                log("preview failed: $e")
            } finally {
                runCatching { at?.stop() }; runCatching { at?.release() }
                android.os.Handler(android.os.Looper.getMainLooper()).post { previewing.value = false; previewPos = 0f }
            }
        }, "clamshell-mic-preview").start()
    }

    fun discard() {
        stopPreview()
        take.value?.file?.delete()
        take.value = null
        waveCount = 0; overviewCount = 0; seconds = 0f; peakHold = 0f
    }

    /** Save the take as a WAV; [onDone] gets the Uri (or null + message) on the main thread. */
    fun keep(onDone: (Uri?, String) -> Unit) {
        val t = take.value ?: return
        if (saving.value) return
        stopPreview()
        saving.value = true
        Thread({
            val uri = runCatching { save(t.name, t.file, t.bytes, 48000) }.getOrNull()
            t.file.delete()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                saving.value = false; take.value = null
                runCatching { onDone(uri, if (uri != null) "${t.name}.wav" else "Couldn't save the recording") }
                    .onFailure { android.util.Log.e(TAG, "loading the recording failed", it) }
            }
        }, "clamshell-mic-save").start()
    }

    companion object {
        const val TAG = "ClamshellMic"
        const val WAVE_STEP = 480          // 10 ms per column at 48 kHz
        const val WAVE_COLS = 1200         // the last 12 s
        const val OVERVIEW_STEP = 4800     // 0.1 s per column
        const val OVERVIEW_COLS = 6000     // 10 minutes
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
