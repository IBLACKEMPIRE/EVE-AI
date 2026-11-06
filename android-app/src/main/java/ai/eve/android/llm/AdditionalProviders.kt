package ai.eve.android.llm

import ai.eve.core.llm.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** OpenAI Responses API. No API credentials are written to logs or repository. */
class OpenAiClient(private val key: String, private val model: String) : LlmClient {
    override val name = "openai:$model"
    override suspend fun complete(request: LlmRequest): LlmResponse = withContext(Dispatchers.IO) {
        val body = JSONObject().put("model", model).put("instructions", request.system)
            .put("max_output_tokens", request.maxTokens)
        val input = JSONArray()
        request.messages.forEach { m ->
            when (m.role) {
                Role.SYSTEM -> Unit
                Role.USER -> input.put(JSONObject().put("role", "user").put("content", m.content))
                Role.ASSISTANT -> {
                    if (m.content.isNotBlank()) input.put(JSONObject().put("role", "assistant").put("content", m.content))
                    m.toolCalls.forEach { c -> input.put(JSONObject().put("type", "function_call").put("call_id", c.id)
                        .put("name", c.name).put("arguments", JSONObject(c.arguments).toString())) }
                }
                Role.TOOL -> input.put(JSONObject().put("type", "function_call_output").put("call_id", m.toolCallId).put("output", m.content))
            }
        }
        body.put("input", input)
        if (request.tools.isNotEmpty()) body.put("tools", JSONArray().apply {
            request.tools.forEach { t ->
                put(JSONObject().put("type", "function").put("name", t.name).put("description", t.description)
                    .put("strict", false).put("parameters", JSONObject().put("type", "object")
                        .put("properties", JSONObject().apply { t.parameters.forEach { (k, v) -> put(k, JSONObject().put("type", "string").put("description", v)) } })
                        .put("required", JSONArray(t.required))))
            }
        })
        val response = ProviderHttp.post("https://api.openai.com/v1/responses", body, mapOf("Authorization" to "Bearer $key"))
        parse(response)
    }

    companion object {
        fun parse(json: String): LlmResponse {
            val root = JSONObject(json)
            val items = root.optJSONArray("output") ?: JSONArray()
            val text = StringBuilder()
            val calls = mutableListOf<ToolCall>()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                when (item.optString("type")) {
                    "function_call" -> {
                        val obj = JSONObject(item.optString("arguments", "{}"))
                        calls += ToolCall(item.getString("call_id"), item.getString("name"), obj.keys().asSequence().associateWith { obj.get(it).toString() })
                    }
                    "message" -> {
                        val parts = item.optJSONArray("content") ?: JSONArray()
                        for (j in 0 until parts.length()) {
                            val p = parts.getJSONObject(j)
                            if (p.optString("type") == "output_text") text.append(p.optString("text"))
                        }
                    }
                }
            }
            return LlmResponse(text.toString(), calls)
        }
    }
}

/** Gemini generateContent API. The same neutral tool contract is used by all providers. */
class GeminiClient(private val key: String, private val model: String) : LlmClient {
    override val name = "gemini:$model"
    override suspend fun complete(request: LlmRequest): LlmResponse = withContext(Dispatchers.IO) {
        val contents = JSONArray()
        request.messages.forEach { m ->
            when (m.role) {
                Role.SYSTEM -> Unit
                Role.USER -> contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", m.content))))
                Role.ASSISTANT -> {
                    val parts = JSONArray()
                    if (m.content.isNotBlank()) parts.put(JSONObject().put("text", m.content))
                    m.toolCalls.forEach { c -> parts.put(JSONObject().put("functionCall", JSONObject().put("name", c.name).put("args", JSONObject(c.arguments)))) }
                    if (parts.length() > 0) contents.put(JSONObject().put("role", "model").put("parts", parts))
                }
                Role.TOOL -> contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject()
                    .put("functionResponse", JSONObject().put("name", findToolName(request.messages, m.toolCallId))
                        .put("response", JSONObject().put("result", m.content))))))
            }
        }
        val body = JSONObject().put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", request.system))))
            .put("contents", contents).put("generationConfig", JSONObject().put("maxOutputTokens", request.maxTokens))
        if (request.tools.isNotEmpty()) body.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().apply {
            request.tools.forEach { t -> put(JSONObject().put("name", t.name).put("description", t.description)
                .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject().apply {
                    t.parameters.forEach { (k, v) -> put(k, JSONObject().put("type", "STRING").put("description", v)) }
                }).put("required", JSONArray(t.required)))) }
        })))
        val response = ProviderHttp.post("https://generativelanguage.googleapis.com/v1beta/models/${java.net.URLEncoder.encode(model, "UTF-8")}:generateContent", body, mapOf("x-goog-api-key" to key))
        parse(response)
    }
    companion object {
        private fun findToolName(messages: List<ChatMessage>, id: String?): String =
            messages.asReversed().flatMap { it.toolCalls }.firstOrNull { it.id == id }?.name ?: "unknown"
        fun parse(json: String): LlmResponse {
            val candidates = JSONObject(json).optJSONArray("candidates") ?: JSONArray()
            val parts = candidates.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
            val text = StringBuilder()
            val calls = mutableListOf<ToolCall>()
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.has("text")) text.append(part.optString("text"))
                val fn = part.optJSONObject("functionCall") ?: continue
                val args = fn.optJSONObject("args") ?: JSONObject()
                // Synthetic stable ID scoped to this response; Gemini's API matches results by name.
                calls += ToolCall("gemini_call_$i", fn.getString("name"), args.keys().asSequence().associateWith { args.get(it).toString() })
            }
            return LlmResponse(text.toString(), calls)
        }
    }
}

internal object ProviderHttp {
    fun post(endpoint: String, body: JSONObject, headers: Map<String, String>): String {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 15_000
        connection.readTimeout = 90_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw LlmException("Provider HTTP $code", retryable = code == 408 || code == 429 || code >= 500)
            return text
        } catch (e: IOException) {
            throw LlmException("Provider network failure", e, retryable = true)
        } finally {
            connection.disconnect()
        }
    }
}
