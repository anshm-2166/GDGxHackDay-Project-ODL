package com.vytros.projects.Quill

import android.content.Context

/** Slash commands for the Chat tab. AI commands carry an editable system prompt; the rest are handled by the app. */
object Commands {
    enum class Kind { AI, MAIL, REPLY, ADDEVENT, REMIND, ALARM, UPCOMING, GOAL, GOALS, EDIT, EVENTS, NOTE, NOTES, HELP, CLEAR }

    class Cmd(
        val name: String,
        val usage: String,
        val about: String,
        val kind: Kind,
        val prompt: String = "",
        val needsText: Boolean = true,
    )

    private const val PLAIN = " Reply with only the result: no preamble and no closing remarks."

    val ALL = listOf(
        Cmd("draftmail", "/draftmail --receiver <email> [--subject \"...\"] [--tone formal] <what to say>",
            "Write an email, then open it in your mail app", Kind.MAIL,
            "You write business emails on the user's behalf. Turn their instructions into a clear, professional email. " +
                "Format: the first line is exactly 'Subject: <subject>', then a blank line, then the email body with a greeting and a sign-off. " +
                "Never use placeholders such as [Your Name]; end the sign-off with just the closing phrase. Match the requested tone if one is given. " +
                "Write in the language the user's instructions are in unless told otherwise. Output nothing except the email."),
        Cmd("reply", "/reply [--tone friendly] <paste the message, then your instruction>",
            "Draft a reply to a message you received", Kind.REPLY,
            "The user pastes a message they received, optionally followed by an instruction on how to answer. Write the reply they should send. " +
                "Be professional, concise and specific, answer every question asked, and follow any instruction or tone given.$PLAIN"),
        Cmd("summarize", "/summarize <text> (or attach an image)", "Short summary with key points", Kind.AI,
            "Summarize the user's content for a busy professional: a one-sentence headline, then at most five short bullet points covering " +
                "decisions, numbers, dates and owners. If an image is attached, summarize what it shows.$PLAIN"),
        Cmd("explain", "/explain <text or topic> (or attach an image)", "Explain it in plain language", Kind.AI,
            "Explain the user's text, topic or image in plain language a smart non-expert would follow. Use short paragraphs, define jargon, " +
                "and finish with one concrete example."),
        Cmd("proofread", "/proofread <text>", "Fix grammar and spelling, list the changes", Kind.AI,
            "You are a careful proofreader. Correct grammar, spelling, punctuation and word choice without changing the meaning or voice. " +
                "Reply with the corrected text, then a line containing only '---', then a short bullet list of the changes you made. " +
                "If nothing needed fixing, say so in one line."),
        Cmd("rewrite", "/rewrite [--tone formal|friendly|confident|concise] <text>", "Rewrite in a chosen tone", Kind.AI,
            "Rewrite the user's text in the requested tone (polished and professional if none is given). Keep the meaning and every fact.$PLAIN"),
        Cmd("translate", "/translate --to <language> <text>", "Translate (default: English)", Kind.AI,
            "Translate the user's text into the requested language (English if none is given). Keep names, numbers and formatting. " +
                "Hindi or Hinglish input is fine.$PLAIN"),
        Cmd("actionitems", "/actionitems <meeting notes>", "Pull out tasks, owners and deadlines", Kind.AI,
            "Extract every action item from the user's notes as a checklist, one per line, in the form '- [ ] Task (owner, due date)'. " +
                "Write 'unassigned' or 'no date' when missing. Do not invent items.$PLAIN"),
        Cmd("minutes", "/minutes <meeting notes>", "Turn notes into meeting minutes", Kind.AI,
            "Turn the user's raw notes into concise meeting minutes with these headings: Attendees (only if mentioned), Decisions, " +
                "Discussion, Action items. Use short bullets and do not add facts."),
        Cmd("agenda", "/agenda <meeting topic>", "Draft a time-boxed agenda", Kind.AI,
            "Create a practical meeting agenda for the user's topic: objective, then numbered items each with a time box and an owner placeholder " +
                "like 'Owner: TBD', fitting a standard meeting length if none is given."),
        Cmd("subject", "/subject <email text>", "Suggest five subject lines", Kind.AI,
            "Suggest five distinct email subject lines for the user's email, from direct to curious, each under 60 characters. One per line, numbered."),
        Cmd("linkedin", "/linkedin <topic>", "Draft a LinkedIn post", Kind.AI,
            "Write a LinkedIn post for the user's topic: a strong first line, short paragraphs, a concrete takeaway and a light call to action. " +
                "No hashtag spam (at most three) and no emoji walls.$PLAIN"),
        Cmd("swot", "/swot <idea or company>", "SWOT analysis", Kind.AI,
            "Produce a SWOT analysis of the user's idea or company: Strengths, Weaknesses, Opportunities, Threats, three sharp bullets each, " +
                "then a one-line verdict."),
        Cmd("pitch", "/pitch <product or idea>", "30-second elevator pitch", Kind.AI,
            "Write a 30-second elevator pitch (about 80 words) for the user's product or idea: the problem, the solution, who it's for and why now.$PLAIN"),
        Cmd("shorten", "/shorten <text>", "Make it shorter, keep the point", Kind.AI,
            "Shorten the user's text to roughly half its length. Keep every key fact, name, number and date, and the original tone.$PLAIN"),
        Cmd("expand", "/expand <rough points>", "Grow bullet points into full paragraphs", Kind.AI,
            "Expand the user's rough points or sentence fragments into clear, well-structured paragraphs in a professional voice. Do not invent facts.$PLAIN"),
        Cmd("tone", "/tone <text>", "How does this text sound?", Kind.AI,
            "Judge the tone of the user's text. Name the one to three tones it conveys, say how a reader is likely to take it, and suggest one change if it could land better. Three short lines at most."),
        Cmd("define", "/define <term>", "Plain-English definition of a term", Kind.AI,
            "Define the user's term for a smart non-expert: one-sentence definition, one sentence on why it matters, and one example. If it has several meanings in business, give the most common."),
        Cmd("followup", "/followup --receiver <email> <context>", "Draft a follow-up email, then open it in mail", Kind.MAIL,
            "You write follow-up emails on the user's behalf. Using the context they give, write a short, polite, specific follow-up that states what you're following up on, restates any deadline and asks for a clear next step. " +
                "Format: the first line is exactly 'Subject: <subject>', then a blank line, then the body with a greeting and a sign-off. No placeholders such as [Your Name]. Output nothing except the email."),
        Cmd("event", "/event <what, when, where>", "Same as /addevent", Kind.ADDEVENT),
        Cmd("alarm", "/alarm <time and label>", "Set an alarm that rings (like /alarm 6:30am gym)", Kind.ALARM),
        Cmd("upcoming", "/upcoming [question]", "Your upcoming events, alarms and reminders, briefed", Kind.UPCOMING,
            "You are a calm, sharp scheduling assistant. The user shares their upcoming schedule (soonest first, with times already worked out). " +
                "Brief them in a few short lines: what is next and when, anything today, any clashes or back-to-back items, and anything that needs preparation. " +
                "If they ask a specific question, answer that using the schedule. Use the exact times given and never invent entries.", needsText = false),
        Cmd("goal", "/goal <what you want to achieve>", "Create a goal; Quill breaks it into steps you can tick off", Kind.GOAL),
        Cmd("goals", "/goals", "Show your goals and progress", Kind.GOALS, needsText = false),
        Cmd("done", "/done <what you finished>", "Mark a goal step as done", Kind.EDIT),
        Cmd("edit", "/edit <change in plain words>", "Change events, alarms and goals (e.g. /edit move gym alarm to 7am)", Kind.EDIT),
        Cmd("reschedule", "/reschedule <what and to when>", "Move an event, alarm or reminder", Kind.EDIT),
        Cmd("cancel", "/cancel <which event, alarm or goal>", "Delete an event, alarm, reminder or goal", Kind.EDIT),
        Cmd("addevent", "/addevent <what, when, where>", "Add an event to your agenda and calendar, with a reminder", Kind.ADDEVENT),
        Cmd("remind", "/remind <what and when>", "Set a reminder", Kind.REMIND),
        Cmd("events", "/events", "Show your upcoming events", Kind.EVENTS, needsText = false),
        Cmd("note", "/note <text>", "Save a note", Kind.NOTE),
        Cmd("notes", "/notes", "Browse your notes", Kind.NOTES, needsText = false),
        Cmd("clear", "/clear", "Start a new chat", Kind.CLEAR, needsText = false),
        Cmd("help", "/help", "List every command", Kind.HELP, needsText = false),
    )

