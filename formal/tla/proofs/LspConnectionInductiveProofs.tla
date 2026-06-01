---------------------- MODULE LspConnectionInductiveProofs ----------------------
EXTENDS LspConnection, TLAPS

THEOREM InitEstablishesLspInv ==
  ASSUME Init
  PROVE LspInv
<1>1. TypeOK
  BY LangsAssumption, MaxPendingIdAssumption, MaxReconnectsAssumption, SMT
     DEF Init, TypeOK, States, PendingKinds, EmptyPendingKinds
<1>2. FlagConsistency
  BY DEF Init, FlagConsistency, ConnectedState, InitializedState,
         ConnectingState
<1>3. InitializedImpliesConnected
  BY DEF Init, InitializedImpliesConnected
<1>4. PendingOnlyWhileLive
  BY DEF Init, PendingOnlyWhileLive
<1>5. GracefulShutdownDoesNotReconnect
  BY DEF Init, GracefulShutdownDoesNotReconnect
<1>6. ShutdownStateConsistent
  BY DEF Init, ShutdownStateConsistent
<1>7. PendingKindsMatchPending
  BY DEF Init, PendingKindsMatchPending, EmptyPendingKinds
<1>8. PendingIdsAreIssued
  BY DEF Init, PendingIdsAreIssued
<1>9. ShutdownPendingIsShutdown
  BY DEF Init, ShutdownPendingIsShutdown
<1>10. NoShutdownRequestOutsideShutdown
  BY DEF Init, NoShutdownRequestOutsideShutdown, EmptyPendingKinds
<1>11. ReconnectQueuedOnlyWhileDisconnected
  BY DEF Init, ReconnectQueuedOnlyWhileDisconnected
<1>12. ReconnectQueueIsUnit
  BY DEF Init, ReconnectQueueIsUnit
<1> QED
  BY <1>1, <1>2, <1>3, <1>4, <1>5, <1>6, <1>7, <1>8, <1>9, <1>10,
     <1>11, <1>12 DEF LspInv

THEOREM OpenSocketAndInitializeRecordsInitializeRequest ==
  \A l \in Langs:
    OpenSocketAndInitialize(l) =>
      /\ state' = [state EXCEPT ![l] = "initializing"]
      /\ pending' = [pending EXCEPT ![l] = @ \cup {nextId[l]}]
      /\ pendingKind' =
           [pendingKind EXCEPT ![l] = [@ EXCEPT ![nextId[l]] = "initialize"]]
      /\ nextId' = [nextId EXCEPT ![l] = @ + 1]
BY DEF OpenSocketAndInitialize, SetLangState, FlagsFor

THEOREM InitializeResponseMatchesInitializeRequest ==
  \A l \in Langs:
    InitializeResponse(l) =>
      \E id \in pending[l]:
        /\ pendingKind[l][id] = "initialize"
        /\ pending' = [pending EXCEPT ![l] = @ \ {id}]
        /\ pendingKind' = [pendingKind EXCEPT ![l] = [@ EXCEPT ![id] = "none"]]
        /\ state' = [state EXCEPT ![l] = "initialized"]
BY DEF InitializeResponse, SetLangState, FlagsFor

THEOREM RequestRecordsNormalRequestKind ==
  \A l \in Langs:
    Request(l) =>
      /\ state[l] = "initialized"
      /\ pending' = [pending EXCEPT ![l] = @ \cup {nextId[l]}]
      /\ pendingKind' =
           [pendingKind EXCEPT ![l] = [@ EXCEPT ![nextId[l]] = "request"]]
      /\ nextId' = [nextId EXCEPT ![l] = @ + 1]
BY DEF Request

THEOREM ResponseRemovesOnlyMatchingNormalRequest ==
  \A l \in Langs:
    Response(l) =>
      /\ shuttingDown[l] = FALSE
      /\ state[l] = "initialized"
      /\ \E id \in pending[l]:
           /\ pendingKind[l][id] = "request"
           /\ pending' = [pending EXCEPT ![l] = @ \ {id}]
           /\ pendingKind' =
                [pendingKind EXCEPT ![l] = [@ EXCEPT ![id] = "none"]]
