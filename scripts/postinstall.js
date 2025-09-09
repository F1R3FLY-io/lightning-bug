import fs from 'fs';
import path from 'path';
import { findPkgDir } from './utils.js';

const scriptDir = path.dirname(new URL(import.meta.url).pathname);
const baseDir = path.dirname(scriptDir);  // Project root (parent of scripts/)
const treeSitterDir = findPkgDir('web-tree-sitter');
const rholangDir = findPkgDir('@f1r3fly-io/tree-sitter-rholang-js-with-comments');

const treeSitterWasmPath = path.join(treeSitterDir, 'tree-sitter.wasm');
const rholangWasmPath = path.join(rholangDir, 'tree-sitter-rholang.wasm');

// Define target paths (create directories if needed).
const targets = [
  { src: treeSitterWasmPath, dest: path.join(baseDir, 'resources/public/js/tree-sitter.wasm') },
  { src: treeSitterWasmPath, dest: path.join(baseDir, 'resources/public/js/test/js/tree-sitter.wasm') },
  { src: rholangWasmPath, dest: path.join(baseDir, 'resources/public/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm') },
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
