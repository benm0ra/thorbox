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
        registerNavTargets()
        applyLaunchExtras(intent)
        // Only after setContent: before it the window has no DecorView, and asking for its insets
        // controller crashes (NPE inside PhoneWindow) on Android 13.
        hideSystemBars()
    }

    /**
     * NAV cursor targets. This window always serves its own role; with no second screen showing it
     * serves both (top + bottom are squeezed in here). NAV keys skip our router (super = framework).
     */
    private fun composeRoot(): android.view.View? = findViewById<android.view.ViewGroup>(android.R.id.content)?.getChildAt(0)
    @android.annotation.SuppressLint("RestrictedApi")   // same false positive as dispatchKeyEvent below
    private fun registerNavTargets() {
        val sink: (KeyEvent) -> Boolean = { ev -> super.dispatchKeyEvent(ev) }
        val focus: () -> Unit = { composeRoot()?.let { it.isFocusableInTouchMode = true; it.requestFocus() } }
        val mine = screens.activityRole().ordinal
        state.input.registerNav(mine, sink, focus)
        lifecycleScope.launch {
            screens.secondShowing.collect { second -> if (!second) state.input.registerNav(1 - mine, sink, focus) }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); applyLaunchExtras(intent) }

    /** `--es view DRUM --es deck FX`: open a given view/deck (used by the emulator smoke test; harmless otherwise). */
    private fun applyLaunchExtras(i: android.content.Intent?) {
        i?.getStringExtra("view")?.let { v -> TopView.entries.firstOrNull { it.name == v }?.let(state::selectTop) }
        i?.getStringExtra("deck")?.let { d -> com.thorbox.model.Deck.entries.firstOrNull { it.name == d }?.let(state::selectDeck) }
        i?.getStringExtra("add")?.let { k -> com.thorbox.model.TrackKind.entries.firstOrNull { it.name == k }?.let(state::addTrack) }
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