BY DEF Response

THEOREM StartShutdownCreatesOnlyShutdownPending ==
  \A l \in Langs:
    StartShutdown(l) =>
      /\ state' = [state EXCEPT ![l] = "disconnecting"]
      /\ shuttingDown' = [shuttingDown EXCEPT ![l] = TRUE]
      /\ pending' = [pending EXCEPT ![l] = {nextId[l]}]
      /\ pendingKind' =
           [pendingKind EXCEPT ![l] = OnlyPendingKind(nextId[l], "shutdown")]
BY DEF StartShutdown, SetLangState, CanTransition, FlagsFor, OnlyPendingKind,
       EmptyPendingKinds

THEOREM ShutdownClosedClearsAllPendingMetadata ==
  \A l \in Langs:
    ShutdownClosed(l) =>
      /\ state' = [state EXCEPT ![l] = "disconnected"]
      /\ pending' = [pending EXCEPT ![l] = {}]
      /\ pendingKind' = [pendingKind EXCEPT ![l] = EmptyPendingKinds]
      /\ shuttingDown' = [shuttingDown EXCEPT ![l] = FALSE]
BY DEF ShutdownClosed, SetLangState, CanTransition, FlagsFor, EmptyPendingKinds

THEOREM OnlyPendingKindTypeOK ==
  ASSUME NEW id \in 1..MaxPendingId,
         NEW kind \in PendingKinds
  PROVE OnlyPendingKind(id, kind) \in [1..MaxPendingId -> PendingKinds]
BY LangsAssumption, MaxPendingIdAssumption, Z3T(30)
   DEF OnlyPendingKind, EmptyPendingKinds, PendingKinds

