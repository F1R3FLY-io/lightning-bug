#!/usr/bin/env node
/**
 * Automated Benchmark Runner for Lightning Bug
 *
 * Runs the benchmark suite in a headless browser and exports results to JSON.
 *
 * Usage:
 *   node scripts/run-benchmark.js [options]
 *
 * Options:
 *   --output, -o <file>    Output file for results (default: stdout)
 *   --timeout <ms>         Max benchmark duration (default: 1800000 = 30 minutes)
 *   --quick                Run quick test instead of full suite
 *   --scroll               Run scroll performance benchmark (EXP-005 validation)
 *   --browser <name>       Browser to use (chrome, firefox, edge, brave) (default: chrome)
 *   --port <number>        Port for local server (default: 3003)
 *   --help, -h             Show this help message
 *
 * Environment Variables:
 *   CHROME_BIN             Path to Chrome executable
 *   FIREFOX_BIN            Path to Firefox executable
 *   BENCHMARK_TIMEOUT      Default timeout in milliseconds
 *
 * Examples:
 *   node scripts/run-benchmark.js
 *   node scripts/run-benchmark.js --output docs/benchmarks/results/baseline.json
 *   node scripts/run-benchmark.js --quick --output quick-test.json
 *   node scripts/run-benchmark.js --browser firefox --timeout 600000
 */

import fs from 'fs';
import http from 'http';
import path from 'path';
import { fileURLToPath } from 'url';
import puppeteer from 'puppeteer-core';

// =============================================================================
// Configuration
// =============================================================================

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const baseDir = path.dirname(scriptDir);
const benchmarkDir = path.join(baseDir, 'resources/public/benchmark');

const DEFAULT_PORT = 3003;
const DEFAULT_TIMEOUT = 30 * 60 * 1000; // 30 minutes
const POLL_INTERVAL = 1000; // 1 second

// Browser configurations
const isWindows = process.platform === 'win32';
const isLinux = process.platform === 'linux';
const isMacOS = process.platform === 'darwin';

const BROWSERS = {
    chrome: {
        name: 'Chrome',
        browser: 'chrome',
        executablePath: process.env.CHROME_BIN || (
            isMacOS ? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' :
            isWindows ? 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe' :
            '/usr/bin/google-chrome-stable'
        ),
        args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-web-security', '--headless=new', '--disable-gpu']
    },
    firefox: {
        name: 'Firefox',
        browser: 'firefox',
        executablePath: process.env.FIREFOX_BIN || (
            isMacOS ? '/Applications/Firefox.app/Contents/MacOS/firefox' :
            isWindows ? 'C:\\Program Files\\Mozilla Firefox\\firefox.exe' :
            '/usr/bin/firefox'
        ),
        args: ['--headless', '--remote-debugging-port=0', '--remote-allow-origins=*']
    },
    edge: {
        name: 'Edge',
        browser: 'chrome',
        executablePath: process.env.EDGE_BIN || (
            isMacOS ? '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge' :
            isWindows ? 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe' :
            '/usr/bin/microsoft-edge-stable'
        ),
        args: ['--no-sandbox', '--disable-setuid-sandbox', '--headless=new', '--disable-gpu']
    },
    brave: {
        name: 'Brave',
        browser: 'chrome',
        executablePath: process.env.BRAVE_BIN || (
            isMacOS ? '/Applications/Brave Browser.app/Contents/MacOS/Brave Browser' :
            isWindows ? 'C:\\Program Files\\BraveSoftware\\Brave-Browser\\Application\\brave.exe' :
            '/usr/bin/brave-browser'
        ),
        args: ['--headless=new', '--no-sandbox', '--disable-gpu']
    }
};

// =============================================================================
// HTTP Server
// =============================================================================

