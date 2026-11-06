package ai.eve.core.agent

import ai.eve.core.audit.AuditKind
import ai.eve.core.audit.AuditLog
import ai.eve.core.llm.ChatMessage
import ai.eve.core.llm.LlmClient
import ai.eve.core.llm.LlmException
import ai.eve.core.llm.LlmRequest
import ai.eve.core.llm.LlmResponse
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
import ai.eve.core.tools.ToolRegistry
import ai.eve.core.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AgentResult(val taskId: String, val status: TaskStatus, val text: String, val emotion: String? = null, val steps: Int)

data class AgentConfig(
    val persona: String = DEFAULT_PERSONA,
    val maxSteps: Int = 6,
    val historyLimit: Int = 20,
    val memoryRecallLimit: Int = 5,
    val retry: RetryPolicy = RetryPolicy(),
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
) {
    private val history = mutableListOf<ChatMessage>()
    private val mutex = Mutex()
    private var lastLlmFailure: String = "Unknown provider failure"

    fun conversation(): List<ChatMessage> = synchronized(history) { history.toList() }
    fun resetConversation() = synchronized(history) { history.clear() }

    suspend fun run(userText: String, approvals: ApprovalGateway, keepInHistory: Boolean = true): AgentResult = mutex.withLock {
        val task = tasks.create(userText)
        audit.record(AuditKind.USER_INPUT, task.id, "received user message (content redacted)")
        if (stop.isEngaged) {
            tasks.update(task.id, TaskStatus.CANCELLED, "emergency stop engaged")
            return AgentResult(task.id, TaskStatus.CANCELLED, "I'm paused by the emergency stop. Release it in settings to continue.", "concerned", 0)
        }
        tasks.update(task.id, TaskStatus.RUNNING, "started")

        val working = ArrayList(if (keepInHistory) conversation().takeLast(config.historyLimit) else emptyList())
        // Never start the window on an orphaned tool result.
        while (working.isNotEmpty() && working.first().role != Role.USER) working.removeAt(0)
        working += ChatMessage(Role.USER, userText)
        val system = buildSystemPrompt(userText)

        var steps = 0
        try {
            while (steps < config.maxSteps) {
                currentCoroutineContext().ensureActive()
                if (stop.isEngaged) return finish(task.id, TaskStatus.CANCELLED, "Stopped by emergency stop.", "concerned", steps, working, keepInHistory)
                steps++
                val response = callWithRetry(task.id, LlmRequest(system, working, tools.specs()))
                    ?: return finish(task.id, TaskStatus.FAILED, "AI connection failed: $lastLlmFailure. Check the API keys, model IDs and billing.", "concerned", steps, working, keepInHistory)

                if (response.toolCalls.isEmpty()) {
                    val (emotion, text) = splitEmotion(response.text, response.emotion)
                    working += ChatMessage(Role.ASSISTANT, text)
                    return finish(task.id, TaskStatus.SUCCEEDED, text, emotion, steps, working, keepInHistory)
                }

                working += ChatMessage(Role.ASSISTANT, response.text, response.toolCalls)
                for (call in response.toolCalls) {
                    currentCoroutineContext().ensureActive()
                    val result = executeCall(task.id, call.name, call.arguments, approvals)
                    working += ChatMessage(Role.TOOL, (if (result.ok) "" else "ERROR: ") + result.output, toolCallId = call.id)
                }
            }
            return finish(task.id, TaskStatus.FAILED, "That took more steps than I'm allowed, so I stopped. Could you break it into smaller requests?", "concerned", steps, working, keepInHistory)
        } catch (e: CancellationException) {
            tasks.update(task.id, TaskStatus.CANCELLED, "cancelled")
            audit.record(AuditKind.TASK_STATUS, task.id, "CANCELLED")
            throw e
        }
    }

    private suspend fun executeCall(taskId: String, name: String, args: Map<String, String>, approvals: ApprovalGateway): ToolResult {
        val tool = tools[name]
        audit.record(AuditKind.TOOL_PROPOSED, taskId, "$name ${args.keys}")
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
        audit.record(AuditKind.TOOL_RESULT, taskId, "$name ok=${result.ok} (output redacted)")
        return result
    }

    private suspend fun callWithRetry(taskId: String, req: LlmRequest): LlmResponse? {
        var attempt = 0
        while (true) {
            attempt++
            try {
                audit.record(AuditKind.LLM_CALL, taskId, "${llm.name} attempt $attempt")
                return llm.complete(req)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                val retryable = (e as? LlmException)?.retryable ?: true
                lastLlmFailure = when (e) {
                    is LlmException -> e.message ?: "provider error"
                    else -> "unexpected client error (${e::class.simpleName})"
                }
                audit.record(AuditKind.LLM_ERROR, taskId, "${e::class.simpleName} (message redacted)")
                if (!retryable || attempt >= config.retry.maxAttempts) return null
                sleeper(config.retry.delayFor(attempt))
            }
        }
    }

    private fun buildSystemPrompt(userText: String): String {
        val mem = memory.recall(userText, config.memoryRecallLimit)
        val memBlock = if (mem.isEmpty()) "" else "\n\nThings the user asked you to remember:\n" + mem.joinToString("\n") { "- ${it.text}" }
        return config.persona + "\n\nCurrent time (epoch ms): ${clock()}" + memBlock
    }

    private fun finish(taskId: String, status: TaskStatus, text: String, emotion: String?, steps: Int, working: List<ChatMessage>, keep: Boolean): AgentResult {
        tasks.update(taskId, status, "finished", result = text)
        audit.record(AuditKind.TASK_STATUS, taskId, status.name)
        if (keep) synchronized(history) {
            history.clear(); history.addAll(working.takeLast(config.historyLimit * 2))
        }
        return AgentResult(taskId, status, text, emotion, steps)
    }

    companion object {
        private val TAG = Regex("^\\s*\\[([a-zA-Z]+)]\\s*")
        fun splitEmotion(text: String, explicit: String?): Pair<String?, String> {
            val m = TAG.find(text) ?: return explicit to text.trim()
            return (explicit ?: m.groupValues[1].lowercase()) to text.substring(m.range.last + 1).trim()
        }
    }
}
