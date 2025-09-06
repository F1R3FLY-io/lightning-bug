const child_process = require('child_process');
const fs = require('fs');
const path = require('path');

/**
 * Finds the directory of a package by searching up the directory tree.
 * @param {string} pkgName - The name of the package to find.
 * @param {string} [startDir=__dirname] - The starting directory for the search.
 * @returns {string} The path to the package directory.
 * @throws {Error} If the package is not found.
 */
exports.findPkgDir = function findPkgDir(pkgName, startDir = __dirname) {
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
};

/**
 * Runs a shell command synchronously in the specified directory.
 * @param {string} cmd - The command to run.
 * @param {string} [cwd=path.dirname(__dirname)] - The working directory.
 */
exports.runCmd = function runCmd(cmd, cwd = path.dirname(__dirname)) {
  console.log(`Running: ${cmd} in ${cwd}`);
  child_process.execSync(cmd, { stdio: 'inherit', cwd });
};