    fun find(name: String) = ALL.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun matching(prefix: String) = ALL.filter { it.name.startsWith(prefix.lowercase()) }

    /** A command line split into its flags and the free text. */
    class Parsed(val cmd: Cmd, val flags: Map<String, String>, val text: String)

    private val FLAG = Regex("""--(\w+)\s+("[^"]*"|“[^”]*”|\S+)""")
    private val ALIASES = mapOf("to" to "receiver", "email" to "receiver", "mail" to "receiver", "lang" to "to", "language" to "to")

    /** Null if [line] doesn't start with a known command; the unknown word is returned through [unknown]. */
    fun parse(line: String, unknown: (String) -> Unit): Parsed? {
        val body = line.trim().removePrefix("/")
        val name = body.substringBefore(' ').substringBefore('\n')
        val cmd = find(name) ?: run { unknown(name); return null }
        var rest = body.drop(name.length)
        val flags = linkedMapOf<String, String>()
        FLAG.findAll(rest).forEach { m ->
            val key = m.groupValues[1].lowercase()
            // /translate --to is the language; /draftmail --to is the receiver.
            val canon = if (cmd.name == "translate" && key == "to") "to" else ALIASES[key] ?: key
            flags[canon] = m.groupValues[2].trim('"', '“', '”')
        }
        rest = FLAG.replace(rest, "")
        return Parsed(cmd, flags, rest.trim())
    }

    // ---- editable prompts ----

    fun prompt(ctx: Context, cmd: Cmd) = Gemma.prefs(ctx).getString("cmd_${cmd.name}", null)?.takeIf { it.isNotBlank() } ?: cmd.prompt

    fun setPrompt(ctx: Context, cmd: Cmd, text: String?) =
        Gemma.prefs(ctx).edit().apply { if (text == null) remove("cmd_${cmd.name}") else putString("cmd_${cmd.name}", text) }.apply()

    /** The system prompt for a run: the command's prompt plus any flags the user supplied. */
    fun system(ctx: Context, p: Parsed): String {
        val opts = p.flags.filterKeys { it != "receiver" && it != "subject" }.entries.joinToString("; ") { "${it.key}: ${it.value}" }
        val subject = p.flags["subject"]?.let { "Use this subject line: $it. " }.orEmpty()
        return prompt(ctx, p.cmd) + (if (subject.isNotEmpty() || opts.isNotEmpty()) "\n\nOptions: $subject$opts" else "")
    }
}
