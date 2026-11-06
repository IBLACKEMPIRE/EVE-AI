package ai.eve.core.tasks

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class TaskStatus { PENDING, RUNNING, AWAITING_APPROVAL, SUCCEEDED, FAILED, CANCELLED;
    val isTerminal get() = this == SUCCEEDED || this == FAILED || this == CANCELLED }

data class TaskEvent(val at: Long, val status: TaskStatus, val note: String)

data class AgentTask(
    val id: String,
    val title: String,
    val status: TaskStatus,
    val createdAt: Long,
    val history: List<TaskEvent>,
    val result: String? = null,
)

/** Tracks task status and history for the UI and for notifications. */
class TaskTracker(private val clock: () -> Long = System::currentTimeMillis) {
    private val tasks = ConcurrentHashMap<String, AgentTask>()
    private val listeners = mutableListOf<(AgentTask) -> Unit>()

    fun addListener(l: (AgentTask) -> Unit) = synchronized(listeners) { listeners += l }

    fun create(title: String): AgentTask {
        val now = clock()
        val t = AgentTask(UUID.randomUUID().toString(), title.take(120), TaskStatus.PENDING, now, listOf(TaskEvent(now, TaskStatus.PENDING, "created")))
        tasks[t.id] = t
        notify(t)
        return t
    }

    fun update(id: String, status: TaskStatus, note: String = "", result: String? = null): AgentTask? {
        val updated = tasks.computeIfPresent(id) { _, t ->
            if (t.status.isTerminal) t // terminal states are final
            else t.copy(status = status, history = t.history + TaskEvent(clock(), status, note), result = result ?: t.result)
        }
        updated?.let(::notify)
        return updated
    }

    operator fun get(id: String) = tasks[id]
    fun all(): List<AgentTask> = tasks.values.sortedByDescending { it.createdAt }

    private fun notify(t: AgentTask) = synchronized(listeners) { listeners.toList() }.forEach { it(t) }
}

/** Platform-neutral description of a scheduled workflow. Android maps it to WorkManager. */
sealed interface Trigger {
    data class Once(val atEpochMillis: Long) : Trigger
    /** Android's WorkManager minimum is 15 minutes; enforced here so behaviour matches the platform. */
    data class Every(val minutes: Long, val firstAtEpochMillis: Long) : Trigger {
        init { require(minutes >= 15) { "Repeat interval must be at least 15 minutes" } }
    }
}

data class ScheduledWorkflow(val id: String, val title: String, val prompt: String, val trigger: Trigger) {
    fun nextRunAfter(now: Long): Long? = when (trigger) {
        is Trigger.Once -> trigger.atEpochMillis.takeIf { it > now }
        is Trigger.Every -> {
            val period = trigger.minutes * 60_000
            if (now < trigger.firstAtEpochMillis) trigger.firstAtEpochMillis
            else trigger.firstAtEpochMillis + ((now - trigger.firstAtEpochMillis) / period + 1) * period
        }
    }
}

/** Exponential backoff for transient failures (network, rate limits). */
data class RetryPolicy(val maxAttempts: Int = 3, val initialDelayMs: Long = 500, val factor: Double = 2.0, val maxDelayMs: Long = 8_000) {
    fun delayFor(attempt: Int): Long = (initialDelayMs * Math.pow(factor, (attempt - 1).toDouble())).toLong().coerceAtMost(maxDelayMs)
}
