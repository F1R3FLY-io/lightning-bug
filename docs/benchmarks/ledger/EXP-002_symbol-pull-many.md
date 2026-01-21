# EXP-002: Use d/pull-many for Batch Symbol Extraction

**Date:** 2026-01-21
**Status:** ACCEPTED
**Git Branch:** experiment/exp-002-symbol-pull-many
**Merged to:** main

## Hypothesis

Using DataScript's `d/pull-many` for batch attribute extraction will be faster than
complex queries with `or-join` clauses because:
1. Simple queries that return only entity IDs are fast
2. `d/pull-many` efficiently batches attribute extraction
3. We avoid the `or-join` overhead entirely

## Expected Improvement

- Target: 50%+ improvement in symbol query times
- Primary metrics: `symbols-by-uri`, `all-symbols`

## Implementation

### Changes Made

**File:** `src/lib/db.cljs`

1. Added pull patterns for symbol attributes:
```clojure
(def ^:private symbol-pull-pattern
  [:symbol/name :symbol/kind
   :symbol/start-line :symbol/start-char
   :symbol/end-line :symbol/end-char
   :symbol/selection-start-line :symbol/selection-start-char
   :symbol/selection-end-line :symbol/selection-end-char
   :symbol/parent])

(def ^:private symbol-pull-pattern-with-doc
  (conj symbol-pull-pattern {:symbol/document [:document/uri]}))
```

2. Added transform functions:
```clojure
(defn- transform-pulled-symbol [uri entity]
  {:uri uri
   :name (:symbol/name entity)
   :kind (:symbol/kind entity)
   :startLine (:symbol/start-line entity)
   :startChar (:symbol/start-char entity)
   :endLine (:symbol/end-line entity)
   :endChar (:symbol/end-char entity)
   :selectionStartLine (:symbol/selection-start-line entity)
   :selectionStartChar (:symbol/selection-start-char entity)
   :selectionEndLine (:symbol/selection-end-line entity)
   :selectionEndChar (:symbol/selection-end-char entity)
   :parent (or (:symbol/parent entity) 0)})
```

3. Modified `symbols-by-uri`:
```clojure
(defn symbols-by-uri [uri]
  (let [entity-ids (d/q '[:find [?e ...]
                          :in $ ?uri
                          :where [?doc :document/uri ?uri]
                                 [?e :symbol/document ?doc]]
                        @conn uri)]
    (when (seq entity-ids)
      (mapv #(transform-pulled-symbol uri %)
            (d/pull-many @conn symbol-pull-pattern entity-ids)))))
```

4. Modified `symbols` (all-symbols):
```clojure
(defn symbols []
  (let [entity-ids (d/q '[:find [?e ...]
                          :where [?e :type :symbol]]
                        @conn)]
    (when (seq entity-ids)
      (mapv transform-pulled-symbol-with-doc
            (d/pull-many @conn symbol-pull-pattern-with-doc entity-ids)))))
```

## Results

### Symbol Query Performance (Primary Goal)

| Benchmark | Baseline | Experiment | Change | p-value | Cohen's d |
|-----------|----------|------------|--------|---------|-----------|
| symbols-by-uri | 10.02ms | 3.77ms | **-62.3%** | 0.0000 | -12.31 |
| all-symbols | 9.33ms | 3.93ms | **-57.9%** | 0.0000 | -23.51 |

### P95 vs Target

| Benchmark | Baseline P95 | Experiment P95 | Target | Status |
|-----------|--------------|----------------|--------|--------|
| symbols-by-uri | 11.30ms | 4.10ms | 5ms | **PASS** |
| all-symbols | 9.80ms | 4.10ms | 5ms | **PASS** |

### Secondary Metrics (Not Modified)

| Benchmark | Baseline | Experiment | Change | Notes |
|-----------|----------|------------|--------|-------|
| diagnostics-by-uri | 6.75ms | 6.77ms | +0.2% | No significant change |
| all-diagnostics | 5.58ms | 6.05ms | +8.4% | Minor regression |
| document-lookup | 0.47ms | 0.50ms | +5.7% | Negligible |
| active-uri | 0.35ms | 0.36ms | +2.6% | Trivial |
| symbol-flatten | 1.14ms | 1.22ms | +6.5% | Minor regression |
| diagnostic-transform | 0.02ms | 0.00ms | 0% | No change |
| timing-overhead | 0.00ms | 0.00ms | 0% | No change |

### Statistical Significance

All symbol query improvements are statistically significant:
- **symbols-by-uri**: t=-87.03, p=0.0000, Cohen's d=-12.31 (large effect)
- **all-symbols**: t=-165.67, p=0.0000, Cohen's d=-23.51 (large effect)

## Decision: ACCEPT

**Rationale:**
1. Primary optimization target achieved: symbol queries now meet 5ms target
2. Massive improvement: 60%+ faster with extremely high statistical confidence
3. Minor regressions in unmodified code (all-diagnostics +8.4%) can be addressed separately
4. The performance gains far outweigh the minor side effects

## Analysis

### Why This Works

1. **Simple ID Query**: The query `[:find [?e ...] :where [?e :type :symbol]]` is extremely fast
   because it only needs to find matching entities without extracting attributes.

2. **Batch Pull**: `d/pull-many` efficiently extracts attributes for multiple entities in a single
   operation, leveraging DataScript's internal indexing.

3. **No or-join**: By handling the optional `:symbol/parent` attribute in the transform function
   (with `(or (:symbol/parent entity) 0)`), we completely avoid the `or-join` overhead.

### Trade-offs

- **Pro**: 60%+ faster symbol queries
- **Pro**: Symbol queries now meet 5ms target
- **Con**: Minor regression in all-diagnostics (+8.4%)
- **Con**: Slightly more code (transform functions)

### All-Diagnostics Regression Investigation

The 8.4% regression in `all-diagnostics` is unexpected since this query was not modified.
Possible causes:
1. Cache/memory effects from different query patterns
2. Garbage collection timing differences
3. Measurement variance (8.4% is relatively small)

This should be investigated in a separate experiment if it persists.

## Configuration

```clojure
{:warmup-iterations 10
 :measurement-iterations 100
 :min-sample-size 30
 :confidence-level 0.95
 :significance-threshold 0.05
 :gc-between-iterations? true
 :delay-between-iterations-ms 10}
```

## Files Modified

- `src/lib/db.cljs` - Added pull patterns and transform functions, modified `symbols-by-uri` and `symbols`

## Lessons Learned

1. DataScript `or-join` is a significant performance bottleneck for queries with optional attributes
2. The "query for IDs, then pull attributes" pattern can be much faster than complex queries
3. Handling optional attributes in post-processing is more efficient than in the query itself
4. The `d/pull-many` API is an effective tool for batch data extraction
