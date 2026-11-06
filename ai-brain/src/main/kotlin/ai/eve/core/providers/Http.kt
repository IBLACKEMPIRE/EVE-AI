package ai.eve.core.providers

import ai.eve.core.json.Json
import ai.eve.core.llm.ErrorKind
import ai.eve.core.llm.LlmException
import ai.eve.core.llm.ProviderId
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class HttpResponse(val status: Int, val body: String)

/** Abstraction over HTTP so providers can be tested with deterministic fake responses. */
fun interface HttpTransport {
    /** Returns any HTTP status; throws [LlmException] (NETWORK/TIMEOUT) only when no response arrived. */
    suspend fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse
}

/**
 * JDK HttpURLConnection transport. The blocking call runs on a dedicated pool and the
 * connection is force-closed when the coroutine is cancelled, so the emergency STOP aborts
 * an in-flight request immediately instead of waiting for the read timeout.
 */
class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) : HttpTransport {
    override suspend fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse =
        suspendCancellableCoroutine { cont ->
            val conn = (URL(url).openConnection() as HttpURLConnection)
            cont.invokeOnCancellation { runCatching { conn.disconnect() } }
            POOL.execute {
                try {
                    conn.requestMethod = method
                    conn.connectTimeout = connectTimeoutMs
                    conn.readTimeout = readTimeoutMs
                    conn.useCaches = false
                    headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                    if (body != null) {
                        conn.doOutput = true
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    }
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    cont.resume(HttpResponse(code, text))
                } catch (e: Exception) {
                    cont.resumeWithException(networkError(e))
                } finally {
                    runCatching { conn.disconnect() }
                }
            }
        }

    companion object {
        private val POOL = Executors.newCachedThreadPool(ThreadFactory { r -> Thread(r, "eve-http").apply { isDaemon = true } })

        fun networkError(e: Exception): LlmException = when (e) {
            is SocketTimeoutException -> LlmException("Request timed out", e, ErrorKind.TIMEOUT)
            is UnknownHostException, is ConnectException, is NoRouteToHostException ->
                LlmException("Network unavailable", e, ErrorKind.NETWORK)
            is InterruptedIOException -> LlmException("Request interrupted", e, ErrorKind.TIMEOUT)
            is IOException -> LlmException("Network failure", e, ErrorKind.NETWORK)
            else -> LlmException("HTTP client failure", e, ErrorKind.UNKNOWN)
        }
    }
}

/**
 * Maps provider error responses to [ErrorKind] using the documented error fields
 * (Anthropic `error.type`, OpenAI `error.code`, Gemini `error.status` / `ErrorInfo.reason`).
 * The response body is inspected here and then discarded; it is never put in messages or logs.
 */
object ErrorClassifier {
    fun classify(provider: ProviderId, status: Int, body: String): ErrorKind {
        val err = runCatching { Json.parseObj(body).obj("error") }.getOrNull()
        val message = err?.str("message").orEmpty().lowercase()
        return when (provider) {
            ProviderId.ANTHROPIC -> anthropic(status, err?.str("type"), message)
            ProviderId.OPENAI -> openAi(status, err?.str("code") ?: err?.str("type"), message)
            ProviderId.GEMINI -> gemini(status, err, message)
        }
    }

    private fun anthropic(status: Int, type: String?, message: String): ErrorKind = when {
        type == "authentication_error" || status == 401 -> ErrorKind.INVALID_KEY
        type == "billing_error" || status == 402 || "credit balance" in message -> ErrorKind.BILLING
        type == "permission_error" || status == 403 -> ErrorKind.PERMISSION
        type == "not_found_error" || status == 404 -> ErrorKind.MODEL_NOT_FOUND
        type == "rate_limit_error" || status == 429 -> ErrorKind.RATE_LIMIT
        type == "overloaded_error" || status == 529 -> ErrorKind.OVERLOADED
        type == "timeout_error" || status == 504 || status == 408 -> ErrorKind.TIMEOUT
        type == "api_error" || status >= 500 -> ErrorKind.SERVER
        type == "invalid_request_error" && "model" in message -> ErrorKind.MODEL_NOT_FOUND
        else -> generic(status)
    }

