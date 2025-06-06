package net.portswigger.mcp.config

private const val MAX_TARGET_LENGTH = 255

object TargetValidation {

    /**
     * Validates whether a target string is in a valid format for auto-approve lists.
     *
     * Valid formats include:
     * - Hostnames: example.com, localhost
     * - IP addresses: 127.0.0.1, ::1
     * - Hostnames with ports: example.com:8080, localhost:3000
     * - IPv6 with ports: [::1]:8080
     * - Wildcards: *.example.com, *.api.com
     *
     * @param target The target string to validate
     * @return true if the target is valid, false otherwise
     */
    fun isValidTarget(target: String): Boolean {
        if (target.isBlank() || target.length > MAX_TARGET_LENGTH) return false

        if (target.contains("\t") || target.contains("\n") || target.contains("\r")) return false

        if (target.startsWith("[") && target.contains("]:")) {
            val portPart = target.substringAfterLast(":")
            val port = portPart.toIntOrNull()
            return !(port == null || port < 1 || port > 65535)
        }

        val parts = target.split(":")
        if (parts.size == 2) {
            val port = parts[1].toIntOrNull()
            if (port == null || port < 1 || port > 65535) return false
        } else if (parts.size > 2) {
            return true
        }

        return true
    }
}