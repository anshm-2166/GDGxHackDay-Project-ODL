package com.vytros.projects.Quill

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.InsetDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.doAfterTextChanged

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

/** A rounded card floating on a dimmed screen, built from [layout]. Our own dialog, no system chrome. */
fun Context.sheet(layout: Int): Pair<Dialog, View> {
    val view = LayoutInflater.from(this).inflate(layout, null)
    val d = Dialog(this)
    d.requestWindowFeature(Window.FEATURE_NO_TITLE)
    d.setContentView(view)
    d.window?.apply {
        setBackgroundDrawable(InsetDrawable(resources.getDrawable(R.drawable.bg_card, theme), dp(16)))
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setDimAmount(0.45f)
        setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    return d to view
}

/** Lists [items] in a sheet; calls [onPick] with the chosen one. */
fun Context.pickSheet(items: List<String>, current: String?, onPick: (String) -> Unit) {
    val (d, v) = sheet(R.layout.dialog_pick)
    val list = v.findViewById<ViewGroup>(R.id.pickList)
    items.forEach { name ->
        list.addView(TextView(this).apply {
            text = name
            textSize = 15f
            typeface = ResourcesCompat.getFont(context, if (name == current) R.font.dmsans_semibold else R.font.dmsans)
            setTextColor(getColor(if (name == current) R.color.brand else R.color.text_primary))
            setBackgroundResource(R.drawable.bg_row)
            setPadding(dp(8), dp(13), dp(8), dp(13))
            setOnClickListener { onPick(name); d.dismiss() }
        })
    }
    d.show()
}

/** What the prompt editor is editing: a name (editable only for custom tools) and the system prompt text. */
class PromptEdit(
    val title: String,
    val hint: String,
    val name: String?,        // null = no name field (chat and style prompts)
    val nameLocked: Boolean,
    val text: String,
    val canReset: Boolean,    // built-ins and the two global prompts reset to default
    val canDelete: Boolean,   // custom tools delete instead
    val nameHint: String = "Name",
    val textHint: String = "System prompt",
)

fun Context.editPrompt(e: PromptEdit, onSave: (name: String, text: String) -> String?, onReset: () -> Unit) {
    val (d, v) = sheet(R.layout.dialog_prompt)
    v.findViewById<TextView>(R.id.dTitle).text = e.title
    v.findViewById<TextView>(R.id.dHint).text = e.hint
    val name = v.findViewById<EditText>(R.id.dName)
    val text = v.findViewById<EditText>(R.id.dText)
    val reset = v.findViewById<TextView>(R.id.dReset)
    val save = v.findViewById<TextView>(R.id.dSave)
    name.hint = e.nameHint
    text.hint = e.textHint
    if (e.name == null) name.visibility = View.GONE else name.setText(e.name)
    if (e.nameLocked) { name.isEnabled = false; name.alpha = 0.6f }
    text.setText(e.text)
    reset.visibility = if (e.canReset || e.canDelete) View.VISIBLE else View.GONE
    reset.text = if (e.canDelete) "Delete" else "Reset"
    if (e.canDelete) reset.setTextColor(getColor(R.color.kb_error))
    reset.setOnClickListener { onReset(); d.dismiss() }
    v.findViewById<View>(R.id.dCancel).setOnClickListener { d.dismiss() }
    text.doAfterTextChanged { save.alpha = if (it.isNullOrBlank()) 0.4f else 1f }
    save.setOnClickListener {
        if (text.text.isBlank()) return@setOnClickListener
        val problem = onSave(name.text.toString().trim(), text.text.toString().trim())
        if (problem == null) d.dismiss() else name.error = problem
    }
    d.show()
}
