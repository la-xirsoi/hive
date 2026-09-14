// Karma configuration for the Hive web frontend.
// Docs: https://karma-runner.github.io/latest/config/configuration-file.html
//
// This file is consumed by the Angular `@angular/build:unit-test` builder via the
// `runnerConfig` option in angular.json (runner: "karma"). Because an explicit config
// file is supplied, the builder does NOT inject its built-in defaults - everything
// Karma needs (frameworks, plugins, launchers, reporters) must be declared here.

const path = require('node:path');
const fs = require('node:fs');

// Make headless Chrome work without the developer exporting anything.
// Honour an existing CHROME_BIN, otherwise fall back to the standard install paths.
if (!process.env['CHROME_BIN']) {
  const candidates = [
    'C:\Program Files\Google\Chrome\Application\chrome.exe',
    'C:\Program Files (x86)\Google\Chrome\Application\chrome.exe',
    '/usr/bin/google-chrome',
    '/usr/bin/chromium-browser',
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  ];
  const found = candidates.find((candidate) => {
    try {
      return fs.existsSync(candidate);
    } catch {
      return false;
    }
  });
  if (found) {
    process.env['CHROME_BIN'] = found;
  }
  // If nothing is found we leave CHROME_BIN unset and let karma-chrome-launcher
  // perform its own discovery.
}

module.exports = function (config) {
  config.set({
    basePath: '',
    frameworks: ['jasmine'],
    plugins: [
      require('karma-jasmine'),
      require('karma-chrome-launcher'),
      require('karma-jasmine-html-reporter'),
      require('karma-coverage'),
    ],
    client: {
      jasmine: {
        // Uncomment to enable random test ordering:
        // random: true,
      },
    },
    jasmineHtmlReporter: {
      suppressAll: true, // removes the duplicated traces
    },
    coverageReporter: {
      dir: path.join(__dirname, 'coverage'),
      subdir: '.',
      reporters: [{ type: 'lcov' }, { type: 'text-summary' }],
      // Coverage thresholds are wired up but intentionally NOT enforced yet.
      // A follow-up issue raises these to the 70% quality gate; bump the numbers
      // below (statements/branches/functions/lines) to turn the gate on.
      check: {
        global: {
          statements: 0,
          branches: 0,
          functions: 0,
          lines: 0,
        },
      },
    },
    reporters: ['progress', 'kjhtml'],
    browsers: ['ChromeHeadlessNoSandbox'],
    customLaunchers: {
      ChromeHeadlessNoSandbox: {
        base: 'ChromeHeadless',
        flags: ['--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage'],
      },
    },
    port: 9876,
    colors: true,
    logLevel: config.LOG_INFO,
    autoWatch: true,
    singleRun: false,
    restartOnFileChange: true,
  });
};
