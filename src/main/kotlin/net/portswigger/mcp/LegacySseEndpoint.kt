package net.portswigger.mcp

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.modelcontextprotocol.kotlin.sdk.server.DnsRebindingProtection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.server.SseServerTransport
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Owns legacy connections without registering per-client callbacks on the shared SDK server. */
internal class LegacySseEndpoint(private val server: Server) {
    private val transports = ConcurrentHashMap<String, SseServerTransport>()
    val activeTransportCount: Int get() = transports.size

    fun mount(application: Application) = with(application) {
        install(SSE)
        routing {
            // Match the SDK helper's host AND origin defaults; the plugin alone omits origins.
            install(DnsRebindingProtection) {
                allowedHosts = listOf("localhost", "127.0.0.1", "[::1]")
                allowedOrigins = listOf("http://localhost", "http://127.0.0.1", "http://[::1]")
            }
            sse {
                val transport = SseServerTransport("", this)
                transports[transport.sessionId] = transport
                var session: ServerSession? = null
                try {
                    session = server.createSession(transport)
                    // A quiet disconnected SSE peer is observed on the next write. Comments
                    // keep idle clients alive without adding protocol messages or a detached job.
                    while (true) {
                        delay(1_000L)
                        send(comments = "keep-alive")
                    }
                } finally {
                    transports.remove(transport.sessionId, transport)
                    withContext(NonCancellable) {
                        session?.close() ?: transport.close()
                    }
                }
            }
            post {
                val sessionId = call.request.queryParameters["sessionId"]
                if (sessionId == null) {
                    call.respond(HttpStatusCode.BadRequest, "sessionId query parameter is not provided")
                    return@post
                }
                val transport = transports[sessionId]
                if (transport == null) {
                    call.respond(HttpStatusCode.NotFound, "Session not found")
                    return@post
                }
                // SDK retains body/media validation, the 4 MiB bound and JSON-RPC decoding.
                transport.handlePostMessage(call)
            }
        }
    }
}
