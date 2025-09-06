const fs = require('fs');
const path = require('path');
const { findPkgDir, runCmd } = require('./utils');

const baseDir = path.dirname(__dirname);
const demoDir = path.join(baseDir, 'resources/public/demo');

// MODE can be 'dev' or 'release', defaults to 'dev'
const mode = process.env.MODE || 'dev';

// Install deps and build libs in base dir.
runCmd('npm install', baseDir);
if (mode === 'release') {
  runCmd('npm run build:release', baseDir);
} else {
  runCmd('npm run build:debug', baseDir);
}

// In demo dir: install deps.
runCmd('npm install', demoDir);

// Find package directories
const treeSitterDir = findPkgDir('web-tree-sitter', demoDir);
const lightningBugDir = findPkgDir('@f1r3fly-io/lightning-bug', demoDir);

const treeSitterWasmPath = path.join(treeSitterDir, 'tree-sitter.wasm');
const extensionsSrc = path.join(lightningBugDir, 'resources/public/extensions');

// Create js dir if not exists.
const demoJsDir = path.join(demoDir, 'js');
fs.mkdirSync(demoJsDir, { recursive: true });
console.log(`Created directory ${demoJsDir}`);

// Copy tree-sitter.wasm to demo/js.
const demoWasmDest = path.join(demoJsDir, 'tree-sitter.wasm');
fs.copyFileSync(treeSitterWasmPath, demoWasmDest);
console.log(`Copied ${treeSitterWasmPath} to ${demoWasmDest}`);

// Copy extensions dir to demo (recursive).
const demoExtensionsDest = path.join(demoDir, 'extensions');
fs.cpSync(extensionsSrc, demoExtensionsDest, { recursive: true });
console.log(`Copied ${extensionsSrc} to ${demoExtensionsDest}`);
