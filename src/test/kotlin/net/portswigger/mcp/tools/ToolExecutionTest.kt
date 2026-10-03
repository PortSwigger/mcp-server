package net.portswigger.mcp.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Timeout(15, unit = TimeUnit.SECONDS)
class ToolExecutionTest {
    @Test
    fun `closing owner cancels suspended work and rejects later work before execution`(): Unit = runBlocking {
        val owner = ToolExecution(maxConcurrentTools = 1)
        val entered = CompletableDeferred<Unit>()
        var laterOperationRan = false
        val operation = async {
            owner.run {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        try {
            withTimeout(5_000) { entered.await() }
            owner.close()
            withTimeout(5_000) { operation.join() }
            assertTrue(operation.isCancelled)

            val rejection = runCatching { owner.run { laterOperationRan = true } }.exceptionOrNull()
            assertInstanceOf(CancellationException::class.java, rejection)
            assertFalse(laterOperationRan, "Closed owner must reject before entering the operation")
        } finally {
            owner.close()
        }
    }

    @Test
    fun `cancelled synchronous work occupies capacity until the operation actually returns`(): Unit = runBlocking {
        val owner = ToolExecution(maxConcurrentTools = 1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = AtomicBoolean(false)
        var competingOperationRan = false
        val operation = async(Dispatchers.Default) {
            owner.run {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "Blocked operation was not released" }
                returned.set(true)
            }
        }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            operation.cancel()
            assertFalse(returned.get())
            assertFalse(operation.isCompleted, "Cancellation must wait for the synchronous operation to return")

            val rejection = runCatching {
                owner.run { competingOperationRan = true }
            }.exceptionOrNull()
            assertInstanceOf(ToolCapacityExceededException::class.java, rejection)
            assertFalse(competingOperationRan, "Cancelled work must still reserve capacity")

            release.countDown()
            withTimeout(5_000) { operation.join() }
            assertTrue(operation.isCancelled)
            assertTrue(returned.get(), "The already-started synchronous operation still completes")
            assertEquals("admitted after return", owner.run { "admitted after return" })
        } finally {
            release.countDown()
            owner.close()
            withTimeout(5_000) { operation.join() }
        }
    }
}
