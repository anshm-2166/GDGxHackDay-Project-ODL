package com.vytros.projects.Quill

import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import java.io.File
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged

/** Server, models, every system prompt, and the keyboard/assistant setup. */
class SettingsTab(private val act: MainActivity, private val root: View, private val onChanged: () -> Unit) {
    private val prefs = Gemma.prefs(act)
    private val promptList = root.findViewById<LinearLayout>(R.id.promptList)
    private val model = root.findViewById<EditText>(R.id.model)
    private val chatModel = root.findViewById<EditText>(R.id.chatModelField)
    private val engOllama = root.findViewById<TextView>(R.id.engOllama)
    private val engDevice = root.findViewById<TextView>(R.id.engDevice)
    private val accGpu = root.findViewById<TextView>(R.id.accGpu)
    private val accCpu = root.findViewById<TextView>(R.id.accCpu)
    private val devStatus = root.findViewById<TextView>(R.id.devStatus)
    private val devDetail = root.findViewById<TextView>(R.id.devDetail)
    private val devProgress = root.findViewById<ProgressBar>(R.id.devProgress)
    private val devPrimary = root.findViewById<TextView>(R.id.devPrimary)
    private val devImport = root.findViewById<TextView>(R.id.devImport)
    private val devDelete = root.findViewById<TextView>(R.id.devDelete)
    private var confirmDelete = false

    init {
        val url = root.findViewById<EditText>(R.id.url)
        url.setText(prefs.getString("url", Gemma.DEFAULT_URL))
        url.doAfterTextChanged { prefs.edit().putString("url", it.toString().trim()).apply() }

        model.setText(prefs.getString("model", Gemma.DEFAULT_MODEL))
        model.doAfterTextChanged { prefs.edit().putString("model", it.toString().trim()).apply(); onChanged() }
        chatModel.setText(prefs.getString("chat_model", ""))
        chatModel.doAfterTextChanged { prefs.edit().putString("chat_model", it.toString().trim()).apply(); onChanged() }

        slider(R.id.temp, R.id.tempValue, "temp", Gemma.DEFAULT_TEMP)
        slider(R.id.chatTemp, R.id.chatTempValue, "chat_temp", Gemma.DEFAULT_CHAT_TEMP)

        val status = root.findViewById<TextView>(R.id.status)
        val icon = root.findViewById<ImageView>(R.id.statusIcon)
        root.findViewById<View>(R.id.test).setOnClickListener {
            status.text = "Connecting…"
            Gemma.models(act) { r ->
                r.onFailure {
                    icon.setImageResource(R.drawable.ic_warning)
                    status.text = "Can't connect: ${it.message}"
                }
                r.onSuccess { names ->
                    icon.setImageResource(if (names.isEmpty()) R.drawable.ic_warning else R.drawable.ic_check_circle)
                    status.text = if (names.isEmpty()) "Connected, but no models installed"
                    else "Connected · ${names.size} model${if (names.size == 1) "" else "s"}"
                }
            }
        }
        root.findViewById<View>(R.id.pickModel).setOnClickListener { pick(model) }
        root.findViewById<View>(R.id.pickChatModel).setOnClickListener { pick(chatModel) }
        root.findViewById<View>(R.id.addTool).setOnClickListener { editTool(null) }
        setupEngine()
        renderPrompts()
    }

    // ---- engine: Ollama server or Gemma on the phone ----


    private fun setupEngine() {
        engOllama.setOnClickListener { Local.setEnabled(act, false); renderEngine(); onChanged() }
        engDevice.setOnClickListener { Local.setEnabled(act, true); renderEngine(); onChanged() }
        accGpu.setOnClickListener { Local.setGpu(act, true); renderEngine() }
        accCpu.setOnClickListener { Local.setGpu(act, false); renderEngine() }
        devPrimary.setOnClickListener { if (Local.busy) Local.cancelDownload() else Local.download(act) }
        devImport.setOnClickListener { act.pickModelFile.launch(arrayOf("*/*")) }
        devDelete.setOnClickListener {
            if (!confirmDelete) { confirmDelete = true; devDelete.text = "Tap to confirm"; return@setOnClickListener }
            Local.delete(act)
        }
        Local.listener = { renderEngine() }
        renderEngine()
    }

    private fun segment(v: TextView, on: Boolean) {
        v.setBackgroundResource(if (on) R.drawable.bg_nav_pill else 0)
        v.setTextColor(act.getColor(if (on) R.color.on_brand_container else R.color.text_secondary))
    }

    private fun gb(b: Long) = "%.1f GB".format(b / 1e9)

    fun renderEngine() {
        val device = Local.enabled(act)
        segment(engOllama, !device)
        segment(engDevice, device)
        root.findViewById<View>(R.id.devicePanel).visibility = if (device) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.serverCard).visibility = if (device) View.GONE else View.VISIBLE
        segment(accGpu, Local.wantsGpu(act))
        segment(accCpu, !Local.wantsGpu(act))
        confirmDelete = false
        devDelete.text = "Delete"

