// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.app.input.navClickable
import com.thorbox.app.synth.SynthPage
import com.thorbox.app.synth.SynthParams

/**
 * Top-screen synth view. Layout (TE-style): a header with the page name and voice LEDs, one
 * hero animation for the current page, and four colour-capped encoders that match the
 * colours drawn above them. While an encoder is held, its name and value fill the corner of the hero.
 */
@Composable
fun SynthScreen(state: AppState) {
    val patch by state.patch.collectAsState()
    val page = state.synthPage.value
    val knobs = SynthParams.pages.getValue(page)
    val dragging = remember { mutableIntStateOf(-1) }
    val lastTouched = remember { mutableIntStateOf(0) }
    val overlay by animateFloatAsState(if (dragging.intValue >= 0) 1f else 0f, tween(if (dragging.intValue >= 0) 80 else 700), label = "overlay")
    var browsing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val userPresets by state.presets.userSynth.collectAsState()
    val all = remember(userPresets) { state.presets.allSynth() }
    val currentPreset = all.firstOrNull { it.name == patch.name }
    val cat = currentPreset?.category
    // The sound family drives the cat's scene: the preset's own, else the preset it was edited from, else a guess from the patch.
    val guessed = remember(patch) { com.thorbox.model.guessCategory(patch) }
    val family = cat ?: all.firstOrNull { it.name == patch.name.removeSuffix("*") }?.category ?: guessed

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {

        // ---- header ----
        Row(Modifier.fillMaxWidth().height(Heights.header).glass(state, 12.dp).padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (LocalWide.current) SectionTag(state, Section.SYNTH)
            SynthPage.entries.forEach { p ->
                val on = p == page
                Box(Modifier.fillMaxHeight().clip(RoundedCornerShape(5.dp))
                    .background(if (on) Tb.Text else Tb.Panel)
                    .navClickable(label = p.label) { state.synthPage.value = p }
                    .padding(horizontal = 9.dp), contentAlignment = Alignment.Center) {
                    Text(p.label, color = if (on) Tb.Black else Tb.Muted, fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 1.sp)
                }
            }
            Box(Modifier.weight(1f))
            // The sound dice lives in the preset bar below; this one rolls the track's NOTES (same as on STEPS).
            Box(Modifier.fillMaxHeight().padding(vertical = 5.dp)) { Row { PatternDice(state, state.groove.synthT, height = 34.dp, fontSize = 11) } }
        }

        // ---- presets + dice ----
        PresetBar(state, patch.name, cat?.label, cat?.let { synthCategoryColor(it) } ?: Tb.Key,
            onPrev = { state.stepSynthPreset(-1) }, onNext = { state.stepSynthPreset(+1) }, onOpen = { saving = false; browsing = true },
            onDice = state::diceSynth, onMutate = { state.mutateSynth() }, onUndo = state::undoPatch, canUndo = state.canUndoPatch.value,
            onSave = { saving = true; browsing = true }, modifier = Modifier.fillMaxWidth())

        // ---- hero: the instrument's "screen" (bezel · status line · page art · live waveform band) ----
        val swipe = remember { floatArrayOf(0f) }
        Box(Modifier.weight(1f).fillMaxWidth().bezel()
            .pointerInput(Unit) {
                // Swipe the screen sideways to flip pages (the tabs still work too).
                detectHorizontalDragGestures(onDragStart = { swipe[0] = 0f }) { ch, dx ->
                    swipe[0] += dx
                    val step = 70.dp.toPx()
                    if (kotlin.math.abs(swipe[0]) > step) { state.cyclePage(if (swipe[0] < 0) 1 else -1); swipe[0] = 0f; ch.consume() }
                }
            }) {
            HeroStatus(state, page, Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp))
            // The page's animation keeps one half of the screen; the cat making biscuits gets the other.
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().padding(top = 26.dp)) {
                val side = maxWidth > maxHeight * 1.1f
                val vizHalf: @Composable (Modifier) -> Unit = { hm ->
                    Box(hm) {
                        ScopeBand(state, Tb.enc(knobs[lastTouched.intValue.coerceIn(0, 3)].color), overlay,
                            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(34.dp).padding(horizontal = 12.dp, vertical = 4.dp))
                        Crossfade(targetState = page, animationSpec = tween(160), label = "page") { pg ->
                            val m = Modifier.fillMaxSize().padding(start = 10.dp, end = 6.dp, top = 2.dp, bottom = 38.dp)
                            when (pg) {
                                SynthPage.OSC -> OscViz(patch, state.telemetry, m)
                                SynthPage.FILTER -> FilterViz(patch, state.telemetry, m)
                                SynthPage.AMP -> EnvViz(patch, state.telemetry, amp = true, m)
                                SynthPage.FENV -> EnvViz(patch, state.telemetry, amp = false, m)
                                SynthPage.LFO -> LfoViz(patch, state.telemetry, m)
                                SynthPage.PITCH -> PitchViz(patch, state.telemetry, m)
                                SynthPage.VOICE -> VoiceViz(patch, state.telemetry, m)
                            }
                        }
                        // Focus readout for the encoder being turned
                        val k = knobs[lastTouched.intValue.coerceIn(0, 3)]
                        Column(Modifier.align(Alignment.TopEnd).padding(6.dp).alpha(overlay).glass(state, 12.dp).padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.End) {
                            Text(k.label, color = Tb.enc(k.color), fontWeight = FontWeight.Black, fontSize = 13.sp, letterSpacing = 1.5.sp)
                            Text(k.format(patch), color = Tb.Text, fontFamily = Tb.Mono, fontWeight = FontWeight.Black, fontSize = 30.sp)
                        }
                    }
                }
                if (side) Row(Modifier.fillMaxSize()) {
                    vizHalf(Modifier.weight(1f).fillMaxHeight())
                    Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 12.dp).background(Tb.Line))
                    BiscuitCat(state, family, synthCategoryColor(family), patch,
                        catHint(knobs[lastTouched.intValue.coerceIn(0, 3)].label, patch), overlay, Modifier.weight(1f).fillMaxHeight())
                } else Column(Modifier.fillMaxSize()) {
                    vizHalf(Modifier.weight(1f).fillMaxWidth())
                    Box(Modifier.height(1.dp).fillMaxWidth().padding(horizontal = 12.dp).background(Tb.Line))
                    BiscuitCat(state, family, synthCategoryColor(family), patch,
                        catHint(knobs[lastTouched.intValue.coerceIn(0, 3)].label, patch), overlay, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }

        // ---- this track's automation, right above the knobs that write it ----
        TrackLanes(state, state.synthTrack.intValue)

        // ---- encoders ----
        Row(Modifier.fillMaxWidth().height(104.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            knobs.forEachIndexed { i, knob ->
                Encoder(
                    label = knob.label, display = knob.format(patch), value = knob.get(patch),
                    color = Tb.enc(knob.color), active = dragging.intValue == i,
                    onDragStart = { dragging.intValue = i; lastTouched.intValue = i },
                    onDelta = { d -> state.nudgeKnob(page, i, d) },
                    onDragEnd = { if (dragging.intValue == i) dragging.intValue = -1 },
                    onReset = { lastTouched.intValue = i; state.setKnob(page, i, knob.get(com.thorbox.model.SynthPatch())) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
    if (browsing) {
        val cats = com.thorbox.model.SynthCategory.entries
        PresetBrowser(
            title = "SOUNDS", groups = cats.map { PresetGroup(it.label, synthCategoryColor(it)) },
            entries = all.map { PresetItem(it.name, it.category.ordinal, it.user) }, current = patch.name,
            filter = state.synthCategory.value?.ordinal, onFilter = { i -> state.synthCategory.value = i?.let { cats[it] } },
            onPick = { item -> all.firstOrNull { it.name == item.name }?.let(state::loadSynthPreset) },
            onDelete = { state.presets.deleteSynth(it.name) }, onClose = { browsing = false; saving = false },
            saving = saving, saveName = patch.name.removeSuffix("*"), saveGroup = (cat ?: state.synthCategory.value ?: com.thorbox.model.SynthCategory.LEAD).ordinal,
            onSave = { name, g -> state.saveSynthPreset(name, cats[g]); saving = false },
            onCancelSave = { saving = false; browsing = false },
            modifier = Modifier.padding(4.dp),
        )
    }
    }
}

/** One colour per sound family, shared by chips, tiles and the preset bar dot. */
fun synthCategoryColor(c: com.thorbox.model.SynthCategory) = when (c) {
    com.thorbox.model.SynthCategory.BASS -> Tb.EncBlue
    com.thorbox.model.SynthCategory.SUB808 -> Tb.Warn
    com.thorbox.model.SynthCategory.LEAD -> Tb.EncOrange
    com.thorbox.model.SynthCategory.KEYS -> Tb.Key
    com.thorbox.model.SynthCategory.PAD -> Tb.EncGreen
    com.thorbox.model.SynthCategory.PLUCK -> Tb.Tempo
    com.thorbox.model.SynthCategory.WEIRD -> Tb.Pads[6]
}

/** Eight voice LEDs: lit count = sounding voices, brightness follows the newest envelope. */
@Composable
private fun VoiceLeds(state: AppState, modifier: Modifier) {
    val tel = state.telemetry
    Canvas(modifier) {
        tel.frame.longValue
        val n = 8
        val step = size.width / n
        val r = minOf(step * 0.32f, size.height / 2f)
        val lit = tel.activeVoices
        val glow = 0.35f + 0.65f * tel.ampLevel
        for (i in 0 until n) {
            val c = Offset(step * (i + 0.5f), size.height / 2f)
            drawCircle(if (i < lit) Tb.EncOrange.copy(alpha = glow) else Tb.Raised, r, c)
        }
    }
}

/** One accent per page, so the screen's status line and dots say where you are at a glance. */
fun pageColor(p: SynthPage) = when (p) {
    SynthPage.OSC, SynthPage.PITCH -> Tb.EncBlue
    SynthPage.FILTER, SynthPage.VOICE -> Tb.EncOrange
    SynthPage.AMP, SynthPage.FENV -> Tb.EncGreen
    SynthPage.LFO -> Tb.EncWhite
}

/** Status line: PAGE · page dots · track · voice LEDs. */
@Composable
private fun HeroStatus(state: AppState, page: SynthPage, modifier: Modifier) {
    Row(modifier.height(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(page.label, color = pageColor(page), fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 2.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            SynthPage.entries.forEach { p ->
                Box(Modifier.size(if (p == page) 8.dp else 5.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (p == page) pageColor(p) else Tb.Dim))
            }
        }
        Box(Modifier.weight(1f))
        Text(state.tracks.value.getOrNull(state.synthTrack.intValue)?.name ?: "SYNTH", color = Tb.Muted, fontSize = 10.sp,
            fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        VoiceLeds(state, Modifier.width(64.dp).height(12.dp))
    }
}

/**
 * Live waveform band along the bottom of the screen: the synth's actual output, drawn as mirrored
 * bars (TE-style). Takes the colour of the knob you're turning, so you see the sound change.
 */
@Composable
private fun ScopeBand(state: AppState, accent: androidx.compose.ui.graphics.Color, active: Float, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val tel = state.telemetry
        tel.frame.longValue
        val buf = tel.scope                                     // fetched once per frame by AppState
        val n = tel.scopeCount.coerceIn(0, buf.size)
        val bars = 64
        val bw = size.width / bars
        val mid = size.height / 2f
        val c = if (active > 0.05f) accent else Tb.Text.copy(alpha = 0.55f)
        for (b in 0 until bars) {
            var pk = 0f
            val from = b * n / bars; val to = ((b + 1) * n / bars).coerceAtMost(n)
            for (i in from until to) pk = maxOf(pk, kotlin.math.abs(buf[i]))
            val h = (pk * 2.2f).coerceIn(0.02f, 1f) * mid
            drawRoundRect(c, androidx.compose.ui.geometry.Offset(b * bw + bw * 0.2f, mid - h),
                androidx.compose.ui.geometry.Size(bw * 0.6f, 2 * h), androidx.compose.ui.geometry.CornerRadius(bw * 0.3f))
        }
    }
}
