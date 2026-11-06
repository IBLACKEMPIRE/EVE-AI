package ai.eve.core.agent

import ai.eve.core.audit.AuditKind
import ai.eve.core.audit.AuditLog
import ai.eve.core.llm.AllProvidersFailed
import ai.eve.core.llm.ChatMessage
import ai.eve.core.llm.Collaborator
import ai.eve.core.llm.ErrorKind
import ai.eve.core.llm.LlmClient
import ai.eve.core.llm.LlmException
import ai.eve.core.llm.LlmRequest
import ai.eve.core.llm.LlmResponse
import ai.eve.core.llm.ProviderId
import ai.eve.core.llm.Role
import ai.eve.core.memory.MemoryStore
import ai.eve.core.policy.ApprovalGateway
import ai.eve.core.policy.ApprovalRequest
import ai.eve.core.policy.Decision
import ai.eve.core.policy.EmergencyStop
import ai.eve.core.policy.PolicyEngine
import ai.eve.core.tasks.RetryPolicy
import ai.eve.core.tasks.TaskStatus
import ai.eve.core.tasks.TaskTracker
import ai.eve.core.tools.RiskLevel
import ai.eve.core.tools.ToolRegistry
import ai.eve.core.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AgentResult(
    val taskId: String,
    val status: TaskStatus,
    val text: String,
    val emotion: String? = null,
    val steps: Int,
    /** Providers whose output contributed to this answer, in order. Empty for offline demo / failures. */
    val providers: List<ProviderId> = emptyList(),
    /** Set when the answer came from multi-AI collaboration. */
    val synthesizer: ProviderId? = null,
    val collaborated: Boolean = false,
    /** Short safe note for the UI (agreement summary, partial-failure notice). */
    val note: String? = null,
    /** Classified provider failures, safe to show. */
    val diagnostics: List<String> = emptyList(),
)

data class AgentConfig(
    val persona: String = DEFAULT_PERSONA,
    val maxSteps: Int = 6,
    val historyLimit: Int = 20,
    val memoryRecallLimit: Int = 5,
    val retry: RetryPolicy = RetryPolicy(maxAttempts = 2),
) {
    companion object {
        const val DEFAULT_PERSONA = """You are EVE, a warm, capable digital assistant living on the user's phone.
Speak naturally and briefly — your replies are read aloud. Use tools when they help; never claim an action happened unless a tool result confirms it.
Only store memories the user asked you to keep. If a tool result says DENIED or the user declined, accept it and say so plainly.
Optionally begin a reply with an emotion tag like [happy], [concerned], [thinking] or [neutral]."""
    }
}

/**
 * Plan → act → observe loop. The model plans by choosing tool calls; every call passes the
 * PolicyEngine and, when required, the user's ApprovalGateway before it executes.
 * Multi-AI collaboration (when enabled) is a separate, tool-free path.
 */
