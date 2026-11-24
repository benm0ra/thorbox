// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.io

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.thorbox.engine.NativeEngine
import com.thorbox.model.PPQN

/**
 * SETTINGS → AUDIO: where the sound goes (output) and comes from (input, for the field recorder), and
 * the metronome. These belong to this device, not to a project, so they live in preferences.
 *
 * Devices are remembered by type + name (Android's ids change between boots and replugs); a device
 * that's gone falls back to the system's choice until it comes back.
 */
class AudioSetup(private val ctx: Context) {
    data class Dev(val id: Int, val name: String, val kind: String, val key: String)

    private val prefs = ctx.getSharedPreferences("audio", Context.MODE_PRIVATE)
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val outputs = mutableStateOf<List<Dev>>(emptyList())
    val inputs = mutableStateOf<List<Dev>>(emptyList())
    /** "" = the system's choice. */
    val outKey = mutableStateOf(prefs.getString("out", "") ?: "")
    val inKey = mutableStateOf(prefs.getString("in", "") ?: "")

    // ---- metronome ----
    val clickSound = mutableIntStateOf(prefs.getInt("clickSound", 0))          // 0 CLICK, 1 WOOD, 2 COWBELL, 3 HAT
    val clickLevel = mutableFloatStateOf(prefs.getFloat("clickLevel", 0.5f))
    val clickAccent = mutableStateOf(prefs.getBoolean("clickAccent", true))
    val countInBars = mutableIntStateOf(prefs.getInt("countIn", 0))            // 0, 1, 2
    val clickOnlyRec = mutableStateOf(prefs.getBoolean("clickOnlyRec", false))

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { refresh(); applyOutput() }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { refresh(); applyOutput() }
    }

    fun start() {
        refresh()
        am.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        applyOutput(); applyClick()
    }
    fun stop() { runCatching { am.unregisterAudioDeviceCallback(callback) } }

    private fun refresh() {
        fun list(flag: Int) = am.getDevices(flag).filter { it.type !in HIDDEN }.map { d ->
            val kind = kindOf(d.type)
            val name = d.productName?.toString()?.takeIf { it.isNotBlank() && it != android.os.Build.MODEL } ?: kind
            Dev(d.id, name, kind, "${d.type}|$name")
        }.distinctBy { it.key }
        outputs.value = list(AudioManager.GET_DEVICES_OUTPUTS)
        inputs.value = list(AudioManager.GET_DEVICES_INPUTS)
    }

    fun chooseOutput(key: String) { outKey.value = key; prefs.edit().putString("out", key).apply(); applyOutput() }
    fun chooseInput(key: String) { inKey.value = key; prefs.edit().putString("in", key).apply() }

    private var appliedOut = -1
    private fun applyOutput() {
        val id = outputs.value.firstOrNull { it.key == outKey.value }?.id ?: 0
        if (id != appliedOut) { appliedOut = id; NativeEngine.setOutputDevice(id) }
    }
    /** For the recorder: the chosen input, if it's plugged in. */
    fun preferredInput(): AudioDeviceInfo? {
        val want = inKey.value.takeIf { it.isNotEmpty() } ?: return null
        val id = inputs.value.firstOrNull { it.key == want }?.id ?: return null
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == id }
    }

    // ---- metronome ----
    fun setClickSound(s: Int) { clickSound.intValue = s.coerceIn(0, 3); save(); applyClick() }
    fun setClickLevel(v: Float) { clickLevel.floatValue = v.coerceIn(0f, 1f); save(); applyClick() }
    fun setClickAccent(on: Boolean) { clickAccent.value = on; save(); applyClick() }
    fun setCountIn(bars: Int) { countInBars.intValue = bars.coerceIn(0, 2); save() }
    fun setClickOnlyRec(on: Boolean) { clickOnlyRec.value = on; save() }
    private fun save() = prefs.edit().putInt("clickSound", clickSound.intValue).putFloat("clickLevel", clickLevel.floatValue)
        .putBoolean("clickAccent", clickAccent.value).putInt("countIn", countInBars.intValue).putBoolean("clickOnlyRec", clickOnlyRec.value).apply()
    fun applyClick() {
        NativeEngine.setClick(NativeEngine.ClickParam.SOUND, clickSound.intValue.toFloat())
        NativeEngine.setClick(NativeEngine.ClickParam.LEVEL, clickLevel.floatValue)
        NativeEngine.setClick(NativeEngine.ClickParam.ACCENT, if (clickAccent.value) 1f else 0f)
    }
    /** Count-in ticks for the next PLAY: only when it starts recording. */
    fun countInTicks(recording: Boolean): Float = if (recording) countInBars.intValue * 4f * PPQN else 0f

    companion object {
        val SOUNDS = listOf("CLICK", "WOOD", "COWBELL", "HAT")
        private val HIDDEN = setOf(AudioDeviceInfo.TYPE_TELEPHONY, AudioDeviceInfo.TYPE_FM_TUNER, AudioDeviceInfo.TYPE_TV_TUNER,
            AudioDeviceInfo.TYPE_IP, AudioDeviceInfo.TYPE_BUS, AudioDeviceInfo.TYPE_REMOTE_SUBMIX, AudioDeviceInfo.TYPE_FM)
        fun kindOf(t: Int) = when (t) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "SPEAKER"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILT-IN MIC"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "HEADPHONES"
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_AUX_LINE -> "LINE"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLUETOOTH"
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI"
            else -> "OTHER"
        }
    }
}
