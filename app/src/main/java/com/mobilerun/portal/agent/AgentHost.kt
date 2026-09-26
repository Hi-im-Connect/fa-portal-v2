package com.mobilerun.portal.agent

import android.content.Context
import com.mobilerun.portal.api.ApiResponse
import com.mobilerun.portal.service.MobilerunAccessibilityService
import com.mobilerun.portal.service.ReverseConnectionService
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

interface RunListener {
    fun started(spec: RunSpec, origin: String)
    fun event(uuid: String, kind: String, text: String, steps: Int?)
    fun finished(uuid: String, status: RunStatus, result: String, steps: Int, shot: String?)
}

/** Runs one task at a time on this phone and reports it to the dashboard (numbered, kept until acked). */
class AgentHost(
    private val outbox: Outbox,
    private val vault: KeyVault,
    private val phone: () -> PhoneControl?,
    private val transport: LlmTransport,
    private val send: (String) -> Boolean,
    private val runner: (Runnable) -> Unit = { Thread(it, "FaAgent").start() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val defaultsFile: File? = null,
    private val bundledDefaults: () -> String? = { null },
) {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<RunListener>()

    fun addListener(listener: RunListener) { listeners += listener }

    fun removeListener(listener: RunListener) { listeners -= listener }

    private fun tell(block: (RunListener) -> Unit) = listeners.forEach { runCatching { block(it) } }

    @Volatile
    private var current: Pair<String, AgentLoop>? = null

    // every send goes through the outbox, one at a time, oldest first; a message goes once per connection
    private val sendLock = Any()
    private val sentOnThisConnection = HashSet<String>()

    fun keyHash(): String = vault.hash()

    fun running(): String? = current?.first

    fun handle(method: String, params: JSONObject): ApiResponse = when (method) {
        "agent/credentials" -> {
            vault.save(params.getString("key"), params.getString("hash"))
            ApiResponse.RawObject(JSONObject().put("saved", true))
        }
        "agent/settings" -> { // the dashboard's current provider, models and limits, for the app's own runs
            params.optJSONObject("defaults")?.let { saveDefaults(it) }
            ApiResponse.RawObject(JSONObject().put("saved", true))
        }
        "agent/run" -> {
            saveDefaults(params)
            start(RunSpec.fromJson(params), announce = false)
        }
        "agent/stop" -> {
            val uuid = params.optString("uuid")
            if (current?.first != uuid) {
                ApiResponse.Error("This phone is not running that task")  // e.g. the app was restarted
            } else {
                stop(uuid)
                ApiResponse.RawObject(JSONObject().put("stopping", true))
            }
        }
        "agent/pause", "agent/resume" -> {
            if (setPaused(params.optString("uuid"), method == "agent/pause")) {
                ApiResponse.RawObject(JSONObject().put("paused", method == "agent/pause"))
            } else {
                ApiResponse.Error("This phone is not running that task")
            }
        }
        "agent/ack" -> {
            outbox.ack(params.optString("uuid"), params.optInt("seq", -1))
            ApiResponse.RawObject(JSONObject().put("ok", true))
        }
        else -> ApiResponse.Error("Unknown agent method $method")
    }

    fun start(spec: RunSpec, announce: Boolean, leaveApp: Boolean = announce): ApiResponse {
        val control = phone() ?: return ApiResponse.Error(DispatcherPhoneControl.NO_SERVICE)
        val key = vault.key() ?: return ApiResponse.Error(
            "This phone has no AI key yet. Add the OpenRouter management key in the dashboard Settings and keep the phone connected.",
        )
        val seq = AtomicInteger(0)
        val sink = EventSink { kind, text, steps ->
            val params = JSONObject().put("kind", kind).put("text", text)
            if (steps != null) params.put("steps", steps)
            post("agent/event", spec.uuid, seq.incrementAndGet(), params)
            tell { it.event(spec.uuid, kind, text, steps) }
        }
        val loop = AgentLoop(spec, control, LlmClient(transport, spec.baseUrl, { key }), sink, clock)
        synchronized(this) {
            if (current != null) return ApiResponse.Error("This phone is already running a task")
            current = spec.uuid to loop
        }
        tell { it.started(spec, if (announce) "app" else "dashboard") }
        if (announce) {
            post("agent/started", spec.uuid, 0, JSONObject().put("instruction", spec.instruction)
                .put("reasoning", spec.reasoning).put("max_steps", spec.maxSteps))
        }
        runner(Runnable { finish(spec, control, loop, seq, leaveApp) })
        return ApiResponse.RawObject(JSONObject().put("accepted", true).put("uuid", spec.uuid))
    }

    /** The app's own Run (Home or bubble): the last settings the dashboard sent (or the bundled ones).
     *  leaveApp: started from FastAutomate's own screen, which the agent cannot read, so go Home first. */
    fun startLocal(instruction: String, reasoning: Boolean?, maxSteps: Int?, leaveApp: Boolean = true): ApiResponse {
        val saved = defaultsFile?.takeIf { it.exists() }?.readText() ?: bundledDefaults()
            ?: return ApiResponse.Error("Connect this phone to the dashboard once first")
        val json = JSONObject(saved).put("uuid", java.util.UUID.randomUUID().toString()).put("instruction", instruction.trim())
        if (reasoning != null) json.put("reasoning", reasoning)
        if (maxSteps != null) json.put("max_steps", maxSteps)
        return start(RunSpec.fromJson(json), announce = true, leaveApp = leaveApp)
    }

    /** The chat layer's AI: the dashboard's planner model, paid with this phone's own key. */
    fun brain(): ChatBrain? {
        val key = vault.key() ?: return null
        val saved = defaultsFile?.takeIf { it.exists() }?.readText() ?: bundledDefaults() ?: return null
        val json = JSONObject(saved)
        val baseUrl = json.optString("base_url", "https://openrouter.ai/api/v1").trimEnd('/')
        return ChatBrain(LlmClient(transport, baseUrl, { key }), json.optString("planner_model", DEFAULT_CHAT_MODEL))
    }

    private fun saveDefaults(params: JSONObject) {
        defaultsFile?.writeText(JSONObject(params.toString()).apply { remove("uuid"); remove("instruction") }.toString())
    }

    /** A chat line for the dashboard (role "deleted" = the chat was deleted), kept until acked like run reports. */
    fun postChat(chatId: String, seq: Int, role: String, text: String, run: String?) {
        val params = JSONObject().put("role", role).put("text", text)
        if (run != null) params.put("run", run)
        post("agent/chat", chatId, seq, params)
    }

    fun isPaused(): Boolean = current?.second?.paused == true

    /** Pause or resume the running task (an empty uuid means whichever is running). */
    fun setPaused(uuid: String, paused: Boolean): Boolean {
        val (id, loop) = current ?: return false
        if (uuid.isNotEmpty() && uuid != id) return false
        loop.paused = paused
        return true
    }

    fun stop(uuid: String) {
        current?.let { (id, loop) -> if (uuid.isEmpty() || id == uuid) loop.stopRequested = true }
    }

    fun onConnected() {
        synchronized(sendLock) { sentOnThisConnection.clear() }
        flush()
    }

    /** Runs cut off when the app was killed: tell the dashboard (and the local history) they ended. */
    fun closeInterrupted(uuids: List<String>) {
        for (uuid in uuids) {
            val result = "The app was restarted during this task"
            post("agent/finished", uuid, INTERRUPTED_SEQ, JSONObject().put("status", RunStatus.FAILED.wire).put("result", result).put("steps", 0))
            tell { it.finished(uuid, RunStatus.FAILED, result, 0, null) }
        }
    }

    private fun flush() {
        synchronized(sendLock) {
            for (message in outbox.pending()) {
                val params = message.getJSONObject("params")
                val key = "${params.getString("uuid")}#${params.getInt("seq")}"
                if (key in sentOnThisConnection) continue
                val ok = runCatching { send(message.toString()) }.getOrDefault(false)
                if (!ok) return  // offline: the outbox keeps it for the next connection
                sentOnThisConnection += key
            }
        }
    }

    private fun finish(spec: RunSpec, control: PhoneControl, loop: AgentLoop, seq: AtomicInteger, leaveApp: Boolean) {
        if (leaveApp) { // started from FastAutomate's own screen, which the screen reader skips: leave it first
            control.global(Actions.GLOBAL_HOME)
            control.sleep(LEAVE_APP_MS)
        }
        try {
            val result = try {
                loop.run()
            } catch (e: Exception) {
                AgentLoop.Result(RunStatus.FAILED, "The agent crashed: ${e.message}", 0)
            }
            val shot = runCatching { control.screenshot(SHOT_SIDE, SHOT_QUALITY) }.getOrNull()
            val params = JSONObject().put("status", result.status.wire).put("result", result.result).put("steps", result.steps)
            if (shot != null) params.put("shot", shot)
            runCatching { post("agent/finished", spec.uuid, seq.incrementAndGet(), params) }
            tell { it.finished(spec.uuid, result.status, result.result, result.steps, shot) }
        } finally {
            synchronized(this) { current = null }  // never leave the phone stuck as busy
        }
    }

    private fun post(method: String, uuid: String, seq: Int, params: JSONObject) {
        params.put("uuid", uuid).put("seq", seq).put("ts", Instant.ofEpochMilli(clock()).toString())
        val message = JSONObject().put("method", method).put("params", params)
        outbox.add(message)
        flush()
    }

    companion object {
        const val SHOT_SIDE = 480
        const val SHOT_QUALITY = 60
        const val LEAVE_APP_MS = 800L
        const val INTERRUPTED_SEQ = 999_999
        const val DEFAULT_CHAT_MODEL = "google/gemini-2.5-flash"
    }
}

/** The app-wide agent: created once in PortalApplication, used by the dispatcher and the connection. */
object AgentRuntime {
    @Volatile
    private var host: AgentHost? = null

    @Volatile
    private var store: RunStore? = null

    @Volatile
    private var chats: ChatStore? = null

    @Synchronized
    fun init(context: Context) {
        if (host != null) return
        val dir = File(context.filesDir, "agent").apply { mkdirs() }
        val runs = RunStore(File(dir, "runs"))
        store = runs
        chats = ChatStore(
            File(dir, "chats.json"),
            onLine = { id, seq, line -> host?.postChat(id, seq, line.role, line.text, line.run) },
            onDelete = { id, seq -> host?.postChat(id, seq, "deleted", "", null) },
        )
        host = AgentHost(
            outbox = Outbox(File(dir, "outbox.json")),
            vault = KeyVault(File(dir, "key.json"), KeystoreSecretBox()),
            phone = {
                MobilerunAccessibilityService.getInstance()?.let { service ->
                    OverlayShy(DispatcherPhoneControl { service.getActionDispatcher() }, { x, y -> overlayCovers(x, y) }) { hidden -> overlayHider(hidden) }
                }
            },
            transport = OkHttpTransport(),
            send = { text -> ReverseConnectionService.getInstance()?.sendText(text) ?: false },
            defaultsFile = File(dir, "defaults.json"),
            bundledDefaults = {
                runCatching { context.assets.open("agent_defaults.json").bufferedReader().readText() }.getOrNull()
            },
        ).also {
            it.addListener(runs)
            it.closeInterrupted(runs.running())  // runs the last process could not finish
        }
    }

    fun host(): AgentHost? = host

    /** Set by the floating bubble: moves it out of the agent's way while it looks or touches. */
    @Volatile
    var overlayHider: (Boolean) -> Unit = {}

    /** Set by the floating bubble: is this screen point on it (so a tap there needs it out of the way)? */
    @Volatile
    var overlayCovers: (Int, Int) -> Boolean = { _, _ -> false }

    fun store(): RunStore? = store

    fun chats(): ChatStore? = chats

    fun keyHash(): String = host?.keyHash().orEmpty()

    fun handle(method: String, params: JSONObject): ApiResponse =
        host?.handle(method, params) ?: ApiResponse.Error("The agent is not ready yet")

    fun onConnected() {
        host?.onConnected()
    }
}
