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
