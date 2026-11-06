package ai.eve.android

import android.app.Application
import ai.eve.android.llm.AnthropicClient
import ai.eve.android.llm.OpenAiClient
import ai.eve.android.llm.GeminiClient
import ai.eve.core.llm.MultiProviderClient
import ai.eve.android.security.SecureSettings
import ai.eve.android.tools.AndroidTools
import ai.eve.core.agent.Agent
import ai.eve.core.audit.FileAuditLog
import ai.eve.core.llm.LlmClient
import ai.eve.core.llm.MockLlmClient
import ai.eve.core.memory.FileMemoryStore
import ai.eve.core.policy.EmergencyStop
import ai.eve.core.policy.PolicyEngine
import ai.eve.core.tasks.TaskTracker
import ai.eve.core.tools.CoreTools
import ai.eve.core.tools.ToolRegistry
import java.io.File

/** Process-wide composition root. Kept deliberately simple (no DI framework) for the prototype. */
class EveApplication : Application() {
    lateinit var runtime: EveRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        runtime = EveRuntime(this)
    }
}

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

    @Volatile var agent: Agent = buildAgent(); private set

    fun llm(): LlmClient {
        val key = settings.apiKey()
        val clients = mutableListOf<LlmClient>()
        if (!key.isNullOrBlank()) clients += AnthropicClient(key, settings.model())
        settings.providerKey("openai")?.takeIf { it.isNotBlank() }?.let { clients += OpenAiClient(it, "gpt-4.1-mini") }
        settings.providerKey("google")?.takeIf { it.isNotBlank() }?.let { clients += GeminiClient(it, "gemini-2.5-flash") }
        return when (clients.size) {
            0 -> MockLlmClient()
            1 -> clients.first()
            else -> MultiProviderClient(clients, settings.collaboration)
        }
    }

    /** Rebuild after the API key or model changes. Conversation history resets. */
    fun rebuildAgent() { agent = buildAgent() }

    private fun buildAgent() = Agent(llm(), tools, policy, stop, memory, audit, tasks)
}

val Application.eve: EveRuntime get() = (this as EveApplication).runtime
