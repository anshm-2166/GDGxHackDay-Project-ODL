package com.vytros.projects.Quill

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/** Gemma 4 E2B running on the phone itself through LiteRT-LM, on the GPU when the device allows it. */
object Local {
    private const val MODEL_URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
    private const val FILE = "gemma-4-E2B-it.litertlm"
    private const val MIN_BYTES = 1_000_000_000L // anything smaller is a broken download
    const val LABEL = "Gemma 4 E2B · on device"

    private val main = Handler(Looper.getMainLooper())

    fun enabled(ctx: Context) = Gemma.prefs(ctx).getString("engine", "ollama") == "device"
    fun setEnabled(ctx: Context, on: Boolean) = Gemma.prefs(ctx).edit().putString("engine", if (on) "device" else "ollama").apply()
    fun wantsGpu(ctx: Context) = Gemma.prefs(ctx).getBoolean("gpu", true)
    fun setGpu(ctx: Context, on: Boolean) { Gemma.prefs(ctx).edit().putBoolean("gpu", on).apply(); release() }

    fun file(ctx: Context) = File(ctx.getExternalFilesDir(null), "models/$FILE")
    fun installed(ctx: Context) = file(ctx).let { it.exists() && it.length() > MIN_BYTES }

    // ---- download / import state, shared so the UI can reattach after leaving the screen ----

    @Volatile var busy = false; private set
    @Volatile var bytes = 0L; private set
    @Volatile var total = 0L; private set
    @Volatile var error: String? = null; private set
    @Volatile private var cancelDl = false
    @Volatile var listener: (() -> Unit)? = null

    private fun changed() = main.post { listener?.invoke() }

    fun cancelDownload() { cancelDl = true }

    fun download(ctx: Context) = transfer(ctx) { part, have ->
        val c = URL(MODEL_URL).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
        val code = c.responseCode
        if (code == 416) return@transfer // already have every byte
        if (code != 200 && code != 206) error("Download failed (HTTP $code)")
        val resumed = code == 206
        total = (if (resumed) have else 0L) + c.contentLengthLong
        copy(c.inputStream, part, append = resumed, start = if (resumed) have else 0L)
    }

    /** Copies a model file the user already has (picked from storage) into the app. */
    fun import(ctx: Context, uri: Uri) = transfer(ctx, resume = false) { part, _ ->
        val size = ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
            ?.use { if (it.moveToFirst()) it.getLong(0) else -1L } ?: -1L
        total = size
        val input = ctx.contentResolver.openInputStream(uri) ?: error("Can't open that file")
        copy(input, part, append = false, start = 0L)
    }

    private fun transfer(ctx: Context, resume: Boolean = true, body: (part: File, have: Long) -> Unit) {
        if (busy) return
        busy = true; cancelDl = false; error = null; bytes = 0; total = 0
        release()
        changed()
        thread {
            try {
                val dest = file(ctx)
                dest.parentFile!!.mkdirs()
                val part = File(dest.path + ".part")
                if (!resume) part.delete()
                bytes = part.length()
                body(part, part.length())
                if (!cancelDl) {
                    if (part.length() < MIN_BYTES) { part.delete(); error("That file is too small to be the model") }
                    dest.delete()
                    if (!part.renameTo(dest)) error("Couldn't save the model")
                }
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
            } finally {
                busy = false
                changed()
            }
        }
    }

    private fun copy(input: java.io.InputStream, part: File, append: Boolean, start: Long) {
        bytes = start
        var last = 0L
        input.use { src ->
            FileOutputStream(part, append).use { out ->
                val buf = ByteArray(256 * 1024)
                while (!cancelDl) {
                    val n = src.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    bytes += n
                    val now = System.currentTimeMillis()
                    if (now - last > 300) { last = now; changed() }
                }
            }
        }
    }

    fun delete(ctx: Context) {
        release()
        file(ctx).delete()
        File(file(ctx).path + ".part").delete()
        changed()
    }

    // ---- inference ----

    private val lock = Any()
    private var engine: Engine? = null
    private var engineGpu = false
    private var engineVision = false

