package ai.eve.core.llm

/**
 * Deterministic, offline stand-in for a real model. Lets the app run end-to-end without
 * an API key (demo mode) and gives tests a predictable planner. It only proposes tools
 * that are present in the request.
 */
class MockLlmClient : LlmClient {
    override val name = "offline-demo"
    private var counter = 0

    override suspend fun complete(request: LlmRequest): LlmResponse {
        val last = request.messages.lastOrNull() ?: return LlmResponse("[happy] Hi, I'm EVE.")
        val available = request.tools.map { it.name }.toSet()

        if (last.role == Role.TOOL) {
            val out = last.content
            return when {
                out.startsWith("ERROR: DENIED") -> LlmResponse("[concerned] Okay, I didn't do that.")
                out.startsWith("ERROR:") -> LlmResponse("[concerned] That didn't work: ${out.removePrefix("ERROR: ")}")
                else -> LlmResponse("[happy] Done. $out")
            }
        }

        val text = last.content.trim()
        val lower = text.lowercase()
        fun call(name: String, vararg args: Pair<String, String>): LlmResponse? =
            if (name in available) LlmResponse("", listOf(ToolCall("call_${++counter}", name, mapOf(*args)))) else null

        Regex("^remember (that )?(.+)", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            call("remember", "text" to m.groupValues[2].trimEnd('.'))?.let { return it }
        }
        Regex("remind me in (\\d+) ?(minutes?|mins?|hours?) (to |that )?(.+)", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            val n = m.groupValues[1].toLong() * (if (m.groupValues[2].startsWith("h")) 60 else 1)
            call("schedule_reminder", "minutes" to n.toString(), "message" to m.groupValues[4])?.let { return it }
        }
        if (Regex("forget (everything|all)").containsMatchIn(lower)) call("forget_all_memories")?.let { return it }
        if (Regex("what do you (know|remember)").containsMatchIn(lower)) {
            val q = lower.substringAfter("about", "").trim().trimEnd('?')
            call("recall", "query" to q)?.let { return it }
        }
        if (Regex("\\b(time|date|day is it)\\b").containsMatchIn(lower)) call("get_time")?.let { return it }
        Regex("^open (the )?(.+?)( app)?$", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            call("open_app", "name" to m.groupValues[2])?.let { return it }
        }
        if (Regex("^(hi|hello|hey)\\b").containsMatchIn(lower)) return LlmResponse("[happy] Hi! I'm EVE. I'm in offline demo mode — add an API key in settings for full conversation.")
        return LlmResponse("[thinking] I'm in offline demo mode, so I can only remember things, recall them, tell the time, open apps and set alarms. Add an API key in settings for full conversation.")
    }
}
