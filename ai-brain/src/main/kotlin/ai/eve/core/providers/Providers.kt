package ai.eve.core.providers

import ai.eve.core.json.Json
import ai.eve.core.llm.ChatMessage
import ai.eve.core.llm.ErrorKind
import ai.eve.core.llm.LlmClient
import ai.eve.core.llm.LlmException
import ai.eve.core.llm.LlmRequest
import ai.eve.core.llm.LlmResponse
import ai.eve.core.llm.ProviderId
import ai.eve.core.llm.ProviderState
import ai.eve.core.llm.Role
import ai.eve.core.llm.ToolCall
import java.net.URLEncoder
import java.util.UUID

/** A real provider: knows its identity and model, and can be health-checked. */
interface ProviderClient : LlmClient {
    val id: ProviderId
    val model: String
}

/**
 * Model IDs checked against the providers' official model pages on 2026-11-06.
 * Defaults favour low latency/cost for a voice assistant; users can pick any ID.
 * Availability for a given key still has to be confirmed with "Test connection".
 */
object ModelCatalog {
    val defaults = mapOf(
        ProviderId.ANTHROPIC to "claude-sonnet-5-5",
        ProviderId.OPENAI to "gpt-6-luna",
        ProviderId.GEMINI to "gemini-3.8-flash",
    )
    val suggestions = mapOf(
        ProviderId.ANTHROPIC to listOf("claude-sonnet-5-5", "claude-haiku-5-5", "claude-opus-5-5"),
        ProviderId.OPENAI to listOf("gpt-6-luna", "gpt-6.1-terra", "gpt-6.1-sol"),
        ProviderId.GEMINI to listOf("gemini-3.8-flash", "gemini-3.1-flash-lite", "gemini-2.5-flash"),
    )
    private val VALID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{1,99}$")

    /** Rejects display labels such as "auto:anthropic:…,openai:…" that older builds could save as a model ID. */
    fun isPlausibleModelId(value: String?): Boolean = value != null && VALID.matches(value.trim())

    fun sanitize(provider: ProviderId, stored: String?): String =
        stored?.trim()?.takeIf(::isPlausibleModelId) ?: defaults.getValue(provider)
}

/** Shared handling of turns that came from a different provider (or from the offline demo). */
private object Foreign {
    /** Tool-call IDs that belong to assistant turns this provider did not produce. */
    fun foreignCallIds(messages: List<ChatMessage>, self: ProviderId): Set<String> =
        messages.filter { it.role == Role.ASSISTANT && it.providerState?.provider != self }
            .flatMap { m -> m.toolCalls.map { it.id } }.toSet()

    fun describeCalls(m: ChatMessage): String {
        val calls = m.toolCalls.joinToString("; ") { c -> c.name + c.arguments.entries.joinToString(", ", "(", ")") { "${it.key}=${it.value}" } }
        return listOf(m.content.trim(), if (calls.isEmpty()) "" else "[EVE already executed: $calls]")
            .filter { it.isNotEmpty() }.joinToString("\n")
    }

    fun describeResult(m: ChatMessage, name: String?): String = "[Result of ${name ?: "tool"}: ${m.content}]"

    fun toolName(messages: List<ChatMessage>, id: String?): String? =
        messages.flatMap { it.toolCalls }.firstOrNull { it.id == id }?.name
}

private fun stringParamsSchema(params: Map<String, String>, required: List<String>, upper: Boolean): Json.Obj {
    val str = if (upper) "STRING" else "string"
    val props = Json.Obj().also { p -> params.forEach { (k, d) -> p.put(k, Json.obj("type" to str, "description" to d)) } }
    return Json.obj("type" to if (upper) "OBJECT" else "object", "properties" to props, "required" to required.filter { it in params })
}

// ───────────────────────────── Anthropic ─────────────────────────────

