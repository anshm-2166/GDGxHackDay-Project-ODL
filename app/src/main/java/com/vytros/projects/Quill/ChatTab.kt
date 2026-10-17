package com.vytros.projects.Quill

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Patterns
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

/** Conversation with the model: plain chat, slash commands, image and voice input, streamed as it is written. */
class ChatTab(private val act: MainActivity, root: View) {
    private class Action(val label: String, val run: () -> Unit)

    private class Msg(
        val user: Boolean, var text: String, var tps: Double? = null,
        val image: Bitmap? = null, var actions: List<Action> = emptyList(),
    )

    private val messages = mutableListOf<Msg>()
    private val list = root.findViewById<RecyclerView>(R.id.messages)
    private val empty = root.findViewById<View>(R.id.chatEmpty)
    private val input = root.findViewById<android.widget.EditText>(R.id.chatInput)
    private val send = root.findViewById<ImageView>(R.id.chatSend)
    private val modelLabel = root.findViewById<TextView>(R.id.chatModel)
    private val cmdPanel = root.findViewById<LinearLayout>(R.id.cmdPanel)
    private val attachRow = root.findViewById<View>(R.id.attachRow)
    private val attachThumb = root.findViewById<ImageView>(R.id.attachThumb)
    private var handle: Gemma.Handle? = null
    private var asking = false
    private var imageBytes: ByteArray? = null
    private var imageThumb: Bitmap? = null

    private val busy get() = handle != null || asking

