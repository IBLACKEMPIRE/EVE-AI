package ai.eve.android

import ai.eve.android.llm.AnthropicClient
import ai.eve.core.llm.ChatMessage
import ai.eve.core.llm.LlmRequest
import ai.eve.core.llm.Role
import ai.eve.core.llm.ToolCall
import ai.eve.core.llm.ToolSpec
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnthropicClientTest {
    @Test fun toolResultsBecomeOneUserTurnAfterToolUse() {
        val msgs = listOf(
            ChatMessage(Role.USER, "remember X and tell time"),
            ChatMessage(Role.ASSISTANT, "", listOf(ToolCall("a", "remember", mapOf("text" to "X")), ToolCall("b", "get_time", emptyMap()))),
            ChatMessage(Role.TOOL, "stored", toolCallId = "a"),
            ChatMessage(Role.TOOL, "ERROR: nope", toolCallId = "b"),
        )
        val api = AnthropicClient.toApiMessages(msgs)
        assertEquals(3, api.length())
        assertEquals("assistant", api.getJSONObject(1).getString("role"))
        assertEquals(2, api.getJSONObject(1).getJSONArray("content").length())
        val results = api.getJSONObject(2).getJSONArray("content")
        assertEquals(2, results.length())
        assertEquals("b", results.getJSONObject(1).getString("tool_use_id"))
        assertTrue(results.getJSONObject(1).getBoolean("is_error"))
    }

    @Test fun bodyDeclaresToolsWithStringSchema() {
        val body = AnthropicClient.buildBody("m", LlmRequest("sys", listOf(ChatMessage(Role.USER, "hi")),
            listOf(ToolSpec("open_app", "Open", mapOf("name" to "App"), listOf("name")))))
        val tool = body.getJSONArray("tools").getJSONObject(0)
        assertEquals("string", tool.getJSONObject("input_schema").getJSONObject("properties").getJSONObject("name").getString("type"))
        assertEquals("sys", body.getString("system"))
    }

    @Test fun parsesTextAndToolUse() {
        val r = AnthropicClient.parseResponse(
            """{"content":[{"type":"text","text":"[happy] Sure"},{"type":"tool_use","id":"t1","name":"set_alarm","input":{"hour":7,"minute":"30"}}]}""",
        )
        assertEquals("[happy] Sure", r.text)
        assertEquals(mapOf("hour" to "7", "minute" to "30"), r.toolCalls.single().arguments)
    }
}