/** Claude Messages API. https://platform.claude.com/docs/en/api/messages */
class AnthropicProvider(
    private val apiKey: String,
    override val model: String,
    private val http: HttpTransport = UrlConnectionTransport(),
    private val baseUrl: String = "https://api.anthropic.com",
) : ProviderClient {
    override val id = ProviderId.ANTHROPIC
    override val name get() = "anthropic:$model"

    override suspend fun complete(request: LlmRequest): LlmResponse {
        val body = buildBody(model, request)
        val res = http.postJson(id, "$baseUrl/v1/messages", mapOf("x-api-key" to apiKey, "anthropic-version" to API_VERSION), body)
        return parse(res, model)
    }

    companion object {
        const val API_VERSION = "2023-06-01"

        fun buildBody(model: String, req: LlmRequest): Json.Obj {
            val body = Json.obj("model" to model, "max_tokens" to req.maxTokens, "system" to req.system)
            if (req.tools.isNotEmpty()) body.put("tools", Json.Arr(req.tools.map { t ->
                Json.obj("name" to t.name, "description" to t.description, "input_schema" to stringParamsSchema(t.parameters, t.required, upper = false))
            }.toMutableList()))
            body.put("messages", messages(req.messages))
            return body
        }

        fun messages(messages: List<ChatMessage>): Json.Arr {
            val foreign = Foreign.foreignCallIds(messages, ProviderId.ANTHROPIC)
            val out = mutableListOf<Pair<String, Json.Arr>>()
            fun add(role: String, blocks: List<Json>) {
                if (blocks.isEmpty()) return
                if (out.isNotEmpty() && out.last().first == role) blocks.forEach { out.last().second.add(it) }
                else out += role to Json.Arr(blocks.toMutableList())
            }
            fun text(t: String) = Json.obj("type" to "text", "text" to t)
            for (m in messages) when (m.role) {
                Role.SYSTEM -> Unit
                Role.USER -> add("user", listOf(text(m.content)))
                Role.ASSISTANT -> {
                    val own = m.providerState?.takeIf { it.provider == ProviderId.ANTHROPIC }
                    if (own != null) add("assistant", (Json.parse(own.raw) as Json.Arr).items)
                    else Foreign.describeCalls(m).takeIf { it.isNotBlank() }?.let { add("assistant", listOf(text(it))) }
                }
                Role.TOOL ->
                    if (m.toolCallId in foreign) add("user", listOf(text(Foreign.describeResult(m, Foreign.toolName(messages, m.toolCallId)))))
                    else add("user", listOf(Json.obj("type" to "tool_result", "tool_use_id" to m.toolCallId, "content" to m.content, "is_error" to m.content.startsWith("ERROR:"))))
            }
            return Json.Arr(out.map { (role, content) -> Json.obj("role" to role, "content" to content) }.toMutableList())
        }

        fun parse(res: Json.Obj, model: String): LlmResponse {
            val content = res.arr("content") ?: Json.Arr()
            val text = StringBuilder()
            val calls = mutableListOf<ToolCall>()
            for (b in content.objects()) when (b.str("type")) {
                "text" -> text.append(b.str("text").orEmpty())
                "tool_use" -> {
                    val input = b.obj("input") ?: Json.Obj()
                    calls += ToolCall(b.str("id") ?: "toolu_${UUID.randomUUID()}", b.str("name").orEmpty(), input.fields.mapValues { it.value.asText() })
                }
                // thinking / redacted_thinking blocks are kept verbatim in providerState below
            }
            val stop = res.str("stop_reason")
            if (stop == "refusal" && text.isBlank() && calls.isEmpty())
                throw LlmException("Claude refused", kind = ErrorKind.CONTENT_BLOCKED, provider = ProviderId.ANTHROPIC)
            return LlmResponse(
                text.toString(), calls, provider = ProviderId.ANTHROPIC, model = model,
                providerState = ProviderState(ProviderId.ANTHROPIC, model, Json.stringify(content)),
                truncated = stop == "max_tokens",
            )
        }
    }
}

// ───────────────────────────── OpenAI ─────────────────────────────

/**
 * OpenAI Responses API, stateless (store=false): every output item, including encrypted
 * reasoning items, is replayed on the next call as the official guides prescribe.
 */