    private fun openAi(status: Int, code: String?, message: String): ErrorKind = when {
        code == "insufficient_quota" -> ErrorKind.QUOTA
        code == "model_not_found" || (status == 404 && "model" in message) -> ErrorKind.MODEL_NOT_FOUND
        code == "invalid_api_key" || status == 401 -> ErrorKind.INVALID_KEY
        code == "unsupported_country_region_territory" -> ErrorKind.REGION
        status == 403 -> ErrorKind.PERMISSION
        status == 429 -> ErrorKind.RATE_LIMIT
        status == 503 -> ErrorKind.OVERLOADED
        else -> generic(status)
    }

    private fun gemini(status: Int, err: Json.Obj?, message: String): ErrorKind {
        val grpc = err?.str("status")
        val details = err?.arr("details")?.objects().orEmpty()
        val reasons = details.mapNotNull { it.str("reason") }
        val quotaIds = details.flatMap { d -> d.arr("violations")?.objects().orEmpty().mapNotNull { it.str("quotaId") } }
        return when {
            "API_KEY_INVALID" in reasons || "API_KEY_EXPIRED" in reasons || "api key not valid" in message || status == 401 -> ErrorKind.INVALID_KEY
            status == 402 -> ErrorKind.BILLING
            grpc == "FAILED_PRECONDITION" -> if ("location" in message || "country" in message || "region" in message) ErrorKind.REGION else ErrorKind.BILLING
            grpc == "PERMISSION_DENIED" || status == 403 -> ErrorKind.PERMISSION
            grpc == "NOT_FOUND" || status == 404 -> ErrorKind.MODEL_NOT_FOUND
            grpc == "RESOURCE_EXHAUSTED" || status == 429 ->
                if (quotaIds.any { "PerDay" in it } || "per day" in message || "prepay" in message) ErrorKind.QUOTA else ErrorKind.RATE_LIMIT
            grpc == "UNAVAILABLE" || status == 503 -> ErrorKind.OVERLOADED
            grpc == "DEADLINE_EXCEEDED" || status == 504 -> ErrorKind.TIMEOUT
            else -> generic(status)
        }
    }

    private fun generic(status: Int): ErrorKind = when (status) {
        400, 413, 422 -> ErrorKind.BAD_REQUEST
        401 -> ErrorKind.INVALID_KEY
        402 -> ErrorKind.BILLING
        403 -> ErrorKind.PERMISSION
        404 -> ErrorKind.MODEL_NOT_FOUND
        408, 504 -> ErrorKind.TIMEOUT
        429 -> ErrorKind.RATE_LIMIT
        503, 529 -> ErrorKind.OVERLOADED
        in 500..599 -> ErrorKind.SERVER
        else -> ErrorKind.UNKNOWN
    }
}

/** Shared request helper: classifies non-2xx responses and tags failures with the provider. */
internal suspend fun HttpTransport.postJson(provider: ProviderId, url: String, headers: Map<String, String>, body: Json): Json.Obj {
    val res = try {
        send("POST", url, headers, Json.stringify(body))
    } catch (e: LlmException) {
        throw LlmException(e.message ?: "network", e.cause, e.kind, provider)
    }
    if (res.status !in 200..299) {
        val kind = ErrorClassifier.classify(provider, res.status, res.body)
        throw LlmException("${provider.displayName} HTTP ${res.status}", null, kind, provider, res.status)
    }
    return try { Json.parseObj(res.body) } catch (e: Exception) {
        throw LlmException("${provider.displayName} returned unreadable JSON", e, ErrorKind.BAD_RESPONSE, provider, res.status)
    }
}
