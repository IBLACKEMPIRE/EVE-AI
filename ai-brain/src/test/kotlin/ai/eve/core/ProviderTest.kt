package ai.eve.core

import ai.eve.core.json.Json
import ai.eve.core.llm.ChatMessage
import ai.eve.core.llm.ErrorKind
import ai.eve.core.llm.LlmException
import ai.eve.core.llm.LlmRequest
import ai.eve.core.llm.ProviderId
import ai.eve.core.llm.ProviderState
import ai.eve.core.llm.Role
import ai.eve.core.llm.ToolCall
import ai.eve.core.llm.ToolSpec
import ai.eve.core.providers.AnthropicProvider
import ai.eve.core.providers.GeminiProvider
import ai.eve.core.providers.HttpResponse
import ai.eve.core.providers.HttpTransport
import ai.eve.core.providers.ModelCatalog
import ai.eve.core.providers.OpenAiProvider
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Records requests and replies with scripted responses. */
class FakeTransport(vararg responses: Pair<Int, String>) : HttpTransport {
    private val queue = ArrayDeque(responses.toList())
    val requests = mutableListOf<Triple<String, Map<String, String>, Json.Obj>>()
    override suspend fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
        requests += Triple(url, headers, Json.parseObj(body ?: "{}"))
        val (status, text) = queue.removeFirstOrNull() ?: (500 to "{}")
        return HttpResponse(status, text)
    }
    fun lastBody() = requests.last().third
}

const val KEY = "sk-test-SECRET-123"
private val TOOLS = listOf(ToolSpec("set_alarm", "Alarm", mapOf("hour" to "0-23")), ToolSpec("get_time", "Clock", emptyMap()))
private fun req(vararg m: ChatMessage, tools: List<ToolSpec> = TOOLS) = LlmRequest("sys", m.toList(), tools)
private val USER = ChatMessage(Role.USER, "wake me at 7")

class ProviderTest {
    // ─── JSON ───
    @Test fun jsonRoundTripsUnicodeEscapesAndNumbers() {
        val src = """{"a":"line\nטאב\t\"q\" \\ \u0001","n":-1.5e3,"b":[true,false,null,{"x":7}]}"""
        val v = Json.parseObj(src)
        assertEquals("line\nטאב\t\"q\" \\ \u0001", v.str("a"))
        assertEquals("-1.5e3", (v["n"] as Json.Num).raw)
        assertEquals(v, Json.parseObj(Json.stringify(v)))
        assertFailsWith<Exception> { Json.parse("{\"a\":}") }
        assertFailsWith<Exception> { Json.parse("[1,2") }
        assertEquals("7", Json.parseObj("""{"h":7}""")["h"]!!.asText())
    }

    // ─── Anthropic ───
    @Test fun anthropicSendsDocumentedHeadersAndParsesText() = runBlocking {
        val t = FakeTransport(200 to """{"content":[{"type":"thinking","thinking":"…","signature":"sig1"},{"type":"text","text":"[happy] Hi"}],"stop_reason":"end_turn"}""")
        val r = AnthropicProvider(KEY, "claude-sonnet-5-5", t, "http://x").complete(req(USER))
        val (url, headers, body) = t.requests.single()
        assertEquals("http://x/v1/messages", url)
        assertEquals(KEY, headers["x-api-key"]); assertEquals("2023-06-01", headers["anthropic-version"])
        assertEquals("claude-sonnet-5-5", body.str("model")); assertEquals("sys", body.str("system"))
        assertEquals(4096, body.int("max_tokens"))
        assertEquals("[happy] Hi", r.text); assertEquals(ProviderId.ANTHROPIC, r.provider)
    }

    @Test fun anthropicReplaysThinkingBlocksDuringToolUse() = runBlocking {
        val t = FakeTransport(
            200 to """{"content":[{"type":"thinking","thinking":"plan","signature":"SIG"},{"type":"tool_use","id":"toolu_1","name":"set_alarm","input":{"hour":7}}],"stop_reason":"tool_use"}""",
            200 to """{"content":[{"type":"text","text":"Done"}],"stop_reason":"end_turn"}""",
        )
        val p = AnthropicProvider(KEY, "m", t, "http://x")
        val first = p.complete(req(USER))
        assertEquals(listOf(ToolCall("toolu_1", "set_alarm", mapOf("hour" to "7"))), first.toolCalls)
        p.complete(req(USER, ChatMessage(Role.ASSISTANT, "", first.toolCalls, providerState = first.providerState),
            ChatMessage(Role.TOOL, "ok", toolCallId = "toolu_1")))
        val msgs = t.lastBody().arr("messages")!!.objects()
        val assistant = msgs[1].arr("content")!!.objects()
        assertEquals("thinking", assistant[0].str("type")); assertEquals("SIG", assistant[0].str("signature"))
        assertEquals("tool_use", assistant[1].str("type"))
        assertEquals("toolu_1", msgs[2].arr("content")!!.objects().single().str("tool_use_id"))
    }

