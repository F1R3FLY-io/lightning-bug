------------------------- MODULE LightningBugAsyncProofs -------------------------
EXTENDS LightningBugAsync, TLAPS

THEOREM FlushDidChangeRequiresOpen ==
  \A u \in Uris:
    DidChangeRequiresDidOpen /\ FlushDidChange(u) =>
      \A d \in didChangeLog' \ didChangeLog: d.openedAtSend = TRUE
  BY DEF DidChangeRequiresDidOpen, FlushDidChange

THEOREM CloseDocumentDoesNotCloseSharedUri ==
  \A p \in Panes:
    \A u \in Uris:
      CloseDocument(p, u) =>
        \A d \in didCloseLog' \ didCloseLog: d.sharedAtClose = FALSE
  BY DEF CloseDocument

THEOREM LocalEditInvalidatesDiagnostics ==
  \A p \in Panes:
    \A u \in Uris:
      Edit(p, u) => diagnosticVersion' = [diagnosticVersion EXCEPT ![u] = 0]
  BY DEF Edit

================================================================================
