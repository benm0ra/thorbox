// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.io

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import com.thorbox.app.AppState
import com.thorbox.engine.NativeEngine
import com.thorbox.model.TrackKind
import java.util.concurrent.locks.LockSupport

/**
 * SETTINGS → MIDI. USB and Bluetooth MIDI (anything Android's MIDI service sees).
 *
 *  IN   a keyboard or pad controller plays the app: notes go to the track you're on (OMNI) or to
 *       tracks 1–8 by channel; pads and drum slots from C1 (36); a few CCs move the synth and mixer;
 *       with SYNC IN, MIDI clock sets the tempo and START / STOP run the transport.
 *  OUT  a track can send its notes (sequenced and played) on a MIDI channel to play outboard gear,
 *       and CLOCK OUT sends clock + START / STOP so drum machines and DAWs follow.
 *
 * Output timing: the audio thread stamps every message with the frame it belongs to; a dedicated
 * thread turns frames into System.nanoTime with the engine's anchor (plus the output latency, so the
 * outboard lines up with what you hear) and sends each one when its time comes. Input is handed to
 * the main thread, the only one that talks to the engine.
 */
class MidiController(private val app: AppState, ctx: Context) {
    data class Dev(val id: Int, val name: String, val ins: Int, val outs: Int, val key: String)

    private val prefs = ctx.getSharedPreferences("midi", Context.MODE_PRIVATE)
    private val mm: MidiManager? = ctx.getSystemService(MidiManager::class.java)
    private val main = Handler.createAsync(Looper.getMainLooper())
    val available get() = mm != null

    val devices = mutableStateOf<List<Dev>>(emptyList())
    /** Which device plays the app: ALL, "" (none) or a device key. */
    val inKey = mutableStateOf(prefs.getString("in", ALL) ?: ALL)
    /** Which device the app sends to: "" (none) or a device key. */
    val outKey = mutableStateOf(prefs.getString("out", "") ?: "")
    /** false: OMNI → the track you're on. true: channels 1–8 → tracks 1–8. */
    val byChannel = mutableStateOf(prefs.getBoolean("byChannel", false))
    val syncIn = mutableStateOf(prefs.getBoolean("syncIn", false))
    val clockOut = mutableStateOf(prefs.getBoolean("clockOut", false))
    /** Each track's MIDI OUT channel (0..15) or -1. */
    val trackCh = mutableStateOf(List(com.thorbox.model.TRACK_COUNT) { prefs.getInt("ch$it", -1) })
    val offsetMs = mutableIntStateOf(prefs.getInt("offset", 0))
    /** For the activity lights: when a message last came in / went out (uptime ms), and what it was. */
    val lastIn = mutableLongStateOf(0L)
    val lastInText = mutableStateOf("")
    val externalBpm = mutableStateOf<Float?>(null)

    @Volatile var outLatencyMs = 0.0

