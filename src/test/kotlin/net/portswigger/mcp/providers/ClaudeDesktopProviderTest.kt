package net.portswigger.mcp.providers

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories

class ClaudeDesktopProviderTest {

    private val provider = ClaudeDesktopProvider(mockk(relaxed = true), mockk(relaxed = true))

    @Test
    fun `windowsCandidatePaths includes traditional path first`(@TempDir home: Path) {
        val paths = provider.windowsCandidatePaths(home.toString())

        assertEquals(home.resolve("AppData").resolve("Roaming").resolve("Claude"), paths.first())
    }

    @Test
    fun `windowsCandidatePaths includes Store path when Claude_ package directory exists`(@TempDir home: Path) {
        val storeDir = home.resolve("AppData/Local/Packages/Claude_abc123/LocalCache/Roaming/Claude")
        storeDir.createDirectories()

        val paths = provider.windowsCandidatePaths(home.toString())

        assertTrue(paths.contains(storeDir), "Expected Store path in candidates: $paths")
    }

    @Test
    fun `windowsCandidatePaths does not include Store path when no Claude_ packages exist`(@TempDir home: Path) {
        home.resolve("AppData/Local/Packages/OtherApp_xyz").createDirectories()

        val paths = provider.windowsCandidatePaths(home.toString())

        assertEquals(1, paths.size)
        assertEquals(home.resolve("AppData/Roaming/Claude"), paths.first())
    }

    @Test
    fun `windowsCandidatePaths returns only traditional path when Packages dir does not exist`(@TempDir home: Path) {
        val paths = provider.windowsCandidatePaths(home.toString())

        assertEquals(listOf(home.resolve("AppData/Roaming/Claude")), paths)
    }

    @Test
    fun `windowsCandidatePaths includes multiple Store packages if present`(@TempDir home: Path) {
        home.resolve("AppData/Local/Packages/Claude_aaa/LocalCache/Roaming/Claude").createDirectories()
        home.resolve("AppData/Local/Packages/Claude_bbb/LocalCache/Roaming/Claude").createDirectories()

        val paths = provider.windowsCandidatePaths(home.toString())

        assertEquals(3, paths.size)
        assertTrue(paths.any { it.endsWith(Path.of("Claude_aaa/LocalCache/Roaming/Claude")) })
        assertTrue(paths.any { it.endsWith(Path.of("Claude_bbb/LocalCache/Roaming/Claude")) })
    }
}
