---------------------- MODULE LspConnectionLivenessProofs ----------------------
EXTENDS LspConnectionLiveness, LspConnectionInductiveProofs, TLAPS

THEOREM FairSpecImpliesAlwaysLspInv ==
  FairSpec => []LspInv
<1>1. Init => LspInv
  BY InitEstablishesLspInv
<1>2. LspInv /\ [Next]_vars => LspInv'
  BY LspInvPreservedByNext
<1> QED
  BY <1>1, <1>2, PTL DEF FairSpec

THEOREM FunctionUpdateAtKey ==
  ASSUME NEW D,
         NEW R,
         NEW f \in [D -> R],
         NEW k \in D,
         NEW v
  PROVE ([f EXCEPT ![k] = v])[k] = v
BY Z3T(30)

THEOREM FunctionUpdateChangesFunction ==
  ASSUME NEW D,
         NEW R,
         NEW f \in [D -> R],
         NEW k \in D,
         NEW v,
         f[k] # v
  PROVE [f EXCEPT ![k] = v] # f
BY FunctionUpdateAtKey, Z3T(30)

THEOREM FunctionUpdateOtherKey ==
  ASSUME NEW D,
         NEW R,
         NEW f \in [D -> R],
         NEW k \in D,
         NEW j \in D,
         k # j,
         NEW v
  PROVE ([f EXCEPT ![k] = v])[j] = f[j]
BY Z3T(30)

THEOREM ConnectProgressEnabled ==
  ASSUME LspInv,
         NEW l \in Langs,
         state[l] = "connecting"
  PROVE ENABLED <<ConnectProgress(l)>>_vars
<1>1. state \in [Langs -> States]
  BY DEF LspInv, TypeOK
<1>2. state[l] # "error"
  BY Z3T(30)
<1>3. [state EXCEPT ![l] = "error"] # state
  BY <1>1, <1>2, FunctionUpdateChangesFunction
<1> QED
  BY <1>3, ExpandENABLED DEF ConnectProgress, EmptyPendingKinds, vars

THEOREM ShutdownCloseProgressEnabled ==
  ASSUME LspInv,
         NEW l \in Langs,
         shuttingDown[l]
  PROVE ENABLED <<ShutdownCloseProgress(l)>>_vars
<1>1. state \in [Langs -> States]
  BY DEF LspInv, TypeOK
<1>2. state[l] = "disconnecting"
  BY DEF LspInv, ShutdownStateConsistent
<1>3. state[l] # "disconnected"
  BY <1>2
<1>4. [state EXCEPT ![l] = "disconnected"] # state
  BY <1>1, <1>3, FunctionUpdateChangesFunction
<1> QED
  BY <1>4, ExpandENABLED DEF ShutdownCloseProgress, EmptyPendingKinds, vars

THEOREM ReconnectProgressEnabled ==
  ASSUME LspInv,
         NEW l \in Langs,
         reconnects[l] > 0
  PROVE ENABLED <<ReconnectProgress(l)>>_vars
<1>1. state \in [Langs -> States]
  BY DEF LspInv, TypeOK
<1>2. state[l] = "disconnected"
  BY DEF LspInv, ReconnectQueuedOnlyWhileDisconnected
<1>3. state[l] # "connecting"
  BY <1>2
<1>4. [state EXCEPT ![l] = "connecting"] # state
  BY <1>1, <1>3, FunctionUpdateChangesFunction
<1> QED
  BY <1>4, ExpandENABLED DEF ReconnectProgress, vars

THEOREM ConnectProgressLeavesConnecting ==
  ASSUME LspInv,
         NEW l \in Langs,
         <<ConnectProgress(l)>>_vars
  PROVE state'[l] # "connecting"
<1>1. state \in [Langs -> States]
  BY DEF LspInv, TypeOK
<1>2. state'[l] = "error"
  BY <1>1, FunctionUpdateAtKey DEF ConnectProgress
<1> QED
  BY <1>2, Z3T(30) DEF States

THEOREM ShutdownCloseProgressDisconnects ==
  ASSUME LspInv,
         NEW l \in Langs,
         <<ShutdownCloseProgress(l)>>_vars
  PROVE state'[l] = "disconnected"
<1>1. state \in [Langs -> States]
  BY DEF LspInv, TypeOK
