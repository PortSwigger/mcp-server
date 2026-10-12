package net.portswigger.mcp.tools

import burp.api.montoya.http.message.requests.HttpRequest
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CapturedHttpRequestsTest {
    @Test
    fun `recent requests are bounded and a response can refresh the same id`() {
        val captured = CapturedHttpRequests(capacity = 2)
        val first = mockk<HttpRequest>()
        val second = mockk<HttpRequest>()
        val updatedSecond = mockk<HttpRequest>()
        val third = mockk<HttpRequest>()

        captured.record(1, first, "PROXY")
        captured.record(2, second, "PROXY")
        captured.record(2, updatedSecond, "PROXY")
        captured.record(3, third, "REPEATER")

        assertNull(captured.get(1))
        assertEquals(updatedSecond, captured.get(2)?.request)
        assertEquals(listOf(3, 2), captured.recent(10).map { it.id })
        assertEquals(listOf(3), captured.recent(1).map { it.id })
        captured.clear()
        assertEquals(emptyList<Int>(), captured.recent(10).map { it.id })
    }
}
