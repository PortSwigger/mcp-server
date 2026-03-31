package net.portswigger.mcp.tools

import burp.api.montoya.scanner.Crawl
import burp.api.montoya.scanner.audit.Audit
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ActiveAuditRegistryTest {

    private val registry = ActiveAuditRegistry()

    @BeforeEach
    fun setup() {
        registry.clear()
    }

    @Test
    fun `register returns incrementing IDs`() {
        val audit1 = mockk<Audit>(relaxed = true)
        val audit2 = mockk<Audit>(relaxed = true)

        val id1 = registry.register(audit = audit1)
        val id2 = registry.register(audit = audit2)

        assertEquals("audit-1", id1)
        assertEquals("audit-2", id2)
    }

    @Test
    fun `register with crawl stores both`() {
        val crawl = mockk<Crawl>(relaxed = true)
        val audit = mockk<Audit>(relaxed = true)

        val id = registry.register(crawl = crawl, audit = audit)
        assertNotNull(id)
    }

    @Test
    fun `stopById runs cleanup callback`() {
        val audit = mockk<Audit>(relaxed = true)
        var cleanedUp = false

        val id = registry.register(
            audit = audit,
            cleanup = { cleanedUp = true }
        )

        registry.stopById(id)

        assertTrue(cleanedUp)
    }

    @Test
    fun `stopAll deletes all tasks and interrupts threads`() {
        val crawl = mockk<Crawl>(relaxed = true)
        val audit1 = mockk<Audit>(relaxed = true)
        val audit2 = mockk<Audit>(relaxed = true)
        val thread = mockk<Thread>(relaxed = true)

        registry.register(crawl = crawl, audit = audit1, pollingThread = thread)
        registry.register(audit = audit2)

        val result = registry.stopAll()

        assertEquals(2, result.total)
        assertEquals(0, result.failed)
        verify { crawl.delete() }
        verify { audit1.delete() }
        verify { audit2.delete() }
        verify { thread.interrupt() }
    }

    @Test
    fun `stopAll on empty registry returns zero`() {
        val result = registry.stopAll()
        assertEquals(0, result.total)
    }

    @Test
    fun `stopById deletes specific task and returns message`() {
        val audit1 = mockk<Audit>(relaxed = true)
        val audit2 = mockk<Audit>(relaxed = true)

        val id1 = registry.register(audit = audit1)
        registry.register(audit = audit2)

        val result = registry.stopById(id1)

        assertNotNull(result)
        assertTrue(result!!.contains("Stopped"))
        verify { audit1.delete() }
        verify(exactly = 0) { audit2.delete() }
    }

    @Test
    fun `stopById with unknown ID returns null`() {
        val audit = mockk<Audit>(relaxed = true)
        registry.register(audit = audit)

        val result = registry.stopById("audit-999")

        assertNull(result)
        verify(exactly = 0) { audit.delete() }
    }

    @Test
    fun `stopById with polling thread interrupts it`() {
        val audit = mockk<Audit>(relaxed = true)
        val thread = mockk<Thread>(relaxed = true)

        val id = registry.register(audit = audit, pollingThread = thread)
        registry.stopById(id)

        verify { thread.interrupt() }
    }

    @Test
    fun `stopAll clears registry`() {
        val audit = mockk<Audit>(relaxed = true)
        registry.register(audit = audit)

        registry.stopAll()
        val result = registry.stopAll()

        assertEquals(0, result.total)
    }

    @Test
    fun `stopById removes entry from registry`() {
        val audit = mockk<Audit>(relaxed = true)
        val id = registry.register(audit = audit)

        assertNotNull(registry.stopById(id))
        assertNull(registry.stopById(id))
    }

    @Test
    fun `stopAll continues when delete throws and reports failures`() {
        val audit1 = mockk<Audit>(relaxed = true)
        val audit2 = mockk<Audit>(relaxed = true)

        every { audit1.delete() } throws RuntimeException("delete failed")

        registry.register(audit = audit1)
        registry.register(audit = audit2)

        val result = registry.stopAll()

        assertEquals(2, result.total)
        assertEquals(1, result.failed)
        assertTrue(result.errors.any { it.contains("delete failed") })
        verify { audit2.delete() }
    }

    @Test
    fun `stopById returns failure message when delete throws`() {
        val audit = mockk<Audit>(relaxed = true)
        every { audit.delete() } throws RuntimeException("delete failed")

        val id = registry.register(audit = audit)
        val result = registry.stopById(id)

        assertNotNull(result)
        assertTrue(result!!.contains("failed"))
    }

    @Test
    fun `stopAll reports cleanup failures`() {
        val audit = mockk<Audit>(relaxed = true)

        registry.register(
            audit = audit,
            cleanup = { throw RuntimeException("cleanup failed") }
        )

        val result = registry.stopAll()

        assertEquals(1, result.total)
        assertEquals(1, result.failed)
        assertTrue(result.errors.any { it.contains("cleanup failed") })
    }
}
