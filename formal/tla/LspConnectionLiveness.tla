-------------------------- MODULE LspConnectionLiveness --------------------------
EXTENDS LspConnection

ConnectProgress(l) ==
  /\ state[l] = "connecting"
  /\ state' = [state EXCEPT ![l] = "error"]
  /\ connected' = [connected EXCEPT ![l] = FALSE]
  /\ isInitialized' = [isInitialized EXCEPT ![l] = FALSE]
  /\ connecting' = [connecting EXCEPT ![l] = FALSE]
  /\ reachable' = [reachable EXCEPT ![l] = FALSE]
  /\ pending' = [pending EXCEPT ![l] = {}]
  /\ pendingKind' = [pendingKind EXCEPT ![l] = EmptyPendingKinds]
  /\ shuttingDown' = [shuttingDown EXCEPT ![l] = FALSE]
  /\ UNCHANGED <<nextId, reconnects>>

ShutdownCloseProgress(l) ==
  /\ shuttingDown[l]
  /\ state' = [state EXCEPT ![l] = "disconnected"]
  /\ connected' = [connected EXCEPT ![l] = FALSE]
  /\ isInitialized' = [isInitialized EXCEPT ![l] = FALSE]
  /\ connecting' = [connecting EXCEPT ![l] = FALSE]
  /\ reachable' = [reachable EXCEPT ![l] = FALSE]
  /\ pending' = [pending EXCEPT ![l] = {}]
  /\ pendingKind' = [pendingKind EXCEPT ![l] = EmptyPendingKinds]
  /\ shuttingDown' = [shuttingDown EXCEPT ![l] = FALSE]
  /\ UNCHANGED <<nextId, reconnects>>

ReconnectProgress(l) ==
  /\ reconnects[l] > 0
  /\ state' = [state EXCEPT ![l] = "connecting"]
  /\ connected' = [connected EXCEPT ![l] = FALSE]
  /\ isInitialized' = [isInitialized EXCEPT ![l] = FALSE]
  /\ connecting' = [connecting EXCEPT ![l] = TRUE]
  /\ reachable' = [reachable EXCEPT ![l] = FALSE]
  /\ reconnects' = [reconnects EXCEPT ![l] = 0]
  /\ shuttingDown' = [shuttingDown EXCEPT ![l] = FALSE]
  /\ UNCHANGED <<pending, pendingKind, nextId>>

FairSpec ==
  /\ Init
  /\ [][Next]_vars
  /\ []LspInv
  /\ \A l \in Langs:
       /\ WF_vars(ConnectProgress(l))
       /\ WF_vars(ShutdownCloseProgress(l))
       /\ WF_vars(ReconnectProgress(l))

ConnectingEventuallyResolves ==
  \A l \in Langs:
    [](state[l] = "connecting" => <>(state[l] # "connecting"))

ShutdownEventuallyDisconnects ==
  \A l \in Langs:
    [](shuttingDown[l] => <>(state[l] = "disconnected"))

QueuedReconnectEventuallyConsumed ==
  \A l \in Langs:
    [](reconnects[l] > 0 => <>(reconnects[l] = 0))

QueuedReconnectEventuallyAttempts ==
  \A l \in Langs:
    [](reconnects[l] > 0 => <>(state[l] = "connecting"))

================================================================================
