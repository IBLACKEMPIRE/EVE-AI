package ai.eve.core.llm

import ai.eve.core.providers.ProviderClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

data class CollaborationResult(
    val text: String,
    val contributors: List<ProviderId>,
    val synthesizer: ProviderId?,
    /** One sentence on where the analysts agreed / disagreed, when a synthesis ran. */
    val agreement: String?,
    val failures: List<LlmException>,
)

/** Hook the Agent uses; null means collaboration is off. */
interface Collaborator {
    fun shouldCollaborate(userText: String): Boolean
    suspend fun collaborate(system: String, history: List<ChatMessage>, userText: String): CollaborationResult?
}

/**
 * Multi-AI collaboration as a **read-only** analysis stage: analysts never receive tool
 * declarations, so no secondary model can trigger a phone action. Then an explicit synthesis
 * stage reconciles the drafts into one answer. Runs only for questions that benefit from it.
 */
class CollaborationEngine(
    private val providers: List<ProviderClient>,
    private val preferred: ProviderId? = null,
    private val analystTimeoutMs: Long = 60_000,
    private val synthesisTimeoutMs: Long = 60_000,
) : Collaborator {

    override fun shouldCollaborate(userText: String): Boolean = providers.size >= 2 && Heuristic.isComplex(userText)

    override suspend fun collaborate(system: String, history: List<ChatMessage>, userText: String): CollaborationResult? {
        val transcript = textOnly(history) + ChatMessage(Role.USER, userText)
        val analystSystem = system + ANALYST_RULES
        val outcomes = coroutineScope {
            providers.map { p ->
                async {
                    try {
                        val r = withTimeoutOrNull(analystTimeoutMs) { p.complete(LlmRequest(analystSystem, transcript, tools = emptyList(), maxTokens = 2048)) }
                            ?: throw LlmException("timeout", kind = ErrorKind.TIMEOUT, provider = p.id)
                        Result.success(p to r.text.trim())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure<Pair<ProviderClient, String>>(e as? LlmException ?: LlmException(e.message ?: "error", e, ErrorKind.UNKNOWN, p.id))
                    }
                }
            }.awaitAll()
        }
        val drafts = outcomes.mapNotNull { it.getOrNull() }.filter { it.second.isNotBlank() }
        val failures = outcomes.mapNotNull { it.exceptionOrNull() as? LlmException }.toMutableList()
        if (drafts.isEmpty()) return null // caller falls back to the normal single-provider path
        if (drafts.size == 1) {
            val (p, d) = drafts.single()
            return CollaborationResult(stripConfidence(d), listOf(p.id), null, null, failures)
        }

        val synthOrder = drafts.map { it.first }.sortedBy { if (it.id == preferred) 0 else 1 + it.id.ordinal }
        val prompt = synthesisPrompt(userText, drafts.map { it.first.id to it.second })
        for (s in synthOrder) {
            try {
                val r = withTimeoutOrNull(synthesisTimeoutMs) {
                    s.complete(LlmRequest(system + SYNTHESIS_RULES, listOf(ChatMessage(Role.USER, prompt)), tools = emptyList(), maxTokens = 2048))
                } ?: continue
                val (answer, agreement) = parseSynthesis(r.text)
                if (answer.isNotBlank()) return CollaborationResult(answer, drafts.map { it.first.id }, s.id, agreement, failures)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += (e as? LlmException ?: LlmException(e.message ?: "error", e, ErrorKind.UNKNOWN, s.id))
            }
        }
        // Synthesis unavailable: use the preferred analyst's single answer rather than pasting drafts together.
        val (p, d) = drafts.minByOrNull { if (it.first.id == preferred) 0 else 1 + it.first.id.ordinal }!!
        return CollaborationResult(stripConfidence(d), listOf(p.id), null, "Synthesis was unavailable, so this is ${p.id.displayName}'s answer.", failures)
    }

    companion object {
        const val ANALYST_RULES = """

COLLABORATION MODE — you are one of several independent analysts answering the same request.
You cannot use tools or perform actions here; if the user asks for an action, describe what you would do instead.
Answer directly in under 250 words. Finish with a final line "CONFIDENCE: high", "CONFIDENCE: medium" or "CONFIDENCE: low"."""

        const val SYNTHESIS_RULES = """

SYNTHESIS STAGE — independent analysts answered the user's request. Produce the single final answer:
keep what they agree on, resolve contradictions using the strongest reasoning (or say plainly that it is uncertain),
drop errors you can identify, and stay concise because the answer may be read aloud. You cannot use tools.
Reply in exactly this format:
ANSWER: <final answer for the user, without mentioning the analysts>
AGREEMENT: <one sentence on where the analysts agreed and where they disagreed>"""

        fun synthesisPrompt(userText: String, drafts: List<Pair<ProviderId, String>>): String = buildString {
            append("User request:\n").append(userText).append("\n")
            drafts.forEachIndexed { i, (p, d) -> append("\nAnalyst ${'A' + i} (${p.displayName}):\n").append(d).append("\n") }
        }

        fun parseSynthesis(text: String): Pair<String, String?> {
            val a = text.indexOf("ANSWER:")
            if (a < 0) return Agent0.stripTag(text.trim()) to null
            val g = text.indexOf("AGREEMENT:", a)
            val answer = (if (g > 0) text.substring(a + 7, g) else text.substring(a + 7)).trim()
            val agreement = if (g > 0) text.substring(g + 10).trim().lineSequence().firstOrNull()?.trim() else null
            return answer to agreement?.takeIf { it.isNotBlank() }
        }

        fun stripConfidence(d: String): String = d.lines().filterNot { it.trim().startsWith("CONFIDENCE:", ignoreCase = true) }.joinToString("\n").trim()

        /** History for analysts: plain user/assistant text only — no tool calls, results or provider state. */
        fun textOnly(history: List<ChatMessage>): List<ChatMessage> =
            history.filter { (it.role == Role.USER || it.role == Role.ASSISTANT) && it.content.isNotBlank() }
                .map { ChatMessage(it.role, it.content) }
                .dropWhile { it.role != Role.USER }
    }

    /** Deterministic eligibility test: analytical questions yes, commands and chit-chat no. */
    object Heuristic {
        private val ACTION = Regex("^(please\\s+)?(open|call|dial|text|send|set|remind|remember|forget|delete|play|turn|start|stop|launch)\\b", RegexOption.IGNORE_CASE)
        private val ANALYTIC = listOf(
            "compare", "comparison", "analy", "pros and cons", "advantages", "disadvantages", "trade-off", "tradeoff",
            "should i", "which is better", "what is better", "recommend", "evaluate", "assess", "explain why", "why does",
            "strategy", "plan for", "decide", "decision", " versus ", " vs ", "review my", "critique",
            "השווה", "השוואה", "יתרונות", "חסרונות", "כדאי", "ניתוח", "נתח", "אסטרטגיה", "מה עדיף", "תוכנית",
            "قارن", "مزايا", "عيوب", "حلل", "تحليل", "خطة", "أفضل",
        )
        fun isComplex(text: String): Boolean {
            val t = text.trim()
            if (t.length < 25 || ACTION.containsMatchIn(t)) return false
            val lower = " " + t.lowercase() + " "
            return ANALYTIC.any { it in lower } || (t.length >= 200 && '?' in t)
        }
    }
}

/** Small shared helper so this file doesn't depend on Agent internals. */
internal object Agent0 {
    private val TAG = Regex("^\\s*\\[([a-zA-Z]+)]\\s*")
    fun stripTag(s: String) = TAG.replace(s, "")
}
