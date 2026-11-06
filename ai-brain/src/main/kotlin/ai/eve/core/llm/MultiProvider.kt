package ai.eve.core.llm

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Never replays a request that may have performed a tool call. This router only invokes models. */
class MultiProviderClient(
    private val providers: List<LlmClient>,
    private val collaboration: Boolean = false,
) : LlmClient {
    init { require(providers.isNotEmpty()) }
    override val name: String get() = "auto:" + providers.joinToString(",") { it.name }

    override suspend fun complete(request: LlmRequest): LlmResponse {
        if (collaboration && providers.size > 1 && request.tools.isEmpty()) {
            val answers = coroutineScope {
                providers.map { p -> async {
                    try { p.name to p.complete(request) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { null }
                } }.awaitAll().filterNotNull()
            }
            if (answers.isEmpty()) throw LlmException("All providers failed", retryable = false)
            if (answers.size == 1) return answers.first().second
            // Explicitly label separate model opinions. Do not pretend a local model synthesized them.
            return LlmResponse(answers.joinToString("\n\n") { (provider, answer) -> "[$provider]\n${answer.text}" })
        }
        val failures = mutableListOf<String>()
        for (p in providers) {
            try { return p.complete(request) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                // The user explicitly configured automatic provider selection. Continue on
                // provider-specific 4xx failures (e.g. unsupported model / expired key).
                // Never expose raw exception messages, which can include private data.
                val category = when {
                    e.message?.contains("401") == true || e.message?.contains("403") == true -> "authentication"
                    e.message?.contains("429") == true -> "rate limit or billing"
                    e.message?.contains("400") == true || e.message?.contains("404") == true -> "model or request"
                    else -> "network or service"
                }
                failures += "${p.name.substringBefore(':')}: $category"
            }
        }
        throw LlmException("All configured providers failed — " + failures.joinToString("; "), retryable = false)
    }
}
