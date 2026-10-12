package net.portswigger.mcp.tools

import burp.api.montoya.http.handler.HttpHandler
import burp.api.montoya.http.handler.HttpRequestToBeSent
import burp.api.montoya.http.handler.HttpResponseReceived
import burp.api.montoya.http.handler.RequestToBeSentAction
import burp.api.montoya.http.handler.ResponseReceivedAction
import burp.api.montoya.http.message.requests.HttpRequest

/** A bounded index of requests observed through Montoya after this extension loads. */
class CapturedHttpRequests(private val capacity: Int = 500) : HttpHandler {
    init {
        require(capacity > 0)
    }

    internal data class Entry(val id: Int, val request: HttpRequest, val tool: String)

    private val entries = LinkedHashMap<Int, Entry>()
    private val lock = Any()

    override fun handleHttpRequestToBeSent(requestToBeSent: HttpRequestToBeSent): RequestToBeSentAction {
        runCatching {
            record(
                requestToBeSent.messageId(),
                requestToBeSent,
                requestToBeSent.toolSource().toolType().name
            )
        }
        return RequestToBeSentAction.continueWith(requestToBeSent)
    }

    override fun handleHttpResponseReceived(responseReceived: HttpResponseReceived): ResponseReceivedAction {
        runCatching {
            record(
                responseReceived.messageId(),
                responseReceived.initiatingRequest(),
                responseReceived.toolSource().toolType().name
            )
        }
        return ResponseReceivedAction.continueWith(responseReceived)
    }

    internal fun record(id: Int, request: HttpRequest, tool: String) {
        val snapshot = runCatching { request.copyToTempFile() }.getOrDefault(request)
        synchronized(lock) {
            entries[id] = Entry(id, snapshot, tool)
            while (entries.size > capacity) entries.remove(entries.keys.first())
        }
    }

    internal fun get(id: Int): Entry? = synchronized(lock) { entries[id] }

    internal fun recent(limit: Int): List<Entry> = synchronized(lock) {
        entries.values.toList().asReversed().take(limit.coerceIn(0, capacity))
    }

    internal fun clear() = synchronized(lock) { entries.clear() }
}