class OpenAiProvider(
    private val apiKey: String,
    override val model: String,
    private val http: HttpTransport = UrlConnectionTransport(),
    private val baseUrl: String = "https://api.openai.com",
) : ProviderClient {
    override val id = ProviderId.OPENAI
    override val name get() = "openai:$model"

    override suspend fun complete(request: LlmRequest): LlmResponse {
        val res = http.postJson(id, "$baseUrl/v1/responses", mapOf("Authorization" to "Bearer $apiKey"), buildBody(model, request))
        return parse(res, model)
    }

    companion object {
        fun buildBody(model: String, req: LlmRequest): Json.Obj {
            val body = Json.obj("model" to model, "instructions" to req.system, "max_output_tokens" to req.maxTokens, "store" to false)
            body.put("input", input(req.messages))
            if (req.tools.isNotEmpty()) body.put("tools", Json.Arr(req.tools.map { t ->
                Json.obj("type" to "function", "name" to t.name, "description" to t.description, "strict" to false,
                    "parameters" to stringParamsSchema(t.parameters, t.required, upper = false))
            }.toMutableList()))
            return body
        }

        fun input(messages: List<ChatMessage>): Json.Arr {
            val foreign = Foreign.foreignCallIds(messages, ProviderId.OPENAI)
            val out = Json.Arr()
            for (m in messages) when (m.role) {
                Role.SYSTEM -> Unit
                Role.USER -> out.add(Json.obj("role" to "user", "content" to m.content))
                Role.ASSISTANT -> {
                    val own = m.providerState?.takeIf { it.provider == ProviderId.OPENAI }
                    if (own != null) (Json.parse(own.raw) as Json.Arr).objects().forEach { item ->
                        out.add(Json.Obj(LinkedHashMap(item.fields).apply { remove("status") }))
                    } else Foreign.describeCalls(m).takeIf { it.isNotBlank() }?.let { out.add(Json.obj("role" to "assistant", "content" to it)) }
                }
                Role.TOOL ->
                    if (m.toolCallId in foreign) out.add(Json.obj("role" to "user", "content" to Foreign.describeResult(m, Foreign.toolName(messages, m.toolCallId))))
                    else out.add(Json.obj("type" to "function_call_output", "call_id" to m.toolCallId, "output" to m.content))
            }
            return out
        }

        fun parse(res: Json.Obj, model: String): LlmResponse {
            val items = res.arr("output") ?: Json.Arr()
            val text = StringBuilder()
            val calls = mutableListOf<ToolCall>()
            var refused = false
            for (item in items.objects()) when (item.str("type")) {
                "message" -> for (p in item.arr("content")?.objects().orEmpty()) when (p.str("type")) {
                    "output_text" -> text.append(p.str("text").orEmpty())
                    "refusal" -> { refused = true; text.append(p.str("refusal").orEmpty()) }
                }
                "function_call" -> {
                    val args = runCatching { Json.parseObj(item.str("arguments") ?: "{}") }.getOrDefault(Json.Obj())
                    calls += ToolCall(item.str("call_id") ?: "call_${UUID.randomUUID()}", item.str("name").orEmpty(), args.fields.mapValues { it.value.asText() })
                }
            }
            if (refused && calls.isEmpty() && text.isBlank())
                throw LlmException("GPT refused", kind = ErrorKind.CONTENT_BLOCKED, provider = ProviderId.OPENAI)
            val incomplete = res.str("status") == "incomplete"
            return LlmResponse(
                text.toString(), calls, provider = ProviderId.OPENAI, model = model,
                providerState = ProviderState(ProviderId.OPENAI, model, Json.stringify(items)),
                truncated = incomplete && res.obj("incomplete_details")?.str("reason") == "max_output_tokens",
            )
        }
    }
}

// ───────────────────────────── Gemini ─────────────────────────────

/**
 * Gemini generateContent API. Model parts are replayed verbatim so thought signatures,
 * which Gemini 3/4 require during function calling, are preserved.
 */
