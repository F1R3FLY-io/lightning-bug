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

  const executablePath = process.env.CHROME_BIN || '/usr/bin/google-chrome-stable';
  console.log(`Using Chrome executable: ${executablePath}`);
  console.log('Launching browser...');
  const browser = await puppeteer.launch({
    headless: true,
    executablePath,
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-web-security']
  });
  console.log('Browser launched');
  const page = await browser.newPage();
  console.log('New page created');

  page.on('request', request => {
    console.log(`Page request: ${request.url()}`);
  });

  page.on('response', response => {
    console.log(`Page response: ${response.url()} - ${response.status()}`);
    if (response.status() >= 400) {
      console.log(`Error response: ${response.url()} - ${response.status()}`);
    }
  });

  page.on('requestfailed', request => {
    console.log(`Failed request: ${request.url()} - ${request.failure().errorText}`);
  });

  let editorReadyResolve;
  const editorReady = new Promise(resolve => { editorReadyResolve = resolve; });
  let docOpenedResolve;
  const docOpened = new Promise(resolve => { docOpenedResolve = resolve; });

  page.on('console', msg => {
    const text = msg.text();
    console.log(`Page console: ${text}`);
    if (text.includes('Editor ready')) {
      editorReadyResolve();
    }
    if (text.includes('Event received: document-open')) {
      docOpenedResolve();
    }
  });

  console.log(`Navigating to http://localhost:${port}/index.html`);
  await page.goto(`http://localhost:${port}/index.html`, { waitUntil: 'networkidle2' });

  try {
    console.log('Waiting for ready and opened...');
    await Promise.race([
      Promise.all([editorReady, docOpened]),
      new Promise((_, reject) => setTimeout(() => reject(new Error('Timeout waiting for logs')), 30000))
    ]);
    console.log('Logs received');
    const hasEditor = await page.evaluate(() => document.querySelector('.cm-editor') !== null);
    if (hasEditor) {
      console.log('Sanity test passed');
      await browser.close();
      server.close();
      process.exit(0);
    } else {
      console.error('Sanity test failed: .cm-editor not found');
      await browser.close();
      server.close();
      process.exit(1);
    }
  } catch (e) {
    console.error('Sanity test failed: timeout or error', e);
    await browser.close();
    server.close();
    process.exit(1);
  }
})();
