# Multi-Editor Workspace Refactor — Progress Ledger

Tracks the refactor from global singletons to instantiable, hot-reload-surviving
Workspaces with reactive same-file sync. Plan: `~/.claude/plans/plan-corrections-to-all-zazzy-candy.md`.
Branch: `feature/multi-editor-workspaces` (off `ce25759`).

Gate convention (verified): the meaningful lint gate is **clj-kondo + eastwood (0/0)**.
`npm run lint` also runs splint + kibit, which exit non-zero on the committed baseline
(~94 splint warnings, ~40 kibit suggestions — pre-existing, advisory). Benchmarks are
**unpinned** (no sudo CPU pinning available): measured per-metric noise between two runs
of identical code is ~±70%, so benchmark comparisons here distinguish *real* regressions
(consistent across replicate runs + a plausible mechanism) from noise.

---

## Phase 0 — Baseline + engineering protections — DONE

- Baseline captured: `test:debug` 501/501, clj-kondo 0/0, eastwood 0/0, `benchmark:baseline` → `docs/benchmarks/results/baseline.json` (26 metrics).
- Engineering protections added:
  - **Benchmark CI gate**: `scripts/compare-benchmarks.js --gate-regression <pct>` mode + a same-runner base-vs-head `benchmark` job in `.github/workflows/ci.yaml`. Threshold set to **+150%** (below that is unpinned-CI noise; see above). `npm run benchmark:gate`.
  - **Demo dep drift check**: `scripts/check-demo-deps.js` + `npm run check:demo-deps` + CI step. Synced `resources/public/demo/package.json` to root (`web-tree-sitter` 0.25.8→0.25.9, `@codemirror/autocomplete` ^6.18.6→^6.18.7, `@codemirror/view` ^6.38.1→^6.38.2).
  - **Karma WASM/query guard**: `karma.conf.js` fails fast if `prepare:test` artifacts are missing (instead of silent 404s).
  - **CI artifact fix**: release artifact path referenced the deleted `scripts/postinstall.js`; corrected to the published `scripts/utils.js`.

## Phase 1 — Workspace foundation: eliminate globals — DONE

**Goal:** replace the module-global `lib.db/conn` with a per-workspace conn threaded
explicitly, so multiple editors / isolated workspaces are possible, while surviving
React re-renders and dev hot-reload.

- New `lib.workspace` (`Workspace` record `[conn resources]`, `make-workspace`,
  `ensure-workspace`, `reset-workspace!`, `default-conn`, and a **`defonce` + `delay`
  `default-workspace`** — the hot-reload survival mechanism: the conn is created once and
  persists across `:dev/after-load`, identical to the old `(defonce conn …)`).
- `lib.db`: all 67 conn-using fns take `conn` as the first arg (pure helpers —
  `flatten-*`/`create-diagnostics`/`valid-*?`/`transform-*` — unchanged); intra-namespace
  calls forward `conn`. The `(defonce conn …)` global was **deleted** (full elimination,
  per user decision). `schema` remains public (used by `make-workspace`).
- Threaded `conn` through all production callers: `core` (resolves workspace via
  prop → React context → shared default; `EditorWorkspaceProvider` added), `commands`
  (via `ctx`), `runtime` (fn params; captured lexically in the keystroke hot path — **no
  per-keystroke deref**, preserving EXP-009/010/011), `syntax` (`init-syntax` takes `conn`),
  `lsp.client`/`connection_manager` (through `connect`/`handle-message`/handlers + the CM
  `conn` field), `datascript_adapter` (record `[conn]` field), `app.system`/`subs`/`core`.
- Moved per-editor EXP-011 scratch (`pending-idle-syncs`, `pending-lsp-changes`) from
  module-global atoms into the per-editor `state-atom` (fixes a latent cross-editor
  same-URI race; ephemeral so hot-reload-safe).
- Tests: threaded `conn` through ~551 `db/*` call sites + factory/handler calls across
  18 files (scripted), pointing them at `(ws/default-conn)` and swapping reset fixtures to
  `reset-workspace!` — so the final global deletion required no further test edits.

**Results:**
- `test:debug` **501/501 SUCCESS**; `test:types` clean; **clj-kondo 0/0, eastwood 0/0**.
- No `db/conn` references remain anywhere (full elimination verified).
- **Benchmark:** initial run showed systematic "regressions" on cheap db queries — root-caused
  to the benchmark harness calling `(ws/default-conn)` *inside* timed loops (per-iteration
  resolution), NOT a production issue. Fixed by hoisting `conn` to a module binding in
  `benchmark_tests` (matching production's lexical capture). Replicate-run control (two runs
  of identical Phase-1 code) showed `query-cache-symbols` swinging −36% / +69% → **pure
  unpinned-CPU noise**. Conclusion: **no real Phase 1 performance regression** (production
  threads `conn` lexically = zero added cost).

