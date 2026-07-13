# The LSP Subsystem

The **Language Server Protocol (LSP)** is a JSON-RPC protocol between an editor and a language
server that supplies language intelligence — here, **diagnostics** (errors/warnings) and **document
symbols**. Lightning Bug speaks LSP over a **WebSocket**. The subsystem is optional: with no
`lspUrl`, the editor runs without it and degrades to syntax-only.

Three namespaces divide the work:

| Namespace | Role |
|-----------|------|
| `lib.lsp.fsm` (`src/lib/lsp/fsm.cljs`) | A pure, dependency-free connection **finite-state machine** (states, transitions, derived flags). |
| `lib.lsp.client` (`src/lib/lsp/client.cljs`) | The wire protocol: JSON-RPC framing, the WebSocket, send/receive, and the notification/response handlers. |
| `lib.lsp.connection-manager` (`src/lib/lsp/connection_manager.cljs`) | The `ConnectionManager` record — the live `ILspClient` — adding timeouts, reconnect-with-backoff, and stale-request cleanup. |

## The connection finite-state machine

The **single source of truth** for a language's connection is the keyword `:state` at
`[:lsp lang :state]` in the Workspace's LSP atom. `lib.lsp.fsm` is dependency-free so both the client
and the connection manager can require it without a cycle.

![LSP finite-state machine: seven states (disconnected, connecting, connected, initializing, initialized, disconnecting, error) and seventeen transitions among them, starting at disconnected; graceful shutdown suppresses auto-reconnect; the boolean flags are projections of the state.](diagrams/lsp-fsm.svg)

- **States (7):** `:disconnected`, `:connecting`, `:connected`, `:initializing`, `:initialized`,
  `:disconnecting`, `:error`.
- **Transitions (17):** the edges shown above. Every state change goes through a validating
  `transition!` (`lib.lsp.client`) that checks the edge against the FSM and refreshes both `:state`
  and the derived flags atomically.
- **Derived flags.** The legacy booleans `:connected?`, `:initialized?`, `:connecting?`,
  `:reachable?` are **projections** of `:state`, kept so older readers and tests still work. For
  example `state->connected?` holds for `:connected`, `:initializing`, and `:initialized`. These
  flags are never written directly.

This exact state set and transition relation is the **anchor of the source↔formal alignment gate**:
`fsm.cljs`, the TLA+ model `LspConnection.tla`, and the Rocq module `Async/Fsm.v` are all required to
declare the identical 7 states and 17 transitions, and the CI gate fails if they diverge. See
[verification-gate.md](../formal/verification-gate.md).

## The wire protocol

`lib.lsp.client` frames JSON-RPC over the WebSocket:

- **Outbound** (`send`). A request carrying a `:response-type` is assigned a serial id and tracked in
  `[:lsp lang :pending id]` as **typed** pending metadata (`{:type … :uri …}`); the payload is
  `JSON.stringify`-d and prefixed with a `Content-Length: N\r\n\r\n` header. A guard writes only when
  connected (or when a forced send is in effect), else it warns once.
- **Inbound** (`handle-message`). The `ArrayBuffer` is decoded, the `Content-Length` header parsed,
  the body sliced and length-validated, `JSON.parse`-d, classified as request/notification/response,
  spec-validated, surfaced as a raw `lsp-message` event, then dispatched. A response is matched to
  its pending id and routed by the recorded type (initialize / documentSymbol / shutdown); errors
  emit `lsp-error`.

