package com.vytros.projects.Quill

import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView

/** The keyboard and accessibility-assistant checklist, drawn into [steps]. [allDone] reports whether nothing is left to do. */
class SetupSteps(private val act: MainActivity, private val steps: ViewGroup) {
    fun render(): Boolean {
        val imm = act.getSystemService(InputMethodManager::class.java)
        val pkg = act.packageName
        val kbEnabled = imm.enabledInputMethodList.any { it.packageName == pkg }
        val kbActive = Settings.Secure.getString(act.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith(pkg) == true
        val assistant = Settings.Secure.getString(act.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.contains("$pkg/") == true
        steps.removeAllViews()
        step("Enable Quill Keyboard", "Turn it on in your keyboard list", kbEnabled, "Enable") {
            act.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        step("Switch to Quill Keyboard", "Suggestions appear above the keys", kbActive, "Switch") {
            imm.showInputMethodPicker()
        }
        step("Assistant for any keyboard", "Underlines and a floating button over Gboard and others", assistant, "Turn on") {
            act.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        return kbEnabled && kbActive && assistant
    }

    private fun step(title: String, subtitle: String, done: Boolean, action: String, onClick: () -> Unit) {
        val row = act.layoutInflater.inflate(R.layout.item_step, steps, false)
        row.findViewById<ImageView>(R.id.stepIcon).setImageResource(if (done) R.drawable.ic_check_circle else R.drawable.ic_circle_outline)
        row.findViewById<TextView>(R.id.stepTitle).text = title
        row.findViewById<TextView>(R.id.stepSubtitle).text = subtitle
        row.findViewById<TextView>(R.id.stepButton).apply {
            text = if (done) "Done" else action
            alpha = if (!done || action == "Switch") 1f else 0.45f
            isEnabled = !done || action == "Switch"
            setOnClickListener { onClick() }
        }
        steps.addView(row)
    }
}
