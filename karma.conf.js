module.exports = function (config) {
  var karmaFile = process.env.KARMA_FILE || 'target/karma-test.js';
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
    logLevel: config.LOG_DEBUG,
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
