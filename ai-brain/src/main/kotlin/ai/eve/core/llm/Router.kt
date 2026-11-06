package ai.eve.core.llm

import ai.eve.core.providers.ProviderClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

enum class RoutingMode { AUTOMATIC, MANUAL }

/**
 * @param preferred provider tried first (AUTOMATIC) or exclusively (MANUAL).
 * @param fallback  whether EVE may send the request to another *enabled* provider when the
 *                  first one fails. Off means the user's conversation never leaves the chosen provider.
 */
data class RoutingPolicy(
    val mode: RoutingMode = RoutingMode.AUTOMATIC,
    val preferred: ProviderId? = null,
    val fallback: Boolean = true,
)

/**
 * Chooses which enabled provider answers. The router only invokes models; tools are executed
 * by the Agent after the PolicyEngine, so a fallback can never re-run a phone action.
 */
class ProviderRouter(
    val providers: List<ProviderClient>,
    val policy: RoutingPolicy = RoutingPolicy(),
    private val onOutcome: (ProviderId, LlmException?) -> Unit = { _, _ -> },
) : LlmClient {
    override val name: String get() = "router(" + providers.joinToString(",") { it.id.key } + ")"

    fun order(request: LlmRequest? = null): List<ProviderClient> {
        val byDefault = ProviderId.entries.mapNotNull { id -> providers.firstOrNull { it.id == id } }
        val preferred = policy.preferred?.let { p -> byDefault.firstOrNull { it.id == p } }
        if (policy.mode == RoutingMode.MANUAL) return listOfNotNull(preferred ?: byDefault.firstOrNull())
        var ordered = if (preferred != null) listOf(preferred) + (byDefault - preferred) else byDefault
        // Mid tool-loop, keep the provider that issued the tool call: its private state
        // (thinking blocks, reasoning items, thought signatures) is only valid for itself.
        val sticky = request?.messages?.takeIf { it.lastOrNull()?.role == Role.TOOL }
            ?.lastOrNull { it.role == Role.ASSISTANT }?.providerState?.provider
        ordered.firstOrNull { it.id == sticky }?.let { s -> ordered = listOf(s) + (ordered - s) }
        return ordered
    }

    override suspend fun complete(request: LlmRequest): LlmResponse {
        val candidates = order(request)
        if (candidates.isEmpty()) throw AllProvidersFailed(listOf(LlmException("No provider", kind = ErrorKind.NOT_CONFIGURED)))
        val failures = mutableListOf<LlmException>()
        for (p in candidates) {
            try {
                val r = p.complete(request)
                onOutcome(p.id, null)
                return r
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val classified = (e as? LlmException)?.takeIf { it.provider != null }
                    ?: LlmException(e.message ?: "error", e, (e as? LlmException)?.kind ?: ErrorKind.UNKNOWN, p.id)
                failures += classified
                onOutcome(p.id, classified)
                if (!policy.fallback || !classified.kind.failover) break
            }
        }
        throw AllProvidersFailed(failures)
    }
}

// ───────────────────────────── Connection tests ─────────────────────────────

data class ConnectionReport(
    val provider: ProviderId,
    val model: String,
    val ok: Boolean,
    val kind: ErrorKind?,
    val latencyMs: Long,
) {
    /** Safe text for the UI; never includes keys or response bodies. */
    val message: String get() = if (ok) "Connected · $model · ${latencyMs} ms" else "${kind?.summary ?: "failed"} — ${hint(kind)}"

    companion object {
        fun hint(kind: ErrorKind?): String = when (kind) {
            ErrorKind.NOT_CONFIGURED -> "add an API key"
            ErrorKind.INVALID_KEY -> "re-enter the key from the provider's console"
            ErrorKind.PERMISSION -> "check the key's project/model access"
            ErrorKind.BILLING -> "add a payment method or credits"
            ErrorKind.QUOTA -> "wait for the quota to reset or raise it"
            ErrorKind.RATE_LIMIT -> "wait a minute and try again"
            ErrorKind.MODEL_NOT_FOUND -> "choose a different model ID"
            ErrorKind.REGION -> "enable billing or use a supported region"
            ErrorKind.TIMEOUT, ErrorKind.NETWORK -> "check the internet connection"
            ErrorKind.OVERLOADED, ErrorKind.SERVER -> "the provider is having trouble; try later"
            else -> "try again later"
        }
    }
}

object ConnectionTester {
    /** A real (tiny) generation call: listing models alone does not prove billing/inference access. */
    suspend fun test(client: ProviderClient, timeoutMs: Long = 30_000, clock: () -> Long = System::currentTimeMillis): ConnectionReport {
        val start = clock()
        val req = LlmRequest("Connection test. Reply with the single word OK.", listOf(ChatMessage(Role.USER, "ping")), emptyList(), maxTokens = 64)
        return try {
            withTimeoutOrNull(timeoutMs) { client.complete(req) }
                ?.let { ConnectionReport(client.id, client.model, true, null, clock() - start) }
                ?: ConnectionReport(client.id, client.model, false, ErrorKind.TIMEOUT, clock() - start)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ConnectionReport(client.id, client.model, false, (e as? LlmException)?.kind ?: ErrorKind.UNKNOWN, clock() - start)
        }
    }

    suspend fun testAll(clients: List<ProviderClient>): List<ConnectionReport> =
        coroutineScope { clients.map { c -> async { test(c) } }.awaitAll() }
}
