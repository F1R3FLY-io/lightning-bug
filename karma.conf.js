import { existsSync } from 'fs';

export default async function (config) {
  const karmaFile = process.env.KARMA_FILE || 'target/karma-test.js';
  const webkitLauncher = (await import('./scripts/karma-playwright-webkit-launcher.js')).default;

  // Fail fast (with a clear message) if the runtime-fetched test artifacts are
  // missing. These are copied by `npm run prepare:test`; without them the browser
  // silently 404s on tree-sitter.wasm / grammar queries and tests degrade in
  // confusing ways (audit finding). A loud error here points straight at the fix.
  const requiredArtifacts = [
    'resources/public/js/test/js/tree-sitter.wasm',
    'resources/public/js/test/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm',
    'resources/public/js/test/extensions/lang/rholang/tree-sitter/queries'
  ];
  const missing = requiredArtifacts.filter((p) => !existsSync(p));
  if (missing.length > 0) {
    throw new Error(
      'Missing test artifacts (run `npm run prepare:test` first):\n  ' +
      missing.join('\n  ')
    );
  }

  await Promise.all([
    import('karma-cljs-test'),
    import('karma-chrome-launcher'),
    import('karma-spec-reporter'),
    import('karma-firefox-launcher'),
    import('karma-opera-launcher')
  ]);

  config.set({
    plugins: ['karma-*', webkitLauncher],
    frameworks: ['cljs-test'],
    files: [
      karmaFile,
      { pattern: 'resources/public/js/test/extensions/**', watched: false, included: false, served: true },
      { pattern: 'resources/public/js/test/js/tree-sitter.wasm', watched: false, included: false, served: true }
    ],
    proxies: {
      '/extensions/': '/base/resources/public/js/test/extensions/',
      '/js/tree-sitter.wasm': '/base/resources/public/js/test/js/tree-sitter.wasm'
    },
    colors: true,
    logLevel: config.LOG_TRACE,
    browsers: process.env.KARMA_BROWSERS ? process.env.KARMA_BROWSERS.split(',') : ['ChromeHeadlessNoSandbox'],
    customLaunchers: {
      ChromeHeadlessNoSandbox: {
        base: 'ChromeHeadless',
        flags: ['--no-sandbox', '--disable-setuid-sandbox']
      },
      FirefoxHeadless: {
        base: 'Firefox',
        flags: ['-headless']
      },
      EdgeHeadless: {
        base: 'Chrome',
        flags: ['--headless=new', '--no-sandbox', '--disable-setuid-sandbox', '--disable-gpu', '--disable-dev-shm-usage', '--disable-extensions', '--remote-debugging-port=9222'],
        env: { CHROME_BIN: process.env.EDGE_BIN }
      },
      OperaHeadless: {
        base: 'Chrome',
        flags: ['--headless=new', '--no-sandbox', '--disable-setuid-sandbox', '--disable-gpu'],
        env: { CHROME_BIN: process.env.OPERA_BIN }
      },
      BraveHeadless: {
        base: 'Chrome',
        flags: ['--headless=new', '--no-sandbox', '--disable-setuid-sandbox', '--disable-gpu'],
        env: { CHROME_BIN: process.env.BRAVE_BIN }
      }
    },
    autoWatch: false,
    singleRun: true,
    browserNoActivityTimeout: 120000, // 2 minutes
    browserDisconnectTimeout: 60000,  // 1 minute
    browserDisconnectTolerance: 2,    // Allow 2 disconnects before failing
    client: {
      args: ['shadow.test.karma.init']
    },
    mime: {
      'application/wasm': ['wasm']
    },
    reporters: ['spec']
  });
};