    @Test fun anthropicToolSchemaAndForeignTurnsAreFlattened() {
        val foreign = ChatMessage(Role.ASSISTANT, "", listOf(ToolCall("call_9", "set_alarm", mapOf("hour" to "7"))),
            providerState = ProviderState(ProviderId.OPENAI, "gpt", "[]"))
        val body = AnthropicProvider.buildBody("m", req(USER, foreign, ChatMessage(Role.TOOL, "ok", toolCallId = "call_9")))
        val tool = body.arr("tools")!!.objects().first()
        assertEquals("object", tool.obj("input_schema")!!.str("type"))
        val msgs = body.arr("messages")!!.objects()
        assertTrue(msgs[1].arr("content")!!.objects().single().str("text")!!.contains("EVE already executed: set_alarm(hour=7)"))
        assertTrue(Json.stringify(body).contains("Result of set_alarm: ok"))
        assertFalse(Json.stringify(body).contains("tool_result"), "foreign tool results must not be sent as tool_result")
    }

    @Test fun anthropicErrorsAreClassifiedWithoutLeakingBodies() = runBlocking {
        val cases = listOf(
            401 to """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key SECRETBODY"}}""" to ErrorKind.INVALID_KEY,
            404 to """{"type":"error","error":{"type":"not_found_error","message":"model: claude-x"}}""" to ErrorKind.MODEL_NOT_FOUND,
            400 to """{"type":"error","error":{"type":"invalid_request_error","message":"Your credit balance is too low"}}""" to ErrorKind.BILLING,
            402 to """{"type":"error","error":{"type":"billing_error","message":"x"}}""" to ErrorKind.BILLING,
            403 to """{"type":"error","error":{"type":"permission_error","message":"x"}}""" to ErrorKind.PERMISSION,
            429 to """{"type":"error","error":{"type":"rate_limit_error","message":"x"}}""" to ErrorKind.RATE_LIMIT,
            529 to """{"type":"error","error":{"type":"overloaded_error","message":"x"}}""" to ErrorKind.OVERLOADED,
            500 to "not json" to ErrorKind.SERVER,
        )
        for ((resp, kind) in cases) {
            val e = assertFailsWith<LlmException> { AnthropicProvider(KEY, "m", FakeTransport(resp), "http://x").complete(req(USER)) }
            assertEquals(kind, e.kind, "status ${resp.first}")
            assertEquals(ProviderId.ANTHROPIC, e.provider)
            assertFalse("SECRETBODY" in (e.message + e.diagnostic())); assertFalse(KEY in (e.message + e.diagnostic()))
        }
    }

    // ─── OpenAI ───
    @Test fun openAiIsStatelessAndReplaysAllOutputItems() = runBlocking {
        val t = FakeTransport(
            200 to """{"status":"completed","output":[{"type":"reasoning","id":"rs_1","encrypted_content":"ENC","summary":[],"status":"completed"},{"type":"function_call","id":"fc_1","call_id":"call_1","name":"set_alarm","arguments":"{\"hour\":\"7\"}","status":"completed"}]}""",
            200 to """{"status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Done"}]}]}""",
        )
        val p = OpenAiProvider(KEY, "gpt-6-luna", t, "http://x")
        val first = p.complete(req(USER))
        assertEquals("Bearer $KEY", t.requests[0].second["Authorization"])
        assertEquals("http://x/v1/responses", t.requests[0].first)
        assertEquals(false, t.requests[0].third.bool("store"))
        assertEquals("call_1", first.toolCalls.single().id)
        val second = p.complete(req(USER, ChatMessage(Role.ASSISTANT, "", first.toolCalls, providerState = first.providerState),
            ChatMessage(Role.TOOL, "ok", toolCallId = "call_1")))
        val input = t.lastBody().arr("input")!!.objects()
        assertEquals(listOf(null, "reasoning", "function_call", "function_call_output"), input.map { it.str("type") })
        assertEquals("ENC", input[1].str("encrypted_content"))
        assertNull(input[1].str("status"), "status is stripped from replayed items")
        assertEquals("Done", second.text)
    }

    @Test fun openAiErrorsDistinguishQuotaFromRateLimit() = runBlocking {
        val cases = listOf(
            429 to """{"error":{"code":"insufficient_quota","message":"x"}}""" to ErrorKind.QUOTA,
            429 to """{"error":{"code":"rate_limit_exceeded","message":"x"}}""" to ErrorKind.RATE_LIMIT,
            401 to """{"error":{"code":"invalid_api_key","message":"Incorrect API key provided: sk-...123"}}""" to ErrorKind.INVALID_KEY,
            404 to """{"error":{"code":"model_not_found","message":"The model `gpt-x` does not exist"}}""" to ErrorKind.MODEL_NOT_FOUND,
            400 to """{"error":{"code":"model_not_found","message":"x"}}""" to ErrorKind.MODEL_NOT_FOUND,
            403 to """{"error":{"code":"unsupported_country_region_territory","message":"x"}}""" to ErrorKind.REGION,
        )
        for ((resp, kind) in cases) {
            val e = assertFailsWith<LlmException> { OpenAiProvider(KEY, "m", FakeTransport(resp), "http://x").complete(req(USER)) }
            assertEquals(kind, e.kind, resp.second)
        }
    }

