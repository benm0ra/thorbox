// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.app.TopView
import com.thorbox.app.input.navModal
import com.thorbox.app.input.navTarget
import androidx.compose.ui.focus.focusRequester
import com.thorbox.model.Deck
import com.thorbox.model.TrackKind
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

/*
 * Design tokens: the one place sizes, corners, type and section colours come from, so every
 * screen reads as panels of the same instrument. New UI should use these instead of literals.
 */

/**
 * True when the top screen's content area is wide (the Thor's 6" panel); false in the narrow
 * single-screen fallback. Headers drop their name tag and other extras when it's false.
 */
val LocalWide = androidx.compose.runtime.compositionLocalOf { true }
val WIDE = 520.dp

/**
 * A text field the NAV cursor can reach: A focuses it, which brings up the on-screen keyboard
 * (the keyboard itself is driven with the D-pad like any Android keyboard).
 */
fun Modifier.navTextField(label: String): Modifier = composed {
    val fr = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    focusRequester(fr).navTarget(label, action = { runCatching { fr.requestFocus() } })
}

/** Spacing steps. */
object Space { val xs = 4.dp; val s = 6.dp; val m = 8.dp; val l = 12.dp; val xl = 16.dp }

/** Corner radii by role. */
object Radius {
    val control = 6.dp      // buttons, value chips, tabs
    val pad = 10.dp         // pads, keys, FX tiles
    val panel = 14.dp       // bezels, glass panels, strips
    val sheet = 18.dp       // sheets and dialogs
}

/** Control heights by role. */
object Heights {
    val touch = 44.dp       // anything a thumb hits on its own
    val header = 40.dp      // a screen's header row
    val compact = 36.dp     // dense strips (16 LEVELS, groove)
    val rail = 28.dp        // the top screen's view rail
}

/** Type roles. Labels are bold caps with tracking; values are mono. */
object Type {
    val label get() = Tb.Label
    val title get() = TextStyle(color = Tb.Text, fontFamily = Tb.Display, fontWeight = if (Tb.palette.monoUi) FontWeight.Black else FontWeight.Normal,
        fontSize = 16.sp, letterSpacing = 1.sp, lineHeight = 1.15.em, lineHeightStyle = Tb.DisplayLines,
        // the hard block under a header; on paper (light themes) dark-on-dark doubles up, so it's left off there
        shadow = if (Tb.palette.light) null else androidx.compose.ui.graphics.Shadow(Tb.BlockShadow, androidx.compose.ui.geometry.Offset(2.5f, 2.5f), 0f))
    val body get() = TextStyle(color = Tb.Text, fontSize = 12.sp)
    val value get() = TextStyle(color = Tb.Text, fontFamily = Tb.Mono, fontWeight = FontWeight.Black, fontSize = 13.sp)
    val valueLg get() = TextStyle(color = Tb.Text, fontFamily = Tb.Mono, fontWeight = FontWeight.Black, fontSize = 15.sp)
    val readout get() = Tb.Readout
}

/** The instrument's sections. Each has one colour, used by the rail, the deck tabs, the mixer and headers. */
enum class Section(val title: String) { SAMPLE("SAMPLE"), SYNTH("SYNTH"), DRUMS("DRUMS"), STEPS("STEPS"), MIXER("MIXER"), FX("FX"), VISUAL("VISUAL") }

/** The section's colour in the current theme (chosen per theme to stay apart from each other and from the key accent). */
val Section.color: Color get() = Tb.palette.sections.getOrNull(ordinal) ?: Tb.Pads[ordinal % Tb.Pads.size]

val TopView.section: Section get() = when (this) {
    TopView.SAMPLER -> Section.SAMPLE; TopView.SYNTH -> Section.SYNTH; TopView.DRUM -> Section.DRUMS
    TopView.SEQ, TopView.SONG -> Section.STEPS; TopView.MIX, TopView.DUCK -> Section.MIXER; TopView.VIZ -> Section.VISUAL
}
val Deck.section: Section get() = when (this) {
    Deck.PADS -> Section.SAMPLE; Deck.KEYS, Deck.STRUM -> Section.SYNTH; Deck.DRUMS -> Section.DRUMS
    Deck.STEP -> Section.STEPS; Deck.FX -> Section.FX
}
val TrackKind.section: Section? get() = when (this) {
    TrackKind.SAMPLER -> Section.SAMPLE; TrackKind.SYNTH -> Section.SYNTH; TrackKind.DRUMS -> Section.DRUMS; TrackKind.EMPTY -> null
}

