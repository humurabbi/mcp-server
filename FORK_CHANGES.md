# Fork Changes

Local changes layered on top of [PortSwigger/mcp-server](https://github.com/PortSwigger/mcp-server). These commits live on `main` of this fork and are not (yet) in upstream.

Read newest first. Each entry references the commit SHA on `main` of this fork.

## Skip Site Map add when sendRequest produced no response — `f0da333`

`api.http().sendRequest()` returns an `HttpRequestResponse` wrapper even when the connection fails (e.g. HTTP/2 against an HTTP/1.1-only origin). The wrapper has `request()` populated but `response() == null` and `hasResponse() == false`. The prior `response?.let { api.siteMap().add(it) }` only null-checked the outer wrapper, so failed attempts polluted Site Map with request-only rows.

Both `send_http1_request` and `send_http2_request` now gate on `hasResponse()`:

```kotlin
response?.takeIf { it.hasResponse() }?.let { api.siteMap().add(it) }
```

Failed attempts still surface in Burp's Logger as "communication error" — that's Burp's own behavior and remains untouched.

## Route Collaborator payloads through the default generator — `49334e4`

`generate_collaborator_payload` previously called `api.collaborator().createClient()`, which creates an extension-private Collaborator client. Payloads from that client never reach Burp's Collaborator tab, so the user had no visibility into out-of-band interactions triggered by the MCP.

Switched to `api.collaborator().defaultPayloadGenerator()`. Payloads are now linked to the UI Collaborator tab and any DNS / HTTP / SMTP interactions appear there as they arrive.

Trade-off: the default generator only exposes `generatePayload(options...)` and provides no way to retrieve interactions. Consequences:

- `get_collaborator_interactions` and its `GetCollaboratorInteractions` data class **removed**.
- `customData` parameter **dropped** from `generate_collaborator_payload` — the default generator API does not accept it.
- The unused `InteractionFilter` import was removed.

Tool description now points clients at the Burp Collaborator tab for interactions.

## Surface MCP HTTP requests in Site Map and add HTTP/2 repeater tab — `537f8c2`

Three related improvements bundled together:

1. **Site Map visibility for sent requests**. `send_http1_request` and `send_http2_request` route through `api.http().sendRequest()`, which bypasses Burp's proxy. Without intervention, MCP-issued requests are invisible (Proxy History is read-only to extensions, and the existing `logToOutput` line only recorded host:port). Each tool now calls `api.siteMap().add(response)` after a successful send, so the request/response pair appears under Target > Site map. *(Later refined in `f0da333` to gate on `hasResponse()`.)*

2. **Newline normalization in `create_repeater_tab`**. Callers (LLMs in particular) often pass content with bare `\n` line terminators. The original code forwarded that straight to `HttpRequest.httpRequest`, producing a garbled request in the Repeater tab. The tool now applies the same `\n` → `\r\n` normalization that `send_http1_request` already uses.

3. **New `create_repeater_tab_http2` tool**. The existing `create_repeater_tab` only constructs HTTP/1.1 requests, which on HTTP/2 servers renders awkwardly in Repeater (the Inspector view has to be flipped from HTTP/2 back to HTTP/1.1 to read the request cleanly). The new tool takes `pseudoHeaders` + `headers` + `requestBody` (same schema as `send_http2_request`) and dispatches via `HttpRequest.http2Request`. The shared `buildHttp2HeaderList` helper was extracted so `send_http2_request` and `create_repeater_tab_http2` keep identical pseudo-header ordering and lowercase normalization. The `create_repeater_tab` description was updated to point clients at the HTTP/2 variant for modern targets.

## Related upstream PRs

Two PRs were opened against `PortSwigger/mcp-server` early in this work and left open:

- **#90** — Surface MCP HTTP requests in Site Map and normalize Repeater newlines. Force-pushed once to include the `hasResponse()` gate from `f0da333`.
- **#91** — Add `create_repeater_tab_http2` for HTTP/2 targets.

The Collaborator change (`49334e4`) was not opened as a PR — it removes a tool (`get_collaborator_interactions`) and a parameter (`customData`) that were added in upstream PR #52, which is the kind of breaking change PortSwigger maintainers typically push back on.

Default workflow going forward is **local-only**: new changes commit to local `main`, then `git push fork main` to mirror to `humurabbi/mcp-server`. PR branches stay where they are, no force-push updates unless explicitly requested.

## Branch layout

- `main` — primary working branch, all local changes integrated
- `fix/sitemap-visibility-and-newline-fix` — PR #90 branch (single commit; force-pushed once to the fork)
- `feat/http2-repeater-tab` — PR #91 branch
- `wip/collaborator-default-generator` — preserved snapshot of the Collaborator commit's lineage before main was fast-forwarded

`origin` → `PortSwigger/mcp-server`, `fork` → `humurabbi/mcp-server`.
