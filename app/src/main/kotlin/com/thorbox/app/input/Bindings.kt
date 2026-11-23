// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.input

import com.thorbox.app.AppState
import com.thorbox.app.TopView
import com.thorbox.model.Deck

/**
 * What the face buttons drive while playing. The instrument editors (SAMPLE · SYNTH · DRUMS) and the
 * editors that *are* the controls (STEPS · MIXER) keep the buttons; everywhere else (VISUAL, or the
 * FX deck under an instrument) the buttons follow the deck under your thumbs, the way a hardware
 * box's pads and cursor always act on what's in front of you.
 */
enum class PlayCtx(val title: String) { SAMPLER("SAMPLE"), SYNTH("SYNTH"), DRUM("DRUMS"), SEQ("STEPS"), SONG("SONG"), MIX("MIXER"), DUCK("DUCK"), FX("FX") }

fun playContext(s: AppState): PlayCtx {
    val deck = s.deck.value
    return when (val v = s.topView.value) {
        TopView.SEQ -> PlayCtx.SEQ
        TopView.MIX -> PlayCtx.MIX
        TopView.DUCK -> PlayCtx.DUCK
        TopView.SONG -> PlayCtx.SONG
        else -> if (deck == Deck.FX) PlayCtx.FX else when (v) {
            TopView.SAMPLER -> PlayCtx.SAMPLER
            TopView.SYNTH -> PlayCtx.SYNTH
            TopView.DRUM -> PlayCtx.DRUM
            else -> when (deck) {                     // VISUAL: the deck decides
                Deck.KEYS -> PlayCtx.SYNTH
                Deck.DRUMS -> PlayCtx.DRUM
                Deck.STEP -> PlayCtx.SEQ
                else -> PlayCtx.SAMPLER
            }
        }
    }
}

/**
 * The button map as text, for the SELECT-hold cheat sheet and SETTINGS → CONTROLS. [InputRouter]
 * implements exactly these: change both together.
 */
object Bindings {
    val shift: List<Pair<String, String>> = listOf(
        "◀ ▶" to "top view",
        "▲ ▼" to "bottom deck",
        "L1 R1" to "previous / next track",
        "L2 R2" to "tempo −1 / +1",
        "A" to "NAV cursor ↔ PLAY",
        "B" to "undo",
        "X" to "tap tempo",
        "Y" to "metronome",
        "START" to "play from bar 1",
        "L3" to "theme",
        "R3" to "diagnostics",
    )
    const val SHIFT_TAP = "tap alone = REC"

    val nav: List<Pair<String, String>> = listOf(
        "D-pad · L-stick" to "move",
        "A" to "press · hold = hold / long-press",
        "A on knob" to "grab: ◀▶ fine · ▲▼ coarse · X reset",
        "A on grid" to "enter: D-pad cell · A play · B out",
        "R-stick" to "turn the focused control",
        "L1 tap · R1" to "top / bottom screen",
        "L1 hold" to "note repeat (play pads / keys with A)",
        "L2 R2" to "previous / next top view",
        "X Y" to "previous / next deck",
        "START" to "play / stop",
        "B" to "close · then leave NAV",
    )

    fun context(c: PlayCtx): List<Pair<String, String>> = when (c) {
        PlayCtx.SAMPLER -> listOf(
            "D-pad" to "choose pad", "A · R1" to "play pad", "L-stick ◀▶" to "pitch ±1",
            "L-stick ▲▼" to "pitch ±12", "X" to "play mode", "Y" to "reset pitch", "L3" to "pad edit", "R3" to "stop sound")
        PlayCtx.SYNTH -> listOf(
            "sticks" to "the 4 knobs", "◀ ▶" to "page", "▲ ▼" to "octave",
            "A · R1" to "play root", "X" to "chord mode", "Y" to "reset page", "L3 · R3" to "next preset · DICE")
        PlayCtx.DRUM -> listOf(
            "◀ ▶" to "step", "▲ ▼" to "sound", "A" to "toggle step", "R1" to "play sound",
            "sticks" to "the 4 knobs", "X · Y" to "dice beat · undo", "L3 · R3" to "next kit · dice kit")
        PlayCtx.SEQ -> listOf(
            "◀ ▶" to "step", "▲ ▼" to "edit lane", "A" to "toggle step", "X" to "dice", "Y" to "undo")
        PlayCtx.MIX -> listOf(
            "◀ ▶" to "channel", "▲ ▼ · L-stick" to "volume", "R-stick" to "pan",
            "A" to "mute", "Y" to "solo", "X" to "open track")
        PlayCtx.SONG -> listOf(
            "D-pad" to "choose pattern", "A" to "launch it (on the beat)", "X" to "+ SONG", "Y" to "PATTERN / SONG mode",
            "L3" to "copy → new pattern", "R3" to "KEY LOCK")
        PlayCtx.DUCK -> listOf(
            "L-stick ◀▶ · ▲▼" to "WEIGHT · SQUASH", "R-stick ◀▶ · ▲▼" to "SNAP · BOUNCE", "▲ ▼" to "PUMP ±5 %",
            "◀ ▶" to "preset", "A" to "play the key", "X" to "next key", "Y" to "ON / OFF")
        PlayCtx.FX -> listOf(
            "D-pad" to "choose effect", "A · R1 hold" to "punch in", "sticks + A" to "sweep X/Y",
            "X" to "latch", "sticks" to "steer latched", "Y" to "ALL DRY")
    }

    val always: List<Pair<String, String>> = listOf(
        "START" to "play / stop",
        "B" to "back",
        "L1 hold" to "note repeat",
        "L2 · R2" to "filter · stutter punch-in",
    )
}
