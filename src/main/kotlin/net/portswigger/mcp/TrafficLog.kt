package net.portswigger.mcp

import burp.api.montoya.core.ToolType
import burp.api.montoya.http.handler.HttpHandler
import burp.api.montoya.http.handler.HttpRequestToBeSent
import burp.api.montoya.http.handler.HttpResponseReceived
import burp.api.montoya.http.handler.RequestToBeSentAction
import burp.api.montoya.http.handler.ResponseReceivedAction
import burp.api.montoya.http.message.HttpRequestResponse
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounded, in-memory record of HTTP traffic sent by Burp tools other than the Proxy
 * (Repeater, Intruder, Scanner, extensions including this MCP server, ...).
 *
 * Montoya offers no way to read Repeater tabs or Intruder results, but every response they
 * receive passes through registered HTTP handlers, so recording them here is the closest
 * available substitute. Only traffic seen after the extension loaded is available.
 */
class TrafficLog(private val capacity: Int = DEFAULT_CAPACITY) : HttpHandler {

    data class Entry(
        val id: Int,
        val time: ZonedDateTime,
        val tool: ToolType,
        val requestResponse: HttpRequestResponse
    )

    private val nextId = AtomicInteger(1)
    private val entries = ArrayDeque<Entry>()

    override fun handleHttpRequestToBeSent(requestToBeSent: HttpRequestToBeSent): RequestToBeSentAction =
        RequestToBeSentAction.continueWith(requestToBeSent)

    override fun handleHttpResponseReceived(responseReceived: HttpResponseReceived): ResponseReceivedAction {
        val tool = responseReceived.toolSource().toolType()
        if (tool != ToolType.PROXY) {
            // Temp-file backed copy keeps large bodies out of the heap.
            val requestResponse = HttpRequestResponse
                .httpRequestResponse(responseReceived.initiatingRequest(), responseReceived)
                .copyToTempFile()
            record(tool, requestResponse)
        }
        return ResponseReceivedAction.continueWith(responseReceived)
    }

    fun record(tool: ToolType, requestResponse: HttpRequestResponse, time: ZonedDateTime = ZonedDateTime.now()) {
        val entry = Entry(nextId.getAndIncrement(), time, tool, requestResponse)
        synchronized(entries) {
            entries.addLast(entry)
            while (entries.size > capacity) entries.removeFirst()
        }
    }

    /** Snapshot of the current entries, oldest first. */
    fun snapshot(): List<Entry> = synchronized(entries) { entries.toList() }

    fun find(id: Int): Entry? = synchronized(entries) { entries.firstOrNull { it.id == id } }

    companion object {
        const val DEFAULT_CAPACITY = 10_000
    }
}
