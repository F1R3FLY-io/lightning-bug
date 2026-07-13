# EXP-007: Active Document Query Coalescence

**Date:** 2026-01-22
**Branch:** `experiment/exp-007-query-coalescence`
**Status:** VALIDATED - ACCEPTED

---

## Hypothesis

Coalescing multiple sequential database queries into single unified queries will reduce query overhead by 20-40%, improving responsiveness for operations that access multiple document attributes.

## Background

Analysis of the codebase revealed several patterns where multiple sequential queries fetched related document attributes:

1. **`:document-repo/active-document` coeffect** - Made 3 separate queries:
   - `active-uri` - Get active document URI
   - `doc-text-lang-by-uri` - Get text and language
   - `document-id-version-by-uri` - Get ID and version

2. **`:document-repo/document` coeffect** - Made 2 separate queries:
   - `doc-text-lang-by-uri` - Get text and language
   - `document-id-version-by-uri` - Get ID and version

3. **`handle-publish-diagnostics`** - Made 2 separate queries:
   - `active-version` - Get active document version
   - `active-uri` - Get active document URI

Each query incurs:
- DataScript query compilation overhead
- Index traversal overhead
- Result materialization overhead

## Implementation

### New Coalesced Query Functions

Added to `src/lib/db.cljs`:

```clojure
(defn active-uri-version
  "Returns [uri version] for the active document in a single query."
  []
  (d/q '[:find [?uri ?version]
         :where [?a :workspace/active-uri ?uri]
                [?e :document/uri ?uri]
                [?e :document/version ?version]]
       @conn))

(defn active-uri-text-lang-version
  "Returns [uri text lang version] for the active document in a single query."
  []
  (d/q '[:find [?uri ?text ?lang ?version]
         :where [?a :workspace/active-uri ?uri]
                [?e :document/uri ?uri]
                [?e :document/text ?text]
                [?e :document/language ?lang]
                [?e :document/version ?version]]
       @conn))

(defn doc-text-lang-version-by-uri
  "Returns [text lang version] for a document by URI in a single query."
  [uri]
  (d/q '[:find [?text ?lang ?version]
         :in $ ?uri
         :where [?e :document/uri ?uri]
                [?e :document/text ?text]
                [?e :document/language ?lang]
                [?e :document/version ?version]]
       @conn uri))
```

### Updated Callers

1. **`src/app/cofx.cljs`**:
   - `:document-repo/active-document` - Now uses `active-uri-text-lang-version` (1 query instead of 3)
   - `:document-repo/document` - Now uses `doc-text-lang-version-by-uri` (1 query instead of 2)

2. **`src/lib/lsp/client.cljs`**:
   - `handle-publish-diagnostics` - Now uses `active-uri-version` (1 query instead of 2)

### Files Modified

| File | Changes |
|------|---------|
| `src/lib/db.cljs` | Added 3 coalesced query functions |
| `src/app/cofx.cljs` | Updated 2 coeffects to use coalesced queries |
| `src/lib/lsp/client.cljs` | Updated diagnostics handler to use coalesced query |
| `src/lib/perf/benchmark_tests.cljs` | Added coalescence benchmarks |

## Results

### Coalesced vs Sequential Query Performance

| Comparison | Coalesced (mean) | Sequential (mean) | Improvement |
|------------|------------------|-------------------|-------------|
| `active-uri-version` vs `active-uri + active-version` | 0.744ms | 1.024ms | **-27.3%** |
| `active-uri-text-lang-version` vs 3 sequential queries | 1.115ms | 1.743ms | **-36.0%** |
| `doc-text-lang-version` vs 2 sequential queries | 1.046ms | 1.367ms | **-23.5%** |

### Statistical Summary

| Metric | Coalesced URI+Version | Sequential URI+Version |
|--------|----------------------|------------------------|
| Mean | 0.744ms | 1.024ms |
| Median | 0.700ms | 1.000ms |
| P95 | 0.900ms | 1.200ms |
| Std Dev | 0.072ms | 0.247ms |

| Metric | Coalesced Full Context | Sequential Full Context |
|--------|------------------------|-------------------------|
| Mean | 1.115ms | 1.743ms |
| Median | 1.100ms | 1.600ms |
| P95 | 1.200ms | 2.300ms |
| Std Dev | 0.217ms | 0.677ms |

## Analysis

### Why Coalescence Works

1. **Single Query Compilation**: One query plan instead of 2-3
2. **Single Index Traversal**: DataScript only traverses indexes once
3. **Reduced Function Call Overhead**: Fewer ClojureScript function invocations
4. **Better JIT Optimization**: Hot path is a single function

### Impact on Real Workloads

- **Coeffect Injection**: Every Re-Frame event that injects `:document-repo/active-document` now executes 67% fewer queries
- **Diagnostic Publishing**: Each LSP diagnostic notification executes 50% fewer queries
- **Cumulative Effect**: During typical editing (many events per second), this compounds significantly

### No Regressions

- All 372 tests pass
- Existing benchmark metrics unchanged within variance

## Verification

### Statistical Validation

- All improvements exceed 20% threshold
- Consistent results across 100 iterations per benchmark
- Low variance indicates reliable measurements

### Functional Verification

- [x] Compilation successful (shadow-cljs)
- [x] All 372 tests pass
- [x] Benchmark suite runs to completion
- [x] Coeffects return identical data structures

## Decision

**ACCEPTED**

The experiment demonstrates significant performance improvements (23-36% faster) with no regressions. The implementation:
- Reduces query count by 50-67% for affected operations
- Follows existing coalesced query patterns in the codebase
- Is a straightforward, low-risk optimization

## Future Optimizations

1. **Additional Coalescence Opportunities**:
   - `active-text` + `active-uri` in content sync (core.cljs:986-997)
   - `active-uri` + `active-lang` in document activation (core.cljs:346-347)

2. **Coeffect Caching**: Consider caching coeffect results within a single event cycle

## Commit Information

```
perf(db): Add coalesced queries for active document access

Experiment: EXP-007
Hypothesis: Coalescing sequential queries will reduce overhead by 20-40%

Results:
- active-uri-version: 27% faster (1.024ms → 0.744ms)
- active-uri-text-lang-version: 36% faster (1.743ms → 1.115ms)
- doc-text-lang-version: 24% faster (1.367ms → 1.046ms)

Changes:
- Add 3 new coalesced query functions to db.cljs
- Update :document-repo/active-document coeffect (3 queries → 1)
- Update :document-repo/document coeffect (2 queries → 1)
- Update handle-publish-diagnostics (2 queries → 1)

Decision: ACCEPT

Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>
```
