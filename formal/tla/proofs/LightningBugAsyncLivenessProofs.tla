--------------------- MODULE LightningBugAsyncLivenessProofs ---------------------
EXTENDS LightningBugAsyncLiveness, LightningBugAsyncProofs, TLAPS

THEOREM FairSpecImpliesAlwaysPublicTraceInv ==
  FairSpec => []PublicTraceInv
<1>1. Init => PublicTraceInv
  BY InitEstablishesPublicTraceInv
<1>2. PublicTraceInv /\ [Next]_vars => PublicTraceInv'
  BY PublicTraceInvPreservedByNext
<1> QED
  BY <1>1, <1>2, PTL DEF FairSpec

THEOREM RemovePendingChangeChangesSet ==
  ASSUME NEW S,
         NEW x \in S
  PROVE S \ {x} # S
BY Z3T(30)

THEOREM AddMissingElementChangesSet ==
  ASSUME NEW S,
         NEW x,
         x \notin S
  PROVE S \cup {x} # S
BY Z3T(30)

THEOREM InitializeLspProgressEnabled ==
  ASSUME lspState = "connecting"
  PROVE ENABLED <<InitializeLspProgress>>_vars
<1>1. "initialized" # lspState
  BY Z3T(30)
<1> QED
  BY <1>1, ExpandENABLED DEF InitializeLspProgress, vars

THEOREM FlushPendingChangeEnabled ==
  ASSUME NEW u \in Uris,
         u \in pendingChange
  PROVE ENABLED <<FlushPendingChange(u)>>_vars
<1>1. pendingChange \ {u} # pendingChange
  BY RemovePendingChangeChangesSet
<1> QED
  BY <1>1, ExpandENABLED DEF FlushPendingChange, vars

THEOREM OpenVisibleDocumentEnabled ==
  ASSUME NEW u \in Uris,
         VisibleOpenObligation(u)
  PROVE ENABLED <<OpenVisibleDocument(u)>>_vars
<1>1. opened \cup {u} # opened
  BY AddMissingElementChangesSet DEF VisibleOpenObligation
<1> QED
  BY <1>1, ExpandENABLED DEF OpenVisibleDocument, vars

THEOREM InitializeLspProgressInitializes ==
  ASSUME <<InitializeLspProgress>>_vars
  PROVE lspState' = "initialized"
BY DEF InitializeLspProgress

THEOREM FlushPendingChangeClearsPending ==
  ASSUME NEW u \in Uris,
         <<FlushPendingChange(u)>>_vars
  PROVE u \notin pendingChange'
BY DEF FlushPendingChange

THEOREM OpenVisibleDocumentOpens ==
  ASSUME NEW u \in Uris,
         <<OpenVisibleDocument(u)>>_vars
  PROVE u \in opened'
BY DEF OpenVisibleDocument

THEOREM ConnectingStepStaysConnectingOrInitializes ==
  ASSUME PublicTraceInv,
         lspState = "connecting",
         [Next]_vars
  PROVE lspState' = "connecting" \/ lspState' = "initialized"
<1>1. ASSUME UNCHANGED vars
      PROVE lspState' = "connecting"
  BY <1>1 DEF vars
<1>2. ASSUME Next
      PROVE lspState' = "connecting" \/ lspState' = "initialized"
  BY <1>2, Z3T(30)
     DEF Next, ConnectLsp, InitializeLsp, Mount, Unmount, OpenDocument,
         EnsureDidOpen, Edit, CloseDocument, FlushDidChange,
         ReceiveDiagnostics, vars
<1> QED
  BY <1>1, <1>2 DEF vars

THEOREM ConnectingLeadsToInitialized ==
  FairSpec => (lspState = "connecting" ~> lspState = "initialized")
<1> DEFINE P == lspState = "connecting"
<1> DEFINE Q == lspState = "initialized"
<1> DEFINE BSpec == /\ []PublicTraceInv
                     /\ [][Next]_vars
                     /\ WF_vars(InitializeLspProgress)
<1>1. PublicTraceInv /\ P /\ [Next]_vars => P' \/ Q'
  BY ConnectingStepStaysConnectingOrInitializes DEF P, Q
