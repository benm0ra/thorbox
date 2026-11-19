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

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { state.setLibraryRoot(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        screens = DualScreenController(this, state)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                state.folderPickRequests.collect { pickFolder.launch(null) }
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
