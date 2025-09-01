module.exports = function (config) {
  var karmaFile = process.env.KARMA_FILE || 'target/karma-test.js';
  config.set({
    frameworks: ['cljs-test'],
    files: [
      karmaFile,
      { pattern: 'resources/public/js/test/extensions/**', watched: false, included: false, served: true },
      { pattern: 'resources/public/js/test/js/tree-sitter.wasm', watched: false, included: false, served: true },
      { pattern: 'target/*.map', watched: false, included: false, served: true } // Serve source maps
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
    preprocessors: {
      '**/*.js': ['sourcemap'] // Enable source map loading for JS files
    },
    reporters: ['spec'],
    plugins: [
      require('karma-cljs-test'),
      require('karma-chrome-launcher'),
      require('karma-sourcemap-loader'),
      require('karma-spec-reporter')
    ]
  });
};
