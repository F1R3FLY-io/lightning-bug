---------------------------- MODULE LightningBugAsync ----------------------------
EXTENDS Naturals

\* Composed public trace model for the first async-core milestone. This abstracts
\* public handle calls into document/open/edit/diagnostic effects and checks the
\* ordering constraints the browser implementation must preserve.

\* @type: Set(Str);
CONSTANTS Panes
\* @type: Set(Str);
CONSTANTS Uris
\* @type: Str;
CONSTANTS NoUri
\* @type: Int;
CONSTANTS MaxVersion

\* @type: Str -> Bool;
VARIABLE mounted
\* @type: Str -> Str;
VARIABLE active
\* @type: Set(Str);
VARIABLE docs
\* @type: Str;
VARIABLE lspState
\* @type: Set(Str);
VARIABLE opened
\* @type: Str -> Int;
VARIABLE version
\* @type: Set(Str);
VARIABLE pendingChange
\* @type: Str -> Int;
VARIABLE diagnosticVersion

vars == <<mounted, active, docs, lspState, opened, version, pendingChange,
          diagnosticVersion>>

LspStates == {"disconnected", "connecting", "initialized"}

Init ==
  /\ mounted = [p \in Panes |-> FALSE]
  /\ active = [p \in Panes |-> NoUri]
  /\ docs = {}
  /\ lspState = "disconnected"
  /\ opened = {}
  /\ version = [u \in Uris |-> 0]
  /\ pendingChange = {}
  /\ diagnosticVersion = [u \in Uris |-> 0]

Mount(p) ==
  /\ mounted[p] = FALSE
  /\ mounted' = [mounted EXCEPT ![p] = TRUE]
  /\ UNCHANGED <<active, docs, lspState, opened, version,
                pendingChange, diagnosticVersion>>

Unmount(p) ==
  /\ mounted[p]
  /\ mounted' = [mounted EXCEPT ![p] = FALSE]
  /\ active' = [active EXCEPT ![p] = NoUri]
  /\ UNCHANGED <<docs, lspState, opened, version,
                pendingChange, diagnosticVersion>>

ConnectLsp ==
  /\ lspState = "disconnected"
  /\ lspState' = "connecting"
  /\ UNCHANGED <<mounted, active, docs, opened, version,
                pendingChange, diagnosticVersion>>

InitializeLsp ==
  /\ lspState = "connecting"
  /\ lspState' = "initialized"
  /\ UNCHANGED <<mounted, active, docs, opened, version,
                pendingChange, diagnosticVersion>>

OpenDocument(p, u) ==
  /\ mounted[p]
  /\ active' = [active EXCEPT ![p] = u]
  /\ docs' = docs \cup {u}
  /\ version' = [version EXCEPT ![u] = IF version[u] = 0 THEN 1 ELSE @]
  /\ UNCHANGED <<mounted, lspState, opened, pendingChange, diagnosticVersion>>

EnsureDidOpen(p, u) ==
  /\ mounted[p]
  /\ active[p] = u
  /\ u \in docs
  /\ lspState = "initialized"
  /\ opened' = opened \cup {u}
  /\ UNCHANGED <<mounted, active, docs, lspState, version,
                pendingChange, diagnosticVersion>>

Edit(p, u) ==
  /\ mounted[p]
  /\ active[p] = u
  /\ u \in docs
  /\ version[u] < MaxVersion
  /\ version' = [version EXCEPT ![u] = @ + 1]
  /\ pendingChange' = IF u \in opened THEN pendingChange \cup {u} ELSE pendingChange
  /\ diagnosticVersion' = [diagnosticVersion EXCEPT ![u] = 0]
  /\ UNCHANGED <<mounted, active, docs, lspState, opened>>

FlushDidChange(u) ==
  /\ u \in pendingChange
  /\ pendingChange' = pendingChange \ {u}
  /\ UNCHANGED <<mounted, active, docs, lspState, opened, version,
                diagnosticVersion>>

ReceiveDiagnostics(u, v) ==
  /\ u \in docs
  /\ u \in opened
  /\ lspState = "initialized"
  /\ v \in 0..MaxVersion
  /\ diagnosticVersion' =
       [diagnosticVersion EXCEPT ![u] = IF v = version[u] THEN v ELSE @]
  /\ UNCHANGED <<mounted, active, docs, lspState, opened, version, pendingChange>>

CloseDocument(p, u) ==
  /\ mounted[p]
  /\ active[p] = u
  /\ active' = [active EXCEPT ![p] = NoUri]
  /\ LET shared == \E q \in Panes \ {p}: active[q] = u IN
       /\ docs' = IF shared THEN docs ELSE docs \ {u}
       /\ opened' = IF shared THEN opened ELSE opened \ {u}
       /\ pendingChange' = IF shared THEN pendingChange ELSE pendingChange \ {u}
  /\ UNCHANGED <<mounted, lspState, version, diagnosticVersion>>

Next ==
  \/ ConnectLsp \/ InitializeLsp
  \/ \E p \in Panes, u \in Uris:
       Mount(p) \/ Unmount(p) \/ OpenDocument(p, u) \/ EnsureDidOpen(p, u)
       \/ Edit(p, u) \/ CloseDocument(p, u)
  \/ \E u \in Uris: FlushDidChange(u)
  \/ \E u \in Uris, v \in 0..MaxVersion: ReceiveDiagnostics(u, v)
  \/ UNCHANGED vars

Spec == Init /\ [][Next]_vars

TypeOK ==
  /\ mounted \in [Panes -> BOOLEAN]
  /\ active \in [Panes -> (Uris \cup {NoUri})]
  /\ docs \subseteq Uris
  /\ lspState \in LspStates
  /\ opened \subseteq Uris
  /\ version \in [Uris -> 0..MaxVersion]
  /\ pendingChange \subseteq Uris
  /\ diagnosticVersion \in [Uris -> 0..MaxVersion]

ActiveDocumentExists ==
  \A p \in Panes: active[p] # NoUri => active[p] \in docs

DidChangeRequiresDidOpen ==
  pendingChange \subseteq opened

OpenedDocumentsExist ==
  opened \subseteq docs

DiagnosticsAreCurrent ==
  \A u \in Uris: diagnosticVersion[u] = 0 \/ diagnosticVersion[u] = version[u]

NoWorkForClosedDocs ==
  pendingChange \subseteq docs

================================================================================
