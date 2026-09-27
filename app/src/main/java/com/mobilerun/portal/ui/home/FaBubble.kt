package com.mobilerun.portal.ui.home

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.mobilerun.portal.R
import com.mobilerun.portal.agent.AgentRuntime
import com.mobilerun.portal.agent.RunListener
import com.mobilerun.portal.agent.RunSpec
import com.mobilerun.portal.agent.RunStatus
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The round FastAutomate bubble, a Messenger-style chat head. Drawn by the accessibility service
 * (TYPE_ACCESSIBILITY_OVERLAY), so it needs no "display over other apps" permission. Moves on
 * springs like Messenger's (Rebound-style physics): flings to the nearest edge, pops in, and is
 * pulled onto the X when dragged near it. Tap = the chat (FaChat) grows out of it, right where it is;
 * tap again = the chat folds back in. While a task runs it turns red with a stop sign (tap = stop)
 * and a small grey pause/play button hangs under it. Hidden on the lock screen.
 */
class FaBubble private constructor(private val service: AccessibilityService) : RunListener, FaChat.Bubble {
    private val main = Handler(Looper.getMainLooper())
    private val wm = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val size = dp(60)
    private val ring = ProgressBar(service).apply {
        isIndeterminate = true
        indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#12904F"))
        visibility = View.GONE
    }
    private val faceBg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
    private val face = ImageView(service).apply { // the logo fills the circle, like a profile photo
        setImageResource(R.drawable.logo)
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = faceBg
        clipToOutline = true
        outlineProvider = ViewOutlineProvider.BACKGROUND
    }
    private val bubble = FrameLayout(service).apply {
        addView(ring, FrameLayout.LayoutParams(size, size))
        addView(face, FrameLayout.LayoutParams(size - dp(10), size - dp(10), Gravity.CENTER))
    }
    private val params = overlayParams(size, size).apply { x = 0; y = dp(220) }