        val error = Local.error
        val installed = Local.installed(act)
        val partial = File(Local.file(act).path + ".part").length()
        devProgress.visibility = if (Local.busy) View.VISIBLE else View.GONE
        devDetail.setTextColor(act.getColor(if (error != null) R.color.kb_error else R.color.text_secondary))
        when {
            Local.busy -> {
                val total = Local.total
                devProgress.progress = if (total > 0) (Local.bytes * 1000 / total).toInt() else 0
                devStatus.text = if (total > 0) "Downloading · ${Local.bytes * 100 / total}%" else "Copying…"
                devDetail.text = if (total > 0) "${gb(Local.bytes)} of ${gb(total)}" else gb(Local.bytes)
                devPrimary.text = "Cancel"
            }
            installed -> {
                devStatus.text = "Gemma 4 E2B is ready"
                devDetail.text = error ?: Local.gpuNote ?: Local.active?.let { "Running on the $it" } ?: "${gb(Local.file(act).length())} · loads on first use"
                devPrimary.visibility = View.GONE
            }
            else -> {
                devStatus.text = "Model not on this phone yet"
                devDetail.text = error ?: "Gemma 4 E2B · about 2.6 GB. Wi-Fi recommended."
                devPrimary.text = if (partial > 0) "Resume" else "Download"
            }
        }
        if (Local.busy || !installed) devPrimary.visibility = View.VISIBLE
        devImport.visibility = if (installed || Local.busy) View.GONE else View.VISIBLE
        devDelete.visibility = if (installed) View.VISIBLE else View.GONE
    }

    private fun slider(bar: Int, label: Int, key: String, default: Float) {
        val seek = root.findViewById<SeekBar>(bar)
        val value = root.findViewById<TextView>(label)
        fun show(p: Int) {
            val t = p / 10f
            value.text = "%.1f · %s".format(t, if (t < 0.4f) "precise" else if (t < 0.9f) "balanced" else "inventive")
        }
        val start = Math.round(prefs.getFloat(key, default) * 10)
        seek.progress = start
        show(start)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { show(p); if (fromUser) prefs.edit().putFloat(key, p / 10f).apply() }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    private fun pick(target: EditText) {
        Gemma.models(act) { r ->
            r.onFailure { android.widget.Toast.makeText(act, "Can't reach Ollama: ${it.message}", android.widget.Toast.LENGTH_LONG).show() }
            r.onSuccess { names ->
                if (names.isEmpty()) android.widget.Toast.makeText(act, "No models installed on the server", android.widget.Toast.LENGTH_LONG).show()
                else act.pickSheet(names, target.text.toString()) { target.setText(it) }
            }
        }
    }

    // ---- prompts ----

    private fun renderPrompts() {
        promptList.removeAllViews()
        row("Chat persona", Prompts.chat(act)) { editChat() }
        row("Style coach", Prompts.style(act)) { editStyle() }
        Prompts.tools(act).forEach { t -> row(t.name + if (t.builtin) "" else "  ·  custom", t.prompt) { editTool(t) } }
        Commands.ALL.filter { it.prompt.isNotEmpty() }.forEach { c -> row("/${c.name}  ·  chat command", Commands.prompt(act, c)) { editCommand(c) } }
    }

    private fun row(title: String, preview: String, onClick: () -> Unit) {
        val v = act.layoutInflater.inflate(R.layout.item_prompt, promptList, false)
        v.findViewById<TextView>(R.id.pTitle).text = title
        v.findViewById<TextView>(R.id.pPreview).text = preview
        v.setOnClickListener { onClick() }
        promptList.addView(v)
    }

    private fun editChat() = act.editPrompt(
        PromptEdit("Chat persona", "The system prompt behind every conversation in the Chat tab.", null, false, Prompts.chat(act), true, false),
        onSave = { _, text -> Prompts.setChat(act, text); renderPrompts(); null },
        onReset = { Prompts.setChat(act, null); renderPrompts() },
    )

    private fun editStyle() = act.editPrompt(
        PromptEdit("Style coach", "Suggests clarity, engagement and delivery improvements on the keyboard and assistant. It must keep returning JSON suggestions, so edit gently.", null, false, Prompts.style(act), true, false),
        onSave = { _, text -> Prompts.setStyle(act, text); renderPrompts(); null },
        onReset = { Prompts.setStyle(act, null); renderPrompts() },
    )

    /** [tool] null = creating a new custom tool. */
    private fun editTool(tool: Prompts.Tool?) = act.editPrompt(
        PromptEdit(
            if (tool == null) "New tool" else tool.name,
            if (tool == null) "It appears as a chip in Scribe and in the keyboard's tools menu." else if (tool.builtin) "Built-in tool. Edit the prompt, or reset it to the original." else "Custom tool.",
            tool?.name.orEmpty(), tool?.builtin == true, tool?.prompt.orEmpty(),
            canReset = tool?.builtin == true, canDelete = tool != null && !tool.builtin,
        ),
        onSave = { name, text ->
            when {
                tool == null || !tool.builtin -> when {
                    name.isBlank() -> "Give it a name"
                    !name.equals(tool?.name, ignoreCase = true) && Prompts.exists(act, name) -> "That name is taken"
                    else -> { Prompts.save(act, tool?.name, Prompts.Tool(name, text, false)); toolsChanged(); null }
                }
                else -> { Prompts.save(act, tool.name, tool.copy(prompt = text)); toolsChanged(); null }
            }
        },
        onReset = {
            if (tool?.builtin == true) Prompts.save(act, tool.name, tool.copy(prompt = Prompts.DEFAULT_TOOLS.getValue(tool.name)))
            else if (tool != null) Prompts.delete(act, tool.name)
            toolsChanged()
        },
    )

    private fun editCommand(c: Commands.Cmd) = act.editPrompt(
        PromptEdit("/${c.name}", c.about + ". Flags you type are appended to this prompt.", null, false, Commands.prompt(act, c), true, false),
        onSave = { _, text -> Commands.setPrompt(act, c, text); renderPrompts(); null },
        onReset = { Commands.setPrompt(act, c, null); renderPrompts() },
    )

    private fun toolsChanged() { renderPrompts(); onChanged() }
}
