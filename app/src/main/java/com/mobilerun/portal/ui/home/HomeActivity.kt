package com.mobilerun.portal.ui.home

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.mobilerun.portal.R
import com.mobilerun.portal.agent.AgentRuntime
import com.mobilerun.portal.agent.RunListener
import com.mobilerun.portal.agent.RunRecord
import com.mobilerun.portal.agent.RunSpec
import com.mobilerun.portal.agent.RunStatus
import com.mobilerun.portal.api.ApiResponse
import com.mobilerun.portal.config.ConfigManager
import com.mobilerun.portal.databinding.ActivityHomeBinding
import com.mobilerun.portal.service.MobilerunAccessibilityService
import com.mobilerun.portal.state.ConnectionState
import com.mobilerun.portal.state.ConnectionStateManager
import com.mobilerun.portal.ui.MainActivity
import com.mobilerun.portal.ui.taskprompt.TaskDetailsActivity

/** FastAutomate v2's everyday screen: setup, run a task, watch it; the task list; a few settings. */
class HomeActivity : AppCompatActivity(), RunListener {
    private lateinit var b: ActivityHomeBinding
    private val main = Handler(Looper.getMainLooper())
    private val tried = mutableSetOf<Step>() // setup steps already opened for the user this visit

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(b.root)
        handleLink(intent)
        FaConnect.connectFromInstall(this) // an invite download links itself on first launch

