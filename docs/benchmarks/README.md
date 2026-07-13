# Performance Benchmarking

This section documents Lightning Bug's **benchmarking methodology** — how performance is measured,
how a proposed optimization is accepted or rejected, and how to run the suite. The **durable results**
(the hot paths and the optimizations that stuck) are in [performance-model.md](performance-model.md);
the dated experiment records are preserved in
[`../archive/benchmarks/`](../archive/README.md#benchmarks--dated-performance-baselines--experiment-records).

## Principles

Optimization here is **data-driven and hypothesis-tested**: no change is accepted on intuition; each
is measured against a pinned baseline with a statistical test, and the result is recorded whether it
is accepted *or* rejected.

![Experiment loop: formulate a quantified hypothesis, branch from a captured baseline, implement a minimal change, run benchmarks with warmup and outlier removal, run the statistical analysis, accept if significant/meaningful/no-regression else reject, and document the result either way.](diagrams/experiment-loop.svg)

## Statistical methodology

Every benchmark follows the same statistical pipeline (implemented in
`scripts/compare-benchmarks.js`).

### Sampling

- **Sample size** ≥ 30 measurements per benchmark. Thirty is the conventional threshold at which the
  **Central Limit Theorem (CLT)** — the sampling distribution of the mean approaches normal as the
  sample grows — makes the normal-theory tests below reasonable.
- **Warmup:** 10 iterations discarded before measurement (to let JIT compilation and caches settle).
- **Measurement:** 100 iterations by default, with garbage collection and a 10 ms delay between
  iterations.

### Outlier removal

Raw measurements are trimmed with the **1.5 × IQR** rule (Tukey). With first and third quartiles
`$`Q_1`$` and `$`Q_3`$` and interquartile range `$`\mathit{IQR} = Q_3 - Q_1`$`, a measurement `$`x`$`
is discarded when

```math
x < Q_1 - 1.5 \cdot \mathit{IQR} \quad\text{or}\quad x > Q_3 + 1.5 \cdot \mathit{IQR}.
```

### Significance — Welch's t-test

Two samples (baseline vs. experiment) are compared with **Welch's t-test**, which does *not* assume
equal variances. For sample means `$`\bar{x}_1, \bar{x}_2`$`, variances `$`s_1^2, s_2^2`$`, and sizes
`$`n_1, n_2`$`:

```math
t = \frac{\bar{x}_1 - \bar{x}_2}{\sqrt{\dfrac{s_1^2}{n_1} + \dfrac{s_2^2}{n_2}}}
```

with the Welch–Satterthwaite degrees of freedom

```math
\nu = \frac{\left(\dfrac{s_1^2}{n_1} + \dfrac{s_2^2}{n_2}\right)^{2}}{\dfrac{(s_1^2/n_1)^2}{n_1-1} + \dfrac{(s_2^2/n_2)^2}{n_2-1}}.
```

A two-tailed p-value is computed from `$`t`$` and `$`\nu`$` (via the regularized incomplete beta
function); the significance threshold is `$`p < 0.05`$`.

### Effect size — Cohen's d

Statistical significance is not practical significance, so an **effect size** is also required.
Cohen's `$`d`$` measures the standardized mean difference,

```math
d = \frac{\bar{x}_1 - \bar{x}_2}{s_{\text{pooled}}}, \qquad
s_{\text{pooled}} = \sqrt{\frac{(n_1-1)s_1^2 + (n_2-1)s_2^2}{n_1 + n_2 - 2}},
```

and a change is considered meaningful only when `$`|d| \ge 0.2`$`.

| `$`\lvert d \rvert`$` | Interpretation |
|:---:|----------------|
| `$`< 0.2`$` | negligible |
| `$`0.2 - 0.5`$` | small |
| `$`0.5 - 0.8`$` | medium |
| `$`\ge 0.8`$` | large |

A 95% confidence interval for the mean difference and the percentiles `$`P_{75}, P_{90}, P_{95},
P_{99}`$` are also reported per metric.

### Decision rule

```
ACCEPT  ⟺  p < 0.05  ∧  |d| ≥ 0.2  ∧  no regression in any other metric
REJECT  otherwise   (a significant slowdown is reported as REJECT-REGRESSION)
```

## Performance targets

| Metric | Target | Description |
|--------|-------:|-------------|
| `tree-sitter-full-parse` | 50 ms | Full parse of ~10K lines |
| `tree-sitter-incremental` | 5 ms | Incremental re-parse |
| `datascript-query` | 5 ms | Typical DataScript query |
| `frame-time` | 16.67 ms | 60 fps budget |
| `startup` | 2000 ms | Time to interactive |
| `lsp-response` | 500 ms | LSP request/response |
| `diagnostic-transform` | 10 ms | Diagnostic transformation |

## Benchmark environment

For reproducible measurements the harness (`scripts/benchmark-setup.sh`) configures:

- CPU governor set to `performance`; Turbo Boost disabled (consistency over peak).
- CPU affinity pinned to a single core with `taskset`.
- An isolated browser profile with no extensions; background processes minimized.

Reference hardware for the recorded baselines is an Intel Xeon E5-2699 v3. CPU pinning requires
`sudo` (`npm run benchmark:prepare`); when unavailable, runs are unpinned and treated as
lower-precision evidence.

## Running the suite

| Command | Description |
|---------|-------------|
| `npm run benchmark:build` | Compile the benchmark ClojureScript. |
| `npm run benchmark:serve` | Dev server with hot-reload (interactive, at `http://localhost:3002`). |
| `npm run benchmark:run` | Run the full suite to stdout. |
| `npm run benchmark:baseline` | Run and save `docs/benchmarks/results/baseline.json`. |
| `npm run benchmark:experiment` | Run and save `docs/benchmarks/results/experiment.json`. |
| `npm run benchmark:quick` | Quick run (fewer iterations). |
| `npm run benchmark:setup` / `:prepare` / `:restore` | Show / configure (sudo) / restore system settings. |
| `npm run compare-benchmarks <baseline> <experiment>` | Statistical comparison + report. |
| `npm run benchmark:gate` | CI-style regression gate (`--gate-regression 150`, i.e. fail only on > 150 %). |

A typical comparison:

```bash
npm run benchmark:baseline
# … make an optimization …
npm run benchmark:experiment
npm run compare-benchmarks docs/benchmarks/results/baseline.json docs/benchmarks/results/experiment.json
```

### The CI role is coarse

In CI the `benchmark` job runs a `--quick` comparison of the base against the PR head on the *same*
runner and gates only on gross regressions (`--gate-regression 150` — a > 2.5× slowdown), because
unpinned CI runners have a large noise floor (±70 %). Authoritative numbers come from a manual,
CPU-pinned run.

## Adding a benchmark

1. Add a benchmark function to `src/lib/perf/benchmark_tests.cljs`.
2. Add it to the `benchmark-suite` vector.
3. Document the target in the [targets table](#performance-targets) above and in `PERFORMANCE-TARGETS`.
4. When you run an experiment, record it with the [experiment guide](experiment-guide.md) using the
   [baseline template](baseline-template.md).

## References

- B. L. Welch (1947). "The generalization of 'Student's' problem when several different population
  variances are involved." *Biometrika* 34(1–2).
  [doi:10.1093/biomet/34.1-2.28](https://doi.org/10.1093/biomet/34.1-2.28)
- J. Cohen (1988). *Statistical Power Analysis for the Behavioral Sciences* (2nd ed.). Routledge.
  [doi:10.4324/9780203771587](https://doi.org/10.4324/9780203771587)
- Student [W. S. Gosset] (1908). "The probable error of a mean." *Biometrika* 6(1).
  [doi:10.1093/biomet/6.1.1](https://doi.org/10.1093/biomet/6.1.1)

## Related reading

- [performance-model.md](performance-model.md) — the durable hot-path model and the optimizations
  that stuck.
- [experiment-guide.md](experiment-guide.md) / [baseline-template.md](baseline-template.md) — how to
  author an experiment record.
- [architecture/syntax-and-editing.md](../architecture/syntax-and-editing.md) and
  [architecture/data-model.md](../architecture/data-model.md) — the subsystems most of these
  optimizations touched.
