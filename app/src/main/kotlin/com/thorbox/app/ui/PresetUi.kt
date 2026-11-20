// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.app.input.navClickable

/** One browsable item (a synth preset or a drum kit). */
data class PresetItem(val name: String, val group: Int, val user: Boolean)
/** A category chip: label + colour. Index into this list = PresetItem.group. */
data class PresetGroup(val label: String, val color: Color)

/**
 * The slim strip under a synth/drum header: ◀ NAME ▶ (tap the name to browse), then the OP-XY
 * style dice row. Every control is a big, flat, one-word button.
 */
@Composable
fun PresetBar(
    state: AppState, name: String, groupLabel: String?, groupColor: Color,
    onPrev: () -> Unit, onNext: () -> Unit, onOpen: () -> Unit,
    onDice: () -> Unit, onMutate: () -> Unit, onUndo: () -> Unit, canUndo: Boolean, onSave: () -> Unit,
    modifier: Modifier = Modifier, mutateLabel: String = "MUTATE",
) {
    // The die tumbles a little on every roll: a tiny reward for pressing it.
    var rolls by remember { mutableIntStateOf(0) }
    val spin by animateFloatAsState(rolls * 90f, spring(dampingRatio = 0.45f, stiffness = 300f), label = "die")
    Row(modifier.height(40.dp).glass(state, 12.dp).padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        HwButton("‹", Modifier.fillMaxHeight().width(40.dp), minHeight = 32.dp, fontSize = 20, hPad = 4.dp, onClick = onPrev)
        Row(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(Tb.Void.copy(alpha = 0.55f))
            .navClickable(label = "PRESETS", onClick = onOpen).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(groupColor))
            Text(name, color = Tb.Text, fontFamily = Tb.Mono, fontWeight = FontWeight.Black, fontSize = 14.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).weight(1f))
            if (groupLabel != null) Text(groupLabel, color = groupColor, fontWeight = FontWeight.Black, fontSize = 11.sp, letterSpacing = 1.sp)
            Text("  ▾", color = Tb.Muted, fontSize = 12.sp)
        }
        HwButton("›", Modifier.fillMaxHeight().width(40.dp), minHeight = 32.dp, fontSize = 20, hPad = 4.dp, onClick = onNext)
        Box(Modifier.fillMaxHeight().width(54.dp).clip(RoundedCornerShape(6.dp)).background(Tb.Key)
            .navClickable { rolls++; onDice() }, contentAlignment = Alignment.Center) {
            Die(Modifier.size(20.dp).rotate(spin))
        }
        HwButton(mutateLabel, Modifier.fillMaxHeight(), accent = Tb.EncGreen, minHeight = 32.dp, fontSize = 11) { rolls++; onMutate() }
        HwButton("↶", Modifier.fillMaxHeight().width(40.dp), enabled = canUndo, minHeight = 32.dp, fontSize = 14, onClick = onUndo)
        HwButton("SAVE", Modifier.fillMaxHeight(), accent = Tb.Good, minHeight = 32.dp, fontSize = 11, onClick = onSave)
    }
}

/** A flat die face (five pips), drawn, not an emoji: same look on every firmware. */
@Composable
internal fun Die(modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val r = size.minDimension
        drawRoundRect(Tb.Black, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r * 0.22f))
        val pip = r * 0.09f
        listOf(0.27f to 0.27f, 0.73f to 0.27f, 0.5f to 0.5f, 0.27f to 0.73f, 0.73f to 0.73f).forEach { (x, y) ->
            drawCircle(Tb.Key, pip, androidx.compose.ui.geometry.Offset(r * x, r * y))
        }
    }
}

