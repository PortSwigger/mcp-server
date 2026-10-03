package net.portswigger.mcp.config

import burp.api.montoya.logging.Logging
import burp.api.montoya.persistence.PersistedObject
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.portswigger.mcp.ServerState
import net.portswigger.mcp.Swing
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.ResourceLock
import java.awt.Component
import java.awt.Container
import java.awt.EventQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JCheckBox
import javax.swing.JList
import javax.swing.JTextField

@ResourceLock("SwingDialogs")
@Timeout(20, unit = TimeUnit.SECONDS)
class ConfigUiLifecycleTest {
    private val fixtures = mutableListOf<UiFixture>()
    private val gates = mutableListOf<EdtGate>()

    @BeforeEach
    fun setUp() {
        mockkObject(Dialogs)
        every { Dialogs.showMessageDialog(any(), any(), any()) } answers {
            assertTrue(EventQueue.isDispatchThread(), "Messages must be displayed on the EDT")
        }
    }

    @AfterEach
    fun tearDown(): Unit = runBlocking {
        gates.forEach { it.release.countDown() }
        try {
            onEdt { fixtures.forEach { it.ui.cleanup() } }
            flushEdt()
        } finally {
            fixtures.forEach { it.config.cleanup() }
            unmockkObject(Dialogs)
        }
    }

    @Test
    fun `queued Failed update after cleanup cannot show dialog or change enabled components`(): Unit = runBlocking {
        val fixture = fixture()
        onEdt {
            // Positive control: the same live UI can display a failure and enable fields.
            fixture.ui.updateServerState(ServerState.Failed(IllegalStateException("live failure")))
        }
        verify(exactly = 1) { Dialogs.showMessageDialog(any(), any(), any()) }
        onEdt { fixture.ui.updateServerState(ServerState.Running) }
        val enabledChanges = ConcurrentLinkedQueue<Component>()
        val enabledBefore = onEdt {
            val inputs = descendants(fixture.ui.component).filterIsInstance<JTextField>().toList()
            assertEquals(2, inputs.size)
            assertTrue(inputs.none { it.isEnabled }, "Running server disables both listener fields")
            descendants(fixture.ui.component).associateWith { component ->
                component.addPropertyChangeListener("enabled") { enabledChanges.add(component) }
                component.isEnabled
            }
        }

        val gate = blockEdt()
        try {
            fixture.ui.updateServerState(ServerState.Failed(IllegalStateException("queued failure")))
            fixture.ui.cleanup()
        } finally {
            gate.release.countDown()
        }
        withTimeout(5_000) { gate.finished.await() }
        flushEdt()
        assertTrue(enabledChanges.isEmpty(), "Disposed UI must not receive queued enable/disable changes")
        onEdt {
            enabledBefore.forEach { (component, enabled) -> assertEquals(enabled, component.isEnabled) }
        }
        verify(exactly = 1) { Dialogs.showMessageDialog(any(), any(), any()) }
    }

    @Test
    fun `queued data access refresh is suppressed after cleanup`(): Unit = runBlocking {
        val fixture = fixture()
        val history = onEdt { historyCheckbox(fixture.ui) }
        onEdt { fixture.config.alwaysAllowHttpHistory = true }
        flushEdt()
        assertTrue(onEdt { history.isSelected }, "Live refresh should update the real checkbox")
        onEdt { fixture.config.alwaysAllowHttpHistory = false }
        flushEdt()
        assertFalse(onEdt { history.isSelected })

        val changesAfterCleanup = AtomicInteger()
        onEdt { history.addItemListener { changesAfterCleanup.incrementAndGet() } }
        val gate = blockEdt()
        try {
            fixture.config.alwaysAllowHttpHistory = true
            fixture.ui.cleanup()
        } finally {
            gate.release.countDown()
        }
        withTimeout(5_000) { gate.finished.await() }
        flushEdt()
        assertTrue(fixture.config.alwaysAllowHttpHistory, "The permission change actually persisted")
        assertFalse(onEdt { history.isSelected }, "Queued refresh must leave the disposed checkbox unchanged")
        assertEquals(0, changesAfterCleanup.get())
    }

