// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import com.thorbox.app.input.navClickable

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * The info line (an info view, for touch): touch any button, knob or slider and a
 * strip at the bottom of the top screen says, in plain words, what it does. It fades after a few
 * seconds and never blocks a touch. One glossary covers every control, looked up by the label it
 * already shows, so nothing needs wiring per screen. The ? chip turns it off for experienced users.
 */
object Info {
    val enabled = mutableStateOf(true)
    val title = mutableStateOf("")
    val body = mutableStateOf<String?>(null)
    val stamp = mutableIntStateOf(0)

    /**
     * Hints fade out as you learn them: each one shows itself at most [PER_SESSION] times per launch,
     * so the strip helps a newcomer and then stops covering the controls for everyone else.
     * Long-press ? for the tour; tap ? to switch hints off entirely.
     */
    private const val PER_SESSION = 2
    private val seen = HashMap<String, Int>()

    /** Feedback for something a button just did (undo, track switch, tempo): always shows, never rationed. */
    fun toast(t: String, text: String) { title.value = t; body.value = text; stamp.intValue++ }

    /** Called by HwButton / Encoder / DragValue with their visible label. Unknown labels are ignored. */
    fun show(label: String) {
        if (!enabled.value) return
        val (key, text) = Glossary.lookup(label) ?: return
        val n = seen[key] ?: 0
        if (n >= PER_SESSION) return
        seen[key] = n + 1
        title.value = key; body.value = text; stamp.intValue++
    }
}

