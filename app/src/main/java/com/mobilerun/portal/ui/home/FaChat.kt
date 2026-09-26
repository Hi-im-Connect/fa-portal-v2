package com.mobilerun.portal.ui.home

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.mobilerun.portal.R
import com.mobilerun.portal.agent.AgentRuntime
import com.mobilerun.portal.agent.BrainReply
import com.mobilerun.portal.agent.ChatBrain
import com.mobilerun.portal.agent.LlmError
import com.mobilerun.portal.api.ApiResponse
import kotlin.math.hypot

/**
 * The bubble's chat, Messenger style, drawn over whatever app is showing: an accessibility overlay
 * window, so the app underneath stays where it is and the status bar stays visible. The card grows
 * out of the bubble and folds back into it, right next to where the bubble was. The heads row has
 * one head per chat: tap to switch, drag it down onto the X to delete it, "+" for a new chat.
 * You talk to ChatBrain; when something should happen on the phone it hands the task to the agent.
 */
class FaChat(private val service: AccessibilityService, private val bubble: Bubble) {
    /** What the chat needs from the bubble. */
    interface Bubble {
        fun center(): Pair<Int, Int> // the bubble's center on screen, now
        fun home(): Pair<Int, Int> // where it rests when the chat is closed (its center)
        fun dockAt(screenX: Int, screenY: Int) // spring onto the heads row
        fun raise() // come back on top of the chat window
        fun closed() // spring back home
        fun changed() // pause state changed
    }

    private val main = Handler(Looper.getMainLooper())
    private val wm = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var root: FrameLayout? = null
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var scrim: View
    private lateinit var column: LinearLayout
    private lateinit var heads: LinearLayout
    private lateinit var headList: LinearLayout
    private lateinit var slot: View
    private lateinit var newChat: View
    private lateinit var pointer: View
    private lateinit var card: View
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var controls: View
    private lateinit var pause: TextView
    private lateinit var input: EditText
    private lateinit var trash: View

    private var closing = false
    private var pausedByOpening = false
    private var anchorY = 0 // the bubble's center when the chat opened: the row sits there
    private var ime = 0
    private val thinking = HashSet<String>() // chats waiting for the assistant's answer

    val isOpen: Boolean get() = root != null && !closing

