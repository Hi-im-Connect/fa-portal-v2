package com.mobilerun.portal.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var n = 0
    private fun store() = ChatStore(tmp.root.resolve("chats.json"), clock = { 1L }, ids = { "c${++n}" }, max = 3)

    @Test
    fun `there is always a current chat and it survives a restart`() {
        val s = store()
        val chat = s.current()
        s.add(chat.id, ChatLine("user", "open youtube", ts = 1))
        val again = ChatStore(tmp.root.resolve("chats.json"))
        assertEquals(chat.id, again.current().id)
        assertEquals("open youtube", again.current().title)
    }

    @Test
    fun `new chat is selected, an empty one is reused, and the oldest goes past the limit`() {
        val s = store()
        val first = s.current()
        assertEquals(first.id, s.newChat().id) // still empty: no second blank chat
        s.add(first.id, ChatLine("user", "a", ts = 1))
        val second = s.newChat()
        assertNotEquals(first.id, second.id)
        assertEquals(second.id, s.current().id)
        s.add(second.id, ChatLine("user", "b", ts = 1))
        s.add(s.newChat().id, ChatLine("user", "c", ts = 1))
        s.newChat()
        assertEquals(listOf("b", "c", "New chat"), s.chats().map { it.title })
    }

    @Test
    fun `switching and deleting chats`() {
        val s = store()
        val a = s.current()
        s.add(a.id, ChatLine("user", "a", ts = 1))
        val b = s.newChat()
        s.select(a.id)
        assertEquals(a.id, s.current().id)
        s.delete(a.id)
        assertEquals(b.id, s.current().id)
        s.delete(b.id)
        assertEquals(1, s.chats().size) // a fresh one takes its place
        assertEquals("New chat", s.current().title)
    }

    @Test
    fun `a task line is found by its run`() {
        val s = store()
        val a = s.current()
        s.add(a.id, ChatLine("task", "open settings", run = "r1", ts = 1))
        assertEquals(a.id, s.chatOfRun("r1"))
        assertEquals(null, s.chatOfRun("r2"))
    }
}
