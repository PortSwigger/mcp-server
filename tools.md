# Burp MCP server tools

This catalog lists the 39 tools registered by `src/main/kotlin/net/portswigger/mcp/tools/Tools.kt` in version `1.3.2`. **Tool name to invoke** is the exact name exposed to an LLM through MCP. The four tools in the Burp Professional section are registered only in that edition. History and Organizer reads, HTTP sends, and extension probes may prompt for approval in Burp; configuration writes require the configuration editing option to be enabled. Paginated tools accept `count` and `offset`.

## HTTP requests, Repeater, and Intruder

| Name | Tool name to invoke | Description |
| --- | --- | --- |
| Send HTTP/1.1 request | `send_http1_request` | Sends a raw HTTP/1.1 request to a target and returns the response; can mark or truncate the response at a specified end marker. |
| Send HTTP/2 request | `send_http2_request` | Sends an HTTP/2 request built from pseudo-headers, headers, and a separate body, then returns the response; supports an end marker. |
| Create HTTP/1.1 Repeater tab | `create_repeater_tab` | Opens a Repeater tab containing a raw HTTP/1.1 request and optional notes; does not send the request. |
| Create HTTP/2 Repeater tab | `create_repeater_tab_http2` | Opens a Repeater tab containing an HTTP/2 request and optional notes; does not send the request. |
| Send Repeater request | `send_repeater_request` | Sends from a named MCP-created Repeater tab and returns its response. Can replace the request, set notes or an end marker, or explicitly fall back to Burp's HTTP API if the Repeater Send control is unavailable. |
| Get Repeater tab | `get_repeater_tab` | Returns a named MCP-created tab's request, notes, response end marker, connection ID, and last response. |
| List Repeater tabs | `list_repeater_tabs` | Lists Repeater tabs opened by MCP in the current Burp session. |
| Set Repeater notes | `set_repeater_notes` | Replaces or appends notes for a named MCP-created Repeater tab and attempts to update its Notes editor. |
| Set Repeater response end marker | `set_repeater_response_end_marker` | Saves or clears the marker used to identify the end of a named tab's response, with optional truncation after that marker. |
| Send proxy history item to Repeater | `send_proxy_history_to_repeater` | Opens a proxy HTTP history item in Repeater, optionally copying notes, selecting the final request, and sending it immediately. |
| Send to Intruder | `send_to_intruder` | Opens a raw HTTP request in Burp Intruder, with an optional tab name. |

## Proxy history, WebSocket history, and Organizer

| Name | Tool name to invoke | Description |
| --- | --- | --- |
| Get proxy HTTP history | `get_proxy_http_history` | Returns paginated proxy HTTP history items with their serialized request and response data. |
| Search proxy HTTP history | `get_proxy_http_history_regex` | Returns paginated proxy HTTP history items whose contents match a regular expression. |
| Summarize proxy HTTP history | `get_proxy_http_history_summary` | Returns short paginated JSON lines with each item's ID, time, method, URL, status, notes, and edited state; supports an optional regex. |
| Get proxy HTTP history item | `get_proxy_http_history_item` | Returns one proxy HTTP history item by ID, including request, response, and notes. |
| Set proxy history notes | `set_proxy_history_notes` | Writes or appends notes to a history item selected by ID or regex; can also set its highlight color. |
| Get proxy WebSocket history | `get_proxy_websocket_history` | Returns paginated proxy WebSocket history items. |
| Search proxy WebSocket history | `get_proxy_websocket_history_regex` | Returns paginated proxy WebSocket history items matching a regular expression. |
| Get Organizer items | `get_organizer_items` | Returns paginated items from Burp Organizer. |
| Search Organizer items | `get_organizer_items_regex` | Returns paginated Organizer items matching a regular expression. |

## Burp configuration and controls

| Name | Tool name to invoke | Description |
| --- | --- | --- |
| Output project options | `output_project_options` | Exports Burp project options as JSON; filters credentials when that setting is enabled. |
| Output user options | `output_user_options` | Exports Burp user options as JSON; filters credentials when that setting is enabled. |
| Set project options | `set_project_options` | Imports JSON into Burp project options when configuration editing is enabled. |
| Set user options | `set_user_options` | Imports JSON into Burp user options when configuration editing is enabled. |
| Set task execution engine state | `set_task_execution_engine_state` | Pauses or resumes Burp's task execution engine. |
| Set Proxy Intercept state | `set_proxy_intercept_state` | Enables or disables Burp Proxy Intercept. |
| Get active editor contents | `get_active_editor_contents` | Reads the text in the currently active message editor, if one is available. |
| Set active editor contents | `set_active_editor_contents` | Replaces text in the currently active message editor if it is editable. |

## Utilities

| Name | Tool name to invoke | Description |
| --- | --- | --- |
| URL encode | `url_encode` | URL-encodes a string using Burp's utility API. |
| URL decode | `url_decode` | URL-decodes a string using Burp's utility API. |
| Base64 encode | `base64_encode` | Base64-encodes a string using Burp's utility API. |
| Base64 decode | `base64_decode` | Base64-decodes a string using Burp's utility API. |
| Generate random string | `generate_random_string` | Generates a random string of a chosen length and character set. |

## Burp Professional only

| Name | Tool name to invoke | Description |
| --- | --- | --- |
| Get scanner issues | `get_scanner_issues` | Returns paginated issues identified by Burp Scanner. |
| Generate Collaborator payload | `generate_collaborator_payload` | Generates an out-of-band payload with optional custom data and server-location options. Can link a payload to Burp's Collaborator tab; those tab-linked payloads are polled in Burp rather than with this server's interaction tool. |
| Get Collaborator interactions | `get_collaborator_interactions` | Polls the MCP Collaborator client for DNS, HTTP, or SMTP interactions, optionally filtering by payload ID, payload string, or interaction type. |
| Get Collaborator client | `get_collaborator_client` | Returns the MCP Collaborator server address and client secret key, or restores a client from a supplied secret key. |

## Other extension integration

| Name | Tool name to invoke | Description |
| --- | --- | --- |
| List extension tools | `list_extension_tools` | Probes loaded extensions through known or supplied command headers and reports their advertised tools or commands. |
| Call extension command | `call_extension_command` | Sends a supplied header, command, and optional body through Burp's HTTP API for a loaded extension to handle. |
