package com.vytros.projects.Quill

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Three tabs: Scribe (write), Chat, Settings. Also opens from "Improve with Quill" in any app's text-selection menu. */
class MainActivity : AppCompatActivity() {
    val pickModelFile = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> uri?.let { Local.import(this, it) } }

    val pickImage = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { onImage?.invoke(it) } }
    val speech = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { onSpeech?.invoke(it) }
    }
    private val notificationPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
    var onImage: ((android.net.Uri) -> Unit)? = null
    var onSpeech: ((String) -> Unit)? = null

    private lateinit var scribe: ScribeTab
    private lateinit var chat: ChatTab
    private lateinit var desk: DeskTab
    private lateinit var settings: SettingsTab
    private lateinit var pages: List<View>
    private lateinit var tabs: List<View>
    private var current = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val main = findViewById<View>(R.id.main)
        val nav = findViewById<LinearLayout>(R.id.nav)
        ViewCompat.setOnApplyWindowInsetsListener(main) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val keyboardUp = ime.bottom > 0
            v.setPadding(bars.left, bars.top, bars.right, if (keyboardUp) ime.bottom else 0)
            // The bar hides behind the keyboard; otherwise it carries the gesture-bar inset itself.
            nav.visibility = if (keyboardUp || fromSelection()) View.GONE else View.VISIBLE
            nav.setPadding(nav.paddingLeft, nav.paddingTop, nav.paddingRight, dp(8) + if (keyboardUp) 0 else bars.bottom)
            insets
        }

        val fromSelection = fromSelection()
        scribe = ScribeTab(this, findViewById(R.id.pageScribe), fromSelection)
        chat = ChatTab(this, findViewById(R.id.pageChat))
        desk = DeskTab(this, findViewById(R.id.pageDesk))
        settings = SettingsTab(this, findViewById(R.id.pageSettings)) { scribe.refreshTools(); chat.refreshModel() }
        chat.refreshModel()
        if (fromSelection) {
            scribe.setText(intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT))
            scribe.setCanReplace(!intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false))
        }

        pages = listOf(R.id.pageScribe, R.id.pageChat, R.id.pageDesk, R.id.pageSettings).map { findViewById(it) }
        val defs = listOf(Triple("Scribe", R.drawable.ic_scribe, 0), Triple("Chat", R.drawable.ic_chat, 1), Triple("Desk", R.drawable.ic_desk, 2), Triple("Settings", R.drawable.ic_settings, 3))
        tabs = defs.map { (label, icon, index) ->
            layoutInflater.inflate(R.layout.item_nav, nav, false).also { v ->
                v.findViewById<TextView>(R.id.label).text = label
                v.findViewById<ImageView>(R.id.icon).setImageResource(icon)
                v.setOnClickListener { select(index) }
                nav.addView(v)
            }
        }
        select(0)
    }

    private fun fromSelection() = intent.action == Intent.ACTION_PROCESS_TEXT

    private fun select(i: Int) {
        if (i == current) return
        current = i
        pages.forEachIndexed { n, p ->
            if (n == i) { p.alpha = 0f; p.visibility = View.VISIBLE; p.animate().alpha(1f).setDuration(160).start() } else p.visibility = View.GONE
        }
        tabs.forEachIndexed { n, v ->
            val on = n == i
            val icon = v.findViewById<ImageView>(R.id.icon)
            val label = v.findViewById<TextView>(R.id.label)
            v.findViewById<FrameLayout>(R.id.pill).setBackgroundResource(if (on) R.drawable.bg_nav_pill else 0)
            val tint = getColor(if (on) R.color.brand else R.color.text_secondary)
            icon.imageTintList = android.content.res.ColorStateList.valueOf(tint)
            label.setTextColor(tint)
            label.typeface = ResourcesCompat.getFont(this, if (on) R.font.dmsans_semibold else R.font.dmsans_medium)
        }
        if (i == 1) chat.refreshModel()
        if (i == 2) desk.refresh()
    }

    /** Switches to the Desk tab, for commands like /events and /notes. */
    fun showDesk(s: DeskTab.Section) { select(2); desk.show(s) }

    /** Asks for permission to post reminder notifications, on Android versions that require it. */
    fun ensureNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    fun finishWithText(text: String) {
        setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, text))
        finish()
    }

    override fun onResume() {
        super.onResume()
        scribe.renderSetup()
        if (current == 2) desk.refresh()
        settings.renderEngine()
    }
}
