#!/usr/bin/env node
/**
 * Benchmark Comparison Script
 *
 * Compares baseline and experiment benchmark results with statistical significance testing.
 *
 * Usage:
 *   node scripts/compare-benchmarks.js <baseline.json> <experiment.json> [--output <file>]
 *
 * The script performs:
 * - Welch's t-test for statistical significance
 * - Cohen's d for effect size
 * - Decision making based on p < 0.05 and meaningful effect size
 */

import { readFile, writeFile } from 'fs/promises';
import { existsSync } from 'fs';
import { basename } from 'path';

// =============================================================================
// Statistical Functions
// =============================================================================

function mean(arr) {
    if (!arr || arr.length === 0) return 0;
    return arr.reduce((a, b) => a + b, 0) / arr.length;
}

function variance(arr) {
    if (!arr || arr.length < 2) return 0;
    const m = mean(arr);
    const sumSq = arr.reduce((acc, x) => acc + (x - m) ** 2, 0);
    return sumSq / (arr.length - 1);
}

function stdDev(arr) {
    return Math.sqrt(variance(arr));
}

function median(arr) {
    if (!arr || arr.length === 0) return 0;
    const sorted = [...arr].sort((a, b) => a - b);
    const mid = Math.floor(sorted.length / 2);
    return sorted.length % 2 !== 0
        ? sorted[mid]
        : (sorted[mid - 1] + sorted[mid]) / 2;
}

function percentile(arr, p) {
    if (!arr || arr.length === 0) return 0;
    const sorted = [...arr].sort((a, b) => a - b);
    const index = Math.round((p / 100) * (sorted.length - 1));
    return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
}

function quartiles(arr) {
    return {
        q1: percentile(arr, 25),
        q2: percentile(arr, 50),
        q3: percentile(arr, 75)
    };
}

function removeOutliers(arr) {
    if (!arr || arr.length < 4) return { cleaned: arr, removed: [] };
    const { q1, q3 } = quartiles(arr);
    const iqr = q3 - q1;
    const lower = q1 - 1.5 * iqr;
    const upper = q3 + 1.5 * iqr;
    const cleaned = arr.filter(x => x >= lower && x <= upper);
    const removed = arr.filter(x => x < lower || x > upper);
    return { cleaned, removed, bounds: [lower, upper] };
}

// =============================================================================
// Welch's t-test
// =============================================================================

// t-distribution critical values for two-tailed test at alpha=0.05
const tCriticalValues = [
    12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228,
    2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086,
    2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042
];

function tCritical(df) {
    if (df < 1) return null;
    if (df <= 30) return tCriticalValues[Math.floor(df) - 1];
    if (df <= 40) return 2.021;
    if (df <= 60) return 2.000;
    if (df <= 80) return 1.990;
    if (df <= 100) return 1.984;
    if (df <= 120) return 1.980;
    return 1.960;
}

// Regularized incomplete beta function approximation
function regularizedIncompleteBeta(x, a, b) {
    if (x <= 0) return 0;
    if (x >= 1) return 1;

    const maxIter = 200;
    const eps = 1e-10;
    const factor = Math.pow(x, a) / a;

    let sum = 1;
    let term = 1;
    for (let n = 1; n < maxIter; n++) {
        term *= ((a - n + 1) * (b - n + 1) * x) / (n * (a + n));
        if (Math.abs(term) < eps) break;
        sum += term;
    }

    return factor * sum;
}

// t-distribution CDF
function tCdf(t, df) {
    const x = df / (df + t * t);
    if (t > 0) {
        return 1 - 0.5 * regularizedIncompleteBeta(x, df / 2, 0.5);
    }
    return 0.5 * regularizedIncompleteBeta(x, df / 2, 0.5);
}

// Two-tailed p-value
function tPValue(tStat, df) {
    const pOneTail = 1 - tCdf(Math.abs(tStat), df);
    return 2 * pOneTail;
}

// Welch-Satterthwaite degrees of freedom
function welchDf(n1, v1, n2, v2) {
    const term1 = v1 / n1;
    const term2 = v2 / n2;
    const numerator = (term1 + term2) ** 2;
    const denom1 = (term1 ** 2) / (n1 - 1);
    const denom2 = (term2 ** 2) / (n2 - 1);
    return numerator / (denom1 + denom2);
}

