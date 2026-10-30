package net.portswigger.mcp.security

import io.mockk.*
import kotlinx.coroutines.*
import net.portswigger.mcp.config.Dialogs
import net.portswigger.mcp.config.McpConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JDialog
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Timeout(10)
class ApprovalCancellationTest {
    @AfterEach
    fun cleanup() = unmockkAll()

    @Test
    fun `cancel before queued dialog opens does not show it`() = runBlocking {
        mockkObject(Dialogs)
        val edtBlocked = CountDownLatch(1)
        val releaseEdt = CountDownLatch(1)
        SwingUtilities.invokeLater {
            edtBlocked.countDown()
            check(releaseEdt.await(5, TimeUnit.SECONDS))
        }
        try {
            assertTrue(edtBlocked.await(5, TimeUnit.SECONDS))
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                Dialogs.requestOption(null, "approval", arrayOf("Allow", "Deny"))
                error("Cancelled approval resumed")
            }
            job.cancelAndJoin()
        } finally {
            releaseEdt.countDown()
            SwingUtilities.invokeAndWait {}
        }
        verify(exactly = 0) { Dialogs.showOptionDialog(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `late always allow after cancellation neither resumes nor persists permission`() = runBlocking {
        mockkObject(Dialogs)
        val config = mockk<McpConfig>(relaxed = true)
        val shown = CompletableDeferred<Unit>()
        val releaseDialog = CountDownLatch(1)
        val dialog = mockk<JDialog>(relaxed = true)
        every { Dialogs.showOptionDialog(any(), any(), any(), any(), any(), any()) } answers {
            val onShow = arg<(JDialog) -> Boolean>(5)
            check(onShow(dialog))
            shown.complete(Unit)
            check(releaseDialog.await(5, TimeUnit.SECONDS))
            1 // A stale Always Allow click.
        }
        var sent = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            if (SwingUserApprovalHandler().requestApproval("example.com", 80, config, null, null)) sent = true
        }
        try {
            withTimeout(5000) { shown.await() }
            job.cancel()
        } finally {
            // Cancellation completes on the EDT; release the mock dialog before joining.
            releaseDialog.countDown()
            SwingUtilities.invokeAndWait {}
        }
        withTimeout(5000) { job.join() }
        assertTrue(job.isCancelled)
        assertEquals(false, sent)
        verify(exactly = 0) { config.addAutoApproveTarget(any()) }
        verify { dialog.dispose() }
    }

    @Test
    fun `active approval still allows and persists permission`() = runBlocking {
        mockkObject(Dialogs)
        val config = mockk<McpConfig>(relaxed = true)
        every { Dialogs.showOptionDialog(any(), any(), any(), any(), any(), any()) } returns 2
        assertTrue(SwingUserApprovalHandler().requestApproval("example.com", 443, config, null, null))
        verify(exactly = 1) { config.addAutoApproveTarget("example.com:443") }
    }
}
