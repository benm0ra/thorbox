// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import kotlin.system.exitProcess

/**
 * Local-only crash capture. An uncaught exception is written to the app's private storage and
 * shown on a plain (no Compose, no engine) screen in a separate process, so even a crash during
 * start-up can be read, copied and shared. Nothing is sent anywhere automatically.
 */
object CrashReporter {
    private const val FILE = "last-crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext ?: context
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val text = buildString {
                    append("Thorbox ").append(versionName(app)).append('\n')
                    append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                        .append(" · Android ").append(Build.VERSION.RELEASE)
                        .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
                    append(Build.SOC_MANUFACTURER).append(' ').append(Build.SOC_MODEL).append('\n')
                    append("Thread: ").append(thread.name).append("\n\n")
                    append(error.stackTraceToString())
                }
                File(app.filesDir, FILE).writeText(text)
                app.startActivity(Intent(app, CrashActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }.onFailure { previous?.uncaughtException(thread, error) }
            // Log it (so logcat / adb still see the trace) and exit. The system's own handler is
            // only a fallback: its "app keeps stopping" teardown can race the crash screen.
            runCatching { android.util.Log.e("Thorbox", "FATAL on ${thread.name}", error) }
                .onFailure { previous?.uncaughtException(thread, error) }
            Process.killProcess(Process.myPid()); exitProcess(10)
        }
    }

    fun read(context: Context): String? = File(context.filesDir, FILE).takeIf { it.exists() }?.readText()
    fun clear(context: Context) { File(context.filesDir, FILE).delete() }

    private fun versionName(c: Context): String =
        runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "?"
}

/** Runs in the ":crash" process (see the manifest), which never creates AppState or the engine. */
class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = CrashReporter.read(this) ?: "No crash report found."
        val pad = (16 * resources.displayMetrics.density).toInt()

        val title = TextView(this).apply {
            text = "Thorbox crashed"
            textSize = 22f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
        }
        val hint = TextView(this).apply {
            text = "Tap COPY and paste this into the chat with Claude (or SHARE it). Nothing has been sent anywhere."
            textSize = 14f; setTextColor(0xFFB0B0B0.toInt()); setPadding(0, pad / 2, 0, pad / 2)
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(context).apply {
                text = "Copy"
                setOnClickListener {
                    getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Thorbox crash", report))
                    text = "Copied"
                }
            })
            addView(Button(context).apply {
                text = "Share"
                setOnClickListener {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND)
                        .setType("text/plain").putExtra(Intent.EXTRA_TEXT, report), "Share crash report"))
                }
            })
            addView(Button(context).apply {
                text = "Restart"
                setOnClickListener {
                    CrashReporter.clear(context)
                    startActivity(Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    finish(); Process.killProcess(Process.myPid())
                }
            })
        }
        val body = TextView(this).apply {
            text = report
            textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(0xFFE0E0E0.toInt())
            setTextIsSelectable(true); setPadding(0, pad / 2, 0, pad)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(title); addView(hint); addView(buttons); addView(body)
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(column, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
    }
}
