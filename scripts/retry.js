import { runShellCmd } from './utils.js';
import os from 'os';

const command = process.argv.slice(2).join(' ');
const platform = os.platform();
const maxAttempts = 3;
let success = false;

for (let attempt = 1; attempt <= maxAttempts; attempt++) {
  try {
    let fullCommand = command;
    if (platform === 'linux') fullCommand = `dbus-run-session -- xvfb-run --auto-servernum ${command}`;
    runShellCmd(fullCommand);
    success = true;
    break;
  } catch (e) {
    if (attempt < maxAttempts) console.log(`Attempt ${attempt} failed, retrying...`);
    else throw e;
  }
}

if (!success) process.exit(1);