<1> QED
  BY <1>1, FunctionUpdateAtKey DEF ShutdownCloseProgress

THEOREM ReconnectProgressConsumesAndAttempts ==
  ASSUME LspInv,
         NEW l \in Langs,
         <<ReconnectProgress(l)>>_vars
  PROVE /\ reconnects'[l] = 0
        /\ state'[l] = "connecting"
<1>1. state \in [Langs -> States]
  BY DEF LspInv, TypeOK
<1>2. reconnects \in [Langs -> 0..MaxReconnects]
  BY DEF LspInv, TypeOK
<1>3. state'[l] = "connecting"
  BY <1>1, FunctionUpdateAtKey DEF ReconnectProgress
<1>4. reconnects'[l] = 0
  BY <1>2, MaxReconnectsAssumption, FunctionUpdateAtKey DEF ReconnectProgress
<1> QED
  BY <1>3, <1>4

THEOREM ShutdownStepStaysPendingOrDisconnects ==
  ASSUME LspInv,
         NEW l \in Langs,
         shuttingDown[l],
         [Next]_vars
  PROVE shuttingDown'[l] \/ state'[l] = "disconnected"
<1>1. state[l] = "disconnecting"
  BY DEF LspInv, ShutdownStateConsistent
<1>2. ASSUME UNCHANGED vars
      PROVE shuttingDown'[l]
  BY <1>2 DEF vars
<1>3. ASSUME Next
      PROVE shuttingDown'[l] \/ state'[l] = "disconnected"
  <2>1. ASSUME NEW q \in Langs, StartConnect(q)
        PROVE shuttingDown'[l] \/ state'[l] = "disconnected"
    BY <1>1, <2>1, FunctionUpdateOtherKey, SMT
       DEF LspInv, TypeOK, StartConnect, SetLangState, FlagsFor,
           ShutdownStateConsistent, ConnectedState, InitializedState,
           ConnectingState, States
  <2>2. ASSUME NEW q \in Langs, OpenSocketAndInitialize(q)
        PROVE shuttingDown'[l] \/ state'[l] = "disconnected"
    BY <1>1, <2>2, SMT
       DEF OpenSocketAndInitialize
  <2>3. ASSUME NEW q \in Langs, InitializeResponse(q)
        PROVE shuttingDown'[l] \/ state'[l] = "disconnected"
    BY <1>1, <2>3, FunctionUpdateOtherKey, SMT
       DEF LspInv, TypeOK, InitializeResponse, SetLangState, FlagsFor,
           ShutdownStateConsistent, ConnectedState, InitializedState,
           ConnectingState, States
  <2>4. ASSUME NEW q \in Langs, Request(q)
        PROVE shuttingDown'[l] \/ state'[l] = "disconnected"
    BY <1>1, <2>4, SMT
       DEF Request
  <2>5. ASSUME NEW q \in Langs, Response(q)
        PROVE shuttingDown'[l] \/ state'[l] = "disconnected"
    BY <1>1, <2>5, SMT
       DEF Response
  <2>6. ASSUME NEW q \in Langs, StartShutdown(q)
        PROVE shuttingDown'[l] \/ state'[l] = "disconnected"
    BY <1>1, <2>6, FunctionUpdateOtherKey, SMT
       DEF LspInv, TypeOK, StartShutdown, SetLangState, FlagsFor,
           ShutdownStateConsistent, ConnectedState, InitializedState,
           ConnectingState, States
  <2>7. ASSUME NEW q \in Langs, ShutdownClosed(q)
        PROVE shuttingDown'[l] \/ state'[l] = "disconnected"
    BY <1>1, <2>7, FunctionUpdateAtKey, FunctionUpdateOtherKey, SMT
       DEF LspInv, TypeOK, ShutdownClosed, SetLangState, FlagsFor,
           ShutdownStateConsistent, ConnectedState, InitializedState,
           ConnectingState, States
  <2>8. ASSUME NEW q \in Langs, SocketError(q)
        PROVE shuttingDown'[l] \/ state'[l] = "disconnected"
    BY <1>1, <2>8, FunctionUpdateOtherKey, SMT
       DEF LspInv, TypeOK, SocketError, SetLangState, FlagsFor,
           ShutdownStateConsistent, ConnectedState, InitializedState,
           ConnectingState, States
  <2>9. ASSUME NEW q \in Langs, UnexpectedClose(q)
        PROVE shuttingDown'[l] \/ state'[l] = "disconnected"
    BY <1>1, <2>9, FunctionUpdateOtherKey, SMT
       DEF LspInv, TypeOK, UnexpectedClose, SetLangState, FlagsFor,
           ShutdownStateConsistent, ConnectedState, InitializedState,
           ConnectingState, States
  <2>10. ASSUME NEW q \in Langs, Reconnect(q)
         PROVE shuttingDown'[l] \/ state'[l] = "disconnected"
    BY <1>1, <2>10, FunctionUpdateOtherKey, SMT
       DEF LspInv, TypeOK, Reconnect, SetLangState, FlagsFor,
           ShutdownStateConsistent, ConnectedState, InitializedState,
           ConnectingState, States
  <2>11. ASSUME UNCHANGED vars
         PROVE shuttingDown'[l] \/ state'[l] = "disconnected"
    BY <2>11 DEF vars
  <2> QED
    BY <2>1, <2>2, <2>3, <2>4, <2>5, <2>6, <2>7, <2>8, <2>9,
       <2>10, <2>11 DEF Next
