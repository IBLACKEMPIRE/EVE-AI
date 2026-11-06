package ai.eve.core.tools

import ai.eve.core.memory.MemoryStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Tools that work on every platform (Android, Windows, robot host). */
object CoreTools {
    fun time(clock: () -> Long = System::currentTimeMillis, zone: () -> ZoneId = ZoneId::systemDefault) = tool(
        "get_time", "Get the current local date and time.", RiskLevel.READ_ONLY,
    ) {
        ToolResult.ok(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm").format(Instant.ofEpochMilli(clock()).atZone(zone())))
    }

    fun memory(store: MemoryStore): List<Tool> = listOf(
        tool(
            "remember", "Save a fact the user explicitly asked you to remember.", RiskLevel.LOW,
            mapOf("text" to "The fact to store, in third person, e.g. 'User's sister is Dana'"),
            describe = { "Remember: ${it["text"]}" },
        ) { args ->
            val text = args["text"]?.trim().orEmpty()
            if (text.isEmpty()) ToolResult.error("text is required")
            else ToolResult.ok("Stored memory ${store.remember(text).id.take(8)}: $text")
        },
        tool(
            "recall", "Search facts the user previously asked you to remember.", RiskLevel.READ_ONLY,
            mapOf("query" to "Keywords to search for; empty for most recent"),
        ) { args ->
            val found = store.recall(args["query"].orEmpty(), 8)
            ToolResult.ok(if (found.isEmpty()) "No matching memories." else found.joinToString("\n") { "[${it.id.take(8)}] ${it.text}" })
        },
        tool(
            "forget_memory", "Delete one stored memory by its id prefix.", RiskLevel.DESTRUCTIVE,
            mapOf("id" to "Id or 8-character id prefix shown by recall"),
            describe = { args -> "Delete memory: " + (store.all().firstOrNull { m -> m.id.startsWith(args["id"].orEmpty()) }?.text ?: args["id"]) },
        ) { args ->
            val prefix = args["id"].orEmpty()
            val match = store.all().filter { prefix.length >= 4 && it.id.startsWith(prefix) }
            when (match.size) {
                0 -> ToolResult.error("No memory with id $prefix")
                1 -> { store.forget(match[0].id); ToolResult.ok("Forgot: ${match[0].text}") }
                else -> ToolResult.error("Id prefix is ambiguous")
            }
        },
        tool(
            "forget_all_memories", "Delete ALL stored memories.", RiskLevel.DESTRUCTIVE,
            describe = { "Delete all ${store.all().size} stored memories" },
        ) { val n = store.all().size; store.clear(); ToolResult.ok("Deleted $n memories") },
    )
}