    // ---------------------------------------------------------------- devices
    private val opened = HashMap<Int, MidiDevice>()
    private val inPorts = ArrayList<MidiOutputPort>()        // (Android names ports from the device's side)
    @Volatile private var outPort: MidiInputPort? = null
    private val cb = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) { refresh(); reconnect() }
        override fun onDeviceRemoved(device: MidiDeviceInfo) { refresh(); reconnect() }
    }

    fun start() {
        val m = mm ?: return
        m.registerDeviceCallback(MidiManager.TRANSPORT_MIDI_BYTE_STREAM, { it.run() }, cb)
        refresh(); reconnect(); apply()
    }
    fun stop() {
        runCatching { mm?.unregisterDeviceCallback(cb) }
        closeAll(); outThread?.quit(); outThread = null
    }

    private fun infos(): List<MidiDeviceInfo> = mm?.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM)?.toList().orEmpty()
    private fun nameOf(i: MidiDeviceInfo): String {
        val p = i.properties
        return (p.getString(MidiDeviceInfo.PROPERTY_NAME) ?: p.getString(MidiDeviceInfo.PROPERTY_PRODUCT) ?: "MIDI device ${i.id}").trim()
    }
    private fun keyOf(i: MidiDeviceInfo) = "${i.type}|${nameOf(i)}"
    private fun refresh() {
        devices.value = infos().map { Dev(it.id, nameOf(it), it.inputPortCount, it.outputPortCount, keyOf(it)) }
    }

    private fun closeAll() {
        inPorts.forEach { runCatching { it.close() } }; inPorts.clear()
        runCatching { outPort?.close() }; outPort = null
        opened.values.forEach { runCatching { it.close() } }; opened.clear()
    }

    /** Open what the settings ask for (after a change or a plug / unplug). */
    fun reconnect() {
        val m = mm ?: return
        closeAll()
        val all = infos()
        val wantIn = all.filter { it.outputPortCount > 0 && (inKey.value == ALL || keyOf(it) == inKey.value) }
        val wantOut = all.firstOrNull { it.inputPortCount > 0 && keyOf(it) == outKey.value }
        val need = (wantIn + listOfNotNull(wantOut)).distinctBy { it.id }
        for (info in need) m.openDevice(info, { dev ->
            if (dev == null) return@openDevice
            opened[info.id] = dev
            if (wantIn.any { it.id == info.id }) for (p in 0 until info.outputPortCount) dev.openOutputPort(p)?.let { it.connect(receiver); inPorts += it }
            if (wantOut?.id == info.id) { outPort = dev.openInputPort(0); ensureOutThread(); apply() }
        }, main)
        if (wantOut == null) apply()
    }

    fun chooseIn(key: String) { inKey.value = key; save(); reconnect() }
    fun chooseOut(key: String) { outKey.value = key; save(); reconnect() }
    fun setByChannel(on: Boolean) { byChannel.value = on; save() }
    fun setSyncIn(on: Boolean) { syncIn.value = on; if (!on) externalBpm.value = null; save() }
    fun setClockOut(on: Boolean) { clockOut.value = on; save(); apply() }
    fun setTrackChannel(t: Int, ch: Int) { trackCh.value = trackCh.value.toMutableList().also { it[t] = ch.coerceIn(-1, 15) }; save(); apply() }
    fun setOffset(ms: Int) { offsetMs.intValue = ms.coerceIn(-100, 100); save() }
    private fun save() {
        val e = prefs.edit().putString("in", inKey.value).putString("out", outKey.value).putBoolean("byChannel", byChannel.value)
            .putBoolean("syncIn", syncIn.value).putBoolean("clockOut", clockOut.value).putInt("offset", offsetMs.intValue)
        trackCh.value.forEachIndexed { i, c -> e.putInt("ch$i", c) }
        e.apply()
    }

    /** Push the OUT settings to the engine (also after the kit changes: drum slots send their GM notes). */
    fun apply() {
        val sending = outPort != null
        NativeEngine.setMidiClock(sending && clockOut.value)
        trackCh.value.forEachIndexed { t, ch ->
            NativeEngine.setMidiOut(t, if (sending) ch else -1)
            if (sending && ch >= 0) for (n in 0 until 16) NativeEngine.setMidiMap(t, n, app.midiNote(t, n).coerceIn(0, 127))
        }
    }

    // ---------------------------------------------------------------- IN
    private val receiver = object : MidiReceiver() {
        private var running = 0
        private var d1 = -1
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            for (k in offset until offset + count) {
                val b = msg[k].toInt() and 0xFF
                when {
                    b >= 0xF8 -> realtime(b, timestamp)
                    b >= 0xF0 -> { running = 0; d1 = -1 }                      // system common / sysex: ignored
                    b >= 0x80 -> { running = b; d1 = -1 }
                    running == 0 -> Unit
                    (running and 0xF0) == 0xC0 || (running and 0xF0) == 0xD0 -> post(running, b, 0)
                    d1 < 0 -> d1 = b
                    else -> { post(running, d1, b); d1 = -1 }
                }
            }
        }
    }
    private fun post(status: Int, a: Int, b: Int) { main.post { onMessage(status, a, b) } }

    // clock in: tempo from the spacing of the last 24 clocks (measured on the MIDI thread: exact timestamps)
    private val clockTimes = LongArray(25)
    private var clockCount = 0
    private var lastTempoPost = 0L
    private fun realtime(b: Int, ts: Long) {
        if (!syncIn.value) return
        when (b) {
            0xF8 -> {
                clockTimes[clockCount % clockTimes.size] = ts; clockCount++
                if (clockCount >= clockTimes.size) {
                    val newest = clockTimes[(clockCount - 1) % clockTimes.size]
                    val oldest = clockTimes[clockCount % clockTimes.size]
                    val bpm = (60e9 / (newest - oldest)).toFloat()                 // 24 clocks = one beat
                    if (bpm in 30f..300f && ts - lastTempoPost > 200_000_000L) {
                        lastTempoPost = ts
                        main.post { followTempo(bpm) }
                    }
                }
            }
            0xFA, 0xFB -> { clockCount = 0; main.post { if (!app.groove.playing.value) app.groove.togglePlay() } }
            0xFC -> main.post { if (app.groove.playing.value) app.groove.togglePlay() }
        }
    }
    private fun followTempo(bpm: Float) {
        val r = (bpm * 10f).let { kotlin.math.round(it) } / 10f
        externalBpm.value = r
        val g = app.groove
        if (kotlin.math.abs(g.groove.value.bpm - r) >= 0.2f) g.setGroove(g.groove.value.copy(bpm = r))
    }

    private val held = HashSet<Long>()                          // (track, note) held from MIDI, released on all-notes-off
    private fun onMessage(status: Int, a: Int, b: Int) {
        lastIn.longValue = android.os.SystemClock.uptimeMillis()
        val ch = status and 0x0F
        val type = status and 0xF0
        lastInText.value = when (type) { 0x90 -> "note ${noteName(a)} ch ${ch + 1}"; 0x80 -> "note off ${noteName(a)}"; 0xB0 -> "CC $a = $b ch ${ch + 1}"; 0xE0 -> "pitch bend"; else -> "%02X".format(status) }
        val track = if (byChannel.value) { if (ch >= com.thorbox.model.TRACK_COUNT) return; ch } else app.groove.seqTrack.intValue
        when (type) {
            0x90 -> if (b > 0) noteIn(track, a, b / 127f, true) else noteIn(track, a, 0f, false)
            0x80 -> noteIn(track, a, 0f, false)
            0xB0 -> cc(track, a, b)
        }
    }

    private fun noteIn(t: Int, note: Int, vel: Float, on: Boolean) {
        val key = t.toLong() shl 8 or note.toLong()
        when (app.trackKind(t)) {
            TrackKind.SAMPLER -> { val pad = note - 36; if (pad !in 0..15) return
                if (on) app.samplerFor(t).padDown(pad, vel) else app.samplerFor(t).padUp(pad) }
            TrackKind.DRUMS -> { val slot = drumSlotFor(t, note) ?: return
                if (on) app.drumsFor(t).hit(slot, vel) else app.drumsFor(t).release(slot) }
            TrackKind.SYNTH -> if (on) NativeEngine.synthNoteOn(t, note, vel) else NativeEngine.synthNoteOff(t, note)
            else -> return
        }
        if (on) held += key else held -= key
    }
    /** A drum note: the slot whose GM note it is, else C1 + slot. */
    private fun drumSlotFor(t: Int, note: Int): Int? {
        for (s in 0 until 16) if (app.midiNote(t, s) == note) return s
        return (note - 36).takeIf { it in 0..15 }
    }

    private fun cc(t: Int, n: Int, v: Int) {
        val x = v / 127f
        when (n) {
            7 -> app.groove.setChannel(t) { it.copy(volumeDb = if (v == 0) -60f else (40f * kotlin.math.log10(x)).coerceIn(-60f, 0f)) }
            10 -> app.groove.setChannel(t) { it.copy(pan = x * 2f - 1f) }
            1 -> if (t == app.synthTrack.intValue) app.setKnob(com.thorbox.app.synth.SynthPage.LFO, 1, x)       // mod wheel → LFO depth
            74 -> if (t == app.synthTrack.intValue) app.setKnob(com.thorbox.app.synth.SynthPage.FILTER, 0, x)   // brightness → cutoff
            71 -> if (t == app.synthTrack.intValue) app.setKnob(com.thorbox.app.synth.SynthPage.FILTER, 1, x)   // resonance
            123, 120 -> allNotesOff()
        }
    }
    fun allNotesOff() {
        for (k in held.toList()) noteIn((k shr 8).toInt(), (k and 0xFF).toInt(), 0f, false)
        held.clear()
    }

    // ---------------------------------------------------------------- OUT
    private var outThread: OutThread? = null
    private fun ensureOutThread() { if (outThread == null) outThread = OutThread().also { it.start() } }

    private inner class OutThread : Thread("ThorboxMidiOut") {
        @Volatile private var running = true
        fun quit() { running = false; interrupt() }
        private val buf = LongArray(3 * 256)
        private val anchor = LongArray(3)
        // waiting messages: send time + bytes (a ring; nothing allocated per message)
        private val qTime = LongArray(4096); private val qMsg = IntArray(4096); private val qLen = IntArray(4096)
        private var head = 0; private var tail = 0
        private val bytes = ByteArray(3)
        override fun run() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (NativeEngine.pollMidi(buf) > 0) Unit          // anything queued before a port was open is stale
            while (running) {
                val n = NativeEngine.pollMidi(buf)
                if (n > 0) {
                    NativeEngine.midiAnchor(anchor)
                    val sr = anchor[2].coerceAtLeast(8000L).toDouble()
                    val shift = ((outLatencyMs + offsetMs.intValue) * 1e6).toLong()
                    for (i in 0 until n) {
                        if ((tail + 1) % qTime.size == head) break     // full: drop (can't happen at sane rates)
                        qTime[tail] = anchor[1] + ((buf[3 * i] - anchor[0]) * 1e9 / sr).toLong() + shift
                        qLen[tail] = buf[3 * i + 1].toInt(); qMsg[tail] = buf[3 * i + 2].toInt()
                        tail = (tail + 1) % qTime.size
                    }
                }
                val port = outPort
                val now = System.nanoTime()
                while (head != tail && qTime[head] <= now + 300_000L) {
                    if (port != null) {
                        val m = qMsg[head]; val len = qLen[head].coerceIn(1, 3)
                        bytes[0] = (m and 0xFF).toByte(); bytes[1] = (m shr 8 and 0xFF).toByte(); bytes[2] = (m shr 16 and 0xFF).toByte()
                        runCatching { port.send(bytes, 0, len) }
                    }
                    head = (head + 1) % qTime.size
                }
                LockSupport.parkNanos(400_000L)
            }
        }
    }

    companion object {
        const val ALL = "*"
        private val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        fun noteName(n: Int) = "${NAMES[n % 12]}${n / 12 - 1}"
    }
}
