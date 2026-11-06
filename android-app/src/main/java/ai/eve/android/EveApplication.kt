package ai.eve.android

import android.app.Application
import ai.eve.android.security.SecureSettings
import ai.eve.android.tools.AndroidTools
import ai.eve.core.agent.Agent
import ai.eve.core.audit.AuditKind
import ai.eve.core.audit.FileAuditLog
import ai.eve.core.llm.CollaborationEngine
import ai.eve.core.llm.ConnectionReport
import ai.eve.core.llm.ConnectionTester
import ai.eve.core.llm.ErrorKind
import ai.eve.core.llm.LlmClient
import ai.eve.core.llm.LlmException
import ai.eve.core.llm.MockLlmClient
import ai.eve.core.llm.ProviderId
import ai.eve.core.llm.ProviderRouter
import ai.eve.core.llm.RoutingPolicy
import ai.eve.core.memory.FileMemoryStore
import ai.eve.core.policy.EmergencyStop
import ai.eve.core.policy.PolicyEngine
import ai.eve.core.providers.AnthropicProvider
import ai.eve.core.providers.GeminiProvider
import ai.eve.core.providers.OpenAiProvider
import ai.eve.core.providers.ProviderClient
import ai.eve.core.tasks.TaskTracker
import ai.eve.core.tools.CoreTools
import ai.eve.core.tools.ToolRegistry
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Process-wide composition root. Kept deliberately simple (no DI framework) for the prototype. */
class EveApplication : Application() {
    lateinit var runtime: EveRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        runtime = EveRuntime(this)
    }
}

/** Last known health of a provider, from a test or a real request. Safe to display. */
data class ProviderHealth(val ok: Boolean, val message: String, val at: Long)

class EveRuntime(app: Application) {
    val settings = SecureSettings(app)
    val stop = EmergencyStop()
    val policy = PolicyEngine(stop)
    val memory = FileMemoryStore(File(app.filesDir, "eve/memory.tsv"))
    val audit = FileAuditLog(File(app.filesDir, "eve/audit.tsv"))
    val tasks = TaskTracker()
    val tools = ToolRegistry().apply {
        register(CoreTools.time())
        CoreTools.memory(memory).forEach(::register)
        AndroidTools.all(app, tasks).forEach(::register)
    }

    val health = ConcurrentHashMap<ProviderId, ProviderHealth>()
    @Volatile var onHealthChanged: () -> Unit = {}

    init {
        if (settings.migratedCorruptModel) audit.record(AuditKind.TASK_STATUS, null, "settings migration: removed invalid saved model ID")
    }

    @Volatile var agent: Agent = buildAgent(); private set

    fun client(id: ProviderId, key: String, model: String): ProviderClient = when (id) {
        ProviderId.ANTHROPIC -> AnthropicProvider(key, model)
        ProviderId.OPENAI -> OpenAiProvider(key, model)
        ProviderId.GEMINI -> GeminiProvider(key, model)
    }

    /** Providers the user has enabled and given a readable key, in default order. */
    fun activeProviders(): List<ProviderClient> = ProviderId.entries
        .filter { settings.enabled(it) }
        .mapNotNull { id -> settings.key(id)?.let { client(id, it, settings.model(id)) } }

    fun llm(providers: List<ProviderClient> = activeProviders()): LlmClient =
        if (providers.isEmpty()) MockLlmClient()
        else ProviderRouter(providers, RoutingPolicy(settings.mode, settings.preferred, settings.fallback), ::recordOutcome)

    /** Rebuild after a settings change. The conversation is carried over. */
    fun rebuildAgent() {
        val history = agent.conversation()
        agent = buildAgent().also { it.restoreConversation(history) }
    }

    private fun buildAgent(): Agent {
        val providers = activeProviders()
        val collaborator = if (settings.collaboration && providers.size >= 2) CollaborationEngine(providers, settings.preferred) else null
        return Agent(llm(providers), tools, policy, stop, memory, audit, tasks, collaborator = collaborator)
    }

    private fun recordOutcome(id: ProviderId, e: LlmException?) {
        health[id] = if (e == null) ProviderHealth(true, "Last request succeeded", System.currentTimeMillis())
        else ProviderHealth(false, "Last request failed: ${e.kind.summary} — ${ConnectionReport.hint(e.kind)}", System.currentTimeMillis())
        onHealthChanged()
    }

    /** Tests one provider with its saved key and model, even if it is currently disabled. */
    suspend fun testProvider(id: ProviderId): ConnectionReport {
        val key = settings.key(id)
        val report = if (key == null) ConnectionReport(id, settings.model(id), false, ErrorKind.NOT_CONFIGURED, 0)
        else ConnectionTester.test(client(id, key, settings.model(id)))
        health[id] = ProviderHealth(report.ok, report.message, System.currentTimeMillis())
        audit.record(AuditKind.LLM_CALL, null, "connection test ${id.key}: ${if (report.ok) "ok" else report.kind?.name}")
        onHealthChanged()
        return report
    }
}

val Application.eve: EveRuntime get() = (this as EveApplication).runtime
