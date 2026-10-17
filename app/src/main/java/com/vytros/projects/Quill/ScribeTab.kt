package com.vytros.projects.Quill

import android.content.ClipData
import android.content.ClipboardManager
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.doAfterTextChanged

/** The writing desk: type or paste, pick a tool, get the result. */
class ScribeTab(private val act: MainActivity, root: View, private val replaceMode: Boolean) {
    private val input = root.findViewById<android.widget.EditText>(R.id.input)
    private val output = root.findViewById<TextView>(R.id.output)
    private val resultCard = root.findViewById<View>(R.id.resultCard)
    private val resultLabel = root.findViewById<TextView>(R.id.resultLabel)
    private val progress = root.findViewById<View>(R.id.progress)
    private val use = root.findViewById<TextView>(R.id.use)
    private val actions = root.findViewById<LinearLayout>(R.id.actions)
    private val setupCard = root.findViewById<View>(R.id.setupCard)
    private val setup = SetupSteps(act, root.findViewById(R.id.steps))
    private var speed: Double? = null
    private var busy = false
    private var canUse = false

    init {
        val count = root.findViewById<TextView>(R.id.count)
        input.doAfterTextChanged {
            val n = it?.trim()?.split(Regex("\\s+"))?.count { w -> w.isNotEmpty() } ?: 0
            count.text = if (n == 1) "1 word" else "$n words"
        }
        root.findViewById<View>(R.id.clear).setOnClickListener { input.setText(""); resultCard.visibility = View.GONE }
        use.text = if (replaceMode) "Replace" else "Use this"
        use.setOnClickListener {
            if (!canUse) return@setOnClickListener
            val out = output.text.toString()
            if (replaceMode) act.finishWithText(out) else { input.setText(out); input.setSelection(out.length) }
        }
        root.findViewById<View>(R.id.copy).setOnClickListener {
            act.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Quill", output.text))
            Toast.makeText(act, "Copied", Toast.LENGTH_SHORT).show()
        }
        refreshTools()
    }

    fun setText(t: CharSequence?) = input.setText(t)

    /** Shows the setup checklist until the keyboard and assistant are all on. */
    fun renderSetup() { setupCard.visibility = if (setup.render()) View.GONE else View.VISIBLE }

    fun setCanReplace(can: Boolean) { use.visibility = if (can) View.VISIBLE else View.GONE }

    /** Rebuilds the chips; called again whenever the prompts in Settings change. */
    fun refreshTools() {
        actions.removeAllViews()
        Prompts.tools(act).forEach { tool ->
            actions.addView(TextView(act).apply {
                text = tool.name
                textSize = 14f
                typeface = ResourcesCompat.getFont(act, R.font.dmsans_medium)
                setTextColor(act.getColor(R.color.text_primary))
                setBackgroundResource(R.drawable.bg_chip)
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(act.dp(16), 0, act.dp(16), 0)
                if (tool.name == Prompts.FIX) {
                    setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_sparkle, 0, 0, 0)
                    compoundDrawablePadding = act.dp(8)
                    compoundDrawableTintList = android.content.res.ColorStateList.valueOf(act.getColor(R.color.brand))
                }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, act.dp(42)).apply { marginEnd = act.dp(8) }
                setOnClickListener { run(tool.name) }
            })
        }
    }

    private fun run(action: String) {
        val text = input.text.toString()
        if (text.isBlank() || busy) return
        busy = true
        progress.visibility = View.VISIBLE
        Gemma.run(act, action, text, onSpeed = { speed = it }) { r ->
            busy = false
            progress.visibility = View.INVISIBLE
            resultCard.visibility = View.VISIBLE
            resultLabel.text = if (!r.isSuccess) "Couldn't finish" else speed?.let { "%s  ·  %.1f tok/s".format(action, it) } ?: action
            output.text = r.getOrElse { "Can't reach Ollama. Check the server in Settings." }
            output.setTextColor(act.getColor(if (r.isSuccess) R.color.on_brand_container else R.color.kb_error))
            canUse = r.isSuccess && action != Prompts.TONE
            use.alpha = if (canUse) 1f else 0.4f
        }
    }
}
