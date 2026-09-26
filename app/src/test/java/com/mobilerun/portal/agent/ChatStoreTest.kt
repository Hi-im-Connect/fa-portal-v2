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
        assertEquals(listOf("New chat", "c", "b"), s.recent().map { it.title }) // newest first
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

    @Test
    fun `every line and every delete is reported with its number in the chat`() {
        val reported = mutableListOf<String>()
        val s = ChatStore(
            tmp.root.resolve("chats.json"), clock = { 1L }, ids = { "c${++n}" },
            onLine = { id, seq, line -> reported += "$id#$seq ${line.role}:${line.text}" },
            onDelete = { id, seq -> reported += "$id#$seq deleted" },
        )
        val a = s.current()
        s.add(a.id, ChatLine("user", "hi", ts = 1))
        s.add(a.id, ChatLine("assistant", "hello", ts = 2))
        s.delete(a.id)
        assertEquals(listOf("c1#1 user:hi", "c1#2 assistant:hello", "c1#3 deleted"), reported)
    }

    @Test
    fun `the most recently used chat comes first`() {
        var t = 0L
        val s = ChatStore(tmp.root.resolve("chats.json"), clock = { ++t }, ids = { "c${++n}" })
        val a = s.current()
        s.add(a.id, s.line("user", "a"))
        val b = s.newChat()
        s.add(b.id, s.line("user", "b"))
        s.add(a.id, s.line("user", "a again"))
        assertEquals(listOf("a", "b"), s.recent().map { it.title })
    }
}
