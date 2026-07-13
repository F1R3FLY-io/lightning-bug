# The Editor Component

`lib.core` (`src/lib/core.cljs`) is the public `Editor` — a React component created with
`react/forwardRef`. It is deliberately a **thin shell** (~291 lines): it wires dependencies together
and delegates the substantive logic to two extracted namespaces. This document explains that
composition, the seam that makes it possible, and the imperative API the component exposes.

## A short history of the shell's size

The shell was once a ~1158-line monolith. A remediation campaign extracted its two cohesive clusters
into `lib.editor.runtime` and `lib.editor.commands` as **byte-exact** moves (the method bodies and
all hot-path tuning were unchanged; only their location moved), briefly taking the shell down to ~149
lines. The subsequent multi-editor work then *re-added* the Workspace-wiring — workspace resolution,
`createWorkspace`, `EditorWorkspaceProvider`, the same-file sync subscription, and the `uri` prop —
bringing it to its current ~291 lines. So the shell is small *by design*, but it is not as small as
the mid-remediation low-water mark; the growth is real, load-bearing multi-editor code, not
regression. (The historical figures live in
[`../archive/remediation/REMEDIATION_LEDGER.md`](../archive/remediation/REMEDIATION_LEDGER.md).)

## Composition flow

When the inner component renders, it composes its parts in a fixed order:

![Editor composition: lib.core resolves the Workspace, creates a per-editor state atom, memoizes per-editor singletons (events, client, lifecycle manager, and the editor-ctx bundle), builds the imperative ref handle from the ctx, and on mount constructs the CodeMirror view and registers resources. The editor-ctx bundle is passed to both lib.editor.commands and lib.editor.runtime, neither of which touches React.](diagrams/editor-composition.svg)

1. **Resolve the Workspace.** The `workspace` prop (read off the raw JS props *without*
   `js->clj`, so the `Workspace` record is not mangled) wins; otherwise a React context
   (`EditorWorkspaceProvider`); otherwise the shared process-default Workspace. A `useMemo` over the
   prop/context keeps the identity stable across re-renders. The DataScript `conn` is pulled from the
   resolved Workspace. See [multi-editor-workspaces.md](multi-editor-workspaces.md).
2. **Create the per-editor state atom.** A reagent atom seeded from `default-state`, stored in a
   `useRef` so it survives re-renders. This atom holds the pane's cursor, selection, the pane's
   `:active-uri`, search term, and per-editor scratch (`:pending-idle-syncs`, `:pending-lsp-changes`).