function createServer(port) {
    return new Promise((resolve, reject) => {
        const server = http.createServer((req, res) => {
            let filePath = path.join(benchmarkDir, req.url === '/' ? 'index.html' : req.url);

            // Handle paths that reference parent directories for shared resources
            // First try benchmark directory, then fall back to demo directory
            if (req.url.startsWith('/extensions/') || req.url.startsWith('/tree-sitter/')) {
                const benchmarkPath = path.join(benchmarkDir, req.url);
                const demoPath = path.join(baseDir, 'resources/public/demo', req.url);
                if (fs.existsSync(benchmarkPath)) {
                    filePath = benchmarkPath;
                } else if (fs.existsSync(demoPath)) {
                    filePath = demoPath;
                } else {
                    filePath = path.join(baseDir, 'resources/public', req.url);
                }
            }

            fs.readFile(filePath, (err, data) => {
                if (err) {
                    console.error(`[Server] 404: ${filePath}`);
                    res.writeHead(404);
                    res.end('Not found');
                } else {
                    const ext = path.extname(filePath);
                    const contentTypes = {
                        '.html': 'text/html',
                        '.js': 'application/javascript',
                        '.css': 'text/css',
                        '.wasm': 'application/wasm',
                        '.json': 'application/json',
                        '.scm': 'text/plain',
                        '.txt': 'text/plain'
                    };
                    const contentType = contentTypes[ext] || 'application/octet-stream';
                    res.writeHead(200, { 'Content-Type': contentType });
                    res.end(data);
                }
            });
        });

        server.on('error', reject);
        server.listen(port, () => {
            console.log(`[Server] Running at http://localhost:${port}`);
            resolve(server);
        });
    });
}

// =============================================================================
// Benchmark Runner
// =============================================================================

async function runBenchmarks(options) {
    const {
        outputPath,
        timeout = DEFAULT_TIMEOUT,
        quick = false,
        scroll = false,
        browserName = 'chrome',
        port = DEFAULT_PORT
    } = options;

    const browserConfig = BROWSERS[browserName.toLowerCase()];
    if (!browserConfig) {
        throw new Error(`Unknown browser: ${browserName}. Available: ${Object.keys(BROWSERS).join(', ')}`);
    }

    // Check if executable exists
    if (!fs.existsSync(browserConfig.executablePath)) {
        throw new Error(`Browser executable not found: ${browserConfig.executablePath}`);
    }

    const benchmarkType = scroll ? 'scroll' : (quick ? 'quick' : 'full');
    console.log(`[Benchmark] Starting ${benchmarkType} benchmark suite`);
    console.log(`[Benchmark] Browser: ${browserConfig.name}`);
    console.log(`[Benchmark] Timeout: ${timeout}ms`);
    console.log(`[Benchmark] Output: ${outputPath || 'stdout'}`);

    // Start HTTP server
    const server = await createServer(port);

    let browser;
    try {
        // Launch browser
        console.log(`[Browser] Launching ${browserConfig.name}...`);
        browser = await puppeteer.launch({
            headless: true,
            browser: browserConfig.browser,
            executablePath: browserConfig.executablePath,
            args: browserConfig.args,
            protocolTimeout: timeout,
            timeout: 0
        });
        console.log(`[Browser] Launched`);

        const page = await browser.newPage();

        // Set up console logging
        page.on('console', msg => {
            const text = msg.text();
            const type = msg.type();
            if (type === 'error') {
                console.error(`[Page] ${text}`);
            } else if (text.includes('Benchmark')) {
                console.log(`[Page] ${text}`);
            }
        });

        page.on('pageerror', error => {
            console.error(`[Page Error] ${error.message}`);
        });

        // Navigate to benchmark page
        console.log(`[Browser] Navigating to benchmark page...`);
        await page.goto(`http://localhost:${port}/index.html`, {
            waitUntil: 'networkidle2',
            timeout: 60000
        });
        console.log(`[Browser] Page loaded`);

        // Wait for ClojureScript to initialize
        console.log(`[Benchmark] Waiting for benchmark module to load...`);
        await page.waitForFunction(
            () => typeof lib !== 'undefined' && typeof lib.perf !== 'undefined' && typeof lib.perf.benchmark_tests !== 'undefined',
            { timeout: 60000 }
        );
        console.log(`[Benchmark] Module loaded`);

        // Run benchmarks
        const runFunction = scroll ? 'runScrollBenchmark' : (quick ? 'runQuickTest' : 'runBenchmarks');
        console.log(`[Benchmark] Running ${runFunction}...`);

        if (scroll) {
            // Scroll benchmark returns a promise directly, handle differently
            await page.evaluate(() => {
                window.benchmarkComplete = false;
                window.currentResults = null;
                window.lib.perf.benchmark_tests.runScrollBenchmark()
                    .then(result => {
                        window.currentResults = { scrollBenchmark: result };
                        window.benchmarkComplete = true;
                    })
                    .catch(err => {
                        window.currentResults = { error: err.message };
                        window.benchmarkComplete = true;
                    });
            });
        } else {
            await page.evaluate((fn) => {
                window.lib.perf.benchmark_tests[fn]();
            }, runFunction);
        }

        // Poll for completion
        const startTime = Date.now();
        let results = null;

        console.log(`[Benchmark] Waiting for completion (timeout: ${Math.round(timeout / 1000)}s)...`);

        while (Date.now() - startTime < timeout) {
            const { complete, data } = await page.evaluate(() => {
                return {
                    complete: window.benchmarkComplete === true,
                    data: window.currentResults
                };
            });

            if (complete && data) {
                results = data;
                break;
            }

            // Log progress
            const elapsed = Math.round((Date.now() - startTime) / 1000);
            if (elapsed % 30 === 0 && elapsed > 0) {
                console.log(`[Benchmark] Still running... (${elapsed}s elapsed)`);
            }

            await new Promise(resolve => setTimeout(resolve, POLL_INTERVAL));
        }

        if (!results) {
            throw new Error(`Benchmark timed out after ${timeout}ms`);
        }

        // Add metadata
        results.meta = {
            runDate: new Date().toISOString(),
            browser: browserConfig.name,
            quick: quick,
            timeout: timeout,
            hostname: process.env.HOSTNAME || 'unknown',
            platform: process.platform,
            nodeVersion: process.version
        };

        const jsonOutput = JSON.stringify(results, null, 2);

        // Output results
        if (outputPath) {
            // Ensure directory exists
            const dir = path.dirname(outputPath);
            if (!fs.existsSync(dir)) {
                fs.mkdirSync(dir, { recursive: true });
            }
            fs.writeFileSync(outputPath, jsonOutput);
            console.log(`[Benchmark] Results written to: ${outputPath}`);
        } else {
            console.log('\n' + '='.repeat(60));
            console.log('BENCHMARK RESULTS');
            console.log('='.repeat(60) + '\n');
            console.log(jsonOutput);
        }

        console.log(`[Benchmark] Complete!`);
        return results;

    } finally {
        if (browser) {
            await browser.close();
            console.log(`[Browser] Closed`);
        }
        server.close(() => {
            console.log(`[Server] Closed`);
        });
    }
}

