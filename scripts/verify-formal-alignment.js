import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const fsmPath = 'src/lib/lsp/fsm.cljs';
const tlaPath = 'formal/tla/LspConnection.tla';
const rocqPath = 'formal/rocq/Async/Fsm.v';
const lspSourceRoot = 'src/lib/lsp';
const requiredTlaModels = [
  'LspConnection',
  'BrowserAsync',
  'DocSync',
  'LightningBugAsync'
];
const requiredRocqModules = [
  'Async/Fsm.v',
  'Async/Pending.v',
  'Async/DocSync.v',
  'Async/Debounce.v',
  'Async/PublicApiTrace.v'
];

const source = readFileSync(fsmPath, 'utf8');
const tla = readFileSync(tlaPath, 'utf8');
const rocq = readFileSync(rocqPath, 'utf8');

function uniqueSorted(items) {
  return [...new Set(items)].sort();
}

function parseQuotedSet(text, name) {
  const match = text.match(new RegExp(`${name} ==\\s*\\n\\s*\\{([\\s\\S]*?)\\}`));
  if (!match) {
    throw new Error(`Could not find ${name} set in ${tlaPath}`);
  }
  return uniqueSorted([...match[1].matchAll(/"([^"]+)"/g)].map((m) => m[1]));
}

function parseCljsKeywordSet(text, defName) {
  const match = text.match(new RegExp(`\\(def ${defName}[\\s\\S]*?#\\{([\\s\\S]*?)\\}\\)`));
  if (!match) {
    throw new Error(`Could not find ${defName} in ${fsmPath}`);
  }
  return uniqueSorted([...match[1].matchAll(/:(\S+)/g)].map((m) => m[1]));
}

function parseCljsTransitions(text) {
  const match = text.match(/\(def TRANSITIONS[\s\S]*?\n\s*\{([\s\S]*?)\}\)/);
  if (!match) {
    throw new Error(`Could not find TRANSITIONS in ${fsmPath}`);
  }
  const body = match[1];
  const transitions = [];
  const lineRe = /:(\S+)\s+#\{([^}]*)\}/g;
  let line;
  while ((line = lineRe.exec(body)) !== null) {
    const from = line[1];
    const targets = [...line[2].matchAll(/:(\S+)/g)].map((m) => m[1]);
    for (const to of targets) {
      transitions.push(`${from}->${to}`);
    }
  }
  return uniqueSorted(transitions);
}

function parseCljsConnectedStates(text) {
  const match = text.match(/\(defn state->connected\?[\s\S]*?#\{([^}]*)\}/);
  if (!match) {
    throw new Error(`Could not find state->connected? state set in ${fsmPath}`);
  }
  return uniqueSorted([...match[1].matchAll(/:(\S+)/g)].map((m) => m[1]));
}

function parseTlaTransitions(text) {
  const match = text.match(/AllowedTransition ==\s*\n\s*\{([\s\S]*?)\}\n/);
  if (!match) {
    throw new Error(`Could not find AllowedTransition in ${tlaPath}`);
  }
  return [...match[1].matchAll(/<<"([^"]+)",\s*"([^"]+)">>/g)]
    .map((m) => `${m[1]}->${m[2]}`)
    .sort();
}

function parseTlaConnectedStates(text) {
  const match = text.match(/ConnectedState\(s\) == s \\in \{([^}]*)\}/);
  if (!match) {
    throw new Error(`Could not find ConnectedState in ${tlaPath}`);
  }
  return uniqueSorted([...match[1].matchAll(/"([^"]+)"/g)].map((m) => m[1]));
}

function rocqNameToSourceName(name) {
  return name.replace(/([a-z])([A-Z])/g, '$1-$2').toLowerCase();
}

function parseRocqStates(text) {
  const match = text.match(/Definition all_states : list state :=\s*\[([\s\S]*?)\]\./);
  if (!match) {
    throw new Error(`Could not find all_states in ${rocqPath}`);
  }
  return uniqueSorted([...match[1].matchAll(/\b([A-Z][A-Za-z]*)\b/g)].map((m) => rocqNameToSourceName(m[1])));
}

function parseRocqTransitions(text) {
  const match = text.match(/Definition all_transitions : list \(state \* state\) :=\s*\[([\s\S]*?)\]\./);
  if (!match) {
    throw new Error(`Could not find all_transitions in ${rocqPath}`);
  }
  return uniqueSorted([...match[1].matchAll(/\(([A-Z][A-Za-z]*),\s*([A-Z][A-Za-z]*)\)/g)]
    .map((m) => `${rocqNameToSourceName(m[1])}->${rocqNameToSourceName(m[2])}`));
}

function parseRocqConnectedStates(text) {
  const match = text.match(/Definition connected_state[\s\S]*?match s with\s*\|\s*([^=]+)=> true/);
  if (!match) {
    throw new Error(`Could not find connected_state true branch in ${rocqPath}`);
  }
  return uniqueSorted([...match[1].matchAll(/\b([A-Z][A-Za-z]*)\b/g)].map((m) => rocqNameToSourceName(m[1])));
}

function walkFiles(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return walkFiles(path);
    return entry.isFile() ? [path] : [];
  });
}

function diff(a, b) {
  const bSet = new Set(b);
  return a.filter((x) => !bSet.has(x));
}

function assertSame(label, expected, actual, expectedName, actualName) {
  const missing = diff(expected, actual);
  const extra = diff(actual, expected);
  if (missing.length > 0 || extra.length > 0) {
    console.error(`${label} alignment failed: ${expectedName} != ${actualName}.`);
    if (missing.length > 0) {
      console.error(`Missing in ${actualName}:`);
      for (const item of missing) console.error(`  ${item}`);
    }
    if (extra.length > 0) {
      console.error(`Missing in ${expectedName}:`);
      for (const item of extra) console.error(`  ${item}`);
    }
    process.exit(1);
  }
}

function verifyRequiredFiles() {
  for (const model of requiredTlaModels) {
    for (const ext of ['tla', 'cfg']) {
      const path = `formal/tla/${model}.${ext}`;
      if (!existsSync(path)) {
        console.error(`Missing required TLA+ ${ext} file: ${path}`);
        process.exit(1);
      }
    }
  }

  for (const module of requiredRocqModules) {
    const path = `formal/rocq/${module}`;
    if (!existsSync(path)) {
      console.error(`Missing required Rocq module: ${path}`);
      process.exit(1);
    }
  }
}

function verifyNoDirectStateWrites() {
  const offenders = [];
  for (const file of walkFiles(lspSourceRoot).filter((path) => path.endsWith('.cljs'))) {
    const text = readFileSync(file, 'utf8');
    const lines = text.split('\n');
    lines.forEach((line, index) => {
      const directAssocIn = /assoc-in\s+\[:lsp\b[^\]]*:state\]/.test(line);
      const directNestedAssoc = /update-in\s+\[:lsp\b[^\]]*\]\s+assoc\b.*:state/.test(line);
      if (directAssocIn || directNestedAssoc) {
        offenders.push(`${file}:${index + 1}: ${line.trim()}`);
      }
    });
  }

  if (offenders.length > 0) {
    console.error('Direct LSP state writes bypass lib.lsp.client/transition!:');
    for (const offender of offenders) console.error(`  ${offender}`);
    process.exit(1);
  }
}

