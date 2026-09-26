package com.mobilerun.portal.agent

import com.mobilerun.portal.taskprompt.PortalCloudClient
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalTasksTest {
    private val record = RunRecord(
        uuid = "u1", instruction = "open settings", origin = "app", status = "succeeded",
        createdAt = "2026-09-25T10:00:00Z", finishedAt = "2026-09-25T10:01:00Z", steps = 2, result = "Settings is open",
        events = listOf(RunEvent("plan", "1. Open Settings", "t1"), RunEvent("action", "tap index=3", "t2"), RunEvent("phase", "Thinking", "t3")),
    )

    @Test
    fun `the app's parsers read the local task`() {
        val details = PortalCloudClient.parseTaskDetails(LocalTasks.taskJson(record).toString(), "u1")!!
        assertEquals(listOf("u1", "completed", "open settings"), listOf(details.taskId, details.status, details.prompt))
        assertEquals("completed", PortalCloudClient.parseTaskStatus(LocalTasks.statusJson(record).toString()))
    }

    @Test
    fun `history and trajectory use the dashboard's shapes`() {
        val page = PortalCloudClient.parseTaskHistoryPage(LocalTasks.historyJson(listOf(record), 1, 20, 1).toString())!!
        assertEquals(listOf("u1"), page.items.map { it.taskId })
        val trajectory = PortalCloudClient.parseTaskTrajectory(LocalTasks.trajectoryJson(record).toString())!!
        assertEquals(listOf("ManagerPlanDetailsEvent", "ExecutorActionEvent"), trajectory.events.map { it.event })  // phases hidden
    }
}
