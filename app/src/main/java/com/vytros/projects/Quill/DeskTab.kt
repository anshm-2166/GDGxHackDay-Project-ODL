package com.vytros.projects.Quill

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Paint
import android.provider.CalendarContract
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat

/** Everything the chat commands saved: upcoming and past events, notes and goals. */
class DeskTab(private val act: MainActivity, root: View) {
    enum class Section { UPCOMING, PAST, NOTES, GOALS }

    private val list = root.findViewById<LinearLayout>(R.id.deskList)
    private val empty = root.findViewById<TextView>(R.id.deskEmpty)
    private val summary = root.findViewById<TextView>(R.id.deskSummary)
    private val add = root.findViewById<TextView>(R.id.deskAdd)
    private val segs = mapOf(
        Section.UPCOMING to root.findViewById<TextView>(R.id.segUpcoming),
        Section.PAST to root.findViewById<TextView>(R.id.segPast),
        Section.NOTES to root.findViewById<TextView>(R.id.segNotes),
        Section.GOALS to root.findViewById<TextView>(R.id.segGoals),
    )
    private var section = Section.UPCOMING

    init {
        segs.forEach { (s, v) -> v.setOnClickListener { show(s) } }
        add.setOnClickListener { if (section == Section.GOALS) newGoal() else newNote() }
        Store.listener = { refresh() }
        refresh()
    }

    fun show(s: Section) { section = s; refresh() }

    fun refresh() {
        val now = System.currentTimeMillis()
        val upcoming = Store.upcoming(act, now)
        val past = Store.events(act).filter { it.endMs < now }.sortedByDescending { it.startMs }
        val notes = Store.notes(act)
        val goals = Store.goals(act)
        summary.text = "${upcoming.size} upcoming · ${past.size} past · ${notes.size} note${if (notes.size == 1) "" else "s"} · ${goals.size} goal${if (goals.size == 1) "" else "s"}"
        segs.forEach { (s, v) ->
            val on = s == section
            v.setBackgroundResource(if (on) R.drawable.bg_nav_pill else 0)
            v.setTextColor(act.getColor(if (on) R.color.on_brand_container else R.color.text_secondary))
        }
        add.visibility = if (section == Section.NOTES || section == Section.GOALS) View.VISIBLE else View.GONE
        add.text = if (section == Section.GOALS) "New goal" else "New note"
        list.removeAllViews()
        when (section) {
            Section.UPCOMING -> {
                upcoming.forEach { e -> event(e, past = false) }
                nothing(upcoming.isEmpty(), "Nothing scheduled.\nIn Chat, try /addevent Standup tomorrow 10am or /alarm 6:30am gym")
            }
            Section.PAST -> {
                past.forEach { e -> event(e, past = true) }
                nothing(past.isEmpty(), "No past events yet.")
            }
            Section.NOTES -> {
                notes.forEach { n ->
                    row(n.text, Reminders.whenText(n.createdMs), listOf(
                        "Copy" to { copy(n.text) },
                        "Remove" to { Store.removeNote(act, n.id) },
                    ), faded = false)
                }
                nothing(notes.isEmpty(), "No notes yet.\nIn Chat, try /note Call the vendor on Monday")
            }
            Section.GOALS -> {
                goals.forEach { goal(it) }
                nothing(goals.isEmpty(), "No goals yet.\nIn Chat, try /goal Launch my portfolio site by 30 November")
            }
        }
    }

    private fun nothing(isEmpty: Boolean, text: String) {
        empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        empty.text = text
    }

    private fun event(e: Store.Event, past: Boolean) {
        val sub = Reminders.whenText(e.startMs) + (if (e.location.isNotBlank()) " · ${e.location}" else "") +
            (if (e.alarm) " · alarm" else if (e.reminder) " · reminder" else "")
        row(e.title, sub, listOfNotNull(
            if (e.reminder) null else "Calendar" to { insertCalendar(e) },
            "Remove" to { Store.removeEvent(act, e.id) },
        ), faded = past)
    }

    private fun chip(label: String, onClick: () -> Unit) = TextView(act).apply {
        text = label
        textSize = 13f
        typeface = ResourcesCompat.getFont(act, R.font.dmsans_medium)
        setTextColor(act.getColor(if (label == "Remove" || label == "Delete") R.color.kb_error else R.color.brand))
        setBackgroundResource(R.drawable.bg_chip)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(act.dp(14), 0, act.dp(14), 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, act.dp(34)).apply { marginEnd = act.dp(8) }
        setOnClickListener { onClick() }
    }

