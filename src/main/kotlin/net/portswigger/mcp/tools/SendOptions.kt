package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.ByteArray
import burp.api.montoya.http.HttpMode
import burp.api.montoya.http.RedirectionMode
import burp.api.montoya.http.RequestOptions
import burp.api.montoya.http.message.HttpRequestResponse
import burp.api.montoya.http.message.requests.HttpRequest
import java.util.Base64

const val SEND_OPTIONS_DESCRIPTION =
    "Optional: responseTimeoutMs, redirectionMode (never|always|same_host|in_scope), serverNameIndicator, " +
            "addToSiteMap (records the exchange in the Target site map)."

/** Optional per-request settings shared by the send tools. */
interface SendOptionsParams {
    val responseTimeoutMs: Int?
    val redirectionMode: String?
    val serverNameIndicator: String?
    val addToSiteMap: Boolean

    /** Null when no option is set, so plain sends keep using the simple Montoya overloads. */
    fun toRequestOptions(mode: HttpMode?): RequestOptions? {
        if (responseTimeoutMs == null && redirectionMode == null && serverNameIndicator == null) return null

        var options = RequestOptions.requestOptions()
        mode?.let { options = options.withHttpMode(it) }
        responseTimeoutMs?.let { options = options.withResponseTimeout(it.toLong()) }
        redirectionMode?.let { options = options.withRedirectionMode(parseRedirectionMode(it)) }
        serverNameIndicator?.let { options = options.withServerNameIndicator(it) }
        return options
    }
}

internal fun parseRedirectionMode(value: String): RedirectionMode =
    RedirectionMode.entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
        ?: throw IllegalArgumentException(
            "Unknown redirectionMode '$value', expected one of: ${RedirectionMode.entries.joinToString("|") { it.name.lowercase() }}"
        )

internal fun HttpRequest.withBase64Body(bodyBase64: String?): HttpRequest =
    if (bodyBase64 == null) this else withBody(ByteArray.byteArray(*Base64.getDecoder().decode(bodyBase64)))

/** Sends [request] honouring [params], and optionally records the result in the site map. */
internal fun sendWithOptions(
    api: MontoyaApi,
    request: HttpRequest,
    mode: HttpMode?,
    params: SendOptionsParams
): HttpRequestResponse? {
    val options = params.toRequestOptions(mode)
    val result = when {
        options != null -> api.http().sendRequest(request, options)
        mode != null -> api.http().sendRequest(request, mode)
        else -> api.http().sendRequest(request)
    }
    if (params.addToSiteMap && result != null) {
        api.siteMap().add(result)
    }
    return result
}
