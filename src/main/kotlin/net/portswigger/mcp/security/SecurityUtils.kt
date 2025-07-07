package net.portswigger.mcp.security

import java.awt.Frame
import net.portswigger.mcp.config.McpConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class UserOptionsWrapper (
    var user_options: UserOptions
)

@Serializable
data class UserOptions (
    var bchecks: JsonObject,
    var connections: Connections,
    var display: JsonObject,
    var extender: JsonObject,
    var intruder: JsonObject,
    var misc: JsonObject,
    var proxy: JsonObject,
    var repeater: JsonObject,
    var ssl: JsonObject
)

@Serializable
data class ProjectOptionsWrapper (
    var bambda: JsonObject,
    var logger: JsonObject,
    var organiser: JsonObject,
    var project_options: ProjectOptions,
    var proxy: JsonObject,
    var repeater: JsonObject,
    var sequencer: JsonObject,
    var target: JsonObject
)

@Serializable
data class ProjectOptions (
    var connections: ProjectConnections,
    var dns: JsonObject,
    var http: JsonObject,
    var misc: JsonObject,
    var sessions: JsonObject,
    var ssl: JsonObject
)

@Serializable
data class Connections (
    var platform_authentication: PlatformAuth,
    var socks_proxy: JsonObject,
    var upstream_proxy: JsonObject
)

@Serializable
data class ProjectConnections (
    var out_of_scope_requests: JsonObject,
    var platform_authentication: ProjectPlatformAuth,
    var socks_proxy: JsonObject,
    var timeouts: JsonObject,
    var upstream_proxy: JsonObject
)

@Serializable
data class PlatformAuth (
    var credentials: JsonArray,
    var do_platform_authentication: Boolean,
    var prompt_on_authentication_failure: Boolean
)

@Serializable
data class ProjectPlatformAuth (
    var credentials: JsonArray,
    var do_platform_authentication: Boolean,
    var prompt_on_authentication_failure: Boolean,
    var use_user_options: Boolean
)

/**
 * Finds the Burp Suite main frame or the largest available frame as fallback
 */
fun findBurpFrame(): Frame? {
    val burpIdentifiers = listOf("Burp Suite", "Professional", "Community", "burp")

    return Frame.getFrames().find { frame ->
        frame.isVisible && frame.isDisplayable && burpIdentifiers.any { identifier ->
            frame.title.contains(identifier, ignoreCase = true) ||
                    frame.javaClass.name.contains(identifier, ignoreCase = true) ||
                    frame.javaClass.simpleName.contains(identifier, ignoreCase = true)
        }
    } ?: Frame.getFrames()
        .filter { it.isVisible && it.isDisplayable }
        .maxByOrNull { it.width * it.height }
}

fun filterUserConfigCredentials(jsonString: String): String {
    try {
        val userOptionWrapper = Json.decodeFromString<UserOptionsWrapper>(jsonString)
        val userOptions = userOptionWrapper.user_options

        val connections = userOptions.connections
        val credentials = connections.platform_authentication.credentials
        val socks_proxy = connections.socks_proxy

        connections.platform_authentication.credentials = filterPlatformAuth(credentials)   
        connections.socks_proxy = filterSocksProxy(socks_proxy) as JsonObject

        return Json.encodeToString(userOptionWrapper)
    } catch (e: Exception) {
        throw RuntimeException("Failed to filter user config credentials", e)
    }
}

fun filterProjectConfigCredentials(jsonString: String): String {
    try {
        val projectOptionsWrapper = Json.decodeFromString<ProjectOptionsWrapper>(jsonString)
        val options = projectOptionsWrapper.project_options

        val connections = options.connections
        val credentials = connections.platform_authentication.credentials
        val socks_proxy = connections.socks_proxy
        connections.platform_authentication.credentials = filterPlatformAuth(credentials)
        connections.socks_proxy = filterSocksProxy(socks_proxy) as JsonObject

        return Json.encodeToString(projectOptionsWrapper)
    } catch (e: Exception) {
        throw RuntimeException("Failed to filter project config credentials", e)
    }
}

private fun filterPlatformAuth(array: JsonArray): JsonArray {
    return JsonArray(array.map { element -> 
        val credentialObj = element.jsonObject
        JsonObject(
            credentialObj.mapValues { (key, value) ->
                when (key) {
                    "password" -> JsonPrimitive("*****")
                    else -> value
                }
            }
        )
    })
}

private fun filterSocksProxy(value: JsonElement): JsonElement {
    val obj = value.jsonObject
    return JsonObject(
        obj.mapValues { (key, value) ->
            when (key) {
                "password" -> JsonPrimitive("*****")
                else -> value
            }
        }
    )
}