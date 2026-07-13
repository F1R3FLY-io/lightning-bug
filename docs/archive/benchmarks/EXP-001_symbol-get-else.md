# EXP-001: Replace Symbol Query or-join with get-else

**Date:** 2026-01-21
**Status:** REJECTED
**Git Branch:** experiment/exp-001-symbol-parent-default

## Hypothesis

Replacing the `or-join` clause in symbol queries with DataScript's `get-else` function would improve query performance by eliminating the multi-branch query execution overhead.

**Rationale:** `or-join` creates separate execution paths that must be evaluated and merged. `get-else` is a function call that provides a default value for missing attributes, potentially avoiding the branch overhead.

**Expected outcome:** 30-50% reduction in symbol query times

## Implementation

Changed symbol queries from:
```clojure
(or-join [?e ?parent]
  [?e :symbol/parent ?parent]
  (and [(missing? $ ?e :symbol/parent)]
       [(ground 0) ?parent]))
```

To:
```clojure
[(get-else $ ?e :symbol/parent 0) ?parent]
```

## Results

### Symbol Query Performance

| Metric | Baseline | Experiment | Change | Decision |
|--------|----------|------------|--------|----------|
| symbols-by-uri (mean) | 10.02 ms | 13.85 ms | **+38.2%** | REJECT |
| all-symbols (mean) | 9.33 ms | 13.09 ms | **+40.2%** | REJECT |

### Statistical Analysis

**symbols-by-uri:**
- Welch's t-test: t=36.74, p<0.0001
- Cohen's d: 5.52 (large effect)
- Significant regression confirmed

**all-symbols:**
- Welch's t-test: t=79.47, p<0.0001
- Cohen's d: 11.59 (large effect)
- Significant regression confirmed

## Analysis

The `get-else` approach is **significantly slower** than `or-join`. This is contrary to expectations.

### Possible explanations:

1. **`get-else` implementation overhead**: The function call may have higher per-invocation cost than the `or-join` branch pruning
2. **Query optimizer**: DataScript may optimize `or-join` patterns better than arbitrary function calls
3. **Index utilization**: `or-join` with direct attribute patterns may leverage indices more effectively
4. **Result materialization**: `get-else` may trigger different code paths for result construction

## Conclusion

**Decision: REJECT**

The hypothesis was disproven. `get-else` is not a viable replacement for `or-join` in symbol queries. The `or-join` approach, while potentially inefficient in theory, performs significantly better in practice on this workload.

## Next Steps

Consider alternative optimization approaches:
1. Use `d/pull` or `d/pull-many` instead of queries
2. Cache query results at application level
3. Denormalize data to avoid optional field handling
4. Profile at lower level to understand actual bottleneck

## Lessons Learned

- Always benchmark before and after optimization attempts
- DataScript query optimizer behavior may not match expectations
- "Simpler" query patterns don't always translate to better performance
