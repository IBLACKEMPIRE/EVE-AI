package ai.eve.core

import ai.eve.core.agent.Agent
import ai.eve.core.agent.AgentConfig
import ai.eve.core.audit.InMemoryAuditLog
import ai.eve.core.llm.AllProvidersFailed
import ai.eve.core.llm.ChatMessage
import ai.eve.core.llm.CollaborationEngine
import ai.eve.core.llm.ConnectionTester
import ai.eve.core.llm.ErrorKind
import ai.eve.core.llm.LlmException
import ai.eve.core.llm.LlmRequest
import ai.eve.core.llm.LlmResponse
import ai.eve.core.llm.ProviderId
import ai.eve.core.llm.ProviderRouter
import ai.eve.core.llm.ProviderState
import ai.eve.core.llm.Role
import ai.eve.core.llm.RoutingMode
import ai.eve.core.llm.RoutingPolicy
import ai.eve.core.llm.ToolCall
import ai.eve.core.memory.InMemoryStore
import ai.eve.core.policy.ApprovalGateway
import ai.eve.core.policy.EmergencyStop
import ai.eve.core.policy.PolicyEngine
import ai.eve.core.providers.AnthropicProvider
import ai.eve.core.providers.OpenAiProvider
import ai.eve.core.providers.ProviderClient
import ai.eve.core.tasks.RetryPolicy
import ai.eve.core.tasks.TaskStatus
import ai.eve.core.tasks.TaskTracker
import ai.eve.core.tools.CoreTools
import ai.eve.core.tools.RiskLevel
import ai.eve.core.tools.ToolRegistry
import ai.eve.core.tools.ToolResult
import ai.eve.core.tools.tool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Scripted provider. Each call pops the next behaviour; records the requests it received. */
class FakeProvider(override val id: ProviderId, vararg script: suspend (LlmRequest) -> LlmResponse) : ProviderClient {
    override val model = "${id.key}-model"
    override val name get() = "${id.key}:$model"
    private val queue = ArrayDeque(script.toList())
    val requests = mutableListOf<LlmRequest>()
    override suspend fun complete(request: LlmRequest): LlmResponse {
        requests += request
        val step: suspend (LlmRequest) -> LlmResponse = queue.removeFirstOrNull() ?: { _ -> reply(id, "default") }
        return step(request)
    }
    companion object {
        fun reply(id: ProviderId, text: String, calls: List<ToolCall> = emptyList()) =
            LlmResponse(text, calls, provider = id, model = "m", providerState = ProviderState(id, "m", "[]"))
        fun fail(id: ProviderId, kind: ErrorKind): suspend (LlmRequest) -> LlmResponse = { throw LlmException("x", kind = kind, provider = id) }
    }
}

private val A = ProviderId.ANTHROPIC
private val O = ProviderId.OPENAI
private val G = ProviderId.GEMINI
private val REQ = LlmRequest("s", listOf(ChatMessage(Role.USER, "hi")), emptyList())

class RouterTest {
    @Test fun automaticUsesDefaultOrderAndPreferredFirst() = runBlocking {
        val a = FakeProvider(A, { FakeProvider.reply(A, "a") }); val o = FakeProvider(O, { FakeProvider.reply(O, "o") })
        assertEquals("a", ProviderRouter(listOf(o, a)).complete(REQ).text)
        val o2 = FakeProvider(O, { FakeProvider.reply(O, "o") })
        assertEquals("o", ProviderRouter(listOf(FakeProvider(A), o2), RoutingPolicy(preferred = O)).complete(REQ).text)
    }

    @Test fun manualModeNeverLeavesTheChosenProvider() = runBlocking {
        val g = FakeProvider(G, FakeProvider.fail(G, ErrorKind.INVALID_KEY)); val a = FakeProvider(A)
        val e = assertFailsWith<AllProvidersFailed> { ProviderRouter(listOf(a, g), RoutingPolicy(RoutingMode.MANUAL, G)).complete(REQ) }
        assertEquals(listOf("Gemini: invalid or expired API key"), e.diagnostics())
        assertTrue(a.requests.isEmpty())
    }

    @Test fun fallbackOnProviderFailureAndReportsOutcome() = runBlocking {
        val seen = mutableListOf<Pair<ProviderId, ErrorKind?>>()
        val router = ProviderRouter(
            listOf(FakeProvider(A, FakeProvider.fail(A, ErrorKind.MODEL_NOT_FOUND)), FakeProvider(O, FakeProvider.fail(O, ErrorKind.QUOTA)), FakeProvider(G, { FakeProvider.reply(G, "g") })),
        ) { id, e -> seen += id to e?.kind }
        assertEquals("g", router.complete(REQ).text)
        assertEquals(listOf(A to ErrorKind.MODEL_NOT_FOUND, O to ErrorKind.QUOTA, G to null), seen)
    }