object Glossary {
    // Longest-prefix match on the visible label (so "KEY MATCH +3" finds "KEY MATCH", "PAD 7" finds "PAD").
    private val entries: Map<String, String> = linkedMapOf(
        // ---- rail / navigation ----
        "SAMPLE" to "Chop a sample into 16 pads, see its key and tempo, and pitch it to fit your song.",
        "SYNTH" to "The synth. Pick a sound (‹ › or tap the name), dice a new one, or shape it with the four knobs.",
        "DRUMS" to "The drum machine. Tap squares to build a beat, pick a kit, or roll the dice.",
        "CRUNCH" to "Sample-rate + bit-depth crush on the whole kit. Around 50% is the classic 26 kHz / 12-bit sampler grit.",
        "MUFFLE" to "Low-pass on the whole kit: the filtered, dug-from-a-record drum sound.",
        "TAPE STOP" to "Hold to slow the whole mix down to a stop, like a tape machine losing power.",
        "TAPE" to "Tape saturation on the whole kit: glues it, rounds the kick, fattens the snare.",
        "DUST" to "The whole kit through an old sampler, tape and a record: CRUNCH for grit, MUFFLE for that filtered break sound, TAPE to glue it, DUST for crackle.",
        "STEPS" to "The step sequencer: write patterns for each track, step by step.",
        "MIXER" to "Volume, pan, mute and solo for each track, plus the master level.",
        "VISUAL" to "A full-screen visualizer that dances to whatever is playing. B or tap to leave.",
        "THEME" to "Change how Clamshell Beats looks: Genesis, Studio, Cyberpunk, Pastel, Terminal or Grayscale.",
        "STRETCH" to "Time-stretch the whole sample, pitch kept: SYNC to the tempo, half or double time, or FIT it to 1, 2, 4 or 8 bars.",
        "16 LEVELS" to "Spread the selected sound across all 16 pads, graded by velocity, tune, decay or tone. Pad 1 is lowest.",
        "VEL" to "16 LEVELS by velocity: pad 1 softest, pad 16 hardest.",
        "TUNE" to "16 LEVELS by pitch: pad 9 is the sound as it is, −8 to +7 semitones.",
        "NORMALIZE" to "Levels every chop to the same loudness (peaks kept under −1 dBFS). Tap again to put the old levels back.",
        "WARP" to "Pitch this chop without it speeding up (MPC-style). Off: turntable pitch, higher and faster.",
        "FIT" to "Stretch the sample to exactly that many bars at the project tempo.",
                "DEMO BREAK" to "Load a built-in 90 BPM boom-bap break onto the pads, to try the sampler right away.",
        "PROJECTS" to "Your saved sessions: open, rename, copy or delete them. The one you're in autosaves.",
        "SAVE AS NEW" to "Save this session as a new project; the one you opened stays as it was.",
        "RENAME" to "Give this project a new name.",
        "COPY" to "Make a copy of this project (with its samples).",
                "SETTINGS" to "Settings: projects, theme, the meters strip, hints, start-up defaults, controls, audio status and logs.",
        "LOOK" to "Theme, glass, the meters strip and hints.",
        "DEFAULTS" to "Save the tempo, swing, key and click a fresh launch starts with.",
        "CONTROLS" to "NAV cursor, tilt, the controller map and the welcome tour.",
        "SYSTEM" to "Audio engine status (rate, buffer, dropouts), diagnostics, logs and version.",
        "SAVE AS DEFAULT" to "Start every launch with this tempo, swing, key and click.",
        "▶" to "Play. Starts the song from the top.",
        "■" to "Stop.",
        "●" to "Record. Whatever you play on pads or keys is written into the pattern.",
        // ---- sampler ----
        "AUTO" to "Automation lanes: arm REC, play, and move knobs / faders / FX. Your moves loop. Open to see and clear them.",
        "CLEAR ALL" to "Delete every automation lane.",
        "CHOOSE FOLDER" to "Pick the folder where your samples live (loops, breaks, one-shots). Clamshell Beats only reads it.",
        "RECORD" to "Record a new sample with the Thor's microphone; it lands on the pads, already chopped.",
        "MIC" to "Record from the Thor's microphone (foley, vocals, field sounds) straight onto the pads.",
        "REC" to "Start / stop recording. When you stop, KEEP saves the WAV and chops it onto this sampler track; DISCARD throws it away.",
        "KEEP" to "Save the take to Music/Clamshell Beats/Field Recordings and chop it onto the pads.",
        "DISCARD" to "Throw this take away. Nothing was saved.",
        "REDO" to "Throw this take away and record again.",
        "FOLDER" to "Pick the folder on your device where your samples live.",
        "CHOOSE SAMPLES FOLDER" to "Pick the folder on your device where your samples live.",
        "KEY MATCH" to "Re-pitch the sample into your song's key (the smallest move that fits).",
        "BPM SYNC" to "Time-stretch the sample to your song's tempo without changing its pitch.",
        "USE SAMPLE KEY+BPM" to "Set your song's key and tempo to match this sample. Nothing gets stretched.",
        "×2" to "Detected tempo looks half as fast as it should? Double it.",
        "÷2" to "Detected tempo looks twice as fast as it should? Halve it.",
        "AUTO" to "Chop automatically wherever a hit starts (kicks, snares, notes).",
        "DETECT" to "How eager auto-chop is: higher finds more, smaller slices.",
        "÷4" to "Chop the sample into 4 equal slices.",
        "÷8" to "Chop the sample into 8 equal slices.",
        "÷16" to "Chop the sample into 16 equal slices.",
        "TAP-CHOP" to "Play the sample and tap pads in time to drop a chop at that moment.",
        "PAD" to "The pad you're editing. Tap a pad on the bottom screen to pick another.",
        "−" to "Pitch the selected pad down a semitone.",
        "+" to "Pitch the selected pad up a semitone.",
        "ONE-SHOT" to "Pad plays its whole slice once, even if you let go.",
        "HOLD" to "Pad plays only while your finger is on it.",
        "LOOP" to "Pad repeats its slice for as long as you hold it.",
        // ---- synth pages + knobs ----
        "OSC" to "The two oscillators: the raw tone before any filtering.",
        "SHAPE 1" to "Waveform of oscillator 1: smooth sine → triangle → buzzy saw → hollow square.",
        "SHAPE 2" to "Waveform of oscillator 2.",
        "DETUNE" to "Pull oscillator 2 slightly out of tune for a thicker, wider sound.",
        "MIX" to "Balance between oscillator 1 and 2.",
        "FILTER" to "Shape the brightness: the filter cuts off the high end.",
        "CUTOFF" to "How bright the sound is. Low = dark and muffled, high = open.",
        "RESO" to "Resonance: a whistling peak at the cutoff. High values sound squelchy (acid!).",
        "ENV AMT" to "How much the filter envelope opens the filter on each note.",
        "KEY TRK" to "Higher notes get a brighter filter, like a real instrument.",
        "AMP" to "The volume envelope: how each note starts, holds and fades.",
        "F·ENV" to "The filter envelope: how the brightness moves over each note.",
        "ATTACK" to "How long the note takes to fade in. Short = punchy, long = swelling.",
        "DECAY" to "How long it takes to fall from the peak to the sustain level.",
        "SUSTAIN" to "The level held while you keep the key down.",
        "RELEASE" to "How long the note rings after you let go.",
        "LFO" to "A slow wobble that moves something automatically (pitch, filter, volume or shape).",
        "RATE" to "Wobble speed.",
        "DEPTH" to "Wobble amount.",
        "WAVE" to "Wobble shape: smooth, ramp, square, or random (S&H).",
        "TARGET" to "What the wobble moves: pitch (vibrato), cutoff (wah), amp (tremolo) or shape.",
        "PITCH" to "Pitch drop and octaves. The secret of the 808 boom.",
        "P.ENV" to "Each note starts this many semitones off and slides back. Big values = 808 boom or laser zap.",
        "P.DECAY" to "How fast that pitch drop slides back to the note.",
        "OCT 1" to "Octave of oscillator 1.",
        "OCT 2" to "Octave of oscillator 2.",
        "VOICE" to "How notes behave together: glide, mono/poly, drive and noise.",
        "GLIDE" to "Notes slide into each other (portamento). The 808 slide.",
        "MONO" to "MONO plays one note at a time (basses, leads, slides). POLY plays chords.",
        "DRIVE" to "Saturation: adds grit and loudness.",
        "NOISE" to "Mixes in noise: breath, air, grit.",
        // ---- presets / dice ----
        "‹" to "Previous preset.",
        "›" to "Next preset.",
        "MUTATE" to "Nudge the current sound a little. Keeps its character, adds a surprise.",
        "↶" to "Undo the last change.",
        "SAVE" to "Save this sound (or kit) so you can load it again later.",
        "DICE SOUND" to "Roll new settings for the selected drum sound only.",
        "DICE BEAT" to "Roll a whole new drum beat. Undo with ↶ if you don't like it.",
        "DICE NOTES" to "Roll a new melody for this synth track, in key. The sound stays; ↶ undoes it.",
        "DICE PAD" to "Roll new steps for the selected pad. ↶ undoes it.",
        "DICE" to "Roll something new and playable. Undo with ↶ if you don't like it.",
        "CLEAR" to "Empty the pattern.",
        // ---- drums ----
        "SOUND" to "Tune, length, tone and snap of the selected drum sound.",
        "TUNE" to "Pitch of the drum, in semitones.",
        "TONE" to "Darker ↔ brighter (or body ↔ noise, depending on the drum).",
        "SNAP" to "How hard the hit cracks: the click, the sweep, the sizzle.",
        "MODEL" to "Which kind of drum this sound is: kick, snare, clap, hat, tom, rim, bell, cymbal, zap or shaker.",
        "LEVEL" to "Volume of this sound.",
        "PAN" to "Left ↔ right placement.",
        // ---- sequencer ----
        "FEEL" to "How strongly this track's groove template is applied (0% = straight).",
        "GROOVE" to "This track's feel: MPC 60, GRIT, DRUNK, LAZY, BOOM BAP, TRAP or HOUSE timing + accents.",
        "MPC 60" to "Late 16ths (≈62% swing), soft off-beats: the classic hip-hop bounce.",
        "GRIT" to "Tighter, gritty shuffle (≈56%) with punchy downbeats.",
        "DRUNK" to "Drunk on purpose: every drum on its own clock, nothing quite on the grid.",
        "LAZY" to "Laid back: the snare drags well behind the beat.",
        "BOOM BAP" to "Head-nod swing (≈60%) with the snare sat back a hair.",
        "TRAP" to "Straight timing with rolling hat dynamics.",
        "HOUSE" to "Light shuffle and pushed off-beat 8ths, for EDM hats and chops.",
        "STRAIGHT" to "On the grid, machine-tight.",
        "CLK" to "Metronome click (only you hear it, it's never recorded).",
        "BPM" to "Song tempo. Drag to change, double-tap to reset.",
        "TAP" to "Tap 3+ times in time to set the tempo.",
        "SWING" to "Push every other step late for a laid-back, MPC-style groove.",
        "1/16" to "Swing applies to 16th notes. Tap for 8ths.",
        "1/8" to "Swing applies to 8th notes. Tap for 16ths.",
        "QUANT" to "How hard recorded notes snap to the grid (100% = perfectly on time).",
        "GRID" to "Draw notes on a piano roll.",
        "LANES" to "Edit velocity, gate, chance or note of each step as bars.",
        "VEL" to "Velocity: how hard each step hits.",
        "PROB" to "Chance: how likely each step is to play.",
        "GATE" to "How long each note is held.",
        "NOTE" to "The pitch of each step.",
        "CHANCE" to "Dice also sprinkles steps that only sometimes play.",
        "FIX SCALE" to "Move every note into the current scale.",
        "DENSITY" to "How busy the dice makes the pattern.",
        "UNDO" to "Undo the last pattern change.",
        // ---- FX ----
        "SAVE MIDI" to "Export all tracks as a .mid file in Downloads: drag it into your DAW (drums use General MIDI notes).",
        "ADD TRACK" to "Add a track: pick a sampler, a synth or a drum machine. Up to 8 tracks.",
        "OPEN TRACK" to "Open this track in its editor (sampler, synth or drums).",
        "TRACK MENU" to "Track options: PUMP sidechain, clear the pattern, remove the track.",
        "OPEN" to "Open this track in its editor.",
        "OFF" to "No sidechain on this track.",
        "REMOVE TRACK" to "Delete this track and its pattern.",
        "CLEAR PATTERN" to "Empty this track's pattern.",
        "LATCH" to "Then tap an effect to keep it on without holding it.",
        "XY" to "One XY pad for every latched effect: slide one finger and they all move together.",
        "ALL DRY" to "Every effect off at once, latched ones too.",
        "SAVE LOG" to "Save the app's recent log as a text file in Downloads, to send with a bug report.",
        "SHARE LOG" to "Send the app's recent log to another app (email, chat, Drive).",
        "TILT" to "Lean the Thor to steer the effect you're holding. Shake to roll the dice.",
        "RPT" to "Note repeat: hold this, then hold a pad or key to retrigger it in time.",
    )
    private val keys = entries.keys.sortedByDescending { it.length }

