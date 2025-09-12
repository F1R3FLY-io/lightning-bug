export default async function (config) {
  const karmaFile = process.env.KARMA_FILE || 'target/karma-test.js';
  const [karmaCljsTest, karmaChromeLauncher, karmaSpecReporter, karmaFirefoxLauncher, karmaOperaLauncher, karmaSafariLauncher] = await Promise.all([
    import('karma-cljs-test'),
    import('karma-chrome-launcher'),
    import('karma-spec-reporter'),
    import('karma-firefox-launcher'),
    import('karma-opera-launcher'),
    import('karma-safari-launcher')
  ]);

  config.set({
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
        base: 'Opera',
        flags: ['--headless', '--disable-gpu', '--no-sandbox']
      }
    },
    autoWatch: false,
    singleRun: true,
    client: {
      args: ['shadow.test.karma.init']
    },
    mime: {
      'application/wasm': ['wasm']
    },
    reporters: ['spec'],
    plugins: [
      karmaCljsTest.default,
      karmaChromeLauncher.default,
      karmaSpecReporter.default,
      karmaFirefoxLauncher.default,
      karmaOperaLauncher.default,
      karmaSafariLauncher.default
    ]
  });
};