Typed pending requests are what let the model prove that responses are only accepted for
outstanding, correctly-typed requests, and that a `shutdown` request is the only pending item during
shutdown — see the [LspConnection model](../formal/models.md#lspconnection).

## The connection lifecycle

![LSP sequence: a pane ensures the document is opened, which connects if needed; the socket opens (connected), the client sends initialize (initializing), receives the initialize response (initialized) and sends initialized, then sends didOpen; user edits flush as one coalesced, monotonically-versioned didChange; the server publishes diagnostics, which are written to DataScript and fanned out to every pane, each applying them only if the URI matches its active file; the client then requests document symbols.](diagrams/lsp-sequence.svg)

Walking the WebSocket callbacks:

1. **`connect`** creates the socket (`binaryType = arraybuffer`), stores it in the Workspace
   resources, and transitions `→ :connecting`.
2. **`onopen`** transitions `→ :connected`, emits `connect`, then immediately `→ :initializing`
   (before sending, so a synchronous mock response is handled correctly) and sends `initialize`.
3. **initialize response** detects the server's `TextDocumentSyncKind` (2 ⇒ incremental), sends
   `initialized`, transitions `→ :initialized`, resolves the init promise, and emits
   `lsp-initialized`.
4. **`request-shutdown`** sets `:shutting-down?`, drops pending requests, transitions
   `→ :disconnecting`, and force-sends `shutdown`.
5. **shutdown response** closes all opened documents, sends `exit`, transitions `→ :disconnected`,
   and closes the socket.
6. **`onclose`** rejects a pending init promise, removes the language from the LSP state, and emits
   `disconnect`; **auto-reconnect** fires only if the socket *was* connected and it was **not** a
   graceful shutdown.
7. **`onerror`** transitions `→ :error` and emits `lsp-error`.

## Document-lifecycle notifications

| Notification | When | Detail |
|--------------|------|--------|
| `didOpen` | first time a document is shown | Orchestrated by `ensure-lsp-document-opened` (`lib.editor.runtime`): connects if needed (awaiting an in-flight connect), sends `didOpen` **once per URI** (guarded by `document-opened?`), marks the document opened in the database and the pane's atom, emits `document-open`. |
| `didChange` (incremental) | on edits, debounced | User edits accumulate per URI in the **Workspace** LSP atom's `:pending-lsp-changes`; a debounced flush (150 ms, max-wait 500 ms) bumps the version and calls `notify-did-change-incremental!` when the server advertised incremental sync (else a full `notify-did-change!`). Because the debounce key is per-`(Workspace, uri)` over a shared accumulator, edits from any pane on one file coalesce into a **single** `didChange` with one monotonic version. |
| `didSave` | `saveDocument()` | Sends `didSave` if connected and dirty; clears dirty. |
| `didClose` | `closeDocument()` | Sends `didClose` only when the document is opened **and not still shared** with a peer pane (the stream ref-count guard), then deletes it unless shared. |
| `didRename` | `renameDocument()` | Sends `workspace/didRenameFiles` when the language is unchanged. |

## How diagnostics and symbols return

- **Diagnostics.** A server `publishDiagnostics` is flattened, checked against the active URI/version
  (via the coalesced `active-uri-version` query — one query, not two), written with
  `replace-diagnostics-by-uri!` (retract-then-create in one transaction), and pushed onto the
  Workspace's `:lsp-events`. Each pane forwards it to its own event stream and applies the
  diagnostics to its CodeMirror view **only if the URI matches the file that pane shows**; it then
  requests symbols for that URI. (See [syntax-and-editing.md](syntax-and-editing.md) for the
  CodeMirror integration.)
- **Symbols.** A `documentSymbol` response is flattened (hierarchical → flat with negative ids and
  `:symbol/parent` links), written with `replace-symbols!`, and emitted as `symbols`.

## Resilience: reconnect, timeouts, and cleanup

`ConnectionManager` adds the operational concerns:

- **Reconnect with backoff.** `reconnect-with-backoff!` retries `connect!` up to a maximum number of
  attempts (3) with exponential backoff (base 1000 ms, factor 2). It is wired as the reconnect
  function and is **suppressed on graceful shutdown** (via `:shutting-down?`).
- **Timeouts.** A connect races an init-timeout (30 s); the default request timeout is 60 s.
- **Self-terminating stale-request cleanup.** On connect, a per-Workspace cleanup task starts (its
  interval id stored under `[:lsp lang :cleanup-interval-id]`) and clears itself once the connection
  reaches `:disconnected`/`:error` — avoiding a client→manager dependency cycle.

The liveness of these operations — that a connecting socket eventually resolves, that shutdown
eventually disconnects, and that a queued reconnect is eventually consumed — is specified as temporal
properties in the [LspConnection liveness model](../formal/models.md#lspconnection).

## Related reading

- [event-model.md](event-model.md) — the `connect`/`disconnect`/`lsp-initialized`/`diagnostics`/
  `symbols`/`lsp-error`/`lsp-message` events.
- [multi-editor-workspaces.md](multi-editor-workspaces.md) — one connection per language per
  Workspace, and the per-pane URI-matched diagnostics fan-out.
- [Formal: LspConnection](../formal/models.md#lspconnection) and the
  [verification gate](../formal/verification-gate.md).
- [LSP Configuration](../guide/lsp-configuration.md) — running a server and setting `lspUrl`.
- [Security: threat model](../security/threat-model.md) — treating the language server as untrusted
  input.
