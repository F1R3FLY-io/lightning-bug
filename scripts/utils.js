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
export function findPkgDir(pkg) {
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
 * @param {string} [cwd=process.cwd()] - The working directory.
 */
export function runCmd(command, cwd = process.cwd()) {
  const parts = command.split(/\s+/);
  const options = {
    cwd,
    stdio: 'inherit',
    shell: process.platform === 'win32'
  };
  const result = spawnSync(parts[0], parts.slice(1), options);
  if (result.error) {
    throw result.error;
  }
  if (result.status !== 0) {
    throw new Error(`Command "${command}" failed with exit code ${result.status}`);
  }
}

/**
 * Runs a complex shell command (with pipes, redirects, etc.) synchronously using the shell.
 * Uses 'pwsh' on Windows, default shell on others.
 * @param {string} command - The full command string to run.
 * @param {string} [cwd=process.cwd()] - The working directory.
 */
export function runShellCmd(command, cwd = process.cwd()) {
  const shell = process.platform === 'win32' ? 'pwsh' : true;
  const options = { cwd, stdio: 'inherit', shell };
  const result = spawnSync(command, [], options);
  if (result.error) {
    throw result.error;
  }
  if (result.status !== 0) {
    throw new Error(`Command "${command}" failed with exit code ${result.status}`);
  }
}
