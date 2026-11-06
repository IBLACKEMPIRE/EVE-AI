package ai.eve.core.llm

/**
 * Provider-neutral LLM contract. Platform modules (Android, Windows, robot host)
 * supply concrete clients; the core never depends on an HTTP or JSON library.
 */
enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

data class ToolCall(val id: String, val name: String, val arguments: Map<String, String>)

data class ChatMessage(
    val role: Role,
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    /** Set when role == TOOL: which call this message answers. */
    val toolCallId: String? = null,
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
    val maxTokens: Int = 1024,
)

data class LlmResponse(
    val text: String,
    val toolCalls: List<ToolCall> = emptyList(),
    /** Optional emotion tag the model attached, e.g. "happy". Drives the avatar. */
    val emotion: String? = null,
)

interface LlmClient {
    val name: String
    suspend fun complete(request: LlmRequest): LlmResponse
}

class LlmException(message: String, cause: Throwable? = null, val retryable: Boolean = true) :
    Exception(message, cause)
