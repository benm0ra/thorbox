// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.app.drums.DrumController
import com.thorbox.engine.NativeEngine
import com.thorbox.model.DRUM_SLOTS

/**
 * Top-screen drum machine. Left: BIT the drummer, playing whatever the track plays. Right: a
 * TR-style grid (8 sounds × 16 steps) in the sounds' own colours. Bottom: four encoders for the
 * selected sound (SOUND page: tune/decay/tone/snap; MIX page: model/drive/level/pan).
 */
@Composable
fun DrumScreen(state: AppState) {
    val dc = state.drums
    val kit = dc.kit.value
    val sel = dc.selected.intValue
    val page = dc.page.intValue
    val voice = kit.voices[sel]
    val knobs = DrumController.knobs(page)
    val dragging = remember { mutableIntStateOf(-1) }
    var browsing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val userKits by state.presets.userKits.collectAsState()
    val all = remember(userKits) { state.presets.allKits() }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // ---- header: pages + selected sound ----
        Row(Modifier.fillMaxWidth().height(40.dp).glass(state, 12.dp).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("DRUMS", color = Tb.Text, fontWeight = FontWeight.Black, fontSize = 14.sp, letterSpacing = 2.sp, modifier = Modifier.padding(end = 4.dp))
            listOf("SOUND", "MIX").forEachIndexed { i, l ->
                Box(Modifier.fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(if (page == i) Tb.Text else Tb.Panel)
                    .clickable { dc.page.intValue = i }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                    Text(l, color = if (page == i) Tb.Black else Tb.Muted, fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 1.sp)
                }
            }
            Box(Modifier.weight(1f))
            Box(Modifier.size(10.dp).clip(CircleShape).background(drumColor(sel)))
            Text(voice.name, color = Tb.Text, fontFamily = Tb.Mono, fontWeight = FontWeight.Black, fontSize = 13.sp)
            Text(voice.model.label, color = drumColor(sel), fontWeight = FontWeight.Black, fontSize = 11.sp, letterSpacing = 1.sp)
        }

        // ---- kit presets + dice ----
        PresetBar(state, kit.name, if (all.firstOrNull { it.name == kit.name }?.user == true) "YOURS" else null, Tb.Pads[0],
            onPrev = { dc.stepKit(-1) }, onNext = { dc.stepKit(+1) }, onOpen = { saving = false; browsing = true },
            onDice = dc::diceKit, onMutate = dc::diceVoice, mutateLabel = "DICE SOUND", onUndo = dc::undoKit, canUndo = dc.canUndo.value,
            onSave = { saving = true; browsing = true }, modifier = Modifier.fillMaxWidth())

        // ---- drummer | grid ----
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(0.85f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(Tb.Void.copy(alpha = 0.78f))) {
                Drummer(state, Modifier.fillMaxSize().padding(4.dp))
            }
            Column(Modifier.weight(1.25f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth().height(26.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    HwButton("DICE BEAT", Modifier.fillMaxHeight(), accent = Tb.Key, minHeight = 24.dp, fontSize = 10) { state.groove.diceBeat() }
                    HwButton("CLEAR", Modifier.fillMaxHeight(), minHeight = 24.dp, fontSize = 10) { state.groove.clear(state.drums.track) }
                    HwButton("↶", Modifier.fillMaxHeight().width(34.dp), enabled = state.groove.canUndo.value, minHeight = 24.dp, fontSize = 11) {
                        state.groove.undo(state.drums.track) }
                    Box(Modifier.weight(1f))
                    val steps = com.thorbox.app.groove.Steps.count(state.groove.clips.value[state.drums.track].lengthTicks)
                    val pages = (steps + 15) / 16
                    if (pages > 1) for (pg in 0 until pages) {
                        val on = state.groove.cursor.intValue / 16 == pg
                        HwButton("${pg + 1}", Modifier.fillMaxHeight().width(30.dp), active = on, accent = Tb.EncGreen, minHeight = 24.dp, fontSize = 10) {
                            state.groove.cursor.intValue = pg * 16 }
                    }
                }
                DrumGrid(state, Modifier.weight(1f).fillMaxWidth())
            }
        }

        GrooveStrip(state, state.drums.track, height = 34.dp)

        // ---- encoders ----
        Row(Modifier.fillMaxWidth().height(100.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            knobs.forEachIndexed { i, k ->
                Encoder(
                    label = k.label, display = k.format(voice), value = k.get(voice), color = Tb.enc(k.color), active = dragging.intValue == i,
                    onDragStart = { dragging.intValue = i }, onDelta = { d -> dc.nudgeKnob(i, d) },
                    onDragEnd = { if (dragging.intValue == i) dragging.intValue = -1 },
                    onReset = { com.thorbox.model.DefaultKit.voices.getOrNull(sel)?.let { def -> dc.setKnob(i, k.get(def.copy(model = voice.model))) } },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
    if (browsing) {
        PresetBrowser(
            title = "KITS", groups = listOf(PresetGroup("FACTORY", Tb.Pads[1]), PresetGroup("YOURS", Tb.Good)),
            entries = all.map { PresetItem(it.name, if (it.user) 1 else 0, it.user) }, current = kit.name,
            filter = null, onFilter = {},
            onPick = { item -> all.firstOrNull { it.name == item.name }?.let(dc::loadKit) },
            onDelete = { state.presets.deleteKit(it.name) }, onClose = { browsing = false; saving = false },
            saving = saving, saveName = kit.name, saveGroup = 1, showGroupsInSave = false,
            onSave = { name, _ -> dc.saveKit(name); saving = false }, onCancelSave = { saving = false; browsing = false },
            modifier = Modifier.padding(4.dp),
        )
    }
    }
}

fun drumColor(slot: Int) = Tb.Pads[slot % Tb.Pads.size]

/**
 * TR grid. Rows = sounds (tap the colour chip to select + hear it), columns = steps (tap to
 * toggle). The playing step glows; the gamepad cursor is a white outline.
 */
@Composable
fun DrumGrid(state: AppState, modifier: Modifier = Modifier) {
    val g = state.groove
    val dc = state.drums
    val tel = state.telemetry
    val track = state.drums.track
    val clip = g.clips.value[track]
    val steps = com.thorbox.app.groove.Steps.count(clip.lengthTicks)
    val labelW = 34.dp
    Row(modifier) {
        // row chips
        Column(Modifier.width(labelW).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (s in 0 until DRUM_SLOTS) {
                val on = dc.selected.intValue == s
                Box(Modifier.weight(1f).fillMaxWidth().padding(end = 3.dp).clip(RoundedCornerShape(4.dp))
                    .background(if (on) drumColor(s) else drumColor(s).copy(alpha = 0.28f))
                    .clickable { dc.hit(s, 0.85f); dc.release(s) }, contentAlignment = Alignment.Center) {
                    Text(dc.kit.value.voices[s].name.take(4), color = if (on) Tb.Black else Tb.Text, fontSize = 8.sp, fontWeight = FontWeight.Black, maxLines = 1)
                }
            }
        }
        Canvas(Modifier.weight(1f).fillMaxHeight()
            .pointerInput(clip.lengthTicks) {
                detectTapGestures { p ->
                    val cw = size.width / 16f; val rh = size.height / DRUM_SLOTS.toFloat()
                    val col = (p.x / cw).toInt().coerceIn(0, 15); val row = (p.y / rh).toInt().coerceIn(0, DRUM_SLOTS - 1)
                    val step = (g.cursor.intValue / 16) * 16 + col
                    if (step < steps) { g.drumToggle(step, row); dc.selected.intValue = row; g.cursor.intValue = step }
                }
            }) {
            tel.frame.longValue
            val notes = g.clips.value[track].notes
            val len = g.clips.value[track].lengthTicks
            val pageStart = (g.cursor.intValue / 16) * 16
            val cw = size.width / 16f; val rh = size.height / DRUM_SLOTS
            val gap = 2.dp.toPx(); val r = CornerRadius(4.dp.toPx())
            val playStep = g.stepPosition(track)
            for (c in 0 until 16) {
                val step = pageStart + c
                if (step >= steps) break
                val beat = c / 4 % 2 == 0
                if (step == playStep) drawRect(Tb.Text.copy(alpha = 0.10f), Offset(c * cw, 0f), Size(cw, size.height))
                for (row in 0 until DRUM_SLOTS) {
                    val o = Offset(c * cw + gap, row * rh + gap); val sz = Size(cw - 2 * gap, rh - 2 * gap)
                    drawRoundRect(if (beat) Tb.Raised else Tb.Panel, o, sz, r)
                }
            }
            for (e in notes) {
                if (e.note !in 0 until DRUM_SLOTS) continue
                val step = com.thorbox.app.groove.Steps.stepOf(e, len)
                val c = step - pageStart
                if (c !in 0 until 16) continue
                val o = Offset(c * cw + gap, e.note * rh + gap); val sz = Size(cw - 2 * gap, rh - 2 * gap)
                val lit = step == playStep
                drawRoundRect(drumColor(e.note).copy(alpha = if (lit) 1f else 0.45f + 0.5f * e.velocity), o, sz, r)
                if (e.ratchet > 1) for (k in 1 until e.ratchet)   // ratchets: little ticks
                    drawLine(Tb.Black.copy(alpha = 0.6f), Offset(o.x + sz.width * k / e.ratchet, o.y + 3f), Offset(o.x + sz.width * k / e.ratchet, o.y + sz.height - 3f), 2f)
            }
            // gamepad cursor
            val cc = g.cursor.intValue - pageStart
            if (cc in 0 until 16 && state.topView.value == com.thorbox.app.TopView.DRUM)
                drawRoundRect(Tb.Text, Offset(cc * cw + 1f, dc.selected.intValue * rh + 1f), Size(cw - 2f, rh - 2f), r, style = Stroke(2.dp.toPx()))
        }
    }
}
