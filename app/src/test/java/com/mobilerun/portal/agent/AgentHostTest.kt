package com.mobilerun.portal.agent

import com.mobilerun.portal.api.ApiResponse
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentHostTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val box = object : SecretBox {
        override fun seal(plain: ByteArray) = plain
        override fun open(sealed: ByteArray) = sealed
    }
    private val state = JSONObject("""{"a11y_tree":{},"phone_state":{},"device_context":{"screen_bounds":{"width":1080,"height":2400}}}""")
    private val sent = mutableListOf<JSONObject>()
    private val queued = mutableListOf<Runnable>()

    private fun host(transport: LlmTransport, phone: PhoneControl? = FakePhone(state), direct: Boolean = true) = AgentHost(
        outbox = Outbox(tmp.newFile()),
        vault = KeyVault(tmp.newFile(), box),
        phone = { phone },
        transport = transport,
        send = { sent += JSONObject(it); true },
        runner = { if (direct) it.run() else queued += it },
    )

    private fun runParams(uuid: String = "u1") = JSONObject(
        """{"uuid":"$uuid","instruction":"open settings","reasoning":false,"max_steps":5,"time_limit_s":900,"vision":false,
            "planner_model":"p/m","executor_model":"e/m","base_url":"https://openrouter.ai/api/v1",
            "prompts":{"version":"v","planner":"P","executor":"E","tools":[]}}""",
    )

    private fun credentials(host: AgentHost) =
        host.handle("agent/credentials", JSONObject().put("key", "sk-or-v1-phone").put("hash", "h1"))

    @Test
    fun `credentials are kept and a dashboard task runs and reports`() {
        val host = host(ScriptedTransport(toolReply("done", """{"success":true,"answer":"Settings is open"}""")))
        credentials(host)
        assertEquals("h1", host.keyHash())
        assertTrue(host.handle("agent/run", runParams()) !is ApiResponse.Error)
        val methods = sent.map { it.getString("method") }
        assertEquals("agent/finished", methods.last())
        assertTrue(methods.dropLast(1).all { it == "agent/event" })
        val finished = sent.last().getJSONObject("params")
        assertEquals(listOf("u1", "succeeded", "Settings is open", "SHOT"),
            listOf(finished.getString("uuid"), finished.getString("status"), finished.getString("result"), finished.getString("shot")))
        assertEquals((1..sent.size).toList(), sent.map { it.getJSONObject("params").getInt("seq") })
    }

    @Test
    fun `acks empty the outbox`() {
        val host = host(ScriptedTransport(toolReply("done", """{"success":true,"answer":"ok"}""")))
        credentials(host)
        host.handle("agent/run", runParams())
        sent.forEach { host.handle("agent/ack", JSONObject().put("uuid", "u1").put("seq", it.getJSONObject("params").getInt("seq"))) }
        sent.clear()
        host.onConnected()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `refused without a key, without accessibility, or while busy`() {
        val noKey = host(ScriptedTransport())
        assertTrue((noKey.handle("agent/run", runParams()) as ApiResponse.Error).message.contains("no AI key"))
        val noService = host(ScriptedTransport(), phone = null)
        credentials(noService)
        assertEquals(DispatcherPhoneControl.NO_SERVICE, (noService.handle("agent/run", runParams()) as ApiResponse.Error).message)
        val busy = host(ScriptedTransport(), direct = false)
        credentials(busy)
        busy.handle("agent/run", runParams("u1"))
        assertTrue((busy.handle("agent/run", runParams("u2")) as ApiResponse.Error).message.contains("already running"))
    }

    @Test
    fun `stop ends the run as stopped`() {
        val host = host(ScriptedTransport(*Array(3) { toolReply("wait", """{"seconds":1}""") }), direct = false)
        credentials(host)
        host.handle("agent/run", runParams())
        host.handle("agent/stop", JSONObject().put("uuid", "u1"))
        queued.single().run()
        assertEquals("stopped", sent.last().getJSONObject("params").getString("status"))
    }

    @Test
    fun `app-started runs announce themselves first`() {
        val host = host(ScriptedTransport(toolReply("done", """{"success":true,"answer":"ok"}""")))
        credentials(host)
        host.start(RunSpec.fromJson(runParams("u7")), announce = true)
        val first = sent.first()
        assertEquals("agent/started", first.getString("method"))
        assertEquals(0, first.getJSONObject("params").getInt("seq"))
        assertEquals("open settings", first.getJSONObject("params").getString("instruction"))
    }

    @Test
    fun `the app's own run uses the last settings from the dashboard`() {
        val dir = tmp.newFolder()
        val host = AgentHost(
            outbox = Outbox(tmp.newFile()), vault = KeyVault(tmp.newFile(), box), phone = { FakePhone(state) },
            transport = ScriptedTransport(toolReply("done", """{"success":true,"answer":"ok"}"""),
                toolReply("done", """{"success":true,"answer":"ok"}""")),
            send = { sent += JSONObject(it); true }, runner = { it.run() },
            defaultsFile = java.io.File(dir, "defaults.json"), bundledDefaults = { null },
        )
        credentials(host)
        host.handle("agent/run", runParams())  // saves the dashboard's settings
        sent.clear()
        assertTrue(host.startLocal("check the weather", reasoning = false, maxSteps = 7) !is ApiResponse.Error)
        val started = sent.first().getJSONObject("params")
        assertEquals(listOf("agent/started", "check the weather", "7"),
            listOf(sent.first().getString("method"), started.getString("instruction"), started.getInt("max_steps").toString()))
    }

    @Test
    fun `settings from the dashboard are used for the app's own runs`() {
        val dir = tmp.newFolder()
        val transport = ScriptedTransport(toolReply("done", """{"success":true,"answer":"ok"}"""))
        val host = AgentHost(
            outbox = Outbox(tmp.newFile()), vault = KeyVault(tmp.newFile(), box), phone = { FakePhone(state) },
            transport = transport, send = { sent += JSONObject(it); true }, runner = { it.run() },
            defaultsFile = java.io.File(dir, "defaults.json"), bundledDefaults = { null },
        )
        val defaults = runParams().apply { remove("uuid"); remove("instruction"); put("executor_model", "gemini-x") }
        host.handle("agent/credentials", JSONObject().put("key", "k").put("hash", "h"))
        host.handle("agent/settings", JSONObject().put("defaults", defaults))
        assertTrue(host.startLocal("open settings", null, null) !is ApiResponse.Error)
        assertEquals("gemini-x", transport.requests.first().second.getString("model"))
    }

    @Test
    fun `the app's own run starts from the home screen`() {
        val phone = FakePhone(state)
        val host = AgentHost(
            outbox = Outbox(tmp.newFile()), vault = KeyVault(tmp.newFile(), box), phone = { phone },
            transport = ScriptedTransport(toolReply("done", """{"success":true,"answer":"ok"}""")),
            send = { sent += JSONObject(it); true }, runner = { it.run() },
        )
        credentials(host)
        host.start(RunSpec.fromJson(runParams("u8")), announce = true)
        assertEquals("global 2", phone.done.first())  // leave the FastAutomate screen first
    }
}
