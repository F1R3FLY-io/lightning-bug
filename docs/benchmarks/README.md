# Lightning Bug Performance Benchmarks

## Overview

This directory contains the performance benchmarking infrastructure, results, and scientific ledger for Lightning Bug optimization efforts.

## Methodology

### Statistical Approach

All benchmarks follow rigorous statistical methodology:

1. **Sample Size**: Minimum 30 measurements per benchmark (Central Limit Theorem requirement)
2. **Warmup**: 10 iterations discarded before measurement
3. **Measurement**: 100 iterations by default
4. **Outlier Removal**: 1.5*IQR rule applied to raw measurements
5. **Significance Testing**: Welch's t-test with p < 0.05 threshold
6. **Effect Size**: Cohen's d with |d| >= 0.2 for meaningful effects

### Benchmark Environment

For reproducible results:

1. **CPU Governor**: Set to `performance` mode
2. **Turbo Boost**: Disabled for consistency
3. **CPU Affinity**: Pin to single core (core 0) using `taskset`
4. **System Load**: Minimize background processes
5. **Browser**: Run in isolated profile without extensions

### Running Benchmarks

#### Automated (Headless) - Recommended

```bash
# Build the benchmark module
npm run benchmark:build

# Check current system status
npm run benchmark:setup

# Prepare system for benchmarking (requires sudo)
npm run benchmark:prepare

# Run full benchmark suite and save as baseline
npm run benchmark:baseline

# Run quick test (fewer iterations)
npm run benchmark:quick

# Run and output to custom file
npm run benchmark:run -- --output results.json

# After benchmarking, restore system settings
npm run benchmark:restore
```

#### Interactive (Browser)

```bash
# Start benchmark dev server with hot-reload
npm run benchmark:serve

# Open browser and navigate to http://localhost:3002
# Click "Run Full Suite" to execute benchmarks
```

### NPM Scripts Reference

| Command | Description |
|---------|-------------|
| `npm run benchmark:build` | Compile benchmark ClojureScript |
| `npm run benchmark:serve` | Start dev server with hot-reload |
| `npm run benchmark:run` | Run full suite, output to stdout |
| `npm run benchmark:baseline` | Run and save as baseline.json |
| `npm run benchmark:experiment` | Run and save as experiment.json |
| `npm run benchmark:quick` | Quick test with fewer iterations |
| `npm run benchmark:setup` | Show current system status |
| `npm run benchmark:prepare` | Configure system for benchmarking (sudo) |
| `npm run benchmark:restore` | Restore default system settings (sudo) |
| `npm run compare-benchmarks` | Compare baseline vs experiment |

### Comparing Results

```bash
# Run baseline first
npm run benchmark:baseline

# Make optimization changes...

# Run experiment
npm run benchmark:experiment

# Compare results
npm run compare-benchmarks docs/benchmarks/results/baseline.json docs/benchmarks/results/experiment.json

# Or with custom output
npm run compare-benchmarks docs/benchmarks/results/baseline.json docs/benchmarks/results/experiment.json -- --output report.md
```

## Directory Structure

```
docs/benchmarks/
├── README.md                 # This file
├── ledger/                   # Scientific experiment logs
│   ├── BASELINE_*.md         # Initial baseline documentation
│   └── EXP-XXX_*.md          # Individual experiment logs
├── results/                  # Machine-readable results
│   ├── baseline.json         # Current baseline measurements
│   └── exp-XXX.json          # Experiment results
└── analysis/                 # Analysis documents
    └── bottleneck-analysis.md  # Profiling findings
```

## Performance Targets

| Metric | Target | Description |
|--------|--------|-------------|
| `tree-sitter-full-parse` | 50ms | Full parse (10K lines) |
| `tree-sitter-incremental` | 5ms | Incremental parse |
| `datascript-query` | 5ms | Typical DataScript query |
| `frame-time` | 16.67ms | 60fps budget |
| `startup` | 2000ms | Time to interactive |
| `lsp-response` | 500ms | LSP request/response |
| `diagnostic-transform` | 10ms | Diagnostic transformation |

## Scientific Method for Optimization

### 1. Formulate Hypothesis

Before any optimization:
- Document the current behavior
- Identify the specific bottleneck
- Predict the expected improvement (quantified)
- Document rationale

### 2. Create Experiment Branch

```bash
git checkout -b experiment/exp-XXX-description main
```

### 3. Implement Optimization

- Make minimal, targeted changes
- Keep scope focused to isolate the variable

### 4. Run Benchmarks

```bash
npm run benchmark:serve
# Export results as exp-XXX.json
```

### 5. Statistical Analysis

For each metric, the comparison script computes:
- Welch's t-test p-value
- Cohen's d effect size
- 95% confidence interval

### 6. Decision Criteria

**ACCEPT** if:
- p < 0.05 (statistically significant)
- |d| >= 0.2 (meaningful effect size)
- No regressions in other metrics

**REJECT** if:
- p >= 0.05 (not significant)
- |d| < 0.2 (trivial effect)
- Regression detected

### 7. Document Results

Whether accepted or rejected, document:
- Hypothesis
- Implementation
- Results
- Decision
- Lessons learned

## Interpreting Results

### Effect Size (Cohen's d)

| |d| | Interpretation |
|-----|----------------|
| < 0.2 | Negligible |
| 0.2 - 0.5 | Small |
| 0.5 - 0.8 | Medium |
| >= 0.8 | Large |

### P-value

- p < 0.01: Very strong evidence
- p < 0.05: Strong evidence (our threshold)
- p < 0.10: Weak evidence
- p >= 0.10: No evidence

### Confidence Intervals

The 95% CI for the mean difference:
- If CI doesn't include 0, difference is significant
- Width indicates precision of estimate

## Hardware Reference

See `/home/dylon/.claude/hardware-specifications.md` for full system details.

**Key Specs:**
- **CPU**: Intel Xeon E5-2699 v3 @ 2.30GHz (36 cores/72 threads)
- **RAM**: 252 GB DDR4-2133 ECC
- **Storage**: Samsung 990 PRO 4TB NVMe
- **GPU**: NVIDIA RTX 4060 Ti 8GB

## Contributing

When adding new benchmarks:

1. Add benchmark function to `src/lib/perf/benchmark_tests.cljs`
2. Add to `benchmark-suite` vector
3. Document expected target in this README
4. Add target to `PERFORMANCE-TARGETS` in `bench.cljs`
