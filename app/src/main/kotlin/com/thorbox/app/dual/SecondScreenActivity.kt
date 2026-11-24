// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.dual

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.thorbox.app.ScreenRole
import com.thorbox.app.appState
import com.thorbox.app.ui.RoleContent
import com.thorbox.app.hideSystemBars
import com.thorbox.app.ui.ThorboxTheme
import java.lang.ref.WeakReference

/** Fallback host for the second panel when it refuses Presentation windows. */
class SecondScreenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        val role = ScreenRole.valueOf(intent.getStringExtra(EXTRA_ROLE) ?: ScreenRole.DECK.name)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { ThorboxTheme { RoleContent(role, appState) } }
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); hideSystemBars() }

    // androidx.core marks ComponentActivity.dispatchKeyEvent @RestrictTo, so lint flags the plain
    // super call. It's a known false positive: we only forward to the framework implementation.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        appState.input.onKey(event) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean =
        appState.input.onGenericMotion(ev) || super.dispatchGenericMotionEvent(ev)

    override fun onDestroy() {
        super.onDestroy()
        if (current?.get() === this) current = null
        if (!isChangingConfigurations) onGone?.invoke()
    }

    companion object {
        const val EXTRA_ROLE = "role"
        var current: WeakReference<SecondScreenActivity>? = null
        var onGone: (() -> Unit)? = null
    }
}
