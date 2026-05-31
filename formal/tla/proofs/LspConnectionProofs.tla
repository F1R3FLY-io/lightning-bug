--------------------------- MODULE LspConnectionProofs ---------------------------
EXTENDS LspConnection, TLAPS

THEOREM FlagConsistencyImpliesInitializedConnected ==
  FlagConsistency => InitializedImpliesConnected
<1>1. ASSUME FlagConsistency, NEW l \in Langs
      PROVE isInitialized[l] => connected[l]
  BY <1>1 DEF FlagConsistency, ConnectedState, InitializedState
<1> QED
  BY <1>1 DEF InitializedImpliesConnected

THEOREM StartShutdownClearsUnrelatedPending ==
  \A l \in Langs:
    StartShutdown(l) =>
      /\ state' = [state EXCEPT ![l] = "disconnecting"]
      /\ shuttingDown' = [shuttingDown EXCEPT ![l] = TRUE]
      /\ pending' = [pending EXCEPT ![l] = {nextId[l]}]
  BY DEF StartShutdown, SetLangState, CanTransition, FlagsFor

================================================================================
