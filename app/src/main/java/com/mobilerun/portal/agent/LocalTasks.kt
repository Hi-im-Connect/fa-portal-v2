package com.mobilerun.portal.agent

import com.mobilerun.portal.api.ApiResponse
import com.mobilerun.portal.taskprompt.PortalBalanceResult
import com.mobilerun.portal.taskprompt.PortalCloudClient
import com.mobilerun.portal.taskprompt.PortalModelOption
import com.mobilerun.portal.taskprompt.PortalModelsLoadResult
import com.mobilerun.portal.taskprompt.PortalTaskCancelResult
import com.mobilerun.portal.taskprompt.PortalTaskDetailsResult
import com.mobilerun.portal.taskprompt.PortalTaskDraft
import com.mobilerun.portal.taskprompt.PortalTaskHistoryResult
import com.mobilerun.portal.taskprompt.PortalTaskLaunchResult
import com.mobilerun.portal.taskprompt.PortalTaskLaunchSuccess
import com.mobilerun.portal.taskprompt.PortalTaskScreenshotResult
import com.mobilerun.portal.taskprompt.PortalTaskScreenshotSet
import com.mobilerun.portal.taskprompt.PortalTaskStatusResult
import com.mobilerun.portal.taskprompt.PortalTaskStatusSuccess
import com.mobilerun.portal.taskprompt.PortalTaskTrajectoryResult
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** FastAutomate v2: the app's task screens read the phone's own runs instead of a server. */
object LocalTasks {
    const val ENABLED = true
    private val worker = Executors.newSingleThreadExecutor()
    private val STATUS = mapOf("running" to "running", "succeeded" to "completed", "failed" to "failed", "stopped" to "cancelled")
    private val HIDDEN = setOf("phase", "status")

    private fun host() = AgentRuntime.host()
    private fun store() = AgentRuntime.store()
    private fun later(block: () -> Unit) = worker.execute(block)

    fun launch(draft: PortalTaskDraft, callback: (PortalTaskLaunchResult) -> Unit) = later {
        val response = host()?.startLocal(draft.prompt, draft.settings.reasoning, draft.settings.maxSteps)
            ?: ApiResponse.Error("The agent is not ready yet")
        callback(
            if (response is ApiResponse.Error) {
                PortalTaskLaunchResult.Error(response.message)
            } else {
                PortalTaskLaunchResult.Success(PortalTaskLaunchSuccess(host()?.running().orEmpty()))
            },
        )
    }

    fun status(taskId: String, callback: (PortalTaskStatusResult) -> Unit) = later {
        val record = store()?.get(taskId)
        callback(
            if (record == null) {
                PortalTaskStatusResult.Error("No such task")
            } else {
                PortalTaskStatusResult.Success(PortalTaskStatusSuccess(STATUS[record.status] ?: "failed"))
            },
        )
    }

    fun details(taskId: String, callback: (PortalTaskDetailsResult) -> Unit) = later {
        val details = store()?.get(taskId)?.let { PortalCloudClient.parseTaskDetails(taskJson(it).toString(), taskId) }
        callback(if (details == null) PortalTaskDetailsResult.Error("No such task") else PortalTaskDetailsResult.Success(details))
    }

    fun list(page: Int, pageSize: Int, callback: (PortalTaskHistoryResult) -> Unit) = later {
        val (items, total) = store()?.page(page, pageSize) ?: (emptyList<RunRecord>() to 0)
        val parsed = PortalCloudClient.parseTaskHistoryPage(historyJson(items, page, pageSize, total).toString())
        callback(if (parsed == null) PortalTaskHistoryResult.Error("History unavailable") else PortalTaskHistoryResult.Success(parsed))
    }

    fun screenshots(taskId: String, callback: (PortalTaskScreenshotResult) -> Unit) = later {
        callback(PortalTaskScreenshotResult.Success(PortalTaskScreenshotSet(emptyList())))
    }

    fun trajectory(taskId: String, callback: (PortalTaskTrajectoryResult) -> Unit) = later {
        val parsed = store()?.get(taskId)?.let { PortalCloudClient.parseTaskTrajectory(trajectoryJson(it).toString()) }
        callback(if (parsed == null) PortalTaskTrajectoryResult.Error("No such task") else PortalTaskTrajectoryResult.Success(parsed))
    }

    fun cancel(taskId: String, callback: (PortalTaskCancelResult) -> Unit) = later {
        if (host()?.running() == taskId) {
            host()?.stop(taskId)
            callback(PortalTaskCancelResult.Success)
        } else {
            callback(PortalTaskCancelResult.AlreadyFinished)
        }
    }

    fun models(callback: (PortalModelsLoadResult) -> Unit) = later {
        callback(PortalModelsLoadResult(listOf(PortalModelOption("fastautomate/agent", "Agent")), null, true))
    }

    fun balance(callback: (PortalBalanceResult) -> Unit) = later {
        callback(PortalBalanceResult.Unavailable("Spending is shown on the dashboard"))
    }

    // ---- the dashboard's v1 REST shapes, which the app's parsers already read ------------------
    fun taskJson(r: RunRecord): JSONObject {
        val status = STATUS[r.status] ?: "failed"
        val ended = status != "running"
        return JSONObject().put(
            "task",
            JSONObject().put("id", r.uuid).put("status", status).put("task", r.instruction).put("createdAt", r.createdAt)
                .put("finishedAt", r.finishedAt ?: JSONObject.NULL).put("steps", r.steps)
                .put("succeeded", if (ended) r.status == "succeeded" else JSONObject.NULL)
                .put("output", if (r.result.isEmpty()) JSONObject.NULL else r.result).put("llmModel", "fastautomate/agent"),
        )
    }

    fun statusJson(r: RunRecord): JSONObject = JSONObject().put("status", STATUS[r.status] ?: "failed")

    fun historyJson(records: List<RunRecord>, page: Int, size: Int, total: Int): JSONObject {
        val pages = maxOf(1, (total + size - 1) / size)
        return JSONObject()
            .put("items", JSONArray(records.map { taskJson(it).getJSONObject("task") }))
            .put(
                "pagination",
                JSONObject().put("page", page).put("pageSize", size).put("total", total).put("pages", pages)
                    .put("hasNext", page < pages).put("hasPrev", page > 1),
            )
    }

    fun trajectoryJson(r: RunRecord): JSONObject {
        val events = JSONArray()
        r.events.filter { it.kind !in HIDDEN }.forEach { e ->
            val (name, data) = when (e.kind) {
                "plan" -> "ManagerPlanDetailsEvent" to JSONObject().put("plan", e.text)
                "answer" -> "ManagerPlanDetailsEvent" to JSONObject().put("answer", e.text)
                "action" -> "ExecutorActionEvent" to JSONObject().put("description", e.text)
                "ok" -> "ExecutorActionResultEvent" to JSONObject().put("success", true).put("summary", e.text)
                "error" -> "ExecutorActionResultEvent" to JSONObject().put("success", false).put("summary", e.text)
                "think" -> "FastAgentResponseEvent" to JSONObject().put("thought", e.text)
                else -> "InfoEvent" to JSONObject().put("message", e.text)
            }
            events.put(JSONObject().put("event", name).put("data", data).put("timestamp", e.ts))
        }
        return JSONObject().put("trajectory", events)
    }
}