// =============================================================================
// CLI
// =============================================================================

function showHelp() {
    console.log(`
Automated Benchmark Runner for Lightning Bug

Usage:
  node scripts/run-benchmark.js [options]

Options:
  --output, -o <file>    Output file for results (default: stdout)
  --timeout <ms>         Max benchmark duration (default: 1800000 = 30 minutes)
  --quick                Run quick test instead of full suite
  --scroll               Run scroll performance benchmark (EXP-005 validation)
  --browser <name>       Browser to use (chrome, firefox, edge, brave) (default: chrome)
  --port <number>        Port for local server (default: 3003)
  --help, -h             Show this help message

Environment Variables:
  CHROME_BIN             Path to Chrome executable
  FIREFOX_BIN            Path to Firefox executable
  BENCHMARK_TIMEOUT      Default timeout in milliseconds

Examples:
  node scripts/run-benchmark.js
  node scripts/run-benchmark.js --output docs/benchmarks/results/baseline.json
  node scripts/run-benchmark.js --quick --output quick-test.json
  node scripts/run-benchmark.js --scroll --output docs/benchmarks/results/scroll.json
  node scripts/run-benchmark.js --browser firefox --timeout 600000
    `);
}

async function main() {
    const args = process.argv.slice(2);

    if (args.includes('--help') || args.includes('-h')) {
        showHelp();
        process.exit(0);
    }

    const options = {
        outputPath: null,
        timeout: parseInt(process.env.BENCHMARK_TIMEOUT) || DEFAULT_TIMEOUT,
        quick: false,
        scroll: false,
        browserName: 'chrome',
        port: DEFAULT_PORT
    };

    for (let i = 0; i < args.length; i++) {
        switch (args[i]) {
            case '--output':
            case '-o':
                options.outputPath = args[++i];
                break;
            case '--timeout':
                options.timeout = parseInt(args[++i]);
                break;
            case '--quick':
                options.quick = true;
                break;
            case '--scroll':
                options.scroll = true;
                break;
            case '--browser':
                options.browserName = args[++i];
                break;
            case '--port':
                options.port = parseInt(args[++i]);
                break;
        }
    }

    try {
        await runBenchmarks(options);
        process.exit(0);
    } catch (error) {
        console.error(`[Error] ${error.message}`);
        process.exit(1);
    }
}

main();
