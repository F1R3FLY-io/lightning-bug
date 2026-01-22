# Experiment EXP-XXX: [Brief Description]

## Metadata

| Field | Value |
|-------|-------|
| ID | EXP-XXX |
| Date | YYYY-MM-DD |
| Branch | `experiment/exp-XXX-description` |
| Status | PENDING / ACCEPTED / REJECTED |

## Hypothesis

**Statement**: [Clear, testable hypothesis statement]

**Rationale**: [Why this optimization might work]

**Expected Improvement**: [Quantified prediction, e.g., "10-20% reduction in P95 latency"]

**Target Metric(s)**: [Which benchmark metrics should improve]

## Background

### Current Behavior

[Describe the current implementation and its performance characteristics]

### Identified Bottleneck

[What profiling/analysis revealed about the bottleneck]

### Proposed Solution

[High-level description of the optimization approach]

## Implementation

### Files Modified

| File | Changes |
|------|---------|
| `src/lib/[file].cljs` | [Brief description] |

### Code Changes

```clojure
;; Before
[relevant code snippet]

;; After
[optimized code snippet]
```

### Testing

- [ ] Unit tests pass
- [ ] Integration tests pass
- [ ] Type check passes
- [ ] Lint passes
- [ ] Demo runs correctly

## Benchmark Results

### Baseline (Pre-optimization)

| Metric | Mean | Median | P95 | P99 |
|--------|------|--------|-----|-----|
| [target-metric] | X.XX ms | X.XX ms | X.XX ms | X.XX ms |

### Experiment (Post-optimization)

| Metric | Mean | Median | P95 | P99 |
|--------|------|--------|-----|-----|
| [target-metric] | X.XX ms | X.XX ms | X.XX ms | X.XX ms |

### Statistical Analysis

| Test | Value | Interpretation |
|------|-------|----------------|
| Welch's t-statistic | X.XXXX | |
| p-value | X.XXXX | [Significant/Not Significant] |
| Cohen's d | X.XXXX | [negligible/small/medium/large] |
| 95% CI (lower) | X.XXXX ms | |
| 95% CI (upper) | X.XXXX ms | |

### Improvement

| Metric | Baseline P95 | Experiment P95 | Change |
|--------|--------------|----------------|--------|
| [target-metric] | X.XX ms | X.XX ms | -XX.X% |

### Regression Check

| Metric | Status |
|--------|--------|
| [other-metric-1] | No regression |
| [other-metric-2] | No regression |

## Decision

### Criteria Evaluation

| Criterion | Required | Actual | Pass/Fail |
|-----------|----------|--------|-----------|
| Statistical significance | p < 0.05 | p = X.XXXX | |
| Meaningful effect size | \|d\| >= 0.2 | d = X.XXXX | |
| No regressions | None | [Status] | |

### Final Decision: **[ACCEPT / REJECT]**

**Justification**: [Explanation of the decision]

## Commit Information

```
perf([component]): [Brief description]

Experiment: EXP-XXX
Hypothesis: [One-line summary]

Baseline P95: X.XX ms
Result P95: X.XX ms
Improvement: X.X%
p-value: X.XXXX
Decision: [ACCEPT/REJECT]

Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>
```

## Lessons Learned

- [What worked]
- [What didn't work]
- [Insights for future optimizations]

## References

- Baseline results: `docs/benchmarks/results/baseline.json`
- Experiment results: `docs/benchmarks/results/exp-XXX.json`
- Profiling data: [link if applicable]
