import { existsSync, mkdtempSync, rmSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { delimiter, join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

const repoRoot = process.cwd();
const tlapsRoot = join(homedir(), '.local', 'tlaps');
const tlapm = join(tlapsRoot, 'bin', 'tlapm');
const tlapsLib = join(tlapsRoot, 'lib', 'tlaps');
const tlapsBin = join(tlapsLib, 'bin');
const tlapsIsabelleBin = join(tlapsLib, 'Isabelle2011-1', 'bin');
const formalTlaDir = join(repoRoot, 'formal', 'tla');
const formalTlaProofsDir = join(formalTlaDir, 'proofs');

const proofModules = [
  'formal/tla/proofs/LspConnectionProofs.tla',
  'formal/tla/proofs/LspConnectionInductiveProofs.tla',
  'formal/tla/proofs/LspConnectionLivenessProofs.tla',
  'formal/tla/proofs/BrowserAsyncProofs.tla',
  'formal/tla/proofs/BrowserAsyncLivenessProofs.tla',
  'formal/tla/proofs/DocSyncProofs.tla',
  'formal/tla/proofs/LightningBugAsyncProofs.tla',
  'formal/tla/proofs/LightningBugAsyncLivenessProofs.tla'
];

if (!existsSync(tlapm)) {
  throw new Error(`TLAPS not found at ${tlapm}`);
}

for (const proofModule of proofModules) {
  if (!existsSync(proofModule)) {
    throw new Error(`Missing TLAPS proof module: ${proofModule}`);
  }
}

const env = {
  ...process.env,
  PATH: [tlapsBin, tlapsIsabelleBin, join(tlapsRoot, 'bin'), process.env.PATH]
    .filter(Boolean)
    .join(delimiter)
};

const workDir = mkdtempSync(join(tmpdir(), 'lightning-bug-tlaps-'));

try {
  for (const proofModule of proofModules) {
    const result = spawnSync(
      tlapm,
      [
        '--nofp',
        '-I',
        formalTlaDir,
        '-I',
        formalTlaProofsDir,
        '-I',
        tlapsLib,
        resolve(repoRoot, proofModule)
      ],
      { stdio: 'inherit', env, cwd: workDir }
    );
    if (result.error) throw result.error;
    if (result.status !== 0) {
      throw new Error(`tlapm failed for ${proofModule}`);
    }
  }
} finally {
  rmSync(workDir, { recursive: true, force: true });
}
