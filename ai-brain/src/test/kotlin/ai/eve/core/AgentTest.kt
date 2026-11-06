package ai.eve.core

import ai.eve.core.agent.Agent
import ai.eve.core.agent.AgentConfig
import ai.eve.core.audit.AuditKind
import ai.eve.core.audit.InMemoryAuditLog
import ai.eve.core.llm.LlmClient
import ai.eve.core.llm.LlmException
import ai.eve.core.llm.LlmRequest
import ai.eve.core.llm.LlmResponse
import ai.eve.core.llm.MockLlmClient
import ai.eve.core.llm.ToolCall
import ai.eve.core.memory.InMemoryStore
import ai.eve.core.policy.ApprovalGateway
import ai.eve.core.policy.DenyAllApprovals
import ai.eve.core.policy.EmergencyStop
import ai.eve.core.policy.PolicyEngine
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
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentTest {
    private class Rig(llm: LlmClient = MockLlmClient(), maxSteps: Int = 6) {
        val stop = EmergencyStop()
        val policy = PolicyEngine(stop)
        val memory = InMemoryStore()
        val audit = InMemoryAuditLog()
        val tasks = TaskTracker()
        val registry = ToolRegistry().apply { CoreTools.memory(memory).forEach { register(it) }; register(CoreTools.time()) }
        var sleeps = 0
        val agent = Agent(llm, registry, policy, stop, memory, audit, tasks,
            AgentConfig(maxSteps = maxSteps, retry = RetryPolicy(maxAttempts = 3)), sleeper = { sleeps++ })
    }

    /** Scripted model: returns responses in order. */
    private class Scripted(vararg val responses: LlmResponse) : LlmClient {
        override val name = "scripted"
        var i = 0
        override suspend fun complete(request: LlmRequest) = responses[minOf(i++, responses.lastIndex)]
    }

    private val approveAll = ApprovalGateway { true }

    @Test fun remembersAndRecallsThroughTools() = runBlocking {
        val r = Rig()
        val res = r.agent.run("Remember that my sister is Dana", DenyAllApprovals)
        assertEquals(TaskStatus.SUCCEEDED, res.status)
        assertEquals(listOf("my sister is Dana"), r.memory.all().map { it.text })
        val recall = r.agent.run("What do you remember about sister?", DenyAllApprovals)
        assertTrue("Dana" in recall.text, recall.text)
        assertEquals("happy", recall.emotion)
    }

    @Test fun destructiveToolRequiresApprovalAndHonoursDecline() = runBlocking {
        val r = Rig()
        r.memory.remember("likes tea")
        val asked = mutableListOf<String>()
        val res = r.agent.run("forget everything", ApprovalGateway { asked += it.summary; false })
        assertEquals(1, asked.size)
        assertTrue(asked[0].contains("Delete all 1"))
        assertEquals(1, r.memory.all().size, "memory must survive a declined delete")
        assertTrue(res.text.contains("didn't"))
        assertTrue(r.audit.entries().any { it.kind == AuditKind.APPROVAL_REJECTED })
    }

    @Test fun destructiveToolRunsWhenApproved() = runBlocking {
        val r = Rig()
        r.memory.remember("likes tea")
        r.agent.run("forget everything", approveAll)
        assertTrue(r.memory.all().isEmpty())
        assertTrue(r.audit.entries().any { it.kind == AuditKind.APPROVAL_GRANTED })
    }

    @Test fun financialToolNeverRunsHeadless() = runBlocking {
        var paid = false
        val llm = Scripted(LlmResponse("", listOf(ToolCall("1", "pay", mapOf("amount" to "100")))), LlmResponse("ok"))
        val r = Rig(llm)
        r.registry.register(tool("pay", "pay", RiskLevel.FINANCIAL) { paid = true; ToolResult.ok("paid") })
        assertFalse(r.policy.grant(r.registry["pay"]!!), "financial tools must not accept standing grants")
        r.agent.run("pay", DenyAllApprovals)
        assertFalse(paid)
    }

    @Test fun unknownToolIsDeniedNotCrashed() = runBlocking {
        val r = Rig(Scripted(LlmResponse("", listOf(ToolCall("1", "rm_rf", emptyMap()))), LlmResponse("sorry")))
        val res = r.agent.run("x", approveAll)
        assertEquals(TaskStatus.SUCCEEDED, res.status)
        assertTrue(r.audit.entries().any { it.kind == AuditKind.TOOL_DENIED && "Unknown tool" in it.detail })
    }

    @Test fun emergencyStopHaltsRunningAgent() = runBlocking {
        lateinit var rig: Rig
        val llm = Scripted(LlmResponse("", listOf(ToolCall("1", "trip", emptyMap()))), LlmResponse("", listOf(ToolCall("2", "trip", emptyMap()))))
        rig = Rig(llm)
        var runs = 0
        rig.registry.register(tool("trip", "engages stop", RiskLevel.LOW) { runs++; rig.stop.engage(); ToolResult.ok("tripped") })
        val res = rig.agent.run("go", approveAll)
        assertEquals(TaskStatus.CANCELLED, res.status)
        assertEquals(1, runs)
        val again = rig.agent.run("go again", approveAll)
        assertEquals(TaskStatus.CANCELLED, again.status)
        assertEquals(1, runs, "no tools may run while stop is engaged")
    }

    @Test fun retriesTransientLlmFailures() = runBlocking {
        var calls = 0
        val flaky = object : LlmClient {
            override val name = "flaky"
            override suspend fun complete(request: LlmRequest): LlmResponse {
                if (++calls < 3) throw LlmException("503")
                return LlmResponse("[happy] recovered")
            }
        }
        val r = Rig(flaky)
        val res = r.agent.run("hi", DenyAllApprovals)
        assertEquals(TaskStatus.SUCCEEDED, res.status)
        assertEquals("recovered", res.text)
        assertEquals(2, r.sleeps)
    }

    @Test fun givesUpAfterMaxAttemptsAndDoesNotRetryFatalErrors() = runBlocking {
        var calls = 0
        val broken = object : LlmClient {
            override val name = "broken"
            override suspend fun complete(request: LlmRequest): LlmResponse { calls++; throw LlmException("401 bad key", retryable = false) }
        }
        val r = Rig(broken)
        val res = r.agent.run("hi", DenyAllApprovals)
        assertEquals(TaskStatus.FAILED, res.status)
        assertEquals(1, calls)
    }

    @Test fun toolExceptionBecomesErrorResult() = runBlocking {
        val r = Rig(Scripted(LlmResponse("", listOf(ToolCall("1", "boom", emptyMap()))), LlmResponse("reported")))
        r.registry.register(tool("boom", "throws", RiskLevel.LOW) { error("disk full") })
        val res = r.agent.run("x", approveAll)
        assertEquals(TaskStatus.SUCCEEDED, res.status)
        val toolMsg = r.agent.conversation().first { it.role == ai.eve.core.llm.Role.TOOL }
        assertTrue(toolMsg.content.startsWith("ERROR: Tool failed: disk full"), toolMsg.content)
    }

    @Test fun stepLimitStopsRunawayLoops() = runBlocking {
        val r = Rig(Scripted(LlmResponse("", listOf(ToolCall("1", "get_time", emptyMap())))), maxSteps = 3)
        val res = r.agent.run("loop", approveAll)
        assertEquals(TaskStatus.FAILED, res.status)
        assertEquals(3, res.steps)
    }

    @Test fun cancellationMarksTaskCancelled() = runBlocking {
        val gate = CompletableDeferred<Boolean>()
        val r = Rig(Scripted(LlmResponse("", listOf(ToolCall("1", "forget_all_memories", emptyMap())))))
        val job = async(Dispatchers.Default) { r.agent.run("forget everything", ApprovalGateway { gate.await() }) }
        while (r.tasks.all().none { it.status == TaskStatus.AWAITING_APPROVAL }) Thread.sleep(5)
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }
        assertEquals(TaskStatus.CANCELLED, r.tasks.all().first().status)
    }

    @Test fun sensitiveToolUsesStandingGrant() = runBlocking {
        var asked = 0
        val llm = Scripted(LlmResponse("", listOf(ToolCall("1", "read_sms", emptyMap()))), LlmResponse("done"))
        val r = Rig(llm)
        r.registry.register(tool("read_sms", "read", RiskLevel.SENSITIVE) { ToolResult.ok("2 messages") })
        assertTrue(r.policy.grant(r.registry["read_sms"]!!))
        r.agent.run("read", ApprovalGateway { asked++; true })
        assertEquals(0, asked)
    }

    @Test fun conversationHistoryCarriesAcrossTurns() = runBlocking {
        val seen = mutableListOf<Int>()
        val llm = object : LlmClient {
            override val name = "count"
            override suspend fun complete(request: LlmRequest): LlmResponse { seen += request.messages.size; return LlmResponse("ok") }
        }
        val r = Rig(llm)
        r.agent.run("one", DenyAllApprovals)
        r.agent.run("two", DenyAllApprovals)
        assertEquals(listOf(1, 3), seen)
    }
}
