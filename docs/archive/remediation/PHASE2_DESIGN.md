# Phase 2 Design — Complete the Hexagonal Migration

Distilled from the Plan-agent design (full reasoning in the remediation session). This is
the authoritative spec for executing Phase 2. Goal: make `domain.protocols` genuinely used
by its two live consumers — the demo app's re-frame cofx/fx and the library's LSP call sites —
behavior-preservingly, and remove what stays dead after wiring.

## Decisions (resolving the design forks)

1. **LSP client = wire the existing `ConnectionManager`** (NOT a new `EditorLspClient` record).
   Rationale: the user chose to *complete/use* the scaffolding, so CM (the intended rich
   `ILspClient`) must become live rather than be bypassed/deleted. CM's `notify-*` methods are
   already thin pass-throughs to `lib.lsp.client`, so the keystroke hot path gains only one
   protocol dispatch (and that code is 150ms-debounced, not per-keystroke).
2. **System container = plain `defonce` atom** in `app/system.cljs` (NOT `lib.lifecycle`):
   the 4 repositories are stateless wrappers over the global `lib.db/conn`; no async
   start/stop/ordering → lifecycle registration would add overhead with no benefit.
3. **Preserve EXP-007**: the repository adapter's hot reads must use the coalesced queries.
   Reimplement `get-active-document` to use `db/active-uri-text-lang-version` (1 query) and add
   one narrow protocol method `get-document-summary` (uses `db/doc-text-lang-version-by-uri`)
   for the `:document-repo/document` cofx. Keep the heavy `get-document` for completeness/tests.
4. **`connect` stays via `connect-supplier`**: add an `ILspClient/connect-supplier` method that
   returns the exact `#(lsp/connect lang {:url url} state-atom events)` thunk lib.core feeds to
   `lib.state/load-resource` — byte-identical connect flow, but removes direct `lsp/*` from
   lib.core. (Fallback if any issue: keep the single `lsp/connect` site direct + comment.)
5. **Narrow CM `request-shutdown!` and `request-symbols!` to thin pass-throughs** (matching
   lib.core's current unguarded semantics; verified no CM test asserts the state-machine guard).
   Keep `disconnect!` for state-machine disconnect.
6. **2d: remove the 3 never-implemented protocols** `IEventEmitter`, `ISyntaxHighlighter`,
   `IEditorOperations` (no fitting API, no consumer) — per the plan's narrow/remove escape hatch.
   Result: every retained protocol has at least one production-instantiated implementation.
7. **2e: delete `domain/entities.cljs` + `entities_test.cljs`** — verified its document/position/
   range/diagnostic/symbol/log specs are NOT in `lib.state` and have no consumer; `lib.state` is
   the live spec home. Nothing to fold. Update `cljs_test_runner.cljs`.

## Protocol changes (`domain/protocols.cljs`)
- `IDocumentRepository`: + `(get-document-summary [this uri])` — coalesced `{:uri :text :language :version}`.
- `ILspClient`: + `(notify-did-change-incremental! [this language uri changes version])`,
  `(shutdown-all! [this])`, `(connect-supplier [this language url])`.
- Remove `IEventEmitter`, `ISyntaxHighlighter`, `IEditorOperations` (with one-line justification comments).

## ConnectionManager changes (`lib/lsp/connection_manager.cljs`)
- Implement the 3 new `ILspClient` methods (all thin pass-throughs to `lib.lsp.client`;
  `connect-supplier` returns `#(lsp/connect language {:url url} state-atom events)`).
- Narrow `request-shutdown!` → `(lsp/request-shutdown language state-atom)`;
  `request-symbols!` → `(lsp/request-document-symbol language uri state-atom)` (drop the
  `initialized?` guard to match lib.core's live behavior).

## Adapter changes (`infrastructure/datascript_adapter.cljs`)
- `get-active-document` → coalesced `db/active-uri-text-lang-version` → `{:uri :text :language :version}`.
- + `get-document-summary` → coalesced `db/doc-text-lang-version-by-uri`.

## App wiring
- New `src/app/system.cljs`: `(defonce ^:private system (atom nil))` + `init!`/`set-system!`/
  `reset-system!` + `document-repo`/`diagnostics-repo`/`symbols-repo`/`log-repo` accessors.
- `app/core.cljs`: require `app.system`, call `(sys/init!)` in `init` (after `rp/connect!`) and in `reload`.
- `app/cofx.cljs` + `app/fx.cljs`: flip every storage handler to `(p/... (sys/*-repo) ...)`; KEYS UNCHANGED.
  Editor-ref / console / timer / `:now` handlers stay unchanged (no repository equivalent).

## lib.core LSP rewiring (`lib/core.cljs`) — benchmark-gated
- Construct `client` per-editor via `react/useMemo` (`cm/make-connection-manager state-atom events`).
- Thread `client` through `get-extensions`, `ensure-lsp-document-opened`, `activate-document`.
- Rewrite all 16 `lsp/*` sites to `(p/... client ...)` per the design's call-site table
  (incl. `notify-did-change-incremental!` at the keystroke path, `shutdown-all!` for the 1-arg
  shutdown sites, `connect-supplier` at the connect site, `request-symbols!` at the symbol site).
- Delete `infrastructure/lsp_adapter.cljs` (redundant, zero references).

## Tests (2e)
- New `src/test/infrastructure/datascript_adapter_test.cljs` (in-memory conn; assert delegation
  equivalence to `lib.db`; EXP-007 regression guard on `get-active-document`/`get-document-summary`).
- Extend `connection_manager_test.cljs`: new methods (incremental, shutdown-all, connect-supplier),
  and the narrowed pass-through `request-shutdown!`/`request-symbols!`.
- Delete `domain/entities.cljs` + `test/domain/entities_test.cljs`; update `cljs_test_runner.cljs`.

## Ordered checklist (gate = `test:debug` green at each step; benchmark gate at step 7)
1. protocols + CM new methods/narrowing (no consumer flip)
2. CM ILspClient tests
3. `app/system.cljs` + `app.core` init/reload wiring
4. adapter reimpl (`get-active-document` coalesced + `get-document-summary`)
5. `datascript_adapter_test` + register in runner
6. flip `cofx`/`fx` to protocol methods (+ ensure test fixtures init the system)
7. **[benchmark-gated]** lib.core: thread `client`, rewrite 16 call sites
8. delete `infrastructure/lsp_adapter.cljs`
9. remove 3 unimplemented protocols
10. delete `domain/entities.cljs` + test; update runner
11. Completed in verification: event coverage and keystroke behavior are covered by browser tests and formal checks.