<1> QED
  BY <1>2, <1>3 DEF vars

THEOREM QueuedReconnectStepStaysQueuedOrConsumed ==
  ASSUME LspInv,
         NEW l \in Langs,
         reconnects[l] > 0,
         [Next]_vars
  PROVE reconnects'[l] > 0 \/ reconnects'[l] = 0
<1>1. LspInv'
  BY LspInvPreservedByNext
<1>2. reconnects'[l] \in 0..MaxReconnects
  BY <1>1 DEF LspInv, TypeOK
<1> QED
  BY <1>2, MaxReconnectsAssumption, Z3T(30)

THEOREM QueuedReconnectStepStaysQueuedOrAttempts ==
  ASSUME LspInv,
         NEW l \in Langs,
         reconnects[l] > 0,
         [Next]_vars
  PROVE reconnects'[l] > 0 \/ state'[l] = "connecting"
<1>1. state[l] = "disconnected"
  BY DEF LspInv, ReconnectQueuedOnlyWhileDisconnected
<1>2. ASSUME UNCHANGED vars
      PROVE reconnects'[l] > 0
  BY <1>2 DEF vars
<1>3. ASSUME Next
      PROVE reconnects'[l] > 0 \/ state'[l] = "connecting"
  <2>1. ASSUME NEW q \in Langs, StartConnect(q)
        PROVE reconnects'[l] > 0 \/ state'[l] = "connecting"
    BY <1>1, <2>1, FunctionUpdateOtherKey, SMT
       DEF LspInv, TypeOK, StartConnect, SetLangState, FlagsFor,
           ReconnectQueuedOnlyWhileDisconnected, ConnectedState,
           InitializedState, ConnectingState, States
  <2>2. ASSUME NEW q \in Langs, OpenSocketAndInitialize(q)
        PROVE reconnects'[l] > 0 \/ state'[l] = "connecting"
    BY <1>1, <2>2, SMT
       DEF OpenSocketAndInitialize
  <2>3. ASSUME NEW q \in Langs, InitializeResponse(q)
        PROVE reconnects'[l] > 0 \/ state'[l] = "connecting"
    BY <1>1, <2>3, FunctionUpdateOtherKey, SMT
       DEF LspInv, TypeOK, InitializeResponse, SetLangState, FlagsFor,
           ReconnectQueuedOnlyWhileDisconnected, ConnectedState,
           InitializedState, ConnectingState, States
  <2>4. ASSUME NEW q \in Langs, Request(q)
        PROVE reconnects'[l] > 0 \/ state'[l] = "connecting"
    BY <1>1, <2>4, SMT
       DEF Request
  <2>5. ASSUME NEW q \in Langs, Response(q)
        PROVE reconnects'[l] > 0 \/ state'[l] = "connecting"
    BY <1>1, <2>5, SMT
       DEF Response
  <2>6. ASSUME NEW q \in Langs, StartShutdown(q)
        PROVE reconnects'[l] > 0 \/ state'[l] = "connecting"
    BY <1>1, <2>6, SMT
       DEF StartShutdown
  <2>7. ASSUME NEW q \in Langs, ShutdownClosed(q)
        PROVE reconnects'[l] > 0 \/ state'[l] = "connecting"
    BY <1>1, <2>7, SMT
       DEF ShutdownClosed
  <2>8. ASSUME NEW q \in Langs, SocketError(q)
        PROVE reconnects'[l] > 0 \/ state'[l] = "connecting"
    BY <1>1, <2>8, SMT
       DEF SocketError
  <2>9. ASSUME NEW q \in Langs, UnexpectedClose(q)
        PROVE reconnects'[l] > 0 \/ state'[l] = "connecting"
    BY <1>1, <2>9, FunctionUpdateOtherKey, SMT
       DEF LspInv, TypeOK, UnexpectedClose, SetLangState, FlagsFor,
           ReconnectQueuedOnlyWhileDisconnected, ConnectedState,
           InitializedState, ConnectingState, States
  <2>10. ASSUME NEW q \in Langs, Reconnect(q)
         PROVE reconnects'[l] > 0 \/ state'[l] = "connecting"
    BY <1>1, <2>10, FunctionUpdateAtKey, FunctionUpdateOtherKey, SMT
       DEF LspInv, TypeOK, Reconnect, SetLangState, FlagsFor,
           ReconnectQueuedOnlyWhileDisconnected, ConnectedState,
           InitializedState, ConnectingState, States
  <2>11. ASSUME UNCHANGED vars
         PROVE reconnects'[l] > 0 \/ state'[l] = "connecting"
    BY <2>11 DEF vars
  <2> QED
    BY <2>1, <2>2, <2>3, <2>4, <2>5, <2>6, <2>7, <2>8, <2>9,
       <2>10, <2>11 DEF Next
