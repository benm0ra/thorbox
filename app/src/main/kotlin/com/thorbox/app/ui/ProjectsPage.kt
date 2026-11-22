// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import com.thorbox.app.input.navModal
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SET → PROJECTS. Top: the project you're in (it autosaves; SAVE forces it, SAVE AS NEW branches it
 * off, NEW starts blank). Below: every saved project, newest first, each with OPEN, RENAME, COPY and
 * DELETE (which asks once). Names are typed with the touch keyboard; everything else is buttons.
 */
@Composable
fun ProjectsPage(state: AppState) {
    val pm = state.projects
    LaunchedEffect(Unit) { pm.refreshList() }
    var naming by remember { mutableStateOf<String?>(null) }          // "saveAs" or a project id being renamed
    var nameText by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ---- current project ----
        Row(Modifier.fillMaxWidth().height(60.dp).background(Tb.Panel, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(pm.currentName.value, color = Tb.Text, fontWeight = FontWeight.Black, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${pm.status.value} · autosaves as you work", color = Tb.Muted, fontSize = 11.sp, maxLines = 1)
            }
            HwButton("SAVE", Modifier.width(88.dp), minHeight = 40.dp, fontSize = 12, active = true, accent = Tb.Good) { pm.save() }
            HwButton("SAVE AS NEW", minHeight = 40.dp, fontSize = 12) { naming = "saveAs"; nameText = pm.currentName.value + " v2" }
            HwButton("NEW", Modifier.width(80.dp), minHeight = 40.dp, fontSize = 12, accent = Tb.Key) { pm.newProject() }
        }

        // ---- name entry (save as / rename) ----
        naming?.let { target ->
            Row(Modifier.navModal { naming = null }.fillMaxWidth().height(52.dp).background(Tb.Raised, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (target == "saveAs") "NAME" else "RENAME", style = Tb.Label, color = Tb.Key)
                val commit = {
                    if (target == "saveAs") pm.saveAs(nameText) else pm.rename(target, nameText)
                    naming = null
                }
                BasicTextField(nameText, { nameText = it.take(40) }, singleLine = true,
                    textStyle = TextStyle(color = Tb.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold), cursorBrush = SolidColor(Tb.Key),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commit() }),
                    modifier = Modifier.weight(1f).navTextField("NAME (A: keyboard)").border(1.dp, Tb.Line, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 8.dp))
                // no keyboard needed: roll a name with the buttons
                HwButton("DICE", Modifier.width(64.dp), minHeight = 38.dp, fontSize = 12) {
                    nameText = com.thorbox.model.PatchDice.name(kotlin.random.Random(System.nanoTime())).split(' ').joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
                }
                HwButton("OK", Modifier.width(64.dp), minHeight = 38.dp, fontSize = 12, active = true, accent = Tb.Good) { commit() }
                HwButton("✕", Modifier.width(48.dp), minHeight = 38.dp, fontSize = 13) { naming = null }
            }
        }

        // ---- saved projects ----
        val items = pm.list.value
        if (items.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("No saved projects yet. Make a beat: it saves itself, and shows up here.", color = Tb.Muted, fontSize = 13.sp)
            }
        } else LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(items, key = { it.id }) { m ->
                val current = m.id == pm.currentId.value
                Row(Modifier.then(if (confirmDelete == m.id) Modifier.navModal { confirmDelete = null } else Modifier)
                    .fillMaxWidth().height(52.dp).background(if (current) Tb.Raised else Tb.Panel, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(m.name + if (current) "  ·  OPEN NOW" else "", color = if (current) Tb.Key else Tb.Text, fontWeight = FontWeight.Black,
                            fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(String.format(Locale.US, "%s · %d tracks · %.0f BPM", SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(m.savedAt)), m.tracks, m.bpm),
                            color = Tb.Muted, fontSize = 11.sp, maxLines = 1)
                    }
                    if (confirmDelete == m.id) {
                        Text("Delete for good?", color = Tb.Warn, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        HwButton("DELETE", minHeight = 38.dp, fontSize = 12, active = true, accent = Tb.Warn) { pm.delete(m.id); confirmDelete = null }
                        HwButton("KEEP", minHeight = 38.dp, fontSize = 12) { confirmDelete = null }
                    } else {
                        HwButton("OPEN", Modifier.width(76.dp), minHeight = 38.dp, fontSize = 12, enabled = !current, active = !current, accent = Tb.Good) {
                            pm.open(m.id); state.showSettings.value = false
                        }
                        HwButton("RENAME", minHeight = 38.dp, fontSize = 11) { naming = m.id; nameText = m.name }
                        HwButton("COPY", minHeight = 38.dp, fontSize = 11) { pm.duplicate(m.id) }
                        HwButton("DELETE", minHeight = 38.dp, fontSize = 11, enabled = !current) { confirmDelete = m.id }
                    }
                }
            }
        }
    }
}