<1>2. PublicTraceInv /\ P /\ <<Next /\ InitializeLspProgress>>_vars => Q'
  BY InitializeLspProgressInitializes DEF P, Q
<1>3. PublicTraceInv /\ P => ENABLED <<InitializeLspProgress>>_vars
  BY InitializeLspProgressEnabled DEF P
<1>4. BSpec => (P ~> Q)
  BY <1>1, <1>2, <1>3, PTL DEF BSpec
<1>5. FairSpec => BSpec
  BY DEF FairSpec, BSpec
<1> QED
  BY <1>4, <1>5, PTL DEF P, Q

THEOREM PendingChangeFlushLeadsToClear ==
  ASSUME NEW u \in Uris
  PROVE FairSpec => (u \in pendingChange ~> u \notin pendingChange)
<1> DEFINE P == u \in pendingChange
<1> DEFINE Q == u \notin pendingChange
<1> DEFINE BSpec == /\ []PublicTraceInv
                     /\ [][Next]_vars
                     /\ WF_vars(FlushPendingChange(u))
<1>1. PublicTraceInv /\ P /\ [Next]_vars => P' \/ Q'
  BY DEF P, Q
<1>2. PublicTraceInv /\ P /\ <<Next /\ FlushPendingChange(u)>>_vars => Q'
  BY FlushPendingChangeClearsPending DEF P, Q
<1>3. PublicTraceInv /\ P => ENABLED <<FlushPendingChange(u)>>_vars
  BY FlushPendingChangeEnabled DEF P
<1>4. BSpec => (P ~> Q)
  BY <1>1, <1>2, <1>3, PTL DEF BSpec
<1>5. FairSpec => BSpec
  BY DEF FairSpec, BSpec
<1> QED
  BY <1>4, <1>5, PTL DEF P, Q

THEOREM VisibleOpenObligationLeadsToResolved ==
  ASSUME NEW u \in Uris
  PROVE FairSpec =>
          (VisibleOpenObligation(u) ~>
             (u \in opened \/ ~VisibleOpenObligation(u)))
<1> DEFINE P == VisibleOpenObligation(u)
<1> DEFINE Q == u \in opened \/ ~P
<1> DEFINE BSpec == /\ []PublicTraceInv
                     /\ [][Next]_vars
                     /\ WF_vars(OpenVisibleDocument(u))
<1>1. PublicTraceInv /\ P /\ [Next]_vars => P' \/ Q'
  BY DEF P, Q
<1>2. PublicTraceInv /\ P /\ <<Next /\ OpenVisibleDocument(u)>>_vars => Q'
  BY OpenVisibleDocumentOpens DEF P, Q
<1>3. PublicTraceInv /\ P => ENABLED <<OpenVisibleDocument(u)>>_vars
  BY OpenVisibleDocumentEnabled DEF P
<1>4. BSpec => (P ~> Q)
  BY <1>1, <1>2, <1>3, PTL DEF BSpec
<1>5. FairSpec => BSpec
  BY DEF FairSpec, BSpec
<1> QED
  BY <1>4, <1>5, PTL DEF P, Q

THEOREM FairSpecImpliesPendingChangeEventuallyFlushes ==
  FairSpec => PendingChangeEventuallyFlushes
<1>1. ASSUME NEW u \in Uris
      PROVE FairSpec => [](u \in pendingChange => <>(u \notin pendingChange))
  BY PendingChangeFlushLeadsToClear, PTL
<1> QED
  BY <1>1 DEF PendingChangeEventuallyFlushes

THEOREM FairSpecImpliesConnectingEventuallyInitializes ==
  FairSpec => ConnectingEventuallyInitializes
BY ConnectingLeadsToInitialized, PTL DEF ConnectingEventuallyInitializes

THEOREM FairSpecImpliesVisibleInitializedDocumentEventuallyOpens ==
  FairSpec => VisibleInitializedDocumentEventuallyOpens
<1>1. ASSUME NEW u \in Uris
      PROVE FairSpec =>
              [](VisibleOpenObligation(u) =>
                  <>(u \in opened \/ ~VisibleOpenObligation(u)))
  BY VisibleOpenObligationLeadsToResolved, PTL
<1> QED
  BY <1>1 DEF VisibleInitializedDocumentEventuallyOpens

================================================================================
