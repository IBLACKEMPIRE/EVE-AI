package ai.eve.android.llm

import ai.eve.core.llm.ChatMessage
import ai.eve.core.llm.LlmClient
import ai.eve.core.llm.LlmException
import ai.eve.core.llm.LlmRequest
import ai.eve.core.llm.LlmResponse
import ai.eve.core.llm.Role
import ai.eve.core.llm.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Claude Messages API client with native tool use. Uses only platform HTTP + org.json. */
class AnthropicClient(
    private val apiKey: String,
    private val model: String,
    private val endpoint: String = "https://api.anthropic.com/v1/messages",
) : LlmClient {
    override val name = "anthropic:$model"

    override suspend fun complete(request: LlmRequest): LlmResponse = withContext(Dispatchers.IO) {
        val body = buildBody(model, request).toString()
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("content-type", "application/json")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", "2023-06-01")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw LlmException("HTTP $code: ${errorMessage(text)}", retryable = code == 408 || code == 429 || code >= 500)
            }
            parseResponse(text)
        } catch (e: IOException) {
            throw LlmException("Network error: ${e.message}", e, retryable = true)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        fun buildBody(model: String, req: LlmRequest): JSONObject = JSONObject().apply {
            put("model", model)
            put("max_tokens", req.maxTokens)
            put("system", req.system)
            if (req.tools.isNotEmpty()) put("tools", JSONArray().apply {
                req.tools.forEach { t ->
                    put(JSONObject().apply {
                        put("name", t.name)
                        put("description", t.description)
                        put("input_schema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                t.parameters.forEach { (p, d) -> put(p, JSONObject().put("type", "string").put("description", d)) }
                            })
                            put("required", JSONArray(t.required))
                        })
                    })
                }
            })
            put("messages", toApiMessages(req.messages))
        }

        /** Maps neutral messages to the API shape; tool results become user turns and same-role turns merge. */
        fun toApiMessages(messages: List<ChatMessage>): JSONArray {
            val out = mutableListOf<Pair<String, JSONArray>>()
            fun add(role: String, block: JSONObject) {
                if (out.isNotEmpty() && out.last().first == role) out.last().second.put(block)
                else out += role to JSONArray().put(block)
            }
            for (m in messages) when (m.role) {
                Role.SYSTEM -> Unit // system prompt goes in the top-level field
                Role.USER -> add("user", JSONObject().put("type", "text").put("text", m.content))
                Role.ASSISTANT -> {
                    if (m.content.isNotBlank()) add("assistant", JSONObject().put("type", "text").put("text", m.content))
                    m.toolCalls.forEach { c ->
                        add("assistant", JSONObject().put("type", "tool_use").put("id", c.id).put("name", c.name)
                            .put("input", JSONObject().apply { c.arguments.forEach { (k, v) -> put(k, v) } }))
                    }
                }
                Role.TOOL -> add("user", JSONObject().put("type", "tool_result").put("tool_use_id", m.toolCallId)
                    .put("content", m.content).put("is_error", m.content.startsWith("ERROR:")))
            }
            return JSONArray().apply { out.forEach { (role, content) -> put(JSONObject().put("role", role).put("content", content)) } }
        }

        fun parseResponse(json: String): LlmResponse {
            val content = JSONObject(json).optJSONArray("content") ?: JSONArray()
            val text = StringBuilder()
            val calls = mutableListOf<ToolCall>()
            for (i in 0 until content.length()) {
                val b = content.getJSONObject(i)
                when (b.optString("type")) {
                    "text" -> text.append(b.optString("text"))
                    "tool_use" -> {
                        val input = b.optJSONObject("input") ?: JSONObject()
                        val args = input.keys().asSequence().associateWith { k -> input.get(k).toString() }
                        calls += ToolCall(b.getString("id"), b.getString("name"), args)
                    }
                }
            }
            return LlmResponse(text.toString(), calls)
        }

        private fun errorMessage(body: String): String =
            runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrDefault(body.take(200))
    }
}
