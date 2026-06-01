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
  'LspConnectionInductiveCheck',
  'LspConnectionLiveness',
  'BrowserAsync',
  'BrowserAsyncInductiveCheck',
  'BrowserAsyncLiveness',
  'DocSync',
  'DocSyncInductiveCheck',
  'LightningBugAsync',
  'LightningBugAsyncInductiveCheck',
  'LightningBugAsyncLiveness'
];
const requiredTlaProofModules = [
  'proofs/LspConnectionProofs.tla',
  'proofs/LspConnectionInductiveProofs.tla',
  'proofs/LspConnectionLivenessProofs.tla',
  'proofs/BrowserAsyncProofs.tla',
  'proofs/BrowserAsyncLivenessProofs.tla',
  'proofs/DocSyncProofs.tla',
  'proofs/LightningBugAsyncProofs.tla',
  'proofs/LightningBugAsyncLivenessProofs.tla'
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
    /PendingKinds == \{"none", "initialize", "request", "shutdown"\}[\s\S]*VARIABLE pendingKind/,
    'TLA+ LSP model tracks pending request kind, not just request IDs',
    tlaPath
  );
  assertContains(
    tla,
    /StartShutdown\(l\) ==[\s\S]*pendingKind' = \[pendingKind EXCEPT !\[l\] = OnlyPendingKind\(nextId\[l\], "shutdown"\)\]/,
    'StartShutdown records exactly one typed shutdown request in the TLA+ model',
    tlaPath
  );
  assertContains(
    tla,
    /ShutdownPendingIsShutdown ==[\s\S]*shuttingDown\[l\] =>[\s\S]*pendingKind\[l\]\[id\] = "shutdown"/,
    'TLA+ model proves graceful shutdown pending work is typed as shutdown',
    tlaPath
  );
  assertContains(
    tla,
    /Response\(l\) ==\s*\n\s*\/\\ shuttingDown\[l\] = FALSE\s*\n\s*\/\\ state\[l\] = "initialized"[\s\S]*pendingKind\[l\]\[id\] = "request"[\s\S]*pendingKind' = \[pendingKind EXCEPT !\[l\] = \[@ EXCEPT !\[id\] = "none"\]\]/,
    'generic responses are disabled during graceful shutdown and remove only matching normal requests',
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

function verifyLspRequestResponseAlignment() {
  assertContains(
    lspClient,
    /let \[response-type \(:response-type msg\)[\s\S]*next-id \(get-in @state-atom \[:lsp lang :next-id\] 1\)[\s\S]*\(swap! state-atom assoc-in \[:lsp lang :next-id\] \(inc next-id\)\)[\s\S]*\(swap! state-atom assoc-in \[:lsp lang :pending id\]/,
    'source send assigns monotonic request IDs and records pending metadata by ID',
    lspClientPath
  );
  assertContains(
    lspClient,
    /\(if extra-uri\s*\n\s*\{:type response-type :uri extra-uri\}\s*\n\s*response-type\)/,
    'source pending metadata records response type and optional URI',
    lspClientPath
  );
  assertContains(
    lspClient,
    /:response\s*\n\s*\(let \[id \(:id parsed\)[\s\S]*pending \(get-in @state-atom \[:lsp lang :pending id\]\)[\s\S]*pending-type \(if \(map\? pending\) \(:type pending\) pending\)[\s\S]*\(swap! state-atom update-in \[:lsp lang :pending\] dissoc id\)/,
    'source responses match pending requests by ID and clear exactly that ID before dispatch',
    lspClientPath
  );
  assertContains(
    lspClient,
    /\(case pending-type\s*\n\s*:initialize \(handle-initialize-response[\s\S]*:document-symbol \(handle-document-symbol-response[\s\S]*:shutdown \(handle-shutdown-response/,
    'source response dispatch is keyed by the matched pending request type',
    lspClientPath
  );
  assertContains(
    tla,
    /PendingKindsMatchPending ==[\s\S]*\(id \\in pending\[l\]\) <=> \(pendingKind\[l\]\[id\] # "none"\)/,
    'TLA+ model ties non-empty pending request kind to pending request membership',
    tlaPath
  );
  assertContains(
    tla,
    /PendingIdsAreIssued ==[\s\S]*id < nextId\[l\]/,
    'TLA+ model requires pending request IDs to have already been issued',
    tlaPath
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
  const lspInductiveCheck = readFileSync('formal/tla/LspConnectionInductiveCheck.tla', 'utf8');
  const lspLiveness = readFileSync('formal/tla/LspConnectionLiveness.tla', 'utf8');
  const browserInductiveCheck = readFileSync('formal/tla/BrowserAsyncInductiveCheck.tla', 'utf8');
  const browserLiveness = readFileSync('formal/tla/BrowserAsyncLiveness.tla', 'utf8');
  const docSyncInductiveCheck = readFileSync('formal/tla/DocSyncInductiveCheck.tla', 'utf8');
  const publicTraceInductiveCheck = readFileSync('formal/tla/LightningBugAsyncInductiveCheck.tla', 'utf8');
  const publicTraceLiveness = readFileSync('formal/tla/LightningBugAsyncLiveness.tla', 'utf8');
  const proofText = requiredTlaProofModules
    .map((proofModule) => readFileSync(`formal/tla/${proofModule}`, 'utf8'))
    .join('\n');

  assertContains(tla, /ShutdownStateConsistent ==[\s\S]*shuttingDown\[l\] =>[\s\S]*state\[l\] = "disconnecting"[\s\S]*\\E id \\in 1\.\.MaxPendingId:[\s\S]*pending\[l\] = \{id\}/, 'LSP model keeps shutdown state tied to exactly one pending request ID', tlaPath);
  assertContains(tla, /ReconnectQueuedOnlyWhileDisconnected ==[\s\S]*reconnects\[l\] > 0 => state\[l\] = "disconnected"/, 'LSP model forbids queued reconnects outside disconnected state', tlaPath);
  assertContains(tla, /ReconnectQueueIsUnit ==[\s\S]*reconnects\[l\] <= 1/, 'LSP model bounds reconnect queue to a single browser reconnect attempt', tlaPath);
  assertContains(lspInductiveCheck, /InductiveInit == LspInv[\s\S]*InductiveSpec == InductiveInit \/\\ \[\]\[Next\]_vars/, 'local TLC inductive-check model starts from the invariant itself', 'formal/tla/LspConnectionInductiveCheck.tla');
  assertContains(lspLiveness, /ConnectProgress\(l\) ==[\s\S]*ShutdownCloseProgress\(l\) ==[\s\S]*ReconnectProgress\(l\) ==/, 'LSP liveness model names explicit source-aligned progress actions', 'formal/tla/LspConnectionLiveness.tla');
  assertContains(lspLiveness, /WF_vars\(ConnectProgress\(l\)\)[\s\S]*WF_vars\(ShutdownCloseProgress\(l\)\)[\s\S]*WF_vars\(ReconnectProgress\(l\)\)/, 'LSP liveness model checks connect, shutdown, and reconnect progress under weak fairness', 'formal/tla/LspConnectionLiveness.tla');
  assertContains(lspLiveness, /ConnectingEventuallyResolves[\s\S]*ShutdownEventuallyDisconnects[\s\S]*QueuedReconnectEventuallyConsumed[\s\S]*QueuedReconnectEventuallyAttempts/, 'LSP liveness model names the browser connection progress properties', 'formal/tla/LspConnectionLiveness.tla');
  assertContains(browserInductiveCheck, /InductiveInit == BrowserInv[\s\S]*InductiveSpec == InductiveInit \/\\ \[\]\[Next\]_vars/, 'BrowserAsync local TLC inductive-check model starts from BrowserInv', 'formal/tla/BrowserAsyncInductiveCheck.tla');
  assertContains(browser, /WriteRecords == \{\[pane \|-> p, wasMounted \|-> TRUE\] : p \\in Panes\}/, 'browser async model records only mounted writes in the write-history type', 'formal/tla/BrowserAsync.tla');
  assertNotContains(browser, /\b(dbWrites|MaxWrites)\b/, 'browser async proof model must not depend on an irrelevant finite write counter', 'formal/tla/BrowserAsync.tla');
  assertContains(browser, /writeHistory[\s\S]*NoUnmountedMutation ==\s*\n\s*\\A w \\in writeHistory: w\.wasMounted = TRUE/, 'browser async model records write history for mounted-only mutation safety', 'formal/tla/BrowserAsync.tla');
  assertContains(browserLiveness, /WF_vars\(DrainDebounce\(p\)\)[\s\S]*WF_vars\(DrainIdle\(p\)\)/, 'browser async liveness model weakly-fairly drains debounce and idle callbacks', 'formal/tla/BrowserAsyncLiveness.tla');
  assertContains(browserLiveness, /DebounceEventuallyDrains[\s\S]*IdleEventuallyDrains/, 'browser async liveness model names pending-callback drain properties', 'formal/tla/BrowserAsyncLiveness.tla');
  assertContains(docSyncInductiveCheck, /InductiveInit == DocSyncInv[\s\S]*InductiveSpec == InductiveInit \/\\ \[\]\[Next\]_vars/, 'DocSync local TLC inductive-check model starts from DocSyncInv', 'formal/tla/DocSyncInductiveCheck.tla');
  assertContains(docSync, /DeliverySeqPrecedesWorkspace ==\s*\n\s*\\A d \\in deliveries: d\.n < workspaceText\[d\.uri\]/, 'doc sync model covers delivery sequence ordering', 'formal/tla/DocSync.tla');
  assertNotContains(docSync, /EmptyStreamHasNoSubscribers/, 'doc sync model must not carry tautological cardinality proof obligations', 'formal/tla/DocSync.tla');
  assertContains(publicTraceInductiveCheck, /InductiveInit == PublicTraceInv[\s\S]*InductiveSpec == InductiveInit \/\\ \[\]\[Next\]_vars/, 'public trace local TLC inductive-check model starts from PublicTraceInv', 'formal/tla/LightningBugAsyncInductiveCheck.tla');
  assertContains(publicTrace, /DidChangeRecords == \{\[uri \|-> u, openedAtSend \|-> TRUE\] : u \\in Uris\}/, 'public trace model types didChange history as sent-after-open records', 'formal/tla/LightningBugAsync.tla');
  assertContains(publicTrace, /DidCloseRecords == \{\[uri \|-> u, sharedAtClose \|-> FALSE\] : u \\in Uris\}/, 'public trace model types didClose history as unshared-close records', 'formal/tla/LightningBugAsync.tla');
  assertContains(publicTrace, /DidChangeSentAfterOpen ==\s*\n\s*\\A d \\in didChangeLog: d\.openedAtSend = TRUE/, 'public trace model covers didChange-after-open ordering', 'formal/tla/LightningBugAsync.tla');
  assertContains(publicTrace, /DidCloseOnlyWhenUnshared ==\s*\n\s*\\A d \\in didCloseLog: d\.sharedAtClose = FALSE/, 'public trace model covers didClose suppression for shared documents', 'formal/tla/LightningBugAsync.tla');
  assertContains(publicTrace, /Visible\(u\) == \\E p \\in Panes: mounted\[p\] \/\\ active\[p\] = u/, 'public trace model names browser-visible documents', 'formal/tla/LightningBugAsync.tla');
  assertContains(publicTrace, /EnsureDidOpen\(p, u\) ==[\s\S]*u \\notin opened[\s\S]*lspState = "initialized"[\s\S]*opened' = opened \\cup \{u\}/, 'public trace model makes didOpen idempotent and initialized-only', 'formal/tla/LightningBugAsync.tla');
  assertContains(publicTraceLiveness, /InitializeLspProgress ==[\s\S]*FlushPendingChange\(u\) ==[\s\S]*OpenVisibleDocument\(u\) ==/, 'public trace liveness model names explicit source-aligned progress actions', 'formal/tla/LightningBugAsyncLiveness.tla');
  assertContains(publicTraceLiveness, /WF_vars\(InitializeLspProgress\)[\s\S]*WF_vars\(FlushPendingChange\(u\)\)[\s\S]*WF_vars\(OpenVisibleDocument\(u\)\)/, 'public trace liveness model checks initialization, pending didChange flush, and visible didOpen under weak fairness', 'formal/tla/LightningBugAsyncLiveness.tla');
  assertContains(publicTraceLiveness, /PendingChangeEventuallyFlushes[\s\S]*ConnectingEventuallyInitializes[\s\S]*VisibleInitializedDocumentEventuallyOpens/, 'public trace liveness model names pending-change, initialization, and visible didOpen progress properties', 'formal/tla/LightningBugAsyncLiveness.tla');
  assertContains(proofText, /THEOREM FlagConsistencyImpliesInitializedConnected/, 'TLAPS proves FSM flag implication', 'formal/tla/proofs');
  assertContains(proofText, /THEOREM InitEstablishesLspInv/, 'TLAPS proves LSP invariant initialization', 'formal/tla/proofs');
  assertContains(proofText, /THEOREM ResponseRemovesOnlyMatchingNormalRequest/, 'TLAPS proves typed request-response matching', 'formal/tla/proofs');
  assertContains(proofText, /THEOREM StartShutdownCreatesOnlyShutdownPending/, 'TLAPS proves shutdown keeps only the shutdown request', 'formal/tla/proofs');
  assertContains(proofText, /THEOREM OnlyPendingKindMatchesSingleton/, 'TLAPS proves typed pending metadata matches singleton shutdown pending set', 'formal/tla/proofs');
  assertContains(proofText, /THEOREM StartShutdownPreservesLspInv/, 'TLAPS proves shutdown preserves the LSP invariant', 'formal/tla/proofs');
  assertContains(proofText, /THEOREM LspInvPreservedByNext/, 'TLAPS proves every LSP next step preserves the invariant', 'formal/tla/proofs');
  assertContains(proofText, /THEOREM FairSpecImpliesConnectingEventuallyResolves/, 'TLAPS proves connecting LSP sessions eventually resolve', 'formal/tla/proofs');
  assertContains(proofText, /THEOREM FairSpecImpliesShutdownEventuallyDisconnects/, 'TLAPS proves graceful shutdown eventually disconnects', 'formal/tla/proofs');
  assertContains(proofText, /THEOREM FairSpecImpliesQueuedReconnectEventuallyConsumed/, 'TLAPS proves queued reconnect work is eventually consumed', 'formal/tla/proofs');
  assertContains(proofText, /THEOREM FairSpecImpliesQueuedReconnectEventuallyAttempts/, 'TLAPS proves queued reconnect work eventually attempts a connection', 'formal/tla/proofs');
  assertContains(proofText, /THEOREM InitEstablishesBrowserInv/, 'TLAPS proves BrowserAsync invariant initialization', 'formal/tla/proofs');
  assertContains(proofText, /THEOREM BrowserInvPreservedByNext/, 'TLAPS proves every BrowserAsync next step preserves the invariant', 'formal/tla/proofs');
  assertContains(proofText, /THEOREM FireDebounceWritesOnlyWhenMounted/, 'TLAPS proves mounted-only debounce writes', 'formal/tla/proofs');
  assertContains(proofText, /THEOREM FairSpecImpliesDebounceEventuallyDrains/, 'TLAPS proves browser debounce callbacks eventually drain', 'formal/tla/proofs');
  assertContains(proofText, /THEOREM FairSpecImpliesIdleEventuallyDrains/, 'TLAPS proves browser idle callbacks eventually drain', 'formal/tla/proofs');
  assertContains(proofText, /THEOREM InitEstablishesDocSyncInv/, 'TLAPS proves DocSync invariant initialization', 'formal/tla/proofs');
  assertContains(proofText, /THEOREM DocSyncInvPreservedByNext/, 'TLAPS proves every DocSync next step preserves the invariant', 'formal/tla/proofs');
  assertContains(proofText, /THEOREM UserEditDoesNotEchoToOrigin/, 'TLAPS proves no origin echo for doc sync deliveries', 'formal/tla/proofs');
  assertContains(proofText, /THEOREM InitEstablishesPublicTraceInv/, 'TLAPS proves public trace invariant initialization', 'formal/tla/proofs');
  assertContains(proofText, /THEOREM PublicTraceInvPreservedByNext/, 'TLAPS proves every public trace next step preserves the invariant', 'formal/tla/proofs');
  assertContains(proofText, /THEOREM FlushDidChangeRequiresOpen/, 'TLAPS proves didChange flush requires didOpen', 'formal/tla/proofs');
  assertContains(proofText, /THEOREM FairSpecImpliesPendingChangeEventuallyFlushes/, 'TLAPS proves pending public didChange work eventually flushes', 'formal/tla/proofs');
  assertContains(proofText, /THEOREM FairSpecImpliesConnectingEventuallyInitializes/, 'TLAPS proves public trace LSP initialization progress', 'formal/tla/proofs');
  assertContains(proofText, /THEOREM FairSpecImpliesVisibleInitializedDocumentEventuallyOpens/, 'TLAPS proves visible initialized documents eventually resolve didOpen obligations', 'formal/tla/proofs');
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
verifyLspRequestResponseAlignment();
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
