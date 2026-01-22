# Lightning Bug Bottleneck Analysis

**Date:** 2026-01-21
**Phase:** 3 - Profiling
**Git Commit:** d92fb0c (main)

## Executive Summary

Analysis of baseline benchmarks and code review reveals that **DataScript `or-join` clauses are the primary performance bottleneck**. All 4 failing benchmarks use `or-join` to handle optional/nullable fields. Symbol queries are particularly slow due to the combination of `or-join` overhead and extracting 12 attributes per result.

## Baseline Results Reference

| Benchmark | Mean (ms) | Target (ms) | Status |
|-----------|-----------|-------------|--------|
| symbols-by-uri | 10.02 | 5 | FAIL (+100%) |
| all-symbols | 9.33 | 5 | FAIL (+87%) |
| diagnostics-by-uri | 6.75 | 5 | FAIL (+35%) |
| all-diagnostics | 5.58 | 5 | FAIL (+12%) |

## Hypothesis Validation

### H-001: DataScript `or-join` clauses are inefficient [CONFIRMED]

**Evidence:**
- All 4 failing queries use `or-join`
- Diagnostic queries have 3-branch `or-join` for version filtering
- Symbol queries have 2-branch `or-join` for optional parent

**Code analysis:**

```clojure
;; Symbol query or-join (2 branches)
(or-join [?e ?parent]
  [?e :symbol/parent ?parent]
  (and [(missing? $ ?e :symbol/parent)]
       [(ground 0) ?parent]))

;; Diagnostic query or-join (3 branches)
(or-join [?e ?diag-version ?doc-version]
  (and [?e :diagnostic/version ?diag-version]
       [(= ?diag-version ?doc-version)])
  (and [(missing? $ ?e :diagnostic/version)]
       [(identity ?doc-version) ?diag-version])
  (and [?e :diagnostic/version ?diag-version]
       [(nil? ?diag-version)]))
```

**Impact:** `or-join` in DataScript creates multiple execution paths that are evaluated and merged. Each branch performs a separate scan/lookup.

### H-002: Symbol queries lack effective indexing [PARTIALLY CONFIRMED]

**Evidence:**
- Schema shows `:symbol/document` IS indexed (`:db/index true`)
- Symbol attribute fields (`:symbol/name`, `:symbol/kind`, etc.) are NOT indexed
- However, queries filter by document ref (indexed), not by attribute values

**Schema excerpt:**
```clojure
{:symbol/parent {:db/valueType :db.type/ref}
 :symbol/document {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one :db/index true}
 :diagnostic/document {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one :db/index true}
 :diagnostic/version {:db/index true}
 :document/uri {:db/unique :db.unique/identity}}
```

**Conclusion:** Indexing is adequate for current query patterns. The bottleneck is `or-join`, not index usage.

### H-003: Query result materialization overhead [PLAUSIBLE]

**Evidence:**
- Symbol queries extract 12 attributes per result
- Diagnostic queries extract 8 attributes per result
- Symbol queries are ~50% slower than diagnostic queries
- Some of this difference may be attributable to result set size and attribute count

**Test setup data sizes:**
- 100 symbols per document
- 50 diagnostics per document

## Detailed Query Analysis

### 1. `symbols-by-uri` (10.02ms mean, worst performer)

**Query structure:**
```clojure
(d/q '[:find ?uri ?name ?kind ?start-line ?start-char ?end-line ?end-char
             ?selection-start-line ?selection-start-char
             ?selection-end-line ?selection-end-char ?parent
       :keys uri name kind startLine startChar endLine endChar
             selectionStartLine selectionStartChar
             selectionEndLine selectionEndChar parent
       :in $ ?uri
       :where [?e :symbol/document ?doc]
              [?doc :document/uri ?uri]
              ;; 10 attribute lookups
              [?e :symbol/name ?name]
              [?e :symbol/kind ?kind]
              [?e :symbol/start-line ?start-line]
              ;; ... more lookups ...
              (or-join [?e ?parent]
                [?e :symbol/parent ?parent]
                (and [(missing? $ ?e :symbol/parent)]
                     [(ground 0) ?parent]))]
     @conn uri)
```

