package net.portswigger.mcp.security

import burp.api.montoya.logging.Logging
import burp.api.montoya.persistence.PersistedObject
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.portswigger.mcp.Swing
import net.portswigger.mcp.config.Dialogs
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.tools.ToolExecution
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.ResourceLock
import java.awt.EventQueue
import java.awt.SecondaryLoop
import java.awt.Toolkit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JDialog

@ResourceLock("SwingDialogs")
@Timeout(20, unit = TimeUnit.SECONDS)
class ApprovalLifecycleTest {
    private val fixtures = mutableListOf<ApprovalConfig>()
    private val approvals = mutableListOf<Deferred<Boolean>>()
    private val modalDialogs = mutableListOf<ModalDialog>()
    private val scopes = mutableListOf<CoroutineScope>()
    private val edtReleases = mutableListOf<CountDownLatch>()

    @BeforeEach
    fun setUp() {
        mockkObject(Dialogs)
    }

    @AfterEach
    fun tearDown(): Unit = runBlocking {
        approvals.forEach { it.cancel() }
        scopes.forEach { it.cancel() }
        edtReleases.forEach { it.countDown() }
        modalDialogs.forEach { it.forceClose() }
        try {
            withTimeout(5_000) { approvals.joinAll() }
            onEdt { }
            onEdt { }
        } finally {
            fixtures.forEach { it.config.cleanup() }
            unmockkObject(Dialogs)
        }
    }

    @Test
    fun `remembered HTTP approval persists on EDT and preserves target removal while waiting`(): Unit = runBlocking {
        val fixture = fixture("removed.example\nkept.example")
        val listenerThreads = ConcurrentLinkedQueue<Boolean>()
        val listener: () -> Unit = { listenerThreads.add(EventQueue.isDispatchThread()) }
        fixture.listeners.add(listener)
        fixture.config.addTargetsChangeListener(listener)
        val modal = pendingDialog(choice = 2)
        val approval = startApproval {
            SwingUserApprovalHandler().requestApproval("new.example", 8443, fixture.config, null, null)
        }
        withTimeout(5_000) { modal.created.await() }

        onEdt { assertTrue(fixture.config.removeAutoApproveTarget("removed.example")) }
        modal.resolve(2)
        assertTrue(withTimeout(5_000) { approval.await() })
        assertEquals(listOf("kept.example", "new.example:8443"), fixture.config.getAutoApproveTargetsList())
        assertEquals(2, fixture.writes.size, "Removal and remembered approval should each persist once")
        assertTrue(fixture.writes.all { it.onEdt }, "Remembered targets must persist on the EDT")
        assertEquals(listOf(true, true), listenerThreads.toList())
        assertTrue(modal.disposedOnEdt.await())
    }

    @Test
    fun `remembered data approvals resolve and persist each permission on EDT`(): Unit = runBlocking {
        val fixture = fixture()
        val listenerThreads = ConcurrentLinkedQueue<Boolean>()
        val listener: () -> Unit = { listenerThreads.add(EventQueue.isDispatchThread()) }
        fixture.listeners.add(listener)
        fixture.config.addDataAccessChangeListener(listener)
        val dialog = mockk<JDialog>()
        every { dialog.dispose() } answers { assertTrue(EventQueue.isDispatchThread()) }
        every { Dialogs.showOptionDialog(any(), any(), any(), any(), any(), any()) } answers {
            assertTrue(EventQueue.isDispatchThread(), "Dialog creation must run on the EDT")
            assertTrue(arg<(JDialog) -> Boolean>(5).invoke(dialog))
            dialog.dispose()
            1
        }

        for (accessType in DataAccessType.entries) {
            val approval = startApproval {
                SwingDataAccessApprovalHandler().requestDataAccess(accessType, fixture.config)
            }
            assertTrue(withTimeout(5_000) { approval.await() }, accessType.name)
        }
        assertTrue(fixture.config.alwaysAllowHttpHistory)
        assertTrue(fixture.config.alwaysAllowWebSocketHistory)
        assertTrue(fixture.config.alwaysAllowOrganizer)
        assertEquals(3, fixture.writes.size)
        assertTrue(fixture.writes.all { it.onEdt })
        assertEquals(listOf(true, true, true), listenerThreads.toList())
    }

