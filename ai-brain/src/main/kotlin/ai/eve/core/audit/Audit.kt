package ai.eve.core.audit

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

enum class AuditKind { USER_INPUT, LLM_CALL, LLM_ERROR, TOOL_PROPOSED, TOOL_DENIED, APPROVAL_GRANTED, APPROVAL_REJECTED, TOOL_RESULT, TASK_STATUS, EMERGENCY_STOP }

data class AuditEntry(val timestamp: Long, val kind: AuditKind, val taskId: String?, val detail: String)

interface AuditLog {
    fun record(kind: AuditKind, taskId: String?, detail: String, now: Long = System.currentTimeMillis())
    fun entries(): List<AuditEntry>
}

class InMemoryAuditLog : AuditLog {
    private val list = CopyOnWriteArrayList<AuditEntry>()
    override fun record(kind: AuditKind, taskId: String?, detail: String, now: Long) { list += AuditEntry(now, kind, taskId, detail) }
    override fun entries(): List<AuditEntry> = list.toList()
}

/**
 * Append-only, tab-separated log. One entry per line; tabs/newlines/backslashes escaped.
 * Never store secrets here — callers pass summaries, not raw credentials.
 */
class FileAuditLog(private val file: File, private val maxEntries: Int = 5000) : AuditLog {
    private val lock = Any()

    override fun record(kind: AuditKind, taskId: String?, detail: String, now: Long) = synchronized(lock) {
        file.parentFile?.mkdirs()
        file.appendText("$now\t${kind.name}\t${esc(taskId ?: "")}\t${esc(detail.take(2000))}\n")
        trimIfNeeded()
    }

    override fun entries(): List<AuditEntry> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        file.readLines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size != 4) return@mapNotNull null
            val kind = runCatching { AuditKind.valueOf(p[1]) }.getOrNull() ?: return@mapNotNull null
            AuditEntry(p[0].toLongOrNull() ?: 0, kind, unesc(p[2]).ifEmpty { null }, unesc(p[3]))
        }
    }

    private fun trimIfNeeded() {
        val lines = file.readLines()
        if (lines.size > maxEntries) file.writeText(lines.takeLast(maxEntries).joinToString("\n", postfix = "\n"))
    }

    companion object {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
        fun unesc(s: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) { 't' -> sb.append('\t'); 'n' -> sb.append('\n'); else -> sb.append(s[i + 1]) }
                    i += 2
                } else { sb.append(c); i++ }
            }
            return sb.toString()
        }
    }
}
