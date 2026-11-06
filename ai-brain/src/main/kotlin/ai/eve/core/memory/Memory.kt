package ai.eve.core.memory

import ai.eve.core.audit.FileAuditLog
import java.io.File
import java.util.UUID

data class MemoryItem(val id: String, val text: String, val createdAt: Long, val tags: Set<String> = emptySet())

/**
 * Long-term memory. Only facts the user explicitly authorised (via the `remember` tool
 * or the settings screen) are stored. Retrieval is keyword-based for now; the interface
 * lets us swap in on-device embeddings later without touching the agent.
 */
interface MemoryStore {
    fun remember(text: String, tags: Set<String> = emptySet(), now: Long = System.currentTimeMillis()): MemoryItem
    fun recall(query: String, limit: Int = 5): List<MemoryItem>
    fun forget(id: String): Boolean
    fun all(): List<MemoryItem>
    fun clear()
}

open class InMemoryStore : MemoryStore {
    protected val items = LinkedHashMap<String, MemoryItem>()

    @Synchronized override fun remember(text: String, tags: Set<String>, now: Long): MemoryItem {
        val clean = text.trim()
        require(clean.isNotEmpty()) { "Empty memory" }
        items.values.firstOrNull { it.text.equals(clean, ignoreCase = true) }?.let { return it }
        val item = MemoryItem(UUID.randomUUID().toString(), clean, now, tags)
        items[item.id] = item
        persist()
        return item
    }

    @Synchronized override fun recall(query: String, limit: Int): List<MemoryItem> {
        val q = tokens(query)
        if (q.isEmpty()) return items.values.sortedByDescending { it.createdAt }.take(limit)
        return items.values
            .map { it to score(q, it) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<MemoryItem, Double>> { it.second }.thenByDescending { it.first.createdAt })
            .take(limit)
            .map { it.first }
    }

    @Synchronized override fun forget(id: String): Boolean = (items.remove(id) != null).also { if (it) persist() }
    @Synchronized override fun all(): List<MemoryItem> = items.values.toList()
    @Synchronized override fun clear() { items.clear(); persist() }

    protected open fun persist() {}

    private fun score(q: Set<String>, item: MemoryItem): Double {
        val t = tokens(item.text) + item.tags.map { it.lowercase() }
        val hits = q.count { qt -> t.any { it == qt || (qt.length >= 4 && it.startsWith(qt.take(4))) } }
        return hits.toDouble() / q.size
    }

    companion object {
        private val STOP = setOf("the", "a", "an", "is", "my", "me", "i", "what", "do", "you", "of", "to", "and", "about", "remember", "that", "your", "are", "was")
        fun tokens(s: String): Set<String> =
            s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 && it !in STOP }.toSet()
    }
}

/** File-backed store (one item per line). On Android the file lives in app-private storage. */
class FileMemoryStore(private val file: File) : InMemoryStore() {
    init {
        if (file.exists()) file.readLines().forEach { line ->
            val p = line.split('\t')
            if (p.size == 4) {
                val item = MemoryItem(
                    p[0], FileAuditLog.unesc(p[1]), p[2].toLongOrNull() ?: 0,
                    p[3].split(',').filter { it.isNotBlank() }.toSet(),
                )
                items[item.id] = item
            }
        }
    }

    override fun persist() {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(items.values.joinToString("") { "${it.id}\t${FileAuditLog.esc(it.text)}\t${it.createdAt}\t${it.tags.joinToString(",")}\n" })
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }
}
