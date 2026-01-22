# Baseline Benchmark: [DATE]

## Environment

### Hardware

| Component | Specification |
|-----------|---------------|
| CPU | Intel Xeon E5-2699 v3 @ 2.30GHz |
| Cores | 36 physical (72 threads) |
| RAM | 252 GB DDR4-2133 ECC |
| Storage | Samsung 990 PRO 4TB NVMe |

### Software

| Component | Version |
|-----------|---------|
| Node.js | [VERSION] |
| Chrome | [VERSION] |
| shadow-cljs | 3.2.0 |
| ClojureScript | [VERSION] |

### System State

| Setting | Value |
|---------|-------|
| CPU Governor | performance |
| Turbo Boost | disabled |
| CPU Affinity | core 0 |
| System Load | [LOAD AVG] |

### Git State

- **Commit**: `[HASH]`
- **Branch**: `main`
- **Status**: clean

## Benchmark Configuration

```clojure
{:warmup-iterations 10
 :measurement-iterations 100
 :min-sample-size 30
 :confidence-level 0.95
 :significance-threshold 0.05
 :gc-between-iterations? true
 :delay-between-iterations-ms 10}
```

## Results

### Summary Table

| Benchmark | Mean (ms) | Median (ms) | P95 (ms) | P99 (ms) | Target (ms) | Status |
|-----------|-----------|-------------|----------|----------|-------------|--------|
| timing-overhead | - | - | - | - | - | - |
| datascript-diagnostics-by-uri | - | - | - | - | 5 | - |
| datascript-symbols-by-uri | - | - | - | - | 5 | - |
| datascript-all-diagnostics | - | - | - | - | 5 | - |
| datascript-all-symbols | - | - | - | - | 5 | - |
| diagnostic-transform | - | - | - | - | 10 | - |
| symbol-flatten | - | - | - | - | 10 | - |

### Detailed Results

#### timing-overhead

Baseline timing overhead for the measurement infrastructure.

```
Count:    100
Min:      [X] ms
Max:      [X] ms
Mean:     [X] ms
Median:   [X] ms
P95:      [X] ms
P99:      [X] ms
Std Dev:  [X] ms
```

#### datascript-diagnostics-by-uri

Query for diagnostics by document URI.

```
Count:    100
Min:      [X] ms
Max:      [X] ms
Mean:     [X] ms
Median:   [X] ms
P95:      [X] ms
P99:      [X] ms
Std Dev:  [X] ms
Target:   5 ms
Status:   [PASS/FAIL]
```

[Continue for each benchmark...]

## Observations

- [Initial observations about performance]
- [Notable patterns or concerns]
- [Comparison to targets]

## Next Steps

1. Profile identified bottlenecks
2. Prioritize optimization candidates
3. Design experiments

## Raw Data

See `docs/benchmarks/results/baseline.json` for complete machine-readable results.