verifyRequiredFiles();
verifyNoDirectStateWrites();

const sourceStates = parseCljsKeywordSet(source, 'STATES');
const sourceTransitions = parseCljsTransitions(source);
const sourceConnectedStates = parseCljsConnectedStates(source);

const tlaStates = parseQuotedSet(tla, 'States');
const tlaTransitions = parseTlaTransitions(tla);
const tlaConnectedStates = parseTlaConnectedStates(tla);

const rocqStates = parseRocqStates(rocq);
const rocqTransitions = parseRocqTransitions(rocq);
const rocqConnectedStates = parseRocqConnectedStates(rocq);

assertSame('State set', sourceStates, tlaStates, fsmPath, tlaPath);
assertSame('State set', sourceStates, rocqStates, fsmPath, rocqPath);
assertSame('Transition set', sourceTransitions, tlaTransitions, fsmPath, tlaPath);
assertSame('Transition set', sourceTransitions, rocqTransitions, fsmPath, rocqPath);
assertSame('Connected-state projection', sourceConnectedStates, tlaConnectedStates, fsmPath, tlaPath);
assertSame('Connected-state projection', sourceConnectedStates, rocqConnectedStates, fsmPath, rocqPath);

console.log(
  `Formal/source alignment OK (${sourceStates.length} states, ${sourceTransitions.length} transitions, ${sourceConnectedStates.length} connected states).`
);