function welchTTest(sample1, sample2, alpha = 0.05) {
    if (!sample1 || !sample2 || sample1.length < 2 || sample2.length < 2) {
        return null;
    }

    const n1 = sample1.length;
    const n2 = sample2.length;
    const m1 = mean(sample1);
    const m2 = mean(sample2);
    const v1 = variance(sample1);
    const v2 = variance(sample2);
    const se = Math.sqrt(v1 / n1 + v2 / n2);
    const tStat = (m1 - m2) / se;
    const df = welchDf(n1, v1, n2, v2);
    const pVal = tPValue(tStat, df);
    const tCrit = tCritical(df) || 1.96;
    const margin = tCrit * se;

    return {
        tStatistic: tStat,
        df,
        pValue: pVal,
        significant: pVal < alpha,
        mean1: m1,
        mean2: m2,
        diff: m1 - m2,
        se,
        ciLower: (m1 - m2) - margin,
        ciUpper: (m1 - m2) + margin,
        n1,
        n2,
        alpha
    };
}

// =============================================================================
// Cohen's d
// =============================================================================

function pooledStdDev(sample1, sample2) {
    const n1 = sample1.length;
    const n2 = sample2.length;
    const v1 = variance(sample1);
    const v2 = variance(sample2);
    return Math.sqrt(((n1 - 1) * v1 + (n2 - 1) * v2) / (n1 + n2 - 2));
}

function cohensD(sample1, sample2) {
    if (!sample1 || !sample2 || sample1.length < 2 || sample2.length < 2) {
        return null;
    }

    const m1 = mean(sample1);
    const m2 = mean(sample2);
    const sPooled = pooledStdDev(sample1, sample2);
    const d = sPooled === 0 ? 0 : (m1 - m2) / sPooled;
    const absD = Math.abs(d);

    let magnitude;
    if (absD < 0.2) magnitude = 'negligible';
    else if (absD < 0.5) magnitude = 'small';
    else if (absD < 0.8) magnitude = 'medium';
    else magnitude = 'large';

    return {
        d,
        magnitude,
        meaningful: absD >= 0.2
    };
}

// =============================================================================
// Full Statistics
// =============================================================================

function fullStatistics(arr) {
    if (!arr || arr.length === 0) return null;

    const sorted = [...arr].sort((a, b) => a - b);
    const n = arr.length;
    const m = mean(arr);
    const s = n > 1 ? stdDev(arr) : 0;
    const { q1, q2, q3 } = quartiles(arr);

    return {
        count: n,
        min: sorted[0],
        max: sorted[n - 1],
        mean: m,
        median: q2,
        stdDev: s,
        variance: n > 1 ? variance(arr) : 0,
        q1,
        q3,
        iqr: q3 - q1,
        p5: percentile(sorted, 5),
        p10: percentile(sorted, 10),
        p25: q1,
        p50: q2,
        p75: q3,
        p90: percentile(sorted, 90),
        p95: percentile(sorted, 95),
        p99: percentile(sorted, 99),
        se: n > 1 ? s / Math.sqrt(n) : 0
    };
}

// =============================================================================
// Comparison
// =============================================================================

function compareBenchmarks(baseline, experiment, options = {}) {
    const { alpha = 0.05, removeOutliersFlag = true } = options;

    const bCleaned = removeOutliersFlag
        ? removeOutliers(baseline).cleaned
        : baseline;
    const eCleaned = removeOutliersFlag
        ? removeOutliers(experiment).cleaned
        : experiment;

    const bStats = fullStatistics(bCleaned);
    const eStats = fullStatistics(eCleaned);

    const tTest = welchTTest(eCleaned, bCleaned, alpha);
    const effect = cohensD(eCleaned, bCleaned);

    const diff = eStats.mean - bStats.mean;
    const pctChange = bStats.mean !== 0 ? (100 * diff / bStats.mean) : 0;
    const improved = diff < 0;

    let decision;
    let summary;

    if (!tTest.significant) {
        decision = 'REJECT-NOT-SIGNIFICANT';
        summary = `REJECT: Not statistically significant (p=${tTest.pValue.toFixed(4)})`;
    } else if (!effect.meaningful) {
        decision = 'REJECT-TRIVIAL-EFFECT';
        summary = `REJECT: Effect size too small (d=${effect.d.toFixed(3)})`;
    } else if (diff > 0) {
        decision = 'REJECT-REGRESSION';
        summary = `REJECT: Regression detected (${Math.abs(pctChange).toFixed(2)}% slower)`;
    } else {
        decision = 'ACCEPT';
        summary = `ACCEPT: Significant improvement (${Math.abs(pctChange).toFixed(2)}% faster, p=${tTest.pValue.toFixed(4)}, d=${effect.d.toFixed(3)})`;
    }

    return {
        baselineStats: bStats,
        experimentStats: eStats,
        difference: diff,
        percentChange: pctChange,
        improved,
        welchTTest: tTest,
        cohensD: effect,
        significant: tTest.significant,
        meaningfulEffect: effect.meaningful,
        decision,
        summary
    };
}

