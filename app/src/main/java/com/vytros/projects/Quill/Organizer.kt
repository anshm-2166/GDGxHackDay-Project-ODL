package com.vytros.projects.Quill

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Lets the model change events, alarms and goals from plain words ("move gym to 7am", "I finished the outline").
 * The model sees short references (E1, G2.3) instead of database ids and answers with a list of operations.
 */
object Organizer {
    class Op(
        val op: String, val ref: String, val title: String, val text: String, val start: String,
        val durationMinutes: Int?, val location: String?, val remindMinutes: Int?, val done: Boolean?,
    )

    class Snapshot(val text: String, val events: Map<String, Store.Event>, val goals: Map<String, Store.Goal>) {
        val isEmpty get() = events.isEmpty() && goals.isEmpty()
    }

    val SCHEMA = JSONObject(
        """{"type":"object","properties":{"ops":{"type":"array","items":{"type":"object","properties":{
        "op":{"type":"string","enum":["update_event","delete_event","mark_step","add_step","delete_step","rename_goal","delete_goal"]},
        "ref":{"type":"string"},"title":{"type":"string"},"text":{"type":"string"},"start":{"type":"string"},
        "durationMinutes":{"type":"integer"},"location":{"type":"string"},"remindMinutes":{"type":"integer"},"done":{"type":"boolean"}},
        "required":["op","ref"]}},"reply":{"type":"string"}},"required":["ops"]}""",
    )

