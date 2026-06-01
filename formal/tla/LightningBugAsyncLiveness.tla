----------------------- MODULE LightningBugAsyncLiveness -----------------------
EXTENDS LightningBugAsync

InitializeLspProgress ==
  /\ lspState = "connecting"
  /\ lspState' = "initialized"
  /\ UNCHANGED <<mounted, active, docs, opened, version,
                pendingChange, diagnosticVersion, didChangeLog, didCloseLog>>

FlushPendingChange(u) ==
  /\ u \in pendingChange
  /\ pendingChange' = pendingChange \ {u}
  /\ didChangeLog' = didChangeLog \cup {[uri |-> u, openedAtSend |-> TRUE]}
  /\ UNCHANGED <<mounted, active, docs, lspState, opened, version,
                diagnosticVersion, didCloseLog>>

VisibleOpenObligation(u) ==
  Visible(u) /\ u \notin opened /\ lspState = "initialized"

OpenVisibleDocument(u) ==
  /\ VisibleOpenObligation(u)
  /\ opened' = opened \cup {u}
  /\ UNCHANGED <<mounted, active, docs, lspState, version,
                pendingChange, diagnosticVersion, didChangeLog, didCloseLog>>

FairSpec ==
  /\ Init
  /\ [][Next]_vars
  /\ []PublicTraceInv
  /\ WF_vars(InitializeLspProgress)
  /\ \A u \in Uris: WF_vars(FlushPendingChange(u))
  /\ \A u \in Uris: WF_vars(OpenVisibleDocument(u))

PendingChangeEventuallyFlushes ==
  \A u \in Uris:
    [](u \in pendingChange => <>(u \notin pendingChange))

ConnectingEventuallyInitializes ==
  [](lspState = "connecting" => <>(lspState = "initialized"))

VisibleInitializedDocumentEventuallyOpens ==
  \A u \in Uris:
    [](VisibleOpenObligation(u) =>
        <>(u \in opened \/ ~VisibleOpenObligation(u)))

================================================================================
