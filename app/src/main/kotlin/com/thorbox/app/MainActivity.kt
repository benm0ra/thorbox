// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.thorbox.app.dual.DualScreenController
import com.thorbox.app.ui.RoleContent
import com.thorbox.app.ui.SingleScreenContent
import com.thorbox.app.ui.ThorboxTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var screens: DualScreenController
    private val state get() = appState
    private val askMic = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok -> state.onMicPermission(ok) }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { state.setLibraryRoot(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        screens = DualScreenController(this, state)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                state.folderPickRequests.collect { pickFolder.launch(null) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                state.micPermissionRequests.collect { askMic.launch(android.Manifest.permission.RECORD_AUDIO) }
            }
        }
        if (state.library.root.value != null && state.library.items.value.isEmpty()) state.rescanLibrary()

        setContent {
            ThorboxTheme {
                val second by screens.secondShowing.collectAsState()
                if (second) RoleContent(screens.activityRole(), state)
                else SingleScreenContent(screens.activityRole(), state)
            }
        }
        applyLaunchExtras(intent)
        // Only after setContent: before it the window has no DecorView, and asking for its insets
        // controller crashes (NPE inside PhoneWindow) on Android 13.
        hideSystemBars()
    }

    // Touching the other screen moves window focus there, and Android brings the status bar back on
    // this one. Hide it again whenever focus changes, in either direction.
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); hideSystemBars() }
    override fun onResume() { super.onResume(); hideSystemBars() }

    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); applyLaunchExtras(intent) }

    /** `--es view DRUM --es deck FX`: open a given view/deck (used by the emulator smoke test; harmless otherwise). */
    private fun applyLaunchExtras(i: android.content.Intent?) {
        // The launcher activity is exported: in release builds no other app may drive it with extras.
        if (!BuildConfig.DEBUG) return
        i?.getStringExtra("view")?.let { v -> TopView.entries.firstOrNull { it.name == v }?.let(state::selectTop) }
        i?.getStringExtra("deck")?.let { d -> com.thorbox.model.Deck.entries.firstOrNull { it.name == d }?.let(state::selectDeck) }
        i?.getStringExtra("add")?.let { k -> com.thorbox.model.TrackKind.entries.firstOrNull { it.name == k }?.let(state::addTrack) }
        if (i?.hasExtra("fx") == true) i.getIntExtra("fx", -1).let { id -> if (id >= 0) state.groove.fxDown(id, 0.6f, 0.6f) else for (k in 0 until 8) state.groove.fxUp(k) }
        if (i?.hasExtra("nav") == true) state.input.setNav(i.getBooleanExtra("nav", false))
        if (i?.hasExtra("settings") == true) i.getIntExtra("settings", -1).let { t -> state.showSettings.value = t >= 0; if (t >= 0) state.settingsTab.value = t }
        if (i?.getBooleanExtra("scratch", false) == true) state.projects.enterScratch()      // tours never touch your projects
        if (i?.getBooleanExtra("demo", false) == true) state.loadDemo()
        if (i?.getBooleanExtra("dice", false) == true) {                                    // fill every track with a pattern
            for (t in 0 until com.thorbox.model.TRACK_COUNT) if (state.trackKind(t) != com.thorbox.model.TrackKind.EMPTY) state.groove.diceNotes(t)
        }
        if (i?.hasExtra("meters") == true) state.setMiniMeters(i.getBooleanExtra("meters", false))
        if (i?.hasExtra("mic") == true) state.showMic.value = i.getBooleanExtra("mic", false)
        i?.getStringExtra("theme")?.let { state.setTheme(com.thorbox.app.ui.Themes.byId(it)) }
        if (i?.getBooleanExtra("play", false) == true && !state.groove.playing.value) state.groove.togglePlay()
    }

    override fun onStart() {
        super.onStart()
        state.startEngine()
        screens.start()
    }

    override fun onStop() {
        screens.stop()
        state.stopEngine()   // M4 (transport) moves the engine into a foreground Service so audio survives this
        super.onStop()
    }

    // androidx.core marks ComponentActivity.dispatchKeyEvent @RestrictTo, so lint flags the plain
    // super call. It's a known false positive: we only forward to the framework implementation.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        state.input.onKey(event) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean =
        state.input.onGenericMotion(ev) || super.dispatchGenericMotionEvent(ev)
}

/** Full-screen: hide status + navigation bars (swipe to peek). Call after setContent/setContentView. */
fun android.app.Activity.hideSystemBars() {
    window.decorView   // forces the DecorView to exist, so the controller below is never null-backed
    window.insetsController?.let {
        it.hide(WindowInsets.Type.systemBars())
        it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