THEOREM OnlyPendingKindMatchesSingleton ==
  ASSUME NEW requestId \in 1..MaxPendingId,
         NEW id \in 1..MaxPendingId,
         NEW kind \in PendingKinds,
         kind # "none"
  PROVE (id \in {requestId}) <=> (OnlyPendingKind(requestId, kind)[id] # "none")
BY MaxPendingIdAssumption, Z3T(30)
   DEF OnlyPendingKind, EmptyPendingKinds, PendingKinds

THEOREM StartShutdownSourceFacts ==
  ASSUME LspInv,
         NEW l \in Langs,
         StartShutdown(l)
  PROVE /\ nextId[l] \in 1..MaxPendingId
        /\ reconnects[l] = 0
        /\ "shutdown" \in PendingKinds
        /\ "shutdown" # "none"
BY LangsAssumption, MaxPendingIdAssumption, MaxReconnectsAssumption, Z3T(30)
   DEF LspInv, StartShutdown, TypeOK, ReconnectQueuedOnlyWhileDisconnected,
       States, PendingKinds

THEOREM StartShutdownPostAtLang ==
  \A l \in Langs:
    LspInv /\ StartShutdown(l) =>
      /\ state'[l] = "disconnecting"
      /\ pending'[l] = {nextId[l]}
      /\ reconnects'[l] = 0
BY LangsAssumption, MaxPendingIdAssumption, MaxReconnectsAssumption,
   StartShutdownSourceFacts, Z3T(30)
   DEF LspInv, StartShutdown, SetLangState, CanTransition, FlagsFor,
       TypeOK, ReconnectQueuedOnlyWhileDisconnected, ConnectedState,
       InitializedState, ConnectingState, States

THEOREM StartConnectPreservesLspInv ==
  ASSUME LspInv,
         NEW l \in Langs,
         StartConnect(l)
  PROVE LspInv'
BY LangsAssumption, MaxPendingIdAssumption, MaxReconnectsAssumption, Z3T(30)
   DEF LspInv, StartConnect, SetLangState, FlagsFor, CanTransition,
           TypeOK, FlagConsistency, InitializedImpliesConnected,
           PendingOnlyWhileLive, GracefulShutdownDoesNotReconnect,
           ShutdownStateConsistent, PendingKindsMatchPending, PendingIdsAreIssued,
           ShutdownPendingIsShutdown, NoShutdownRequestOutsideShutdown,
           ReconnectQueuedOnlyWhileDisconnected, ReconnectQueueIsUnit,
           ConnectedState, InitializedState, ConnectingState, States,
           PendingKinds, EmptyPendingKinds

THEOREM OpenSocketAndInitializePreservesLspInv ==
  ASSUME LspInv,
         NEW l \in Langs,
         OpenSocketAndInitialize(l)
  PROVE LspInv'
BY LangsAssumption, MaxPendingIdAssumption, MaxReconnectsAssumption, Z3T(30)
   DEF LspInv, OpenSocketAndInitialize, SetLangState, FlagsFor,
           TypeOK, FlagConsistency, InitializedImpliesConnected,
           PendingOnlyWhileLive, GracefulShutdownDoesNotReconnect,
           ShutdownStateConsistent, PendingKindsMatchPending, PendingIdsAreIssued,
           ShutdownPendingIsShutdown, NoShutdownRequestOutsideShutdown,
           ReconnectQueuedOnlyWhileDisconnected, ReconnectQueueIsUnit,
           ConnectedState, InitializedState, ConnectingState, States,
           PendingKinds, EmptyPendingKinds

THEOREM InitializeResponsePreservesLspInv ==
  ASSUME LspInv,
         NEW l \in Langs,
         InitializeResponse(l)
  PROVE LspInv'
BY LangsAssumption, MaxPendingIdAssumption, MaxReconnectsAssumption, Z3T(30)
   DEF LspInv, InitializeResponse, SetLangState, FlagsFor,
           TypeOK, FlagConsistency, InitializedImpliesConnected,
           PendingOnlyWhileLive, GracefulShutdownDoesNotReconnect,
           ShutdownStateConsistent, PendingKindsMatchPending, PendingIdsAreIssued,
           ShutdownPendingIsShutdown, NoShutdownRequestOutsideShutdown,
           ReconnectQueuedOnlyWhileDisconnected, ReconnectQueueIsUnit,
           ConnectedState, InitializedState, ConnectingState, States,
           PendingKinds, EmptyPendingKinds

THEOREM RequestPreservesLspInv ==
  ASSUME LspInv,
         NEW l \in Langs,
         Request(l)
  PROVE LspInv'
BY LangsAssumption, MaxPendingIdAssumption, MaxReconnectsAssumption, Z3T(30)
   DEF LspInv, Request, TypeOK, FlagConsistency, InitializedImpliesConnected,
           PendingOnlyWhileLive, GracefulShutdownDoesNotReconnect,
           ShutdownStateConsistent, PendingKindsMatchPending, PendingIdsAreIssued,
           ShutdownPendingIsShutdown, NoShutdownRequestOutsideShutdown,
           ReconnectQueuedOnlyWhileDisconnected, ReconnectQueueIsUnit,
           ConnectedState, InitializedState, ConnectingState, States,
           PendingKinds, EmptyPendingKinds

THEOREM ResponsePreservesLspInv ==
  ASSUME LspInv,
         NEW l \in Langs,
         Response(l)
  PROVE LspInv'
BY LangsAssumption, MaxPendingIdAssumption, MaxReconnectsAssumption, Z3T(30)
   DEF LspInv, Response, TypeOK, FlagConsistency, InitializedImpliesConnected,
           PendingOnlyWhileLive, GracefulShutdownDoesNotReconnect,
           ShutdownStateConsistent, PendingKindsMatchPending, PendingIdsAreIssued,
           ShutdownPendingIsShutdown, NoShutdownRequestOutsideShutdown,
           ReconnectQueuedOnlyWhileDisconnected, ReconnectQueueIsUnit,
           ConnectedState, InitializedState, ConnectingState, States,
           PendingKinds, EmptyPendingKinds

THEOREM StartShutdownPreservesLspInv ==
  ASSUME LspInv,
         NEW l \in Langs,
         StartShutdown(l)
  PROVE LspInv'
<1>1. TypeOK'
  BY LangsAssumption, MaxPendingIdAssumption, MaxReconnectsAssumption,
     OnlyPendingKindTypeOK, StartShutdownSourceFacts, Z3T(30)
     DEF LspInv, StartShutdown, SetLangState, FlagsFor, CanTransition,
         OnlyPendingKind, TypeOK, FlagConsistency, InitializedImpliesConnected,
         PendingOnlyWhileLive, GracefulShutdownDoesNotReconnect,
         ShutdownStateConsistent, PendingKindsMatchPending, PendingIdsAreIssued,
         ShutdownPendingIsShutdown, NoShutdownRequestOutsideShutdown,
         ReconnectQueuedOnlyWhileDisconnected, ReconnectQueueIsUnit,
         ConnectedState, InitializedState, ConnectingState, States,
         PendingKinds, EmptyPendingKinds
<1>2. FlagConsistency'
  BY LangsAssumption, MaxPendingIdAssumption, MaxReconnectsAssumption,
     StartShutdownSourceFacts, Z3T(30)
     DEF LspInv, StartShutdown, SetLangState, FlagsFor, CanTransition,
         OnlyPendingKind, TypeOK, FlagConsistency, InitializedImpliesConnected,
         PendingOnlyWhileLive, GracefulShutdownDoesNotReconnect,
         ShutdownStateConsistent, PendingKindsMatchPending, PendingIdsAreIssued,
         ShutdownPendingIsShutdown, NoShutdownRequestOutsideShutdown,
         ReconnectQueuedOnlyWhileDisconnected, ReconnectQueueIsUnit,
         ConnectedState, InitializedState, ConnectingState, States,
         PendingKinds, EmptyPendingKinds
<1>3. InitializedImpliesConnected'
  BY <1>2 DEF FlagConsistency, InitializedImpliesConnected, ConnectedState,
     InitializedState
<1>4. PendingOnlyWhileLive'
  BY LangsAssumption, MaxPendingIdAssumption, MaxReconnectsAssumption,
     StartShutdownSourceFacts, Z3T(30)
     DEF LspInv, StartShutdown, SetLangState, FlagsFor, CanTransition,
         OnlyPendingKind, TypeOK, FlagConsistency, InitializedImpliesConnected,
         PendingOnlyWhileLive, GracefulShutdownDoesNotReconnect,
         ShutdownStateConsistent, PendingKindsMatchPending, PendingIdsAreIssued,
         ShutdownPendingIsShutdown, NoShutdownRequestOutsideShutdown,
         ReconnectQueuedOnlyWhileDisconnected, ReconnectQueueIsUnit,
         ConnectedState, InitializedState, ConnectingState, States,
         PendingKinds, EmptyPendingKinds
<1>5. GracefulShutdownDoesNotReconnect'
  <2>1. ASSUME NEW q \in Langs, shuttingDown'[q]
        PROVE reconnects'[q] = 0
    <3>1. CASE q = l
      BY <2>1, <3>1, StartShutdownPostAtLang, StartShutdownSourceFacts, Z3T(30)
    <3>2. CASE q # l
      BY <2>1, <3>2, Z3T(30)
         DEF LspInv, StartShutdown, GracefulShutdownDoesNotReconnect
    <3> QED
      BY <3>1, <3>2
  <2> QED
    BY <2>1 DEF GracefulShutdownDoesNotReconnect
<1>6. ShutdownStateConsistent'
  <2>1. ASSUME NEW q \in Langs, shuttingDown'[q]
        PROVE /\ state'[q] = "disconnecting"
              /\ \E id \in 1..MaxPendingId:
                   pending'[q] = {id}
    <3>1. CASE q = l
      BY <2>1, <3>1, StartShutdownPostAtLang, StartShutdownSourceFacts, Z3T(30)
    <3>2. CASE q # l
      BY <2>1, <3>2, Z3T(30)
         DEF LspInv, StartShutdown, SetLangState, FlagsFor,
             ShutdownStateConsistent, ConnectedState, InitializedState,
             ConnectingState
    <3> QED
      BY <3>1, <3>2
  <2> QED
    BY <2>1 DEF ShutdownStateConsistent
<1>7. PendingKindsMatchPending'
  BY LangsAssumption, MaxPendingIdAssumption, MaxReconnectsAssumption,
     OnlyPendingKindMatchesSingleton, StartShutdownSourceFacts, Z3T(30)
     DEF LspInv, StartShutdown, SetLangState, FlagsFor, CanTransition,
         OnlyPendingKind, TypeOK, FlagConsistency, InitializedImpliesConnected,
         PendingOnlyWhileLive, GracefulShutdownDoesNotReconnect,
         ShutdownStateConsistent, PendingKindsMatchPending, PendingIdsAreIssued,
         ShutdownPendingIsShutdown, NoShutdownRequestOutsideShutdown,
         ReconnectQueuedOnlyWhileDisconnected, ReconnectQueueIsUnit,
         ConnectedState, InitializedState, ConnectingState, States,
         PendingKinds, EmptyPendingKinds
<1>8. PendingIdsAreIssued'
  BY LangsAssumption, MaxPendingIdAssumption, MaxReconnectsAssumption,
     StartShutdownSourceFacts, Z3T(30)
     DEF LspInv, StartShutdown, SetLangState, FlagsFor, CanTransition,
         OnlyPendingKind, TypeOK, FlagConsistency, InitializedImpliesConnected,
         PendingOnlyWhileLive, GracefulShutdownDoesNotReconnect,
         ShutdownStateConsistent, PendingKindsMatchPending, PendingIdsAreIssued,
         ShutdownPendingIsShutdown, NoShutdownRequestOutsideShutdown,
         ReconnectQueuedOnlyWhileDisconnected, ReconnectQueueIsUnit,
         ConnectedState, InitializedState, ConnectingState, States,
         PendingKinds, EmptyPendingKinds
<1>9. ShutdownPendingIsShutdown'
  BY LangsAssumption, MaxPendingIdAssumption, MaxReconnectsAssumption,
     StartShutdownSourceFacts, Z3T(30)
     DEF LspInv, StartShutdown, SetLangState, FlagsFor, CanTransition,
         OnlyPendingKind, TypeOK, FlagConsistency, InitializedImpliesConnected,
         PendingOnlyWhileLive, GracefulShutdownDoesNotReconnect,
         ShutdownStateConsistent, PendingKindsMatchPending, PendingIdsAreIssued,
         ShutdownPendingIsShutdown, NoShutdownRequestOutsideShutdown,
         ReconnectQueuedOnlyWhileDisconnected, ReconnectQueueIsUnit,
         ConnectedState, InitializedState, ConnectingState, States,
         PendingKinds, EmptyPendingKinds
<1>10. NoShutdownRequestOutsideShutdown'
  BY LangsAssumption, MaxPendingIdAssumption, MaxReconnectsAssumption,
     OnlyPendingKindMatchesSingleton, StartShutdownSourceFacts, Z3T(30)
     DEF LspInv, StartShutdown, SetLangState, FlagsFor, CanTransition,
         OnlyPendingKind, TypeOK, FlagConsistency, InitializedImpliesConnected,
         PendingOnlyWhileLive, GracefulShutdownDoesNotReconnect,
         ShutdownStateConsistent, PendingKindsMatchPending, PendingIdsAreIssued,
         ShutdownPendingIsShutdown, NoShutdownRequestOutsideShutdown,
         ReconnectQueuedOnlyWhileDisconnected, ReconnectQueueIsUnit,
         ConnectedState, InitializedState, ConnectingState, States,
         PendingKinds, EmptyPendingKinds
<1>11. ReconnectQueuedOnlyWhileDisconnected'
  BY LangsAssumption, MaxPendingIdAssumption, MaxReconnectsAssumption,
     StartShutdownSourceFacts, Z3T(30)
     DEF LspInv, StartShutdown, SetLangState, FlagsFor, CanTransition,
         OnlyPendingKind, TypeOK, FlagConsistency, InitializedImpliesConnected,
         PendingOnlyWhileLive, GracefulShutdownDoesNotReconnect,
         ShutdownStateConsistent, PendingKindsMatchPending, PendingIdsAreIssued,
         ShutdownPendingIsShutdown, NoShutdownRequestOutsideShutdown,
         ReconnectQueuedOnlyWhileDisconnected, ReconnectQueueIsUnit,
         ConnectedState, InitializedState, ConnectingState, States,
         PendingKinds, EmptyPendingKinds
<1>12. ReconnectQueueIsUnit'
  BY LangsAssumption, MaxPendingIdAssumption, MaxReconnectsAssumption,
     StartShutdownSourceFacts, Z3T(30)
     DEF LspInv, StartShutdown, SetLangState, FlagsFor, CanTransition,
         OnlyPendingKind, TypeOK, FlagConsistency, InitializedImpliesConnected,
         PendingOnlyWhileLive, GracefulShutdownDoesNotReconnect,
         ShutdownStateConsistent, PendingKindsMatchPending, PendingIdsAreIssued,
         ShutdownPendingIsShutdown, NoShutdownRequestOutsideShutdown,
         ReconnectQueuedOnlyWhileDisconnected, ReconnectQueueIsUnit,
         ConnectedState, InitializedState, ConnectingState, States,
         PendingKinds, EmptyPendingKinds
<1> QED
  BY <1>1, <1>2, <1>3, <1>4, <1>5, <1>6, <1>7, <1>8, <1>9, <1>10,
     <1>11, <1>12 DEF LspInv

THEOREM ShutdownClosedPreservesLspInv ==
  ASSUME LspInv,
         NEW l \in Langs,
         ShutdownClosed(l)
  PROVE LspInv'
BY LangsAssumption, MaxPendingIdAssumption, MaxReconnectsAssumption, Z3T(30)
   DEF LspInv, ShutdownClosed, SetLangState, FlagsFor, CanTransition,
           TypeOK, FlagConsistency, InitializedImpliesConnected,
           PendingOnlyWhileLive, GracefulShutdownDoesNotReconnect,
           ShutdownStateConsistent, PendingKindsMatchPending, PendingIdsAreIssued,
           ShutdownPendingIsShutdown, NoShutdownRequestOutsideShutdown,
           ReconnectQueuedOnlyWhileDisconnected, ReconnectQueueIsUnit,
           ConnectedState, InitializedState, ConnectingState, States,
           PendingKinds, EmptyPendingKinds

THEOREM SocketErrorPreservesLspInv ==
  ASSUME LspInv,
         NEW l \in Langs,
         SocketError(l)
  PROVE LspInv'
BY LangsAssumption, MaxPendingIdAssumption, MaxReconnectsAssumption, Z3T(30)
   DEF LspInv, SocketError, SetLangState, FlagsFor, CanTransition,
           TypeOK, FlagConsistency, InitializedImpliesConnected,
           PendingOnlyWhileLive, GracefulShutdownDoesNotReconnect,
           ShutdownStateConsistent, PendingKindsMatchPending, PendingIdsAreIssued,
           ShutdownPendingIsShutdown, NoShutdownRequestOutsideShutdown,
           ReconnectQueuedOnlyWhileDisconnected, ReconnectQueueIsUnit,
           ConnectedState, InitializedState, ConnectingState, States,
           PendingKinds, EmptyPendingKinds

THEOREM UnexpectedClosePreservesLspInv ==
  ASSUME LspInv,
         NEW l \in Langs,
         UnexpectedClose(l)
  PROVE LspInv'
BY LangsAssumption, MaxPendingIdAssumption, MaxReconnectsAssumption, Z3T(30)
   DEF LspInv, UnexpectedClose, SetLangState, FlagsFor, CanTransition,
           TypeOK, FlagConsistency, InitializedImpliesConnected,
           PendingOnlyWhileLive, GracefulShutdownDoesNotReconnect,
           ShutdownStateConsistent, PendingKindsMatchPending, PendingIdsAreIssued,
           ShutdownPendingIsShutdown, NoShutdownRequestOutsideShutdown,
           ReconnectQueuedOnlyWhileDisconnected, ReconnectQueueIsUnit,
           ConnectedState, InitializedState, ConnectingState, States,
           PendingKinds, EmptyPendingKinds

THEOREM ReconnectPreservesLspInv ==
  ASSUME LspInv,
         NEW l \in Langs,
         Reconnect(l)
  PROVE LspInv'
BY LangsAssumption, MaxPendingIdAssumption, MaxReconnectsAssumption, Z3T(30)
   DEF LspInv, Reconnect, SetLangState, FlagsFor, CanTransition,
           TypeOK, FlagConsistency, InitializedImpliesConnected,
           PendingOnlyWhileLive, GracefulShutdownDoesNotReconnect,
           ShutdownStateConsistent, PendingKindsMatchPending, PendingIdsAreIssued,
           ShutdownPendingIsShutdown, NoShutdownRequestOutsideShutdown,
           ReconnectQueuedOnlyWhileDisconnected, ReconnectQueueIsUnit,
           ConnectedState, InitializedState, ConnectingState, States,
           PendingKinds, EmptyPendingKinds

THEOREM StutterPreservesLspInv ==
  LspInv /\ UNCHANGED vars => LspInv'
BY LangsAssumption, MaxPendingIdAssumption, MaxReconnectsAssumption, Z3T(30)
   DEF LspInv, vars, TypeOK, FlagConsistency,
           InitializedImpliesConnected, PendingOnlyWhileLive,
           GracefulShutdownDoesNotReconnect, ShutdownStateConsistent,
           PendingKindsMatchPending, PendingIdsAreIssued,
           ShutdownPendingIsShutdown, NoShutdownRequestOutsideShutdown,
           ReconnectQueuedOnlyWhileDisconnected, ReconnectQueueIsUnit

THEOREM LspInvPreservedByNext ==
  LspInv /\ [Next]_vars => LspInv'
<1>1. ASSUME LspInv, [Next]_vars PROVE LspInv'
  <2>1. ASSUME NEW l \in Langs, StartConnect(l) PROVE LspInv'
    BY <1>1, <2>1, StartConnectPreservesLspInv
  <2>2. ASSUME NEW l \in Langs, OpenSocketAndInitialize(l) PROVE LspInv'
    BY <1>1, <2>2, OpenSocketAndInitializePreservesLspInv
  <2>3. ASSUME NEW l \in Langs, InitializeResponse(l) PROVE LspInv'
    BY <1>1, <2>3, InitializeResponsePreservesLspInv
  <2>4. ASSUME NEW l \in Langs, Request(l) PROVE LspInv'
    BY <1>1, <2>4, RequestPreservesLspInv
  <2>5. ASSUME NEW l \in Langs, Response(l) PROVE LspInv'
    BY <1>1, <2>5, ResponsePreservesLspInv
  <2>6. ASSUME NEW l \in Langs, StartShutdown(l) PROVE LspInv'
    BY <1>1, <2>6, StartShutdownPreservesLspInv
  <2>7. ASSUME NEW l \in Langs, ShutdownClosed(l) PROVE LspInv'
    BY <1>1, <2>7, ShutdownClosedPreservesLspInv
  <2>8. ASSUME NEW l \in Langs, SocketError(l) PROVE LspInv'
    BY <1>1, <2>8, SocketErrorPreservesLspInv
  <2>9. ASSUME NEW l \in Langs, UnexpectedClose(l) PROVE LspInv'
    BY <1>1, <2>9, UnexpectedClosePreservesLspInv
  <2>10. ASSUME NEW l \in Langs, Reconnect(l) PROVE LspInv'
    BY <1>1, <2>10, ReconnectPreservesLspInv
  <2>11. ASSUME UNCHANGED vars PROVE LspInv'
    BY <1>1, <2>11, StutterPreservesLspInv
  <2> QED
    BY <1>1, <2>1, <2>2, <2>3, <2>4, <2>5, <2>6, <2>7, <2>8,
       <2>9, <2>10, <2>11 DEF Next, vars
<1> QED
  BY <1>1

================================================================================
