package com.mobilerun.portal.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

data class RunEvent(val kind: String, val text: String, val ts: String)

data class RunRecord(
    val uuid: String,
    val instruction: String,
    val origin: String,
    val status: String,
    val createdAt: String,
    val finishedAt: String?,
    val steps: Int,
    val result: String,
    val events: List<RunEvent>,
)

/** The phone's own history of runs (both origins), for the app's task screens. */
class RunStore(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val keep: Int = 100,
) : RunListener {
    private val file = File(dir, "runs.json")
    private val runs = LinkedHashMap<String, RunRecord>()

    init {
        dir.mkdirs()
        if (file.exists()) {
            runCatching { JSONArray(file.readText()) }.getOrNull()?.let { array ->
                for (i in 0 until array.length()) array.optJSONObject(i)?.let { runs[it.getString("uuid")] = fromJson(it) }
            }
        }
    }

    @Synchronized
    override fun started(spec: RunSpec, origin: String) {
        runs[spec.uuid] = RunRecord(spec.uuid, spec.instruction, origin, "running", now(), null, 0, "", emptyList())
        while (runs.size > keep) runs.remove(runs.keys.first())
        save()
    }

    @Synchronized
    override fun event(uuid: String, kind: String, text: String, steps: Int?) {
        val r = runs[uuid] ?: return
        runs[uuid] = r.copy(steps = steps ?: r.steps, events = r.events + RunEvent(kind, text, now()))
        save()
    }

    @Synchronized
    override fun finished(uuid: String, status: RunStatus, result: String, steps: Int, shot: String?) {
        val r = runs[uuid] ?: return
        runs[uuid] = r.copy(status = status.wire, result = result, steps = steps, finishedAt = now())
        save()
    }

    @Synchronized
    fun get(uuid: String): RunRecord? = runs[uuid]

    @Synchronized
    fun page(page: Int, size: Int): Pair<List<RunRecord>, Int> {
        val newest = runs.values.reversed()
        return newest.drop((page.coerceAtLeast(1) - 1) * size).take(size) to newest.size
    }

    private fun now() = Instant.ofEpochMilli(clock()).toString()

    private fun save() {
        val array = JSONArray()
        runs.values.forEach { r ->
            array.put(
                JSONObject().put("uuid", r.uuid).put("instruction", r.instruction).put("origin", r.origin)
                    .put("status", r.status).put("createdAt", r.createdAt).put("finishedAt", r.finishedAt ?: JSONObject.NULL)
                    .put("steps", r.steps).put("result", r.result)
                    .put("events", JSONArray(r.events.map { JSONObject().put("kind", it.kind).put("text", it.text).put("ts", it.ts) })),
            )
        }
        val tmp = File(dir, "runs.json.tmp")
        tmp.writeText(array.toString())
        tmp.renameTo(file)
    }

    private fun fromJson(j: JSONObject): RunRecord {
        val events = j.optJSONArray("events") ?: JSONArray()
        return RunRecord(
            uuid = j.getString("uuid"),
            instruction = j.getString("instruction"),
            origin = j.optString("origin"),
            status = j.optString("status"),
            createdAt = j.optString("createdAt"),
            finishedAt = if (j.isNull("finishedAt")) null else j.optString("finishedAt"),
            steps = j.optInt("steps"),
            result = j.optString("result"),
            events = (0 until events.length()).map { i ->
                events.getJSONObject(i).let { e -> RunEvent(e.getString("kind"), e.getString("text"), e.optString("ts")) }
            },
        )
    }
}
