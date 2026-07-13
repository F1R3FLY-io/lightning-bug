# Documentation Archive — Preserved Scientific Record

This directory holds **historical documentation that is deliberately preserved verbatim, not
rewritten.** Each file here is a dated, point-in-time record of a *completed* engineering campaign
or experiment. Rewriting such a record would falsify a scientific ledger, so these documents are
kept exactly as they were written when the work was done.

> **Looking for how the system works *today*?** The archive describes *how the system got here*.
> For the current design and behavior at the present release, read the living documentation under
> [`../architecture/`](../architecture/README.md), [`../guide/`](../guide/README.md),
> [`../formal/`](../formal/README.md), [`../benchmarks/`](../benchmarks/README.md),
> [`../security/`](../security/README.md), and [`../development/`](../development/README.md).
> The enduring *design* and *performance* knowledge distilled from these ledgers already lives
> there; the archive is their immutable provenance.

## Why keep these at all?

The project follows a scientific-method workflow: every non-trivial change records a **hypothesis**,
the **change** made, the **result** (test / benchmark / lint evidence), and a **decision**. The
resulting ledgers are valuable precisely *because* they are frozen:

- They document **rejected** hypotheses as well as accepted ones (e.g. benchmark `EXP-001`, where
  replacing a DataScript `or-join` with `get-else` measurably *regressed* performance and was
  reverted). Frozen negative results stop a future contributor from re-attempting a known dead end.
- They preserve the **exact measurements, test counts, and lint deltas** at each step, which are the
  evidence behind the current design. Re-narrating them in the present tense would erase that
  evidence.
- They are the **audit trail** for how the architecture reached its current shape.

## Contents

### `remediation/` — completed code-quality & architecture campaigns

| File | What it records |
|------|-----------------|
| [`remediation/REMEDIATION_LEDGER.md`](remediation/REMEDIATION_LEDGER.md) | The six-phase remediation campaign (dead-code removal, completing the hexagonal migration, structural refactors, test fixes, naming/docs/types, final verification). Enduring design → [`../architecture/hexagonal-architecture.md`](../architecture/hexagonal-architecture.md), [`../architecture/editor-component.md`](../architecture/editor-component.md). |
| [`remediation/MULTI_EDITOR_WORKSPACE_LEDGER.md`](remediation/MULTI_EDITOR_WORKSPACE_LEDGER.md) | The multi-editor **Workspace** redesign campaign (eliminating module-global singletons; per-`Workspace` `conn`, resources, and LSP; same-file reactive sync; hot-reload survival). Enduring design → [`../architecture/multi-editor-workspaces.md`](../architecture/multi-editor-workspaces.md). |
| [`remediation/PHASE2_DESIGN.md`](remediation/PHASE2_DESIGN.md) | The point-in-time execution spec for Phase 2 of the remediation campaign (the hexagonal ports-and-adapters migration), including its ordered, gated checklist. Enduring design → [`../architecture/hexagonal-architecture.md`](../architecture/hexagonal-architecture.md). |

### `benchmarks/` — dated performance baselines & experiment records

| File | What it records |
|------|-----------------|
| [`benchmarks/BASELINE_2026-01-21.md`](benchmarks/BASELINE_2026-01-21.md) | The performance baseline captured on 2026-01-21 (commit-pinned). |
| [`benchmarks/analysis/bottleneck-analysis.md`](benchmarks/analysis/bottleneck-analysis.md) | The one-off profiling analysis that identified the DataScript `or-join`-on-optional-attributes bottleneck. Its enduring finding seeds [`../benchmarks/performance-model.md`](../benchmarks/performance-model.md). |
| `benchmarks/EXP-001…EXP-010_*.md` | The individual experiment logs (hypothesis → change → measured result → accept/reject). No `EXP-006` or `EXP-011` log exists. The optimizations that were **accepted** are summarized, current-state, in [`../benchmarks/performance-model.md`](../benchmarks/performance-model.md). |

The experiment logs, at a glance:

| Experiment | Change | Decision |
|------------|--------|----------|
| [`EXP-001`](benchmarks/EXP-001_symbol-get-else.md) | symbol query: `or-join` → `get-else` | **Rejected** (regression) |
| [`EXP-002`](benchmarks/EXP-002_symbol-pull-many.md) | symbol query: entity-ids → `d/pull-many` | **Accepted** |
| [`EXP-003`](benchmarks/EXP-003_diagnostic-pull-many.md) | diagnostic query: entity-ids → `d/pull-many` | **Accepted** |
| [`EXP-004`](benchmarks/EXP-004_wasm-timeout-removal.md) | remove dead post-init WASM timeout | **Accepted** |
| [`EXP-005`](benchmarks/EXP-005_viewport-highlight-cache.md) | viewport-margin highlight decoration cache | **Accepted** |
| [`EXP-007`](benchmarks/EXP-007_query-coalescence.md) | coalesce multi-attribute reads into one query | **Accepted** |
| [`EXP-008`](benchmarks/EXP-008_debounce-consolidation.md) | single centralized debounce with max-wait | **Accepted** |
| [`EXP-009`](benchmarks/EXP-009_keystroke-hot-path.md) | keep the keystroke path `O(1)` | **Accepted** |
| [`EXP-010`](benchmarks/EXP-010_hot-path-fix-event-cleanup.md) | fix EXP-009's eagerly-forced `delay` | **Accepted** |

The machine-readable results (`baseline.json`, `experiment.json`, `exp-005-scroll.json`,
`exp-007.json`) remain under [`../benchmarks/results/`](../benchmarks/results/) so the comparison
tooling can still consume them.

## Relationship to the living docs

```
archive/  (frozen: how we got here)          living docs/  (current: what the system is)
──────────────────────────────────          ────────────────────────────────────────────
remediation/REMEDIATION_LEDGER.md    ─┐
remediation/MULTI_EDITOR_..._LEDGER  ─┼─▶  architecture/  (hexagon, editor split, Workspaces,
remediation/PHASE2_DESIGN.md         ─┘                    data model, LSP, syntax, events)

benchmarks/BASELINE_2026-01-21.md    ─┐
benchmarks/analysis/bottleneck-...   ─┼─▶  benchmarks/performance-model.md (durable hot-path model)
benchmarks/EXP-00N_*.md              ─┘     benchmarks/README.md            (methodology)
```

Nothing in this directory should be edited except to fix a genuine transcription error in the
original record; new findings belong in the living documentation, not here.