/**
 * Full-panel browser: category chips across the top, a grid of name tiles, and a save sheet.
 * Long-press one of YOUR presets to delete it (it asks once). Factory presets can't be deleted.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PresetBrowser(
    title: String, groups: List<PresetGroup>, entries: List<PresetItem>, current: String,
    filter: Int?, onFilter: (Int?) -> Unit,
    onPick: (PresetItem) -> Unit, onDelete: (PresetItem) -> Unit, onClose: () -> Unit,
    saving: Boolean, saveName: String, saveGroup: Int, onSave: (String, Int) -> Unit, onCancelSave: () -> Unit,
    modifier: Modifier = Modifier, showGroupsInSave: Boolean = true,
) {
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxSize().background(Tb.Bg.copy(alpha = 0.96f), RoundedCornerShape(14.dp))
        .border(1.dp, Tb.Line, RoundedCornerShape(14.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Tb.Text, fontWeight = FontWeight.Black, fontSize = 15.sp, letterSpacing = 2.sp, modifier = Modifier.padding(end = 6.dp))
            Chip("ALL", Tb.Text, filter == null) { onFilter(null) }
            groups.forEachIndexed { i, g -> Chip(g.label, g.color, filter == i) { onFilter(i) } }
            Box(Modifier.weight(1f))
            HwButton("✕", Modifier.width(44.dp), minHeight = 34.dp, fontSize = 14, onClick = onClose)
        }
        if (saving) {
            SaveSheet(if (showGroupsInSave) groups else emptyList(), saveName, saveGroup, onSave, onCancelSave)
        } else {
            val shown = entries.filter { filter == null || it.group == filter }
            LazyVerticalGrid(GridCells.Adaptive(132.dp), Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shown, key = { (if (it.user) "u:" else "f:") + it.name }) { item ->
                    val g = groups.getOrNull(item.group)
                    val on = item.name == current
                    val asking = confirmDelete == item.name
                    Box(Modifier.height(52.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (asking) Tb.Warn else if (on) Tb.Text else Tb.Panel)
                        .navClickable(
                            onClick = { if (asking) { onDelete(item); confirmDelete = null } else { confirmDelete = null; onPick(item) } },
                            onLongClick = { if (item.user) confirmDelete = item.name })
                        .padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Box(Modifier.align(Alignment.TopStart).size(7.dp).clip(CircleShape).background(g?.color ?: Tb.Muted))
                        if (item.user) Text("YOURS", color = if (on) Tb.Black else Tb.Muted, fontSize = 8.sp, fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp, modifier = Modifier.align(Alignment.TopEnd))
                        Text(if (asking) "TAP TO DELETE" else item.name, color = if (on || asking) Tb.Black else Tb.Text, fontFamily = Tb.Mono,
                            fontWeight = FontWeight.Black, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.align(Alignment.BottomStart))
                    }
                }
            }
            Text("Tap to load · long-press YOURS to delete", color = Tb.Dim, fontSize = 10.sp)
        }
    }
}

@Composable
private fun Chip(label: String, color: Color, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.height(30.dp).clip(RoundedCornerShape(15.dp)).background(if (on) color else Tb.Raised)
        .navClickable(onClick = onClick).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (on) Tb.Black else Tb.Muted, fontWeight = FontWeight.Black, fontSize = 11.sp, letterSpacing = 1.sp)
    }
}

@Composable
private fun SaveSheet(groups: List<PresetGroup>, initialName: String, initialGroup: Int, onSave: (String, Int) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    var group by remember { mutableIntStateOf(initialGroup) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("NAME", color = Tb.Muted, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        BasicTextField(
            value = name, onValueChange = { name = it.uppercase().take(24) }, singleLine = true,
            textStyle = TextStyle(color = Tb.Text, fontFamily = Tb.Mono, fontWeight = FontWeight.Black, fontSize = 26.sp),
            cursorBrush = SolidColor(Tb.Key),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave(name, group) }),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Tb.Void).padding(12.dp),
        )
        if (groups.size > 1) {
            Text("CATEGORY", color = Tb.Muted, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { groups.forEachIndexed { i, g -> Chip(g.label, g.color, group == i) { group = i } } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HwButton("CANCEL", Modifier.weight(1f), minHeight = 48.dp, onClick = onCancel)
            HwButton("SAVE", Modifier.weight(2f), active = true, accent = Tb.Good, minHeight = 48.dp) { onSave(name, group) }
        }
    }
}
