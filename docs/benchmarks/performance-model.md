# The Performance Model

This document is the **durable** performance knowledge for Lightning Bug: where the hot paths are,
and which optimizations were measured, accepted, and kept. It distills the project's benchmark ledger
(the dated experiment records, preserved in
[`../archive/benchmarks/`](../archive/README.md#benchmarks--dated-performance-baselines--experiment-records))
into current-state guidance. The methodology behind the measurements is in [README.md](README.md).

## The hot paths

Five areas dominate interactive performance. Each was identified by profiling before any change was
made (the original analysis is
[`../archive/benchmarks/analysis/bottleneck-analysis.md`](../archive/benchmarks/analysis/bottleneck-analysis.md)).

1. **DataScript `or-join` on optional attributes** — the dominant query cost. Every slow query used
   an in-query `or-join` to handle an optional attribute (a symbol's optional parent; a diagnostic's
   optional version filter), and each `or-join` branch is a separate scan. Symbol queries also
   materialized ~12 attributes per row.
2. **The keystroke path.** CodeMirror's `updateListener` runs **synchronously on every keystroke**;
   naïve work there — a full `(str doc)` document serialization (`$`O(n)`$` in document length), a
   synchronous DataScript text transaction, a redundant active-URI query, a synchronous
   LSP-opened check — directly adds keystroke latency.
3. **Redundant sequential queries.** Coeffects/handlers issued two or three separate
   single-attribute queries for one logical read.
4. **Dual debouncing.** Events passed through *two* debounce layers (a core layer and a view-level
   throttle), so worst-case latency was additive (~400 ms).
5. **Redundant highlight queries on scroll.** A full decoration rebuild on every viewport change.

## The optimizations that stuck

### IDs-then-`pull-many`

The single most important query optimization: replace an in-query `or-join` with a trivial
entity-id query, then `d/pull-many` over those ids, then handle optional attributes in ClojureScript
*after* the query. This moved every DataScript query under the 5 ms target.

- **`EXP-002`** (symbols): `symbols-by-uri` −62 % (10.02 → 3.77 ms), `all-symbols` −58 % (9.33 → 3.93 ms).
- **`EXP-003`** (diagnostics): `diagnostics-by-uri` −49 % (6.75 → 3.42 ms), `all-diagnostics` −57 %
  (5.58 → 2.41 ms).
- **`EXP-001`**, the *rejected* precursor, tried `get-else` instead and **regressed +38–40 %** — a
  reminder that DataScript's optimizer does not match intuition. Where it lives: `lib.db`. See
  [data-model.md](../architecture/data-model.md#batch-reads-with-pull-many).

### Query coalescence

Merge several single-attribute reads into one multi-attribute query, so a single Re-frame event does
one query compilation and index traversal instead of several. `EXP-007` added the coalesced accessors
(`active-uri-version`, `active-uri-text-lang-version`, `doc-text-lang-version-by-uri`, …) and cut the
coalesced reads by 23–36 %. This is the `doc-*`/`active-uri-*` naming convention in
[data-model.md](../architecture/data-model.md#coalesced-queries); it is also why
`get-document-summary` exists on the [`IDocumentRepository` port](../architecture/hexagonal-architecture.md#ports--domainprotocols).

### A single centralized debounce with max-wait

`EXP-008` replaced the dual debounce (core + view-level throttle) with one `lib.debounce` layer that
supports a **max-wait**, which forces execution during continuous input. This eliminated the additive
latency and guarantees selection/cursor updates within ≤ 200 ms (a −43 % worst case).

### An `$`O(1)`$` keystroke path

`EXP-009` and its corrective `EXP-010` reshaped the update listener so it never does `$`O(n)`$` work:

- **Never serialize the document in the listener.** `content-change` carries `{uri, length}`, not the
  text; consumers read text from DataScript when they need it.
- **Debounce the DataScript text sync** (50 ms, max-wait 200 ms) inside a `requestIdleCallback`, so
  it never blocks a keystroke.
- **Cache the LSP-opened status in an atom** (`$`O(1)`$`) instead of a DataScript query.
- **Make the callback source-aware** — immediate for API-driven changes (discriminated by the
  `external-set-annotation`), debounced for keystrokes.

> **`EXP-010`'s lesson.** `EXP-009` first shipped a bug: a `cond->` unconditionally dereferenced a
> `(delay (str doc))` on every keystroke when LSP was connected, defeating the laziness and keeping
> the `$`O(n)`$` serialization on the hot path. **`cond->` and `delay` are not lazy if you dereference
> them unconditionally.** `EXP-010` fixed it. The current behaviour is in
> [syntax-and-editing.md](../architecture/syntax-and-editing.md#the-keystroke-hot-path).

### The viewport highlight cache

`EXP-005` gave the Tree-Sitter highlighter a decoration cache with a ±2000-character viewport margin,
reused while the viewport stays within the cached range. On a cache hit it is **30–100× faster**
(1–8 ms vs. 200–400 ms) with no regressions. See
[syntax-and-editing.md](../architecture/syntax-and-editing.md#viewport-cached-highlighting).

### Dead-delay removal

`EXP-004` removed a dead 100 ms timeout after Tree-Sitter initialization — a deterministic −100 ms
per language init.

## The experiment ledger at a glance

There is no `EXP-006` or `EXP-011` record; the ledger contains `EXP-001`–`005` and `007`–`010`.

| Experiment | Change | Decision | Measured effect |
|------------|--------|:--------:|-----------------|
| [`EXP-001`](../archive/benchmarks/EXP-001_symbol-get-else.md) | symbol `or-join` → `get-else` | **REJECT** | +38–40 % regression |
| [`EXP-002`](../archive/benchmarks/EXP-002_symbol-pull-many.md) | symbols: ids → `pull-many` | **ACCEPT** | −58…−62 % |
| [`EXP-003`](../archive/benchmarks/EXP-003_diagnostic-pull-many.md) | diagnostics: ids → `pull-many` | **ACCEPT** | −49…−57 % |
| [`EXP-004`](../archive/benchmarks/EXP-004_wasm-timeout-removal.md) | remove dead WASM timeout | **ACCEPT** | −100 ms init |
| [`EXP-005`](../archive/benchmarks/EXP-005_viewport-highlight-cache.md) | viewport highlight cache | **ACCEPT** | 30–100× on hits |
| [`EXP-007`](../archive/benchmarks/EXP-007_query-coalescence.md) | query coalescence | **ACCEPT** | −23…−36 % |
| [`EXP-008`](../archive/benchmarks/EXP-008_debounce-consolidation.md) | single debounce + max-wait | **ACCEPT** | −43 % worst case |
| [`EXP-009`](../archive/benchmarks/EXP-009_keystroke-hot-path.md) | `O(1)` keystroke path | **ACCEPT** | ~90 % fewer DB txns/keystroke |
| [`EXP-010`](../archive/benchmarks/EXP-010_hot-path-fix-event-cleanup.md) | fix EXP-009's eager `delay` | **ACCEPT** | removes `O(n)` from hot path |

## Meta-lessons

- **Benchmark before and after**, with the full statistical pipeline — never optimize on intuition.
- **DataScript's optimizer defies intuition:** `or-join` beat `get-else`; moving optional-attribute
  logic *out* of the query (into `pull-many` + ClojureScript) beat both.
- **Keep the keystroke path `$`O(1)`$`:** never serialize the document there; cache hot-path status
  flags in atoms; debounce and idle-schedule anything heavier.
- **Laziness is easy to defeat:** a `delay`/`cond->` dereferenced unconditionally is not lazy.

## Related reading

- [README.md](README.md) — the statistical methodology and how to run the suite.
- [architecture/data-model.md](../architecture/data-model.md) — the query conventions these
  optimizations produced.
- [architecture/syntax-and-editing.md](../architecture/syntax-and-editing.md) — the keystroke hot
  path and the highlight cache.
- [`../archive/benchmarks/`](../archive/README.md) — the original dated experiment records.