class Agent(
    private val llm: LlmClient,
    private val tools: ToolRegistry,
    private val policy: PolicyEngine,
    private val stop: EmergencyStop,
    private val memory: MemoryStore,
    private val audit: AuditLog,
    private val tasks: TaskTracker,
    private val config: AgentConfig = AgentConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val collaborator: Collaborator? = null,
) {
    private val history = mutableListOf<ChatMessage>()
    private val mutex = Mutex()

    fun conversation(): List<ChatMessage> = synchronized(history) { history.toList() }
    fun resetConversation() = synchronized(history) { history.clear() }

    suspend fun run(userText: String, approvals: ApprovalGateway, keepInHistory: Boolean = true): AgentResult = mutex.withLock {
        val task = tasks.create(userText)
        audit.record(AuditKind.USER_INPUT, task.id, "received user message (content redacted)")
        if (stop.isEngaged) {
            tasks.update(task.id, TaskStatus.CANCELLED, "emergency stop engaged")
            return AgentResult(task.id, TaskStatus.CANCELLED, "I'm paused by the emergency stop. Tap RESUME to continue.", "concerned", 0)
        }
        tasks.update(task.id, TaskStatus.RUNNING, "started")

        val prior = if (keepInHistory) conversation().takeLast(config.historyLimit).toMutableList() else mutableListOf()
        // Never start the window on an orphaned assistant/tool turn.
        while (prior.isNotEmpty() && prior.first().role != Role.USER) prior.removeAt(0)
        val system = buildSystemPrompt(userText)

        try {
            if (collaborator != null && collaborator.shouldCollaborate(userText)) {
                audit.record(AuditKind.LLM_CALL, task.id, "multi-AI collaboration (read-only, no tools)")
                val c = collaborator.collaborate(system, prior, userText)
                if (c != null) {
                    val (emotion, text) = splitEmotion(c.text, null)
                    val working = prior + ChatMessage(Role.USER, userText) + ChatMessage(Role.ASSISTANT, text)
                    val note = listOfNotNull(c.agreement, c.failures.takeIf { it.isNotEmpty() }?.joinToString("; ") { it.diagnostic() }?.let { "Unavailable: $it" })
                        .joinToString(" · ").ifBlank { null }
                    return finish(task.id, TaskStatus.SUCCEEDED, text, emotion, 1, working, keepInHistory,
                        providers = c.contributors, synthesizer = c.synthesizer, collaborated = true, note = note)
                }
                audit.record(AuditKind.LLM_ERROR, task.id, "collaboration produced no draft; using single-provider path")
            }

            val working = ArrayList(prior)
            working += ChatMessage(Role.USER, userText)
            val executed = mutableSetOf<String>()
            val used = linkedSetOf<ProviderId>()
            var steps = 0
            while (steps < config.maxSteps) {
                currentCoroutineContext().ensureActive()
                if (stop.isEngaged) return finish(task.id, TaskStatus.CANCELLED, "Stopped by emergency stop.", "concerned", steps, working, keepInHistory)
                steps++
                val outcome = callWithRetry(task.id, LlmRequest(system, working, tools.specs()))
                val response = outcome.response
                    ?: return finish(task.id, TaskStatus.FAILED, failureMessage(outcome.error), "concerned", steps, working, keepInHistory,
                        diagnostics = diagnostics(outcome.error))
                response.provider?.let(used::add)

                if (response.toolCalls.isEmpty()) {
                    val (emotion, parsed) = splitEmotion(response.text, response.emotion)
                    val text = when {
                        parsed.isNotBlank() -> parsed
                        response.truncated -> "My answer was cut off by the output limit. Please ask again, or ask for a shorter answer."
                        else -> "I didn't get any text back from the AI service. Please try again."
                    }
                    working += ChatMessage(Role.ASSISTANT, text, providerState = response.providerState)
                    return finish(task.id, TaskStatus.SUCCEEDED, text, emotion, steps, working, keepInHistory, providers = used.toList())
                }

                working += ChatMessage(Role.ASSISTANT, response.text, response.toolCalls, providerState = response.providerState)
                for (call in response.toolCalls) {
                    currentCoroutineContext().ensureActive()
                    val result = executeCall(task.id, call.name, call.arguments, approvals, executed)
                    working += ChatMessage(Role.TOOL, (if (result.ok) "" else "ERROR: ") + result.output, toolCallId = call.id)
                }
            }
            return finish(task.id, TaskStatus.FAILED, "That took more steps than I'm allowed, so I stopped. Could you break it into smaller requests?", "concerned", steps, working, keepInHistory, providers = used.toList())
        } catch (e: CancellationException) {
            tasks.update(task.id, TaskStatus.CANCELLED, "cancelled")
            audit.record(AuditKind.TASK_STATUS, task.id, "CANCELLED")
            throw e
        }
    }

    private suspend fun executeCall(
        taskId: String, name: String, args: Map<String, String>, approvals: ApprovalGateway, executed: MutableSet<String>,
    ): ToolResult {
        val tool = tools[name]
        audit.record(AuditKind.TOOL_PROPOSED, taskId, "$name ${args.keys}")
        // A model (or a fallback model) re-issuing an identical side-effecting call within the same
        // request must not repeat the phone action.
        val fingerprint = name + "|" + args.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
        if (tool != null && tool.risk != RiskLevel.READ_ONLY && fingerprint in executed) {
            audit.record(AuditKind.TOOL_DENIED, taskId, "$name: duplicate call suppressed")
            return ToolResult.ok("Already done earlier in this request; not repeated.")
        }
        when (val d = policy.evaluate(tool, name)) {
            is Decision.Deny -> {
                audit.record(AuditKind.TOOL_DENIED, taskId, "$name: ${d.reason}")
                return ToolResult.error("DENIED: ${d.reason}")
            }
            is Decision.RequireApproval -> {
                tasks.update(taskId, TaskStatus.AWAITING_APPROVAL, name)
                val ok = try {
                    approvals.requestApproval(ApprovalRequest(name, tool!!.risk, tool.describeCall(args), d.reason))
                } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
                tasks.update(taskId, TaskStatus.RUNNING, if (ok) "approved $name" else "declined $name")
                if (!ok || stop.isEngaged) {
                    audit.record(AuditKind.APPROVAL_REJECTED, taskId, name)
                    return ToolResult.error("DENIED: the user declined this action")
                }
                audit.record(AuditKind.APPROVAL_GRANTED, taskId, name)
            }
            Decision.Allow -> Unit
        }
        val result = try {
            tool!!.execute(args)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            ToolResult.error("Tool failed: ${e.message ?: e::class.simpleName}")
        }
        if (result.ok) executed += fingerprint
        audit.record(AuditKind.TOOL_RESULT, taskId, "$name ok=${result.ok} (output redacted)")
        return result
    }

    private class Outcome(val response: LlmResponse?, val error: Exception?)

    private suspend fun callWithRetry(taskId: String, req: LlmRequest): Outcome {
        var attempt = 0
        while (true) {
            attempt++
            try {
                audit.record(AuditKind.LLM_CALL, taskId, "${llm.name} attempt $attempt")
                return Outcome(llm.complete(req), null)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                val retryable = (e as? LlmException)?.retryable ?: true
                // Only the classification is logged — never the provider's message body.
                audit.record(AuditKind.LLM_ERROR, taskId, diagnostics(e).joinToString("; ").ifBlank { e::class.simpleName ?: "error" })
                if (!retryable || attempt >= config.retry.maxAttempts) return Outcome(null, e)
                sleeper(config.retry.delayFor(attempt))
            }
        }
    }

    private fun buildSystemPrompt(userText: String): String {
        val mem = memory.recall(userText, config.memoryRecallLimit)
        val memBlock = if (mem.isEmpty()) "" else "\n\nThings the user asked you to remember:\n" + mem.joinToString("\n") { "- ${it.text}" }
        return config.persona + "\n\nCurrent time (epoch ms): ${clock()}" + memBlock
    }

    private fun finish(
        taskId: String, status: TaskStatus, text: String, emotion: String?, steps: Int, working: List<ChatMessage>, keep: Boolean,
        providers: List<ProviderId> = emptyList(), synthesizer: ProviderId? = null, collaborated: Boolean = false,
        note: String? = null, diagnostics: List<String> = emptyList(),
    ): AgentResult {
        tasks.update(taskId, status, "finished", result = text)
        audit.record(AuditKind.TASK_STATUS, taskId, status.name + if (providers.isEmpty()) "" else " via " + providers.joinToString(",") { it.key })
        if (keep) synchronized(history) {
            history.clear(); history.addAll(working.takeLast(config.historyLimit * 2))
        }
        return AgentResult(taskId, status, text, emotion, steps, providers, synthesizer, collaborated, note, diagnostics)
    }

    companion object {
        private val TAG = Regex("^\\s*\\[([a-zA-Z]+)]\\s*")
        fun splitEmotion(text: String, explicit: String?): Pair<String?, String> {
            val m = TAG.find(text) ?: return explicit to text.trim()
            return (explicit ?: m.groupValues[1].lowercase()) to text.substring(m.range.last + 1).trim()
        }

        fun diagnostics(e: Exception?): List<String> = when (e) {
            null -> emptyList()
            is AllProvidersFailed -> e.diagnostics()
            is LlmException -> listOf(e.diagnostic())
            else -> listOf("unexpected client error (${e::class.simpleName})")
        }

        fun failureMessage(e: Exception?): String {
            val lines = diagnostics(e)
            val kinds = (e as? AllProvidersFailed)?.failures?.map { it.kind } ?: listOfNotNull((e as? LlmException)?.kind)
            if (kinds.isNotEmpty() && kinds.all { it == ErrorKind.NOT_CONFIGURED })
                return "No AI provider is configured. Add an API key in Settings, or keep using offline demo mode."
            return "I couldn't get an answer from the AI service:\n" + lines.joinToString("\n") { "• $it" } +
                "\nOpen Settings → Test all providers for details."
        }
    }
}
