import path from 'path';
import { runCmd } from './utils.js';

const scriptDir = path.dirname(new URL(import.meta.url).pathname);
const baseDir = path.dirname(scriptDir);
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
