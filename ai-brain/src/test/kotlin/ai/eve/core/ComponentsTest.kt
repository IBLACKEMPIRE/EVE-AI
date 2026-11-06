package ai.eve.core

import ai.eve.core.agent.Agent
import ai.eve.core.audit.AuditKind
import ai.eve.core.audit.FileAuditLog
import ai.eve.core.avatar.Emotion
import ai.eve.core.avatar.Viseme
import ai.eve.core.avatar.VisemeMapper
import ai.eve.core.memory.FileMemoryStore
import ai.eve.core.memory.InMemoryStore
import ai.eve.core.policy.Decision
import ai.eve.core.policy.EmergencyStop
import ai.eve.core.policy.PolicyEngine
import ai.eve.core.tasks.RetryPolicy
import ai.eve.core.tasks.ScheduledWorkflow
import ai.eve.core.tasks.TaskStatus
import ai.eve.core.tasks.TaskTracker
import ai.eve.core.tasks.Trigger
import ai.eve.core.tools.RiskLevel
import ai.eve.core.tools.ToolRegistry
import ai.eve.core.tools.ToolResult
import ai.eve.core.tools.tool
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComponentsTest {
    private fun t(risk: RiskLevel) = tool("t_${risk.name.lowercase()}", "d", risk) { ToolResult.ok("") }

    @Test fun policyMapsRiskLevels() {
        val p = PolicyEngine(EmergencyStop())
        assertIs<Decision.Allow>(p.evaluate(t(RiskLevel.READ_ONLY), "x"))
        assertIs<Decision.Allow>(p.evaluate(t(RiskLevel.LOW), "x"))
        for (r in listOf(RiskLevel.SENSITIVE, RiskLevel.DESTRUCTIVE, RiskLevel.FINANCIAL, RiskLevel.CREDENTIAL))
            assertIs<Decision.RequireApproval>(p.evaluate(t(r), "x"), r.name)
        assertIs<Decision.Deny>(p.evaluate(null, "ghost"))
    }

    @Test fun onlySensitiveToolsAreGrantable() {
        val p = PolicyEngine(EmergencyStop())
        for (r in RiskLevel.values()) assertEquals(r == RiskLevel.SENSITIVE, p.grant(t(r)), r.name)
        assertIs<Decision.Allow>(p.evaluate(t(RiskLevel.SENSITIVE), "x"))
        p.revoke("t_sensitive")
        assertIs<Decision.RequireApproval>(p.evaluate(t(RiskLevel.SENSITIVE), "x"))
    }

    @Test fun emergencyStopAndBlockDenyEverything() {
        val stop = EmergencyStop()
        val p = PolicyEngine(stop)
        p.block("t_low")
        assertIs<Decision.Deny>(p.evaluate(t(RiskLevel.LOW), "t_low"))
        stop.engage()
        assertIs<Decision.Deny>(p.evaluate(t(RiskLevel.READ_ONLY), "x"))
        stop.release()
        assertIs<Decision.Allow>(p.evaluate(t(RiskLevel.READ_ONLY), "x"))
    }

    @Test fun registryRejectsBadAndDuplicateNames() {
        val reg = ToolRegistry().register(t(RiskLevel.LOW))
        assertFailsWith<IllegalArgumentException> { reg.register(t(RiskLevel.LOW)) }
        assertFailsWith<IllegalArgumentException> { reg.register(tool("Bad Name", "d", RiskLevel.LOW) { ToolResult.ok("") }) }
    }

    @Test fun memoryRanksDedupesAndForgets() {
        val m = InMemoryStore()
        val a = m.remember("Sister is called Dana", now = 1)
        m.remember("Favourite drink is mint tea", now = 2)
        assertEquals(a.id, m.remember("sister is called dana").id, "duplicate must not be stored twice")
        assertEquals(2, m.all().size)
        assertEquals("Sister is called Dana", m.recall("who is my sister?").first().text)
        assertTrue(m.recall("quantum physics").isEmpty())
        assertEquals(2, m.recall("").size)
        assertTrue(m.forget(a.id))
        assertEquals(1, m.all().size)
    }

    @Test fun fileMemorySurvivesRestartWithSpecialCharacters() {
        val f = Files.createTempDirectory("eve").resolve("memory.tsv").toFile()
        val tricky = "line1\nline2\twith tab \\ backslash — עברית"
        FileMemoryStore(f).apply { remember(tricky, setOf("family")); remember("second") }
        val reloaded = FileMemoryStore(f)
        assertEquals(listOf(tricky, "second"), reloaded.all().map { it.text })
        assertEquals(setOf("family"), reloaded.all().first().tags)
    }

    @Test fun fileAuditLogRoundTripsAndTrims() {
        val f = Files.createTempDirectory("eve").resolve("audit.tsv").toFile()
        val log = FileAuditLog(f, maxEntries = 3)
        repeat(5) { log.record(AuditKind.TOOL_RESULT, "task$it", "detail\t$it\nx", now = it.toLong()) }
        val e = log.entries()
        assertEquals(3, e.size)
        assertEquals("detail\t4\nx", e.last().detail)
        assertEquals("task2", e.first().taskId)
    }

    @Test fun taskTrackerKeepsHistoryAndTerminalStatesAreFinal() {
        var now = 0L
        val tr = TaskTracker { now++ }
        val seen = mutableListOf<TaskStatus>()
        tr.addListener { seen += it.status }
        val task = tr.create("do it")
        tr.update(task.id, TaskStatus.RUNNING)
        tr.update(task.id, TaskStatus.SUCCEEDED, result = "done")
        tr.update(task.id, TaskStatus.FAILED)
        assertEquals(TaskStatus.SUCCEEDED, tr[task.id]!!.status)
        assertEquals(listOf(TaskStatus.PENDING, TaskStatus.RUNNING, TaskStatus.SUCCEEDED), tr[task.id]!!.history.map { it.status })
        assertEquals("done", tr[task.id]!!.result)
        assertEquals(TaskStatus.PENDING, seen.first())
    }

    @Test fun scheduleComputesNextRun() {
        val once = ScheduledWorkflow("1", "t", "p", Trigger.Once(1000))
        assertEquals(1000L, once.nextRunAfter(0))
        assertNull(once.nextRunAfter(1000))
        val every = ScheduledWorkflow("2", "t", "p", Trigger.Every(15, 0))
        assertEquals(900_000L, every.nextRunAfter(0))
        assertEquals(1_800_000L, every.nextRunAfter(900_000))
        assertFailsWith<IllegalArgumentException> { Trigger.Every(5, 0) }
    }

    @Test fun retryBackoffIsBounded() {
        val r = RetryPolicy(initialDelayMs = 500, factor = 2.0, maxDelayMs = 3000)
        assertEquals(listOf(500L, 1000L, 2000L, 3000L, 3000L), (1..5).map(r::delayFor))
    }

    @Test fun emotionTagIsParsedAndStripped() {
        assertEquals("happy" to "Hello!", Agent.splitEmotion("[Happy] Hello!", null))
        assertEquals(null to "No tag", Agent.splitEmotion("No tag", null))
        assertEquals(Emotion.CONCERNED, Emotion.parse("warning"))
        assertEquals(Emotion.NEUTRAL, Emotion.parse(null))
    }

    @Test fun visemesCoverDigraphsMergeRepeatsAndStayInRange() {
        val v = VisemeMapper.fromText("the shop")
        assertEquals(Viseme.TH, v.first().viseme)
        assertTrue(v.any { it.viseme == Viseme.CH })
        val merged = VisemeMapper.fromText("aaa")
        assertEquals(1, merged.size)
        assertEquals(3 * 65L, merged[0].durationMs)
        val timeline = VisemeMapper.fromText("Hello world, I'm EVE.")
        timeline.zipWithNext().forEach { (a, b) -> assertEquals(a.startMs + a.durationMs, b.startMs) }
        Viseme.values().forEach { assertTrue(VisemeMapper.openness(it) in 0f..1f) }
        assertEquals(Viseme.AA, VisemeMapper.fromText("שלום").first().viseme)
    }
}
