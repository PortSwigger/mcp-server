package net.portswigger.mcp.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

/** Owns unfinished tool work for one listener, including suspended approval waits. */
class ToolExecution(private val maxConcurrentTools: Int = 32) {
    private val lock = Any()
    private var open = true
    private val jobs = mutableSetOf<Job>()

    init {
        require(maxConcurrentTools > 0)
    }

    val isOpen: Boolean
        get() = synchronized(lock) { open }

    internal val activeCount: Int
        get() = synchronized(lock) { jobs.size }

    suspend fun <T> run(block: suspend () -> T): T = coroutineScope {
        val job = coroutineContext.job
        synchronized(lock) {
            if (!open) throw CancellationException("MCP listener stopped")
            if (jobs.size >= maxConcurrentTools) throw ToolCapacityExceededException()
            jobs.add(job)
        }
        try {
            withContext(Dispatchers.IO) {
                coroutineContext.ensureActive()
                block()
            }
        } finally {
            synchronized(lock) { jobs.remove(job) }
        }
    }

    fun close() {
        val unfinished = synchronized(lock) {
            open = false
            jobs.toList()
        }
        unfinished.forEach { it.cancel(CancellationException("MCP listener stopped")) }
    }
}

class ToolCapacityExceededException : IllegalStateException("MCP server is busy; too many active tool calls")
