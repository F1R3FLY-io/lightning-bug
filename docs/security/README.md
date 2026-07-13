# Security Overview

Lightning Bug is an **embeddable browser code editor**. This section describes its **trust
boundaries** and the **threat model** so that integrators can deploy it safely. The detailed,
categorized enumeration with mitigations is in [threat-model.md](threat-model.md).

## Scope and assumptions

- Lightning Bug runs **entirely in the browser**, in the host page's origin, with the host page's
  privileges. It has **no server component of its own** and **no persistence** — the DataScript
  database is in-memory and is discarded when the page unloads. It stores no credentials and, by
  default, no personally-identifiable information.
- The **host application** is assumed to be trusted with respect to the editor: it embeds `<Editor>`,
  supplies the language configuration, and may read the editor's database via `getDb()`/`query()`.
  Securing the host page itself (its authentication, its Content-Security-Policy, its other scripts)
  is the integrator's responsibility; this document covers the editor's contribution to that
  posture.
- The **language server** is treated as **untrusted input**. It is a separate process, reached over
  a WebSocket, that returns diagnostics, symbols, and log strings which the editor renders.

## Trust boundaries

Three boundaries matter. Data crossing them must be handled defensively.

![Trust-boundary data-flow diagram: within the browser origin the host app, the Editor, and the in-memory DataScript DB run with host privileges; the Editor loads and executes Tree-Sitter WASM and .scm queries in the same-origin sandbox (supply-chain trust); and the Editor exchanges LSP messages with an untrusted language server over an unauthenticated, plaintext ws:// connection.](diagrams/trust-boundaries.svg)

| Boundary | Between | Why it matters |
|----------|---------|----------------|
| **1 — Network** | Editor ↔ language server | LSP is spoken over `ws://`, which is **unauthenticated and plaintext by default**. Any message the server sends (diagnostic text, symbol names, log lines) is untrusted input that the editor renders. |
| **2 — Supply chain** | Editor ↔ Tree-Sitter WASM + `.scm` queries | The grammar WebAssembly and the highlight/indent queries are **code that runs against user input**. They may be loaded from a path, a thunk, or a `data:`/Blob URL. A compromised grammar package is a compromised parser. |
| **3 — Host trust** | Editor ↔ host application | `getDb()` and `query()` hand the host **full read access** to the editor's DataScript database (document contents, diagnostics, symbols). This is intentional API, but it means the host is inside the editor's trust boundary. |

## Assumed adversaries

- A **malicious or compromised language server** (boundary 1) — the primary adversary the design
  actively defends against (bounded reconnect/timeouts; version-matched diagnostics; text rendering,
  not HTML).
- A **compromised grammar/query supply chain** (boundary 2) — mitigated by pinning and verifying the
  packages you load.
- A **network attacker** on a non-localhost `ws://` connection (boundary 1) — mitigated by using
  `wss://` with authentication in production.

Out of scope: attacks against the host page that do not involve the editor, and attacks requiring the
integrator to have already loaded attacker-controlled code as a grammar or extension (that is a
supply-chain failure the integrator must prevent).

## The short version for integrators

1. Use **`wss://` with authentication** for any non-localhost language server.
2. **Pin and verify** the Tree-Sitter grammar package and any `grammarWasm`/`parser` you supply.
3. Set a **Content-Security-Policy** that permits WebAssembly and the `data:`/Blob URLs the editor
   uses, and nothing more.
4. Treat anything you render from `getDb()`/`query()` or from LSP payloads as **untrusted text** in
   your own UI.

Each of these is expanded, with the specific threat it addresses, in
[threat-model.md](threat-model.md).

## Related reading

- [threat-model.md](threat-model.md) — the STRIDE-categorized enumeration and mitigations.
- [architecture/lsp-subsystem.md](../architecture/lsp-subsystem.md) — the LSP transport and the
  resilience limits (backoff caps, timeouts) that bound denial-of-service.
- [architecture/data-model.md](../architecture/data-model.md#the-public-querying-seam) — the
  `query()`/`getDb()` seam.