    @Test fun fallbackDisabledStopsAtFirstFailure() = runBlocking {
        val o = FakeProvider(O)
        assertFailsWith<AllProvidersFailed> {
            ProviderRouter(listOf(FakeProvider(A, FakeProvider.fail(A, ErrorKind.NETWORK)), o), RoutingPolicy(fallback = false)).complete(REQ)
        }
        assertTrue(o.requests.isEmpty())
    }

    @Test fun allUnavailableGivesOneDiagnosticPerProvider() = runBlocking {
        val e = assertFailsWith<AllProvidersFailed> {
            ProviderRouter(listOf(FakeProvider(A, FakeProvider.fail(A, ErrorKind.INVALID_KEY)), FakeProvider(O, FakeProvider.fail(O, ErrorKind.QUOTA)), FakeProvider(G, FakeProvider.fail(G, ErrorKind.MODEL_NOT_FOUND)))).complete(REQ)
        }
        assertEquals(listOf("Claude: invalid or expired API key", "GPT: API quota exhausted", "Gemini: unsupported or unavailable model ID"), e.diagnostics())
        assertFalse(e.retryable)
        val msg = Agent.failureMessage(e)
        assertTrue("• GPT: API quota exhausted" in msg)
        assertEquals("No AI provider is configured. Add an API key in Settings, or keep using offline demo mode.",
            Agent.failureMessage(AllProvidersFailed(listOf(LlmException("x", kind = ErrorKind.NOT_CONFIGURED)))))
    }

    @Test fun midToolLoopStaysWithTheProviderThatCalledTheTool() {
        val router = ProviderRouter(listOf(FakeProvider(A), FakeProvider(G)))
        val loop = LlmRequest("s", listOf(
            ChatMessage(Role.USER, "x"),
            ChatMessage(Role.ASSISTANT, "", listOf(ToolCall("1", "get_time", emptyMap())), providerState = ProviderState(G, "m", "[]")),
            ChatMessage(Role.TOOL, "12:00", toolCallId = "1"),
        ), emptyList())
        assertEquals(listOf(G, A), router.order(loop).map { it.id })
        assertEquals(listOf(A, G), router.order(REQ).map { it.id })
    }

    @Test fun cancellationIsNotTreatedAsAFailure() = runBlocking {
        val o = FakeProvider(O)
        val started = CompletableDeferred<Unit>()
        val router = ProviderRouter(listOf(FakeProvider(A, { started.complete(Unit); awaitCancellation() }), o))
        val job = async(Dispatchers.Default) { router.complete(REQ) }
        started.await(); job.cancel()
        assertFailsWith<CancellationException> { job.await() }
        assertTrue(o.requests.isEmpty(), "STOP must not fall through to the next provider")
    }

    @Test fun connectionTesterReportsEachProviderIndependently() = runBlocking {
        val reports = ConnectionTester.testAll(listOf(
            FakeProvider(A, { FakeProvider.reply(A, "OK") }), FakeProvider(O, FakeProvider.fail(O, ErrorKind.INVALID_KEY)), FakeProvider(G, FakeProvider.fail(G, ErrorKind.MODEL_NOT_FOUND)),
        ))
        assertEquals(listOf(true, false, false), reports.map { it.ok })
        assertEquals(listOf(null, ErrorKind.INVALID_KEY, ErrorKind.MODEL_NOT_FOUND), reports.map { it.kind })
        assertTrue(reports[1].message.startsWith("invalid or expired API key"))
    }
}

class CollaborationTest {
    private val complex = "Compare renting versus buying an apartment in Haifa for a student — what should I consider?"

    @Test fun heuristicSelectsAnalysisNotCommands() {
        val h = CollaborationEngine.Heuristic
        assertTrue(h.isComplex(complex))
        assertTrue(h.isComplex("מה עדיף לי ללמוד למבחן בתורת המשפט, סיכומים או מבחנים קודמים?"))
        assertFalse(h.isComplex("open WhatsApp and compare nothing"))
        assertFalse(h.isComplex("remember that I prefer tea over coffee, compare later"))
        assertFalse(h.isComplex("hi"))
        assertFalse(h.isComplex("what time is it?"))
    }

