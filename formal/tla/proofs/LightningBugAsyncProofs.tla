------------------------- MODULE LightningBugAsyncProofs -------------------------
EXTENDS LightningBugAsync, TLAPS

THEOREM InitEstablishesPublicTraceInv ==
  ASSUME Init
  PROVE PublicTraceInv
BY PanesAssumption, UrisAssumption, NoUriAssumption, MaxVersionAssumption,
   Z3T(30)
   DEF Init, PublicTraceInv, TypeOK, LspStates, DidChangeRecords,
       DidCloseRecords, ActiveDocumentExists,
       DidChangeRequiresDidOpen, OpenedDocumentsExist, DiagnosticsAreCurrent,
       NoWorkForClosedDocs, DidChangeSentAfterOpen, DidCloseOnlyWhenUnshared

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

THEOREM MountPreservesPublicTraceInv ==
  ASSUME PublicTraceInv,
         NEW p \in Panes,
         Mount(p)
  PROVE PublicTraceInv'
BY PanesAssumption, UrisAssumption, NoUriAssumption, MaxVersionAssumption,
   Z3T(30)
   DEF PublicTraceInv, Mount, TypeOK, LspStates, DidChangeRecords,
       DidCloseRecords, ActiveDocumentExists,
       DidChangeRequiresDidOpen, OpenedDocumentsExist, DiagnosticsAreCurrent,
       NoWorkForClosedDocs, DidChangeSentAfterOpen, DidCloseOnlyWhenUnshared

THEOREM UnmountPreservesPublicTraceInv ==
  ASSUME PublicTraceInv,
         NEW p \in Panes,
         Unmount(p)
  PROVE PublicTraceInv'
BY PanesAssumption, UrisAssumption, NoUriAssumption, MaxVersionAssumption,
   Z3T(30)
   DEF PublicTraceInv, Unmount, TypeOK, LspStates, DidChangeRecords,
       DidCloseRecords, ActiveDocumentExists,
       DidChangeRequiresDidOpen, OpenedDocumentsExist, DiagnosticsAreCurrent,
       NoWorkForClosedDocs, DidChangeSentAfterOpen, DidCloseOnlyWhenUnshared

THEOREM ConnectLspPreservesPublicTraceInv ==
  ASSUME PublicTraceInv,
         ConnectLsp
  PROVE PublicTraceInv'
BY PanesAssumption, UrisAssumption, NoUriAssumption, MaxVersionAssumption,
   Z3T(30)
   DEF PublicTraceInv, ConnectLsp, TypeOK, LspStates, DidChangeRecords,
       DidCloseRecords, ActiveDocumentExists,
       DidChangeRequiresDidOpen, OpenedDocumentsExist, DiagnosticsAreCurrent,
       NoWorkForClosedDocs, DidChangeSentAfterOpen, DidCloseOnlyWhenUnshared

THEOREM InitializeLspPreservesPublicTraceInv ==
  ASSUME PublicTraceInv,
         InitializeLsp
  PROVE PublicTraceInv'
BY PanesAssumption, UrisAssumption, NoUriAssumption, MaxVersionAssumption,
   Z3T(30)
   DEF PublicTraceInv, InitializeLsp, TypeOK, LspStates, DidChangeRecords,
       DidCloseRecords, ActiveDocumentExists,
       DidChangeRequiresDidOpen, OpenedDocumentsExist, DiagnosticsAreCurrent,
       NoWorkForClosedDocs, DidChangeSentAfterOpen, DidCloseOnlyWhenUnshared

THEOREM OpenDocumentPreservesPublicTraceInv ==
  ASSUME PublicTraceInv,
         NEW p \in Panes,
         NEW u \in Uris,
         OpenDocument(p, u)
  PROVE PublicTraceInv'
BY PanesAssumption, UrisAssumption, NoUriAssumption, MaxVersionAssumption,
   Z3T(30)
   DEF PublicTraceInv, OpenDocument, TypeOK, LspStates, DidChangeRecords,
       DidCloseRecords, ActiveDocumentExists,
       DidChangeRequiresDidOpen, OpenedDocumentsExist, DiagnosticsAreCurrent,
       NoWorkForClosedDocs, DidChangeSentAfterOpen, DidCloseOnlyWhenUnshared

THEOREM EnsureDidOpenPreservesPublicTraceInv ==
  ASSUME PublicTraceInv,
         NEW p \in Panes,
         NEW u \in Uris,
         EnsureDidOpen(p, u)
  PROVE PublicTraceInv'
BY PanesAssumption, UrisAssumption, NoUriAssumption, MaxVersionAssumption,
   Z3T(30)
   DEF PublicTraceInv, EnsureDidOpen, TypeOK, LspStates, DidChangeRecords,
       DidCloseRecords, ActiveDocumentExists,
       DidChangeRequiresDidOpen, OpenedDocumentsExist, DiagnosticsAreCurrent,
       NoWorkForClosedDocs, DidChangeSentAfterOpen, DidCloseOnlyWhenUnshared

THEOREM EditPreservesPublicTraceInv ==
  ASSUME PublicTraceInv,
         NEW p \in Panes,
         NEW u \in Uris,
         Edit(p, u)
  PROVE PublicTraceInv'
BY PanesAssumption, UrisAssumption, NoUriAssumption, MaxVersionAssumption,
   Z3T(30)
   DEF PublicTraceInv, Edit, TypeOK, LspStates, DidChangeRecords,
       DidCloseRecords, ActiveDocumentExists,
       DidChangeRequiresDidOpen, OpenedDocumentsExist, DiagnosticsAreCurrent,
       NoWorkForClosedDocs, DidChangeSentAfterOpen, DidCloseOnlyWhenUnshared

