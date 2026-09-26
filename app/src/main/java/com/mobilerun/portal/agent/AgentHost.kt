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
    @Volatile
    var listener: RunListener? = null

    @Volatile
    private var current: Pair<String, AgentLoop>? = null

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
            stop(params.optString("uuid"))
            ApiResponse.RawObject(JSONObject().put("stopping", true))
        }
        "agent/ack" -> {
            outbox.ack(params.optString("uuid"), params.optInt("seq", -1))
            ApiResponse.RawObject(JSONObject().put("ok", true))
        }
        else -> ApiResponse.Error("Unknown agent method $method")
    }

    fun start(spec: RunSpec, announce: Boolean): ApiResponse {
        val control = phone() ?: return ApiResponse.Error(DispatcherPhoneControl.NO_SERVICE)
        val key = vault.key() ?: return ApiResponse.Error(
            "This phone has no AI key yet. Add the OpenRouter management key in the dashboard Settings and keep the phone connected.",
        )
        val seq = AtomicInteger(0)
        val sink = EventSink { kind, text, steps ->
            val params = JSONObject().put("kind", kind).put("text", text)
            if (steps != null) params.put("steps", steps)
            post("agent/event", spec.uuid, seq.incrementAndGet(), params)
            listener?.event(spec.uuid, kind, text, steps)
        }
        val loop = AgentLoop(spec, control, LlmClient(transport, spec.baseUrl, { key }), sink, clock)
        synchronized(this) {
            if (current != null) return ApiResponse.Error("This phone is already running a task")
            current = spec.uuid to loop
        }
        listener?.started(spec, if (announce) "app" else "dashboard")
        if (announce) {
            post("agent/started", spec.uuid, 0, JSONObject().put("instruction", spec.instruction)
                .put("reasoning", spec.reasoning).put("max_steps", spec.maxSteps))
        }
        runner(Runnable { finish(spec, control, loop, seq, fromApp = announce) })
        return ApiResponse.RawObject(JSONObject().put("accepted", true))
    }

    /** The app's own Run button: the last settings the dashboard sent (or the bundled ones). */
    fun startLocal(instruction: String, reasoning: Boolean?, maxSteps: Int?): ApiResponse {
        val saved = defaultsFile?.takeIf { it.exists() }?.readText() ?: bundledDefaults()
            ?: return ApiResponse.Error("Connect this phone to the dashboard once first")
        val json = JSONObject(saved).put("uuid", java.util.UUID.randomUUID().toString()).put("instruction", instruction.trim())
        if (reasoning != null) json.put("reasoning", reasoning)
        if (maxSteps != null) json.put("max_steps", maxSteps)
        return start(RunSpec.fromJson(json), announce = true)
    }

    private fun saveDefaults(params: JSONObject) {
        defaultsFile?.writeText(JSONObject(params.toString()).apply { remove("uuid"); remove("instruction") }.toString())
    }

    fun stop(uuid: String) {
        current?.let { (id, loop) -> if (uuid.isEmpty() || id == uuid) loop.stopRequested = true }
    }

    fun onConnected() = outbox.flush(send)

    private fun finish(spec: RunSpec, control: PhoneControl, loop: AgentLoop, seq: AtomicInteger, fromApp: Boolean) {
        if (fromApp) { // started from FastAutomate's own screen, which the screen reader skips: leave it first
            control.global(Actions.GLOBAL_HOME)
            control.sleep(LEAVE_APP_MS)
        }
        val result = try {
            loop.run()
        } catch (e: Exception) {
            AgentLoop.Result(RunStatus.FAILED, "The agent crashed: ${e.message}", 0)
        }
        val shot = runCatching { control.screenshot(SHOT_SIDE, SHOT_QUALITY) }.getOrNull()
        val params = JSONObject().put("status", result.status.wire).put("result", result.result).put("steps", result.steps)
        if (shot != null) params.put("shot", shot)
        post("agent/finished", spec.uuid, seq.incrementAndGet(), params)
        synchronized(this) { current = null }
        listener?.finished(spec.uuid, result.status, result.result, result.steps, shot)
    }

    private fun post(method: String, uuid: String, seq: Int, params: JSONObject) {
        params.put("uuid", uuid).put("seq", seq).put("ts", Instant.ofEpochMilli(clock()).toString())
        val message = JSONObject().put("method", method).put("params", params)
        outbox.add(message)
        send(message.toString())
    }

    companion object {
        const val SHOT_SIDE = 480
        const val SHOT_QUALITY = 60
        const val LEAVE_APP_MS = 800L
    }
}

/** The app-wide agent: created once in PortalApplication, used by the dispatcher and the connection. */
object AgentRuntime {
    @Volatile
    private var host: AgentHost? = null

    @Volatile
    private var store: RunStore? = null

    @Synchronized
    fun init(context: Context) {
        if (host != null) return
        val dir = File(context.filesDir, "agent").apply { mkdirs() }
        val runs = RunStore(File(dir, "runs"))
        store = runs
        host = AgentHost(
            outbox = Outbox(File(dir, "outbox.json")),
            vault = KeyVault(File(dir, "key.json"), KeystoreSecretBox()),
            phone = {
                MobilerunAccessibilityService.getInstance()?.let { service ->
                    DispatcherPhoneControl { service.getActionDispatcher() }
                }
            },
            transport = OkHttpTransport(),
            send = { text -> ReverseConnectionService.getInstance()?.sendText(text) ?: false },
            defaultsFile = File(dir, "defaults.json"),
            bundledDefaults = {
                runCatching { context.assets.open("agent_defaults.json").bufferedReader().readText() }.getOrNull()
            },
        ).also { it.listener = runs }
    }

    fun host(): AgentHost? = host

    fun store(): RunStore? = store

    fun keyHash(): String = host?.keyHash().orEmpty()

    fun handle(method: String, params: JSONObject): ApiResponse =
        host?.handle(method, params) ?: ApiResponse.Error("The agent is not ready yet")

    fun onConnected() {
        host?.onConnected()
    }
}
