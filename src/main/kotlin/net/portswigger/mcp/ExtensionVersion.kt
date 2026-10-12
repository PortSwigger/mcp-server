package net.portswigger.mcp

/**
 * Visible build label for this fork. Keep in sync with the Gradle and BApp
 * versions so the loaded extension can be identified in Burp.
 */
object ExtensionVersion {
    const val VERSION = "1.3.2"
    const val TAB_TITLE = "MCP v$VERSION"
    const val NAME = "Burp MCP Server v$VERSION"
}
