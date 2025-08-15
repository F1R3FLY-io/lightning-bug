const fs = require('fs');
const path = require('path');
const cp = require('child_process');

const baseDir = path.dirname(__dirname);
const demoDir = path.join(baseDir, 'resources/public/demo');

// Function to run shell commands synchronously.
function runCmd(cmd, cwd = baseDir) {
  console.log(`Running: ${cmd} in ${cwd}`);
  cp.execSync(cmd, { stdio: 'inherit', cwd });
}

// Install deps and build libs in base dir.
runCmd('npm install', baseDir);
runCmd('npx shadow-cljs release libs', baseDir);

// In demo dir: install deps.
runCmd('npm install', demoDir);

// Function to find package directory by searching up the tree.
function findPkgDir(pkgName, startDir = demoDir) {
  let dir = startDir;
  const root = path.parse(dir).root;
  while (dir !== root) {
    const nm = path.join(dir, 'node_modules', pkgName);
    if (fs.existsSync(nm)) {
      return nm;
    }
    dir = path.dirname(dir);
  }
  throw new Error(`Package ${pkgName} not found`);
}

const treeSitterDir = findPkgDir('web-tree-sitter', demoDir);
const lightningBugDir = findPkgDir('@f1r3fly-io/lightning-bug', demoDir);

const treeSitterWasmPath = path.join(treeSitterDir, 'tree-sitter.wasm');
const extensionsSrc = path.join(lightningBugDir, 'resources/public/extensions');

// Create js dir.
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
