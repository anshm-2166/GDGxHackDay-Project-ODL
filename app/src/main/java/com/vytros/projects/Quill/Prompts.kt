package com.vytros.projects.Quill

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Every system prompt in the app, editable from Settings. Built-ins fall back to their defaults on reset. */
object Prompts {
    const val FIX = "Fix all"
    const val TONE = "Tone"

    private const val ONLY_TEXT = " Reply with only the resulting text: no quotes, no preamble, no explanation."

    val DEFAULT_TOOLS = linkedMapOf(
        FIX to "You are a careful proofreader. Correct every grammar, spelling, punctuation, capitalization and word-choice " +
            "mistake in the user's text, including wrong pronouns and verb forms (for example 'Me is' becomes 'I am'). " +
            "Change as little as possible and keep the meaning and tone. If the text is Hindi or Hinglish, keep it in that " +
            "language and only fix clear typos; never translate. If it is already correct, return it unchanged.$ONLY_TEXT",
        TONE to "Detect the tone of the user's text. Reply with one short plain sentence: name 1 to 3 tones, then give one short tip. No emoji, no dashes. Example: Sounds neutral and a little blunt. Add a greeting to sound warmer.",
        "Formal" to "Rewrite the user's text in a polite, professional tone.$ONLY_TEXT",
        "Friendly" to "Rewrite the user's text in a warm, friendly tone.$ONLY_TEXT",
        "Shorter" to "Make the user's text more concise while keeping its meaning.$ONLY_TEXT",
        "Clearer" to "Rewrite the user's text so it is clearer and easier to read.$ONLY_TEXT",
        "To English" to "Translate the user's text (it may be Hindi or Hinglish) into natural English.$ONLY_TEXT",
        "Compose" to "The user's text is an instruction describing a message. Write that message.$ONLY_TEXT",
    )

    const val DEFAULT_STYLE = "You are a writing coach. Suggest at most 3 improvements to the user's text. Ignore spelling, grammar and " +
        "punctuation mistakes; they are handled separately. Categories: clarity = make a wordy or confusing phrase concise and clear; " +
        "engagement = replace a bland or overused word with a more vivid, precise one; delivery = adjust tone, for example remove " +
        "hedging to sound more confident ('I think maybe' becomes 'I believe'), or make it more polite or formal. " +
        "label = a short action such as 'Sound more confident', 'Remove wordiness', 'Choose a stronger word', 'Make it more polite'. " +
        "original = an exact snippet copied verbatim from the text; replacement = the improved snippet. " +
        "For casual chat or Hindi/Hinglish text, only suggest something if it clearly helps. Return {\"suggestions\":[]} if nothing."

    const val DEFAULT_CHAT = "You are Quill, a warm, thoughtful assistant who loves good writing. Answer clearly and concisely. " +
        "Match the user's language, including Hindi or Hinglish."

    /** Models don't know today's date and fall back on their training year, so every request states it. */
    fun clock(now: ZonedDateTime = ZonedDateTime.now()): String {
        val f = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm", Locale.ENGLISH)
        return "Current date and time: ${now.format(f)} (time zone ${now.zone}, UTC${now.offset}). This is the real present: " +
            "use it for \"today\", \"now\" and every year, and never assume an earlier year from your training. The current year is ${now.year}."
    }

    /** The next fortnight spelled out, so relative days like "next Friday" resolve by lookup, not arithmetic. */
    fun calendar(now: ZonedDateTime = ZonedDateTime.now()): String {
        val f = DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd", Locale.ENGLISH)
        return (0..14).joinToString("; ") { d ->
            val label = when (d) { 0 -> "today"; 1 -> "tomorrow"; else -> "in $d days" }
            "$label = ${now.plusDays(d.toLong()).format(f)}"
        }
    }

    fun withClock(system: String) = "$system\n\n${clock()}"

    data class Tool(val name: String, val prompt: String, val builtin: Boolean)

    private const val KEY_TOOLS = "tools"
    private const val KEY_STYLE = "style_prompt"
    private const val KEY_CHAT = "chat_prompt"

    fun tools(ctx: Context): List<Tool> {
        val raw = Gemma.prefs(ctx).getString(KEY_TOOLS, null)
        val saved = runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { arr.getJSONObject(it).let { o -> Tool(o.getString("name"), o.getString("prompt"), o.optBoolean("builtin")) } }
        }.getOrDefault(emptyList())
        // Always show every built-in, in default order, with any saved edit applied; customs follow.
        val builtins = DEFAULT_TOOLS.map { (n, p) -> Tool(n, saved.firstOrNull { it.name == n && it.builtin }?.prompt ?: p, true) }
        return builtins + saved.filter { !it.builtin }
    }

    fun prompt(ctx: Context, name: String) = tools(ctx).firstOrNull { it.name == name }?.prompt ?: DEFAULT_TOOLS.getValue(FIX)

    /** Inserts or replaces the tool called [oldName] (null = add new). */
    fun save(ctx: Context, oldName: String?, tool: Tool) {
        val all = tools(ctx).toMutableList()
        val at = all.indexOfFirst { it.name == oldName }
        if (at >= 0) all[at] = tool else all += tool
        store(ctx, all)
    }

    fun delete(ctx: Context, name: String) = store(ctx, tools(ctx).filter { it.builtin || it.name != name })

    fun exists(ctx: Context, name: String) = tools(ctx).any { it.name.equals(name, ignoreCase = true) }

    private fun store(ctx: Context, all: List<Tool>) {
        val arr = JSONArray()
        all.forEach { arr.put(JSONObject().put("name", it.name).put("prompt", it.prompt).put("builtin", it.builtin)) }
        Gemma.prefs(ctx).edit().putString(KEY_TOOLS, arr.toString()).apply()
    }

    fun style(ctx: Context) = Gemma.prefs(ctx).getString(KEY_STYLE, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_STYLE
    fun setStyle(ctx: Context, v: String?) = Gemma.prefs(ctx).edit().apply { if (v == null) remove(KEY_STYLE) else putString(KEY_STYLE, v) }.apply()

    fun chat(ctx: Context) = Gemma.prefs(ctx).getString(KEY_CHAT, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_CHAT
    fun setChat(ctx: Context, v: String?) = Gemma.prefs(ctx).edit().apply { if (v == null) remove(KEY_CHAT) else putString(KEY_CHAT, v) }.apply()
}