    // ---- opening and closing ---------------------------------------------------------------------
    fun open() {
        if (root != null) return
        val ctx = ContextThemeWrapper(service, R.style.Theme_Mobilerun)
        val frame = object : FrameLayout(ctx) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) { // Back minimizes, like Messenger
                    if (event.action == KeyEvent.ACTION_UP) minimize()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        LayoutInflater.from(ctx).inflate(R.layout.fa_chat_overlay, frame, true)
        bind(frame)
        val (screenW, screenH) = screenSize()
        val top = statusBar()
        params = WindowManager.LayoutParams(
            screenW, screenH - top, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = top // below the status bar: it stays visible
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        anchorY = bubble.center().second - top
        ime = 0
        place()
        scrim.alpha = 0f
        column.alpha = 0f
        runCatching { wm.addView(frame, params) }.onFailure { return }
        root = frame
        closing = false
        bubble.raise() // the bubble stays on top of the chat, on its slot
        holdTask()
        render()
        card.post { animateIn() }
    }

    /** Fold back into the bubble (tap on the bubble, outside, or Back), then maybe do something. */
    fun minimize(then: (() -> Unit)? = null) {
        val frame = root ?: return
        if (closing) return
        closing = true
        hideKeyboard()
        bubble.closed()
        val (hx, hy) = bubble.home()
        val at = IntArray(2).also { card.getLocationOnScreen(it) }
        card.pivotX = (hx - at[0]).toFloat()
        card.pivotY = (hy - at[1]).toFloat()
        card.animate().scaleX(0.1f).scaleY(0.1f).alpha(0f).setDuration(CLOSE_MS).setInterpolator(AccelerateInterpolator()).start()
        heads.animate().alpha(0f).setDuration(CLOSE_MS / 2).start()
        pointer.animate().alpha(0f).setDuration(CLOSE_MS / 2).start()
        scrim.animate().alpha(0f).setDuration(SCRIM_OUT_MS).setInterpolator(DecelerateInterpolator()).withEndAction {
            runCatching { wm.removeView(frame) }
            root = null
            closing = false
            releaseTask()
            then?.invoke()
        }.start()
    }

    /** The screen went off: close at once. */
    fun closeNow() {
        val frame = root ?: return
        hideKeyboard()
        runCatching { wm.removeView(frame) }
        root = null
        closing = false
        releaseTask()
        bubble.closed()
    }

    private fun animateIn() {
        val (bx, by) = bubble.center()
        val at = IntArray(2).also { card.getLocationOnScreen(it) }
        card.pivotX = (bx - at[0]).toFloat() // grows out of the bubble
        card.pivotY = (by - at[1]).toFloat()
        card.scaleX = 0.1f
        card.scaleY = 0.1f
        card.alpha = 0f
        column.alpha = 1f
        dock()
        scrim.animate().alpha(1f).setDuration(SCRIM_IN_MS).setInterpolator(DecelerateInterpolator(1.6f)).start()
        card.animate().alpha(1f).setDuration(140).start()
        for (p in listOf(DynamicAnimation.SCALE_X, DynamicAnimation.SCALE_Y)) {
            SpringAnimation(card, p, 1f).apply { spring.setStiffness(CARD_STIFFNESS).dampingRatio = CARD_DAMPING }.start()
        }
        pointer.alpha = 0f
        pointer.animate().alpha(1f).setStartDelay(120).setDuration(120).start()
        popHeads()
    }

    private fun popHeads() {
        val views = (0 until headList.childCount).map { headList.getChildAt(it) } + newChat
        views.forEachIndexed { i, v ->
            v.scaleX = 0f
            v.scaleY = 0f
            v.postDelayed({ pop(v, 1f) }, 60L + i * 40L)
        }
    }

    private fun pop(v: View, to: Float) {
        for (p in listOf(DynamicAnimation.SCALE_X, DynamicAnimation.SCALE_Y)) {
            SpringAnimation(v, p, to).apply {
                spring.setStiffness(SpringForce.STIFFNESS_MEDIUM).dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
            }.start()
        }
    }

    // ---- where things go ---------------------------------------------------------------------------
    /** The row sits at the bubble's height (close to your finger), moved up only as far as the card needs. */
    private fun place() {
        val winH = params.height
        val rowH = dp(64)
        val pointerH = dp(10)
        val bottom = winH - maxOf(ime, navBar()) - dp(12)
        var cardH = (winH * CARD_SHARE).toInt()
        var top = (anchorY - rowH / 2).coerceAtLeast(dp(4))
        if (top + rowH + pointerH + cardH > bottom) top = maxOf(dp(4), bottom - rowH - pointerH - cardH)
        if (top + rowH + pointerH + cardH > bottom) cardH = maxOf(dp(180), bottom - top - rowH - pointerH)
        (column.layoutParams as FrameLayout.LayoutParams).topMargin = top
        card.layoutParams.height = cardH
        column.requestLayout()
    }

    private fun dock() {
        val at = IntArray(2).also { slot.getLocationOnScreen(it) }
        bubble.dockAt(at[0], at[1])
    }

    private fun placePointer() {
        val chatId = AgentRuntime.chats()?.current()?.id
        val head = (0 until headList.childCount).map { headList.getChildAt(it) }.firstOrNull { it.tag == chatId } ?: return
        val h = IntArray(2).also { head.getLocationOnScreen(it) }
        val c = IntArray(2).also { column.getLocationOnScreen(it) }
        pointer.animate().translationX((h[0] - c[0] + head.width / 2 - pointer.width / 2).toFloat()).setDuration(160).start()
    }

    private fun scrollToEnd() {
        scroll.post { scroll.scrollTo(0, maxOf(0, list.height - scroll.height)) }
    }

    // ---- content --------------------------------------------------------------------------------
    private fun bind(frame: View) {
        scrim = frame.findViewById(R.id.chat_scrim)
        scrim.setOnClickListener { minimize() } // tap outside minimizes
        column = frame.findViewById(R.id.chat_column)
        heads = frame.findViewById(R.id.chat_heads)
        headList = frame.findViewById(R.id.chat_head_list)
        slot = frame.findViewById(R.id.head_slot)
        newChat = frame.findViewById(R.id.chat_new)
        newChat.setOnClickListener { startNewChat() }
        pointer = frame.findViewById(R.id.chat_pointer)
        card = frame.findViewById(R.id.chat_root)
        scroll = frame.findViewById(R.id.chat_scroll)
        list = frame.findViewById(R.id.chat_list)
        title = frame.findViewById(R.id.chat_title)
        status = frame.findViewById(R.id.chat_status)
        controls = frame.findViewById(R.id.chat_controls)
        pause = frame.findViewById(R.id.chat_pause)
        input = frame.findViewById(R.id.chat_input)
        trash = frame.findViewById(R.id.chat_trash)
        frame.findViewById<View>(R.id.chat_avatar).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            clipToOutline = true
        }
        frame.findViewById<View>(R.id.chat_send).setOnClickListener { send(input.text.toString()) }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) { send(input.text.toString()); true } else false
        }
        pause.setOnClickListener {
            val host = AgentRuntime.host() ?: return@setOnClickListener
            pausedByOpening = false // the user decides now: closing leaves it as they set it
            host.setPaused("", !host.isPaused())
            bubble.changed()
            render()
        }
        frame.findViewById<View>(R.id.chat_stop).setOnClickListener { AgentRuntime.host()?.let { h -> h.running()?.let(h::stop) } }
        list.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scrollToEnd() } // always the latest message
        scroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scrollToEnd() }
        ViewCompat.setOnApplyWindowInsetsListener(frame) { _, insets ->
            val now = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            if (now != ime && !closing) { // the keyboard came or went: keep the card above it
                ime = now
                place()
                column.post { dock(); scrollToEnd() }
            }
            WindowInsetsCompat.CONSUMED
        }
    }

    /** Redraw the heads and the open chat (also called on every run update). */
    fun render() {
        if (root == null) return
        val store = AgentRuntime.chats() ?: return
        val host = AgentRuntime.host()
        val chat = store.current()
        val running = host?.running() != null
        val paused = host?.isPaused() == true
        renderHeads(store.chats().map { it.id to it.title }, chat.id)
        title.text = if (chat.lines.isEmpty()) "FastAutomate" else chat.title
        status.text = when {
            chat.id in thinking -> "Typing..."
            !running -> "Ready"
            paused -> "Task paused"
            else -> "Working on a task"
        }
        controls.visibility = if (running) View.VISIBLE else View.GONE
        pause.text = if (paused) "Resume" else "Pause"
        list.removeAllViews()
        val messages = ChatMessages.of(chat) { AgentRuntime.store()?.get(it) }
        if (messages.isEmpty()) list.addView(bubbleView(ChatMessage(false, HELLO, Tone.STEP)))
        messages.forEach { list.addView(bubbleView(it)) }
        if (chat.id in thinking) list.addView(bubbleView(ChatMessage(false, "···", Tone.STEP)))
        scrollToEnd()
        heads.post { placePointer() }
    }

    private fun renderHeads(chats: List<Pair<String, String>>, selected: String) {
        val known = (0 until headList.childCount).map { headList.getChildAt(it).tag }
        if (known == chats.map { it.first }) { // same chats: just mark the open one
            chats.forEachIndexed { i, (id, name) -> styleHead(headList.getChildAt(i) as TextView, i, id == selected, name) }
            return
        }
        headList.removeAllViews()
        chats.forEachIndexed { i, (id, name) ->
            val head = TextView(root!!.context).apply {
                tag = id
                gravity = Gravity.CENTER
                textSize = 15f
                setTextColor(Color.WHITE)
                setOnTouchListener(HeadDrag(id))
            }
            headList.addView(head, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(10) })
            styleHead(head, i, id == selected, name)
        }
    }

    private fun styleHead(head: TextView, index: Int, selected: Boolean, name: String) {
        head.text = initials(name)
        head.foreground = if (head.text.isEmpty()) service.getDrawable(R.drawable.fa_ic_chat) else null // a new, empty chat
        head.foregroundGravity = Gravity.CENTER
        head.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(HEAD_COLORS[index % HEAD_COLORS.size])
            if (selected) setStroke(dp(3), Color.WHITE)
        }
        head.alpha = if (selected) 1f else 0.8f
    }

    private fun bubbleView(msg: ChatMessage): View {
        val (bg, fg) = when (msg.tone) {
            Tone.USER -> service.getColor(R.color.mobilerun_primary) to Color.WHITE
            Tone.STEP -> service.getColor(R.color.fa_chat_step) to service.getColor(R.color.mobilerun_foreground)
            Tone.GOOD -> service.getColor(R.color.fa_chat_good) to service.getColor(R.color.fa_chat_good_ink)
            Tone.BAD -> service.getColor(R.color.fa_chat_bad) to service.getColor(R.color.fa_chat_bad_ink)
        }
        val text = TextView(root!!.context).apply {
            text = msg.text
            setTextColor(fg)
            textSize = 15f
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(bg) }
            maxWidth = (service.resources.displayMetrics.widthPixels * 0.68f).toInt()
            setTextIsSelectable(true)
        }
        return LinearLayout(root!!.context).apply {
            gravity = if (msg.fromUser) Gravity.END else Gravity.START
            setPadding(0, dp(3), 0, dp(3))
            addView(text)
        }
    }

    // ---- chats --------------------------------------------------------------------------------------
    private fun startNewChat() {
        AgentRuntime.chats()?.newChat()
        render()
        popHeads()
        input.text.clear()
        input.requestFocus()
        service.getSystemService(InputMethodManager::class.java).showSoftInput(input, 0)
    }

    private fun select(id: String) {
        AgentRuntime.chats()?.select(id)
        render()
    }

    private fun delete(id: String) {
        AgentRuntime.chats()?.delete(id)
        thinking -= id
        render()
    }

    /** You talk; the assistant answers, or hands a task to the agent and gets out of the way. */
    private fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        val store = AgentRuntime.chats() ?: return
        val chatId = store.current().id
        if (chatId in thinking) return
        store.add(chatId, store.line("user", text))
        input.text.clear()
        thinking += chatId
        render()
        Thread {
            val host = AgentRuntime.host()
            val reply = try {
                val brain: ChatBrain = host?.brain() ?: throw LlmError(NO_AI)
                val lines = store.chats().firstOrNull { it.id == chatId }?.lines.orEmpty()
                brain.reply(lines, { AgentRuntime.store()?.get(it) }, busy = host.running() != null)
            } catch (e: LlmError) {
                BrainReply.Say(e.message ?: NO_AI)
            } catch (e: Exception) {
                BrainReply.Say("Something went wrong: ${e.message}")
            }
            main.post { answered(chatId, reply) }
        }.start()
    }

    private fun answered(chatId: String, reply: BrainReply) {
        val store = AgentRuntime.chats() ?: return
        thinking -= chatId
        when (reply) {
            is BrainReply.Say -> store.add(chatId, store.line("assistant", reply.text))
            is BrainReply.Run -> {
                store.add(chatId, store.line("assistant", reply.say))
                render()
                // let them read the reply, then fold away so the task works on the app underneath
                main.postDelayed({ if (isOpen) minimize { startTask(chatId, reply.instruction) } else startTask(chatId, reply.instruction) }, READ_MS)
                return
            }
        }
        render()
    }

    private fun startTask(chatId: String, instruction: String) {
        main.postDelayed({
            Thread {
                val response = AgentRuntime.host()?.startLocal(instruction, null, null, leaveApp = false)
                    ?: ApiResponse.Error("The agent is not ready yet")
                main.post {
                    val store = AgentRuntime.chats() ?: return@post
                    val uuid = (response as? ApiResponse.RawObject)?.json?.optString("uuid").orEmpty()
                    if (response is ApiResponse.Error || uuid.isEmpty()) {
                        val why = (response as? ApiResponse.Error)?.message ?: "The task did not start"
                        store.add(chatId, store.line("assistant", why))
                        Toast.makeText(service, why, Toast.LENGTH_LONG).show()
                    } else {
                        store.add(chatId, store.line("task", instruction, run = uuid))
                    }
                    render()
                }
            }.start()
        }, SETTLE_MS)
    }

    // ---- the task while the chat covers the screen: hold it, then let it go on --------------------
    private fun holdTask() {
        val host = AgentRuntime.host() ?: return
        if (host.running() != null && !host.isPaused()) {
            host.setPaused("", true)
            pausedByOpening = true
            bubble.changed()
        }
    }

    private fun releaseTask() {
        val host = AgentRuntime.host() ?: return
        if (pausedByOpening && host.isPaused()) host.setPaused("", false)
        pausedByOpening = false
        bubble.changed()
    }

    private fun hideKeyboard() {
        runCatching { service.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0) }
    }

    // ---- drag a head down onto the X to delete that chat ------------------------------------------
    private inner class HeadDrag(private val chatId: String) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(service).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private var onTrash = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; dragging = false; onTrash = false
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && hypot(e.rawX - downX, e.rawY - downY) > slop) {
                        dragging = true
                        showTrash(true)
                    }
                    if (dragging) {
                        v.translationX = e.rawX - downX
                        v.translationY = e.rawY - downY
                        val t = IntArray(2).also { trash.getLocationOnScreen(it) }
                        val near = hypot(e.rawX - (t[0] + trash.width / 2), e.rawY - (t[1] + trash.height / 2)) < dp(90)
                        if (near != onTrash) {
                            onTrash = near
                            val s = if (near) 1.25f else 1f
                            trash.animate().scaleX(s).scaleY(s).setDuration(120).start()
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    showTrash(false)
                    when {
                        !dragging -> {
                            v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                            if (e.actionMasked == MotionEvent.ACTION_UP) select(chatId)
                        }
                        onTrash -> v.animate().scaleX(0f).scaleY(0f).setDuration(140).withEndAction { delete(chatId) }.start()
                        else -> { // not dropped on the X: spring back into the row
                            v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                            SpringAnimation(v, DynamicAnimation.TRANSLATION_X, 0f).apply { spring.dampingRatio = 0.6f }.start()
                            SpringAnimation(v, DynamicAnimation.TRANSLATION_Y, 0f).apply { spring.dampingRatio = 0.6f }.start()
                        }
                    }
                }
            }
            return true
        }

        private fun showTrash(show: Boolean) {
            trash.animate().alpha(if (show) 1f else 0f).scaleX(1f).scaleY(1f).setDuration(150).start()
        }
    }

    // ---- screen facts --------------------------------------------------------------------------------
    private fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            return b.width() to b.height()
        }
        val m = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    private fun systemDimen(name: String): Int {
        val id = service.resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) service.resources.getDimensionPixelSize(id) else 0
    }

    private fun statusBar() = systemDimen("status_bar_height")

    private fun navBar() = systemDimen("navigation_bar_height")

    private fun initials(name: String): String {
        if (name == "New chat") return ""
        return name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" }
    }

    companion object {
        private const val CARD_SHARE = 0.8f // of the screen below the status bar
        private const val CARD_STIFFNESS = 700f
        private const val CARD_DAMPING = 0.78f
        private const val CLOSE_MS = 170L
        private const val SCRIM_IN_MS = 380L
        private const val SCRIM_OUT_MS = 240L
        private const val READ_MS = 700L
        private const val SETTLE_MS = 250L
        private const val HELLO = "Hi! I can use this phone for you. What should I do?"
        private const val NO_AI = "I can't reach the AI yet: keep this phone connected to the dashboard for a moment."
        private val HEAD_COLORS = listOf("#12904F", "#3B7DD8", "#D8793B", "#8A4FD8", "#D84F7E").map(Color::parseColor)
    }
}
