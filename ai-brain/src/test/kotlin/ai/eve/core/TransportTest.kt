package ai.eve.core

import ai.eve.core.llm.ErrorKind
import ai.eve.core.llm.LlmException
import ai.eve.core.providers.UrlConnectionTransport
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Exercises the real HttpURLConnection transport against a local server: success, HTTP errors, timeouts, refusal, STOP. */
class TransportTest {
    private fun server(delayMs: Long, status: Int, body: String): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                ex.requestBody.readBytes()
                Thread.sleep(delayMs)
                val bytes = body.toByteArray()
                runCatching { ex.sendResponseHeaders(status, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) } }
            }
            executor = java.util.concurrent.Executors.newCachedThreadPool()
            start()
        }

    private fun url(s: HttpServer) = "http://127.0.0.1:${s.address.port}/v1/test"

    @Test fun returnsBodyAndErrorStatusWithoutThrowing() = runBlocking {
        val ok = server(0, 200, """{"ok":true}"""); val err = server(0, 429, """{"error":{}}""")
        try {
            val t = UrlConnectionTransport()
            assertEquals(200, t.send("POST", url(ok), mapOf("x-api-key" to "k"), "{}").status)
            val r = t.send("POST", url(err), emptyMap(), "{}")
            assertEquals(429, r.status); assertEquals("""{"error":{}}""", r.body)
        } finally { ok.stop(0); err.stop(0) }
    }

    @Test fun slowServerIsClassifiedAsTimeout() = runBlocking {
        val slow = server(1_500, 200, "{}")
        try {
            val e = assertFailsWith<LlmException> { UrlConnectionTransport(readTimeoutMs = 300).send("POST", url(slow), emptyMap(), "{}") }
            assertEquals(ErrorKind.TIMEOUT, e.kind)
        } finally { slow.stop(0) }
    }

    @Test fun refusedConnectionIsClassifiedAsNetwork() = runBlocking {
        val port = ServerSocket(0).use { it.localPort } // free port, nothing listening
        val e = assertFailsWith<LlmException> { UrlConnectionTransport().send("POST", "http://127.0.0.1:$port/", emptyMap(), "{}") }
        assertEquals(ErrorKind.NETWORK, e.kind)
    }

    @Test fun cancellationAbortsAnInFlightRequestImmediately() = runBlocking {
        val hang = server(10_000, 200, "{}")
        try {
            val job = async(Dispatchers.Default) { UrlConnectionTransport().send("POST", url(hang), emptyMap(), "{}") }
            delay(200)
            val start = System.currentTimeMillis()
            job.cancel()
            assertFailsWith<CancellationException> { job.await() }
            assertTrue(System.currentTimeMillis() - start < 1_000, "STOP must not wait for the read timeout")
        } finally { hang.stop(0) }
    }
}
