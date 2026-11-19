// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app

import android.app.Application

class ThorboxApp : Application() {
    /** Process-wide state shared by both screens. It's process-scoped (not an Activity
     *  ViewModel) because the second screen may be a separate Activity on some firmware. */
    lateinit var state: AppState
        private set

    override fun onCreate() {
        super.onCreate()
        state = AppState(this)
    }
}

val android.content.Context.appState: AppState get() = (applicationContext as ThorboxApp).state
