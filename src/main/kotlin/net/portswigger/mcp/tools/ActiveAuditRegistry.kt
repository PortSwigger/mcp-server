package net.portswigger.mcp.tools

import burp.api.montoya.scanner.Crawl
import burp.api.montoya.scanner.audit.Audit
import java.util.concurrent.atomic.AtomicInteger

data class ActiveAuditEntry(
    val crawl: Crawl?,
    val audit: Audit,
    val pollingThread: Thread?,
    val cleanup: (() -> Unit)? = null,
)

data class StopAllResult(val total: Int, val failed: Int, val errors: List<String>)

class ActiveAuditRegistry {
    private val entries = LinkedHashMap<String, ActiveAuditEntry>()
    private val counter = AtomicInteger(0)

    @Synchronized
    fun register(
        crawl: Crawl? = null,
        audit: Audit,
        pollingThread: Thread? = null,
        cleanup: (() -> Unit)? = null,
    ): String {
        val id = "audit-${counter.incrementAndGet()}"
        entries[id] = ActiveAuditEntry(crawl, audit, pollingThread, cleanup)
        return id
    }

    private fun stopEntry(
        entry: ActiveAuditEntry,
        interruptPollingThread: Boolean,
        formatError: (action: String, error: Exception) -> String,
    ): List<String> {
        val errors = mutableListOf<String>()

        if (interruptPollingThread && entry.pollingThread != null && entry.pollingThread != Thread.currentThread()) {
            try {
                entry.pollingThread.interrupt()
            } catch (_: Exception) {}
        }

        try {
            entry.audit.delete()
        } catch (e: Exception) {
            errors.add(formatError("audit delete", e))
        }

        try {
            entry.crawl?.delete()
        } catch (e: Exception) {
            errors.add(formatError("crawl delete", e))
        }

        try {
            entry.cleanup?.invoke()
        } catch (e: Exception) {
            errors.add(formatError("cleanup", e))
        }

        return errors
    }

    fun stopAll(): StopAllResult {
        val snapshot: Map<String, ActiveAuditEntry>
        synchronized(this) {
            snapshot = entries.toMap()
            entries.clear()
        }

        val errors = mutableListOf<String>()
        snapshot.forEach { (id, entry) ->
            errors += stopEntry(entry, interruptPollingThread = true) { action, error ->
                "$id $action: ${error.message}"
            }
        }

        return StopAllResult(snapshot.size, errors.size, errors)
    }

    fun stopById(id: String, interruptPollingThread: Boolean = true): String? {
        val entry: ActiveAuditEntry
        synchronized(this) {
            entry = entries.remove(id) ?: return null
        }

        val errors = stopEntry(entry, interruptPollingThread) { action, error ->
            "$action failed: ${error.message}"
        }

        return if (errors.isEmpty()) {
            "Stopped audit $id"
        } else {
            "Stopped audit $id (${errors.joinToString("; ")})"
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        counter.set(0)
    }
}
