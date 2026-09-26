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
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
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
    private var fresh = false // "+" was tapped: a clean chat for a new task
    private var closing = false
    private lateinit var card: View
    private lateinit var scrim: View
    private lateinit var newTask: View
    private lateinit var pointer: View
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat_panel)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) // the card shrinks above the keyboard
        overridePendingTransition(0, 0) // our own Messenger-style animation instead
        scrim = findViewById(R.id.chat_scrim)
        scrim.setOnClickListener { minimizeNow() } // tap outside minimizes, like Messenger
        card = findViewById(R.id.chat_root)
        newTask = findViewById(R.id.chat_new)
        pointer = findViewById(R.id.chat_pointer)
        newTask.setOnClickListener { startNewTask() }
        open = java.lang.ref.WeakReference(this)

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
        input = findViewById(R.id.chat_input)
        findViewById<View>(R.id.chat_send).setOnClickListener { send(input.text.toString()) }
        input.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEND) { send(input.text.toString()); true } else false }
        pause.setOnClickListener {
            val host = AgentRuntime.host() ?: return@setOnClickListener
            pausedByOpening = false // the user decides now: closing the chat leaves it as they set it
            host.setPaused("", !host.isPaused())
            FaBubble.changed()
            render()
        }
        findViewById<View>(R.id.chat_stop).setOnClickListener { AgentRuntime.host()?.running()?.let { AgentRuntime.host()?.stop(it) } }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = minimizeNow()
        })
        animateIn()
    }

    // ---- Messenger-style motion ------------------------------------------------------------------
    private fun animateIn() {
        scrim.background.alpha = 0
        card.alpha = 0f
        pointer.alpha = 0f
        newTask.scaleX = 0f
        newTask.scaleY = 0f
        card.post {
            val slot = IntArray(2).also { findViewById<View>(R.id.head_slot).getLocationOnScreen(it) }
            FaBubble.dockAt(slot[0], slot[1]) // the bubble springs onto its place in the top row
            card.pivotX = card.width - dp(42).toFloat() // grows out from under the bubble
            card.pivotY = 0f
            card.scaleX = 0.4f
            card.scaleY = 0.4f
            android.animation.ValueAnimator.ofInt(0, 255).apply {
                duration = 200
                addUpdateListener { scrim.background.alpha = it.animatedValue as Int }
            }.start()
            card.animate().alpha(1f).setDuration(120).start()
            pointer.animate().alpha(1f).setStartDelay(80).setDuration(120).start()
            spring(card, 1f, SpringForce.DAMPING_RATIO_LOW_BOUNCY)
            newTask.postDelayed({ spring(newTask, 1f, SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY) }, 90)
        }
    }

    private fun spring(view: View, to: Float, damping: Float) {
        for (p in listOf(DynamicAnimation.SCALE_X, DynamicAnimation.SCALE_Y)) {
            SpringAnimation(view, p, to).apply { spring.setStiffness(SpringForce.STIFFNESS_MEDIUM).dampingRatio = damping }.start()
        }
    }

    /** Shrink back into the bubble, then close (tap on the bubble, outside, or Back). */
    private fun minimizeNow(then: (() -> Unit)? = null) {
        if (closing) return
        closing = true
        hideKeyboard()
        FaBubble.chatClosed() // the bubble springs back to its edge while the chat folds into it
        newTask.animate().scaleX(0f).scaleY(0f).setDuration(120).start()
        pointer.animate().alpha(0f).setDuration(80).start()
        card.animate().scaleX(0.4f).scaleY(0.4f).alpha(0f).setDuration(170)
            .setInterpolator(android.view.animation.AccelerateInterpolator()).start()
        android.animation.ValueAnimator.ofInt(scrim.background.alpha, 0).apply {
            duration = 170
            addUpdateListener { scrim.background.alpha = it.animatedValue as Int }
            doOnEnd {
                then?.invoke()
                finish()
                overridePendingTransition(0, 0)
            }
        }.start()
    }

    private fun android.animation.Animator.doOnEnd(block: () -> Unit) = addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) = block()
    })

    /** "+": a clean chat and the keyboard, ready for a new task. */
    private fun startNewTask() {
        fresh = true
        render()
        input.text.clear()
        input.requestFocus()
        getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(input, 0)
        newTask.animate().rotationBy(90f).setDuration(200).start()
    }

    private fun hideKeyboard() {
        getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0)
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
        FaBubble.chatClosed() // no-op when the bubble already went back
        if (open?.get() === this) open = null
        super.onDestroy()
    }

    private fun send(text: String) {
        if (text.isBlank()) return
        if (AgentRuntime.host()?.running() != null) {
            Toast.makeText(this, "Wait for this task to finish, or stop it first", Toast.LENGTH_SHORT).show()
            return
        }
        val app = applicationContext
        val handler = main
        minimizeNow {
            handler.postDelayed({ // once the chat is gone, start on the app the user was in
                Thread {
                    val response = AgentRuntime.host()?.startLocal(text.trim(), null, null, leaveApp = false)
                        ?: ApiResponse.Error("The agent is not ready yet")
                    if (response is ApiResponse.Error) handler.post { Toast.makeText(app, response.message, Toast.LENGTH_LONG).show() }
                }.start()
            }, CLOSE_MS)
        }
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
        if (running) fresh = false // a running task is always shown
        val records = if (fresh) emptyList() else AgentRuntime.store()?.page(1, RECENT)?.first.orEmpty().reversed()
        val messages = ChatMessages.of(records)
        val hello = if (fresh) "New task: what should I do?" else "Hi! Tell me what to do on this phone."
        if (messages.isEmpty()) list.addView(bubbleView(ChatMessage(false, hello, Tone.STEP)))
        messages.forEach { list.addView(bubbleView(it)) }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun bubbleView(msg: ChatMessage): View {
        val (bg, fg) = when (msg.tone) {
            Tone.USER -> getColor(R.color.mobilerun_primary) to Color.WHITE
            Tone.STEP -> getColor(R.color.fa_chat_step) to getColor(R.color.mobilerun_foreground)
            Tone.GOOD -> getColor(R.color.fa_chat_good) to getColor(R.color.fa_chat_good_ink)
            Tone.BAD -> getColor(R.color.fa_chat_bad) to getColor(R.color.fa_chat_bad_ink)
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
        private var open: java.lang.ref.WeakReference<ChatPanelActivity>? = null

        /** The bubble was tapped while the chat is open: fold it back in, like Messenger. */
        fun minimize() {
            open?.get()?.let { chat -> chat.runOnUiThread { chat.minimizeNow() } }
        }

        private const val CLOSE_MS = 350L
        private const val RECENT = 6
    }
}