    fun lookup(label: String): Pair<String, String>? {
        val l = label.trim().uppercase()
        if (l.isEmpty()) return null
        val k = keys.firstOrNull { l == it || l.startsWith("$it ") || (it.length > 1 && l.startsWith(it)) } ?: return null
        return k to entries.getValue(k)
    }
}

/** The strip itself: glass pill at the bottom of the top screen, plus the ? toggle in the corner. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BoxScope.InfoLine() {
    val stamp = Info.stamp.intValue
    val show = Info.body.value != null && Info.enabled.value && !com.thorbox.app.input.Nav.on.value
    LaunchedEffect(stamp) { if (Info.body.value != null) { delay(3200); Info.body.value = null } }
    AnimatedVisibility(show, Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).fillMaxWidth(0.86f),
        enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
        Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Tb.Bg.copy(alpha = 0.94f))
            .border(1.dp, Tb.Line, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(Info.title.value, color = Tb.Key, fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 1.sp,
                maxLines = 1, modifier = Modifier.padding(end = 10.dp))
            Text(Info.body.value ?: "", color = Tb.Text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** ? : hints on/off (tap), the welcome tour (long-press). Lives in the rail so it never sits on a control. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InfoToggle(onTour: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.height(30.dp).clip(RoundedCornerShape(8.dp))
        .background(if (Info.enabled.value) Tb.Raised else Tb.Panel)
        .navClickable(label = "?", onLongClick = onTour) { Info.enabled.value = !Info.enabled.value; if (!Info.enabled.value) Info.body.value = null },
        contentAlignment = Alignment.Center) {
        Text("?", color = if (Info.enabled.value) Tb.Key else Tb.Dim, fontWeight = FontWeight.Black, fontSize = 14.sp)
    }
}

/**
 * NAV status: while the cursor is on, the screen that has it shows what's under it and what the
 * buttons do to it right now ("SWING · A grab · R-stick turn"), in a strip both screens keep free at
 * the bottom (NAV_BAR) so it never covers a control; the other screen shows a small marker pointing
 * at where the cursor went. Replaces the hint line while NAV is on (hints are rationed, this isn't).
 */
