#!/usr/bin/env node
/**
 * Demo dependency drift check.
 *
 * The demo app (resources/public/demo) declares its own copies of several
 * dependencies that are ALSO declared by the root package (e.g. web-tree-sitter,
 * @codemirror/*). When the root bumps a version but the demo is not updated, the
 * demo silently runs against a different version than the published library was
 * tested with (audit finding: web-tree-sitter 0.25.9 vs 0.25.8, @codemirror/*
 * patch mismatches). This script fails if any dependency declared in BOTH the
 * root and the demo disagrees on its version specifier.
 *
 * Usage: node scripts/check-demo-deps.js
 * Exit 0 = in sync; exit 1 = drift detected (prints the mismatches).
 */

import { readFileSync } from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const baseDir = path.dirname(scriptDir); // project root (parent of scripts/)

function deps(pkgPath) {
  const pkg = JSON.parse(readFileSync(pkgPath, 'utf8'));
  return { ...(pkg.dependencies || {}), ...(pkg.devDependencies || {}) };
}

const rootDeps = deps(path.join(baseDir, 'package.json'));
const demoDeps = deps(path.join(baseDir, 'resources/public/demo/package.json'));

// The demo depends on the library itself via a file: link — never compare it.
const ignore = new Set(['@f1r3fly-io/lightning-bug']);

const mismatches = [];
for (const [name, demoVersion] of Object.entries(demoDeps)) {
  if (ignore.has(name)) continue;
  const rootVersion = rootDeps[name];
  if (rootVersion !== undefined && rootVersion !== demoVersion) {
    mismatches.push({ name, root: rootVersion, demo: demoVersion });
  }
}

if (mismatches.length > 0) {
  console.error('Demo/root dependency drift detected (shared deps must match):\n');
  for (const { name, root, demo } of mismatches) {
    console.error(`  ${name}: root=${root}  demo=${demo}`);
  }
  console.error('\nAlign resources/public/demo/package.json with the root package.json.');
  process.exit(1);
}

console.log('Demo dependencies are in sync with the root package.');
