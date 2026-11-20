// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.dual

import android.app.Presentation
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.thorbox.app.AppState
import com.thorbox.app.R
import com.thorbox.app.ScreenRole
import com.thorbox.app.ui.RoleContent
import com.thorbox.app.ui.ThorboxTheme

/**
 * The other screen, hosted as a Presentation (a Dialog bound to another Display).
 *
 * The Compose gotcha: Presentation extends plain Dialog, not ComponentDialog, so its window
 * has no lifecycle, ViewModelStore or SavedStateRegistry owners and Compose would crash.
 * We borrow the host Activity's owners. That's correct here because the Presentation
 * never outlives the Activity (it's dismissed in the Activity's onStop).
 */
class RolePresentation(
    private val activity: ComponentActivity,
    display: Display,
    private val role: ScreenRole,
    private val state: AppState,
) : Presentation(activity, display, R.style.Theme_Thorbox_Presentation) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { ThorboxTheme { RoleContent(role, state) } }
        }
        setContentView(view)
        // NAV cursor: the root must be focusable so the cursor can land here even when the other screen has input focus.
        view.isFocusable = true; view.isFocusableInTouchMode = true
        window?.let { w ->
            w.decorView.setViewTreeLifecycleOwner(activity)
            w.decorView.setViewTreeViewModelStoreOwner(activity)
            w.decorView.setViewTreeSavedStateRegistryOwner(activity)
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            w.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    // Key focus follows the last-touched display, so gamepad input can land here.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        state.input.onKey(event) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean =
        state.input.onGenericMotion(ev) || super.dispatchGenericMotionEvent(ev)
}