/** Height of the strip kept free at the bottom of each screen for the NAV status line. */
val NAV_BAR = 30.dp

@Composable
fun BoxScope.NavBadge(state: com.thorbox.app.AppState, thisScreen: Int) {
    val inp = state.input
    if (!inp.navMode.value) return
    val nav = com.thorbox.app.input.Nav
    nav.tick.intValue; nav.current.intValue; nav.grabbed.intValue; nav.entered.intValue      // re-read on every move
    val here = nav.screen.intValue == thisScreen
    if (!here) {
        // a short bright bar on the edge facing the other screen: "the cursor went that way" (covers nothing)
        Box(Modifier.align(if (thisScreen == 0) Alignment.BottomCenter else Alignment.TopCenter).padding(vertical = 2.dp)
            .fillMaxWidth(0.25f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Tb.Text.copy(alpha = 0.8f)))
        return
    }
    // A toast (undo, track, tempo …) wins for a moment; then the cursor's status.
    val stamp = Info.stamp.intValue
    val fresh = androidx.compose.runtime.remember { mutableStateOf(false) }
    LaunchedEffect(stamp) { if (Info.body.value != null) { fresh.value = true; delay(2200); fresh.value = false } }
    val st = nav.status()
    val (title, body) = if (fresh.value && Info.body.value != null) Info.title.value to (Info.body.value ?: "")
        else st ?: ("NAV" to "D-pad to a control")
    // it lives in the strip both screens keep free at the bottom while NAV is on (NAV_BAR)
    Row(Modifier.align(Alignment.BottomCenter).height(NAV_BAR).padding(vertical = 3.dp).fillMaxWidth(0.96f)
        .clip(RoundedCornerShape(8.dp)).background(Tb.Bg.copy(alpha = 0.95f)).border(1.dp, Tb.Text.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
        .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("NAV", color = Tb.Bg, fontWeight = FontWeight.Black, fontSize = 10.sp, letterSpacing = 1.sp,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Tb.Text).padding(horizontal = 5.dp, vertical = 1.dp))
        Text(title, color = Tb.Text, fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 0.5.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp, end = 10.dp).weight(1f, fill = false))
        Text(body, color = Tb.Muted, fontFamily = Tb.Mono, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