    @Test
    fun `HTTP and data cancellation before dialog creation skips show and persistence`(): Unit = runBlocking {
        val fixture = fixture()
        every { Dialogs.showOptionDialog(any(), any(), any(), any(), any(), any()) } answers {
            error("Cancelled approval must never create a dialog")
        }
        val requests: List<suspend () -> Boolean> = listOf(
            { SwingUserApprovalHandler().requestApproval("cancelled.example", 443, fixture.config, null, null) },
            { SwingDataAccessApprovalHandler().requestDataAccess(DataAccessType.HTTP_HISTORY, fixture.config) },
        )
        for (request in requests) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing).also { scopes.add(it) }
            val scheduled = CompletableDeferred<Deferred<Boolean>>()
            val releaseEdt = CountDownLatch(1).also { edtReleases.add(it) }
            EventQueue.invokeLater {
                try {
                    // Run through requestOption until it queues show, then keep the EDT
                    // occupied so cancellation occurs before that queued creation callback.
                    val approval = scope.async(start = CoroutineStart.UNDISPATCHED) { request() }
                    scheduled.complete(approval)
                    check(releaseEdt.await(5, TimeUnit.SECONDS)) { "EDT gate was not released" }
                } catch (failure: Throwable) {
                    scheduled.completeExceptionally(failure)
                }
            }
            val approval = withTimeout(5_000) { scheduled.await() }.also { approvals.add(it) }
            try {
                approval.cancel()
            } finally {
                releaseEdt.countDown()
            }
            withTimeout(5_000) { approval.join() }
            onEdt { }
            assertTrue(approval.isCancelled)
        }
        verify(exactly = 0) { Dialogs.showOptionDialog(any(), any(), any(), any(), any(), any()) }
        assertTrue(fixture.writes.isEmpty())
        assertTrue(fixture.config.getAutoApproveTargetsList().isEmpty())
        assertFalse(fixture.config.alwaysAllowHttpHistory)
    }

    @Test
    fun `pending HTTP cancellation disposes on EDT and ignores late Always Allow`(): Unit = runBlocking {
        val fixture = fixture()
        val modal = pendingDialog(choice = 1)
        val approval = startApproval {
            SwingUserApprovalHandler().requestApproval("cancelled.example", 443, fixture.config, null, null)
        }
        expectPendingCancellation(approval, modal)
        assertTrue(fixture.writes.isEmpty())
        assertTrue(fixture.config.getAutoApproveTargetsList().isEmpty())
    }

    @Test
    fun `pending data cancellation disposes on EDT and ignores late Always Allow`(): Unit = runBlocking {
        val fixture = fixture()
        val modal = pendingDialog(choice = 1)
        val approval = startApproval {
            SwingDataAccessApprovalHandler().requestDataAccess(DataAccessType.ORGANIZER, fixture.config)
        }
        expectPendingCancellation(approval, modal)
        assertTrue(fixture.writes.isEmpty())
        assertFalse(fixture.config.alwaysAllowOrganizer)
    }

    @Test
    fun `HTTP remembered write already started can finish after owner closes`(): Unit = runBlocking {
        expectCooperativeCommit(choice = 2, key = "_autoApproveTargets", request = { config ->
            SwingUserApprovalHandler().requestApproval("remembered.example", 8443, config, null, null)
        }, assertRemembered = { config ->
            assertEquals(listOf("remembered.example:8443"), config.getAutoApproveTargetsList())
        })
    }

    @Test
    fun `data remembered write already started can finish after owner closes`(): Unit = runBlocking {
        expectCooperativeCommit(choice = 1, key = "_alwaysAllowHttpHistory", request = { config ->
            SwingDataAccessApprovalHandler().requestDataAccess(DataAccessType.HTTP_HISTORY, config)
        }, assertRemembered = { config ->
            assertTrue(config.alwaysAllowHttpHistory)
        })
    }

    private suspend fun expectCooperativeCommit(
        choice: Int,
        key: String,
        request: suspend (McpConfig) -> Boolean,
        assertRemembered: (McpConfig) -> Unit,
    ) {
        val fixture = fixture()
        val owner = ToolExecution()
        val writeEntered = CompletableDeferred<Unit>()
        val releaseWrite = CountDownLatch(1).also { edtReleases.add(it) }
        fixture.beforeWrite = { writtenKey ->
            assertEquals(key, writtenKey)
            assertTrue(EventQueue.isDispatchThread())
            writeEntered.complete(Unit)
            check(releaseWrite.await(5, TimeUnit.SECONDS)) { "Remembered write was not released" }
        }
        val dialog = mockk<JDialog>()
        every { dialog.dispose() } answers { assertTrue(EventQueue.isDispatchThread()) }
        every { Dialogs.showOptionDialog(any(), any(), any(), any(), any(), any()) } answers {
            assertTrue(EventQueue.isDispatchThread())
            assertTrue(arg<(JDialog) -> Boolean>(5).invoke(dialog))
            dialog.dispose()
            choice
        }
        val approval = startApproval { owner.run { request(fixture.config) } }
        try {
            withTimeout(5_000) { writeEntered.await() }
            owner.close()
            assertFalse(owner.isOpen)
            assertEquals(1, owner.activeCount, "A synchronous commit retains its unfinished tool slot")
            assertTrue(fixture.writes.isEmpty(), "The gate must stop before the actual persistence effect")

            releaseWrite.countDown()
            withTimeout(5_000) { approval.join() }
            onEdt { }
            assertTrue(approval.isCancelled, "Closing the owner still cancels the tool result")
            assertEquals(0, owner.activeCount)
            assertEquals(listOf(key), fixture.writes.map { it.key })
            assertTrue(fixture.writes.all { it.onEdt })
            assertRemembered(fixture.config)
        } finally {
            releaseWrite.countDown()
            owner.close()
        }
    }

    private suspend fun expectPendingCancellation(approval: Deferred<Boolean>, modal: ModalDialog) {
        withTimeout(5_000) { modal.created.await() }
        approval.cancel()
        assertTrue(withTimeout(5_000) { modal.disposedOnEdt.await() }, "Disposal must occur on the EDT")
        withTimeout(5_000) {
            modal.returned.await()
            approval.join()
        }
        onEdt { }
        assertTrue(approval.isCancelled)
        verify(exactly = 1) { modal.dialog.dispose() }
    }

    private fun startApproval(request: suspend () -> Boolean): Deferred<Boolean> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes.add(it) }
        return scope.async { request() }.also { approvals.add(it) }
    }

    private fun fixture(targets: String = ""): ApprovalConfig =
        ApprovalConfig(targets).also { fixtures.add(it) }

    private fun pendingDialog(choice: Int): ModalDialog {
        val modal = ModalDialog(choice).also { modalDialogs.add(it) }
        every { Dialogs.showOptionDialog(any(), any(), any(), any(), any(), any()) } answers {
            modal.show(arg(5))
        }
        return modal
    }

    private suspend fun <T> onEdt(block: () -> T): T =
        withTimeout(5_000) { withContext(Dispatchers.Swing) { block() } }

    private data class PersistenceWrite(val key: String, val value: Any, val onEdt: Boolean)

    private class ApprovalConfig(targets: String) {
        private val values = ConcurrentHashMap<String, Any>().apply { put("_autoApproveTargets", targets) }
        val writes = ConcurrentLinkedQueue<PersistenceWrite>()
        var beforeWrite: ((String) -> Unit)? = null
        val listeners = mutableListOf<() -> Unit>()
        private val storage = mockk<PersistedObject>().apply {
            every { getBoolean(any()) } answers { values[firstArg<String>()] as? Boolean }
            every { getString(any()) } answers { values[firstArg<String>()] as? String }
            every { getInteger(any()) } answers { values[firstArg<String>()] as? Int }
            every { setBoolean(any(), any()) } answers {
                val key = firstArg<String>()
                val value = secondArg<Boolean>()
                beforeWrite?.invoke(key)
                values[key] = value
                writes.add(PersistenceWrite(key, value, EventQueue.isDispatchThread()))
            }
            every { setString(any(), any()) } answers {
                val key = firstArg<String>()
                val value = secondArg<String>()
                beforeWrite?.invoke(key)
                values[key] = value
                writes.add(PersistenceWrite(key, value, EventQueue.isDispatchThread()))
            }
            every { setInteger(any(), any()) } answers {
                values[firstArg<String>()] = secondArg<Int>()
            }
        }
        val config = McpConfig(storage, mockk<Logging>(relaxed = true))
    }

    /** Mimics modal event pumping while leaving requestOption itself real. */
    private class ModalDialog(private var choice: Int) {
        val dialog = mockk<JDialog>()
        val created = CompletableDeferred<Unit>()
        val disposedOnEdt = CompletableDeferred<Boolean>()
        val returned = CompletableDeferred<Unit>()
        private var loop: SecondaryLoop? = null

        init {
            every { dialog.dispose() } answers {
                disposedOnEdt.complete(EventQueue.isDispatchThread())
                assertTrue(EventQueue.isDispatchThread(), "Dialog disposal must run on the EDT")
                loop?.exit()
            }
        }

        fun show(onShow: (JDialog) -> Boolean): Int {
            assertTrue(EventQueue.isDispatchThread())
            loop = Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop()
            assertTrue(onShow(dialog), "Pending dialog must belong to the live continuation")
            created.complete(Unit)
            try {
                assertTrue(loop!!.enter(), "Modal event loop should be entered")
                return choice // Can return Always Allow even after cancellation disposes it.
            } finally {
                returned.complete(Unit)
            }
        }

        suspend fun resolve(result: Int) {
            withTimeout(5_000) {
                withContext(Dispatchers.Swing) {
                    choice = result
                    dialog.dispose()
                }
            }
        }

        fun forceClose() {
            if (!returned.isCompleted) EventQueue.invokeLater { dialog.dispose() }
        }
    }
}
