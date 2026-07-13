# Hexagonal Architecture — Ports, Adapters, and Dependency Injection

This document details the **ports-and-adapters** structure of Lightning Bug: the domain protocols
(*ports*), their concrete implementations (*adapters*), and the `app.system` dependency-injection
(DI) container that wires them. Read the [architecture overview](README.md) first for how this fits
into the whole system, including why the design is *partially* hexagonal.

## The pattern, briefly

The **hexagonal architecture** (also called *ports and adapters*, Alistair Cockburn, 2005) isolates
application logic from external concerns by defining **ports** — abstract interfaces describing what
the application needs — and **adapters** — concrete implementations that connect a port to a real
technology (a database, a network protocol, a UI framework). Callers depend on the port, not the
adapter, so the technology can be swapped or mocked without touching the caller.

- A **driving** (primary) adapter *uses* the application — here, the re-frame coeffects/effects of
  the demo app.
- A **driven** (secondary) adapter is *used by* the application — here, the DataScript repositories,
  the LSP connection manager, the lifecycle manager, and the debounce coordinator.

![Ports-and-adapters diagram: on the left the driving side (app.cofx reads, app.fx writes, app.system as DI container); in the centre the seven domain ports; on the right the driven adapters implementing them; behind the adapters the DataScript database and the LSP WebSocket client. ILspClient is annotated as the one port the library editor also consumes directly.](diagrams/hexagon.svg)

## Ports — `domain.protocols`

`domain.protocols` (`src/domain/protocols.cljs`) defines seven protocols and contains **no**
implementations. Every retained port has at least one production implementer — that is the invariant
the design maintains.

| Port | Purpose | Methods | Production implementer |
|------|---------|---------|------------------------|
| **`IDocumentRepository`** | The open-document store. | `get-document`, `get-document-text`, `get-document-language`, `get-document-version`, `get-active-uri`, `get-active-document`, `get-document-summary`, `list-documents`, `document-opened?`, `list-opened-documents-by-language`, `create-document!`, `update-document-text!`, `update-document-language!`, `increment-version!`, `mark-document-opened!`, `mark-document-closed!`, `set-active-document!`, `delete-document!`, `rename-document!` | `DataScriptDocumentRepository` |
| **`IDiagnosticsRepository`** | LSP diagnostics per document. | `get-diagnostics`, `get-diagnostics-by-uri`, `replace-diagnostics!` | `DataScriptDiagnosticsRepository` |
| **`ISymbolsRepository`** | Document symbols (flattened hierarchy). | `get-symbols`, `get-symbols-by-uri`, `replace-symbols!` | `DataScriptSymbolsRepository` |
| **`ILogRepository`** | The log entries surfaced in the UI. | `get-logs`, `add-log!` | `DataScriptLogRepository` |
| **`ILspClient`** | A language-server connection. | `connect!`, `disconnect!`, `connected?`, `initialized?`, `connect-supplier`, `notify-did-open!`, `notify-did-change!`, `notify-did-change-incremental!`, `notify-did-close!`, `notify-did-save!`, `notify-did-rename!`, `request-symbols!`, `request-shutdown!`, `shutdown-all!` | `ConnectionManager` |
| **`IResourceLifecycle`** | Ordered start/stop of editor resources. | `start!`, `stop!`, `started?`, `restart!` | `LifecycleManager` |
| **`IDebounceCoordinator`** | Keyed debouncing of callbacks. | `debounced-call`, `cancel`, `cancel-all` | `DebounceCoordinator` |