    /** "GPU" or "CPU" once the engine has loaded; null before that. */
    @Volatile var active: String? = null; private set
    @Volatile var gpuNote: String? = null; private set

    fun release() = synchronized(lock) {
        runCatching { engine?.close() }
        engine = null
        active = null
    }

    private fun engine(ctx: Context, vision: Boolean = false): Engine = synchronized(lock) {
        val gpu = wantsGpu(ctx)
        engine?.let { if (engineGpu == gpu && (engineVision || !vision)) return it else release() }
        if (!installed(ctx)) error("The on-device model isn't downloaded yet. Open Settings to get it.")
        val app = ctx.applicationContext
        fun make(b: Backend) = Engine(EngineConfig(modelPath = file(app).path, backend = b, visionBackend = if (vision) b else null, maxNumImages = if (vision) 1 else null, cacheDir = app.cacheDir.path))
            .also { it.initialize() }
        gpuNote = null
        engine = if (gpu) try {
            make(Backend.GPU()).also { active = "GPU" }
        } catch (t: Throwable) {
            gpuNote = "GPU unavailable (${t.message?.lineSequence()?.firstOrNull() ?: t.javaClass.simpleName}), using CPU"
            make(Backend.CPU()).also { active = "CPU" }
        } else make(Backend.CPU()).also { active = "CPU" }
        engineGpu = gpu
        engineVision = vision
        engine!!
    }

    private fun config(system: String, history: List<Pair<String, String>>, temp: Float) = ConversationConfig(
        systemInstruction = Contents.of(system),
        initialMessages = history.map { if (it.first == "user") Message.user(it.second) else Message.model(it.second) },
        samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = temp.toDouble(), seed = 0),
        thinkingConfig = ThinkingConfig(false),
    )

    private fun contents(text: String, image: ByteArray?) =
        if (image == null) Contents.of(text) else Contents.of(Content.ImageBytes(image), Content.Text(text))

    private fun Message.text() = contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    // Reached by reflection: the runtime marks benchmarking experimental and Kotlin won't resolve it directly.
    private fun speed(c: Conversation): Double? = runCatching {
        val info = c.javaClass.getMethod("getBenchmarkInfo").invoke(c)
        info.javaClass.getMethod("getLastDecodeTokensPerSecond").invoke(info) as Double
    }.getOrNull()?.takeIf { it > 0 }

    /** One-shot reply to [user] under [system]: text and decode speed in tokens/s. */
    fun complete(ctx: Context, system: String, user: String, temp: Float, image: ByteArray? = null): Pair<String, Double?> = synchronized(lock) {
        engine(ctx, image != null).createConversation(config(system, emptyList(), temp)).use { c ->
            val reply = c.sendMessage(contents(user, image)).text()
            reply to speed(c)
        }
    }

    /** Streams a reply to the last entry of [history]; returns the decode speed. Stops early if [h] is cancelled. */
    fun stream(ctx: Context, system: String, history: List<Pair<String, String>>, temp: Float, image: ByteArray?, h: Gemma.Handle, onDelta: (String) -> Unit): Double? =
        synchronized(lock) {
            val eng = engine(ctx, image != null)
            eng.createConversation(config(system, history.dropLast(1), temp)).use { c ->
                val done = CountDownLatch(1)
                var failure: Throwable? = null
                var chars = 0
                val began = System.nanoTime()
                h.onCancel = { runCatching { c.cancelProcess() } }
                c.sendMessageAsync(contents(history.last().second, image), object : MessageCallback {
                    override fun onMessage(message: Message) { val t = message.text(); chars += t.length; if (t.isNotEmpty()) onDelta(t) }
                    override fun onDone() { done.countDown() }
                    override fun onError(throwable: Throwable) { failure = throwable; done.countDown() }
                })
                done.await()
                h.onCancel = null
                failure?.let { if (!h.cancelled) throw it }
                // Fall back to a rough estimate (about 4 characters a token) if the runtime reports no benchmark.
                speed(c) ?: (chars / 4.0 / ((System.nanoTime() - began) / 1e9)).takeIf { chars > 0 && it.isFinite() }
            }
        }
}
