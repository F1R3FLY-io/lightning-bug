import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { spawnSync } from 'child_process';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

/**
 * Finds the directory of a package by searching up the directory tree.
 * @param {string} pkgName - The name of the package to find.
 * @param {string} [startDir=__dirname] - The starting directory for the search.
 * @returns {string} The path to the package directory.
 * @throws {Error} If the package is not found.
 */
function findPkgDir(pkg) {
  // Walk up from current dir until package.json is found, then resolve node_modules/pkg
  let currentDir = __dirname;
  while (currentDir !== path.parse(currentDir).root) {
    const pkgPath = path.join(currentDir, 'node_modules', pkg);
    if (fs.existsSync(pkgPath)) {
      return pkgPath;
    }
    currentDir = path.dirname(currentDir);
  }
  throw new Error(`Package ${pkg} not found in node_modules`);
}

/**
 * Runs a shell command synchronously in the specified directory.
 * @param {string} cmd - The command to run.
 * @param {string} [cwd=__dirname] - The working directory.
 */
function runCmd(cmd, cwd = __dirname) {
  const parts = cmd.trim().split(/\s+/);
  const command = parts[0];
  const args = parts.slice(1);
  const result = spawnSync(command, args, { cwd, stdio: 'inherit' });
  if (result.error) {
    throw result.error;
  }
  if (result.status !== 0) {
    throw new Error(`Command failed: ${cmd} (exit code: ${result.status})`);
  }
  return result;
}

export { findPkgDir, runCmd };
