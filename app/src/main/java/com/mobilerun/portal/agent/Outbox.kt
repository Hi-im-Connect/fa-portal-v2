package com.mobilerun.portal.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Reports for the dashboard, kept on disk until it acks them, so nothing is lost while offline. */
class Outbox(private val file: File, private val limit: Int = 5000) {
    private val items = LinkedHashMap<String, JSONObject>()

    init {
        if (file.exists() && file.length() > 0) {
            runCatching { JSONArray(file.readText()) }.getOrNull()?.let { array ->
                for (i in 0 until array.length()) array.optJSONObject(i)?.let { items[key(it)] = it }
            }
        }
    }

    @Synchronized
    fun add(message: JSONObject) {
        items[key(message)] = message
        while (items.size > limit) items.remove(items.keys.first())
        save()
    }

    @Synchronized
    fun ack(uuid: String, seq: Int) {
        if (items.remove("$uuid#$seq") != null) save()
    }

    @Synchronized
    fun pending(): List<JSONObject> = items.values.toList()

    fun flush(send: (String) -> Boolean) {
        for (message in pending()) if (!send(message.toString())) return
    }

    private fun key(message: JSONObject): String {
        val params = message.getJSONObject("params")
        return "${params.getString("uuid")}#${params.getInt("seq")}"
    }

    private fun save() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONArray(items.values.toList()).toString())
        tmp.renameTo(file)
    }
}
