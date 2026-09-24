package net.portswigger.mcp.config

object AllowedHostValidation {

    private val HOST_PATTERN = Regex("^[a-zA-Z0-9.-]+$")

    fun isValidAllowedHost(host: String): Boolean {
        val trimmed = host.trim().lowercase()
        if (trimmed.isBlank()) return false
        return trimmed.matches(HOST_PATTERN)
    }

    fun parseCommaSeparated(value: String): List<String> {
        if (value.isBlank()) return emptyList()
        return value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun sanitizeAllowedHosts(value: String): String {
        return parseCommaSeparated(value)
            .filter { isValidAllowedHost(it) }
            .joinToString(",")
    }

    fun validateAllowedHosts(value: String): String? {
        val entries = parseCommaSeparated(value)
        if (entries.isEmpty()) return null

        val invalid = entries.filterNot { isValidAllowedHost(it) }
        if (invalid.isNotEmpty()) {
            return "Invalid allowed host(s): ${invalid.joinToString(", ")}"
        }

        return null
    }
}