class GeminiProvider(
    private val apiKey: String,
    override val model: String,
    private val http: HttpTransport = UrlConnectionTransport(),
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
) : ProviderClient {
    override val id = ProviderId.GEMINI
    override val name get() = "gemini:$model"

    override suspend fun complete(request: LlmRequest): LlmResponse {
        val url = "$baseUrl/v1beta/models/${URLEncoder.encode(model, "UTF-8")}:generateContent"
        return parse(http.postJson(id, url, mapOf("x-goog-api-key" to apiKey), buildBody(request)), model)
    }

    companion object {
        fun buildBody(req: LlmRequest): Json.Obj {
            val body = Json.obj(
                "systemInstruction" to Json.obj("parts" to Json.arr(Json.obj("text" to req.system))),
                "contents" to contents(req.messages),
                "generationConfig" to Json.obj("maxOutputTokens" to req.maxTokens),
            )
            if (req.tools.isNotEmpty()) body.put("tools", Json.arr(Json.obj("functionDeclarations" to Json.Arr(req.tools.map { t ->
                // Gemini rejects OBJECT schemas with empty properties, so omit parameters for argument-less tools.
                Json.obj("name" to t.name, "description" to t.description).also {
                    if (t.parameters.isNotEmpty()) it.put("parameters", stringParamsSchema(t.parameters, t.required, upper = true))
                }
            }.toMutableList()))))
            return body
        }

        fun contents(messages: List<ChatMessage>): Json.Arr {
            val foreign = Foreign.foreignCallIds(messages, ProviderId.GEMINI)
            val out = mutableListOf<Pair<String, Json.Arr>>()
            fun add(role: String, parts: List<Json>) {
                if (parts.isEmpty()) return
                if (out.isNotEmpty() && out.last().first == role) parts.forEach { out.last().second.add(it) }
                else out += role to Json.Arr(parts.toMutableList())
            }
            fun text(t: String) = Json.obj("text" to t)
            for (m in messages) when (m.role) {
                Role.SYSTEM -> Unit
                Role.USER -> add("user", listOf(text(m.content)))
                Role.ASSISTANT -> {
                    val own = m.providerState?.takeIf { it.provider == ProviderId.GEMINI }
                    if (own != null) add("model", (Json.parse(own.raw) as Json.Arr).items)
                    else Foreign.describeCalls(m).takeIf { it.isNotBlank() }?.let { add("model", listOf(text(it))) }
                }
                Role.TOOL -> {
                    val name = Foreign.toolName(messages, m.toolCallId)
                    if (m.toolCallId in foreign) add("user", listOf(text(Foreign.describeResult(m, name))))
                    else add("user", listOf(Json.obj("functionResponse" to Json.obj("name" to (name ?: "unknown"), "response" to Json.obj("result" to m.content)).also { fr ->
                        m.toolCallId?.takeIf { !it.startsWith(SYNTHETIC) }?.let { fr.put("id", it) }
                    })))
                }
            }
            return Json.Arr(out.map { (role, parts) -> Json.obj("role" to role, "parts" to parts) }.toMutableList())
        }

        private const val SYNTHETIC = "gemini_local_"

        fun parse(res: Json.Obj, model: String): LlmResponse {
            val candidate = res.arr("candidates")?.objects()?.firstOrNull()
            if (candidate == null) {
                val blocked = res.obj("promptFeedback")?.str("blockReason") != null
                throw LlmException("Gemini returned no candidates", kind = if (blocked) ErrorKind.CONTENT_BLOCKED else ErrorKind.BAD_RESPONSE, provider = ProviderId.GEMINI)
            }
            val parts = candidate.obj("content")?.arr("parts") ?: Json.Arr()
            val text = StringBuilder()
            val calls = mutableListOf<ToolCall>()
            for (p in parts.objects()) {
                if (p.bool("thought") != true) p.str("text")?.let(text::append)
                val fn = p.obj("functionCall") ?: continue
                val args = fn.obj("args") ?: Json.Obj()
                calls += ToolCall(fn.str("id") ?: (SYNTHETIC + UUID.randomUUID()), fn.str("name").orEmpty(), args.fields.mapValues { it.value.asText() })
            }
            val finish = candidate.str("finishReason")
            if (finish in setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII") && text.isBlank() && calls.isEmpty())
                throw LlmException("Gemini blocked the response", kind = ErrorKind.CONTENT_BLOCKED, provider = ProviderId.GEMINI)
            return LlmResponse(
                text.toString(), calls, provider = ProviderId.GEMINI, model = model,
                providerState = ProviderState(ProviderId.GEMINI, model, Json.stringify(parts)),
                truncated = finish == "MAX_TOKENS",
            )
        }
    }
}