3. **Memoize per-editor singletons.** An `events` `ReplaySubject` (the RxJS event stream), a
   `pane-id` (a random UUID used for same-file echo suppression), a `client` (a `ConnectionManager`,
   i.e. an `ILspClient`, over the Workspace's LSP atom + events + `conn`), an isolated
   `lifecycle-mgr`, the **`editor-ctx`** (below), and the container/view refs.
4. **Build the imperative handle.** `useImperativeHandle` returns
   `(commands/build-handle ctx ready)`, recomputed when `[@state-atom current-view ready]` changes.
5. **Run effects**, culminating in the mount effect that builds the CodeMirror `EditorState`/
   `EditorView`, subscribes to the event stream, registers all resources with the lifecycle manager,
   emits `ready`, and — if a `uri` prop was given — activates that document.

## The `editor-ctx` seam

The extraction hinges on a single immutable map:

```clojure
;; built once via useMemo in lib.core
{:state-atom state-atom   ; this pane's reagent state atom
 :view-ref   view-ref     ; ref to the CodeMirror EditorView
 :events     events       ; this pane's RxJS ReplaySubject
 :client     client       ; the ILspClient (ConnectionManager)
 :conn       conn         ; the Workspace's DataScript connection
 :workspace  workspace    ; the resolved Workspace record
 :pane-id    pane-id      ; UUID for same-file echo suppression
 :lsp-atom   (:lsp workspace)}
```

`editor-ctx` is the **dependency vocabulary** shared by the two extracted namespaces:

- `lib.editor.commands/build-handle` destructures `ctx` to implement every imperative method.
- `lib.editor.runtime` functions (`get-extensions`, `activate-document`,
  `ensure-lsp-document-opened`) receive the same fields positionally.

Crucially, **neither extracted namespace touches React** — they see only `ctx`. That is what makes
them unit-testable without a DOM and what keeps `lib.core` a shell.

## The two extracted namespaces

### `lib.editor.runtime`

`src/lib/editor/runtime.cljs` holds the editor's *behaviour*: event emission and helpers
(`emit-event`), `update-editor-state`, the CodeMirror extension assembly (`get-extensions`), the
diagnostic `StateField`, the LSP document-open lifecycle (`ensure-lsp-document-opened`), document
activation (`activate-document`), and the per-editor idle-sync / LSP-change scratch. It is fully
parameterized by `ctx` fields, so it has **no dependency back on `lib.core`** — the dependency graph
is acyclic (and `domain.protocols` has zero requires, so there is no cycle through the ports either).
The keystroke hot path lives here; see [syntax-and-editing.md](syntax-and-editing.md#the-keystroke-hot-path).

### `lib.editor.commands`

`src/lib/editor/commands.cljs` is `build-handle [ctx ready]` — the entire `useImperativeHandle`
method bag, plus `normalize-uri`. Every method is wrapped so that a thrown error emits an `error`
event rather than crashing the host.

## The imperative ref API (28 methods)

`build-handle` returns a JavaScript object with 28 methods, grouped by concern. These are the public
API; the authoritative TypeScript signatures are in `types/lib.d.ts` and documented for consumers in
[React Integration](../guide/react-integration.md).

| Group | Methods |
|-------|---------|
| Lifecycle / readiness | `isReady`, `getState`, `getEvents` |
| Documents | `openDocument`, `closeDocument`, `renameDocument`, `saveDocument`, `activateDocument` |
| Text | `getText`, `setText`, `getFilePath`, `getFileUri` |
| Cursor / selection | `getCursor`, `setCursor`, `getSelection`, `setSelection` |
| Highlight / scroll | `highlightRange`, `clearHighlight`, `centerOnRange` |
| LSP data | `getDiagnostics`, `getSymbols`, `shutdownLsp` |
| DataScript | `query`, `getDb` |
| Search / logging | `getSearchTerm`, `openSearchPanel`, `getLogLevel`, `setLogLevel` |

`getState` returns a snapshot (`EditorState`) merged from the live LSP atom and the DataScript-derived
workspace/logs/diagnostics/symbols. Note that `highlightRange`/`clearHighlight` **emit** a
`highlight-change` event and are always available, but the CodeMirror range-highlight plugin is not
in the default extension stack — see
[syntax-and-editing.md](syntax-and-editing.md#range-highlight-is-opt-in).

## Resource lifecycle

On mount, the component registers its resources with an **isolated** per-editor lifecycle manager
(`lib.lifecycle`), each with a priority. On unmount, teardown runs **synchronously** in
reverse-priority order, so higher-priority resources stop first:

| Priority | Resource | Stopped on unmount |
|---------:|----------|--------------------|
| 40 | LSP connection | first |
| 30 | CodeMirror `EditorView` | |
| 20 | events subscription | |
| 15 | workspace `:lsp-events` subscription | |
| 10 | emit timers (debounce) | last |

Synchronous teardown is deliberate: React strict-mode can remount a component immediately, and an
asynchronous teardown could race the DOM `EditorView.destroy`. Isolated registries prevent one
editor's teardown from touching another's resources. This teardown ordering, and the requirement that
stale asynchronous callbacks observe the unmounted state and perform no mutation, is one of the
properties specified in the [BrowserAsync formal model](../formal/models.md#browserasync).

## Related reading

- [multi-editor-workspaces.md](multi-editor-workspaces.md) — Workspace resolution and the `uri` prop.
- [event-model.md](event-model.md) — the `events` subject and the event catalog.
- [lsp-subsystem.md](lsp-subsystem.md) — the `client` (`ConnectionManager`) the shell creates.
- [React Integration](../guide/react-integration.md) — using this component and its ref from JS/TS.
