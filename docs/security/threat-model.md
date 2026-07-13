# Threat Model

This document enumerates the threats to a Lightning Bug deployment and the mitigations for each,
organized by the **STRIDE** categories (Spoofing, Tampering, Repudiation, Information disclosure,
Denial of service, Elevation of privilege). Read the [security overview](README.md) first for the
trust boundaries these threats cross.

STRIDE is a threat-classification framework introduced at Microsoft; see Shostack (2014) and the
[Microsoft threat-modeling guidance](https://learn.microsoft.com/en-us/azure/security/develop/threat-modeling-tool-threats).
Boundary numbers below refer to the [trust boundaries](README.md#trust-boundaries): **1** network,
**2** supply chain, **3** host trust.

## Defensive properties already in the code

Several mitigations are built in, and are worth stating before the threat list:

- **Bounded reconnect and timeouts** (boundary 1). Auto-reconnect is capped at 3 attempts with
  exponential backoff; a connect races a 30 s init-timeout; requests have a 60 s timeout. These bound
  the resource cost of a flapping or hostile server. See
  [lsp-subsystem.md](../architecture/lsp-subsystem.md#resilience-reconnect-timeouts-and-cleanup).
- **Framed, length-validated parsing** (boundary 1). Inbound LSP messages are parsed against their
  `Content-Length` header and the body length is validated before `JSON.parse`.
- **Version-matched diagnostics** (boundary 1). Diagnostics are applied only when their version
  matches the current document version, so a stale or mismatched push is dropped rather than
  mis-rendered.
- **Text rendering, not HTML** (boundary 1). The editor renders diagnostic messages and symbol names
  as **text**: CodeMirror's linter builds text nodes, and the demo application renders through
  reagent/Hiccup, which escapes strings by default. There is no known HTML/JavaScript sink for
  server-supplied strings in the library.
- **Graceful shutdown suppresses reconnect** (boundary 1); a formally-verified property
  (`GracefulShutdownDoesNotReconnect`, see [formal/models.md](../formal/models.md#lspconnection)).

## Spoofing (boundary 1)

| Threat | Mitigation |
|--------|------------|
| A rogue server impersonates the intended language server on an unauthenticated `ws://` endpoint. | Use **`wss://` with authentication** (e.g. a token in the connection URL or a reverse proxy enforcing auth) for any non-localhost server. Restrict the server to `localhost` in development. The library does not add authentication itself; it is the integrator's responsibility to point `lspUrl` at an authenticated endpoint. |

## Tampering (boundaries 1, 2)

| Threat | Mitigation |
|--------|------------|
| A man-in-the-middle rewrites LSP traffic on plaintext `ws://`. | `wss://` (TLS) for non-localhost. |
| A compromised Tree-Sitter grammar package or `.scm` query is loaded and runs against every document. | **Pin and verify** the `@f1r3fly-io` grammar package (lockfile + integrity hashes) and any `grammarWasm`/`parser`/query you supply via `LanguageConfig`. Treat a grammar as you would any executable dependency. See [language-extensions.md](../guide/language-extensions.md). |
| A tampered `data:`/Blob URL supplies a malicious grammar/query. | Only construct `data:` URLs from build-time-embedded, integrity-checked assets (as the bundled Rholang extension does); never from network-fetched, unverified bytes. |

## Repudiation

| Threat | Mitigation |
|--------|------------|
| The editor keeps no audit log, so actions cannot be attributed after the fact. | This is by design (a client-side editor with no persistence). If your application needs an audit trail, record it in the host — subscribe to the [editor events](../architecture/event-model.md) (`document-save`, `document-rename`, etc.) and log them server-side. |

## Information disclosure (boundaries 1, 3)

| Threat | Mitigation |
|--------|------------|
| Document contents are sent to the language server. | Expected for LSP to function; ensure the server is trusted and the channel is `wss://` for non-localhost. Do not connect a language server to documents whose contents must not leave the browser. |
| `getDb()`/`query()` expose the full editor database to the host page's JavaScript. | Intentional API (boundary 3). Because the host is inside the trust boundary, ensure the host page does not itself leak the database to third-party scripts (a strict CSP and dependency hygiene on the host help). |
| Plaintext `ws://` exposes document contents to network observers. | `wss://`. |

## Denial of service (boundary 1)

| Threat | Mitigation |
|--------|------------|
| A hostile server floods `publishDiagnostics` to exhaust CPU/memory. | Diagnostics are version-matched (stale pushes dropped) and rendered via a memoized transform; still, a determined flood is a risk — integrators embedding untrusted servers should rate-limit at the proxy. |
| A server repeatedly drops the socket to trigger reconnect storms. | Auto-reconnect is capped (3 attempts, exponential backoff) and suppressed on graceful shutdown. |
| Malformed `Content-Length` framing (oversized/short) is used to stall or overrun the parser. | The client validates the declared length against the actual body before parsing. |
| A pathological document defeats incremental parsing. | Tree-Sitter is incremental and the highlighter is viewport-bounded ([syntax-and-editing.md](../architecture/syntax-and-editing.md#viewport-cached-highlighting)); extremely large documents remain the integrator's sizing concern. |

## Elevation of privilege (boundary 2)

| Threat | Mitigation |
|--------|------------|
| Grammar WebAssembly executes in the browser. | WebAssembly runs in the browser's sandbox — it is memory-safe and has no ambient authority (no direct DOM, filesystem, or network access); see the [WebAssembly security model](https://webassembly.org/docs/security/). The residual risk is (a) a bug in the host's WASM runtime, and (b) the grammar's *own* logic (a malicious grammar can still cause a DoS). Mitigate (b) with supply-chain pinning; (a) is the browser vendor's responsibility. |
| Loading WASM requires a permissive Content-Security-Policy. | Scope the CSP tightly: permit `wasm-unsafe-eval` (or the appropriate WebAssembly directive for your target browsers) and the specific `data:`/Blob sources the editor uses under `script-src`/`worker-src`, and nothing broader. See the [MDN CSP guide](https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/CSP). |

## Cross-site scripting (a note)

Cross-site scripting (**XSS**) is the injection of attacker-controlled markup/script into a page (see
[OWASP XSS](https://owasp.org/www-community/attacks/xss/), [CWE-79](https://cwe.mitre.org/data/definitions/79.html)).
The relevant untrusted strings here are LSP-supplied diagnostic messages, symbol names, and log
lines. As noted above, the library renders them as **text**, so there is no known XSS sink in the
editor itself. The residual risk is entirely on the **host side**: if your application takes those
strings (e.g. from the [`diagnostics`/`symbols`/`log` events](../architecture/event-model.md)) and
renders them as raw HTML, you reintroduce the vulnerability. **Render LSP-supplied strings as text**
in your own UI, or sanitize them.

## Secrets in the toolchain (build/CI)

| Threat | Mitigation |
|--------|------------|
| The GitHub Packages token (`NODE_AUTH_TOKEN`, `read:packages` scope) leaks. | Store it as a CI secret, never in the repository; the `.npmrc` reads it from the environment. Scope the token to `read:packages` only. See [development/building-and-testing.md](../development/building-and-testing.md). |

## References

- A. Shostack (2014). *Threat Modeling: Designing for Security.* Wiley. ISBN 978-1-118-80999-0.
- Microsoft. [Threat modeling / STRIDE guidance](https://learn.microsoft.com/en-us/azure/security/develop/threat-modeling-tool-threats).
- WebAssembly. [Security](https://webassembly.org/docs/security/).
- MDN. [Content Security Policy (CSP)](https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/CSP).
- OWASP. [Cross-Site Scripting (XSS)](https://owasp.org/www-community/attacks/xss/);
  [CWE-79](https://cwe.mitre.org/data/definitions/79.html).
- Microsoft. [Language Server Protocol specification](https://microsoft.github.io/language-server-protocol/).

## Related reading

- [README.md](README.md) — the trust boundaries and the integrator checklist.
- [architecture/lsp-subsystem.md](../architecture/lsp-subsystem.md) — the transport and resilience
  limits.
- [guide/lsp-configuration.md](../guide/lsp-configuration.md) — configuring `lspUrl`.
