package com.vytros.projects.Quill

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Talks to any Ollama server. Everything AI in the app goes through here. */
object Gemma {
    const val DEFAULT_URL = "http://10.0.2.2:11434" // emulator -> host machine
    const val DEFAULT_MODEL = "gemma4:e4b"
    const val DEFAULT_TEMP = 0.2f
    const val DEFAULT_CHAT_TEMP = 0.7f

    private const val STYLE_SCHEMA = """{"type":"object","properties":{"suggestions":{"type":"array","items":{"type":"object",
        "properties":{"category":{"type":"string","enum":["clarity","engagement","delivery"]},"label":{"type":"string"},
        "original":{"type":"string"},"replacement":{"type":"string"}},
        "required":["category","label","original","replacement"]}}},"required":["suggestions"]}"""

    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun prefs(ctx: Context) = ctx.getSharedPreferences("quill", Context.MODE_PRIVATE)

    fun url(ctx: Context) = prefs(ctx).getString("url", DEFAULT_URL)!!.trim().trimEnd('/')
    fun model(ctx: Context) = prefs(ctx).getString("model", DEFAULT_MODEL)!!.ifBlank { DEFAULT_MODEL }
    fun chatModel(ctx: Context) = prefs(ctx).getString("chat_model", "")!!.ifBlank { model(ctx) }
    fun temp(ctx: Context) = prefs(ctx).getFloat("temp", DEFAULT_TEMP)
    fun chatTemp(ctx: Context) = prefs(ctx).getFloat("chat_temp", DEFAULT_CHAT_TEMP)

    fun style(ctx: Context, text: String, cb: (Result<List<Checker.Issue>>) -> Unit) {
        if (text.trim().split(Regex("\\s+")).size < 4) return cb(Result.success(emptyList()))
        val c = Config(ctx)
        val system = Prompts.style(ctx)
        thread {
            val r = runCatching {
                val arr = JSONObject(chat(c, system, text, JSONObject(STYLE_SCHEMA)).first).getJSONArray("suggestions")
                List(arr.length()) {
                    val o = arr.getJSONObject(it)
                    val cat = runCatching { Checker.Category.valueOf(o.getString("category").uppercase()) }.getOrDefault(Checker.Category.CLARITY)
                    Checker.Issue(o.getString("label"), o.getString("original"), o.getString("replacement"), -1, cat)
                }
            }
            main.post { cb(r) }
        }
    }

    /** [onSpeed] receives the generation speed in tokens per second, when the server reports it. */
    fun run(ctx: Context, action: String, text: String, onSpeed: ((Double?) -> Unit)? = null, cb: (Result<String>) -> Unit) {
        val c = Config(ctx)
        val system = Prompts.prompt(ctx, action)
        thread {
            var tps: Double? = null
            val r = runCatching { chat(c, system, text, null).let { tps = it.second; clean(it.first) } }
            main.post { onSpeed?.invoke(tps); cb(r) }
        }
    }

    /**
     * One-shot request with a caller-supplied [system] prompt, an optional [image] and an optional JSON [schema].
     * [cb] gets the cleaned reply and the generation speed in tokens per second.
     */
    fun ask(ctx: Context, system: String, user: String, image: ByteArray? = null, schema: JSONObject? = null, cb: (Result<String>, Double?) -> Unit) {
        val c = Config(ctx, chat = true)
        thread {
            var tps: Double? = null
            val r = runCatching { chat(c, system, user, schema, image).let { tps = it.second; clean(it.first) } }
            main.post { cb(r, tps) }
        }
    }

    /** Ollama reports eval_count tokens generated over eval_duration nanoseconds. */
    private fun speed(o: JSONObject): Double? {
        val n = o.optLong("eval_count"); val ns = o.optLong("eval_duration")
        return if (n > 0 && ns > 0) n * 1e9 / ns else null
    }

    fun issues(ctx: Context, text: String, cb: (Result<List<Checker.Issue>>) -> Unit) {
        val c = Config(ctx)
        val system = Prompts.prompt(ctx, Prompts.FIX)
        thread {
            // Ask for the corrected text (small models do this far more reliably than listing
            // mistakes), then diff it word by word into issue cards.
            val r = runCatching { Checker.diff(text, clean(chat(c, system, text, null).first)) }
            main.post { cb(r) }
        }
    }

    fun models(ctx: Context, cb: (Result<List<String>>) -> Unit) {
        val url = url(ctx)
        thread {
            val r = runCatching {
                val arr = JSONObject(request("$url/api/tags", null)).getJSONArray("models")
                List(arr.length()) { arr.getJSONObject(it).getString("name") }
            }
            main.post { cb(r) }
        }
    }

    /** A running chat reply; [cancel] stops it and keeps whatever has streamed so far. */
    class Handle { @Volatile var tps: Double? = null; @Volatile var cancelled = false; @Volatile var conn: HttpURLConnection? = null; @Volatile var onCancel: (() -> Unit)? = null
        fun cancel() { cancelled = true; conn?.disconnect(); onCancel?.invoke() } }

