package com.vytros.projects.Quill

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Calendar entries, notes and goals saved by chat commands. Small enough to live in preferences as JSON. */
object Store {
    /** Called after every change so open screens (the Desk) redraw themselves. */
    @Volatile var listener: (() -> Unit)? = null

    data class Event(
        val id: Long, val title: String, val startMs: Long, val endMs: Long,
        val location: String, val notes: String, val remindMin: Int, val reminder: Boolean,
        val alarm: Boolean = false, val viaClock: Boolean = false,
    )

    data class Note(val id: Long, val text: String, val createdMs: Long)

    data class Step(val text: String, val done: Boolean = false)

    data class Goal(val id: Long, val title: String, val createdMs: Long, val dueMs: Long, val steps: List<Step>) {
        val doneCount get() = steps.count { it.done }
        val percent get() = if (steps.isEmpty()) 0 else doneCount * 100 / steps.size
        val complete get() = steps.isNotEmpty() && doneCount == steps.size
    }

    // ---- events ----

    fun events(ctx: Context): List<Event> = read(ctx, "events") { o ->
        Event(o.getLong("id"), o.getString("title"), o.getLong("start"), o.getLong("end"),
            o.optString("location"), o.optString("notes"), o.optInt("remind", 10), o.optBoolean("reminder"),
            o.optBoolean("alarm"), o.optBoolean("clock"))
    }.sortedBy { it.startMs }

    fun upcoming(ctx: Context, now: Long = System.currentTimeMillis()) = events(ctx).filter { it.endMs >= now }

    private fun putEvent(o: JSONObject, x: Event) {
        o.put("id", x.id).put("title", x.title).put("start", x.startMs).put("end", x.endMs)
            .put("location", x.location).put("notes", x.notes).put("remind", x.remindMin).put("reminder", x.reminder)
            .put("alarm", x.alarm).put("clock", x.viaClock)
    }

    fun addEvent(ctx: Context, e: Event) {
        write(ctx, "events", events(ctx) + e, ::putEvent)
        // An alarm handed to the Clock app rings from there; everything else is ours to schedule.
        if (!e.viaClock) runCatching { Reminders.schedule(ctx, e) } // saved even if Android refuses the alarm
    }

    /** Replaces the event with the same id and moves its alarm. */
    fun updateEvent(ctx: Context, e: Event) {
        Reminders.cancel(ctx, e.id)
        write(ctx, "events", events(ctx).map { if (it.id == e.id) e else it }, ::putEvent)
        if (!e.viaClock) runCatching { Reminders.schedule(ctx, e) }
    }

    fun event(ctx: Context, id: Long) = events(ctx).firstOrNull { it.id == id }

    fun removeEvent(ctx: Context, id: Long) {
        Reminders.cancel(ctx, id)
        write(ctx, "events", events(ctx).filter { it.id != id }, ::putEvent)
    }

    // ---- notes ----

    private fun putNote(o: JSONObject, x: Note) { o.put("id", x.id).put("text", x.text).put("created", x.createdMs) }

    fun notes(ctx: Context): List<Note> = read(ctx, "notes") { o -> Note(o.getLong("id"), o.getString("text"), o.getLong("created")) }
        .sortedByDescending { it.createdMs }

    fun addNote(ctx: Context, text: String) {
        val now = System.currentTimeMillis()
        write(ctx, "notes", notes(ctx) + Note(now, text, now), ::putNote)
    }

    fun removeNote(ctx: Context, id: Long) = write(ctx, "notes", notes(ctx).filter { it.id != id }, ::putNote)

    // ---- goals ----

    fun goals(ctx: Context): List<Goal> = read(ctx, "goals") { o ->
        val arr = o.getJSONArray("steps")
        Goal(o.getLong("id"), o.getString("title"), o.getLong("created"), o.optLong("due"),
            List(arr.length()) { arr.getJSONObject(it).let { s -> Step(s.getString("text"), s.optBoolean("done")) } })
    }.sortedBy { it.createdMs }

    private fun putGoal(o: JSONObject, g: Goal) {
        val steps = JSONArray()
        g.steps.forEach { steps.put(JSONObject().put("text", it.text).put("done", it.done)) }
        o.put("id", g.id).put("title", g.title).put("created", g.createdMs).put("due", g.dueMs).put("steps", steps)
    }

    fun goal(ctx: Context, id: Long) = goals(ctx).firstOrNull { it.id == id }

    fun addGoal(ctx: Context, g: Goal) = write(ctx, "goals", goals(ctx) + g, ::putGoal)

    fun updateGoal(ctx: Context, g: Goal) = write(ctx, "goals", goals(ctx).map { if (it.id == g.id) g else it }, ::putGoal)

    fun removeGoal(ctx: Context, id: Long) = write(ctx, "goals", goals(ctx).filter { it.id != id }, ::putGoal)

    /** Marks step [index] (0-based) of goal [id]; returns the updated goal, or null if either is gone. */
    fun setStep(ctx: Context, id: Long, index: Int, done: Boolean): Goal? {
        val g = goal(ctx, id) ?: return null
        if (index !in g.steps.indices) return null
        val updated = g.copy(steps = g.steps.mapIndexed { i, s -> if (i == index) s.copy(done = done) else s })
        updateGoal(ctx, updated)
        return updated
    }

    // ---- plumbing ----

    private fun <T> read(ctx: Context, key: String, parse: (JSONObject) -> T): List<T> = runCatching {
        val arr = JSONArray(Gemma.prefs(ctx).getString(key, "[]"))
        List(arr.length()) { parse(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun <T> write(ctx: Context, key: String, items: List<T>, put: (JSONObject, T) -> Unit) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().also { o -> put(o, it) }) }
        Gemma.prefs(ctx).edit().putString(key, arr.toString()).apply()
        listener?.invoke()
    }
}
