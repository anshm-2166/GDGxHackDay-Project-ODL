package com.vytros.projects.Quill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class OrganizerTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = ZonedDateTime.of(2026, 10, 17, 12, 40, 0, 0, zone)
    private fun ms(d: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 10, d, h, m, 0, 0, zone).toInstant().toEpochMilli()

    private val gym = Store.Event(1, "Gym", ms(18, 6, 30), ms(18, 6, 30), "", "", 0, reminder = true, alarm = true, viaClock = true)
    private val standup = Store.Event(2, "Standup", ms(18, 10, 0), ms(18, 10, 30), "Room 2", "", 10, reminder = false)
    private val goal = Store.Goal(9, "Launch site", 0, 0, listOf(Store.Step("Buy domain", done = true), Store.Step("Draft copy"), Store.Step("Publish")))

    @Test
    fun snapshotGivesShortRefsAndShowsState() {
        val s = Organizer.snapshot(listOf(gym, standup), listOf(goal), now)
        assertEquals(setOf("E1", "E2"), s.events.keys)
        assertEquals("Gym", s.events.getValue("E1").title)
        assertEquals(setOf("G1"), s.goals.keys)
        assertTrue(s.text, s.text.contains("E1: [alarm] Gym: 2026-10-18T06:30 (Sunday)"))
        assertTrue(s.text, s.text.contains("E2: [event] Standup: 2026-10-18T10:00 (Sunday), 30 min, at Room 2"))
        assertTrue(s.text, s.text.contains("G1: Launch site (1 of 3 done)"))
        assertTrue(s.text, s.text.contains("G1.1 [done] Buy domain"))
        assertTrue(s.text, s.text.contains("G1.2 [open] Draft copy"))
    }

    @Test
    fun emptySnapshotIsEmpty() {
        assertTrue(Organizer.snapshot(emptyList(), emptyList(), now).isEmpty)
    }

    @Test
    fun parsesOperationsFromTheModel() {
        val p = Organizer.parse(
            """```json
{"ops":[{"op":"update_event","ref":"e1","start":"2026-10-18T07:00"},
{"op":"mark_step","ref":"G1.2","done":true},{"op":"add_step","ref":"G1","text":"Tell friends"}],"reply":"ok"}
```""",
        )
        assertEquals(3, p.ops.size)
        assertEquals("E1", p.ops[0].ref) // refs are normalised to upper case
        assertEquals("2026-10-18T07:00", p.ops[0].start)
        assertEquals(true, p.ops[1].done)
        assertEquals(null, p.ops[0].done)
        assertEquals("Tell friends", p.ops[2].text)
        assertEquals("ok", p.reply)
    }

    @Test
    fun emptyOpsKeepTheReply() {
        val p = Organizer.parse("""{"ops":[],"reply":"Nothing matches that."}""")
        assertTrue(p.ops.isEmpty())
        assertEquals("Nothing matches that.", p.reply)
    }

    @Test
    fun goalProgress() {
        assertEquals(1, goal.doneCount)
        assertEquals(33, goal.percent)
        assertFalse(goal.complete)
        assertTrue(goal.copy(steps = goal.steps.map { it.copy(done = true) }).complete)
        assertEquals(0, goal.copy(steps = emptyList()).percent)
    }

    @Test
    fun parsesAGoalFromTheModel() {
        val g = Organizer.parseGoal(
            """{"title":"Launch portfolio","steps":["Pick a template"," Write bio ",""],"dueDate":"2026-11-30"}""", now,
        )
        assertEquals("Launch portfolio", g.title)
        assertEquals(listOf("Pick a template", "Write bio"), g.steps.map { it.text })
        assertTrue(g.steps.none { it.done })
        assertEquals(ms(0 + 30, 0, 0).let { ZonedDateTime.of(2026, 11, 30, 9, 0, 0, 0, zone).toInstant().toEpochMilli() }, g.dueMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aGoalNeedsSteps() {
        Organizer.parseGoal("""{"title":"x","steps":[]}""", now)
    }

    @Test
    fun onlyEditSoundingMessagesAreWorthAModelCall() {
        assertTrue(Organizer.worthTrying("move my gym alarm to 7am"))
        assertTrue(Organizer.worthTrying("I finished the outline"))
        assertTrue(Organizer.worthTrying("Cancel the dentist"))
        assertFalse(Organizer.worthTrying("what is the capital of France"))
        assertFalse(Organizer.worthTrying("move ".repeat(80)))
    }
}
