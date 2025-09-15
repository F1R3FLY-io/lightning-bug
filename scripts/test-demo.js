import fs from 'fs';
import http from 'http';
import path from 'path';
import puppeteer from 'puppeteer-core';

(async () => {
  console.log('Starting sanity test...');
  const demoDir = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..', 'resources', 'public', 'demo');
  const port = 3002; // Arbitrary port

  // Simple HTTP server to serve the demo directory
  const server = http.createServer((req, res) => {
    console.log(`Server request: ${req.url}`);
    const filePath = path.join(demoDir, req.url === '/' ? 'index.html' : req.url);
    fs.readFile(filePath, (err, data) => {
      if (err) {
        console.log(`Server 404: ${filePath}`);
        res.writeHead(404);
        res.end('Not found');
      } else {
        const ext = path.extname(filePath);
        const contentType = ext === '.html' ? 'text/html' : ext === '.js' ? 'application/javascript' : ext === '.css' ? 'text/css' : ext === '.wasm' ? 'application/wasm' : 'text/plain';
        res.writeHead(200, { 'Content-Type': contentType });
        res.end(data);
      }
    });
  });

  server.listen(port, () => {
    console.log(`Server running at http://localhost:${port}`);
  });

  // Determine browsers based on OS
  const isWindows = process.platform === 'win32';
  const isLinux = process.platform === 'linux';
  const isMacOS = process.platform === 'darwin';
  const browsers = [
    { name: 'Chrome', browser: 'chrome', executablePath: process.env.CHROME_BIN || (isMacOS ? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' : isWindows ? 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe' : '/usr/bin/google-chrome-stable'), args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-web-security'] },
    { name: 'Firefox', browser: 'firefox', executablePath: process.env.FIREFOX_BIN || (isMacOS ? '/Applications/Firefox.app/Contents/MacOS/firefox' : isWindows ? 'C:\\Program Files\\Mozilla Firefox\\firefox.exe' : '/usr/bin/firefox'), args: ['--headless', '--remote-debugging-port=0', '--remote-allow-origins=*'] },
    { name: 'Edge', browser: 'chrome', executablePath: process.env.EDGE_BIN || (isMacOS ? '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge' : isWindows ? 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe' : '/usr/bin/microsoft-edge-stable'), args: ['--no-sandbox', '--disable-setuid-sandbox', '--headless=new', '--disable-gpu'] },
    { name: 'Opera', browser: 'chrome', executablePath: process.env.OPERA_BIN || (isMacOS ? '/Applications/Opera.app/Contents/MacOS/Opera' : isWindows ? 'C:\\Users\\%USERNAME%\\AppData\\Local\\Programs\\Opera\\opera.exe' : '/usr/bin/opera'), args: ['--no-sandbox', '--headless', '--disable-gpu'] }
  ];
  // Safari is not supported by Puppeteer, so exclude it

  const testBrowser = process.env.TEST_BROWSER;
  let browsersToTest = browsers;
  if (testBrowser) {
    browsersToTest = browsers.filter(b => b.name.toLowerCase() === testBrowser.toLowerCase());
  }
  if (browsersToTest.length === 0) {
    console.log(`No browser matches ${testBrowser}, skipping sanity test`);
    server.close(() => {
      console.log('Server closed');
      process.exit(0);
    });
    return;
  }

  let allTestsPassed = true;

  for (const browserConfig of browsersToTest) {
    const { name, browser: browserType, executablePath, args } = browserConfig;
    console.log(`Testing with ${name}, executable: ${executablePath}`);

    try {
      console.log(`Launching ${name}...`);
      const launchOptions = {
        headless: true,
        browser: browserType,
        executablePath,
        args,
        dumpio: true, // Enable to debug stdout/stderr
        protocolTimeout: 60000, // Increase protocol timeout to 60s
        timeout: 0 // Disable launch timeout
      };
      const browser = await puppeteer.launch(launchOptions);
      console.log(`${name} launched`);
      const page = await browser.newPage();
      console.log(`${name} new page created`);

      page.on('request', request => {
        console.log(`${name} page request: ${request.url()}`);
      });

      page.on('response', response => {
        console.log(`${name} page response: ${response.url()} - ${response.status()}`);
        if (response.status() >= 400) {
          console.log(`${name} error response: ${response.url()} - ${response.status()}`);
        }
      });

      page.on('requestfailed', request => {
        console.log(`${name} failed request: ${request.url()} - ${request.failure().errorText}`);
      });

      let editorReadyResolve;
      const editorReady = new Promise(resolve => { editorReadyResolve = resolve; });
      let docOpenedResolve;
      const docOpened = new Promise(resolve => { docOpenedResolve = resolve; });

      page.on('console', msg => {
        const text = msg.text();
        console.log(`${name} page console: ${text}`);
        if (text.includes('Editor ready')) {
          editorReadyResolve();
        }
        if (text.includes('Event received: document-open')) {
          docOpenedResolve();
        }
      });

      console.log(`${name} navigating to http://localhost:${port}/index.html`);
      await page.goto(`http://localhost:${port}/index.html`, { waitUntil: 'networkidle2' });

      try {
        console.log(`${name} waiting for ready and opened...`);
        await Promise.race([
          Promise.all([editorReady, docOpened]),
          new Promise((_, reject) => setTimeout(() => reject(new Error(`Timeout waiting for logs in ${name}`)), 30000))
        ]);
        console.log(`${name} logs received`);
        const hasEditor = await page.evaluate(() => document.querySelector('.cm-editor') !== null);
        if (hasEditor) {
          console.log(`${name} sanity test passed`);
        } else {
          console.error(`${name} sanity test failed: .cm-editor not found`);
          allTestsPassed = false;
        }
      } catch (e) {
        console.error(`${name} sanity test failed: timeout or error`, e);
        allTestsPassed = false;
      } finally {
        await browser.close();
        console.log(`${name} browser closed`);
      }
    } catch (e) {
      console.error(`Failed to run test with ${name}:`, e);
      allTestsPassed = false;
    }
  }

  server.close(() => {
    console.log('Server closed');
    if (allTestsPassed) {
      console.log('All sanity tests passed');
      process.exit(0);
    } else {
      console.error('One or more sanity tests failed');
      process.exit(1);
    }
  });
})();
