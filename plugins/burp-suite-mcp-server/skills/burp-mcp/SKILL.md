---
name: burp-mcp
description: Use when working with Burp Suite through the `burp` MCP server - reading Proxy HTTP or WebSocket history, sending HTTP/1.1 or HTTP/2 requests through Burp, creating Repeater or Intruder tabs, generating or polling Burp Collaborator payloads, reading scanner issues, encoding/decoding, or reading and changing Burp configuration. Also use when a `burp` tool call hangs, is denied, or the server will not connect.
---

# Burp Suite MCP server

The Burp MCP server is a Burp extension. Its tools act on the user's running Burp: real requests, real
Proxy history, real Repeater tabs. Treat every call as acting on their live testing session.

## Connecting

Burp must be running with the MCP Server extension loaded and **Enabled** ticked in its `MCP` tab.
The server listens on `http://127.0.0.1:9876` by default (host and port are configurable in the `MCP` tab).

Codex connects over Streamable HTTP at `/mcp`. The Burp Suite MCP Server plugin configures this for you. Without
the plugin, or to use a different host or port, set it in `~/.codex/config.toml`:

```toml
[mcp_servers.burp]
url = "http://127.0.0.1:9876/mcp"
```

If the `burp` tools are missing or the handshake fails:

| Symptom | Cause |
|---|---|
| Connection refused | Burp is not running, the extension is not loaded, or **Enabled** is unticked. |
| `HTTP 400: sessionId query parameter is not provided` | The URL is missing `/mcp`, so it hit the legacy SSE endpoint at `/`. |
| `403 Forbidden` | The request came from a non-localhost Host/Origin, or looked like a browser. The server only accepts local, non-browser clients. |
| Fewer tools than expected | Collaborator and scanner tools only exist in Burp Suite Professional. |

Ask the user to fix Burp-side problems; you cannot start Burp or load the extension through MCP.

## Approvals: why a call hangs

By default Burp asks the user before the extension does anything sensitive. The tool call blocks until
they answer the dialog in Burp.

- **Sending requests** (`send_http1_request`, `send_http2_request`): dialog offers Allow Once,
  Always Allow Host, Always Allow Host:Port, Deny. Hosts in the `MCP` tab's auto-approve list skip it
  (`*.example.com` wildcards work).
- **Reading data** (Proxy HTTP history, WebSocket history, Organizer): dialog offers Allow Once,
  Always Allow, Deny.

If a call is slow, tell the user Burp is probably waiting for them to approve it. A denial comes back as a
normal result such as `Send HTTP request denied by Burp Suite` or `HTTP history access denied by Burp Suite`.
Do not retry a denied call; ask the user.

Codex has its own approval layer too. Interactive sessions prompt for each `burp` tool call. Under
`codex exec` the calls are rejected (`MCP tool call requires approval`) unless the server entry sets
`default_tools_approval_mode = "approve"`. When overriding the plugin's server in `config.toml`, give the full
entry including `url`; an entry with only the approval mode fails with `invalid transport`.

## Tools by task

### Reading traffic

| Tool | Use |
|---|---|
| `get_proxy_http_history_regex` | First choice. Filters Proxy history by a Java regex over the whole request and response. |
| `get_proxy_http_history` | Pages through all Proxy history in order. |
| `get_proxy_websocket_history[_regex]` | Same for WebSocket messages. |
| `get_organizer_items[_regex]` | Items the user saved to Organizer. Often the ones they care about most. |
| `get_scanner_issues` | Issues Burp Scanner has found (Professional only). |

All of these take `count` and `offset`. Start small (`count` 5-20) and page with `offset`;
`Reached end of items` means there is nothing more. Each item is JSON capped at about 5,000 characters,
so long bodies are cut and marked `... (truncated)`. If you need the full response, resend the request.

Prefer a regex on a host, path or parameter (`regex: "api\\.example\\.com/v2/users"`) over paging through
everything.

### Sending requests

| Tool | Use |
|---|---|
| `send_http1_request` | `content` is the raw request: request line, headers, blank line, body. |
| `send_http2_request` | `pseudoHeaders` (`method`, `path`, `authority`, `scheme`; leading `:` optional), `headers` map, `requestBody`. Never put headers in the body. |

Both also take `targetHostname`, `targetPort` and `usesHttps`; these decide where the request goes, not the
`Host` header. Line endings in `content` are normalised to CRLF, and `\r\n` written as a literal escape is
accepted too. Keep `Host` and `Content-Length` consistent with what you send.

The result is the raw response, or `<no response>` if nothing came back.

### Handing work to the user

| Tool | Use |
|---|---|
| `create_repeater_tab_http2` | Default for modern targets. Same fields as `send_http2_request` plus optional `tabName`. |
| `create_repeater_tab` | HTTP/1.1 Repeater tab from raw `content`. |
| `send_to_intruder` | Raw HTTP/1.1 request into Intruder for the user to set positions. |
| `get_active_editor_contents` / `set_active_editor_contents` | Read or replace the text in whichever Burp message editor the user has focused. Returns `<No active editor>` if focus is elsewhere. |

These do not send anything and need no approval. Use them when the user wants to carry on by hand, and give
the tab a name that says what it is for.

### Out-of-band testing (Professional only)

1. `generate_collaborator_payload` (optional `customData`) returns a payload host and a `Payload ID`.
2. Put the payload where the target might resolve or fetch it.
3. `get_collaborator_interactions` with that `payloadId` lists DNS, HTTP and SMTP hits. Poll again after a
   short wait. `No interactions detected` is not proof of absence.

### Utilities

`url_encode`, `url_decode`, `base64_encode`, `base64_decode`, `generate_random_string` (`length`,
`characterSet`). Use them when you need Burp's exact encoding; otherwise encoding locally is fine.

### Burp state and configuration

| Tool | Notes |
|---|---|
| `set_proxy_intercept_state` | `intercepting: true/false`. Interception holds the user's browser traffic, so only change it when asked. |
| `set_task_execution_engine_state` | `running: true/false`. Pauses or resumes scans and other tasks. |
| `output_project_options` / `output_user_options` | Export config as JSON. Credentials are redacted by default. |
| `set_project_options` / `set_user_options` | Merge a partial JSON document into config. |

The `set_*_options` tools only work when the user has ticked **Enable tools that can edit your config** in
the `MCP` tab; otherwise they return a message saying so. Export first to learn the schema, then send only
the keys you are changing.

Their descriptions have the top-level key swapped. Use the key that matches the tool, not the description:
`set_project_options` takes `{"project_options": {...}}` and `set_user_options` takes `{"user_options": {...}}`.

## Ground rules

- Only send requests to targets the user has said are in scope. Burp's approval dialog is a backstop, not
  permission.
- Do not change intercept, task engine or configuration state unless the user asked for it.
- Prefer reading what is already in history over generating new traffic.
- When you find something, leave the user a Repeater tab with the request so they can reproduce it.
