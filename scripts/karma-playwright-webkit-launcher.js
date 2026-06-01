import childProcess from 'child_process';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { createRequire } from 'module';

const require = createRequire(import.meta.url);

function makeTempDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'lightning-bug-webkit-'));
}

function rmTempDir(tempDir) {
  if (tempDir) {
    fs.rmSync(tempDir, { recursive: true, force: true });
  }
}

function getPlaywrightWebkitExecutable() {
  if (process.platform !== 'darwin') {
    return '';
  }

  for (const packageName of ['playwright', 'playwright-core']) {
    try {
      const playwright = require(packageName);
      const executable = playwright.webkit?.executablePath();
      if (executable) {
        return executable;
      }
    } catch (e) {
      if (e?.code !== 'MODULE_NOT_FOUND') {
        throw e;
      }
    }
  }
  return '';
}

function addTestBrowserInformation(url) {
  const nextUrl = new URL(url);
  nextUrl.searchParams.append('test_browser', 'Playwright');
  return nextUrl.toString();
}

function killChildProcesses(processIds) {
  for (const processId of processIds) {
    try {
      process.kill(processId, 'SIGHUP');
    } catch (e) {
      if (e.code !== 'ESRCH') {
        throw e;
      }
    }
  }
}

function killOrphanedMiniBrowser(done) {
  childProcess.exec('ps -eo pid,ppid,comm', (error, stdout) => {
    if (error) {
      done();
      return;
    }

    const processIds = stdout
      .split('\n')
      .map(line => line.trim().match(/^(\d+)\s+1\s+MiniBrowser$/))
      .filter(Boolean)
      .map(match => Number(match[1]));

    killChildProcesses(processIds);
    done();
  });
}

function WebkitHeadlessBrowser(baseBrowserDecorator, args) {
  baseBrowserDecorator(this);
  let tempDir;

  this._start = (url) => {
    tempDir = makeTempDir();
    const platformFlags = process.platform === 'darwin' || process.platform === 'win32'
      ? ['--headless', '--disable-gpu']
      : ['--headless'];
    const configuredFlags = Array.isArray(args?.flags) ? args.flags : [];
    this._execCommand(
      this._getCommand(),
      [
        addTestBrowserInformation(url),
        `--user-data-dir=${tempDir}`,
        ...configuredFlags,
        ...platformFlags
      ]
    );
  };

  this.on('kill', (done) => {
    const finish = () => {
      rmTempDir(tempDir);
      done();
    };

    if (process.platform === 'linux') {
      killOrphanedMiniBrowser(finish);
    } else {
      finish();
    }
  });

  this.on('done', () => {
    rmTempDir(tempDir);
  });
}

WebkitHeadlessBrowser.prototype = {
  name: 'WebkitHeadless',
  DEFAULT_CMD: {
    linux: '',
    darwin: getPlaywrightWebkitExecutable(),
    win32: ''
  },
  ENV_CMD: 'WEBKIT_HEADLESS_BIN'
};

WebkitHeadlessBrowser.$inject = ['baseBrowserDecorator', 'args'];

export default {
  'launcher:WebkitHeadless': ['type', WebkitHeadlessBrowser]
};
