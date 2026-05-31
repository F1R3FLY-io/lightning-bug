# Experiment Log Guide

Use this guide when adding a concrete benchmark experiment log. Do not commit
unfilled template text; each committed experiment document should contain real
measurements, commands, decisions, and file references.

## Metadata

Record the experiment identifier, ISO date, branch, and final status. Use a
specific identifier such as `EXP-005` and a branch name such as
`experiment/exp-005-parser-cache`.

## Hypothesis

State the performance claim in measurable terms, including the target benchmark
metric and the expected improvement range.

## Background

Describe the current implementation, the observed bottleneck, and the proposed
change. Link profiling data or prior benchmark results when available.

## Implementation

List each modified file and the concrete behavior changed. Include short
before/after snippets only when they clarify the mechanism behind the expected
performance effect.

## Verification

Record the exact commands used for unit tests, integration tests, type checks,
linting, demo checks, and benchmark runs. Include pass/fail status and any
environment notes such as CPU pinning availability.

## Benchmark Results

Include baseline and experiment tables with mean, median, P95, and P99 values
for every target metric. Include Welch's t-statistic, p-value, Cohen's d, and
the 95% confidence interval for each comparison.

## Decision

Accept an experiment only when the target metric improves with statistical
significance, the effect size is meaningful, and non-target metrics do not
regress. Otherwise reject it and keep the result as evidence.

## Commit Information

Reference the concrete commit and summarize the measured result in the commit
message body. Include the experiment identifier, baseline P95, result P95,
percentage change, p-value, and decision.

## Lessons

Capture what worked, what did not, and any specific benchmark or implementation
insights that should inform subsequent optimization work.