        b.nav.setOnItemSelectedListener { item -> show(item.itemId); true }
        b.btnRun.setOnClickListener { runTask() }
        b.btnPause.setOnClickListener { AgentRuntime.host()?.let { it.setPaused("", !it.isPaused()) }; refreshLive() }
        b.btnStop.setOnClickListener { AgentRuntime.host()?.running()?.let { AgentRuntime.host()?.stop(it) } }
        b.btnReconnect.setOnClickListener { FaConnect.reconnect(this); toast("Reconnecting...") }
        b.btnAdvanced.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }
        b.switchBubble.isChecked = FaBubble.enabled(this)
        b.switchBubble.setOnCheckedChangeListener { _, on -> FaBubble.setEnabled(this, on) }
        ConnectionStateManager.connectionState.observe(this) { renderConnection(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    override fun onStart() {
        super.onStart()
        AgentRuntime.host()?.addListener(this)
    }

    override fun onStop() {
        AgentRuntime.host()?.removeListener(this)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshSetup(auto = true)
        refreshLive()
        refreshTasks()
        refreshSettings()
    }

    // ---- links ------------------------------------------------------------------------------
    private fun handleLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "fastautomate2" && data.host == "connect") {
            val ok = FaConnect.connect(this, data.getQueryParameter("token"), data.getQueryParameter("url"))
            toast(if (ok) "Connecting to FastAutomate..." else "This connect link is not valid")
        }
    }

    private fun show(tab: Int) {
        b.pageHome.visibility = if (tab == R.id.tab_home) View.VISIBLE else View.GONE
        b.pageTasks.visibility = if (tab == R.id.tab_tasks) View.VISIBLE else View.GONE
        b.pageSettings.visibility = if (tab == R.id.tab_settings) View.VISIBLE else View.GONE
        if (tab == R.id.tab_tasks) refreshTasks()
    }

    // ---- setup ------------------------------------------------------------------------------
    private fun state() = SetupState(
        linked = FaConnect.isLinked(this),
        notifications = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
        accessibility = MobilerunAccessibilityService.getInstance() != null,
    )

    private fun refreshSetup(auto: Boolean) {
        val state = state()
        val missing = SetupSteps.missing(state)
        b.setupCard.visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE
        b.setupRows.removeAllViews()
        for (step in Step.values()) b.setupRows.addView(setupRow(step, step !in missing))
        if (auto) SetupSteps.nextAuto(state, tried)?.let { next -> tried += next; main.postDelayed({ open(next) }, AUTO_DELAY_MS) }
    }

    private fun setupRow(step: Step, done: Boolean): View {
        val (title, hint) = when (step) {
            Step.NOTIFICATIONS -> "Notifications" to "So you can see when a task is running."
            Step.BATTERY -> "Keep running" to "So Android does not stop FastAutomate in the background."
            Step.ACCESSIBILITY -> "Control the screen" to
                "Switch on FastAutomate v2. If Android says \"Restricted setting\": tap App info, then ⋮ > Allow restricted settings."
            Step.DASHBOARD -> "Dashboard" to "Open your invite link once to connect this phone."
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(10)) }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply { text = title; textSize = 15f; setTextColor(getColor(R.color.mobilerun_foreground)); paint.isFakeBoldText = true })
        if (!done) texts.addView(TextView(this).apply { text = hint; textSize = 13f; setTextColor(getColor(R.color.mobilerun_muted_foreground)) })
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        when {
            done -> row.addView(TextView(this).apply { text = "✓"; textSize = 20f; setTextColor(getColor(R.color.mobilerun_primary)) })
            step == Step.DASHBOARD -> Unit
            else -> {
                val buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                buttons.addView(smallButton("Turn on") { open(step) })
                if (step == Step.ACCESSIBILITY) buttons.addView(smallButton("App info") { openAppInfo() })
                row.addView(buttons)
            }
        }
        return row
    }

    private fun smallButton(label: String, onClick: () -> Unit) = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        cornerRadius = dp(12)
        backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.mobilerun_primary))
        setOnClickListener { onClick() }
    }

    private fun open(step: Step) {
        when (step) {
            Step.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
            Step.BATTERY -> start(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
            Step.ACCESSIBILITY -> {
                val component = ComponentName(this, MobilerunAccessibilityService::class.java).flattenToString()
                start(
                    Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME, component),
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                )
                toast("Switch on FastAutomate v2")
            }
            Step.DASHBOARD -> Unit
        }
    }

    private fun openAppInfo() = start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

    private fun start(vararg intents: Intent) {
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshSetup(auto = true) // go on to the next switch
    }

    // ---- running ----------------------------------------------------------------------------
    private fun runTask() {
        val text = b.taskInput.text.toString().trim()
        if (text.isEmpty()) return toast("Write what the phone should do")
        b.btnRun.isEnabled = false
        Thread {
            val response = AgentRuntime.host()?.startLocal(text, null, null, leaveApp = true)
                ?: ApiResponse.Error("The agent is not ready yet")
            main.post {
                b.btnRun.isEnabled = true
                if (response is ApiResponse.Error) toast(response.message) else b.taskInput.text.clear()
                refreshLive()
            }
        }.start()
    }

    private fun refreshLive() {
        val host = AgentRuntime.host()
        val record = host?.running()?.let { AgentRuntime.store()?.get(it) }
        b.liveCard.visibility = if (record != null) View.VISIBLE else View.GONE
        b.runCard.visibility = if (record != null) View.GONE else View.VISIBLE
        if (record != null) {
            val paused = host.isPaused()
            b.liveLabel.text = if (paused) "Paused" else "Working on"
            b.liveTask.text = record.instruction
            b.liveStep.text = record.events.lastOrNull { it.kind != "phase" }?.text ?: "Starting..."
            b.liveProgress.visibility = if (paused) View.INVISIBLE else View.VISIBLE
            b.btnPause.text = if (paused) "Resume" else "Pause"
        }
        val last = AgentRuntime.store()?.page(1, 1)?.first?.firstOrNull()?.takeIf { it.status != "running" }
        b.lastResult.visibility = if (last != null) View.VISIBLE else View.GONE
        if (last != null) {
            val outcome = if (last.result.isBlank() || last.result == label(last.status)) label(last.status) else "${label(last.status)}: ${last.result.take(160)}"
            b.lastResult.text = "Last: ${last.instruction}\n$outcome"
        }
    }

    override fun started(spec: RunSpec, origin: String) { main.post { refreshLive(); refreshTasks() } }

    override fun event(uuid: String, kind: String, text: String, steps: Int?) { main.post { refreshLive() } }

    override fun finished(uuid: String, status: RunStatus, result: String, steps: Int, shot: String?) {
        main.post { refreshLive(); refreshTasks() }
    }

    // ---- tasks ------------------------------------------------------------------------------
    private fun refreshTasks() {
        val list = b.taskList
        list.removeAllViews()
        val records = AgentRuntime.store()?.page(1, 50)?.first.orEmpty()
        if (records.isEmpty()) {
            list.addView(TextView(this).apply { text = "No tasks yet. Run one from Home or the bubble."; setTextColor(getColor(R.color.mobilerun_muted_foreground)) })
        }
        for (r in records) list.addView(taskRow(r))
    }

    private fun taskRow(r: RunRecord): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = getDrawable(R.drawable.fa_input_bg)
            setOnClickListener { startActivity(TaskDetailsActivity.createIntent(this@HomeActivity, r.uuid)) }
        }
        val color = when (r.status) {
            "succeeded" -> getColor(R.color.mobilerun_primary)
            "running" -> Color.parseColor("#2F6FB3")
            "stopped" -> getColor(R.color.fa_warn)
            else -> getColor(R.color.fa_off)
        }
        val from = if (r.origin == "app") "this phone" else "dashboard"
        card.addView(TextView(this).apply { text = "${label(r.status)}  ·  ${r.steps} steps  ·  from $from"; setTextColor(color); textSize = 12f })
        card.addView(TextView(this).apply { text = r.instruction; textSize = 15f; setTextColor(getColor(R.color.mobilerun_foreground)); maxLines = 2 })
        if (r.result.isNotBlank()) card.addView(TextView(this).apply { text = r.result; textSize = 13f; setTextColor(getColor(R.color.mobilerun_muted_foreground)); maxLines = 2 })
        return card.also { it.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) } }
    }

    // ---- settings ---------------------------------------------------------------------------
    private fun refreshSettings() {
        b.setPhone.text = ConfigManager.getInstance(this).deviceName
        b.switchBubble.isChecked = FaBubble.enabled(this)
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        b.setVersion.text = "FastAutomate v2 ${version.orEmpty()}"
    }

    private fun renderConnection(state: ConnectionState?) {
        val (text, color) = when (state) {
            ConnectionState.CONNECTED -> "Connected" to R.color.fa_live
            ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> "Connecting" to R.color.fa_warn
            else -> (if (FaConnect.isLinked(this)) "Offline" else "Not linked") to R.color.fa_off
        }
        b.statusPill.text = "●  $text"
        b.statusPill.setTextColor(getColor(color))
        b.setDashboard.text = when (state) {
            ConnectionState.CONNECTED -> "Connected to the FastAutomate dashboard."
            else -> if (FaConnect.isLinked(this)) "Not connected right now. It retries by itself." else "Not linked yet: open your invite link."
        }
        refreshSetup(auto = false)
    }

    // ---- helpers ----------------------------------------------------------------------------
    private fun label(status: String) = when (status) {
        "succeeded" -> "Done"
        "running" -> "Running"
        "stopped" -> "Stopped"
        else -> "Failed"
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_NOTIFY = 41
        private const val AUTO_DELAY_MS = 500L
    }
}
