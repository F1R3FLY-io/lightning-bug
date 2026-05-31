import { readdirSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';

const root = 'formal/rocq';

function walk(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return walk(path);
    return entry.isFile() && path.endsWith('.v') ? [path] : [];
  });
}

function cleanGenerated(dir) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) {
      cleanGenerated(path);
    } else if (/\.(aux|vo|vos|vok|glob)$/.test(entry.name) || entry.name === '.lia.cache') {
      rmSync(path, { force: true });
    }
  }
}

const files = walk(root).sort((a, b) => {
  if (a.endsWith('Fsm.v')) return -1;
  if (b.endsWith('Fsm.v')) return 1;
  return a.localeCompare(b);
});

cleanGenerated(root);

for (const file of files) {
  const result = spawnSync('rocq', ['compile', '-Q', root, 'LightningBug', file], { stdio: 'inherit' });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`rocq compile failed for ${file}`);
  }
}

cleanGenerated(root);
