// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.dual

import android.app.ActivityOptions
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.thorbox.app.AppState
import com.thorbox.app.DisplayProbe
import com.thorbox.app.ScreenRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.min

/**
 * Owns "what's on the other screen". Rules:
 *  - Roles come from GEOMETRY, not display IDs: the wide 16:9 panel shows the Session view and the
 *    near-square panel shows the Deck. So it works whichever panel the firmware calls "default".
 *  - Strategy 1: a Presentation on the other display (one Activity, one lifecycle, shared state).
 *  - Strategy 2: if the panel rejects Presentation windows (it isn't flagged FLAG_PRESENTATION,
 *    so the system throws InvalidDisplayException), launch SecondScreenActivity there with
 *    setLaunchDisplayId. State is process-scoped, so both strategies share it the same way.
 *  - The UI is shown in onStart and hidden in onStop. Display add/remove/change (lid, panel off)
 *    triggers a re-evaluation. With no second panel, MainActivity shows a combined layout.
 */
class DualScreenController(
    private val activity: ComponentActivity,
    private val state: AppState,
) : DisplayManager.DisplayListener {

    private val dm = activity.getSystemService(DisplayManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var presentation: RolePresentation? = null
    private var activityHostDisplay: Int? = null
    private var started = false
    private val presentationRejected = mutableSetOf<Int>()

    private val _secondShowing = MutableStateFlow(false)
    val secondShowing = _secondShowing.asStateFlow()

    fun activityRole(): ScreenRole = roleOf(activity.display ?: dm.getDisplay(Display.DEFAULT_DISPLAY))

    fun start() {
        started = true
        dm.registerDisplayListener(this, handler)
        SecondScreenActivity.onGone = { handler.post { onSecondActivityGone() } }
        refresh()
    }

    fun stop() {
        started = false
        dm.unregisterDisplayListener(this)
        hide()
    }

    override fun onDisplayAdded(displayId: Int) = refreshSoon()
    override fun onDisplayRemoved(displayId: Int) = refreshSoon()
    override fun onDisplayChanged(displayId: Int) = refreshSoon()

    private fun refreshSoon() { handler.removeCallbacks(refreshRunnable); handler.postDelayed(refreshRunnable, 150) }
    private val refreshRunnable = Runnable { refresh() }

    fun refresh() {
        publishProbe()
        if (!started) return
        val here = activity.display?.displayId ?: Display.DEFAULT_DISPLAY
        val target = pickTarget(here)
        if (target == null) { hide(); state.secondScreenMode.value = "none (single-screen layout)"; return }

        // Already showing on the right display?
        if (presentation?.isShowing == true && presentation?.display?.displayId == target.displayId) return
        if (activityHostDisplay == target.displayId) return
        hide()

        val role = if (activityRole() == ScreenRole.SESSION) ScreenRole.DECK else ScreenRole.SESSION

        if (target.displayId !in presentationRejected) {
            val p = RolePresentation(activity, target, role, state)
            try {
                p.setOnDismissListener { if (presentation === p) { presentation = null; _secondShowing.value = false; refreshSoon() } }
                p.show()
                presentation = p
                _secondShowing.value = true
                state.secondScreenMode.value = "Presentation → display ${target.displayId} ($role)"
                return
            } catch (e: WindowManager.InvalidDisplayException) {
                Log.w(TAG, "display ${target.displayId} rejected Presentation; falling back to an Activity", e)
                presentationRejected += target.displayId
            }
        }

        try {
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(target.displayId)
            val intent = Intent(activity, SecondScreenActivity::class.java)
                .putExtra(SecondScreenActivity.EXTRA_ROLE, role.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            activity.startActivity(intent, opts.toBundle())
            activityHostDisplay = target.displayId
            _secondShowing.value = true
            state.secondScreenMode.value = "Activity → display ${target.displayId} ($role)"
        } catch (e: SecurityException) {
            Log.e(TAG, "cannot launch on display ${target.displayId}", e)
            state.secondScreenMode.value = "blocked by system (single-screen layout)"
            _secondShowing.value = false
        }
    }

    private fun hide() {
        presentation?.let { p -> presentation = null; p.setOnDismissListener(null); p.dismiss() }
        if (activityHostDisplay != null) {
            activityHostDisplay = null
            SecondScreenActivity.current?.get()?.finish()
        }
        _secondShowing.value = false
    }

    private fun onSecondActivityGone() {
        if (activityHostDisplay != null) { activityHostDisplay = null; _secondShowing.value = false; refreshSoon() }
    }

    /** The other built-in panel: on, public, and preferably the opposite shape from ours. */
    private fun pickTarget(here: Int): Display? {
        val myRole = activityRole()
        val candidates = dm.displays.filter {
            it.displayId != here && it.state != Display.STATE_OFF && (it.flags and Display.FLAG_PRIVATE) == 0
        }
        return candidates.firstOrNull { roleOf(it) != myRole } ?: candidates.firstOrNull()
    }

    private fun roleOf(d: Display): ScreenRole {
        val m = d.mode
        val ratio = max(m.physicalWidth, m.physicalHeight).toFloat() / min(m.physicalWidth, m.physicalHeight).coerceAtLeast(1)
        return if (ratio >= 1.4f) ScreenRole.SESSION else ScreenRole.DECK
    }

    private fun publishProbe() {
        val list = dm.displays.map { d ->
            DisplayProbe(
                id = d.displayId, name = d.name, widthPx = d.mode.physicalWidth, heightPx = d.mode.physicalHeight,
                refreshHz = d.refreshRate, isDefault = d.displayId == Display.DEFAULT_DISPLAY,
                presentationFlag = (d.flags and Display.FLAG_PRESENTATION) != 0,
                state = when (d.state) { Display.STATE_ON -> "on"; Display.STATE_OFF -> "off"; else -> "state ${d.state}" },
                role = roleOf(d),
            )
        }
        state.displays.value = list
        list.forEach { Log.i(TAG, "display $it") }
    }

    companion object { private const val TAG = "ThorboxDual" }
}