// =============================================================================
// Report Formatting
// =============================================================================

function formatComparisonReport(name, comparison) {
    const { decision, summary, baselineStats, experimentStats, welchTTest, cohensD, percentChange, improved } = comparison;

    const lines = [
        `### ${name}`,
        '',
        `**Decision:** ${decision}`,
        '',
        summary,
        '',
        '#### Statistics',
        '',
        '| Metric | Baseline | Experiment | Change |',
        '|--------|----------|------------|--------|',
        `| Mean | ${baselineStats.mean.toFixed(3)} ms | ${experimentStats.mean.toFixed(3)} ms | ${improved ? '' : '+'}${percentChange.toFixed(2)}% |`,
        `| Median | ${baselineStats.median.toFixed(3)} ms | ${experimentStats.median.toFixed(3)} ms | |`,
        `| P95 | ${baselineStats.p95.toFixed(3)} ms | ${experimentStats.p95.toFixed(3)} ms | |`,
        `| P99 | ${baselineStats.p99.toFixed(3)} ms | ${experimentStats.p99.toFixed(3)} ms | |`,
        `| Std Dev | ${baselineStats.stdDev.toFixed(3)} ms | ${experimentStats.stdDev.toFixed(3)} ms | |`,
        `| Sample Size | ${baselineStats.count} | ${experimentStats.count} | |`,
        '',
        '#### Statistical Tests',
        '',
        `- **Welch\'s t-test:** t=${welchTTest.tStatistic.toFixed(4)}, p=${welchTTest.pValue.toFixed(4)}, df=${welchTTest.df.toFixed(2)}`,
        `- **Significant:** ${welchTTest.significant ? 'Yes' : 'No'} (alpha=${welchTTest.alpha})`,
        `- **Cohen\'s d:** ${cohensD.d.toFixed(4)} (${cohensD.magnitude})`,
        `- **Meaningful effect:** ${cohensD.meaningful ? 'Yes' : 'No'}`,
        ''
    ];

    return lines.join('\n');
}

// =============================================================================
// Main
// =============================================================================

async function loadBenchmarkFile(filepath) {
    if (!existsSync(filepath)) {
        throw new Error(`File not found: ${filepath}`);
    }
    const content = await readFile(filepath, 'utf8');
    return JSON.parse(content);
}

function extractMeasurements(benchmarkData, metricName) {
    // Handle different data structures
    if (benchmarkData.benchmarks) {
        // Suite format
        const bench = benchmarkData.benchmarks.find(b => b.name === metricName);
        if (bench) {
            return bench.cleaned?.measurements || bench.raw?.measurements || [];
        }
    }

    // Direct metric format
    if (benchmarkData[metricName]) {
        return benchmarkData[metricName].cleaned?.measurements ||
               benchmarkData[metricName].raw?.measurements ||
               benchmarkData[metricName].measurements ||
               [];
    }

    return [];
}

function getAllMetricNames(data) {
    const names = new Set();

    if (data.benchmarks) {
        data.benchmarks.forEach(b => names.add(b.name));
    } else {
        Object.keys(data).forEach(key => {
            if (typeof data[key] === 'object' && data[key] !== null) {
                names.add(key);
            }
        });
    }

    return Array.from(names);
}

