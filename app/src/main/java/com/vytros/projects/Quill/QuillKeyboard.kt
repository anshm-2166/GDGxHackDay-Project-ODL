package com.vytros.projects.Quill

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Quill's own keyboard: a full keyboard with Grammarly-style issue cards above the keys. */
class QuillKeyboard : InputMethodService() {
    private enum class Mode { LETTERS, SYMBOLS, EMOJI }

    private lateinit var bar: LinearLayout
    private lateinit var badge: TextView
    private lateinit var keys: LinearLayout
    private var mode = Mode.LETTERS
    private var shift = false
    private var capsLock = false
    private var lastShiftTap = 0L
    private var secret = false
    private var menuOpen = false
    private var night = false
    private var message: Pair<Int, String>? = null // icon (0 = spinner) + text, shown instead of cards
    private var undo: (() -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoCheck = Runnable { if (!secret) Checker.check(this, currentText()) }
    private val onIssues: () -> Unit = { renderBar() }
    private val repeatDelete = object : Runnable {
        override fun run() { delete(); handler.postDelayed(this, 50) }
    }

    override fun onCreate() {
        super.onCreate()
        Checker.listen(onIssues)
    }

    override fun onDestroy() {
        Checker.unlisten(onIssues)
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        night = isNight()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.kb_bg))
        }
        // Edge-to-edge IME windows draw under the nav bar; keep the keys above it.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            v.setPadding(0, 0, 0, insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
            insets
        }
        val barRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), 0, dp(2))
        }
        barRow.addView(ball())
        bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        barRow.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(bar)
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        keys = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(2), dp(3), dp(4))
        }
        root.addView(barRow)
        root.addView(keys)
        buildKeys()
        renderBar()
        return root
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        if (isNight() != night) setInputView(onCreateInputView())
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        // Never ship passwords to a server.
        secret = variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        ) || (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        mode = if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE) Mode.SYMBOLS else Mode.LETTERS
        capsLock = false
        menuOpen = false
        message = null
        undo = null
        shift = autoShift()
        buildKeys()
        renderBar()
        scheduleCheck()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        handler.removeCallbacks(autoCheck)
        handler.removeCallbacks(repeatDelete)
    }

    // ---- Suggestion bar -------------------------------------------------------------------

    private fun ball(): View {
        val f = FrameLayout(this)
        f.addView(ImageView(this).apply { setImageResource(R.drawable.ic_logo) },
            FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
        badge = TextView(this).apply {
            setTextColor(color(R.color.white))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color(R.color.kb_error)) }
        }
        f.addView(badge, FrameLayout.LayoutParams(dp(17), dp(17), Gravity.TOP or Gravity.END))
        f.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(6) }
        f.contentDescription = "Quill writing tools"
        f.background = RippleDrawable(ColorStateList.valueOf(color(R.color.kb_ripple)), null, null)
        f.setOnClickListener {
            menuOpen = !menuOpen
            message = null
            renderBar()
        }
        return f
    }

    private fun renderBar() {
        if (!::bar.isInitialized) return
        bar.removeAllViews()
        val text = currentText()
        val current = Checker.text == text
        val issues = if (text.isBlank()) emptyList() else Checker.issues.filter { text.contains(it.original) }
        badge.text = issues.size.toString()
        badge.visibility = if (issues.isEmpty() || secret) View.GONE else View.VISIBLE
        val msg = message
        when {
            secret -> status(R.drawable.ic_lock, "Writing assistant is off in password fields")
            menuOpen -> Prompts.tools(this).map { it.name }.forEach { a -> bar.addView(chip(a, if (a == "Fix all") R.drawable.ic_sparkle else 0) { runAction(a) }) }
            msg != null -> {
                status(msg.first, msg.second)
                undo?.let { u -> bar.addView(chip("Undo", R.drawable.ic_undo) { u() }) }
            }
            issues.isNotEmpty() -> issues.forEach { bar.addView(card(it)) }
            current && Checker.busy -> status(0, "Checking")
            current && Checker.error != null -> status(R.drawable.ic_warning, "Ollama error: ${Checker.error}")
            current && text.isNotBlank() -> status(R.drawable.ic_check_circle, "No issues found")
            else -> status(R.drawable.ic_sparkle, "Quill checks your writing when you pause")
        }
    }

    private fun status(icon: Int, msg: String) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), 0, dp(12), 0) }
        row.addView(
            if (icon == 0) ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply { indeterminateTintList = ColorStateList.valueOf(color(R.color.kb_accent)) }
            else ImageView(this).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(color(R.color.kb_accent)) },
            LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) },
        )
        row.addView(TextView(this).apply {
            text = msg
            setTextColor(color(R.color.kb_sub))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = resources.displayMetrics.widthPixels - dp(110)
        })
        bar.addView(row)
    }

    private fun card(i: Checker.Issue) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(4), dp(14), dp(4))
        background = keyBg(color(R.color.kb_key), 10)
        layoutParams = LinearLayout.LayoutParams(-2, dp(46)).apply { marginEnd = dp(8) }
        addView(TextView(context).apply {
            text = i.type.uppercase()
            letterSpacing = 0.06f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(color(R.color.kb_sub))
            dot(this, i)
        })
        addView(TextView(context).apply {
            text = i.replacement.ifBlank { "Remove \"${i.original}\"" }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(color(R.color.kb_accent))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(220)
        })
        contentDescription = "${i.type}: replace ${i.original} with ${i.replacement}"
        setOnClickListener { haptic(it); applyIssue(i) }
    }

    private fun chip(label: String, icon: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(color(R.color.kb_text))
        setPadding(dp(14), 0, dp(14), 0)
        background = keyBg(color(R.color.kb_key), 18)
        if (icon != 0) {
            val d = getDrawable(icon)!!.mutate().apply { setTint(color(R.color.kb_accent)); setBounds(0, 0, dp(16), dp(16)) }
            setCompoundDrawablesRelative(d, null, null, null)
            compoundDrawablePadding = dp(6)
        }
        layoutParams = LinearLayout.LayoutParams(-2, dp(38)).apply { marginEnd = dp(8) }
        setOnClickListener { haptic(it); onClick() }
    }

    private fun say(icon: Int, msg: String, undo: (() -> Unit)? = null) {
        menuOpen = false
        message = icon to msg
        this.undo = undo
        renderBar()
    }

    // ---- AI actions -----------------------------------------------------------------------

    private fun applyIssue(issue: Checker.Issue) {
        val ic = currentInputConnection ?: return
        val text = currentText()
        val i = Checker.locate(text, listOf(issue)).firstOrNull() ?: return renderBar()
        val cursor = cursor()
        val c = if (cursor >= i.end) cursor + i.replacement.length - i.original.length else cursor
        ic.beginBatchEdit()
        ic.setComposingRegion(i.start, i.end)
        ic.commitText(i.replacement, 1)
        ic.setSelection(c, c)
        ic.endBatchEdit()
        Checker.applied(Checker.applyTo(text, i), Checker.issues - issue)
    }

    private fun runAction(action: String) {
        val text = currentText()
        if (text.isBlank()) return say(R.drawable.ic_warning, "Type something first")
        say(0, "$action in progress")
        Gemma.run(this, action, text) { r ->
            val out = r.getOrElse { return@run say(R.drawable.ic_warning, "Can't reach Ollama. Check the server in Quill.") }
            when {
                action == Prompts.TONE -> say(R.drawable.ic_mood, out)
                currentText() != text -> say(R.drawable.ic_warning, "Text changed, try again")
                else -> {
                    replaceAll(out)
                    Checker.applied(out, emptyList())
                    say(R.drawable.ic_check_circle, "$action applied") {
                        if (currentText() == out) { replaceAll(text); say(R.drawable.ic_undo, "Change undone") }
                    }
                }
            }
        }
    }

    // ---- Keys -----------------------------------------------------------------------------

    private fun buildKeys() {
        if (!::keys.isInitialized) return
        keys.removeAllViews()
        if (mode == Mode.EMOJI) return buildEmoji()
        val letters = mode == Mode.LETTERS
        val rows = if (letters) listOf("qwertyuiop", "asdfghjkl", "zxcvbnm") else listOf("1234567890", "@#₹_&-+()/", "*\"':;!?")
        keys.addView(row(rows[0].map { charKey(it) }))
        keys.addView(row(rows[1].map { charKey(it) }, inset = if (letters) 0.5f else 0f))
        val left = if (letters) iconKey(if (shift || capsLock) R.drawable.ic_shift_on else R.drawable.ic_shift, "Shift", 1.5f,
            tint = if (capsLock) color(R.color.kb_accent) else color(R.color.kb_text)) { onShift() }
        else textKey("%", 1.5f, special = true) { commit("%") }
        keys.addView(row(listOf(left) + rows[2].map { charKey(it) } + backspaceKey(1.5f)))
        keys.addView(row(listOf(
            textKey(if (letters) "?123" else "ABC", 1.5f, special = true, size = 14f) {
                mode = if (letters) Mode.SYMBOLS else Mode.LETTERS
                buildKeys()
            },
            iconKey(R.drawable.ic_mood, "Emoji", 1f) { mode = Mode.EMOJI; buildKeys() },
            textKey(",") { commit(",") },
            textKey("Quill", 4.5f, size = 13f, textColor = color(R.color.kb_sub)) { commit(" ") }.apply { contentDescription = "Space" },
            textKey(".") { commit(".") },
            iconKey(R.drawable.ic_return, "Enter", 1.5f, fill = color(R.color.kb_accent), tint = color(R.color.kb_on_accent)) { enter() },
        )))
    }

    private fun buildEmoji() {
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        EMOJI.chunked(8).forEach { chunk ->
            val line = LinearLayout(this)
            chunk.forEach { e ->
                line.addView(TextView(this).apply {
                    text = e
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                    background = keyBg(0, 8)
                    setOnClickListener { haptic(it); commit(e) }
                }, LinearLayout.LayoutParams(0, dp(46), 1f))
            }
            repeat(8 - chunk.size) { line.addView(View(this), LinearLayout.LayoutParams(0, dp(46), 1f)) }
            grid.addView(line)
        }
        keys.addView(ScrollView(this).apply { addView(grid) }, LinearLayout.LayoutParams(-1, dp(3 * 58)))
        keys.addView(row(listOf(
            textKey("ABC", 1.5f, special = true, size = 14f) { mode = Mode.LETTERS; buildKeys() },
            textKey("Quill", 5f, size = 13f, textColor = color(R.color.kb_sub)) { commit(" ") },
            backspaceKey(1.5f),
        )))
    }

    private fun row(views: List<View>, inset: Float = 0f) = LinearLayout(this).apply {
        if (inset > 0) addView(View(context), LinearLayout.LayoutParams(0, 0, inset))
        views.forEach { addView(it) }
        if (inset > 0) addView(View(context), LinearLayout.LayoutParams(0, 0, inset))
    }

    private fun charKey(c: Char): View {
        val label = if ((shift || capsLock) && c.isLetter()) c.uppercase() else c.toString()
        return textKey(label) { commit(label) }
    }

    private fun textKey(label: String, weight: Float = 1f, special: Boolean = false, size: Float = 21f,
                        textColor: Int = color(R.color.kb_text), onClick: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(textColor)
        background = keyBg(color(if (special) R.color.kb_key_special else R.color.kb_key), 8)
        layoutParams = keyLp(weight)
        elevation = dp(1).toFloat()
        setOnClickListener { haptic(it); onClick() }
    }

    private fun iconKey(icon: Int, desc: String, weight: Float, fill: Int = color(R.color.kb_key_special),
                        tint: Int = color(R.color.kb_text), onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(tint)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = desc
        background = keyBg(fill, 8)
        layoutParams = keyLp(weight)
        elevation = dp(1).toFloat()
        setOnClickListener { haptic(it); onClick() }
    }

    @SuppressLint("ClickableViewAccessibility") // click listener kept for TalkBack
    private fun backspaceKey(weight: Float) = iconKey(R.drawable.ic_backspace, "Delete", weight) { delete() }.apply {
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    haptic(v)
                    delete()
                    handler.postDelayed(repeatDelete, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(repeatDelete)
                }
            }
            true
        }
    }

    private fun keyLp(weight: Float) = LinearLayout.LayoutParams(0, dp(50), weight).apply { setMargins(dp(3), dp(4), dp(3), dp(4)) }

    private fun keyBg(fill: Int, radius: Int) = RippleDrawable(
        ColorStateList.valueOf(color(R.color.kb_ripple)),
        GradientDrawable().apply { cornerRadius = dp(radius).toFloat(); setColor(fill) },
        GradientDrawable().apply { cornerRadius = dp(radius).toFloat(); setColor(color(R.color.black)) },
    )

    // ---- Editing --------------------------------------------------------------------------

    private fun commit(s: String) {
        currentInputConnection?.commitText(s, 1) ?: return
        afterEdit()
    }

    private fun delete() {
        val ic = currentInputConnection ?: return
        if (ic.getSelectedText(0).isNullOrEmpty()) ic.deleteSurroundingTextInCodePoints(1, 0) else ic.commitText("", 1)
        afterEdit()
    }

    private fun enter() {
        if (!sendDefaultEditorAction(true)) currentInputConnection?.commitText("\n", 1)
        afterEdit()
    }

    private fun onShift() {
        val now = SystemClock.uptimeMillis()
        when {
            capsLock -> { capsLock = false; shift = false }
            shift && now - lastShiftTap < 350 -> capsLock = true
            else -> shift = !shift
        }
        lastShiftTap = now
        buildKeys()
    }

    private fun afterEdit() {
        if (message != null || menuOpen) { message = null; undo = null; menuOpen = false; renderBar() }
        val s = !capsLock && mode == Mode.LETTERS && autoShift()
        if (s != shift && !capsLock) { shift = s; buildKeys() }
        scheduleCheck()
    }

    private fun autoShift(): Boolean {
        val info = currentInputEditorInfo ?: return false
        return mode == Mode.LETTERS && (currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0) != 0
    }

    private fun scheduleCheck() {
        handler.removeCallbacks(autoCheck)
        if (!secret) handler.postDelayed(autoCheck, 1200)
    }

    private fun currentText(): String {
        val ic = currentInputConnection ?: return ""
        ic.getExtractedText(ExtractedTextRequest(), 0)?.text?.let { return it.toString() }
        // Some apps don't support extracted text; fall back to the text around the cursor.
        return "${ic.getTextBeforeCursor(5000, 0) ?: ""}${ic.getTextAfterCursor(5000, 0) ?: ""}"
    }

    private fun cursor(): Int {
        val ic = currentInputConnection ?: return 0
        return ic.getExtractedText(ExtractedTextRequest(), 0)?.selectionEnd ?: (ic.getTextBeforeCursor(5000, 0)?.length ?: 0)
    }

    private fun replaceAll(t: String) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        ic.setSelection(0, currentText().length)
        ic.commitText(t, 1)
        ic.endBatchEdit()
    }

    private fun haptic(v: View) = v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    private fun isNight() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private fun color(id: Int) = getColor(id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        val EMOJI = ("😀 😃 😄 😁 😆 😅 😂 🤣 😊 😇 🙂 🙃 😉 😌 😍 🥰 😘 😋 😛 😜 🤪 😝 🤗 🤭 " +
            "🤫 🤔 🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🥵 🥶 🥴 😵 🤯 🤠 🥳 😎 🤓 " +
            "🧐 😕 😟 🙁 😮 😯 😲 😳 🥺 😦 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 " +
            "💀 🤡 👻 👽 🤖 🙈 🙉 🙊 ❤️ 🧡 💛 💚 💙 💜 🖤 🤍 💔 💕 💯 💥 💫 🔥 ✨ 🎉 🎊 👍 👎 👌 " +
            "✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ ✋ 👋 👏 🙌 🤲 🤝 🙏 💪 🫶 👀 🧠 ☕ 🍕 🍔 🍟 🎂 🍫 🍿 " +
            "⚽ 🏏 🎮 🎵 🎧 📱 💻 📚 ✏️ 📌 ✅ ❌ ⚠️ ⏰ 🚀 🌙 ☀️ 🌧️ 🌈 🌸 🌹 🇮🇳").split(" ")
    }
}
