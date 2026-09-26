package com.mobilerun.portal.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OutboxTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun msg(seq: Int) = JSONObject().put("method", "agent/event").put("params", JSONObject().put("uuid", "u1").put("seq", seq))

    @Test
    fun `reports stay until acked, in order, across restarts`() {
        val file = tmp.newFile("outbox.json")
        val outbox = Outbox(file)
        (1..3).forEach { outbox.add(msg(it)) }
        outbox.ack("u1", 2)
        assertEquals(listOf(1, 3), Outbox(file).pending().map { it.getJSONObject("params").getInt("seq") })
    }

    @Test
    fun `flush stops at the first failed send`() {
        val outbox = Outbox(tmp.newFile("o.json"))
        (1..3).forEach { outbox.add(msg(it)) }
        val sent = mutableListOf<Int>()
        outbox.flush { text -> val seq = JSONObject(text).getJSONObject("params").getInt("seq"); sent += seq; seq < 2 }
        assertEquals(listOf(1, 2), sent)
        assertEquals(3, outbox.pending().size)  // nothing is dropped by sending; only acks drop
    }

    @Test
    fun `a full outbox drops the oldest`() {
        val outbox = Outbox(tmp.newFile("o.json"), limit = 2)
        (1..3).forEach { outbox.add(msg(it)) }
        assertEquals(listOf(2, 3), outbox.pending().map { it.getJSONObject("params").getInt("seq") })
    }
}
