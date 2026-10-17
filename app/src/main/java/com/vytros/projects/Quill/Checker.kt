package com.vytros.projects.Quill

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.widget.TextView

/** The category's coloured dot before a suggestion label, like Grammarly's cards. */
fun dot(tv: TextView, i: Checker.Issue) {
    val size = (7 * tv.resources.displayMetrics.density).toInt()
    val d = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(tv.context.getColor(i.category.color))
        setBounds(0, 0, size, size)
    }
    tv.setCompoundDrawablesRelative(d, null, null, null)
    tv.compoundDrawablePadding = size
}

/**
 * The live grammar state shared by the keyboard and the floating assistant (same process),
 * so a text is only ever checked once no matter who asked.
 */
object Checker {
    /** Grammarly's four suggestion types, each with its own underline colour. */
    enum class Category(val color: Int) {
        CORRECTNESS(R.color.cat_correctness),
        CLARITY(R.color.cat_clarity),
        ENGAGEMENT(R.color.cat_engagement),
        DELIVERY(R.color.cat_delivery),
    }

    data class Issue(
        val type: String, val original: String, val replacement: String, val start: Int,
        val category: Category = Category.CORRECTNESS,
    ) {
        val end get() = start + original.length
    }

    var text = ""; private set
    var issues: List<Issue> = emptyList(); private set
    var busy = false; private set
    var error: String? = null; private set
    private val listeners = mutableSetOf<() -> Unit>()

    fun listen(l: () -> Unit) = listeners.add(l)
    fun unlisten(l: () -> Unit) = listeners.remove(l)
    private fun changed() = listeners.toList().forEach { it() }

    fun check(ctx: Context, t: String) {
        if (t == text) return
        text = t
        issues = emptyList()
        error = null
        busy = t.trim().length >= 3
        changed()
        if (!busy) return
        // Correctness and style run separately; whichever lands first shows first.
        var pending = 2
        val done = { r: Result<List<Issue>> ->
            if (t == text) { // else the user kept typing and a newer check owns the state
                busy = --pending > 0
                r.onSuccess { issues = merge(issues + locate(t, it)) }
                r.onFailure { error = error ?: it.message }
                changed()
            }
        }
        Gemma.issues(ctx, t, done)
        Gemma.style(ctx, t, done)
    }

    /** Sorted by position; where suggestions overlap, correctness beats style. */
    fun merge(all: List<Issue>): List<Issue> {
        val kept = mutableListOf<Issue>()
        all.sortedBy { it.category.ordinal }.forEach { i ->
            if (kept.none { i.start < it.end && it.start < i.end }) kept += i
        }
        return kept.sortedBy { it.start }
    }

    /** Records an edit we made ourselves, so it isn't re-checked and the other issues stay. */
    fun applied(newText: String, remaining: List<Issue>) {
        text = newText
        issues = locate(newText, remaining)
        changed()
    }

    /** Pins each issue to where its snippet actually is in [text]; drops ones that aren't there. */
    fun locate(text: String, raw: List<Issue>): List<Issue> {
        var from = 0
        return raw.mapNotNull { i ->
            if (i.original.isEmpty() || i.original == i.replacement) return@mapNotNull null
            val at = when {
                i.start >= 0 && text.startsWith(i.original, i.start) -> i.start
                else -> text.indexOf(i.original, from).takeIf { it >= 0 } ?: text.indexOf(i.original)
            }
            if (at < 0) null else i.copy(start = at).also { from = it.end }
        }.distinctBy { it.start }
    }

    /**
     * Turns a corrected version of [text] into issues by diffing words, so every change the
     * model made shows up as a card, not only the ones it would remember to list.
     */
    fun diff(text: String, fixed: String): List<Issue> {
        val a = Regex("\\S+").findAll(text).toList()
        val b = Regex("\\S+").findAll(fixed).toList()
        // ponytail: O(n*m) LCS on words, fine for messages; chunk by sentence if texts get long.
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed())
            lcs[i][j] = if (a[i].value == b[j].value) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        val out = mutableListOf<Issue>()
        var i = 0; var j = 0
        while (i < a.size || j < b.size) {
            if (i < a.size && j < b.size && a[i].value == b[j].value) { i++; j++; continue }
            val i0 = i; val j0 = j
            while ((i < a.size || j < b.size) && !(i < a.size && j < b.size && a[i].value == b[j].value)) {
                if (j >= b.size || (i < a.size && lcs[i + 1][j] >= lcs[i][j + 1])) i++ else j++
            }
            out += span(text, fixed, a, b, i0, i, j0, j) ?: continue
        }
        return out
    }

    // Builds one issue from changed words a[i0 until i1] -> b[j0 until j1], anchoring pure
    // insertions/deletions to a neighbouring word so there is always something to underline.
    private fun span(text: String, fixed: String, a: List<MatchResult>, b: List<MatchResult>, i0: Int, i1: Int, j0: Int, j1: Int): Issue? {
        var ai0 = i0; var bj0 = j0
        if (i0 == i1) { // insertion: include the previous (or next) word on both sides
            if (i0 > 0) { ai0--; bj0-- } else if (i1 < a.size) return Issue(
                label(a[i1].value, fixed.substring(b[j0].range.first, b[j1].range.last + 1)),
                a[i1].value, fixed.substring(b[j0].range.first, b[j1].range.last + 1), a[i1].range.first,
            ) else return null
        }
        val start = a[ai0].range.first
        val original = text.substring(start, a[i1 - 1].range.last + 1)
        val replacement = if (bj0 == j1) "" else fixed.substring(b[bj0].range.first, b[j1 - 1].range.last + 1)
        if (replacement.isEmpty() && ai0 > 0) { // deletion: keep the previous word so the card reads naturally
            val prev = a[ai0 - 1]
            return Issue("Unnecessary word", text.substring(prev.range.first, a[i1 - 1].range.last + 1), prev.value, prev.range.first)
        }
        return Issue(label(original, replacement), original, replacement, start)
    }

    private val ARTICLES = setOf("a", "an", "the")

    fun label(o: String, r: String): String {
        val ow = o.split(' '); val rw = r.split(' ')
        val letters = { s: String -> s.filter { it.isLetterOrDigit() }.lowercase() }
        return when {
            r.isEmpty() -> "Unnecessary word"
            o.equals(r, ignoreCase = true) -> "Capitalization"
            letters(o) == letters(r) -> "Punctuation"
            rw.size > ow.size && (rw - ow.toSet()).any { it.lowercase() in ARTICLES } -> "Missing article"
            rw.size > ow.size -> "Missing word"
            rw.size < ow.size -> "Unnecessary word"
            // Close match relative to length, so "iz"->"is" is spelling but "Me"->"I" is grammar.
            ow.size == 1 && distance(letters(o), letters(r)) <= maxOf(1, letters(o).length / 3) -> "Spelling"
            else -> "Grammar"
        }
    }

    private fun distance(s: String, t: String): Int {
        var prev = IntArray(t.length + 1) { it }
        for (i in 1..s.length) {
            val cur = IntArray(t.length + 1).also { it[0] = i }
            for (j in 1..t.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (s[i - 1] == t[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[t.length]
    }

    fun applyTo(text: String, i: Issue) = text.replaceRange(i.start, i.end, i.replacement)

    /** Applies every issue at once, back to front so earlier offsets stay valid. */
    fun applyAll(text: String, all: List<Issue>) = all.sortedByDescending { it.start }.fold(text, ::applyTo)
}
