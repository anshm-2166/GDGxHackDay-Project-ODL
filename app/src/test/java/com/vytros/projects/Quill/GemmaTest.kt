package com.vytros.projects.Quill

import org.junit.Assert.assertEquals
import org.junit.Test

class GemmaTest {
    @Test
    fun cleanStripsModelWrapping() {
        assertEquals("I am late.", Gemma.clean("<think>hmm</think>\n\"I am late.\""))
        assertEquals("Hi there", Gemma.clean("```\nHi there\n```"))
        assertEquals("He said \"hi\" today", Gemma.clean(" He said \"hi\" today "))
    }

    @Test
    fun locateAndApplyIssues() {
        val text = "Thank you for your time Brad. I'm confidant that my skills would add value to team."
        val raw = listOf(
            Checker.Issue("Add a comma", "time", "time,", -1),
            Checker.Issue("Spelling", "confidant", "confident", -1),
            Checker.Issue("Missing article", "to team", "to the team", -1),
            Checker.Issue("Hallucinated", "not in text", "x", -1),
            Checker.Issue("No-op", "Brad", "Brad", -1),
        )
        val issues = Checker.locate(text, raw)
        assertEquals(listOf(19, 34, 75), issues.map { it.start })
        assertEquals(
            "Thank you for your time, Brad. I'm confident that my skills would add value to the team.",
            Checker.applyAll(text, issues),
        )
        // After one fix, the rest are re-pinned to their shifted positions.
        val after = Checker.applyTo(text, issues[0])
        assertEquals(listOf(35, 76), Checker.locate(after, issues.drop(1)).map { it.start })
    }
}

class DiffTest {
    private fun check(text: String, fixed: String) = Checker.diff(text, fixed).also {
        assertEquals("applying every issue must yield the fix", fixed, Checker.applyAll(text, it))
    }

    @Test
    fun grammarSpellingPunctuationArticle() {
        val text = "Thank you for your time Brad. Me iz confidant that my skills would add value to team"
        val fixed = "Thank you for your time, Brad. I am confident that my skills would add value to the team."
        val issues = check(text, fixed)
        assertEquals(listOf("Punctuation", "Grammar", "Missing article"), issues.map { it.type })
        // Adjacent changes merge into one card.
        assertEquals("Me iz confidant" to "I am confident", issues[1].original to issues[1].replacement)
        assertEquals("Spelling", Checker.diff("I iz here", "I is here").single().type)
        assertEquals("Grammar", Checker.diff("Me think so", "I think so").single().type)
        assertEquals("Spelling", Checker.diff("we discus it", "we discuss it").single().type)
    }

    @Test
    fun deletionsAndEdges() {
        check("He is is here", "He is here")
        check("going to market", "Going to the market")
        check("the", "")
        assertEquals(0, Checker.diff("All good here.", "All good here.").size)
    }
}

class MergeTest {
    @Test
    fun correctnessWinsOverlapsAndOrderIsByPosition() {
        val style = Checker.Issue("Sound more confident", "I think maybe we", "We", 0, Checker.Category.DELIVERY)
        val fix = Checker.Issue("Spelling", "maybe", "may be", 8)
        val clarity = Checker.Issue("Remove wordiness", "in order to", "to", 20, Checker.Category.CLARITY)
        val merged = Checker.merge(listOf(style, clarity, fix))
        assertEquals(listOf(fix, clarity), merged)
    }
}
