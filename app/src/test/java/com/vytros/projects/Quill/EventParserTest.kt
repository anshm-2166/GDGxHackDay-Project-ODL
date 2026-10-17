package com.vytros.projects.Quill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class EventParserTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = ZonedDateTime.of(2026, 10, 17, 12, 40, 0, 0, zone)

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun plainIsoJson() {
        val e = EventParser.parse("""{"title":"Standup","start":"2026-10-18T10:00","durationMinutes":30,"location":"Room 2","remindMinutes":15}""", false, now)
        assertEquals("Standup", e.title)
        assertEquals(at(2026, 10, 18, 10, 0), e.startMs)
        assertEquals(e.startMs + 30 * 60_000, e.endMs)
        assertEquals(15, e.remindMin)
        assertEquals("Room 2", e.location)
    }

    @Test
    fun trainingYearIsMovedToThisYear() {
        val e = EventParser.parse("""{"title":"Call","start":"2025-10-18T16:00"}""", false, now)
        assertEquals(at(2026, 10, 18, 16, 0), e.startMs)
    }

    @Test
    fun toleratesCommonFormatSlips() {
        val want = at(2026, 10, 18, 16, 0)
        listOf(
            """{"title":"x","start":"2026-10-18 16:00"}""",
            """{"title":"x","start":"2026-10-18T16:00:00"}""",
            """{"title":"x","start":"2026-10-18T10:30:00Z"}""", // 16:00 in India
            """```json
{"title":"x","start":"2026-10-18T16:00"}
```""",
            """Sure! {"title":"x","start":"2026-10-18T16:00"} Hope that helps""",
            """{"event":{"title":"x","start":"2026-10-18T16:00"}}""",
        ).forEach { assertEquals(it, want, EventParser.parse(it, false, now).startMs) }
    }

    @Test
    fun dateOnlyBecomesNineAm() {
        assertEquals(at(2026, 10, 20, 9, 0), EventParser.parse("""{"title":"x","start":"2026-10-20"}""", false, now).startMs)
    }

    @Test
    fun reminderHasNoDurationAndRemindsAtStart() {
        val e = EventParser.parse("""{"title":"Pay rent","start":"2026-10-18T09:00","durationMinutes":60,"remindMinutes":10}""", true, now)
        assertEquals(e.startMs, e.endMs)
        assertEquals(0, e.remindMin)
        assertTrue(e.reminder)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyStartIsRejected() {
        EventParser.parse("""{"title":"x","start":""}""", false, now)
    }
}