    /** Streams a reply to [history] (role to content pairs), calling [onDelta] with each new piece of text. */
    fun converse(
        ctx: Context, history: List<Pair<String, String>>, system: String? = null, image: ByteArray? = null,
        onDelta: (String) -> Unit, onDone: (Throwable?, Double?) -> Unit,
    ): Handle {
        val c = Config(ctx, chat = true)
        val system = Prompts.withClock(system ?: Prompts.chat(ctx))
        val h = Handle()
        thread {
            var err: Throwable? = null
            try {
                if (c.local) {
                    h.tps = Local.stream(c.ctx, system, history, c.temp, image, h) { s -> main.post { if (!h.cancelled) onDelta(s) } }
                } else {
                    val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
                    history.forEachIndexed { i, m ->
                        val o = JSONObject().put("role", m.first).put("content", m.second)
                        if (image != null && i == history.lastIndex) o.put("images", JSONArray().put(Base64.encodeToString(image, Base64.NO_WRAP)))
                        messages.put(o)
                    }
                    stream(c, messages, h, onDelta)
                }
            } catch (t: Throwable) {
                if (!h.cancelled) err = t
            }
            main.post { onDone(err, h.tps) }
        }
        return h
    }

    private class Config(val ctx: Context, val url: String, val model: String, val temp: Float, val local: Boolean) {
        constructor(ctx: Context, chat: Boolean = false) : this(
            ctx.applicationContext,
            url(ctx), if (chat) chatModel(ctx) else model(ctx), if (chat) chatTemp(ctx) else temp(ctx), Local.enabled(ctx),
        )
    }

    private fun body(c: Config, messages: JSONArray, stream: Boolean, think: Boolean, format: JSONObject?) = JSONObject()
        .put("model", c.model)
        .put("stream", stream)
        .put("options", JSONObject().put("temperature", c.temp.toDouble()))
        .put("messages", messages)
        .also {
            if (!think) it.put("think", false)
            if (format != null) it.put("format", format)
        }

    private fun chat(c: Config, systemPrompt: String, user: String, format: JSONObject?, image: ByteArray? = null): Pair<String, Double?> {
        val system = Prompts.withClock(systemPrompt)
        if (c.local) {
            // The on-device runtime has no JSON-schema mode, so ask for the shape in words and cut out the object.
            val sys = if (format == null) system else "$system\n\nReply with only one JSON object matching this schema, nothing else: $format"
            val (text, tps) = Local.complete(c.ctx, sys, user, c.temp, image)
            return (if (format == null) text else text.substring(text.indexOf('{').coerceAtLeast(0), text.lastIndexOf('}') + 1)) to tps
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user).also { if (image != null) it.put("images", JSONArray().put(Base64.encodeToString(image, Base64.NO_WRAP))) })
        // think:false speeds up reasoning models but some models reject the field; retry without it.
        val s = try {
            request("${c.url}/api/chat", body(c, messages, false, false, format))
        } catch (e: IllegalStateException) {
            if (e.message?.contains("think", ignoreCase = true) != true) throw e
            request("${c.url}/api/chat", body(c, messages, false, true, format))
        }
        val o = JSONObject(s)
        return o.getJSONObject("message").getString("content") to speed(o)
    }

    private fun stream(c: Config, messages: JSONArray, h: Handle, onDelta: (String) -> Unit) {
        fun open(think: Boolean): HttpURLConnection {
            val conn = URL("${c.url}/api/chat").openConnection() as HttpURLConnection
            h.conn = conn
            conn.connectTimeout = 5_000
            conn.readTimeout = 120_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body(c, messages, true, think, null).toString().toByteArray()) }
            return conn
        }
        var conn = open(false)
        if (conn.responseCode >= 400) {
            val msg = errorOf(conn)
            if (!msg.contains("think", ignoreCase = true)) error(msg)
            conn = open(true)
            if (conn.responseCode >= 400) error(errorOf(conn))
        }
        var inThink = false
        conn.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (h.cancelled || line.isBlank()) { if (h.cancelled) break else continue }
                val o = JSONObject(line)
                o.optString("error").takeIf { it.isNotEmpty() }?.let { error(it) }
                var piece = o.optJSONObject("message")?.optString("content").orEmpty()
                // Some models inline their reasoning in <think> tags; hide it from the chat.
                if (piece.contains("<think>")) inThink = true
                if (inThink) { if (piece.contains("</think>")) { inThink = false; piece = piece.substringAfter("</think>") } else piece = "" }
                if (piece.isNotEmpty()) main.post { if (!h.cancelled) onDelta(piece) }
                if (o.optBoolean("done")) { h.tps = speed(o); break }
            }
        }
    }

    private fun errorOf(c: HttpURLConnection): String {
        val s = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        return runCatching { JSONObject(s).getString("error") }.getOrDefault("HTTP ${c.responseCode}")
    }

    private fun request(url: String, body: JSONObject?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5_000
        c.readTimeout = 120_000
        if (body != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val code = c.responseCode
        if (code >= 400) error(errorOf(c))
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    /** Strips the wrapping small models like to add: think blocks, code fences, quotes. */
    fun clean(raw: String): String {
        var s = raw.replace(Regex("(?s)<think>.*?</think>"), "").trim()
        s = s.removePrefix("```").removeSuffix("```").trim()
        if (s.length > 1 && s.first() == '"' && s.last() == '"') s = s.substring(1, s.length - 1)
        return s.trim()
    }
}