THEOREM FlushDidChangePreservesPublicTraceInv ==
  ASSUME PublicTraceInv,
         NEW u \in Uris,
         FlushDidChange(u)
  PROVE PublicTraceInv'
BY PanesAssumption, UrisAssumption, NoUriAssumption, MaxVersionAssumption,
   Z3T(30)
   DEF PublicTraceInv, FlushDidChange, TypeOK, LspStates, DidChangeRecords,
       DidCloseRecords, ActiveDocumentExists,
       DidChangeRequiresDidOpen, OpenedDocumentsExist, DiagnosticsAreCurrent,
       NoWorkForClosedDocs, DidChangeSentAfterOpen, DidCloseOnlyWhenUnshared

THEOREM ReceiveDiagnosticsPreservesPublicTraceInv ==
  ASSUME PublicTraceInv,
         NEW u \in Uris,
         NEW v \in 0..MaxVersion,
         ReceiveDiagnostics(u, v)
  PROVE PublicTraceInv'
BY PanesAssumption, UrisAssumption, NoUriAssumption, MaxVersionAssumption,
   Z3T(30)
   DEF PublicTraceInv, ReceiveDiagnostics, TypeOK, LspStates,
       DidChangeRecords, DidCloseRecords,
       ActiveDocumentExists, DidChangeRequiresDidOpen, OpenedDocumentsExist,
       DiagnosticsAreCurrent, NoWorkForClosedDocs, DidChangeSentAfterOpen,
       DidCloseOnlyWhenUnshared

THEOREM CloseDocumentPreservesPublicTraceInv ==
  ASSUME PublicTraceInv,
         NEW p \in Panes,
         NEW u \in Uris,
         CloseDocument(p, u)
  PROVE PublicTraceInv'
BY PanesAssumption, UrisAssumption, NoUriAssumption, MaxVersionAssumption,
   Z3T(30)
   DEF PublicTraceInv, CloseDocument, TypeOK, LspStates, DidChangeRecords,
       DidCloseRecords, ActiveDocumentExists,
       DidChangeRequiresDidOpen, OpenedDocumentsExist, DiagnosticsAreCurrent,
       NoWorkForClosedDocs, DidChangeSentAfterOpen, DidCloseOnlyWhenUnshared

THEOREM StutterPreservesPublicTraceInv ==
  PublicTraceInv /\ UNCHANGED vars => PublicTraceInv'
BY PanesAssumption, UrisAssumption, NoUriAssumption, MaxVersionAssumption,
   Z3T(30)
   DEF PublicTraceInv, vars, TypeOK, LspStates, DidChangeRecords,
       DidCloseRecords, ActiveDocumentExists,
       DidChangeRequiresDidOpen, OpenedDocumentsExist, DiagnosticsAreCurrent,
       NoWorkForClosedDocs, DidChangeSentAfterOpen, DidCloseOnlyWhenUnshared

THEOREM PublicTraceInvPreservedByNext ==
  PublicTraceInv /\ [Next]_vars => PublicTraceInv'
<1>1. ASSUME PublicTraceInv, [Next]_vars PROVE PublicTraceInv'
  <2>1. ASSUME ConnectLsp PROVE PublicTraceInv'
    BY <1>1, <2>1, ConnectLspPreservesPublicTraceInv
  <2>2. ASSUME InitializeLsp PROVE PublicTraceInv'
    BY <1>1, <2>2, InitializeLspPreservesPublicTraceInv
  <2>3. ASSUME NEW p \in Panes, NEW u \in Uris, Mount(p)
        PROVE PublicTraceInv'
    BY <1>1, <2>3, MountPreservesPublicTraceInv
  <2>4. ASSUME NEW p \in Panes, NEW u \in Uris, Unmount(p)
        PROVE PublicTraceInv'
    BY <1>1, <2>4, UnmountPreservesPublicTraceInv
  <2>5. ASSUME NEW p \in Panes, NEW u \in Uris, OpenDocument(p, u)
        PROVE PublicTraceInv'
    BY <1>1, <2>5, OpenDocumentPreservesPublicTraceInv
  <2>6. ASSUME NEW p \in Panes, NEW u \in Uris, EnsureDidOpen(p, u)
        PROVE PublicTraceInv'
    BY <1>1, <2>6, EnsureDidOpenPreservesPublicTraceInv
  <2>7. ASSUME NEW p \in Panes, NEW u \in Uris, Edit(p, u)
        PROVE PublicTraceInv'
    BY <1>1, <2>7, EditPreservesPublicTraceInv
  <2>8. ASSUME NEW p \in Panes, NEW u \in Uris, CloseDocument(p, u)
        PROVE PublicTraceInv'
    BY <1>1, <2>8, CloseDocumentPreservesPublicTraceInv
  <2>9. ASSUME NEW u \in Uris, FlushDidChange(u) PROVE PublicTraceInv'
    BY <1>1, <2>9, FlushDidChangePreservesPublicTraceInv
  <2>10. ASSUME NEW u \in Uris, NEW v \in 0..MaxVersion,
                 ReceiveDiagnostics(u, v)
         PROVE PublicTraceInv'
    BY <1>1, <2>10, ReceiveDiagnosticsPreservesPublicTraceInv
  <2>11. ASSUME UNCHANGED vars PROVE PublicTraceInv'
    BY <1>1, <2>11, StutterPreservesPublicTraceInv
  <2> QED
    BY <1>1, <2>1, <2>2, <2>3, <2>4, <2>5, <2>6, <2>7, <2>8,
       <2>9, <2>10, <2>11 DEF Next, vars
<1> QED
  BY <1>1

================================================================================