**Hot-reload invariant upheld:** `default-workspace` is `defonce`+`delay`; no-prop editors
resolve to that shared instance (never a per-instance fallback) → documents survive reloads.

## Phase 2 — Per-pane :active-uri (the spine) — DONE

**Goal:** make "which file this editor shows" per-pane (so multiple editors in one
workspace can show different files), while the workspace keeps a single FOCUS for the app.

- Added `:active-uri` to the per-editor `state-atom`. The conn's `:workspace/active-uri`
  is RETAINED as the workspace focus (read by the demo via `app.subs`/`app.cofx`/the
  repository's `get-active-*`).
- Editor-local code now reads the pane's `:active-uri`: the keystroke updateListener,
  the imperative handle methods (`normalize-uri` now takes `state-atom`; `getState`,
  `getText`, cursor/selection events, `active-uri?` checks), `syntax/init-syntax`, and the
  core DataScript→view sync effect. `activate-document` and `renameDocument` set BOTH the
  pane `:active-uri` and the workspace focus, so **single-editor behavior is identical**.
- Unmount clears the workspace focus only if THIS pane held it (`(= (:active-uri @state-atom)
  (db/active-uri conn))`) — multi-pane safe.
- **Hot-path improvement:** the per-keystroke listener now reads `(:active-uri @state-atom)`
  (a cheap atom lookup) instead of `(db/active-uri conn)` (a DataScript query every
  keystroke). Strictly less work; no benchmark re-run needed (and the unpinned suite's
  ±70% noise couldn't show it anyway).
- Tests: syntax tests that staged an active document via the conn now also seed the pane's
  `:active-uri` (the per-pane equivalent of "the active document").

**Results:** test:debug 501/501, clj-kondo 0/0, eastwood 0/0, test:types clean. No dead
code introduced (`active-version`/`active-uri-text-lang` retain db_query_test coverage).

## Phase 3 — Projects + files model — DONE

**Goal:** a workspace groups its files into projects ("workspaces with multiple projects
and files").

- New schema: `:project/id` (unique-identity), `:project/root` (unique-identity),
  `:project/name`, and `:document/project` (ref). Added `::project` spec + `valid-project?`.
- Accessors (conn-first): `create-projects!`, `projects`, `project-by-id`,
  `project-by-root`, `project-for-uri` (longest-root-prefix match), `documents-by-project`,
  `project-of-uri` (→ {:id :name :root}), `link-document-to-project!`.
- `create-documents!` auto-links a new document to a project: an explicit `:project` id
  wins, else the project whose `:project/root` is a prefix of the uri. No projects ⇒ no
  link (backward-compatible; existing callers unchanged).
- New test `db_test/projects-crud-and-linking` (create/query/auto-link/explicit-link).

**Results:** test:debug **502/502**, clj-kondo 0/0, eastwood 0/0, test:types clean.

## Phase 4 — Reactive cross-pane propagation — DONE

**Goal:** editors viewing the SAME file sync live (Google-Docs-style); each keeps its
own cursor; edits still persist to the backend exactly as before.

- Workspace gains `:doc-streams` (atom). New ns `lib.workspace.doc-sync`: ref-counted
  per-(workspace,file) RxJS `Subject` with `get-or-create-stream!`/`release-stream!`/
  `has-peers?`/`publish-delta!`/`subscribe-pane`/`apply-remote-delta!`.
- **Echo-suppression (double-guarded):** each delta carries its origin `pane-id` and a
  subscriber ignores its own emissions; AND a receiver applies the remote delta annotated
  with `external-set-annotation`, so its own updateListener treats it as API-driven and
  neither re-publishes nor re-runs LSP/DataScript-as-user-edit.
- `lib.core`: a stable per-pane `pane-id`; `ctx` gains `:workspace`/`:pane-id`; a subscribe
  `useEffect` (keyed on `[active-uri workspace]`) applies remote deltas to this pane's view
  via `ChangeSet.fromJSON` + `selection.map` (cursor preserved) + `scrollIntoView false`,
  and releases the ref-counted stream on cleanup.
- `lib.editor.runtime`: publishes the keystroke `ChangeSet` delta on the origin, guarded by
  `(not from-api?)` AND `has-peers?` — so a **single editor serializes nothing** (zero
  hot-path cost); the DataScript idle-sync + LSP didChange paths are unchanged.
- Ordering = single-threaded synchronous fan-out in publish order ⇒ convergence (no OT/CRDT).
- Unit tests `doc_sync_test`: ref-counting/has-peers, echo-suppression + seq ordering,
  apply-remote-delta. The full two-editor integration test is Phase 8 (multi_pane_test).

**Results:** test:debug **505/505**, clj-kondo 0/0, eastwood 0/0, test:types clean.
Single-editor hot path unchanged (has-peers? gate); benchmark unaffected (single-editor).

## Phase 5 — LSP: one FSM state model + resilience — DONE (5a, 5c); per-workspace move documented (5b)

**Goal:** wire the dead ConnectionManager state-machine, make reconnect active by default,
and (5b) move LSP connection ownership per-workspace so split panes share one didOpen.

- **5a (DONE):** Extracted `lib.lsp.fsm` (STATES/TRANSITIONS/valid-transition? + pure
  state->connected?/initialized?/flags). `lib.lsp.client` drives `[:lsp lang :state]` via a
  `transition!` helper at every connection transition, deriving the legacy boolean flags
  from `:state`. So `:state` is the single source of truth and the CM's keyword predicates
  (connected?/initialized?/connect!) are LIVE; both the boolean- and keyword-asserting test
  contracts pass. connection_manager re-exports STATES/TRANSITIONS/valid-transition? as aliases.
- **5c (DONE):** `connect` takes a `reconnect-fn` invoked when an ESTABLISHED connection
  drops unexpectedly (was connected, not graceful shutdown, not a failed initial connect).
  The CM supplies `reconnect-fn = reconnect-with-backoff!` (its formerly-dead path) via
  connect-supplier + connect! → **reconnect is active by default**. Graceful shutdown sets
  `[:lsp lang :shutting-down?]` to suppress it; failed initial connects don't storm.

**5b — per-workspace/per-file LSP (REMAINING; documented scope boundary, not a silent skip):**
Today LSP state is per-editor (`state-atom [:lsp lang]`) while the socket is shared
(`lib.state/resources`, currently global). For a SINGLE editor this is correct (all tests
green). For SPLIT PANES on the same file it is suboptimal: pane B's per-editor LSP state
doesn't see pane A's connection, so B can't send LSP and B's view wouldn't get live
diagnostics. The correct fix is to make the LSP **connection + state + events** workspace-
level: one ConnectionManager per language per workspace (over a workspace `:lsp` atom +
`:resources`), with a workspace LSP **events** subject that panes subscribe to and forward
to their own `events` (preserving `editor.getEvents()`), and `getState` reading the
workspace `:lsp`. This is a large cross-cutting refactor (resource-model + LSP event-routing
+ getState shape) with high risk to the intricate LSP test contracts, and the core reactive
**content** sync (Phase 4) does not depend on it. Deferred to a focused follow-up to avoid
destabilizing the green LSP layer mid-effort. (Also: `cleanup-stale-requests!`/
`start-cleanup-task!`/`with-timeout` remain callable + tested but not auto-started in
production — auto-start is constrained by a client→CM cycle and the module-global
`cleanup-intervals`; low value given reconnect is wired.)

**Results:** test:debug **505/505**, clj-kondo 0/0, eastwood 0/0, test:types clean.

## Phase 7 — Public API, wire app.languages, types — DONE

- Exported `createWorkspace` (wraps make-workspace) + `EditorWorkspaceProvider` from the
  `:lib.core` ESM module (shadow-cljs `:exports` + `^:export`).
- `<Editor>` props: `workspace` (resolved prop → React context → shared default) and `uri`
  (activates that document on mount — declarative split-pane file selection). Both read off
  raw js-props and stripped before config validation.
- `types/lib.d.ts`: renamed the state-shape `Workspace` → `WorkspaceSnapshot`; added the
  opaque `Workspace` handle, `createWorkspace()`, `EditorWorkspaceProvider`, and
  `workspace?`/`uri?` on `EditorProps`. `tsd` passes.
- **Wired `app.languages`** (3rd dead abstraction): `app.db` registers the demo's languages
  via `app.languages/register-language` + `set-default-lang` and builds the initial app-db
  from its registry — `app.languages` is the demo's language source of truth, not dead.

**Results:** test:debug **505/505**, clj-kondo 0/0, eastwood 0/0, test:types clean, app build clean.
