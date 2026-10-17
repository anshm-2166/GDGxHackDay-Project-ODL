package com.vytros.projects.Quill

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.os.BundleCompat
import kotlin.math.abs

/**
 * Works with any keyboard, like Grammarly on Android: underlines mistakes in the focused
 * text field and shows a floating button with a count; tapping it lists the fixes.
 */
class QuillAssistant : AccessibilityService() {
    private lateinit var wm: WindowManager
    private lateinit var underlines: View
    private lateinit var ball: View
    private lateinit var badge: TextView
    private var panel: View? = null
    private var node: AccessibilityNodeInfo? = null
    private var nodeText = ""
    private val lines = mutableListOf<Pair<RectF, Int>>() // rect + category colour
    private val handler = Handler(Looper.getMainLooper())
    private val check = Runnable { Checker.check(this, nodeText) }
    private val onIssues: () -> Unit = {
        refresh()
        if (panel != null) { if (currentIssues().isEmpty() && !Checker.busy && Checker.error == null) hidePanel() else showPanel() }
    }

    override fun onServiceConnected() {
        wm = getSystemService(WindowManager::class.java)
        underlines = object : View(this) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = getColor(R.color.kb_error)
                strokeWidth = dp(2.5f)
                strokeCap = Paint.Cap.ROUND
            }
            val loc = IntArray(2)
            override fun onDraw(c: Canvas) {
                getLocationOnScreen(loc)
                lines.forEach { (r, col) ->
                    paint.color = col
                    c.drawLine(r.left - loc[0], r.bottom - loc[1], r.right - loc[0], r.bottom - loc[1], paint)
                }
            }
        }
        wm.addView(underlines, params(-1, -1, touchable = false))
        ball = ball()
        ball.visibility = View.GONE
        wm.addView(ball, params(-2, -2, touchable = true))
        Checker.listen(onIssues)
    }

    override fun onDestroy() {
        Checker.unlisten(onIssues)
        handler.removeCallbacksAndMessages(null)
        hidePanel()
        if (::wm.isInitialized) { wm.removeView(underlines); wm.removeView(ball) }
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val n = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (n == null || !n.isEditable || n.isPassword) {
            node = null
            hidePanel()
            refresh()
            return
        }
        node = n
        val t = if (n.isShowingHintText) "" else n.text?.toString().orEmpty()
        if (t != nodeText) {
            nodeText = t
            handler.removeCallbacks(check)
            handler.postDelayed(check, 1200)
        }
        refresh()
    }

    private fun currentIssues() =
        if (node != null && Checker.text == nodeText) Checker.issues else emptyList()

    private fun refresh() {
        val n = node
        val issues = currentIssues()
        lines.clear()
        if (n != null) {
            val field = Rect().also(n::getBoundsInScreen)
            issues.forEach { i ->
                lines += rectsFor(n, i).filter { it.bottom <= field.bottom + dp(4f) && it.top >= field.top - dp(4f) }
                    .map { it to getColor(i.category.color) }
            }
        }
        underlines.invalidate()

        // Our own keyboard already shows the cards; only float the button over other keyboards.
        val showBall = n != null && !ourKeyboardActive()
        ball.visibility = if (showBall) View.VISIBLE else View.GONE
        if (!showBall) return hidePanel()
        val failed = Checker.text == nodeText && Checker.error != null
        badge.text = if (failed) "!" else issues.size.toString()
        badge.visibility = if (issues.isEmpty() && !failed) View.GONE else View.VISIBLE
        val b = Rect().also(n!!::getBoundsInScreen)
        val p = ball.layoutParams as WindowManager.LayoutParams
        val size = dp(44f).toInt()
        val x = b.right - size - dp(4f).toInt()
        val y = if (b.height() > dp(120f)) b.top + dp(6f).toInt() else b.top + (b.height() - size) / 2
        if (p.x != x || p.y != y) { p.x = x; p.y = y; wm.updateViewLayout(ball, p) }
    }

    /** Screen rects of the issue's characters, merged into one rect per line. */
    private fun rectsFor(n: AccessibilityNodeInfo, i: Checker.Issue): List<RectF> {
        val key = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
        if (key !in n.availableExtraData) return emptyList()
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, i.start)
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, i.original.length)
        }
        if (!n.refreshWithExtraData(key, args)) return emptyList()
        val rects = BundleCompat.getParcelableArray(n.extras, key, RectF::class.java) ?: return emptyList()
        val out = mutableListOf<RectF>()
        rects.filterIsInstance<RectF>().forEach { r ->
            val last = out.lastOrNull()
            if (last != null && abs(last.bottom - r.bottom) < 2) last.union(r) else out += RectF(r)
        }
        return out
    }

    // ---- Floating button and panel ----------------------------------------------------------

    private fun ball(): View {
        val f = FrameLayout(this)
        f.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_logo)
            elevation = dp(4f)
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(getColor(R.color.logo)) }
        }, FrameLayout.LayoutParams(dp(36f).toInt(), dp(36f).toInt(), Gravity.CENTER))
        badge = TextView(this).apply {
            setTextColor(getColor(R.color.white))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            elevation = dp(5f)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(getColor(R.color.kb_error)) }
        }
        f.addView(badge, FrameLayout.LayoutParams(dp(17f).toInt(), dp(17f).toInt(), Gravity.TOP or Gravity.END))
        f.setPadding(dp(2f).toInt(), dp(2f).toInt(), dp(2f).toInt(), dp(2f).toInt())
        f.contentDescription = "Quill suggestions"
        f.setOnClickListener { if (panel == null) showPanel() else hidePanel() }
        return f
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showPanel() {
        val text = nodeText
        val issues = currentIssues()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f).toInt(), dp(12f).toInt(), dp(16f).toInt(), dp(12f).toInt())
            elevation = dp(8f)
            background = GradientDrawable().apply { cornerRadius = dp(18f); setColor(getColor(R.color.surface_card)) }
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply { setImageResource(R.drawable.ic_logo) }, LinearLayout.LayoutParams(dp(22f).toInt(), dp(22f).toInt()))
        val error = Checker.error.takeIf { Checker.text == text && !Checker.busy }
        header.addView(label(
            when {
                Checker.busy && Checker.text == text -> "Checking"
                error != null -> "Ollama error"
                issues.isEmpty() -> "No issues found"
                issues.size == 1 -> "1 suggestion"
                else -> "${issues.size} suggestions"
            }, 16f, R.color.text_primary, bold = true,
        ).apply { setPadding(dp(10f).toInt(), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        if (Checker.busy && Checker.text == text) header.addView(ProgressBar(this).apply {
            indeterminateTintList = ColorStateList.valueOf(getColor(R.color.brand))
        }, LinearLayout.LayoutParams(dp(18f).toInt(), dp(18f).toInt()).apply { marginEnd = dp(10f).toInt() })
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(getColor(R.color.text_secondary))
            contentDescription = "Close"
            setOnClickListener { hidePanel() }
        }, LinearLayout.LayoutParams(dp(22f).toInt(), dp(22f).toInt()))
        card.addView(header)
        if (error != null) card.addView(label("$error. Check the server URL and model in Quill.", 14f, R.color.text_secondary).apply {
            maxLines = 3
            setPadding(0, dp(8f).toInt(), 0, dp(4f).toInt())
        })

        issues.take(5).forEach { i -> card.addView(issueRow(i)) }
        if (issues.size > 1) card.addView(label("Accept all", 15f, R.color.brand, bold = true).apply {
            setPadding(0, dp(12f).toInt(), 0, dp(4f).toInt())
            setOnClickListener { val all = Checker.applyAll(text, issues); if (writeField(all, null)) Checker.applied(all, emptyList()) }
        })

        val v = FrameLayout(this).apply {
            setPadding(dp(8f).toInt(), dp(8f).toInt(), dp(8f).toInt(), dp(8f).toInt())
            addView(card)
            setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_OUTSIDE) hidePanel(); false }
        }
        // Anchor to the text field: just below it, else just above it, never on top of the text.
        val field = Rect().also { r -> node?.getBoundsInScreen(r) }
        val screenW = resources.displayMetrics.widthPixels
        val w = minOf(dp(340f).toInt(), screenW)
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val h = v.measuredHeight
        val bottomLimit = minOf(imeTop(), resources.displayMetrics.heightPixels)
        val topLimit = dp(24f).toInt()
        val p = params(w, -2, touchable = true).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            x = field.left.coerceIn(0, maxOf(0, screenW - w))
            y = when {
                field.bottom + h <= bottomLimit -> field.bottom
                field.top - h >= topLimit -> field.top - h
                else -> (bottomLimit - h).coerceAtLeast(topLimit)
            }
        }
        hidePanel()
        panel = v
        wm.addView(v, p)
    }

    private fun issueRow(i: Checker.Issue) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(10f).toInt(), 0, dp(10f).toInt())
        addView(label(i.type.uppercase(), 11f, R.color.text_secondary).apply { letterSpacing = 0.06f; dot(this, i) })
        val s = SpannableStringBuilder()
        s.append(i.original, StrikethroughSpan(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(ForegroundColorSpan(getColor(R.color.text_secondary)), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.append("   ")
        val start = s.length
        s.append(i.replacement.ifBlank { "(remove)" })
        s.setSpan(ForegroundColorSpan(getColor(R.color.brand)), start, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        addView(label("", 16f, R.color.text_primary).apply { text = s })
        contentDescription = "${i.type}: replace ${i.original} with ${i.replacement}"
        setOnClickListener { applyIssue(i) }
    }

    private fun label(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(getColor(color))
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun hidePanel() {
        panel?.let { wm.removeView(it) }
        panel = null
    }

    // ---- Editing ----------------------------------------------------------------------------

    private fun applyIssue(issue: Checker.Issue) {
        val text = nodeText
        val i = Checker.locate(text, listOf(issue)).firstOrNull() ?: return
        val newText = Checker.applyTo(text, i)
        if (writeField(newText, i.start + i.replacement.length)) Checker.applied(newText, Checker.issues - issue)
    }

    private fun writeField(t: String, cursor: Int?): Boolean {
        // Re-find the field: the node we stored can be a stale snapshot by the time the user taps.
        val n = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } ?: node ?: return false
        val ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t)
        })
        if (!ok) { Log.w("Quill", "SET_TEXT refused by ${n.packageName}/${n.className}"); return false }
        node = n
        val c = cursor ?: t.length
        n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, c)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, c)
        })
        nodeText = t
        return true
    }

    // A hidden keyboard can still be listed with empty bounds; only a visible one counts.
    private fun imeTop() = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD && it.root?.isVisibleToUser == true }
        ?.let { Rect().also(it::getBoundsInScreen) }?.takeIf { !it.isEmpty }?.top ?: Int.MAX_VALUE

    private fun ourKeyboardActive() =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith(packageName) == true

    private fun params(w: Int, h: Int, touchable: Boolean) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