    private val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = messages.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            object : RecyclerView.ViewHolder(LayoutInflater.from(p.context).inflate(R.layout.item_msg, p, false)) {}

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            val m = messages[i]
            val v = h.itemView
            val g = if (m.user) Gravity.END else Gravity.START
            val b = v.findViewById<TextView>(R.id.bubble)
            b.text = m.text.ifEmpty { "…" }
            b.setBackgroundResource(if (m.user) R.drawable.bg_bubble_user else R.drawable.bg_bubble_bot)
            b.setTextColor(act.getColor(if (m.user) R.color.on_brand else R.color.text_primary))
            b.setTextIsSelectable(m.text.isNotEmpty())
            (b.layoutParams as LinearLayout.LayoutParams).gravity = g
            v.findViewById<ImageView>(R.id.msgImage).apply {
                visibility = if (m.image != null) View.VISIBLE else View.GONE
                setImageBitmap(m.image)
                (layoutParams as LinearLayout.LayoutParams).gravity = g
            }
            v.findViewById<TextView>(R.id.stats).apply {
                (layoutParams as LinearLayout.LayoutParams).gravity = g
                visibility = if (m.tps != null) View.VISIBLE else View.GONE
                text = m.tps?.let { "%.1f tok/s".format(it) }.orEmpty()
            }
            val scroll = v.findViewById<View>(R.id.actionsScroll)
            val row = v.findViewById<LinearLayout>(R.id.msgActions)
            row.removeAllViews()
            scroll.visibility = if (m.actions.isEmpty()) View.GONE else View.VISIBLE
            (scroll.layoutParams as LinearLayout.LayoutParams).gravity = g
            m.actions.forEach { a -> row.addView(chip(a.label) { a.run() }) }
        }
    }

    private fun chip(label: String, onClick: () -> Unit) = TextView(act).apply {
        text = label
        textSize = 13f
        typeface = ResourcesCompat.getFont(act, R.font.dmsans_medium)
        setTextColor(act.getColor(R.color.brand))
        setBackgroundResource(R.drawable.bg_chip)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(act.dp(14), 0, act.dp(14), 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, act.dp(34)).apply { marginEnd = act.dp(8) }
        setOnClickListener { onClick() }
    }

    init {
        list.layoutManager = LinearLayoutManager(act).apply { stackFromEnd = true }
        list.adapter = adapter
        list.itemAnimator = null
        load()
        root.findViewById<View>(R.id.newChat).setOnClickListener { newChat() }
        send.setOnClickListener { if (handle != null) { handle?.cancel(); finish(); save() } else submit() }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = suggest(s?.toString().orEmpty())
        })

        root.findViewById<View>(R.id.chatImage).setOnClickListener {
            act.pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        root.findViewById<View>(R.id.attachRemove).setOnClickListener { clearImage() }
        act.onImage = { uri -> attach(uri) }

        root.findViewById<View>(R.id.chatMic).setOnClickListener {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Quill")
            try { act.speech.launch(i) } catch (e: ActivityNotFoundException) { toast("No speech recognizer on this phone") }
        }
        act.onSpeech = { text ->
            val cur = input.text.toString()
            input.setText(if (cur.isBlank()) text else "$cur $text")
            input.setSelection(input.text.length)
        }
        changed()
    }

    fun refreshModel() { modelLabel.text = if (Local.enabled(act)) Local.LABEL else Gemma.chatModel(act) }

    private fun toast(s: String) = Toast.makeText(act, s, Toast.LENGTH_SHORT).show()

    // ---- slash command suggestions ----

    private fun suggest(typed: String) {
        cmdPanel.removeAllViews()
        if (!typed.startsWith("/")) { cmdPanel.visibility = View.GONE; return }
        val word = typed.drop(1).substringBefore(' ')
        if (typed.contains(' ') || typed.contains('\n')) {
            // Past the command name: remind the user of its shape.
            val c = Commands.find(word)
            if (c == null) { cmdPanel.visibility = View.GONE; return }
            cmdPanel.addView(hint(c.usage))
            cmdPanel.visibility = View.VISIBLE
            return
        }
        val matches = Commands.matching(word)
        if (matches.isEmpty()) { cmdPanel.visibility = View.GONE; return }
        matches.take(6).forEach { c ->
            cmdPanel.addView(TextView(act).apply {
                val s = SpannableStringBuilder("/${c.name}   ${c.about}")
                s.setSpan(ForegroundColorSpan(act.getColor(R.color.brand)), 0, c.name.length + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                s.setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, c.name.length + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text = s
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(act.getColor(R.color.text_secondary))
                setBackgroundResource(R.drawable.bg_row)
                setPadding(act.dp(16), act.dp(10), act.dp(16), act.dp(10))
                setOnClickListener { input.setText("/${c.name} "); input.setSelection(input.text.length) }
            })
        }
        if (matches.size > 6) cmdPanel.addView(hint("+${matches.size - 6} more. Keep typing, or send /help."))
        cmdPanel.visibility = View.VISIBLE
    }

    private fun hint(s: String) = TextView(act).apply {
        text = s
        textSize = 12.5f
        setTextColor(act.getColor(R.color.text_hint))
        setPadding(act.dp(16), act.dp(8), act.dp(16), act.dp(8))
    }

    // ---- image attachment ----

    private fun attach(uri: Uri) = thread {
        val prepared = runCatching {
            val bmp = if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(act.contentResolver, uri)) { d, info, _ ->
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val big = maxOf(info.size.width, info.size.height)
                    if (big > MAX_SIDE) d.setTargetSize(info.size.width * MAX_SIDE / big, info.size.height * MAX_SIDE / big)
                }
            } else {
                act.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
            }
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray() to bmp
        }
        act.runOnUiThread {
            prepared.onSuccess { (bytes, bmp) ->
                imageBytes = bytes; imageThumb = bmp
                attachThumb.setImageBitmap(bmp)
                attachRow.visibility = View.VISIBLE
            }.onFailure { toast("Couldn't open that image") }
        }
    }

    private fun clearImage() {
        imageBytes = null; imageThumb = null
        attachRow.visibility = View.GONE
    }

    // ---- sending ----

    private fun submit() {
        if (busy) return
        val t = input.text.toString().trim()
        if (t.isEmpty() && imageBytes == null) return
        input.setText("")
        if (t.startsWith("/")) command(t) else chat(t.ifEmpty { "What is in this image?" })
    }

    private fun chat(t: String) {
        val img = imageBytes
        messages += Msg(true, t, image = imageThumb)
        clearImage()
        // "move my gym alarm to 7", "I finished the outline": let the organizer try first, then fall back to plain chat.
        if (img == null && Organizer.worthTrying(t) && !Organizer.snapshot(act).isEmpty) { edit(t, soft = true); return }
        respond(img)
    }

    private fun respond(img: ByteArray?) {
        val reply = Msg(false, "")
        messages += reply
        setStreaming(true)
        changed()
        val history = messages.dropLast(1).map { (if (it.user) "user" else "assistant") to it.text }.filter { it.second.isNotEmpty() }
        stream(reply, history, null, img)
    }

    /** Streams into [reply]; [done] runs once it has finished without error. */
    private fun stream(reply: Msg, history: List<Pair<String, String>>, system: String?, image: ByteArray?, done: (String) -> Unit = {}) {
        handle = Gemma.converse(act, history, system, image, onDelta = { piece ->
            reply.text += piece
            adapter.notifyItemChanged(messages.indexOf(reply))
            list.scrollToPosition(messages.size - 1)
        }) { err, tps ->
            reply.tps = tps
            if (err != null && reply.text.isEmpty()) reply.text = "Can't reach the model: ${err.message ?: "unknown error"}. Check Settings."
            else if (err != null) reply.text += "\n\n(Interrupted: ${err.message})"
            finish()
            save()
            changed()
            if (err == null && reply.text.isNotBlank()) done(reply.text)
        }
    }

    private fun finish() {
        handle = null
        setStreaming(false)
    }

    private fun setStreaming(on: Boolean) {
        send.setImageResource(if (on) R.drawable.ic_stop else R.drawable.ic_send)
        send.contentDescription = if (on) "Stop" else "Send"
    }

    // ---- commands ----

    private fun bot(text: String, actions: List<Action> = emptyList()): Msg {
        val m = Msg(false, text, actions = actions)
        messages += m
        save()
        changed()
        return m
    }

    private fun command(line: String) {
        val img = imageBytes
        val thumb = imageThumb
        var unknown: String? = null
        val p = Commands.parse(line) { unknown = it }
        messages += Msg(true, line, image = thumb)
        clearImage()
        if (p == null) { bot("I don't know /$unknown. Send /help to see every command."); return }
        val c = p.cmd
        if (c.needsText && p.text.isEmpty() && !(img != null && c.kind == Commands.Kind.AI)) {
            bot("Usage: ${c.usage}"); return
        }
        when (c.kind) {
            Commands.Kind.HELP -> bot(Commands.ALL.joinToString("\n") { "/${it.name} — ${it.about}" } +
                "\n\nFlags go before your text, for example:\n/draftmail --receiver sam@acme.com tell him the demo moved to Friday 3pm")
            Commands.Kind.CLEAR -> newChat()
            Commands.Kind.NOTE -> {
                Store.addNote(act, p.text)
                bot("Saved to your notes.", listOf(Action("View notes") { act.showDesk(DeskTab.Section.NOTES) }))
            }
            Commands.Kind.NOTES -> { bot("Opening your notes."); act.showDesk(DeskTab.Section.NOTES) }
            Commands.Kind.EVENTS -> { bot("${Store.upcoming(act).size} upcoming. Opening your desk."); act.showDesk(DeskTab.Section.UPCOMING) }
            Commands.Kind.ADDEVENT -> schedule(p.text, Mode.EVENT)
            Commands.Kind.REMIND -> schedule(p.text, Mode.REMINDER)
            Commands.Kind.ALARM -> schedule(p.text, Mode.ALARM)
            Commands.Kind.UPCOMING -> upcoming(p)
            Commands.Kind.GOAL -> newGoal(p.text)
            Commands.Kind.GOALS -> showGoals()
            Commands.Kind.EDIT -> edit(editText(p), soft = false)
            Commands.Kind.MAIL -> mail(p, img)
            else -> generate(p, img)
        }
    }

    private fun userText(p: Commands.Parsed) = p.text.ifEmpty { "Use the attached image." }

    private fun generate(p: Commands.Parsed, img: ByteArray?) {
        val reply = bot("")
        setStreaming(true)
        stream(reply, listOf("user" to userText(p)), Commands.system(act, p), img) { out ->
            reply.actions = buildList {
                add(Action("Copy") { copy(out) })
                if (p.cmd.kind == Commands.Kind.REPLY) add(Action("Open in mail") { val (s, b) = splitMail(out, ""); openMail(null, s, b) })
                add(Action("Save as note") { Store.addNote(act, out); toast("Saved to notes") })
            }
            changed()
        }
    }

    private fun mail(p: Commands.Parsed, img: ByteArray?) {
        val to = p.flags["receiver"]
        if (to != null && !Patterns.EMAIL_ADDRESS.matcher(to).matches()) {
            bot("\"$to\" doesn't look like an email address.\nUsage: ${p.cmd.usage}"); return
        }
        val reply = bot("")
        setStreaming(true)
        stream(reply, listOf("user" to userText(p)), Commands.system(act, p), img) { out ->
            val (subject, body) = splitMail(out, p.flags["subject"].orEmpty())
            reply.actions = listOf(
                Action("Open in mail") { openMail(to, subject, body) },
                Action("Copy") { copy(out) },
            )
            changed()
            openMail(to, subject, body) // the mail app opens with everything prefilled
        }
    }

    /** Splits "Subject: ...\n\nbody" into its parts; falls back to [fallbackSubject] and the whole text. */
    private fun splitMail(text: String, fallbackSubject: String): Pair<String, String> {
        val lines = text.trim().lines()
        val first = lines.firstOrNull { it.isNotBlank() }.orEmpty()
        if (!first.startsWith("Subject:", ignoreCase = true)) return fallbackSubject to text.trim()
        val body = lines.dropWhile { it.isBlank() }.drop(1).joinToString("\n").trim()
        return first.substringAfter(':').trim().ifEmpty { fallbackSubject } to body
    }

    private fun openMail(to: String?, subject: String, body: String) {
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, body)
        if (!to.isNullOrBlank()) i.putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
        try { act.startActivity(i) } catch (e: ActivityNotFoundException) { toast("No email app found") }
    }

    private fun copy(text: String) {
        act.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Quill", text))
        toast("Copied")
    }

    // ---- events and reminders ----

    private val schema = JSONObject(
        """{"type":"object","properties":{"title":{"type":"string"},"start":{"type":"string"},"durationMinutes":{"type":"integer"},
        "location":{"type":"string"},"remindMinutes":{"type":"integer"},"notes":{"type":"string"}},"required":["title","start"]}""",
    )

    private enum class Mode(val command: String) { EVENT("addevent"), REMINDER("remind"), ALARM("alarm") }

    private fun schedule(text: String, mode: Mode) {
        val now = ZonedDateTime.now()
        val system = "You extract calendar details from the user's message. The current local time is " +
            "${now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))}. Look relative days up in this table: ${Prompts.calendar(now)}. " +
            "For a weekday name like 'Friday' use the next such date in the table; for a time like '4pm' use 16:00; " +
            "if only a clock time is given, use today if it is still ahead of the current time, otherwise tomorrow; " +
            "for 'in 2 hours' add to the current time. Every date must use the year from the table. Reply with one JSON object: " +
            "title (short; for alarms the label, e.g. 'Gym'), start (local time formatted yyyy-MM-dd'T'HH:mm, or an empty string if the message gives no time), " +
            "durationMinutes (60 if not stated), location (empty if none), remindMinutes (how many minutes before to remind; 10 if not stated), notes (empty if none)."
        val pending = bot("")
        asking = true
        Gemma.ask(act, system, text, schema = schema) { r, tps ->
            asking = false
            pending.tps = tps
            val e = r.mapCatching { EventParser.parse(it, reminder = mode != Mode.EVENT, alarm = mode == Mode.ALARM) }
            e.onFailure { why ->
                val said = r.getOrNull()?.take(160)?.let { "\n\nThe model said: $it" } ?: "\n\n(${r.exceptionOrNull()?.message ?: why.message})"
                pending.text = "I couldn't read a time from that. Try: /${mode.command} ${if (mode == Mode.ALARM) "6:30am gym" else "Call Priya tomorrow at 4pm"}$said"
            }
            e.onSuccess { ev ->
                when {
                    ev.startMs < System.currentTimeMillis() -> pending.text = "That time (${Reminders.whenText(ev.startMs)}) has already passed."
                    else -> saveEvent(pending, ev)
                }
            }
            save()
            changed()
        }
    }

    /** Asks the Clock app for a real alarm at the same time of day. It only knows hours and minutes, so it covers the next 24 hours. */
    private fun setClockAlarm(e: Store.Event): Boolean {
        if (e.startMs - System.currentTimeMillis() > 24 * 60 * 60_000L) return false
        val t = java.time.Instant.ofEpochMilli(e.startMs).atZone(ZoneId.systemDefault())
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, t.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, t.minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, e.title)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return try { act.startActivity(i); true } catch (x: Exception) { false } // no clock app, or it refused
    }

    private fun saveEvent(m: Msg, planned: Store.Event) {
        act.ensureNotifications()
        val e = if (planned.alarm) planned.copy(viaClock = setClockAlarm(planned)) else planned
        Store.addEvent(act, e)
        val exact = Reminders.canBeExact(act)
        m.text = buildString {
            append(if (e.alarm) "Alarm set: " else if (e.reminder) "Reminder set: " else "Added: ").append(e.title).append("\n")
            append(Reminders.whenText(e.startMs))
            if (e.location.isNotBlank()) append(" · ").append(e.location)
            if (!e.reminder) append("\nReminder ${if (e.remindMin == 0) "at the start" else "${e.remindMin} min before"}.")
            if (e.viaClock) append("\nIt's in your Clock app and will ring there.")
            else if (e.alarm) append("\nThat's more than 24 hours away, which the Clock app can't take, so Quill will notify you instead.")
            if (!e.viaClock && !exact) append("\nExact alarms are off, so Android may deliver this a few minutes late.")
        }
        m.actions = buildList {
            if (!e.reminder) add(Action("Add to Calendar") { insertCalendar(e) })
            add(Action("View desk") { act.showDesk(DeskTab.Section.UPCOMING) })
            if (!e.viaClock && !exact && Build.VERSION.SDK_INT >= 31) add(Action("Allow exact alarms") {
                act.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${act.packageName}")))
            })
            add(Action("Undo") {
                Store.removeEvent(act, e.id)
                m.text = "Removed \"${e.title}\"." + if (e.viaClock) " Delete the alarm in your Clock app too." else ""
                m.actions = emptyList(); changed()
            })
        }
    }

    /** /upcoming: hands the real schedule to the model so it can brief the user. */
    private fun upcoming(p: Commands.Parsed) {
        val items = Store.upcoming(act)
        val open = Action("Open desk") { act.showDesk(DeskTab.Section.UPCOMING) }
        if (items.isEmpty()) { bot("Nothing upcoming. Add something with /addevent, /alarm or /remind.", listOf(open)); return }
        val now = System.currentTimeMillis()
        val agenda = items.take(30).joinToString("\n") { e ->
            val kind = if (e.alarm) "alarm" else if (e.reminder) "reminder" else "event"
            "- [$kind] ${e.title}: ${Reminders.whenText(e.startMs)} (${until(e.startMs - now)})" + if (e.location.isNotBlank()) ", at ${e.location}" else ""
        }
        val ask = p.text.ifEmpty { "Brief me on what's coming up." }
        val reply = bot("")
        setStreaming(true)
        stream(reply, listOf("user" to "My upcoming schedule, soonest first:\n$agenda\n\n$ask"), Commands.system(act, p), null) {
            reply.actions = listOf(open)
            changed()
        }
    }

    private fun until(ms: Long): String {
        val min = (ms / 60_000).coerceAtLeast(0)
        return when {
            min < 60 -> "in $min min"
            min < 24 * 60 -> "in ${min / 60} h ${min % 60} min"
            else -> "in ${min / (24 * 60)} days"
        }
    }

    private fun insertCalendar(e: Store.Event) {
        val i = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, e.title)
            .putExtra(CalendarContract.Events.EVENT_LOCATION, e.location)
            .putExtra(CalendarContract.Events.DESCRIPTION, e.notes)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.startMs)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.endMs)
        try { act.startActivity(i) } catch (x: ActivityNotFoundException) { toast("No calendar app found") }
    }

    // ---- goals and plain-language edits ----

    private fun editText(p: Commands.Parsed) = when (p.cmd.name) {
        "done" -> "I finished: ${p.text}"
        "cancel" -> "Cancel or delete: ${p.text}"
        "reschedule" -> "Reschedule: ${p.text}"
        else -> p.text
    }

    /**
     * Lets the model change events, alarms and goals from [instruction]. With [soft] (an ordinary chat message that
     * merely sounded like an edit) it falls back to a normal reply when there is nothing to change.
     */
    private fun edit(instruction: String, soft: Boolean) {
        val now = ZonedDateTime.now()
        val snap = Organizer.snapshot(act, now)
        if (snap.isEmpty) { bot("You have no events, alarms or goals yet. Try /addevent, /alarm or /goal."); return }
        val pending = bot("")
        asking = true
        Gemma.ask(act, Organizer.system(snap, now), instruction, schema = Organizer.SCHEMA) { r, tps ->
            asking = false
            val parsed = r.mapCatching { Organizer.parse(it) }
            val ops = parsed.getOrNull()?.ops.orEmpty()
            when {
                ops.isNotEmpty() -> {
                    pending.tps = tps
                    pending.text = Organizer.apply(act, snap, ops, now) { setClockAlarm(it) }.joinToString("\n")
                    pending.actions = listOf(Action("View desk") { act.showDesk(DeskTab.Section.UPCOMING) })
                }
                soft -> { messages.remove(pending); respond(null); return@ask }
                else -> pending.text = parsed.getOrNull()?.reply?.takeIf { it.isNotEmpty() }
                    ?: "I couldn't tell what to change. Try: /edit move my gym alarm to 7am"
            }
            save()
            changed()
        }
    }

    private fun newGoal(text: String) {
        val now = ZonedDateTime.now()
        val pending = bot("")
        asking = true
        Gemma.ask(act, Organizer.goalSystem(now), text, schema = Organizer.GOAL_SCHEMA) { r, tps ->
            asking = false
            pending.tps = tps
            val g = r.mapCatching { Organizer.parseGoal(it, now) }
            g.onSuccess { Store.addGoal(act, it); showGoal(pending, it.id) }
            g.onFailure {
                val said = r.getOrNull()?.take(160)?.let { s -> "\n\nThe model said: $s" }.orEmpty()
                pending.text = "I couldn't turn that into a goal. Try: /goal Launch my portfolio site by 30 November$said"
            }
            save()
            changed()
        }
    }

    private fun goalText(g: Store.Goal) = buildString {
        append("Goal: ").append(g.title).append("\n")
        append("${g.doneCount} of ${g.steps.size} done · ${g.percent}%")
        if (g.dueMs > 0) append(" · due ").append(java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(g.dueMs)))
        if (g.complete) append(" · complete")
        append("\n")
        g.steps.forEachIndexed { i, s -> append("\n").append(if (s.done) "✓ " else "○ ").append("${i + 1}. ").append(s.text) }
    }

    /** Draws goal [id] into [m] with a "Done n" button for every open step. */
    private fun showGoal(m: Msg, id: Long) {
        val g = Store.goal(act, id)
        if (g == null) { m.text = "That goal was deleted."; m.actions = emptyList(); changed(); return }
        m.text = goalText(g)
        m.actions = buildList {
            g.steps.forEachIndexed { i, s ->
                if (!s.done) add(Action("Done ${i + 1}") { Store.setStep(act, id, i, true); showGoal(m, id); save() })
            }
            add(Action("Open goals") { act.showDesk(DeskTab.Section.GOALS) })
        }
        changed()
    }

    private fun showGoals() {
        val goals = Store.goals(act)
        if (goals.isEmpty()) { bot("No goals yet. Try /goal Launch my portfolio site by 30 November."); return }
        bot(goals.joinToString("\n") { "${it.title}: ${it.doneCount} of ${it.steps.size} (${it.percent}%)" },
            goals.take(5).map { g -> Action(g.title.take(22)) { showGoal(bot(""), g.id) } } +
                Action("Open goals") { act.showDesk(DeskTab.Section.GOALS) })
    }

    // ---- state ----

    private fun newChat() {
        handle?.cancel()
        finish()
        asking = false
        messages.clear()
        clearImage()
        save()
        changed()
    }

    private fun changed() {
        adapter.notifyDataSetChanged()
        empty.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
        if (messages.isNotEmpty()) list.scrollToPosition(messages.size - 1)
    }

    private fun save() {
        val arr = JSONArray()
        messages.filter { it.text.isNotEmpty() }.takeLast(60).forEach { arr.put(JSONObject().put("u", it.user).put("t", it.text).put("s", it.tps ?: 0.0)) }
        Gemma.prefs(act).edit().putString("chat", arr.toString()).apply()
    }

    private fun load() {
        runCatching {
            val arr = JSONArray(Gemma.prefs(act).getString("chat", "[]"))
            for (i in 0 until arr.length()) arr.getJSONObject(i).let { messages += Msg(it.getBoolean("u"), it.getString("t"), it.optDouble("s", 0.0).takeIf { s -> s > 0 }) }
        }
    }

    private companion object {
        const val MAX_SIDE = 1024
    }
}
