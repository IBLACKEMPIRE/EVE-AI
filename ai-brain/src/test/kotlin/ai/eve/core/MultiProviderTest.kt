package ai.eve.core

import ai.eve.core.llm.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MultiProviderTest {
    private val req = LlmRequest("system", listOf(ChatMessage(Role.USER, "hello")), emptyList())
    private fun provider(id: String, block: suspend () -> LlmResponse): LlmClient = object : LlmClient {
        override val name = id
        override suspend fun complete(request: LlmRequest) = block()
    }
    @Test fun fallbackOnTransientFailure() = runBlocking {
        val router = MultiProviderClient(listOf(provider("first") { throw LlmException("timeout") }, provider("second") { LlmResponse("ok") }))
        assertEquals("ok", router.complete(req).text)
    }
    @Test fun nonRetryableErrorsDoNotLeakToNextProvider() = runBlocking {
        var called = false
        val router = MultiProviderClient(listOf(provider("first") { throw LlmException("invalid key", retryable = false) }, provider("second") { called = true; LlmResponse("oops") }))
        assertFailsWith<LlmException> { router.complete(req) }
        assertTrue(!called)
    }
    @Test fun collaborationLabelsProvidersOnlyWithoutTools() = runBlocking {
        val router = MultiProviderClient(listOf(provider("A") { LlmResponse("one") }, provider("B") { LlmResponse("two") }), true)
        assertTrue(router.complete(req).text.contains("[A]\none"))
        assertTrue(router.complete(req).text.contains("[B]\ntwo"))
    }
    @Test fun toolCallingDoesNotFanOut() = runBlocking {
        var second = 0
        val router = MultiProviderClient(listOf(provider("A") { LlmResponse("", listOf(ToolCall("1","get_time", emptyMap()))) }, provider("B") { second++; LlmResponse("no") }), true)
        val answer = router.complete(req.copy(tools = listOf(ToolSpec("get_time","clock",emptyMap()))))
        assertEquals(1, answer.toolCalls.size)
        assertEquals(0, second)
    }
}