    private val WORDS = Regex(
        """\b(done|finished|finish|completed|complete|mark|tick|reschedule|postpone|snooze|push|move|shift|change|cancel|delete|remove|rename|update|edit)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Cheap check before spending a model call on an ordinary chat message. */
    fun worthTrying(text: String) = text.length <= 240 && WORDS.containsMatchIn(text)

    fun snapshot(ctx: Context, now: ZonedDateTime = ZonedDateTime.now()) =
        snapshot(Store.upcoming(ctx).take(30), Store.goals(ctx).take(20), now)

    fun snapshot(events: List<Store.Event>, goals: List<Store.Goal>, now: ZonedDateTime): Snapshot {
        val f = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm (EEEE)", Locale.ENGLISH)
        val evMap = linkedMapOf<String, Store.Event>()
        val goalMap = linkedMapOf<String, Store.Goal>()
        val text = buildString {
            if (events.isNotEmpty()) appendLine("EVENTS, ALARMS AND REMINDERS:")
            events.forEachIndexed { i, e ->
                val ref = "E${i + 1}"
                evMap[ref] = e
                val kind = if (e.alarm) "alarm" else if (e.reminder) "reminder" else "event"
                val at = Instant.ofEpochMilli(e.startMs).atZone(now.zone).format(f)
                val extra = (if (!e.reminder) ", ${(e.endMs - e.startMs) / 60_000} min" else "") + (if (e.location.isNotBlank()) ", at ${e.location}" else "")
                appendLine("$ref: [$kind] ${e.title}: $at$extra")
            }
            if (goals.isNotEmpty()) appendLine("GOALS:")
            goals.forEachIndexed { i, g ->
                val ref = "G${i + 1}"
                goalMap[ref] = g
                appendLine("$ref: ${g.title} (${g.doneCount} of ${g.steps.size} done)")
                g.steps.forEachIndexed { j, s -> appendLine("  $ref.${j + 1} [${if (s.done) "done" else "open"}] ${s.text}") }
            }
        }.trim()
        return Snapshot(text, evMap, goalMap)
    }

    fun system(s: Snapshot, now: ZonedDateTime = ZonedDateTime.now()) =
        "You change the user's schedule and goals for them. The current local time is " +
            "${now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))}. Look relative days up in this table: ${Prompts.calendar(now)}.\n\n" +
            "${s.text}\n\n" +
            "Reply with one JSON object {\"ops\": [...], \"reply\": \"...\"}. Each op has an \"op\" and a \"ref\" copied exactly from the lists above:\n" +
            "- update_event: change an event, alarm or reminder. Include only the fields that change: title, start (yyyy-MM-dd'T'HH:mm), durationMinutes, location, remindMinutes. " +
            "If the user gives only a new time of day, keep the item's current date; if only a new day, keep its time.\n" +
            "- delete_event: remove an event, alarm or reminder.\n" +
            "- mark_step: ref like G1.2; done true when the user finished it, false to reopen it.\n" +
            "- add_step: ref like G1; text is the new step.\n" +
            "- delete_step: ref like G1.2.\n" +
            "- rename_goal: ref like G1; text is the new name.\n" +
            "- delete_goal: ref like G1.\n" +
            "Match items by meaning, not exact words. Use only refs that exist. If nothing matches or the message is not a request to change these, " +
            "return an empty ops list and put a short explanation in reply."

    class Parsed(val ops: List<Op>, val reply: String)

    fun parse(raw: String): Parsed {
        val a = raw.indexOf('{'); val b = raw.lastIndexOf('}')
        require(a in 0 until b) { "no JSON object" }
        val o = JSONObject(raw.substring(a, b + 1))
        val arr = o.optJSONArray("ops") ?: JSONArray()
        val ops = List(arr.length()) {
            val x = arr.getJSONObject(it)
            Op(
                op = x.optString("op"), ref = x.optString("ref").trim().uppercase(), title = x.optString("title").trim(),
                text = x.optString("text").trim(), start = x.optString("start").trim(),
                durationMinutes = if (x.has("durationMinutes")) x.optInt("durationMinutes") else null,
                location = if (x.has("location")) x.optString("location").trim() else null,
                remindMinutes = if (x.has("remindMinutes")) x.optInt("remindMinutes") else null,
                done = if (x.has("done")) x.optBoolean("done") else null,
            )
        }
        return Parsed(ops, o.optString("reply").trim())
    }

    /**
     * Applies [ops] and returns one human line per op. Unknown refs and bad times are reported, not thrown.
     * [clockAlarm] hands a changed alarm to the Clock app and says whether it was accepted.
     */
    fun apply(ctx: Context, snap: Snapshot, ops: List<Op>, now: ZonedDateTime, clockAlarm: (Store.Event) -> Boolean): List<String> =
        ops.map { op -> runCatching { applyOne(ctx, snap, op, now, clockAlarm) }.getOrElse { "Skipped ${op.op.replace('_', ' ')} ${op.ref}: ${it.message}" } }

    private fun applyOne(ctx: Context, snap: Snapshot, op: Op, now: ZonedDateTime, clockAlarm: (Store.Event) -> Boolean): String {
        val goalKey = op.ref.substringBefore('.')
        val stepNo = op.ref.substringAfter('.', "").toIntOrNull()
        return when (op.op) {
            "update_event" -> {
                val old = Store.event(ctx, (snap.events[op.ref] ?: error("no such item")).id) ?: error("it no longer exists")
                var start = old.startMs
                if (op.start.isNotEmpty()) start = EventParser.whenOf(op.start, now).toInstant().toEpochMilli()
                require(start >= now.toInstant().toEpochMilli()) { "${Reminders.whenText(start)} has already passed" }
                val length = op.durationMinutes?.coerceIn(5, 24 * 60)?.let { it * 60_000L } ?: (old.endMs - old.startMs)
                var e = old.copy(
                    title = op.title.ifEmpty { old.title }, startMs = start, endMs = if (old.reminder) start else start + length,
                    location = op.location ?: old.location,
                    remindMin = if (old.reminder) 0 else op.remindMinutes?.coerceIn(0, 24 * 60) ?: old.remindMin,
                )
                val moved = start != old.startMs
                if (old.alarm && moved) e = e.copy(viaClock = clockAlarm(e))
                Store.updateEvent(ctx, e)
                "Updated \"${e.title}\": ${Reminders.whenText(e.startMs)}" +
                    if (old.viaClock && moved) ". The old alarm is still in your Clock app, so delete it there." else ""
            }
            "delete_event" -> {
                val e = snap.events[op.ref] ?: error("no such item")
                Store.removeEvent(ctx, e.id)
                "Deleted \"${e.title}\"." + if (e.viaClock) " Delete the alarm in your Clock app too." else ""
            }
            "mark_step" -> {
                val g = snap.goals[goalKey] ?: error("no such goal")
                val i = (stepNo ?: error("which step?")) - 1
                val done = op.done ?: true
                val updated = Store.setStep(ctx, g.id, i, done) ?: error("no such step")
                "${if (done) "Done" else "Reopened"}: \"${updated.steps[i].text}\". ${updated.title}: ${updated.doneCount} of ${updated.steps.size} (${updated.percent}%)" +
                    if (updated.complete) ". Goal complete!" else ""
            }
            "add_step" -> {
                val g = Store.goal(ctx, (snap.goals[goalKey] ?: error("no such goal")).id) ?: error("it no longer exists")
                require(op.text.isNotEmpty()) { "no step text" }
                Store.updateGoal(ctx, g.copy(steps = g.steps + Store.Step(op.text)))
                "Added step \"${op.text}\" to ${g.title}."
            }
            "delete_step" -> {
                val g = Store.goal(ctx, (snap.goals[goalKey] ?: error("no such goal")).id) ?: error("it no longer exists")
                val i = (stepNo ?: error("which step?")) - 1
                require(i in g.steps.indices) { "no such step" }
                Store.updateGoal(ctx, g.copy(steps = g.steps.filterIndexed { n, _ -> n != i }))
                "Removed step \"${g.steps[i].text}\" from ${g.title}."
            }
            "rename_goal" -> {
                val g = Store.goal(ctx, (snap.goals[goalKey] ?: error("no such goal")).id) ?: error("it no longer exists")
                require(op.text.isNotEmpty()) { "no new name" }
                Store.updateGoal(ctx, g.copy(title = op.text))
                "Renamed \"${g.title}\" to \"${op.text}\"."
            }
            "delete_goal" -> {
                val g = snap.goals[goalKey] ?: error("no such goal")
                Store.removeGoal(ctx, g.id)
                "Deleted goal \"${g.title}\"."
            }
            else -> error("unknown action")
        }
    }

    // ---- creating a goal ----

    val GOAL_SCHEMA = JSONObject(
        """{"type":"object","properties":{"title":{"type":"string"},"steps":{"type":"array","items":{"type":"string"}},
        "dueDate":{"type":"string"}},"required":["title","steps"]}""",
    )

    fun goalSystem(now: ZonedDateTime = ZonedDateTime.now()) =
        "You turn the user's goal into a concrete plan. Reply with one JSON object: title (short, starts with a verb, under 60 characters), " +
            "steps (3 to 7 ordered, specific, doable steps; if the user listed their own steps use exactly those), " +
            "dueDate (yyyy-MM-dd if the user gave a deadline, otherwise an empty string). " +
            "Look relative dates up in this table: ${Prompts.calendar(now)}."

    fun parseGoal(raw: String, now: ZonedDateTime = ZonedDateTime.now()): Store.Goal {
        val a = raw.indexOf('{'); val b = raw.lastIndexOf('}')
        require(a in 0 until b) { "no JSON object" }
        val o = JSONObject(raw.substring(a, b + 1))
        val arr = o.optJSONArray("steps") ?: JSONArray()
        val steps = List(arr.length()) { arr.optString(it).trim() }.filter { it.isNotEmpty() }.map { Store.Step(it) }
        require(steps.isNotEmpty()) { "no steps" }
        val due = o.optString("dueDate").trim().let {
            if (it.isEmpty()) 0L else runCatching { EventParser.whenOf(it, now).toInstant().toEpochMilli() }.getOrDefault(0L)
        }
        return Store.Goal(now.toInstant().toEpochMilli(), o.optString("title").trim().ifEmpty { "My goal" }, now.toInstant().toEpochMilli(), due, steps)
    }

}
