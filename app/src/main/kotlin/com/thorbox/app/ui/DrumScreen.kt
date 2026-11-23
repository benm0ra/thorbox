// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import com.thorbox.app.input.navTarget
import com.thorbox.app.input.NavGrid
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
import com.thorbox.app.input.navClickable
import com.thorbox.app.drums.DrumController
import com.thorbox.engine.NativeEngine

/**
 * Top-screen drum machine. Left: BIG DAWG the drummer, playing whatever the track plays. Right: a
 * step grid (8 sounds × 16 steps) in the sounds' own colours. Bottom: four encoders for the
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
        Row(Modifier.fillMaxWidth().height(Heights.header).glass(state, 12.dp).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTag(state, Section.DRUMS)
            listOf("SOUND", "MIX", "DUST").forEachIndexed { i, l ->
                // DUST is kit-wide; it lights amber while any of it is on, so you can see the kit is "dirty".
                val dusty = i == DrumController.PAGE_DUST && kit.bus.let { it.crunch + it.muffle + it.tape + it.dust > 0.01f }
                Box(Modifier.fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(if (page == i) Tb.Text else Tb.Panel)
                    .navClickable(label = l) { dc.page.intValue = i; if (i == DrumController.PAGE_DUST) Info.show("DUST") }.padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (dusty) Box(Modifier.size(6.dp).clip(CircleShape).background(Tb.Key))
                        Text(l, color = if (page == i) Tb.Black else Tb.Muted, fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 1.sp)
                    }
                }
            }
            Box(Modifier.weight(1f))
            if (page == DrumController.PAGE_DUST) {
                Text("WHOLE KIT", color = Tb.Key, fontWeight = FontWeight.Black, fontSize = 11.sp, letterSpacing = 1.sp)
            } else {
                Box(Modifier.size(10.dp).clip(CircleShape).background(drumColor(sel)))
                Text(voice.name, color = Tb.Text, fontFamily = Tb.Mono, fontWeight = FontWeight.Black, fontSize = 13.sp, maxLines = 1)
                // the engine model, unless the name already says it ("KICK" · KICK was said twice)
                if (!voice.name.uppercase().contains(voice.model.label.uppercase()))
                    Text(voice.model.label, color = drumColor(sel), fontWeight = FontWeight.Black, fontSize = 11.sp, letterSpacing = 1.sp, maxLines = 1)
            }
        }

        // ---- kit presets + dice ----
        PresetBar(state, kit.name, if (all.firstOrNull { it.name == kit.name }?.user == true) "YOURS" else null, Tb.Pads[0],
            onPrev = { dc.stepKit(-1) }, onNext = { dc.stepKit(+1) }, onOpen = { saving = false; browsing = true },
            onDice = dc::diceKit, onMutate = dc::diceVoice, mutateLabel = "DICE SOUND", onUndo = dc::undoKit, canUndo = dc.canUndo.value,
            onSave = { saving = true; browsing = true }, modifier = Modifier.fillMaxWidth())

        // ---- drummer | grid ----
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(0.85f).fillMaxHeight().bezel()) {
                Drummer(state, Modifier.fillMaxSize().padding(4.dp))
            }
            Column(Modifier.weight(1.25f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth().height(26.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PatternDice(state, state.drums.track, height = 26.dp, fontSize = 10)
                    HwButton("CLEAR", Modifier.fillMaxHeight(), minHeight = 24.dp, fontSize = 10) { state.groove.clear(state.drums.track) }
                    // pad banks: A = 1–8, B = 9–16; a dot means the other bank has hits in it
                    val bank = dc.selected.intValue / 8
                    val notes = state.groove.clips.value[state.drums.track].notes
                    for (b in 0..1) {
                        val has = notes.any { it.note / 8 == b }
                        HwButton((if (b == 0) "A" else "B") + if (has && b != bank) "•" else "", Modifier.fillMaxHeight().width(36.dp), active = bank == b,
                            accent = Tb.EncBlue, minHeight = 24.dp, fontSize = 10, hPad = 2.dp) { dc.selected.intValue = b * 8 + dc.selected.intValue % 8 }
                    }
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
        TrackLanes(state, state.drums.track, height = 30.dp)

        // ---- encoders ----
        Row(Modifier.fillMaxWidth().height(92.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (page == DrumController.PAGE_DUST) DrumController.busKnobs.forEachIndexed { i, k ->
                Encoder(
                    label = k.label, display = DrumController.busFormat(k.get(kit.bus)), value = k.get(kit.bus), color = Tb.enc(k.color), active = dragging.intValue == i,
                    onDragStart = { dragging.intValue = i },
                    onDelta = { d -> dc.nudgeKnob(i, d) },
                    onDragEnd = { if (dragging.intValue == i) dragging.intValue = -1 },
                    onReset = { dc.setKnob(i, 0f) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            } else knobs.forEachIndexed { i, k ->
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
    val labelW = 54.dp
    // 16 pads, 8 rows at a time: bank A = pads 1–8, B = 9–16 (follows the selected pad)
    val bankStart = (dc.selected.intValue / 8) * 8
    Row(modifier) {
        // row chips
        Column(Modifier.width(labelW).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (s in bankStart until bankStart + 8) {
                val on = dc.selected.intValue == s
                Box(Modifier.weight(1f).fillMaxWidth().padding(end = 3.dp).clip(RoundedCornerShape(4.dp))
                    .background(if (on) drumColor(s) else drumColor(s).copy(alpha = 0.28f))
                    .navClickable(label = dc.kit.value.voices[s].name) { dc.selected.intValue = s; dc.hit(s, 0.85f); dc.release(s) }, contentAlignment = Alignment.Center) {
                    // the whole name, shrunk to fit and centred (it used to be cut to 4 letters and sat low in the chip)
                    FitText(dc.kit.value.voices[s].name, androidx.compose.ui.text.TextStyle(color = if (on) Tb.Black else Tb.Text, fontSize = 9.sp,
                        fontWeight = FontWeight.Black, lineHeight = 9.sp), modifier = Modifier.padding(horizontal = 3.dp), minSize = 6f)
                }
            }
        }
        // NAV: A enters the grid (16 steps × all 16 sounds; the bank follows the row), A toggles a step.
        val navGrid = NavGrid(16, com.thorbox.model.DRUM_SLOTS,
            get = { dc.selected.intValue * 16 + g.cursor.intValue % 16 },
            set = { c -> dc.selected.intValue = c / 16; g.cursor.intValue = (g.cursor.intValue / 16) * 16 + c % 16 },
            down = { c -> val step = (g.cursor.intValue / 16) * 16 + c % 16; if (step < steps) g.drumToggle(step, c / 16) },
            describe = { c -> "${dc.kit.value.voices.getOrNull(c / 16)?.name ?: ""} · step ${(g.cursor.intValue / 16) * 16 + c % 16 + 1}" },
            verb = "toggle", tag = "drumgrid")
        Canvas(Modifier.weight(1f).fillMaxHeight()
            .navTarget("STEPS", grid = navGrid, corner = 4f)
            .pointerInput(clip.lengthTicks, bankStart) {
                detectTapGestures { p ->
                    val cw = size.width / 16f; val rh = size.height / 8f
                    val col = (p.x / cw).toInt().coerceIn(0, 15); val row = bankStart + (p.y / rh).toInt().coerceIn(0, 7)
                    val step = (g.cursor.intValue / 16) * 16 + col
                    if (step < steps) { g.drumToggle(step, row); dc.selected.intValue = row; g.cursor.intValue = step }
                }
            }) {
            tel.frame.longValue
            val notes = g.clips.value[track].notes
            val len = g.clips.value[track].lengthTicks
            val pageStart = (g.cursor.intValue / 16) * 16
            val cw = size.width / 16f; val rh = size.height / 8f
            val gap = 2.dp.toPx(); val r = CornerRadius(4.dp.toPx())
            val playStep = g.stepPosition(track)
            for (c in 0 until 16) {
                val step = pageStart + c
                if (step >= steps) break
                val beat = c / 4 % 2 == 0
                if (step == playStep) drawRect(Tb.Text.copy(alpha = 0.10f), Offset(c * cw, 0f), Size(cw, size.height))
                for (row in 0 until 8) {
                    val o = Offset(c * cw + gap, row * rh + gap); val sz = Size(cw - 2 * gap, rh - 2 * gap)
                    drawRoundRect(if (beat) Tb.Raised else Tb.Panel, o, sz, r)
                }
            }
            for (e in notes) {
                if (e.note !in bankStart until bankStart + 8) continue
                val step = com.thorbox.app.groove.Steps.stepOf(e, len)
                val c = step - pageStart
                if (c !in 0 until 16) continue
                val o = Offset(c * cw + gap, (e.note - bankStart) * rh + gap); val sz = Size(cw - 2 * gap, rh - 2 * gap)
                val lit = step == playStep
                drawRoundRect(drumColor(e.note).copy(alpha = if (lit) 1f else 0.45f + 0.5f * e.velocity), o, sz, r)
                if (e.ratchet > 1) for (k in 1 until e.ratchet)   // ratchets: little ticks
                    drawLine(Tb.Black.copy(alpha = 0.6f), Offset(o.x + sz.width * k / e.ratchet, o.y + 3f), Offset(o.x + sz.width * k / e.ratchet, o.y + sz.height - 3f), 2f)
            }
            // gamepad cursor
            val cc = g.cursor.intValue - pageStart
            com.thorbox.app.input.Nav.entered.intValue
            if (cc in 0 until 16 && (state.topView.value == com.thorbox.app.TopView.DRUM || com.thorbox.app.input.Nav.enteredTag() == "drumgrid"))
                drawRoundRect(Tb.Text, Offset(cc * cw + 1f, (dc.selected.intValue - bankStart) * rh + 1f), Size(cw - 2f, rh - 2f), r, style = Stroke(2.dp.toPx()))
        }
    }
}
