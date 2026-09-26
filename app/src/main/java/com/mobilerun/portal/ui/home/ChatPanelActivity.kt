package com.mobilerun.portal.ui.home

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.mobilerun.portal.R
import com.mobilerun.portal.agent.AgentRuntime
import com.mobilerun.portal.agent.RunListener
import com.mobilerun.portal.agent.RunSpec
import com.mobilerun.portal.agent.RunStatus
import com.mobilerun.portal.api.ApiResponse

/**
 * The bubble's chat, Messenger style: opens under the bubble over whatever app is showing.
 * Sending a task closes it first, so the task works on the app underneath. While a task runs,
 * opening the chat pauses it (the phone must not tap on the chat) and closing it lets it go on.
 */
class ChatPanelActivity : AppCompatActivity(), RunListener {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var controls: View
    private lateinit var pause: MaterialButton
    private var pausedByOpening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat_panel)
        val m = resources.displayMetrics
        window.setLayout((m.widthPixels - dp(16)), (m.heightPixels * 0.68f).toInt())
        window.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        window.attributes = window.attributes.apply { y = dp(104) } // just under the bubble at the top
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        list = findViewById(R.id.chat_list)
        scroll = findViewById(R.id.chat_scroll)
        status = findViewById(R.id.chat_status)
        controls = findViewById(R.id.chat_controls)
        pause = findViewById(R.id.chat_pause)
        findViewById<ImageView>(R.id.chat_avatar).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }
        val input = findViewById<EditText>(R.id.chat_input)
        findViewById<View>(R.id.chat_send).setOnClickListener { send(input.text.toString()) }
        input.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEND) { send(input.text.toString()); true } else false }
        findViewById<View>(R.id.chat_close).setOnClickListener { finish() }
        pause.setOnClickListener {
            val host = AgentRuntime.host() ?: return@setOnClickListener
            pausedByOpening = false // the user decides now: closing the chat leaves it as they set it
            host.setPaused("", !host.isPaused())
            FaBubble.changed()
            render()
        }
        findViewById<View>(R.id.chat_stop).setOnClickListener { AgentRuntime.host()?.running()?.let { AgentRuntime.host()?.stop(it) } }
    }

    override fun onStart() {
        super.onStart()
        AgentRuntime.host()?.let { host ->
            host.addListener(this)
            if (host.running() != null && !host.isPaused()) { // hold the task while the chat covers the screen
                host.setPaused("", true)
                pausedByOpening = true
                FaBubble.changed()
            }
        }
        render()
    }

    override fun onStop() {
        AgentRuntime.host()?.let { host ->
            host.removeListener(this)
            if (pausedByOpening && host.isPaused()) host.setPaused("", false)
            pausedByOpening = false
            FaBubble.changed()
        }
        super.onStop()
        if (!isFinishing) finish() // the user left: the chat closes like Messenger's
    }

    override fun onDestroy() {
        FaBubble.chatClosed()
        super.onDestroy()
    }

    private fun send(text: String) {
        if (text.isBlank()) return
        if (AgentRuntime.host()?.running() != null) {
            Toast.makeText(this, "Wait for this task to finish, or stop it first", Toast.LENGTH_SHORT).show()
            return
        }
        val app = applicationContext
        finish()
        main.postDelayed({ // once the chat is gone, start on the app the user was in
            Thread {
                val response = AgentRuntime.host()?.startLocal(text.trim(), null, null, leaveApp = false)
                    ?: ApiResponse.Error("The agent is not ready yet")
                if (response is ApiResponse.Error) main.post { Toast.makeText(app, response.message, Toast.LENGTH_LONG).show() }
            }.start()
        }, CLOSE_MS)
    }

    private fun render() {
        val host = AgentRuntime.host()
        val running = host?.running() != null
        val paused = host?.isPaused() == true
        status.text = when {
            !running -> "Ready"
            paused -> "Paused"
            else -> "Working..."
        }
        controls.visibility = if (running) View.VISIBLE else View.GONE
        pause.text = if (paused) "Resume" else "Pause"
        list.removeAllViews()
        val records = AgentRuntime.store()?.page(1, RECENT)?.first.orEmpty().reversed()
        val messages = ChatMessages.of(records)
        if (messages.isEmpty()) list.addView(bubbleView(ChatMessage(false, "Hi! Tell me what to do on this phone.", Tone.STEP)))
        messages.forEach { list.addView(bubbleView(it)) }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun bubbleView(msg: ChatMessage): View {
        val (bg, fg) = when (msg.tone) {
            Tone.USER -> getColor(R.color.mobilerun_primary) to Color.WHITE
            Tone.STEP -> Color.parseColor("#EEF0EA") to getColor(R.color.mobilerun_foreground)
            Tone.GOOD -> Color.parseColor("#DDF3E6") to Color.parseColor("#0B5E33")
            Tone.BAD -> Color.parseColor("#FBE3E0") to Color.parseColor("#9C2F25")
        }
        val text = TextView(this).apply {
            this.text = msg.text
            setTextColor(fg)
            textSize = 15f
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(bg) }
            maxWidth = (resources.displayMetrics.widthPixels * 0.7f).toInt()
        }
        return LinearLayout(this).apply {
            gravity = if (msg.fromUser) Gravity.END else Gravity.START
            setPadding(0, dp(3), 0, dp(3))
            addView(text)
        }
    }

    override fun started(spec: RunSpec, origin: String) { main.post { render() } }

    override fun event(uuid: String, kind: String, text: String, steps: Int?) { main.post { render() } }

    override fun finished(uuid: String, status: RunStatus, result: String, steps: Int, shot: String?) { main.post { render() } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val CLOSE_MS = 600L
        private const val RECENT = 6
    }
}