    @Test
    fun `data refresh already dispatched on EDT cannot mutate checkbox after cleanup`(): Unit = runBlocking {
        val fixture = fixture()
        val history = onEdt { historyCheckbox(fixture.ui) }
        val changesAfterCleanup = AtomicInteger()
        val selectionAtCleanup = onEdt {
            fixture.config.alwaysAllowHttpHistory = true
            fixture.ui.cleanup()
            history.addItemListener { changesAfterCleanup.incrementAndGet() }
            history.isSelected
        }
        // This also drains a nested invokeLater if the panel ever reintroduces one.
        flushEdt()
        assertEquals(selectionAtCleanup, onEdt { history.isSelected })
        assertEquals(0, changesAfterCleanup.get(), "Refresh must complete before cleanup or be suppressed")
    }

    @Test
    fun `queued target list refresh cannot repaint the disposed UI`(): Unit = runBlocking {
        val fixture = fixture("kept.example")
        val targets = onEdt {
            descendants(fixture.ui.component).filterIsInstance<JList<*>>().single()
        }
        onEdt { fixture.config.addAutoApproveTarget("visible.example") }
        flushEdt()
        val visibleBefore = onEdt { listEntries(targets) }
        assertEquals(listOf("kept.example", "visible.example"), visibleBefore)

        val gate = blockEdt()
        try {
            fixture.config.addAutoApproveTarget("late.example")
            fixture.ui.cleanup()
        } finally {
            gate.release.countDown()
        }
        withTimeout(5_000) { gate.finished.await() }
        flushEdt()
        assertEquals(listOf("kept.example", "visible.example", "late.example"),
            fixture.config.getAutoApproveTargetsList())
        assertEquals(visibleBefore, onEdt { listEntries(targets) })
    }

    private suspend fun fixture(targets: String = ""): UiFixture =
        onEdt { UiFixture(targets) }.also { fixtures.add(it) }

    private suspend fun blockEdt(): EdtGate {
        val gate = EdtGate().also { gates.add(it) }
        gate.enqueue()
        withTimeout(5_000) { gate.entered.await() }
        return gate
    }

    private suspend fun flushEdt() {
        onEdt { }
        onEdt { }
    }

    private suspend fun <T> onEdt(block: () -> T): T =
        withTimeout(5_000) { withContext(Dispatchers.Swing) { block() } }

    private fun historyCheckbox(ui: ConfigUi): JCheckBox =
        descendants(ui.component).filterIsInstance<JCheckBox>()
            .single { it.text == "Always allow HTTP history access" }

    private fun listEntries(list: JList<*>): List<String> =
        (0 until list.model.size).map { list.model.getElementAt(it).toString() }

    private fun descendants(component: Component): Sequence<Component> = sequence {
        yield(component)
        if (component is Container) component.components.forEach { yieldAll(descendants(it)) }
    }

    private class UiFixture(targets: String) {
        private val values = ConcurrentHashMap<String, Any>().apply { put("_autoApproveTargets", targets) }
        private val storage = mockk<PersistedObject>().apply {
            every { getBoolean(any()) } answers { values[firstArg<String>()] as? Boolean }
            every { getString(any()) } answers { values[firstArg<String>()] as? String }
            every { getInteger(any()) } answers { values[firstArg<String>()] as? Int }
            every { setBoolean(any(), any()) } answers { values[firstArg<String>()] = secondArg<Boolean>() }
            every { setString(any(), any()) } answers { values[firstArg<String>()] = secondArg<String>() }
            every { setInteger(any(), any()) } answers { values[firstArg<String>()] = secondArg<Int>() }
        }
        val config = McpConfig(storage, mockk<Logging>(relaxed = true))
        val ui = ConfigUi(config, providers = emptyList())
    }

    private class EdtGate {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val finished = CompletableDeferred<Unit>()

        fun enqueue() {
            EventQueue.invokeLater {
                entered.complete(Unit)
                try {
                    check(release.await(5, TimeUnit.SECONDS)) { "EDT gate was not released" }
                    finished.complete(Unit)
                } catch (failure: Throwable) {
                    finished.completeExceptionally(failure)
                }
            }
        }
    }
}
