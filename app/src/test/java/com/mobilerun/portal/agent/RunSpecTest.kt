package com.mobilerun.portal.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RunSpecTest {
    @Test
    fun `reads the dashboard's agent run message`() {
        val spec = RunSpec.fromJson(
            JSONObject(
                """{"uuid":"u1","instruction":"open settings","reasoning":true,"max_steps":12,"time_limit_s":900,
                    "vision":false,"planner_model":"p/m","executor_model":"e/m","base_url":"https://openrouter.ai/api/v1/",
                    "prompts":{"version":"v","planner":"P","executor":"E","tools":[{"type":"function"}]}}""",
            ),
        )
        assertEquals("u1", spec.uuid)
        assertEquals(12, spec.maxSteps)
        assertEquals(900_000L, spec.timeLimitMs)
        assertEquals("https://openrouter.ai/api/v1", spec.baseUrl)
        assertEquals(1, spec.prompts.tools.length())
    }
}
