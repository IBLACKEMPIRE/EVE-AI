package ai.eve.core.live

import ai.eve.core.agent.Agent
import ai.eve.core.audit.InMemoryAuditLog
import ai.eve.core.llm.ConnectionTester
import ai.eve.core.llm.ProviderId
import ai.eve.core.llm.ProviderRouter
import ai.eve.core.llm.RoutingMode
import ai.eve.core.llm.RoutingPolicy
import ai.eve.core.memory.InMemoryStore
import ai.eve.core.policy.DenyAllApprovals
import ai.eve.core.policy.EmergencyStop
import ai.eve.core.policy.PolicyEngine
import ai.eve.core.providers.AnthropicProvider
import ai.eve.core.providers.GeminiProvider
import ai.eve.core.providers.ModelCatalog
import ai.eve.core.providers.OpenAiProvider
import ai.eve.core.providers.ProviderClient
import ai.eve.core.tasks.TaskStatus
import ai.eve.core.tasks.TaskTracker
import ai.eve.core.tools.CoreTools
import ai.eve.core.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * Optional live API smoke test. Never part of `test`; run with
 *   EVE_ANTHROPIC_API_KEY=… EVE_OPENAI_API_KEY=… EVE_GEMINI_API_KEY=… ./gradlew :ai-brain:liveSmoke
 * Optional EVE_<PROVIDER>_MODEL overrides the default model. Prints only classifications,
 * never keys or response bodies. Exit code 1 if any configured provider fails.
 */
fun main() = runBlocking {
    val env = System.getenv()
    fun cfg(id: ProviderId, prefix: String, make: (String, String) -> ProviderClient): ProviderClient? =
        env["EVE_${prefix}_API_KEY"]?.takeIf { it.isNotBlank() }?.let { make(it, ModelCatalog.sanitize(id, env["EVE_${prefix}_MODEL"])) }
    val clients = listOfNotNull(
        cfg(ProviderId.ANTHROPIC, "ANTHROPIC") { k, m -> AnthropicProvider(k, m) },
        cfg(ProviderId.OPENAI, "OPENAI") { k, m -> OpenAiProvider(k, m) },
        cfg(ProviderId.GEMINI, "GEMINI") { k, m -> GeminiProvider(k, m) },
    )
    if (clients.isEmpty()) { println("SKIPPED: no EVE_*_API_KEY variables set"); return@runBlocking }
    var failed = false
    for (c in clients) {
        val report = ConnectionTester.test(c)
        println("[${c.id.displayName}] connection: ${if (report.ok) "OK" else "FAIL"} — ${report.message}")
        if (!report.ok) { failed = true; continue }
        // Tool round trip through the real agent loop (read-only tool, so nothing changes).
        val stop = EmergencyStop(); val tasks = TaskTracker()
        val tools = ToolRegistry().register(CoreTools.time())
        val router = ProviderRouter(listOf(c), RoutingPolicy(RoutingMode.MANUAL, c.id, fallback = false))
        val agent = Agent(router, tools, PolicyEngine(stop), stop, InMemoryStore(), InMemoryAuditLog(), tasks)
        val res = agent.run("What is the current date and time? Use the get_time tool, then answer in one sentence.", DenyAllApprovals)
        val usedTool = agent.conversation().any { m -> m.toolCalls.any { it.name == "get_time" } }
        val ok = res.status == TaskStatus.SUCCEEDED && usedTool
        println("[${c.id.displayName}] tool round trip: ${if (ok) "OK" else "FAIL"} — status=${res.status}, tool_called=$usedTool, steps=${res.steps}" +
            if (res.diagnostics.isEmpty()) "" else ", ${res.diagnostics.joinToString("; ")}")
        if (!ok) failed = true
    }
    if (failed) exitProcess(1)
}
