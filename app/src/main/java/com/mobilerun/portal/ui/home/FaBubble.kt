package com.mobilerun.portal.ui.home

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
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
 * The round FastAutomate bubble, like a chat head. Drawn by the accessibility service
 * (TYPE_ACCESSIBILITY_OVERLAY), so it needs no "display over other apps" permission.
 * Looks like a Messenger chat head: tap = it jumps to the top and the chat panel opens under it
 * (ChatPanelActivity). Running: a green ring spins around it. Drag onto the X to hide it.
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
    }
    private val bubble = FrameLayout(service).apply {
        addView(ring, FrameLayout.LayoutParams(size, size))
        addView(ImageView(service).apply { // the logo fills the circle, like a profile photo
            setImageResource(R.drawable.logo)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            elevation = dp(8).toFloat()
        }, FrameLayout.LayoutParams(size - dp(10), size - dp(10), Gravity.CENTER))
        addView(badge, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(4), dp(4)) })
        badge.elevation = dp(9).toFloat()
    }
    private val bubbleParams = overlayParams(size, size).apply { x = 0; y = dp(220) }
    private var parked: Pair<Int, Int>? = null // where the bubble was before the chat opened

    private val closeTarget = TextView(service).apply {
        text = "✕"
        textSize = 22f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#CC161F1A")) }
    }
    private val closeParams = overlayParams(dp(64), dp(64)).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(48) }

    private var shown = false
    private var closeShown = false
    private var runningUuid: String? = null
    private var hiddenForAgent = false
    private val restore = Runnable { setAgentHidden(false, now = true) }

    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    // ---- showing ---------------------------------------------------------------------------
    fun refresh() = main.post {
        val want = enabled(service)
        if (want && !shown) {
            runCatching { wm.addView(bubble, bubbleParams) }.onSuccess { shown = true }
            bubble.setOnTouchListener(DragHandler())
        } else if (!want && shown) {
            runCatching { wm.removeView(bubble) }
            shown = false
        }
        render()
    }

    private fun render() {
        val running = runningUuid != null
        val paused = AgentRuntime.host()?.isPaused() == true
        ring.visibility = if (running && !paused) View.VISIBLE else View.GONE
        (badge.background as GradientDrawable).setColor(if (paused) AMBER else GREEN)
    }

    // ---- the chat panel ------------------------------------------------------------------------
    private fun onTap() {
        if (parked != null) return // already open
        parked = bubbleParams.x to bubbleParams.y
        val m = service.resources.displayMetrics
        moveTo(m.widthPixels - size - dp(12), dp(36)) // to the top, like Messenger
        service.startActivity(Intent(service, ChatPanelActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun chatClosed() {
        val (x, y) = parked ?: return
        parked = null
        moveTo(x, y)
    }

    private fun moveTo(x: Int, y: Int) {
        ValueAnimator.ofFloat(0f, 1f).apply {
            val fromX = bubbleParams.x
            val fromY = bubbleParams.y
            duration = 200
            addUpdateListener {
                val f = it.animatedValue as Float
                bubbleParams.x = (fromX + (x - fromX) * f).toInt()
                bubbleParams.y = (fromY + (y - fromY) * f).toInt()
                if (shown) runCatching { wm.updateViewLayout(bubble, bubbleParams) }
            }
        }.start()
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
                    bubbleParams.flags = bubbleParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    runCatching { wm.updateViewLayout(bubble, bubbleParams) }
                }
                done.countDown()
            }
            done.await(500, TimeUnit.MILLISECONDS)
            Thread.sleep(FRAME_MS) // let the screen redraw without it
        } else if (now) {
            if (!hiddenForAgent) return
            hiddenForAgent = false
            bubble.visibility = View.VISIBLE
            bubbleParams.flags = bubbleParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            runCatching { wm.updateViewLayout(bubble, bubbleParams) }
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

    // ---- dragging --------------------------------------------------------------------------
    private inner class DragHandler : View.OnTouchListener {
        private val slop = ViewConfiguration.get(service).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = bubbleParams.x; startY = bubbleParams.y; dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && hypot(e.rawX - downX, e.rawY - downY) > slop) {
                        dragging = true
                        if (!closeShown) runCatching { wm.addView(closeTarget, closeParams) }.onSuccess { closeShown = true }
                    }
                    if (dragging) {
                        bubbleParams.x = startX + (e.rawX - downX).toInt()
                        bubbleParams.y = startY + (e.rawY - downY).toInt()
                        runCatching { wm.updateViewLayout(bubble, bubbleParams) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (closeShown) runCatching { wm.removeView(closeTarget) }.also { closeShown = false }
                    if (!dragging) {
                        if (e.actionMasked == MotionEvent.ACTION_UP) onTap()
                    } else if (overClose(e)) {
                        setEnabled(service, false)
                        Toast.makeText(service, "Bubble hidden. Turn it back on in FastAutomate > Settings.", Toast.LENGTH_LONG).show()
                    } else {
                        snapToEdge()
                    }
                }
            }
            return true
        }

        private fun overClose(e: MotionEvent): Boolean {
            val m = service.resources.displayMetrics
            return abs(e.rawX - m.widthPixels / 2f) < dp(70) && m.heightPixels - e.rawY < dp(170)
        }

        private fun snapToEdge() {
            val screenW = service.resources.displayMetrics.widthPixels
            val target = if (bubbleParams.x + size / 2 > screenW / 2) screenW - size else 0
            ValueAnimator.ofInt(bubbleParams.x, target).apply {
                duration = 180
                addUpdateListener {
                    bubbleParams.x = it.animatedValue as Int
                    runCatching { wm.updateViewLayout(bubble, bubbleParams) }
                }
            }.start()
        }
    }

    companion object {
        private const val PREFS = "fa_v2"
        private const val KEY = "bubble"
        private const val FRAME_MS = 60L
        private const val RESTORE_MS = 600L
        private val GREEN = Color.parseColor("#43C57F")
        private val AMBER = Color.parseColor("#E0B45A")

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
                bubble.shown = false
            }
        }

        /** The chat panel closed: the bubble goes back where it was. */
        fun chatClosed() {
            instance?.let { b -> b.main.post { b.chatClosed() } }
        }

        /** Pause/resume from the chat: update the ring and the dot. */
        fun changed() {
            instance?.let { b -> b.main.post { b.render() } }
        }

    }
}