async function main() {
    const args = process.argv.slice(2);

    if (args.length < 2 || args.includes('--help') || args.includes('-h')) {
        console.log(`
Benchmark Comparison Tool

Usage:
  node compare-benchmarks.js <baseline.json> <experiment.json> [options]

Options:
  --output, -o <file>   Write report to file (default: stdout)
  --json                Output as JSON
  --help, -h            Show this help message

Examples:
  node compare-benchmarks.js baseline.json experiment.json
  node compare-benchmarks.js baseline.json experiment.json --output report.md
  node compare-benchmarks.js baseline.json experiment.json --json
        `);
        process.exit(args.includes('--help') || args.includes('-h') ? 0 : 1);
    }

    const baselinePath = args[0];
    const experimentPath = args[1];

    let outputPath = null;
    let jsonOutput = false;

    for (let i = 2; i < args.length; i++) {
        if ((args[i] === '--output' || args[i] === '-o') && args[i + 1]) {
            outputPath = args[++i];
        } else if (args[i] === '--json') {
            jsonOutput = true;
        }
    }

    try {
        console.log('Loading benchmark files...');
        const baseline = await loadBenchmarkFile(baselinePath);
        const experiment = await loadBenchmarkFile(experimentPath);

        console.log(`Baseline: ${basename(baselinePath)}`);
        console.log(`Experiment: ${basename(experimentPath)}`);

        // Get all metric names from both files
        const baselineMetrics = getAllMetricNames(baseline);
        const experimentMetrics = getAllMetricNames(experiment);
        const allMetrics = [...new Set([...baselineMetrics, ...experimentMetrics])];

        console.log(`\nFound ${allMetrics.length} metrics to compare\n`);

        const results = {};
        const reportLines = [
            '# Benchmark Comparison Report',
            '',
            `**Date:** ${new Date().toISOString()}`,
            `**Baseline:** ${basename(baselinePath)}`,
            `**Experiment:** ${basename(experimentPath)}`,
            '',
            '---',
            '',
            '## Summary',
            '',
            '| Metric | Decision | Change | p-value | Cohen\'s d |',
            '|--------|----------|--------|---------|-----------|'
        ];

        let acceptCount = 0;
        let rejectCount = 0;
        const detailedReports = [];

        for (const metric of allMetrics) {
            const baselineMeasurements = extractMeasurements(baseline, metric);
            const experimentMeasurements = extractMeasurements(experiment, metric);

            if (baselineMeasurements.length < 2 || experimentMeasurements.length < 2) {
                console.log(`  Skipping ${metric}: insufficient data`);
                continue;
            }

            const comparison = compareBenchmarks(baselineMeasurements, experimentMeasurements);
            results[metric] = comparison;

            const changeStr = `${comparison.improved ? '' : '+'}${comparison.percentChange.toFixed(2)}%`;
            const pStr = comparison.welchTTest.pValue.toFixed(4);
            const dStr = comparison.cohensD.d.toFixed(3);

            reportLines.push(`| ${metric} | ${comparison.decision} | ${changeStr} | ${pStr} | ${dStr} |`);

            detailedReports.push(formatComparisonReport(metric, comparison));

            if (comparison.decision === 'ACCEPT') {
                acceptCount++;
            } else {
                rejectCount++;
            }
        }

        reportLines.push('');
        reportLines.push(`**Accepted:** ${acceptCount} | **Rejected:** ${rejectCount}`);
        reportLines.push('');
        reportLines.push('---');
        reportLines.push('');
        reportLines.push('## Detailed Results');
        reportLines.push('');
        reportLines.push(...detailedReports);

        const report = reportLines.join('\n');

        if (jsonOutput) {
            const jsonReport = JSON.stringify({
                timestamp: new Date().toISOString(),
                baseline: basename(baselinePath),
                experiment: basename(experimentPath),
                summary: { accepted: acceptCount, rejected: rejectCount },
                results
            }, null, 2);

            if (outputPath) {
                await writeFile(outputPath, jsonReport);
                console.log(`JSON report written to: ${outputPath}`);
            } else {
                console.log(jsonReport);
            }
        } else {
            if (outputPath) {
                await writeFile(outputPath, report);
                console.log(`Report written to: ${outputPath}`);
            } else {
                console.log('\n' + report);
            }
        }

        // Exit with appropriate code
        process.exit(rejectCount > 0 && acceptCount === 0 ? 1 : 0);

    } catch (error) {
        console.error('Error:', error.message);
        process.exit(1);
    }
}

main();
