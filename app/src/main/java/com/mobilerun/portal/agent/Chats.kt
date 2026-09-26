package com.mobilerun.portal.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One line of a chat: the user's, the assistant's, or a task the assistant started (run = its uuid). */
data class ChatLine(val role: String, val text: String, val run: String? = null, val ts: Long)

data class Chat(val id: String, val lines: List<ChatLine>) {
    val title: String get() = lines.firstOrNull { it.role == "user" }?.text ?: "New chat"
}

/** The bubble's chats (Messenger-style heads): a few conversations, one of them open. Kept on disk. */
class ChatStore(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ids: () -> String = { java.util.UUID.randomUUID().toString() },
    private val max: Int = 5,
) {
    private val chats = ArrayList<Chat>()
    private var selected: String? = null

    init {
        runCatching { JSONObject(file.readText()) }.getOrNull()?.let { root ->
            val array = root.optJSONArray("chats") ?: JSONArray()
            for (i in 0 until array.length()) chats += fromJson(array.getJSONObject(i))
            selected = root.optString("selected").ifEmpty { null }
        }
    }

    @Synchronized
    fun chats(): List<Chat> = chats.toList()

    @Synchronized
    fun current(): Chat = chats.firstOrNull { it.id == selected } ?: chats.lastOrNull()?.also { selected = it.id } ?: newChat()

    /** "+": a fresh chat (the open one is reused while it is still empty). */
    @Synchronized
    fun newChat(): Chat {
        chats.firstOrNull { it.id == selected && it.lines.isEmpty() }?.let { return it }
        val chat = Chat(ids(), emptyList())
        chats += chat
        while (chats.size > max) chats.removeAt(0)
        selected = chat.id
        save()
        return chat
    }

    @Synchronized
    fun select(id: String) {
        if (chats.any { it.id == id }) selected = id
        save()
    }

    @Synchronized
    fun delete(id: String) {
        val at = chats.indexOfFirst { it.id == id }
        if (at < 0) return
        chats.removeAt(at)
        if (selected == id) selected = chats.getOrNull(at.coerceAtMost(chats.size - 1))?.id
        if (chats.isEmpty()) newChat() else save()
    }

    @Synchronized
    fun add(chatId: String, line: ChatLine) {
        val at = chats.indexOfFirst { it.id == chatId }
        if (at < 0) return
        chats[at] = chats[at].copy(lines = (chats[at].lines + line).takeLast(MAX_LINES))
        save()
    }

    @Synchronized
    fun chatOfRun(run: String): String? = chats.firstOrNull { c -> c.lines.any { it.run == run } }?.id

    fun line(role: String, text: String, run: String? = null) = ChatLine(role, text, run, clock())

    private fun save() {
        val array = JSONArray()
        chats.forEach { c ->
            array.put(
                JSONObject().put("id", c.id).put(
                    "lines",
                    JSONArray(c.lines.map { JSONObject().put("role", it.role).put("text", it.text).put("run", it.run ?: "").put("ts", it.ts) }),
                ),
            )
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSONObject().put("chats", array).put("selected", selected ?: "").toString())
        tmp.renameTo(file)
    }

    private fun fromJson(j: JSONObject): Chat {
        val lines = j.optJSONArray("lines") ?: JSONArray()
        return Chat(
            j.getString("id"),
            (0 until lines.length()).map { i ->
                lines.getJSONObject(i).let { ChatLine(it.getString("role"), it.getString("text"), it.optString("run").ifEmpty { null }, it.optLong("ts")) }
            },
        )
    }

    companion object {
        const val MAX_LINES = 200
    }
}