    @Test fun openAiTruncationAndRefusalAreReported() = runBlocking {
        val r = OpenAiProvider(KEY, "m", FakeTransport(200 to """{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[{"type":"reasoning","summary":[]}]}"""), "http://x").complete(req(USER))
        assertTrue(r.truncated); assertEquals("", r.text)
        val e = assertFailsWith<LlmException> {
            OpenAiProvider(KEY, "m", FakeTransport(200 to """{"output":[{"type":"message","content":[{"type":"refusal","refusal":""}]}]}"""), "http://x").complete(req(USER))
        }
        assertEquals(ErrorKind.CONTENT_BLOCKED, e.kind)
    }

    // ─── Gemini ───
    @Test fun geminiOmitsEmptySchemasAndReplaysThoughtSignatures() = runBlocking {
        val t = FakeTransport(
            200 to """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"set_alarm","args":{"hour":7},"id":"fc-1"},"thoughtSignature":"SIG_A"}]},"finishReason":"STOP"}]}""",
            200 to """{"candidates":[{"content":{"role":"model","parts":[{"text":"thinking…","thought":true},{"text":"Done"}]},"finishReason":"STOP"}]}""",
        )
        val p = GeminiProvider(KEY, "gemini-3.8-flash", t, "http://x")
        val first = p.complete(req(USER))
        val (url, headers, body) = t.requests[0]
        assertEquals("http://x/v1beta/models/gemini-3.8-flash:generateContent", url)
        assertEquals(KEY, headers["x-goog-api-key"])
        val decls = body.arr("tools")!!.objects().single().arr("functionDeclarations")!!.objects()
        assertEquals("OBJECT", decls[0].obj("parameters")!!.str("type"))
        assertNull(decls[1]["parameters"], "argument-less tools must not send an empty OBJECT schema")
        assertEquals("fc-1", first.toolCalls.single().id)
        val second = p.complete(req(USER, ChatMessage(Role.ASSISTANT, "", first.toolCalls, providerState = first.providerState),
            ChatMessage(Role.TOOL, "ok", toolCallId = "fc-1")))
        val contents = t.lastBody().arr("contents")!!.objects()
        assertEquals("SIG_A", contents[1].arr("parts")!!.objects().single().str("thoughtSignature"))
        val fr = contents[2].arr("parts")!!.objects().single().obj("functionResponse")!!
        assertEquals("set_alarm", fr.str("name")); assertEquals("fc-1", fr.str("id"))
        assertEquals("Done", second.text, "thought parts are not shown to the user")
    }

    @Test fun geminiErrorsAreClassified() = runBlocking {
        val cases = listOf(
            400 to """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"API_KEY_INVALID"}]}}""" to ErrorKind.INVALID_KEY,
            404 to """{"error":{"code":404,"message":"models/x is not found","status":"NOT_FOUND"}}""" to ErrorKind.MODEL_NOT_FOUND,
            429 to """{"error":{"code":429,"message":"Resource exhausted","status":"RESOURCE_EXHAUSTED"}}""" to ErrorKind.RATE_LIMIT,
            429 to """{"error":{"code":429,"message":"quota","status":"RESOURCE_EXHAUSTED","details":[{"violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]}]}}""" to ErrorKind.QUOTA,
            400 to """{"error":{"code":400,"message":"User location is not supported for the API use.","status":"FAILED_PRECONDITION"}}""" to ErrorKind.REGION,
            403 to """{"error":{"code":403,"message":"x","status":"PERMISSION_DENIED"}}""" to ErrorKind.PERMISSION,
            503 to """{"error":{"code":503,"message":"x","status":"UNAVAILABLE"}}""" to ErrorKind.OVERLOADED,
        )
        for ((resp, kind) in cases) {
            val e = assertFailsWith<LlmException> { GeminiProvider(KEY, "m", FakeTransport(resp), "http://x").complete(req(USER)) }
            assertEquals(kind, e.kind, resp.second)
        }
        val blocked = assertFailsWith<LlmException> {
            GeminiProvider(KEY, "m", FakeTransport(200 to """{"promptFeedback":{"blockReason":"SAFETY"}}"""), "http://x").complete(req(USER))
        }
        assertEquals(ErrorKind.CONTENT_BLOCKED, blocked.kind)
    }

    // ─── Settings migration ───
    @Test fun corruptedRouterLabelIsNeverUsedAsModelId() {
        val legacy = "auto:anthropic:claude-sonnet-5-5,openai:gpt-4.1-mini,gemini:gemini-2.5-flash"
        assertFalse(ModelCatalog.isPlausibleModelId(legacy))
        assertEquals("claude-sonnet-5-5", ModelCatalog.sanitize(ProviderId.ANTHROPIC, legacy))
        assertEquals("claude-haiku-5-5", ModelCatalog.sanitize(ProviderId.ANTHROPIC, " claude-haiku-5-5 "))
        assertEquals("gpt-6-luna", ModelCatalog.sanitize(ProviderId.OPENAI, ""))
        assertEquals("gemini-2.5-flash", ModelCatalog.sanitize(ProviderId.GEMINI, "gemini-2.5-flash"))
    }
}
