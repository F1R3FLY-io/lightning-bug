export default async function (config) {
  const karmaFile = process.env.KARMA_FILE || 'target/karma-test.js';
  const [karmaCljsTest, karmaChromeLauncher, karmaSpecReporter] = await Promise.all([
    import('karma-cljs-test'),
    import('karma-chrome-launcher'),
    import('karma-spec-reporter')
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
    browsers: ['ChromeHeadless'],
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
      karmaSpecReporter.default
    ]
  });
};
