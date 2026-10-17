package com.vytros.projects.Quill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CommandsTest {
    @Test
    fun draftmailSplitsFlagsFromText() {
        val p = Commands.parse("/draftmail --receiver sam@acme.com --subject \"Demo moved\" tell him it is Friday at 3pm") {}
        assertNotNull(p)
        assertEquals("draftmail", p!!.cmd.name)
        assertEquals("sam@acme.com", p.flags["receiver"])
        assertEquals("Demo moved", p.flags["subject"])
        assertEquals("tell him it is Friday at 3pm", p.text)
    }

    @Test
    fun toIsReceiverForMailButLanguageForTranslate() {
        assertEquals("a@b.co", Commands.parse("/draftmail --to a@b.co hi") {}!!.flags["receiver"])
        val t = Commands.parse("/translate --to French good morning") {}!!
        assertEquals("French", t.flags["to"])
        assertEquals("good morning", t.text)
        assertEquals("German", Commands.parse("/translate --language German hello") {}!!.flags["to"])
    }

    @Test
    fun unknownCommandIsReported() {
        var seen = ""
        assertNull(Commands.parse("/frobnicate now") { seen = it })
        assertEquals("frobnicate", seen)
    }

    @Test
    fun suggestionsMatchByPrefix() {
        assertEquals(setOf("summarize", "swot", "subject", "shorten"), Commands.matching("s").map { it.name }.toSet())
        assertEquals(listOf("event", "events"), Commands.matching("ev").map { it.name })
        assertEquals(listOf("alarm"), Commands.matching("al").map { it.name } - "actionitems")
    }

    @Test
    fun scheduleCommandsAreRegistered() {
        assertEquals(Commands.Kind.ADDEVENT, Commands.find("event")!!.kind)
        assertEquals(Commands.Kind.ADDEVENT, Commands.find("addevent")!!.kind)
        assertEquals(Commands.Kind.ALARM, Commands.find("alarm")!!.kind)
        assertEquals(Commands.Kind.UPCOMING, Commands.find("upcoming")!!.kind)
        assertEquals(false, Commands.find("upcoming")!!.needsText)
        assertEquals(Commands.Kind.AI, Commands.find("translate")!!.kind)
        val p = Commands.parse("/alarm 6:30am gym") {}!!
        assertEquals("6:30am gym", p.text)
    }
}

class ClockTest {
    @Test
    fun clockNamesTheRealYearAndDay() {
        val now = java.time.ZonedDateTime.of(2026, 10, 17, 12, 40, 0, 0, java.time.ZoneId.of("Asia/Kolkata"))
        val c = Prompts.clock(now)
        org.junit.Assert.assertTrue(c, c.contains("Saturday, 17 October 2026, 12:40"))
        org.junit.Assert.assertTrue(c.contains("The current year is 2026"))
        val cal = Prompts.calendar(now)
        org.junit.Assert.assertTrue(cal, cal.contains("tomorrow = Sunday 2026-10-18"))
        org.junit.Assert.assertTrue(cal.contains("in 7 days = Saturday 2026-10-24"))
    }
}
