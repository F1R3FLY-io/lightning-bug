# Baseline Template

Copy this template to record a **baseline** measurement — the pinned reference a later experiment is
compared against. Replace every `[bracketed]` placeholder with a real value; do **not** commit
unfilled template text. See the [experiment guide](experiment-guide.md) for the surrounding workflow
and [README.md](README.md#statistical-methodology) for the statistics.

---

## Baseline: `[YYYY-MM-DD]`

### Environment

**Hardware** (reference machine for the recorded baselines):

| Component | Specification |
|-----------|---------------|
| CPU | Intel Xeon E5-2699 v3 @ 2.30 GHz |
| Cores | 36 physical (72 threads) |
| RAM | 252 GB DDR4-2133 ECC |
| Storage | Samsung 990 PRO 4 TB NVMe |

**Software:**

| Component | Version |
|-----------|---------|
| Library | `[X.Y.Z]` |
| Node.js | `[version]` |
| Chrome | `[version]` |
| shadow-cljs | 3.2.0 |
| ClojureScript | `[version]` |

**System state:**

| Setting | Value |
|---------|-------|
| CPU governor | `[performance / other]` |
| Turbo Boost | `[disabled / enabled]` |
| CPU affinity (`taskset`) | `[core N / unavailable]` |
| System load | `[load avg]` |

**Git state:** commit `[short-sha]`, branch `[branch]`, status `[clean/dirty]`.

### Benchmark configuration

```clojure
{:warmup-iterations 10
 :measurement-iterations 100
 :min-sample-size 30
 :confidence-level 0.95
 :significance-threshold 0.05
 :gc-between-iterations? true
 :delay-between-iterations-ms 10}
```

### Results

One row per benchmark in the suite. Times in milliseconds; `$`P_{95}`$`/`$`P_{99}`$` are the 95th and
99th percentiles.

| Benchmark | Mean | Median | `$`P_{95}`$` | `$`P_{99}`$` | Std. dev | Target | Status |
|-----------|-----:|-------:|-----:|-----:|--------:|-------:|:------:|
| `timing-overhead` | `[…]` | `[…]` | `[…]` | `[…]` | `[…]` | — | — |
| `datascript-diagnostics-by-uri` | `[…]` | `[…]` | `[…]` | `[…]` | `[…]` | 5 | `[✓/✗]` |
| `datascript-symbols-by-uri` | `[…]` | `[…]` | `[…]` | `[…]` | `[…]` | 5 | `[✓/✗]` |
| `datascript-all-diagnostics` | `[…]` | `[…]` | `[…]` | `[…]` | `[…]` | 5 | `[✓/✗]` |
| `datascript-all-symbols` | `[…]` | `[…]` | `[…]` | `[…]` | `[…]` | 5 | `[✓/✗]` |
| `diagnostic-transform` | `[…]` | `[…]` | `[…]` | `[…]` | `[…]` | 10 | `[✓/✗]` |
| `symbol-flatten` | `[…]` | `[…]` | `[…]` | `[…]` | `[…]` | 10 | `[✓/✗]` |
| `[additional benchmark]` | `[…]` | `[…]` | `[…]` | `[…]` | `[…]` | `[…]` | `[✓/✗]` |

### Observations

- `[Which benchmarks meet target; which are the current bottlenecks.]`
- `[Notable variance or patterns; note if pinning was unavailable → lower precision.]`
- `[Link the profiling data that motivates the next experiment.]`

### How this baseline was produced

```bash
npm run benchmark:prepare   # sudo — CPU governor + affinity (skip if unavailable)
npm run benchmark:baseline  # writes docs/benchmarks/results/baseline.json
npm run benchmark:restore   # sudo — restore system settings
```

Raw machine-readable data: `docs/benchmarks/results/baseline.json`.

---

## Comparison scaffold (for the paired experiment)

When an experiment is run against this baseline, record the paired statistics per metric (see the
[experiment guide](experiment-guide.md#benchmark-results) and the
[decision rule](README.md#decision-rule)):

| Benchmark | Baseline `$`P_{95}`$` | Experiment `$`P_{95}`$` | Δ % | Welch `$`t`$` | p | Cohen's `$`d`$` | 95 % CI | Verdict |
|-----------|----:|----:|----:|----:|--:|----:|--------|---------|
| `[benchmark]` | `[…]` | `[…]` | `[…]` | `[…]` | `[…]` | `[…]` | `[…, …]` | `[ACCEPT / REJECT / REJECT-REGRESSION]` |

```bash
npm run compare-benchmarks \
  docs/benchmarks/results/baseline.json \
  docs/benchmarks/results/experiment.json
```
