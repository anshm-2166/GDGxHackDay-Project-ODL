package com.vytros.projects.Quill

import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime

/** Turns whatever JSON a model produced for /addevent or /remind into an [Store.Event]. Pure, so it can be unit tested. */
object EventParser {
    fun parse(raw: String, reminder: Boolean, now: ZonedDateTime = ZonedDateTime.now(), alarm: Boolean = false): Store.Event {
        val o = unwrap(JSONObject(braces(raw)))
        val start = whenOf(o.getString("start"), now)
        val minutes = o.optInt("durationMinutes", 60).coerceIn(5, 24 * 60)
        val startMs = start.toInstant().toEpochMilli()
        return Store.Event(
            id = now.toInstant().toEpochMilli(), title = o.optString("title").trim().ifEmpty { "Untitled" },
            startMs = startMs, endMs = if (reminder) startMs else startMs + minutes * 60_000L,
            location = o.optString("location").trim(), notes = o.optString("notes").trim(),
            remindMin = if (reminder) 0 else o.optInt("remindMinutes", 10).coerceIn(0, 24 * 60), reminder = reminder, alarm = alarm,
        )
    }

    /** The JSON object inside [raw], ignoring code fences or chatter around it. */
    private fun braces(raw: String): String {
        val a = raw.indexOf('{'); val b = raw.lastIndexOf('}')
        require(a in 0 until b) { "no JSON object" }
        return raw.substring(a, b + 1)
    }

    /** Some models wrap the answer, e.g. {"event": {...}}. */
    private fun unwrap(o: JSONObject): JSONObject {
        if (o.has("start")) return o
        for (k in o.keys()) o.optJSONObject(k)?.let { if (it.has("start")) return it }
        return o
    }

    fun whenOf(text: String, now: ZonedDateTime): ZonedDateTime {
        val t = text.trim()
        require(t.isNotEmpty()) { "no time given" }
        val zone = now.zone
        // With an offset or zone ("...Z", "...+05:30"): convert to this phone's zone.
        runCatching { return ZonedDateTime.parse(t).withZoneSameInstant(zone).fixYear(now) }
        val local = t.replace(' ', 'T')
        runCatching { return LocalDateTime.parse(local).atZone(zone).fixYear(now) }
        // A date with no time becomes 9am.
        runCatching { return LocalDate.parse(local.substringBefore('T')).atTime(9, 0).atZone(zone).fixYear(now) }
        error("unreadable time: $t")
    }

    // Models stuck on their training year say 2025, which is never what someone means.
    private fun ZonedDateTime.fixYear(now: ZonedDateTime) = if (year < now.year) withYear(now.year) else this
}
