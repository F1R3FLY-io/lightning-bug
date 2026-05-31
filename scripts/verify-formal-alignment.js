import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const fsmPath = 'src/lib/lsp/fsm.cljs';
const tlaPath = 'formal/tla/LspConnection.tla';
const rocqPath = 'formal/rocq/Async/Fsm.v';
const lspSourceRoot = 'src/lib/lsp';
const lspClientPath = 'src/lib/lsp/client.cljs';
const editorRuntimePath = 'src/lib/editor/runtime.cljs';
const editorCommandsPath = 'src/lib/editor/commands.cljs';
const ciWorkflowPath = '.github/workflows/ci.yaml';
const requiredTlaModels = [
  'LspConnection',
  'BrowserAsync',
  'DocSync',
  'LightningBugAsync'
];
const requiredTlaProofModules = [
  'proofs/LspConnectionProofs.tla',
  'proofs/BrowserAsyncProofs.tla',
  'proofs/DocSyncProofs.tla',
  'proofs/LightningBugAsyncProofs.tla'
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
const lspClient = readFileSync(lspClientPath, 'utf8');
const editorRuntime = readFileSync(editorRuntimePath, 'utf8');
const editorCommands = readFileSync(editorCommandsPath, 'utf8');
const ciWorkflow = readFileSync(ciWorkflowPath, 'utf8');

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

function assertContains(text, pattern, description, path) {
  if (!pattern.test(text)) {
    console.error(`Missing source/formal alignment fact in ${path}: ${description}`);
    process.exit(1);
  }
}

function assertNotContains(text, pattern, description, path) {
  if (pattern.test(text)) {
    console.error(`Forbidden source/formal alignment fact in ${path}: ${description}`);
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

  for (const proofModule of requiredTlaProofModules) {
    const path = `formal/tla/${proofModule}`;
    if (!existsSync(path)) {
      console.error(`Missing required TLAPS proof module: ${path}`);
      process.exit(1);
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

function verifyCiDoesNotRunTlaTools() {
  assertNotContains(
    ciWorkflow,
    /\b(verify:tla|verify:tlaps|verify:formal(?!:ci)|tlapm|tla2tools|TLA_TOOLS|Install TLC)\b/,
    'CI must not install or run TLC/TLA+/TLAPS',
    ciWorkflowPath
  );
  assertContains(
    ciWorkflow,
    /run:\s*npm run verify:formal:ci/,
    'CI formal job runs only the CI-safe formal gate',
    ciWorkflowPath
  );
}

function verifyLspShutdownAlignment() {
  assertContains(
    source,
    /:initializing\s+#\{[^}]*:disconnecting/,
    'shutdown while initialize is in flight has an explicit source FSM transition',
    fsmPath
  );
  assertContains(
    tla,
    /<<"initializing",\s*"disconnecting">>/,
    'shutdown while initialize is in flight has an explicit TLA+ FSM transition',
    tlaPath
  );
  assertContains(
    rocq,
    /\(Initializing,\s*Disconnecting\)/,
    'shutdown while initialize is in flight has an explicit Rocq FSM transition',
    rocqPath
  );
  assertContains(
    tla,
    /StartShutdown\(l\) ==[\s\S]*pending' = \[pending EXCEPT !\[l\] = \{nextId\[l\]\}\][\s\S]*nextId' = \[nextId EXCEPT !\[l\] = @ \+ 1\][\s\S]*shuttingDown' = \[shuttingDown EXCEPT !\[l\] = TRUE\]/,
    'StartShutdown clears unrelated pending work and records only the shutdown request',
    tlaPath
  );
  assertContains(
    tla,
    /Response\(l\) ==\s*\n\s*\/\\ shuttingDown\[l\] = FALSE/,
    'generic responses are disabled during graceful shutdown',
    tlaPath
  );
  assertContains(
    lspClient,
    /:shutting-down\? true\s*:pending \{\}[\s\S]*\(send lang \{:method "shutdown"\s*:response-type :shutdown\}/,
    'request-shutdown clears pending before sending the shutdown request',
    lspClientPath
  );
  assertContains(
    lspClient,
    /\(notify-exit lang state-atom\)[\s\S]*\(transition! state-atom lang :disconnected\)[\s\S]*\(close-resource!/,
    'shutdown response sends exit, transitions to disconnected, then closes the resource',
    lspClientPath
  );
}

function verifyLspTransitionGuardAlignment() {
  assertContains(
    lspClient,
    /\(if \(fsm\/valid-transition\? current next\)[\s\S]*\(swap! state-atom update-in \[:lsp lang\] merge \{:state next\}[\s\S]*Rejected invalid LSP state transition/s,
    'transition! rejects invalid transitions instead of mutating source state',
    lspClientPath
  );
  assertNotContains(
    lspClient,
    /outside declared TRANSITIONS/,
    'invalid transitions must not be accepted with a debug-only warning',
    lspClientPath
  );
}

function verifyDocumentLifecycleAlignment() {
  assertContains(
    editorRuntime,
    /\[:lsp-did-change uri\][\s\S]*\(fn \[\][\s\S]*\(let \[\[text lang\] \(db\/doc-text-lang-by-uri conn uri\)\]/,
    'debounced didChange flush uses the edited URI, not the active URI at flush time',
    editorRuntimePath
  );
  assertNotContains(
    editorRuntime,
    /\[:lsp-did-change uri\][\s\S]{0,500}\(let \[uri \(:active-uri @state-atom\)/,
    'debounced didChange callback must not re-read :active-uri',
    editorRuntimePath
  );
  assertContains(
    editorRuntime,
    /\(when-not from-api\?\s*\n\s*\(clear-visible-diagnostics! conn workspace uri\)\)/,
    'local user edits clear visible diagnostics for the edited URI',
    editorRuntimePath
  );
  assertContains(
    editorCommands,
    /shared-with-peer\?[\s\S]*\(when \(and opened\? \(not shared-with-peer\?\)\)[\s\S]*\(p\/notify-did-close! client lang uri\)/,
    'didClose is suppressed while another pane still references the URI',
    editorCommandsPath
  );
  assertContains(
    editorCommands,
    /\(when-not shared-with-peer\?\s*\n\s*\(db\/delete-document-by-id! conn id\)\)/,
    'shared pane close does not delete the workspace document',
    editorCommandsPath
  );
}

function verifyTlaAsyncPropertyCoverage() {
  const browser = readFileSync('formal/tla/BrowserAsync.tla', 'utf8');
  const docSync = readFileSync('formal/tla/DocSync.tla', 'utf8');
  const publicTrace = readFileSync('formal/tla/LightningBugAsync.tla', 'utf8');
  const proofText = requiredTlaProofModules
    .map((proofModule) => readFileSync(`formal/tla/${proofModule}`, 'utf8'))
    .join('\n');

  assertContains(browser, /writeHistory[\s\S]*NoUnmountedMutation ==\s*\n\s*\\A w \\in writeHistory: w\.wasMounted = TRUE/, 'browser async model records write history for mounted-only mutation safety', 'formal/tla/BrowserAsync.tla');
  assertContains(docSync, /DeliverySeqPrecedesWorkspace ==\s*\n\s*\\A d \\in deliveries: d\.n < workspaceText\[d\.uri\]/, 'doc sync model covers delivery sequence ordering', 'formal/tla/DocSync.tla');
  assertContains(publicTrace, /DidChangeSentAfterOpen ==\s*\n\s*\\A d \\in didChangeLog: d\.openedAtSend = TRUE/, 'public trace model covers didChange-after-open ordering', 'formal/tla/LightningBugAsync.tla');
  assertContains(publicTrace, /DidCloseOnlyWhenUnshared ==\s*\n\s*\\A d \\in didCloseLog: d\.sharedAtClose = FALSE/, 'public trace model covers didClose suppression for shared documents', 'formal/tla/LightningBugAsync.tla');
  assertContains(proofText, /THEOREM FlagConsistencyImpliesInitializedConnected/, 'TLAPS proves FSM flag implication', 'formal/tla/proofs');
  assertContains(proofText, /THEOREM FireDebounceWritesOnlyWhenMounted/, 'TLAPS proves mounted-only debounce writes', 'formal/tla/proofs');
  assertContains(proofText, /THEOREM UserEditDoesNotEchoToOrigin/, 'TLAPS proves no origin echo for doc sync deliveries', 'formal/tla/proofs');
  assertContains(proofText, /THEOREM FlushDidChangeRequiresOpen/, 'TLAPS proves didChange flush requires didOpen', 'formal/tla/proofs');
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
verifyCiDoesNotRunTlaTools();
verifyNoDirectStateWrites();
verifyLspShutdownAlignment();
verifyLspTransitionGuardAlignment();
verifyDocumentLifecycleAlignment();
verifyTlaAsyncPropertyCoverage();

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