`get-document-summary` deserves a note: it returns a **coalesced** `{:uri :text :language :version}`
map from a *single* DataScript query. It exists because it is a hot read (used on the keystroke and
diagnostics paths), and issuing four single-attribute reads instead would be measurably slower — see
[data-model.md](data-model.md#coalesced-queries) and the
[performance model](../benchmarks/performance-model.md).

### Ports that were removed

Three protocols were removed because no implementation fit the actual runtime, and forcing one would
have added indirection without testability. They are recorded here so they are not re-introduced:

| Removed port | Why it did not earn its keep |
|--------------|------------------------------|
| `IEventEmitter` | Events are a bare RxJS `ReplaySubject` created in the component; there is no adapter to inject, and wrapping the subject in a protocol buys nothing. |
| `ISyntaxHighlighter` | A protocol returning a parse tree does not fit CodeMirror's compartment/`StateEffect` integration, where highlighting is a *view plugin*, not a callable service. |
| `IEditorOperations` | The imperative editor operations *are* the public JavaScript ref handle; a second ClojureScript protocol over them adds no testability and risks the two drifting apart. |

## Adapters — `infrastructure.datascript-adapter`

`infrastructure.datascript-adapter` (`src/infrastructure/datascript_adapter.cljs`) provides the four
repository adapters as ClojureScript records. The defining detail:

> **Each adapter record holds a Workspace `conn`** (`{:keys [conn]}`) and destructures it in every
> method. Adapters are therefore **Workspace-scoped**, not global — two Workspaces get two sets of
> repositories over two databases.

Hot reads route through the coalesced query family (`active-uri-text-lang-version`,
`doc-text-lang-version-by-uri`); this is the performance contract validated by
`datascript_adapter_test`. Factory functions `make-*-repository` construct each record over a given
`conn`.

The remaining adapters live in the library:

- **`ConnectionManager`** (`lib.lsp.connection-manager`) is the live `ILspClient`. Its `notify-*`
  methods are thin pass-throughs to `lib.lsp.client`, so consuming the port adds only one protocol
  dispatch, and that dispatch is off the per-keystroke path (the keystroke `didChange` is debounced
  — see [lsp-subsystem.md](lsp-subsystem.md)).
- **`LifecycleManager`** (`lib.lifecycle`) implements `IResourceLifecycle`; each editor gets a
  private, isolated registry (see [editor-component.md](editor-component.md#resource-lifecycle)).
- **`DebounceCoordinator`** (`lib.debounce`) implements `IDebounceCoordinator`.

## The DI container — `app.system`

`app.system` (`src/app/system.cljs`) is the demo app's composition root. It is a rebindable
`defonce` atom holding the four DataScript repositories, constructed lazily over the default
Workspace's `conn`:

- `init!` — build the production repositories (idempotent; the default).
- `set-system!` — replace the repositories with test doubles.
- `reset-system!` — restore the production repositories.

`app.core` calls `sys/init!` on start and on hot-reload. The re-frame layer never references
`lib.db` or DataScript directly:

- **Reads** — `app.cofx` coeffects call `(p/get-… (sys/document-repo) …)`, etc.
- **Writes** — `app.fx` effects call `(p/…! (sys/document-repo) …)`, etc.

This is the seam that delivers testability: `app/system_test` proves that the *real*
`:document-repo/active-uri` coeffect and `:document/create` effect delegate to an injected `reify`
mock repository. The container was chosen over routing everything through `IResourceLifecycle`
because the repositories are stateless `conn`-wrappers with no asynchronous start/stop ordering —
they need injection, not lifecycle management.

## Data-flow: a read and a write

To make the indirection concrete, here is how the demo app reads the active document's text and how
it creates a document, each passing through a port:

```clojure
;; READ (app.cofx) — the coeffect delegates to the injected IDocumentRepository.
;; The adapter serves this from ONE coalesced DataScript query (get-document-summary).
(rf/reg-cofx
 :document-repo/active-document
 (fn [coeffects]
   (assoc coeffects :active-document
          (p/get-active-document (sys/document-repo)))))

;; WRITE (app.fx) — the effect delegates to the injected IDocumentRepository.
(rf/reg-fx
 :document/create
 (fn [{:keys [uri text language]}]
   (p/create-document! (sys/document-repo) uri text language)))
```

In tests, `app.system/set-system!` swaps `(sys/document-repo)` for a `reify IDocumentRepository`
double, and the *same* coeffect/effect code exercises the mock — no re-frame re-registration
required.

## What is *not* routed through ports

The library editor (`lib.core` and the `lib.editor.*`/`lib.workspace`/`lib.db` stack) calls `lib.db`
directly, threading the Workspace `conn`. The single exception is the `ILspClient` port, which the
editor holds as `client` and invokes via `p/notify-*!`/`p/request-*!`. This keeps the editor's hot
paths free of avoidable dispatch while still abstracting the one collaborator — the language server —
that genuinely has multiple conceivable implementations (the live `ConnectionManager`, and mock
clients in tests). See the [architecture overview](README.md#why-partial-hexagonal) for the rationale.

## Related reading

- [editor-component.md](editor-component.md) — how the `client` (an `ILspClient`) is created per
  editor and threaded into the runtime.
- [data-model.md](data-model.md) — the DataScript schema the adapters read and write.
- The original migration record: [`../archive/remediation/PHASE2_DESIGN.md`](../archive/remediation/PHASE2_DESIGN.md)
  and [`../archive/remediation/REMEDIATION_LEDGER.md`](../archive/remediation/REMEDIATION_LEDGER.md).
