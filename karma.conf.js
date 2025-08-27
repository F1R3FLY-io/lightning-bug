module.exports = function (config) {
  config.set({
    frameworks: ['cljs-test'],
    files: [
      'target/karma-test.js',
      { pattern: 'resources/public/js/test/extensions/**', watched: false, included: false, served: true },
      { pattern: 'resources/public/js/test/js/tree-sitter.wasm', watched: false, included: false, served: true }
    ],
    proxies: {
      '/extensions/': '/base/resources/public/js/test/extensions/',
      '/js/tree-sitter.wasm': '/base/resources/public/js/test/js/tree-sitter.wasm'
    },
    colors: true,
    logLevel: config.LOG_INFO,
    browsers: ['ChromeHeadless'],
    autoWatch: false,
    singleRun: true,
    client: {
      args: ['shadow.test.karma.init']
    },
    mime: {
      'application/wasm': ['wasm']
    }
  });
};
