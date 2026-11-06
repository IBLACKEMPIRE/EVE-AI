package ai.eve.core.llm

/**
 * Provider-neutral LLM contract. Concrete providers live in ai.eve.core.providers and use
 * only the JDK (HttpURLConnection) plus ai.eve.core.json, so they run on Android, desktop
 * and robot hosts alike.
 */
enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

data class ToolCall(val id: String, val name: String, val arguments: Map<String, String>)

/**
 * Opaque, provider-specific record of an assistant turn exactly as the provider returned it
 * (Claude thinking blocks, OpenAI reasoning items, Gemini thought signatures). Providers
 * must receive these back unchanged during tool use; other providers ignore them.
 */
data class ProviderState(val provider: ProviderId, val model: String, val raw: String)

data class ChatMessage(
    val role: Role,
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    /** Set when role == TOOL: which call this message answers. */
    val toolCallId: String? = null,
    /** Set on ASSISTANT turns produced by a real provider. */
    val providerState: ProviderState? = null,
)

data class ToolSpec(
    val name: String,
    val description: String,
    /** parameter name -> human description. All parameters are strings. */
    val parameters: Map<String, String>,
    val required: List<String> = parameters.keys.toList(),
)

data class LlmRequest(
    val system: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec>,
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
) {
    companion object {
        /** Thinking/reasoning tokens count against this budget on current models; 1024 was too small. */
        const val DEFAULT_MAX_TOKENS = 4096
    }
}

data class LlmResponse(
    val text: String,
    val toolCalls: List<ToolCall> = emptyList(),
    /** Optional emotion tag the model attached, e.g. "happy". Drives the avatar. */
    val emotion: String? = null,
    /** Which provider produced this response (null for the offline demo). */
    val provider: ProviderId? = null,
    val model: String? = null,
    val providerState: ProviderState? = null,
    /** True when the provider stopped because it ran out of output tokens. */
    val truncated: Boolean = false,
)

interface LlmClient {
    val name: String
    suspend fun complete(request: LlmRequest): LlmResponse
}

enum class ProviderId(val key: String, val displayName: String, val vendor: String) {
    ANTHROPIC("anthropic", "Claude", "Anthropic"),
    OPENAI("openai", "GPT", "OpenAI"),
    GEMINI("google", "Gemini", "Google");

    companion object {
        fun fromKey(key: String?): ProviderId? = entries.firstOrNull { it.key == key }
    }
}

/** Classified failure. Messages built from this never contain keys or raw response bodies. */
enum class ErrorKind(val retryable: Boolean, val failover: Boolean, val summary: String) {
    NOT_CONFIGURED(false, true, "no API key configured"),
    INVALID_KEY(false, true, "invalid or expired API key"),
    PERMISSION(false, true, "API key lacks permission for this model or project"),
    BILLING(false, true, "billing problem or no credits"),
    QUOTA(false, true, "API quota exhausted"),
    RATE_LIMIT(true, true, "rate limit reached"),
    MODEL_NOT_FOUND(false, true, "unsupported or unavailable model ID"),
    REGION(false, true, "not available in this region or project tier"),
    BAD_REQUEST(false, true, "request rejected by the provider"),
    CONTENT_BLOCKED(false, false, "response blocked by the provider's safety filter"),
    OVERLOADED(true, true, "provider temporarily overloaded"),
    SERVER(true, true, "provider server error"),
    TIMEOUT(true, true, "request timed out"),
    NETWORK(true, true, "network unavailable"),
    BAD_RESPONSE(true, true, "unreadable response from provider"),
    UNKNOWN(true, true, "unexpected error"),
}

open class LlmException(
    message: String,
    cause: Throwable? = null,
    val kind: ErrorKind = ErrorKind.UNKNOWN,
    val provider: ProviderId? = null,
    /** HTTP status when there was one. Never the body. */
    val httpStatus: Int? = null,
    retryable: Boolean? = null,
) : Exception(message, cause) {
    val retryable: Boolean = retryable ?: kind.retryable

    /** Backwards-compatible constructor used by older call sites and tests. */
    constructor(message: String, cause: Throwable? = null, retryable: Boolean) :
        this(message, cause, if (retryable) ErrorKind.UNKNOWN else ErrorKind.BAD_REQUEST, null, null, retryable)

    /** Safe, user-facing one-liner, e.g. "Claude: invalid or expired API key". */
    fun diagnostic(): String = (provider?.let { "${it.displayName}: " } ?: "") + kind.summary
}

/** Thrown by the router when no provider could answer. Carries one classified failure per provider. */
class AllProvidersFailed(val failures: List<LlmException>) : LlmException(
    "All providers failed",
    kind = failures.firstOrNull { !it.kind.retryable }?.kind ?: failures.firstOrNull()?.kind ?: ErrorKind.NOT_CONFIGURED,
    retryable = failures.isNotEmpty() && failures.all { it.kind.retryable },
) {
    fun diagnostics(): List<String> = failures.map { it.diagnostic() }
}
