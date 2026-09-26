package com.mobilerun.portal.agent

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RunStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun spec(uuid: String) = RunSpec(uuid, "open settings", false, 5, 60_000, false, "p", "e", "u",
        Prompts("v", "P", "E", JSONArray()))

    @Test
    fun `records runs with their steps and keeps the newest`() {
        val dir = tmp.newFolder()
        val store = RunStore(dir, clock = { 1_790_000_000_000 }, keep = 2)
        listOf("a", "b", "c").forEach { store.started(spec(it), "app") }
        store.event("c", "action", "Tap Settings", 1)
        store.finished("c", RunStatus.SUCCEEDED, "Settings is open", 1, null)
        val again = RunStore(dir)
        val (items, total) = again.page(1, 10)
        assertEquals(listOf("c", "b"), items.map { it.uuid })
        assertEquals(2, total)
        val c = again.get("c")!!
        assertEquals(listOf("succeeded", "Settings is open", "1"), listOf(c.status, c.result, c.steps.toString()))
        assertEquals("Tap Settings", c.events.single().text)
    }
}