    @Test fun analystsGetNoToolsAndSynthesisReconcilesDrafts() = runBlocking {
        val a = FakeProvider(A, { FakeProvider.reply(A, "Rent: flexible.\nCONFIDENCE: high") }, { r ->
            assertTrue("Analyst A (Claude)" in r.messages.single().content && "Analyst B (GPT)" in r.messages.single().content)
            FakeProvider.reply(A, "ANSWER: Renting is usually better for a student.\nAGREEMENT: Both favour renting; GPT stresses equity.")
        })
        val o = FakeProvider(O, { FakeProvider.reply(O, "Buy only if staying 5+ years.") })
        val g = FakeProvider(G, FakeProvider.fail(G, ErrorKind.QUOTA))
        val r = CollaborationEngine(listOf(a, o, g), preferred = A).collaborate("sys", emptyList(), complex)!!
        assertEquals("Renting is usually better for a student.", r.text)
        assertEquals(listOf(A, O), r.contributors); assertEquals(A, r.synthesizer)
        assertEquals("Both favour renting; GPT stresses equity.", r.agreement)
        assertEquals(listOf("Gemini: API quota exhausted"), r.failures.map { it.diagnostic() })
        (a.requests + o.requests + g.requests).forEach { assertTrue(it.tools.isEmpty(), "collaboration must never expose tools") }
    }

    @Test fun synthesisFailureFallsBackToOneAnswerNotAConcatenation() = runBlocking {
        val a = FakeProvider(A, { FakeProvider.reply(A, "Answer A\nCONFIDENCE: low") }, FakeProvider.fail(A, ErrorKind.OVERLOADED))
        val o = FakeProvider(O, { FakeProvider.reply(O, "Answer O") }, FakeProvider.fail(O, ErrorKind.OVERLOADED))
        val r = CollaborationEngine(listOf(a, o), preferred = O).collaborate("sys", emptyList(), complex)!!
        assertEquals("Answer O", r.text)
        assertNull(r.synthesizer)
        assertFalse("Answer A" in r.text)
    }

    @Test fun singleSurvivorIsReturnedWithoutSynthesisAndNoneMeansNull() = runBlocking {
        val r = CollaborationEngine(listOf(FakeProvider(A, { FakeProvider.reply(A, "Only A\nCONFIDENCE: medium") }), FakeProvider(O, FakeProvider.fail(O, ErrorKind.NETWORK))))
            .collaborate("sys", emptyList(), complex)!!
        assertEquals("Only A", r.text); assertEquals(listOf(A), r.contributors)
        assertNull(CollaborationEngine(listOf(FakeProvider(A, FakeProvider.fail(A, ErrorKind.NETWORK)), FakeProvider(O, FakeProvider.fail(O, ErrorKind.NETWORK))))
            .collaborate("sys", emptyList(), complex))
    }
}

/** Agent wired to the real router + providers over the fake transport. */
class EndToEndTest {
    private class Rig(router: ProviderRouter, collab: CollaborationEngine? = null) {
        val stop = EmergencyStop(); val memory = InMemoryStore(); val audit = InMemoryAuditLog(); val tasks = TaskTracker()
        var alarms = 0
        val registry = ToolRegistry().apply {
            CoreTools.memory(memory).forEach(::register); register(CoreTools.time())
            register(tool("set_alarm", "Alarm", RiskLevel.LOW, mapOf("hour" to "h")) { alarms++; ToolResult.ok("set") })
        }
        val agent = Agent(router, registry, PolicyEngine(stop), stop, memory, audit, tasks, AgentConfig(retry = RetryPolicy(maxAttempts = 2)), sleeper = {}, collaborator = collab)
    }
    private val yes = ApprovalGateway { true }

    @Test fun claudeToolRoundTripPreservesThinkingAndAttributesProvider() = runBlocking {
        val t = FakeTransport(
            200 to """{"content":[{"type":"thinking","thinking":"p","signature":"S"},{"type":"tool_use","id":"toolu_1","name":"set_alarm","input":{"hour":"7"}}],"stop_reason":"tool_use"}""",
            200 to """{"content":[{"type":"text","text":"[happy] Alarm set for 7."}],"stop_reason":"end_turn"}""",
        )
        val rig = Rig(ProviderRouter(listOf(AnthropicProvider(KEY, "claude-sonnet-5-5", t, "http://x"))))
        val res = rig.agent.run("wake me at 7", yes)
        assertEquals(TaskStatus.SUCCEEDED, res.status); assertEquals("Alarm set for 7.", res.text); assertEquals(listOf(A), res.providers)
        assertEquals(1, rig.alarms)
        assertTrue(ai.eve.core.json.Json.stringify(t.lastBody()).contains("\"signature\":\"S\""))
        assertFalse(rig.audit.entries().any { KEY in it.detail })
    }

