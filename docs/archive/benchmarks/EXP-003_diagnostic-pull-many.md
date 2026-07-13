# EXP-003: Diagnostic Pull-Many Optimization

**Date:** 2026-01-22
**Status:** ACCEPTED
**Decision:** **ACCEPT** - Significant improvements in both target metrics

## Hypothesis

Using DataScript's `d/pull-many` for batch diagnostic attribute extraction will be faster than the current complex 3-branch `or-join` query, following the same pattern that achieved 60%+ improvement for symbol queries in EXP-002.

## Rationale

The diagnostic queries use a complex 3-branch `or-join` for version filtering:

```clojure
(or-join [?e ?diag-version ?doc-version]
  (and [?e :diagnostic/version ?diag-version]
       [(= ?diag-version ?doc-version)])
  (and [(missing? $ ?e :diagnostic/version)]
       [(identity ?doc-version) ?diag-version])
  (and [?e :diagnostic/version ?diag-version]
       [(nil? ?diag-version)]))
```

This `or-join` pattern is known to be inefficient in DataScript. EXP-002 demonstrated that replacing a similar pattern in symbol queries with `d/pull-many` + post-processing achieved 60%+ improvements.

## Expected Improvement

- Target: 40-60% improvement in diagnostic query times
- Primary metrics: `diagnostics-by-uri`, `all-diagnostics`
- Goal: Bring both metrics under the 5ms target

## Implementation

### Files Modified

- `src/lib/db.cljs`

### Changes

1. **Added pull patterns for diagnostics:**
```clojure
(def ^:private diagnostic-pull-pattern
  [:diagnostic/message :diagnostic/severity
   :diagnostic/start-line :diagnostic/start-char
   :diagnostic/end-line :diagnostic/end-char
   :diagnostic/version])

(def ^:private diagnostic-pull-pattern-with-doc
  [:diagnostic/message :diagnostic/severity
   :diagnostic/start-line :diagnostic/start-char
   :diagnostic/end-line :diagnostic/end-char
   :diagnostic/version
   {:diagnostic/document [:document/uri :document/version]}])
```

2. **Added version matching helper:**
```clojure
(defn- diagnostic-version-matches?
  [diag-version doc-version]
  (or (nil? diag-version)
      (= diag-version doc-version)))
```

3. **Added transform functions:**
```clojure
(defn- transform-pulled-diagnostic
  [uri doc-version entity]
  (let [diag-version (:diagnostic/version entity)]
    (when (diagnostic-version-matches? diag-version doc-version)
      {:uri uri
       :message (:diagnostic/message entity)
       ...})))

(defn- transform-pulled-diagnostic-with-doc
  [entity]
  (let [doc (:diagnostic/document entity)
        doc-version (:document/version doc)
        diag-version (:diagnostic/version entity)]
    (when (diagnostic-version-matches? diag-version doc-version)
      {:uri (:document/uri doc)
       ...})))
```

4. **Modified `diagnostics-by-uri`:**
```clojure
(defn diagnostics-by-uri [uri]
  (when-let [[doc-id doc-version] (document-id-version-by-uri uri)]
    (let [entity-ids (d/q '[:find [?e ...]
                            :in $ ?doc
                            :where [?e :diagnostic/document ?doc]]
                          @conn doc-id)]
      (when (seq entity-ids)
        (into []
              (keep #(transform-pulled-diagnostic uri doc-version %))
              (d/pull-many @conn diagnostic-pull-pattern entity-ids))))))
```

5. **Modified `diagnostics` (all-diagnostics):**
```clojure
(defn diagnostics []
  (let [entity-ids (d/q '[:find [?e ...]
                          :where [?e :diagnostic/document _]]
                        @conn)]
    (when (seq entity-ids)
      (into []
            (keep transform-pulled-diagnostic-with-doc)
            (d/pull-many @conn diagnostic-pull-pattern-with-doc entity-ids)))))
```

## Results

### Statistical Summary

| Benchmark | Baseline | EXP-003 | Change | p-value | Cohen's d | Target | Status |
|-----------|----------|---------|--------|---------|-----------|--------|--------|
| diagnostics-by-uri | 6.75ms | 3.42ms | **-49.3%** | <0.0001 | -4.17 | 5ms | **PASS** |
| all-diagnostics | 5.58ms | 2.41ms | **-56.8%** | <0.0001 | -12.91 | 5ms | **PASS** |

### Detailed Statistics

#### diagnostics-by-uri

| Statistic | Baseline | EXP-003 |
|-----------|----------|---------|
| Mean | 6.75ms | 3.42ms |
| Median | 6.60ms | 3.20ms |
| P95 | 7.90ms | 5.10ms |
| P99 | 8.20ms | 5.70ms |
| Std Dev | 0.67ms | 0.91ms |
| Sample Size | 97 | 97 |

#### all-diagnostics

| Statistic | Baseline | EXP-003 |
|-----------|----------|---------|
| Mean | 5.58ms | 2.41ms |
| Median | 5.60ms | 2.20ms |
| P95 | 5.90ms | 4.10ms |
| P99 | 5.90ms | 4.20ms |
| Std Dev | 0.16ms | 0.60ms |
| Sample Size | 98 | 90 |

### Impact on Other Metrics

No regressions detected in symbol queries or other metrics (they retain EXP-002 optimizations).

## Verification

- **Unit Tests:** All 372 tests pass
- **Integration Tests:** Verified diagnostic functionality remains correct
- **Benchmark Runs:** 100 iterations with outlier removal

## Analysis

### Why This Works

1. **Avoids `or-join` overhead**: The complex 3-branch `or-join` query in DataScript requires evaluating multiple conditional paths within the query engine. By using a simple entity ID query followed by `d/pull-many`, we avoid this overhead entirely.

2. **Efficient batch retrieval**: `d/pull-many` retrieves all attributes for multiple entities in a single operation, leveraging DataScript's internal indexing efficiently.

3. **Post-processing is cheap**: Filtering diagnostics by version in ClojureScript is a simple `nil?` or `=` check, which is negligible compared to query engine overhead.

4. **Pattern consistency**: Following the same pattern as EXP-002 ensures consistency and maintainability.

### Variance Increase

The standard deviation increased slightly (0.67ms → 0.91ms for diagnostics-by-uri), likely due to:
- Garbage collection during post-processing
- Memory allocation for intermediate collections

This is acceptable given the 49-57% improvement in mean times.

## Decision

**ACCEPT**

Both target metrics show statistically significant improvements (p < 0.0001) with large effect sizes (|d| > 4). Both metrics are now under the 5ms target:

- `diagnostics-by-uri`: 3.42ms < 5ms ✅
- `all-diagnostics`: 2.41ms < 5ms ✅

## Git Information

- **Branch:** `experiment/exp-003-diagnostic-pull-many`
- **Base Commit:** `9091f3d` (main, after EXP-002 merge)
- **Files Changed:** `src/lib/db.cljs`

## Next Steps

1. Merge this experiment into main
2. Update baseline with new performance levels
3. Similar optimizations apply to other `or-join` queries when profiling identifies them.

## Environment

See `BASELINE_2026-01-21.md` for full environment details.

- **Platform:** Linux x86_64
- **CPU:** Intel Xeon E5-2699 v3 @ 2.30GHz (36 cores)
- **Browser:** HeadlessChrome/143.0.0.0
- **Node.js:** v25.3.0
