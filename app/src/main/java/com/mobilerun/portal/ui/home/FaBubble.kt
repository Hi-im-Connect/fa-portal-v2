package com.mobilerun.portal.ui.home

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
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
 * pulled onto the X when dragged near it. Tap = it springs to the top and the chat opens under it
 * (ChatPanelActivity); tap again = the chat minimizes. While a task runs it turns red with a stop
 * sign (tap = stop) and a small grey pause/play button hangs under it.
 */
class FaBubble private constructor(private val service: AccessibilityService) : RunListener {
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
    private val badge = View(service).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(GREEN); setStroke(dp(2), Color.WHITE) }
        elevation = dp(9).toFloat()
    }
    private val faceBg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
    private val face = ImageView(service).apply { // the logo fills the circle, like a profile photo
        setImageResource(R.drawable.logo)
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = faceBg
        clipToOutline = true
        outlineProvider = ViewOutlineProvider.BACKGROUND
        elevation = dp(8).toFloat()
    }
    private val bubble = FrameLayout(service).apply {
        addView(ring, FrameLayout.LayoutParams(size, size))
        addView(face, FrameLayout.LayoutParams(size - dp(10), size - dp(10), Gravity.CENTER))
        addView(badge, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(4), dp(4)) })
    }
    private val params = overlayParams(size, size).apply { x = 0; y = dp(220) }

    // while a task runs: a small grey pause/play button hangs under the red stop bubble
    private val pauseSize = dp(36)
    private val pauseButton = ImageView(service).apply {
        setImageResource(R.drawable.fa_ic_pause)
        scaleType = ImageView.ScaleType.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#6E7A72")) }
        elevation = dp(6).toFloat()
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
        val want = enabled(service)
        if (want && !shown) {
            runCatching { wm.addView(bubble, params) }.onSuccess {
                shown = true
                bubble.scaleX = 0f
                bubble.scaleY = 0f
                pop(bubble) // pops in
            }
            bubble.setOnTouchListener(DragHandler())
        } else if (!want && shown) {
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
        badge.visibility = if (running) View.GONE else View.VISIBLE
        if (running) { // red, with a stop sign: tap it to stop
            face.setImageResource(R.drawable.fa_ic_stop)
            face.scaleType = ImageView.ScaleType.CENTER
            faceBg.setColor(RED)
        } else {
            face.setImageResource(R.drawable.logo)
            face.scaleType = ImageView.ScaleType.CENTER_CROP
            faceBg.setColor(Color.WHITE)
        }
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
        pauseParams.y = params.y + size + dp(4)
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
            ChatPanelActivity.minimize()
            return
        }
        parked = params.x to params.y
        val m = service.resources.displayMetrics
        springTo(m.widthPixels - size - dp(12), dp(24)) // up to the top; the chat then moves it onto its slot
        service.startActivity(Intent(service, ChatPanelActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** The chat asks the bubble to sit exactly on its head slot (screen coordinates). */
    private fun dockAt(screenX: Int, screenY: Int) {
        val now = IntArray(2).also { bubble.getLocationOnScreen(it) }
        val offsetX = now[0] - params.x
        val offsetY = now[1] - params.y
        springTo(screenX - offsetX, screenY - offsetY)
    }

    private fun chatClosed() {
        val (x, y) = parked ?: return
        parked = null
        springTo(x, y)
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
        } else {
            main.postDelayed(restore, RESTORE_MS) // after the gesture has landed
        }
    }

    // ---- run updates -----------------------------------------------------------------------
    override fun started(spec: RunSpec, origin: String) = main.post { runningUuid = spec.uuid; render() }.let {}

    override fun event(uuid: String, kind: String, text: String, steps: Int?) = main.post { render() }.let {}

    override fun finished(uuid: String, status: RunStatus, result: String, steps: Int, shot: String?) = main.post {
        runningUuid = null
        render()
        if (parked == null) { // the chat is closed: say how it went
            val label = when (status) {
                RunStatus.SUCCEEDED -> "Done"
                RunStatus.STOPPED -> "Stopped"
                RunStatus.FAILED -> "Failed"
            }
            Toast.makeText(service, "$label: ${result.take(120)}", Toast.LENGTH_LONG).show()
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
                    bubble.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start() // picked up
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && parked == null && hypot(e.rawX - downX, e.rawY - downY) > slop) {
                        dragging = true
                        showClose(true)
                    }
                    if (dragging) follow(e)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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
                setEnabled(service, false)
                bubble.scaleX = 1f
                bubble.scaleY = 1f
                params.x = 0
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
        private val GREEN = Color.parseColor("#43C57F")
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
            AgentRuntime.host()?.running()?.let { uuid -> bubble.main.post { bubble.runningUuid = uuid; bubble.render() } }
            bubble.refresh()
        }

        fun detach() {
            val bubble = instance ?: return
            instance = null
            AgentRuntime.overlayHider = {}
            AgentRuntime.host()?.removeListener(bubble)
            bubble.main.post {
                if (bubble.shown) runCatching { bubble.wm.removeView(bubble.bubble) }
                if (bubble.pauseShown) runCatching { bubble.wm.removeView(bubble.pauseButton) }
                bubble.shown = false
                bubble.pauseShown = false
            }
        }

        /** The chat's head slot is at this screen point: the bubble springs onto it. */
        fun dockAt(screenX: Int, screenY: Int) {
            instance?.let { b -> b.main.post { b.dockAt(screenX, screenY) } }
        }

        /** The chat closed: the bubble springs back where it was. */
        fun chatClosed() {
            instance?.let { b -> b.main.post { b.chatClosed() } }
        }

        /** Pause/resume from the chat: update the ring and the dot. */
        fun changed() {
            instance?.let { b -> b.main.post { b.render() } }
        }
    }
}