    @Test fun fallbackAfterInvalidKeyAnswersWithNextProvider() = runBlocking {
        val bad = FakeTransport(401 to """{"type":"error","error":{"type":"authentication_error","message":"invalid"}}""")
        val good = FakeTransport(200 to """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Hello from GPT"}]}]}""")
        val rig = Rig(ProviderRouter(listOf(AnthropicProvider(KEY, "m", bad, "http://x"), OpenAiProvider(KEY, "m", good, "http://y"))))
        val res = rig.agent.run("hello there", yes)
        assertEquals("Hello from GPT", res.text); assertEquals(listOf(O), res.providers)
    }

    @Test fun providerSwitchMidLoopDoesNotRepeatThePhoneAction() = runBlocking {
        // Claude calls the tool, then is overloaded; GPT takes over and (wrongly) asks for the same alarm again.
        val claude = FakeTransport(
            200 to """{"content":[{"type":"tool_use","id":"toolu_1","name":"set_alarm","input":{"hour":"7"}}],"stop_reason":"tool_use"}""",
            529 to """{"type":"error","error":{"type":"overloaded_error","message":"x"}}""",
            529 to """{"type":"error","error":{"type":"overloaded_error","message":"x"}}""",
        )
        val gpt = FakeTransport(
            200 to """{"output":[{"type":"function_call","call_id":"call_2","name":"set_alarm","arguments":"{\"hour\":\"7\"}"}]}""",
            200 to """{"output":[{"type":"message","content":[{"type":"output_text","text":"Your alarm is set."}]}]}""",
        )
        val rig = Rig(ProviderRouter(listOf(AnthropicProvider(KEY, "m", claude, "http://x"), OpenAiProvider(KEY, "m", gpt, "http://y"))))
        val res = rig.agent.run("wake me at 7", yes)
        assertEquals("Your alarm is set.", res.text)
        assertEquals(1, rig.alarms, "the alarm must be set exactly once")
        val gptInput = ai.eve.core.json.Json.stringify(gpt.requests.first().third)
        assertTrue("EVE already executed: set_alarm(hour=7)" in gptInput, "the fallback model is told the action already happened")
        assertEquals(listOf(A, O), res.providers)
    }

    @Test fun diagnosticsReachTheUserWhenEveryProviderFails() = runBlocking {
        val rig = Rig(ProviderRouter(listOf(
            AnthropicProvider(KEY, "auto:anthropic:x,openai:y", FakeTransport(404 to """{"type":"error","error":{"type":"not_found_error","message":"model"}}"""), "http://x"),
            OpenAiProvider(KEY, "m", FakeTransport(429 to """{"error":{"code":"insufficient_quota"}}"""), "http://y"),
        )))
        val res = rig.agent.run("hello there", yes)
        assertEquals(TaskStatus.FAILED, res.status)
        assertEquals(listOf("Claude: unsupported or unavailable model ID", "GPT: API quota exhausted"), res.diagnostics)
        assertFalse("reasoning service" in res.text)
    }

    @Test fun collaborationPathIsUsedOnlyWhenEligibleAndNeverRunsTools() = runBlocking {
        val a = FakeProvider(A,
            { FakeProvider.reply(A, "draft A") }, { FakeProvider.reply(A, "ANSWER: merged\nAGREEMENT: agreed") },
            { FakeProvider.reply(A, "", listOf(ToolCall("t", "set_alarm", mapOf("hour" to "7")))) }, { FakeProvider.reply(A, "done") })
        val o = FakeProvider(O, { FakeProvider.reply(O, "draft O") })
        val rig = Rig(ProviderRouter(listOf(a, o)), CollaborationEngine(listOf(a, o), A))
        val r1 = rig.agent.run("Compare the pros and cons of studying with summaries versus past exams", yes)
        assertTrue(r1.collaborated); assertEquals("merged", r1.text); assertEquals(A, r1.synthesizer); assertEquals(0, rig.alarms)
        val r2 = rig.agent.run("set an alarm for 7", yes)
        assertFalse(r2.collaborated); assertEquals(1, rig.alarms); assertEquals("done", r2.text)
    }

    @Test fun truncatedEmptyAnswerIsExplained() = runBlocking {
        val t = FakeTransport(200 to """{"content":[{"type":"thinking","thinking":"long","signature":"s"}],"stop_reason":"max_tokens"}""")
        val res = Rig(ProviderRouter(listOf(AnthropicProvider(KEY, "m", t, "http://x")))).agent.run("explain", yes)
        assertTrue(res.text.startsWith("My answer was cut off"))
    }
}
