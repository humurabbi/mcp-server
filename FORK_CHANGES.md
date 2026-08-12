# Fork Changes

Local changes layered on top of [PortSwigger/mcp-server](https://github.com/PortSwigger/mcp-server). These commits live on `main` of this fork and are not (yet) in upstream.

Read newest first. Each entry references the commit SHA on `main` of this fork.

## Merge upstream v1.3.0 — `e16cac8`

Merged `origin/main` at `642e6fa` (upstream tag `v1.3.0` and everything after it) into fork `main`. Upstream features gained:

- MCP Kotlin SDK upgraded 0.7.4 → 0.15.0, plus the `mcpUnitTool` helper for tools that return no payload (PR #97).
- Organizer tools: `get_organizer_items` and `get_organizer_items_regex`, including Organizer id/status fields (PRs #87, #94).
- Broadened credential filter for `output_project_options` / `output_user_options`, failing closed on malformed JSON, with a UI checkbox to filter password fields (PRs #25, #95).
- `normalizeHttpContent`, which normalizes only the request prelude and leaves the body byte-exact, and also handles the literal four-character `\r\n` escape sequence that MCP clients emit in JSON parameters (PRs #58, #93).
- JSON-safe truncation of history items, so a truncated response is still parseable (PR #115).
- Windows Store Claude Desktop config path detection (PR #96).
- CI action bumps and `minplatformversion` / BappManifest updates.

Upstream also merged this fork's `create_repeater_tab_http2` work as PR #91, so that tool and the `buildHttp2HeaderList` helper are now upstream code rather than fork deltas.

Conflicts resolved as follows, keeping fork behavior intact:

- Dropped the fork's copies of `buildHttp2HeaderList` and `create_repeater_tab_http2` in favor of upstream's, which are identical apart from using `mcpUnitTool`.
- `create_repeater_tab` now calls upstream's `normalizeHttpContent` instead of the fork's inline `\n` → `\r\n` replacement. `normalizeHttpContent` is a superset of that behavior.
- Kept `GenerateCollaboratorPayload` as a no-arg class and kept `GetCollaboratorInteractions` deleted, preserving the default-payload-generator switch described below.
- The `hasResponse()`-gated Site Map writes merged cleanly and remain fork-only.

Remaining fork-only deltas after the merge: the Site Map writes in `send_http1_request` / `send_http2_request`, and the Collaborator default-generator switch.

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

2. **Newline normalization in `create_repeater_tab`**. Callers (LLMs in particular) often pass content with bare `\n` line terminators. The original code forwarded that straight to `HttpRequest.httpRequest`, producing a garbled request in the Repeater tab. The tool now applies the same `\n` → `\r\n` normalization that `send_http1_request` already uses. *(Superseded in `e16cac8` by upstream's `normalizeHttpContent`.)*

3. **New `create_repeater_tab_http2` tool**. *(Landed upstream as PR #91; no longer a fork delta as of `e16cac8`.)* The existing `create_repeater_tab` only constructs HTTP/1.1 requests, which on HTTP/2 servers renders awkwardly in Repeater (the Inspector view has to be flipped from HTTP/2 back to HTTP/1.1 to read the request cleanly). The new tool takes `pseudoHeaders` + `headers` + `requestBody` (same schema as `send_http2_request`) and dispatches via `HttpRequest.http2Request`. The shared `buildHttp2HeaderList` helper was extracted so `send_http2_request` and `create_repeater_tab_http2` keep identical pseudo-header ordering and lowercase normalization. The `create_repeater_tab` description was updated to point clients at the HTTP/2 variant for modern targets.

## Branch layout

- `main` — primary working branch, all local changes integrated
- `merge/upstream-v1.3.0` — branch the v1.3.0 merge was performed on before landing on `main`
- `fix/sitemap-visibility-and-newline-fix` — PR #90 branch (single commit; force-pushed once to the fork)
- `feat/http2-repeater-tab` — PR #91 branch (merged upstream)
- `wip/collaborator-default-generator` — preserved snapshot of the Collaborator commit's lineage before main was fast-forwarded

`origin` → `PortSwigger/mcp-server`, `fork` → `humurabbi/mcp-server`.
