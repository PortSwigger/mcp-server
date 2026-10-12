# Burp Suite MCP Server Extension

## Overview

Integrate Burp Suite with AI Clients using the Model Context Protocol (MCP).

For more information about the protocol visit: [modelcontextprotocol.io](https://modelcontextprotocol.io/)

Development notes and the codebase map are in [`localdev/KNOWLEDGEBASE.md`](localdev/KNOWLEDGEBASE.md). The `localdev` directory has its own Git history and is excluded from this repository.

For each development phase, increment the patch version in `gradle.properties` by one, then keep `ExtensionVersion.kt` and `BappManifest.bmf` in sync. The built extension JAR includes that version in its filename. Record the change and its verification status in the local knowledge base. Use PortSwigger's [Montoya API](https://github.com/PortSwigger/burp-extensions-montoya-api) for Burp integration.

## Features

- Connect Burp Suite to AI clients through MCP
- Automatic installation for Claude Desktop
- Comes with packaged Stdio MCP proxy server

## Usage

- Install the extension in Burp Suite
- Configure your Burp MCP server in the extension settings
- Configure your MCP client to use the Burp SSE MCP server or stdio proxy
- Interact with Burp through your client!

## Installation

### Prerequisites

Ensure that the following prerequisites are met before building and installing the extension:

1. **Java**: Java must be installed and available in your system's PATH. You can verify this by running `java --version` in your terminal.
2. **jar Command**: The `jar` command must be executable and available in your system's PATH. You can verify this by running `jar --version` in your terminal. This is required for building and installing the extension.

### Building the Extension

1. **Clone the Repository**: Obtain the source code for the MCP Server Extension.
   ```
   git clone https://github.com/PortSwigger/mcp-server.git
   ```

2. **Navigate to the Project Directory**: Move into the project's root directory.
   ```
   cd mcp-server
   ```

3. **Build the JAR File**: Use Gradle to build the extension.
   ```
   ./gradlew embedProxyJar
   ```

   This command compiles the source code and packages it into `build/libs/burp-mcp-all-v1.3.3.jar`.

### Loading the Extension into Burp Suite

1. **Open Burp Suite**: Launch your Burp Suite application.
2. **Access the Extensions Tab**: Navigate to the `Extensions` tab.
3. **Add the Extension**:
    - Click on `Add`.
    - Set `Extension Type` to `Java`.
    - Click `Select file ...` and choose the JAR file built in the previous step.
    - Click `Next` to load the extension.

Upon successful loading, the MCP Server Extension will be active within Burp Suite.

## Configuration

### Configuring the Extension
Configuration for the extension is done through the Burp Suite UI in the `MCP v1.3.3` tab.
- **Toggle the MCP Server**: The `Enabled` checkbox controls whether the MCP server is active.
- **Enable config editing**: The `Enable tools that can edit your config` checkbox allows the MCP server to expose tools which can edit Burp configuration files.
- **Advanced options**: You can configure the port and host for the MCP server. By default, it listens on `http://127.0.0.1:9876`.

### Claude Desktop Client

To fully utilize the MCP Server Extension with Claude, you need to configure your Claude client settings appropriately.
The extension has an installer which will automatically configure the client settings for you.

1. Currently, Claude Desktop only support STDIO MCP Servers
   for the service it needs.
   This approach isn't ideal for desktop apps like Burp, so instead, Claude will start a proxy server that points to the
   Burp instance,  
   which hosts a web server at a known port (`localhost:9876`).

2. **Configure Claude to use the Burp MCP server**  
   You can do this in one of two ways:

    - **Option 1: Run the installer from the extension**
      This will add the Burp MCP server to the Claude Desktop config.

    - **Option 2: Manually edit the config file**  
      Open the file located at `~/Library/Application Support/Claude/claude_desktop_config.json`,
      and replace or update it with the following:
      ```json
      {
        "mcpServers": {
          "burp": {
            "command": "<path to Java executable packaged with Burp>",
            "args": [
                "-jar",
                "/path/to/mcp/proxy/jar/mcp-proxy-all.jar",
                "--sse-url",
                "<your Burp MCP server URL configured in the extension>"
            ]
          }
        }
      }
      ```

3. **Restart Claude Desktop** - assuming Burp is running with the extension loaded.

## Manual installations
If you want to install the MCP server manually you can either use the extension's SSE server directly or the packaged
Stdio proxy server.

### SSE MCP Server
To use the SSE server directly, provide the configured server URL to your MCP client:
```
http://127.0.0.1:9876
```

### Stdio MCP Proxy Server
The source code for the proxy server can be found here: [MCP Proxy Server](https://github.com/PortSwigger/mcp-proxy)

In order to support MCP Clients which only support Stdio MCP Servers, the extension comes packaged with a proxy server for
passing requests to the SSE MCP server extension.

If you want to use the Stdio proxy server you can use the extension's installer option to extract the proxy server jar.
Once you have the jar you can add the following command and args to your client configuration:
```
/path/to/packaged/burp/java -jar /path/to/proxy/jar/mcp-proxy-all.jar --sse-url http://127.0.0.1:9876
```

If you modify the proxy source, rebuild and copy it into this project before packaging the extension:
```bash
# From mcp-proxy
./gradlew shadowJar
cp build/libs/mcp-proxy-all.jar /path/to/mcp-server/libs/mcp-proxy-all.jar

# From mcp-server
./gradlew embedProxyJar
```

### Creating / modifying tools

Tools are defined in `src/main/kotlin/net/portswigger/mcp/tools/Tools.kt`. To define new tools, create a new serializable
data class with the required parameters which will come from the LLM.

The full catalog of names exposed to MCP clients and descriptions is in [`tools.md`](tools.md).

The tool name is auto-derived from its parameters data class. A description is also needed for the LLM. You can return
a string or a `List<ContentBlock>` to provide data back to the LLM.

Extend the Paginated interface to add auto-pagination support.

## New enhancements

These commits turn the extension from a request-and-history bridge into a suite tab that can drive Repeater, Collaborator, proxy notes, and other loaded extensions. The suite tab is labeled `MCP v1.3.3`.

### Suite tab

The left side of the tab is an activity log. Each MCP tool call is recorded as `HH:mm:ss - Pass - toolName` or `HH:mm:ss - Fail - toolName`, newest first. A failure also shows the message that was returned to the model. Repeater Send and Repeater notes update the target tab and then leave the Burp tool and tab you already had open, so manual testing can continue.

### Introduced tools

- `send_repeater_request` clicks Send on a named Repeater tab and returns the response shown in that tab. Leave `issueFrom` unset so the response fills the Repeater pane. `issueFrom` `http` is only a fallback when Send cannot be clicked, and that path does not fill the pane.
- `set_repeater_notes` writes the Notes editor of a Repeater tab opened by MCP. `append` adds to the existing note.
- `set_repeater_response_end_marker` stores the word or characters that mark the end of that tab's server response. An empty marker clears it. `truncateAtEndMarker` drops everything after the marker.
- `get_repeater_tab` returns the request, notes, end marker, connection id, and last response for one MCP Repeater tab.
- `list_repeater_tabs` lists the Repeater tabs opened by MCP in this Burp session.
- `send_proxy_history_to_repeater` copies one proxy history item into a Repeater tab. `useFinalRequest` selects the request Burp sent after match-and-replace. Notes are copied unless `notes` is set or `copyNotes` is false. `sendNow` issues that tab in the same call.
- `get_proxy_http_history_summary` lists history as one line per item: id, time, method, URL, status, notes, and whether it was edited. Use the id with the Repeater and notes tools. An optional regex limits the list.
- `get_proxy_http_history_item` returns one history item, including the request, response, and notes.
- `set_proxy_history_notes` writes the proxy history Notes field, the Comment column, for one id or for items matching a regex. `highlightColor` accepts a Burp color name, and `append` keeps the existing note.
- `get_collaborator_client` returns the Collaborator server address and secret key for the MCP client. The secret is stored in the project file. Pass `secretKey` to restore a different client. Burp Professional only.
- `list_extension_tools` probes loaded extensions. ATOR answers `X-ATOR-Command: status`. Other extensions can answer `X-Burp-Mcp-Discover: tools`.
- `call_extension_command` sends a command header through Burp's HTTP API. ATOR commands are `status`, `refresh`, `export`, and `import`. `import` reads ATOR export JSON from `body`.

> Note: Only repeater tab functionalities are fully tested and deployed; other tools are still in beta-stage expect instability will check and provide fixes while facing. As each workflow differs

### Development record

Version `1.3.1` adds a versioned extension JAR and aligns the Gradle, Burp tab, BApp, and CI release versions. The proxy embedding task now declares its actual JAR input, and the MCP server integration tests wait for a startup signal. `./gradlew test embedProxyJar` passed all 124 tests; manual verification in Burp is pending.

Version `1.3.2` adds [`tools.md`](tools.md), covering all 39 registered MCP tools and their exact LLM invocation names. The catalog matches source registration, and `./gradlew test embedProxyJar` passed all 124 tests. Manual verification in Burp is pending.

Version `1.3.3` adds `send_recorded_request_to_repeater`. Give it `source: "proxy"` and a `requestId` from `get_proxy_http_history_summary`, or `source: "captured"` and a capture ID from `list_captured_http_requests`. It opens the selected request in Repeater, clicks Send there, and returns the fresh response. The captured list includes HTTP requests observed after the extension loads, keeps the latest 500, and uses Montoya message IDs; these IDs are **not** the visible `#` values in Burp Logger. If Repeater Send is unavailable, the tool reports that the request was not sent. Reading captured requests and sending traffic use the existing Burp approval controls, with a separate captured-request access setting. `./gradlew test embedProxyJar` passed all 127 tests, and the JAR contains the extension class and embedded proxy. Burp UI verification is pending.

### Updated tools

- `create_repeater_tab` and `create_repeater_tab_http2` open a tab and wait. The request is not sent until `send_repeater_request`. Optional `notes` are written when the Notes field can be found. The tool and tab you already had open stay selected.
- `send_http1_request` and `send_http2_request` accept `responseEndMarker` and `truncateAtEndMarker`, the same end-of-response marker used by Repeater.
- `generate_collaborator_payload` keeps one Collaborator client for later polls. `customData` is stored with the payload. `withoutServerLocation` omits the server hostname. `includeSecretKey` returns the client secret. `linkToCollaboratorTab` uses Burp's default generator so the interaction shows in the Collaborator tab. Those tab payloads are not returned by `get_collaborator_interactions`. Burp Professional only.
- `get_collaborator_interactions` polls that same MCP client. Filter with `payloadId`, the payload string, or `interactionType` (`DNS`, `HTTP`, or `SMTP`). It does not see payloads created with `linkToCollaboratorTab`.