<1> QED
  BY <1>2, <1>3 DEF vars

THEOREM ConnectingLeadsToResolved ==
  ASSUME NEW l \in Langs
  PROVE FairSpec => (state[l] = "connecting" ~> state[l] # "connecting")
<1> DEFINE P == state[l] = "connecting"
<1> DEFINE Q == state[l] # "connecting"
<1> DEFINE BSpec == /\ []LspInv
                     /\ [][Next]_vars
                     /\ WF_vars(ConnectProgress(l))
<1>1. LspInv /\ P /\ [Next]_vars => P' \/ Q'
  BY DEF P, Q
<1>2. LspInv /\ P /\ <<Next /\ ConnectProgress(l)>>_vars => Q'
  BY ConnectProgressLeavesConnecting DEF P, Q
<1>3. LspInv /\ P => ENABLED <<ConnectProgress(l)>>_vars
  BY ConnectProgressEnabled DEF P
<1>4. BSpec => (P ~> Q)
  BY <1>1, <1>2, <1>3, PTL DEF BSpec
<1>5. FairSpec => BSpec
  BY DEF FairSpec, BSpec
<1> QED
  BY <1>4, <1>5, PTL DEF P, Q

THEOREM ShutdownLeadsToDisconnected ==
  ASSUME NEW l \in Langs
  PROVE FairSpec => (shuttingDown[l] ~> state[l] = "disconnected")
<1> DEFINE P == shuttingDown[l]
<1> DEFINE Q == state[l] = "disconnected"
<1> DEFINE BSpec == /\ []LspInv
                     /\ [][Next]_vars
                     /\ WF_vars(ShutdownCloseProgress(l))
<1>1. LspInv /\ P /\ [Next]_vars => P' \/ Q'
  BY ShutdownStepStaysPendingOrDisconnects DEF P, Q
<1>2. LspInv /\ P /\ <<Next /\ ShutdownCloseProgress(l)>>_vars => Q'
  BY ShutdownCloseProgressDisconnects DEF P, Q
<1>3. LspInv /\ P => ENABLED <<ShutdownCloseProgress(l)>>_vars
  BY ShutdownCloseProgressEnabled DEF P
<1>4. BSpec => (P ~> Q)
  BY <1>1, <1>2, <1>3, PTL DEF BSpec
<1>5. FairSpec => BSpec
  BY DEF FairSpec, BSpec
<1> QED
  BY <1>4, <1>5, PTL DEF P, Q

THEOREM QueuedReconnectLeadsToConsumed ==
  ASSUME NEW l \in Langs
  PROVE FairSpec => (reconnects[l] > 0 ~> reconnects[l] = 0)
<1> DEFINE P == reconnects[l] > 0
<1> DEFINE Q == reconnects[l] = 0
<1> DEFINE BSpec == /\ []LspInv
                     /\ [][Next]_vars
                     /\ WF_vars(ReconnectProgress(l))
<1>1. LspInv /\ P /\ [Next]_vars => P' \/ Q'
  BY QueuedReconnectStepStaysQueuedOrConsumed DEF P, Q
<1>2. LspInv /\ P /\ <<Next /\ ReconnectProgress(l)>>_vars => Q'
  BY ReconnectProgressConsumesAndAttempts DEF P, Q
<1>3. LspInv /\ P => ENABLED <<ReconnectProgress(l)>>_vars
  BY ReconnectProgressEnabled DEF P
<1>4. BSpec => (P ~> Q)
  BY <1>1, <1>2, <1>3, PTL DEF BSpec
<1>5. FairSpec => BSpec
  BY DEF FairSpec, BSpec
<1> QED
  BY <1>4, <1>5, PTL DEF P, Q

THEOREM QueuedReconnectLeadsToAttempt ==
  ASSUME NEW l \in Langs
  PROVE FairSpec => (reconnects[l] > 0 ~> state[l] = "connecting")
<1> DEFINE P == reconnects[l] > 0
<1> DEFINE Q == state[l] = "connecting"
<1> DEFINE BSpec == /\ []LspInv
                     /\ [][Next]_vars
                     /\ WF_vars(ReconnectProgress(l))
<1>1. LspInv /\ P /\ [Next]_vars => P' \/ Q'
  BY QueuedReconnectStepStaysQueuedOrAttempts DEF P, Q
<1>2. LspInv /\ P /\ <<Next /\ ReconnectProgress(l)>>_vars => Q'
  BY ReconnectProgressConsumesAndAttempts DEF P, Q
<1>3. LspInv /\ P => ENABLED <<ReconnectProgress(l)>>_vars
  BY ReconnectProgressEnabled DEF P
<1>4. BSpec => (P ~> Q)
  BY <1>1, <1>2, <1>3, PTL DEF BSpec
<1>5. FairSpec => BSpec
  BY DEF FairSpec, BSpec
<1> QED
  BY <1>4, <1>5, PTL DEF P, Q

THEOREM FairSpecImpliesConnectingEventuallyResolves ==
  FairSpec => ConnectingEventuallyResolves
<1>1. ASSUME NEW l \in Langs
      PROVE FairSpec => [](state[l] = "connecting" => <>(state[l] # "connecting"))
  BY ConnectingLeadsToResolved, PTL
<1> QED
  BY <1>1 DEF ConnectingEventuallyResolves

THEOREM FairSpecImpliesShutdownEventuallyDisconnects ==
  FairSpec => ShutdownEventuallyDisconnects
<1>1. ASSUME NEW l \in Langs
      PROVE FairSpec => [](shuttingDown[l] => <>(state[l] = "disconnected"))
  BY ShutdownLeadsToDisconnected, PTL
<1> QED
  BY <1>1 DEF ShutdownEventuallyDisconnects

THEOREM FairSpecImpliesQueuedReconnectEventuallyConsumed ==
  FairSpec => QueuedReconnectEventuallyConsumed
<1>1. ASSUME NEW l \in Langs
      PROVE FairSpec => [](reconnects[l] > 0 => <>(reconnects[l] = 0))
  BY QueuedReconnectLeadsToConsumed, PTL
<1> QED
  BY <1>1 DEF QueuedReconnectEventuallyConsumed

THEOREM FairSpecImpliesQueuedReconnectEventuallyAttempts ==
  FairSpec => QueuedReconnectEventuallyAttempts
<1>1. ASSUME NEW l \in Langs
      PROVE FairSpec => [](reconnects[l] > 0 => <>(state[l] = "connecting"))
  BY QueuedReconnectLeadsToAttempt, PTL
<1> QED
  BY <1>1 DEF QueuedReconnectEventuallyAttempts

================================================================================
