package net.portswigger.mcp.config

object ConfigValidation {

    fun validateServerConfig(host: String, portText: String): String? {
        val trimmedHost = host.trim()
        val port = portText.trim().toIntOrNull()

        if (trimmedHost.isBlank() || !trimmedHost.matches(Regex("^[a-zA-Z0-9.-]+$"))) {
            return "Host must be a non-empty alphanumeric string"
        }

        if (port == null) {
            return "Port must be a valid number"
        }

        if (port < 1024 || port > 65535) {
            return "Port is not within valid range"
        }

        return null
    }
}