/**
 * The instrument's "screen": a dark inset with a hard edge, like the display window in a hardware
 * panel. Waveforms, grids, the synth's scope and the drummer all sit in one.
 */
fun Modifier.bezel(corner: Dp = Radius.panel): Modifier =
    graffFrame(corner, block = 4.dp).clip(RoundedCornerShape(corner)).background(Tb.Void.copy(alpha = 0.82f))

/**
 * NIGHT LINE '79 chrome (docs/ART_STYLE.md): a hard [block] down-right behind the element, a keyline round
 * it, and, when [active], a white shine tick in its top-left corner. Put it BEFORE the element's clip, so
 * the block can sit outside the bounds. [pressed] drops the element onto its block.
 */
fun Modifier.graffFrame(corner: Dp, active: Boolean = false, pressed: Boolean = false, block: Dp = 3.dp, line: Dp = 1.5.dp): Modifier =
    this.drawWithContent {
        val r = androidx.compose.ui.geometry.CornerRadius(corner.toPx())
        val b = block.toPx(); val lw = line.toPx()
        if (!pressed && b > 0f) drawRoundRect(Tb.BlockShadow, androidx.compose.ui.geometry.Offset(b, b), size, r)
        val d = if (pressed) b * 0.8f else 0f
        translate(d, d) {
            this@drawWithContent.drawContent()
            drawRoundRect(Tb.Keyline, androidx.compose.ui.geometry.Offset(lw / 2f, lw / 2f),
                androidx.compose.ui.geometry.Size(size.width - lw, size.height - lw),
                androidx.compose.ui.geometry.CornerRadius((corner.toPx() - lw / 2f).coerceAtLeast(0f)),
                style = androidx.compose.ui.graphics.drawscope.Stroke(lw))
            if (active && size.minDimension > 20.dp.toPx()) {
                val x = 7.dp.toPx(); val y = 9.dp.toPx(); val l = 6.dp.toPx()
                drawLine(Color.White.copy(alpha = 0.9f), androidx.compose.ui.geometry.Offset(x, y),
                    androidx.compose.ui.geometry.Offset(x + l * 0.77f, y - l * 0.64f), 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
    }

/** A sheet / dialog panel: same backing, corner and edge everywhere, and B closes it ([onClose]). */
fun Modifier.sheet(corner: Dp = Radius.sheet, onClose: (() -> Unit)?): Modifier =
    navModal(onClose).clip(RoundedCornerShape(corner)).background(Tb.Bg.copy(alpha = 0.97f)).border(1.dp, Tb.Line, RoundedCornerShape(corner))

/**
 * A screen's name tag, first thing in its header row: a bar in the section colour and the name,
 * plus a REC light while recording, so every screen says where you are the same way.
 */
@Composable
fun SectionTag(state: AppState, section: Section, modifier: Modifier = Modifier) {
    Row(modifier.padding(end = Space.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        Box(Modifier.width(4.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(section.color))
        Text(section.title, style = Type.title, maxLines = 1)
        if (state.groove.recording.value) Box(Modifier.size(8.dp).clip(CircleShape).background(Tb.Warn))
    }
}

/**
 * One line of text that shrinks (down to [minSize]) instead of being cut off when its box is too
 * narrow, so "DRUMS" never turns into "DRU". It measures once per size change, not per frame.
 */
@Composable
fun FitText(text: String, style: TextStyle, modifier: Modifier = Modifier, minSize: Float = 8f) {
    var size by remember(text, style.fontSize) { mutableFloatStateOf(style.fontSize.value) }
    var ready by remember(text, style.fontSize) { androidx.compose.runtime.mutableStateOf(false) }
    Text(text, style = style.copy(fontSize = size.sp), maxLines = 1, softWrap = false,
        modifier = modifier.drawWithContent { if (ready) drawContent() },
        onTextLayout = { r ->
            if (r.didOverflowWidth && size > minSize) size = (size - 1f).coerceAtLeast(minSize) else ready = true
        })
}