    private fun row(title: String, sub: String, actions: List<Pair<String, () -> Unit>>, faded: Boolean) {
        val v = act.layoutInflater.inflate(R.layout.item_entry, list, false)
        v.findViewById<TextView>(R.id.eTitle).text = title
        v.findViewById<TextView>(R.id.eSub).text = sub
        if (faded) v.alpha = 0.6f
        val row = v.findViewById<LinearLayout>(R.id.eActions)
        actions.forEach { (label, run) -> row.addView(chip(label, run)) }
        list.addView(v)
    }

    // ---- goals ----

    private fun goal(g: Store.Goal) {
        val v = act.layoutInflater.inflate(R.layout.item_goal, list, false)
        v.findViewById<TextView>(R.id.gTitle).text = g.title
        val due = if (g.dueMs > 0) " · due ${java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(g.dueMs))}" else ""
        v.findViewById<TextView>(R.id.gSub).text = "${g.doneCount} of ${g.steps.size} done · ${g.percent}%$due" + if (g.complete) " · complete" else ""
        v.findViewById<ProgressBar>(R.id.gProgress).progress = g.percent
        val steps = v.findViewById<LinearLayout>(R.id.gSteps)
        g.steps.forEachIndexed { i, s ->
            steps.addView(TextView(act).apply {
                text = (if (s.done) "✓  " else "○  ") + s.text
                textSize = 15f
                setTextColor(act.getColor(if (s.done) R.color.text_hint else R.color.text_primary))
                if (s.done) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                setBackgroundResource(R.drawable.bg_row)
                setPadding(act.dp(6), act.dp(10), act.dp(6), act.dp(10))
                contentDescription = (if (s.done) "Done: " else "To do: ") + s.text
                setOnClickListener { Store.setStep(act, g.id, i, !s.done) }
            })
        }
        val actions = v.findViewById<LinearLayout>(R.id.gActions)
        actions.addView(chip("Add step") { addStep(g) })
        actions.addView(chip("Delete") { Store.removeGoal(act, g.id) })
        list.addView(v)
    }

    private fun newGoal() = act.editPrompt(
        PromptEdit("New goal", "A name and its steps, one step per line.", "", false, "", canReset = false, canDelete = false,
            nameHint = "Goal", textHint = "Step 1\nStep 2\nStep 3"),
        onSave = { name, text ->
            val steps = text.lines().map { it.trim().trimStart('-', '*', ' ') }.filter { it.isNotEmpty() }
            when {
                name.isBlank() -> "Give it a name"
                steps.isEmpty() -> "Add at least one step"
                else -> {
                    val now = System.currentTimeMillis()
                    Store.addGoal(act, Store.Goal(now, name, now, 0L, steps.map { Store.Step(it) })); null
                }
            }
        },
        onReset = {},
    )

    private fun addStep(g: Store.Goal) = act.editPrompt(
        PromptEdit("Add a step", g.title, null, false, "", canReset = false, canDelete = false, textHint = "What needs doing?"),
        onSave = { _, text ->
            Store.goal(act, g.id)?.let { cur -> Store.updateGoal(act, cur.copy(steps = cur.steps + Store.Step(text.lines().first().trim()))) }
            null
        },
        onReset = {},
    )

    private fun newNote() = act.editPrompt(
        PromptEdit("New note", "Saved on this phone.", null, false, "", canReset = false, canDelete = false, textHint = "Write a note"),
        onSave = { _, text -> Store.addNote(act, text); null },
        onReset = {},
    )

    private fun insertCalendar(e: Store.Event) {
        val i = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, e.title)
            .putExtra(CalendarContract.Events.EVENT_LOCATION, e.location)
            .putExtra(CalendarContract.Events.DESCRIPTION, e.notes)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.startMs)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.endMs)
        try { act.startActivity(i) } catch (x: ActivityNotFoundException) { Toast.makeText(act, "No calendar app found", Toast.LENGTH_SHORT).show() }
    }

    private fun copy(text: String) {
        act.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Quill", text))
        Toast.makeText(act, "Copied", Toast.LENGTH_SHORT).show()
    }
}
