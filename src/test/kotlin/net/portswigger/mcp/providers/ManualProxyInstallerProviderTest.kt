package net.portswigger.mcp.providers

import burp.api.montoya.logging.Logging
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ManualProxyInstallerProviderTest {
    private val logging = mockk<Logging>(relaxed = true)
    private val provider = ManualProxyInstallerProvider(logging, mockk())

    @Test
    fun `saves a new file without prompting`(@TempDir directory: Path) {
        val source = directory.resolve("source.jar").apply { writeText("new proxy") }
        val destination = directory.resolve("mcp-proxy.jar")

        val result = provider.saveProxyJar(source, destination) {
            error("A new file must not require confirmation")
        }

        assertEquals("new proxy", destination.readText())
        assertEquals("Extracted proxy jar to $destination", result)
        verify(exactly = 1) { logging.logToOutput(any<String>()) }
    }

    @Test
    fun `declining replacement preserves the existing file and reports no success`(@TempDir directory: Path) {
        val source = directory.resolve("source.jar").apply { writeText("new proxy") }
        val destination = directory.resolve("mcp-proxy.jar").apply { writeText("existing proxy") }
        var confirmations = 0

        val result = provider.saveProxyJar(source, destination) { path ->
            assertEquals(destination, path)
            assertEquals("existing proxy", path.readText())
            confirmations++
            false
        }

        assertNull(result)
        assertEquals(1, confirmations)
        assertEquals("existing proxy", destination.readText())
        verify(exactly = 0) { logging.logToOutput(any<String>()) }
    }

    @Test
    fun `replaces the existing file only after confirmation`(@TempDir directory: Path) {
        val source = directory.resolve("source.jar").apply { writeText("new proxy") }
        val destination = directory.resolve("mcp-proxy.jar").apply { writeText("existing proxy") }
        var confirmations = 0

        val result = provider.saveProxyJar(source, destination) { path ->
            assertEquals(destination, path)
            assertEquals("existing proxy", path.readText())
            confirmations++
            true
        }

        assertEquals(1, confirmations)
        assertEquals("new proxy", destination.readText())
        assertEquals("Extracted proxy jar to $destination", result)
        verify(exactly = 1) { logging.logToOutput(any<String>()) }
    }

    @Test
    fun `copy failure is propagated and never reported as success`(@TempDir directory: Path) {
        val source = directory.resolve("missing.jar")
        val destination = directory.resolve("mcp-proxy.jar")

        assertThrows(NoSuchFileException::class.java) {
            provider.saveProxyJar(source, destination) {
                error("A missing source must not trigger a replacement prompt")
            }
        }

        assertFalse(destination.exists())
        verify(exactly = 0) { logging.logToOutput(any<String>()) }
        verify(exactly = 1) { logging.logToError(any<String>()) }
    }
}
