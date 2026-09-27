package com.mobilerun.portal.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatFollowUpTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val runs = HashMap<String, RunRecord>()
    private val started = mutableListOf<String>()
    private val finals = mutableListOf<String>()

    private fun setup(transport: ScriptedTransport, maxRounds: Int = 3): Pair<ChatStore, ChatFollowUp> {
        val store = ChatStore(tmp.root.resolve("chats.json"))
        var n = 0
        val followUp = ChatFollowUp(
            chats = store,
            runs = { runs[it] },
            brain = { ChatBrain(LlmClient(transport, "https://x", { "k" }, sleep = {}), "m") },
            start = { instruction -> started += instruction; "r${++n + 1}" },
            runner = { it.run() },
            maxRounds = maxRounds,
            onFinal = { _, text -> finals += text },
        )
        return store to followUp
    }

    private fun finish(f: ChatFollowUp, uuid: String, status: RunStatus, result: String) {
        runs[uuid] = RunRecord(uuid, "x", "app", status.wire, "t", "t", 3, result, emptyList())
        f.finished(uuid, status, result, 3, "SHOT")
    }

    @Test
    fun `a good result is checked and answered`() {
        val t = ScriptedTransport(textReply("It's 8:53."))
        val (store, f) = setup(t)
        val chat = store.current()
        store.add(chat.id, store.line("user", "what time is it"))
        f.track("r1", chat.id)
        finish(f, "r1", RunStatus.SUCCEEDED, "The time is 8:53")
        assertEquals(listOf("user", "assistant"), store.current().lines.map { it.role })
        assertEquals("It's 8:53.", store.current().lines.last().text)
        assertTrue(started.isEmpty())
        assertEquals(listOf("It's 8:53."), finals)
        val request = t.requests.single().second
        val note = request.getJSONArray("messages").let { it.getJSONObject(it.length() - 1) }
        assertTrue(note.toString().contains("attempt 1 of 3"))
        assertTrue(note.toString().contains("data:image/jpeg;base64,SHOT"))
    }

    @Test
    fun `a wrong result goes back to the agent with what went wrong, until it is right`() {
        val t = ScriptedTransport(
            toolReply("run_task", """{"instruction":"Open YouTube, search lo-fi and PLAY the first video (last time it only searched)","reply":"It only searched, trying again."}"""),
            textReply("Playing lo-fi now."),
        )
        val (store, f) = setup(t)
        val chat = store.current()
        store.add(chat.id, store.line("user", "play lofi"))
        f.track("r1", chat.id)
        finish(f, "r1", RunStatus.SUCCEEDED, "Search results shown")
        assertEquals(listOf("Open YouTube, search lo-fi and PLAY the first video (last time it only searched)"), started)
        assertEquals(listOf("user", "assistant", "task"), store.current().lines.map { it.role })
        assertEquals("r2", store.current().lines.last().run)
        finish(f, "r2", RunStatus.SUCCEEDED, "Video playing")
        assertEquals("Playing lo-fi now.", store.current().lines.last().text)
        assertTrue(t.requests[1].second.toString().contains("attempt 2 of 3"))
        assertEquals(listOf("Playing lo-fi now."), finals)
    }

    @Test
    fun `the last attempt can only report, never loop`() {
        val t = ScriptedTransport(textReply("I could not finish: the app kept crashing."))
        val (store, f) = setup(t, maxRounds = 1)
        val chat = store.current()
        f.track("r1", chat.id)
        finish(f, "r1", RunStatus.FAILED, "crash")
        assertFalse(t.requests.single().second.has("tools"))
        assertTrue(started.isEmpty())
    }

    @Test
    fun `a task the user stopped, or one not from a chat, is left alone`() {
        val t = ScriptedTransport()
        val (store, f) = setup(t)
        f.track("r1", store.current().id)
        finish(f, "r1", RunStatus.STOPPED, "Stopped")
        finish(f, "other", RunStatus.SUCCEEDED, "ok")
        assertTrue(t.requests.isEmpty())
        assertTrue(store.current().lines.isEmpty())
    }
}