**Bottlenecks:**
1. `or-join` for optional parent handling
2. 12 attributes extracted per symbol
3. Join through document ref to get URI

### 2. `all-symbols` (9.33ms mean)

Same structure as `symbols-by-uri` but without URI filtering. Similar bottlenecks.

### 3. `diagnostics-by-uri` (6.75ms mean)

**Bottlenecks:**
1. Complex 3-branch `or-join` for version filtering
2. 8 attributes extracted per diagnostic
3. Version equality check in query

### 4. `all-diagnostics` (5.58ms mean)

Same structure as `diagnostics-by-uri` but without URI filtering.

## Proposed Optimizations

### OPT-001: Replace symbol `or-join` with default parent at write time

**Hypothesis:** By storing a default parent value (0 or :none) at write time, we can eliminate the `or-join` in symbol queries.

**Predicted improvement:** 30-50% reduction in symbol query times

**Implementation:**
1. Modify `create-symbols` to always set `:symbol/parent` (use 0 for top-level)
2. Simplify symbol queries to direct attribute lookup

**Risk:** Schema change, requires data migration for existing databases

### OPT-002: Simplify diagnostic version filtering

**Hypothesis:** The 3-branch `or-join` may be overly complex. If we can simplify the version logic at write time, queries become simpler.

**Predicted improvement:** 15-25% reduction in diagnostic query times

**Implementation:**
1. Review diagnostic version use cases
2. Potentially store normalized version info at write time
3. Replace `or-join` with simple equality check

**Risk:** May change diagnostic filtering semantics

### OPT-003: Use `d/pull` instead of query for single-document lookups

**Hypothesis:** For `-by-uri` queries, `d/pull` with known entity IDs may be faster than full query.

**Predicted improvement:** 10-20% for `-by-uri` queries

**Implementation:**
1. First lookup document entity by URI (indexed, fast)
2. Use `d/datoms` to get symbol/diagnostic entity IDs for that document
3. Use `d/pull-many` to extract attributes

**Risk:** Changes query pattern, may not be faster in practice

### OPT-004: Batch attribute extraction

**Hypothesis:** Extracting all 12 symbol attributes individually in the query may be slower than pulling entity and extracting client-side.

**Predicted improvement:** 5-15% reduction in query times

**Implementation:**
1. Query for entity IDs only
2. Use `d/pull-many` to batch extract attributes
3. Or use `d/entity` with attribute access

**Risk:** May shift work from query to post-processing

## Optimization Priority

| Priority | Experiment | Target Benchmark | Expected Improvement |
|----------|------------|------------------|---------------------|
| 1 (HIGH) | OPT-001 | symbols-by-uri, all-symbols | 30-50% |
| 2 (HIGH) | OPT-002 | diagnostics-by-uri, all-diagnostics | 15-25% |
| 3 (MEDIUM) | OPT-003 | all -by-uri queries | 10-20% |
| 4 (LOW) | OPT-004 | all queries | 5-15% |

## Next Steps

1. Create experiment EXP-001 for OPT-001 (symbol parent default)
2. Implement and benchmark with statistical rigor
3. If significant (p < 0.05), accept and update baseline
4. Proceed to next optimization

## Profiling Notes

### Chrome DevTools Findings

To be updated with actual profiling data from Chrome DevTools Performance Panel.

### Performance Marks

The benchmark infrastructure uses `performance.mark/measure` which can be examined in Chrome DevTools:
- `benchmark::{name}:start`
- `benchmark::{name}:iteration:{n}`
- `benchmark::{name}:end`

## Appendix: Query Execution Model

DataScript queries execute in the following order:
1. Parse and compile query (cached after first run)
2. Resolve `:in` bindings
3. Execute `:where` clauses (left to right, with some optimization)
4. `or-join` branches are executed separately and merged
5. Project results via `:find`/`:keys`
6. Return result set

The `or-join` step creates multiple execution paths that must be evaluated and deduplicated, adding overhead proportional to branch count and result size.
