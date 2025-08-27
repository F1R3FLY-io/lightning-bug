const fs = require('fs');
const path = require('path');

const baseDir = path.dirname(__dirname);

// Function to find package directory by searching up the tree.
function findPkgDir(pkgName) {
  let dir = __dirname;
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

const treeSitterDir = findPkgDir('web-tree-sitter');
const rholangDir = findPkgDir('@f1r3fly-io/tree-sitter-rholang-js-with-comments');

const treeSitterWasmPath = path.join(treeSitterDir, 'tree-sitter.wasm');
const rholangWasmPath = path.join(rholangDir, 'tree-sitter-rholang.wasm');

// Define target paths (create directories if needed).
const targets = [
  { src: treeSitterWasmPath, dest: path.join(baseDir, 'resources/public/js/tree-sitter.wasm') },
  { src: treeSitterWasmPath, dest: path.join(baseDir, 'resources/public/js/test/js/tree-sitter.wasm') },
  { src: rholangWasmPath, dest: path.join(baseDir, 'resources/public/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm') },
  // NOTE: The rholang parser WASM is copied to the testing extensions dir further in the script
  // { src: rholangWasmPath, dest: path.join(baseDir, 'resources/public/js/test/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm') }
];

targets.forEach(({ src, dest }) => {
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  fs.copyFileSync(src, dest);
  console.log(`Copied ${src} to ${dest}`);
});

// Copy full extensions dir to test (includes queries and all subdirs).
const extensionsSrc = path.join(baseDir, 'resources/public/extensions');
const testExtensionsDest = path.join(baseDir, 'resources/public/js/test/extensions');
fs.cpSync(extensionsSrc, testExtensionsDest, { recursive: true });
console.log(`Copied ${extensionsSrc} to ${testExtensionsDest}`);
