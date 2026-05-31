import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';

const specs = [
  'LspConnection',
  'BrowserAsync',
  'DocSync',
  'LightningBugAsync'
];

function run(command, args) {
  const result = spawnSync(command, args, { stdio: 'inherit' });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`${command} ${args.join(' ')} failed with exit code ${result.status}`);
  }
}

for (const spec of specs) {
  const metaDir = mkdtempSync(join(tmpdir(), `lightning-bug-tlc-${spec}-`));
  try {
    run(
      'tlc',
      ['-workers', '1', '-metadir', metaDir, '-config', `formal/tla/${spec}.cfg`, `formal/tla/${spec}.tla`]
    );
  } finally {
    rmSync(metaDir, { recursive: true, force: true });
  }
}