    // while a task runs: a small grey pause/play button hangs under the red stop bubble
    private val pauseSize = dp(36)
    private val pauseBg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(PAUSED_GREY) }
    private val pauseButton = ImageView(service).apply {
        setImageResource(R.drawable.fa_ic_pause)
        scaleType = ImageView.ScaleType.CENTER
        background = pauseBg
        setOnClickListener { togglePause() }
    }
    private val pauseParams = overlayParams(pauseSize, pauseSize)
    private var pauseShown = false

    private val closeTarget = TextView(service).apply {
        text = "✕"
        textSize = 22f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#CC161F1A")) }
        alpha = 0f
    }
    private val closeParams = overlayParams(dp(72), dp(72)).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(56) }

    // springs move the window, like Messenger's chat heads
    private val springX = windowSpring { params.x = it }
    private val springY = windowSpring { params.y = it }

    private var shown = false
    private var closeShown = false
    private var runningUuid: String? = null
    private var hiddenForAgent = false
    private var parked: Pair<Int, Int>? = null // where the bubble was before the chat opened
    private var locked = false
    private var foregroundApp: String? = null
    private var inOwnApp = false // FastAutomate's own screens: the bubble stays out of the way there
    private val chat = FaChat(service, this)

    // the bubble's offset between window coordinates and the screen, for the agent's hit test
    @Volatile
    private var offset = 0 to 0

    private val screenWatch = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // screen off: gone at once (the lock may only kick in seconds later); unlocked: back
            locked = when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> true
                Intent.ACTION_USER_PRESENT -> false
                else -> isLocked() // screen on: still locked, or no lock at all
            }
            if (locked) chat.closeNow()
            refresh()
        }
    }

    private fun isLocked() = service.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
    private val restore = Runnable { setAgentHidden(false, now = true) }

    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun windowSpring(apply: (Int) -> Unit) = SpringAnimation(FloatValueHolder()).apply {
        spring = SpringForce().setStiffness(STIFFNESS).setDampingRatio(DAMPING)
        addUpdateListener { _, value, _ ->
            apply(value.toInt())
            if (shown) runCatching { wm.updateViewLayout(bubble, params) }
            placePause()
        }
    }

    private fun springTo(x: Int, y: Int, vx: Float = 0f, vy: Float = 0f) {
        springX.setStartValue(params.x.toFloat()).setStartVelocity(vx).animateToFinalPosition(x.toFloat())
        springY.setStartValue(params.y.toFloat()).setStartVelocity(vy).animateToFinalPosition(y.toFloat())
    }

    // ---- showing ---------------------------------------------------------------------------
    fun refresh() = main.post {
        val want = enabled(service) && !locked // never on the lock screen
        if (want && !shown) {
            runCatching { wm.addView(bubble, params) }.onSuccess {
                shown = true
                bubble.scaleX = 0f
                bubble.scaleY = 0f
                pop(bubble) // pops in
                bubble.post { measureOffset() }
            }
            bubble.setOnTouchListener(DragHandler())
        } else if (!want && shown) {
            chat.closeNow()
            runCatching { wm.removeView(bubble) }
            shown = false
        }
        render() // also adds/removes the pause button
    }

    private fun pop(view: View) {
        for (p in listOf(DynamicAnimation.SCALE_X, DynamicAnimation.SCALE_Y)) {
            SpringAnimation(view, p, 1f).apply {
                spring.setStiffness(SpringForce.STIFFNESS_MEDIUM).dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
            }.start()
        }
    }

    private fun render() {
        val running = runningUuid != null
        val paused = AgentRuntime.host()?.isPaused() == true
        ring.visibility = if (running && !paused) View.VISIBLE else View.GONE
        if (running) { // red, with a stop sign: tap it to stop
            face.setImageResource(R.drawable.fa_ic_stop)
            face.scaleType = ImageView.ScaleType.CENTER
            faceBg.setColor(RED)
            faceBg.setStroke(0, Color.TRANSPARENT)
            if (paused) faceBg.setColor(PAUSED_GREY) // paused: the whole bubble says so, not just the small button
        } else {
            face.setImageResource(R.drawable.logo)
            face.scaleType = ImageView.ScaleType.CENTER_CROP
            faceBg.setColor(Color.WHITE)
            faceBg.setStroke(dp(1), OUTLINE) // a hairline, so the white bubble shows on white screens
        }
        pauseBg.setColor(if (paused) GREEN else PAUSED_GREY)
        pauseButton.setImageResource(if (paused) R.drawable.fa_ic_play else R.drawable.fa_ic_pause)
        val wantPause = running && shown && parked == null
        if (wantPause && !pauseShown) {
            placePause(update = false)
            runCatching { wm.addView(pauseButton, pauseParams) }.onSuccess { pauseShown = true }
        } else if (!wantPause && pauseShown) {
            runCatching { wm.removeView(pauseButton) }
            pauseShown = false
        }
    }

    private fun placePause(update: Boolean = true) {
        pauseParams.x = params.x + (size - pauseSize) / 2
        pauseParams.y = params.y + size + dp(12) // a clear gap, so Stop and Pause are not mixed up
        if (update && pauseShown) runCatching { wm.updateViewLayout(pauseButton, pauseParams) }
    }

    private fun togglePause() {
        val host = AgentRuntime.host() ?: return
        host.setPaused("", !host.isPaused())
        render()
    }

    private fun stopRun() {
        val uuid = runningUuid ?: return
        AgentRuntime.host()?.stop(uuid)
        bubble.animate().scaleX(0.8f).scaleY(0.8f).setDuration(90).withEndAction { pop(bubble) }.start()
        Toast.makeText(service, "Stopping...", Toast.LENGTH_SHORT).show()
    }

    // ---- the chat ------------------------------------------------------------------------------
    private fun onTap() {
        if (runningUuid != null && parked == null) { // the red bubble: tap to stop
            stopRun()
            return
        }
        if (parked != null) { // open: tapping the bubble again minimizes it, like Messenger
            chat.minimize()
            return
        }
        openChat()
    }

    private fun openChat() {
        springX.cancel()
        springY.cancel()
        parked = params.x to params.y
        applyOwnApp()
        render() // the pause button steps away while the chat is open
        chat.open() // the chat grows out of the bubble and moves it onto its slot
    }

    /** Hidden over FastAutomate's own screens (the app has its own Chats), unless the chat is open there. */
    private fun applyOwnApp() {
        if (!shown || hiddenForAgent) return
        val hide = inOwnApp && parked == null
        bubble.animate().alpha(if (hide) 0f else 1f).setDuration(150).start()
        val flags = if (hide) params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        if (flags != params.flags) {
            params.flags = flags
            runCatching { wm.updateViewLayout(bubble, params) }
        }
    }

    private fun measureOffset() {
        val now = IntArray(2).also { bubble.getLocationOnScreen(it) }
        offset = (now[0] - params.x) to (now[1] - params.y)
    }

    // ---- what the chat needs from the bubble ---------------------------------------------------
    override fun center(): Pair<Int, Int> = (params.x + offset.first + size / 2) to (params.y + offset.second + size / 2)

    override fun home(): Pair<Int, Int> {
        val (x, y) = parked ?: (params.x to params.y)
        return (x + offset.first + size / 2) to (y + offset.second + size / 2)
    }

    /** Sit exactly on the chat's head slot (screen coordinates). */
    override fun dockAt(screenX: Int, screenY: Int) {
        measureOffset()
        springTo(screenX - offset.first, screenY - offset.second)
    }

    /** The chat window was just added above: put the bubble back on top of it. */
    override fun raise() {
        if (!shown) return
        runCatching { wm.removeView(bubble) }
        runCatching { wm.addView(bubble, params) }
    }

    override fun closed() {
        val (x, y) = parked ?: return
        parked = null
        springTo(x, y)
        main.postDelayed({ applyOwnApp() }, 250)
        main.postDelayed({ render() }, 200) // the pause button comes back once the bubble is home
    }

    override fun changed() {
        main.post { render() }
    }

    /** For the agent (any thread): would a touch at this screen point land on the bubble or its pause button? */
    fun covers(x: Int, y: Int): Boolean {
        if (!shown) return false
        val pad = dp(TOUCH_PAD_DP)
        val (ox, oy) = offset
        val onBubble = x in params.x + ox - pad..params.x + ox + size + pad && y in params.y + oy - pad..params.y + oy + size + pad
        val onPause = pauseShown &&
            x in pauseParams.x + ox - pad..pauseParams.x + ox + pauseSize + pad && y in pauseParams.y + oy - pad..pauseParams.y + oy + pauseSize + pad
        return onBubble || onPause
    }

    /** Called from the agent's thread: hide before it looks or touches, come back a moment after. */
    fun setAgentHidden(hidden: Boolean, now: Boolean = false) {
        if (hidden) {
            val done = CountDownLatch(1)
            main.post {
                main.removeCallbacks(restore)
                if (shown && !hiddenForAgent) {
                    hiddenForAgent = true
                    bubble.visibility = View.INVISIBLE
                    params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    runCatching { wm.updateViewLayout(bubble, params) }
                    pauseButton.visibility = View.INVISIBLE
                    pauseParams.flags = pauseParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    if (pauseShown) runCatching { wm.updateViewLayout(pauseButton, pauseParams) }
                }
                done.countDown()
            }
            done.await(500, TimeUnit.MILLISECONDS)
            Thread.sleep(FRAME_MS) // let the screen redraw without it
        } else if (now) {
            if (!hiddenForAgent) return
            hiddenForAgent = false
            bubble.visibility = View.VISIBLE
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            runCatching { wm.updateViewLayout(bubble, params) }
            pauseButton.visibility = View.VISIBLE
            pauseParams.flags = pauseParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            if (pauseShown) runCatching { wm.updateViewLayout(pauseButton, pauseParams) }
            applyOwnApp()
        } else {
            main.postDelayed(restore, RESTORE_MS) // after the gesture has landed
        }
    }

    // ---- run updates -----------------------------------------------------------------------
    override fun started(spec: RunSpec, origin: String) = main.post { runningUuid = spec.uuid; render(); chat.render() }.let {}

    override fun event(uuid: String, kind: String, text: String, steps: Int?) = main.post { render(); chat.render() }.let {}

    override fun finished(uuid: String, status: RunStatus, result: String, steps: Int, shot: String?) = main.post {
        runningUuid = null
        render()
        chat.render()
        val fromChat = AgentRuntime.chats()?.chatOfRun(uuid) != null // the chat checks it and answers (onChatAnswer)
        if (!chat.isOpen && !fromChat) { // the chat is closed: say how it went
            val label = when (status) {
                RunStatus.SUCCEEDED -> "Done"
                RunStatus.STOPPED -> "Stopped"
                RunStatus.FAILED -> "Failed"
            }
            val text = if (status == RunStatus.STOPPED || result.isBlank()) label else "$label: ${result.take(120)}"
            Toast.makeText(service, text, Toast.LENGTH_LONG).show()
        }
    }.let {}

    // ---- dragging: follow the finger, fling to an edge, or get pulled onto the X --------------
    private inner class DragHandler : View.OnTouchListener {
        private val slop = ViewConfiguration.get(service).scaledTouchSlop
        private var velocity: VelocityTracker? = null
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var magnet = false
        private var held = false // long-pressed: while a task runs this opens the chat instead of stopping
        private val hold = Runnable {
            if (!dragging && runningUuid != null && parked == null) {
                held = true
                bubble.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                openChat()
            }
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val tracker = velocity ?: VelocityTracker.obtain().also { velocity = it }
            tracker.addMovement(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    springX.cancel()
                    springY.cancel()
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y
                    dragging = false
                    magnet = false
                    held = false
                    main.postDelayed(hold, LONG_PRESS_MS)
                    bubble.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start() // picked up
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && parked == null && hypot(e.rawX - downX, e.rawY - downY) > slop) {
                        dragging = true
                        main.removeCallbacks(hold)
                        showClose(true)
                    }
                    if (dragging) follow(e)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(hold)
                    if (held) { // the long-press already opened the chat
                        tracker.recycle()
                        velocity = null
                        return true
                    }
                    bubble.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    tracker.computeCurrentVelocity(1000)
                    val vx = tracker.xVelocity
                    val vy = tracker.yVelocity
                    tracker.recycle()
                    velocity = null
                    showClose(false)
                    when {
                        !dragging -> if (e.actionMasked == MotionEvent.ACTION_UP) onTap()
                        magnet -> dismiss()
                        else -> fling(vx, vy)
                    }
                }
            }
            return true
        }

        private fun follow(e: MotionEvent) {
            val m = service.resources.displayMetrics
            val nearClose = abs(e.rawX - m.widthPixels / 2f) < dp(90) && m.heightPixels - e.rawY < dp(200)
            if (nearClose != magnet) {
                magnet = nearClose
                val scale = if (magnet) 1.25f else 1f
                closeTarget.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
            }
            if (magnet) { // pulled onto the X
                val target = IntArray(2).also { closeTarget.getLocationOnScreen(it) }
                val now = IntArray(2).also { bubble.getLocationOnScreen(it) }
                val cx = target[0] + closeTarget.width / 2 - size / 2
                val cy = target[1] + closeTarget.height / 2 - size / 2
                springTo(params.x + (cx - now[0]), params.y + (cy - now[1]))
            } else {
                springX.cancel()
                springY.cancel()
                params.x = startX + (e.rawX - downX).toInt()
                params.y = startY + (e.rawY - downY).toInt()
                runCatching { wm.updateViewLayout(bubble, params) }
                placePause()
            }
        }

        private fun fling(vx: Float, vy: Float) {
            val m = service.resources.displayMetrics
            val goRight = if (abs(vx) > FLING_PX_S) vx > 0 else params.x + size / 2 > m.widthPixels / 2
            val x = if (goRight) m.widthPixels - size else 0
            val y = (params.y + vy * FLING_CARRY).toInt().coerceIn(dp(24), m.heightPixels - size - dp(120))
            springTo(x, y, vx, vy)
        }

        private fun dismiss() {
            bubble.animate().scaleX(0f).scaleY(0f).setDuration(160).withEndAction {
                springX.cancel() // the pull onto the X must not move it after this
                springY.cancel()
                setEnabled(service, false)
                bubble.scaleX = 1f
                bubble.scaleY = 1f
                params.x = 0 // turned back on later: it comes back at the left edge, a third down
                params.y = service.resources.displayMetrics.heightPixels / 3
            }.start()
            Toast.makeText(service, "Bubble hidden. Turn it back on in FastAutomate > Settings.", Toast.LENGTH_LONG).show()
        }

        private fun showClose(show: Boolean) {
            if (show && !closeShown) {
                runCatching { wm.addView(closeTarget, closeParams) }.onSuccess { closeShown = true }
                closeTarget.scaleX = 0.6f
                closeTarget.scaleY = 0.6f
                closeTarget.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).start()
            } else if (!show && closeShown) {
                closeTarget.animate().alpha(0f).setDuration(120).withEndAction {
                    runCatching { wm.removeView(closeTarget) }
                    closeShown = false
                }.start()
            }
        }
    }

    companion object {
        private const val PREFS = "fa_v2"
        private const val KEY = "bubble"
        private const val FRAME_MS = 60L
        private const val RESTORE_MS = 600L
        private const val STIFFNESS = 380f // a soft spring with a little bounce, like Messenger's chat heads
        private const val DAMPING = 0.68f
        private const val FLING_PX_S = 900f
        private const val FLING_CARRY = 0.12f // how far a vertical fling carries the bubble
        private const val TOUCH_PAD_DP = 12
        private const val LONG_PRESS_MS = 450L
        private val PAUSED_GREY = Color.parseColor("#6E7A72")
        private val GREEN = Color.parseColor("#12904F")
        private val OUTLINE = Color.parseColor("#26000000")
        private val RED = Color.parseColor("#E0463A")

        @Volatile
        private var instance: FaBubble? = null

        fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

        fun setEnabled(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
            instance?.refresh()
        }

        /** The accessibility service came up: show the bubble (if wanted) and follow the agent's runs. */
        fun attach(service: AccessibilityService) {
            detach()
            val bubble = FaBubble(service)
            instance = bubble
            AgentRuntime.host()?.addListener(bubble)
            AgentRuntime.overlayHider = { hidden -> bubble.setAgentHidden(hidden) }
            AgentRuntime.overlayCovers = { x, y -> bubble.covers(x, y) }
            AgentRuntime.onChatChange = { bubble.main.post { bubble.render(); bubble.chat.render() } }
            AgentRuntime.onChatAnswer = { _, text ->
                bubble.main.post { if (!bubble.chat.isOpen) Toast.makeText(bubble.service, text.take(160), Toast.LENGTH_LONG).show() }
            }
            bubble.locked = bubble.isLocked()
            bubble.inOwnApp = appOpen
            service.registerReceiver(
                bubble.screenWatch,
                IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) },
            )
            AgentRuntime.host()?.running()?.let { uuid -> bubble.main.post { bubble.runningUuid = uuid; bubble.render() } }
            bubble.refresh()
        }

        /** From the app's Chats screen: open this chat in the bubble (null = a new chat). False when there is no bubble. */
        fun openChat(chatId: String?): Boolean {
            val b = instance ?: return false
            if (!enabled(b.service)) return false
            b.main.post {
                b.chat.show(chatId)
                if (b.shown && !b.chat.isOpen && b.parked == null) b.openChat()
            }
            return true
        }

        /** The foreground app changed (Home, Recents, another app): fold the chat away like Messenger. */
        fun foreground(packageName: String) {
            val b = instance ?: return
            // our own windows (the chat itself) can show up as "foreground": they are not an app switch
            if (packageName == b.service.packageName) return
            b.main.post {
                val before = b.foregroundApp
                b.foregroundApp = packageName
                if (before != null && before != packageName && b.chat.isOpen) b.chat.minimize()
            }
        }

        /** One of FastAutomate's own screens is open (or not): the bubble keeps out of the way there. */
        fun appVisible(visible: Boolean) {
            appOpen = visible
            instance?.let { b -> b.main.post { b.inOwnApp = visible; b.applyOwnApp() } }
        }

        @Volatile
        private var appOpen = false

        fun detach() {
            val bubble = instance ?: return
            instance = null
            AgentRuntime.overlayHider = {}
            AgentRuntime.overlayCovers = { _, _ -> false }
            AgentRuntime.host()?.removeListener(bubble)
            runCatching { bubble.service.unregisterReceiver(bubble.screenWatch) }
            bubble.main.post {
                bubble.chat.closeNow()
                if (bubble.shown) runCatching { bubble.wm.removeView(bubble.bubble) }
                if (bubble.pauseShown) runCatching { bubble.wm.removeView(bubble.pauseButton) }
                bubble.shown = false
                bubble.pauseShown = false
            }
        }
    }
}
