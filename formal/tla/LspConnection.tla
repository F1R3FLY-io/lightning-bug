------------------------------- MODULE LspConnection -------------------------------
EXTENDS Naturals, FiniteSets

\* Abstract model of src/lib/lsp/fsm.cljs plus the browser WebSocket events in
\* src/lib/lsp/client.cljs and src/lib/lsp/connection_manager.cljs.

\* @type: Set(Str);
CONSTANTS Langs
\* @type: Int;
CONSTANTS MaxPendingId
\* @type: Int;
CONSTANTS MaxReconnects

ASSUME LangsAssumption == Langs \in SUBSET STRING
ASSUME MaxPendingIdAssumption == MaxPendingId \in (Nat \ {0})
ASSUME MaxReconnectsAssumption == MaxReconnects \in Nat

States ==
  {"disconnected", "connecting", "connected", "initializing",
   "initialized", "disconnecting", "error"}

PendingKinds == {"none", "initialize", "request", "shutdown"}

AllowedTransition ==
  {<<"disconnected", "connecting">>,
   <<"connecting", "connected">>,
   <<"connecting", "error">>,
   <<"connecting", "disconnected">>,
   <<"connected", "initializing">>,
   <<"connected", "disconnecting">>,
   <<"connected", "error">>,
   <<"initializing", "initialized">>,
   <<"initializing", "disconnecting">>,
   <<"initializing", "error">>,
   <<"initializing", "disconnected">>,
   <<"initialized", "disconnecting">>,
   <<"initialized", "error">>,
   <<"initialized", "disconnected">>,
   <<"disconnecting", "disconnected">>,
   <<"error", "disconnected">>,
   <<"error", "connecting">>}

CanTransition(from, to) == <<from, to>> \in AllowedTransition
ConnectedState(s) == s \in {"connected", "initializing", "initialized"}
InitializedState(s) == s = "initialized"
ConnectingState(s) == s = "connecting"

\* @type: Str -> Str;
VARIABLE state
\* @type: Str -> Bool;
VARIABLE connected
\* @type: Str -> Bool;
VARIABLE isInitialized
\* @type: Str -> Bool;
VARIABLE connecting
\* @type: Str -> Bool;
VARIABLE reachable
\* @type: Str -> Set(Int);
VARIABLE pending
\* @type: Str -> (Int -> Str);
VARIABLE pendingKind
\* @type: Str -> Int;
VARIABLE nextId
\* @type: Str -> Bool;
VARIABLE shuttingDown
\* @type: Str -> Int;
VARIABLE reconnects

vars == <<state, connected, isInitialized, connecting, reachable,
          pending, pendingKind, nextId, shuttingDown, reconnects>>

FlagsFor(s) ==
  [connected |-> ConnectedState(s),
   isInitialized |-> InitializedState(s),
   connecting |-> ConnectingState(s),
   reachable |-> ConnectedState(s)]

SetLangState(l, s) ==
  /\ state' = [state EXCEPT ![l] = s]
  /\ connected' = [connected EXCEPT ![l] = FlagsFor(s).connected]
  /\ isInitialized' = [isInitialized EXCEPT ![l] = FlagsFor(s).isInitialized]
  /\ connecting' = [connecting EXCEPT ![l] = FlagsFor(s).connecting]
  /\ reachable' = [reachable EXCEPT ![l] = FlagsFor(s).reachable]

EmptyPendingKinds == [id \in 1..MaxPendingId |-> "none"]
OnlyPendingKind(id, kind) == [EmptyPendingKinds EXCEPT ![id] = kind]

Init ==
  /\ state = [l \in Langs |-> "disconnected"]
  /\ connected = [l \in Langs |-> FALSE]
  /\ isInitialized = [l \in Langs |-> FALSE]
  /\ connecting = [l \in Langs |-> FALSE]
  /\ reachable = [l \in Langs |-> FALSE]
  /\ pending = [l \in Langs |-> {}]
  /\ pendingKind = [l \in Langs |-> EmptyPendingKinds]
  /\ nextId = [l \in Langs |-> 1]
  /\ shuttingDown = [l \in Langs |-> FALSE]
  /\ reconnects = [l \in Langs |-> 0]

StartConnect(l) ==
  /\ state[l] \in {"disconnected", "error"}
  /\ reconnects[l] = 0
  /\ CanTransition(state[l], "connecting")
  /\ SetLangState(l, "connecting")
  /\ shuttingDown' = [shuttingDown EXCEPT ![l] = FALSE]
  /\ UNCHANGED <<pending, pendingKind, nextId, reconnects>>

OpenSocketAndInitialize(l) ==
  /\ state[l] = "connecting"
  /\ nextId[l] <= MaxPendingId
  /\ SetLangState(l, "initializing")
  /\ pending' = [pending EXCEPT ![l] = @ \cup {nextId[l]}]
  /\ pendingKind' = [pendingKind EXCEPT ![l] = [@ EXCEPT ![nextId[l]] = "initialize"]]
  /\ nextId' = [nextId EXCEPT ![l] = @ + 1]
  /\ UNCHANGED <<shuttingDown, reconnects>>

InitializeResponse(l) ==
  /\ state[l] = "initializing"
  /\ pending[l] # {}
  /\ \E id \in pending[l]:
       /\ pendingKind[l][id] = "initialize"
       /\ pending' = [pending EXCEPT ![l] = @ \ {id}]
       /\ pendingKind' = [pendingKind EXCEPT ![l] = [@ EXCEPT ![id] = "none"]]
       /\ SetLangState(l, "initialized")
  /\ UNCHANGED <<nextId, shuttingDown, reconnects>>

Request(l) ==
  /\ state[l] = "initialized"
  /\ nextId[l] <= MaxPendingId
  /\ pending' = [pending EXCEPT ![l] = @ \cup {nextId[l]}]
  /\ pendingKind' = [pendingKind EXCEPT ![l] = [@ EXCEPT ![nextId[l]] = "request"]]
  /\ nextId' = [nextId EXCEPT ![l] = @ + 1]
  /\ UNCHANGED <<state, connected, isInitialized, connecting, reachable,
                shuttingDown, reconnects>>

Response(l) ==
  /\ shuttingDown[l] = FALSE
  /\ state[l] = "initialized"
  /\ pending[l] # {}
  /\ \E id \in pending[l]:
       /\ pendingKind[l][id] = "request"
       /\ pending' = [pending EXCEPT ![l] = @ \ {id}]
       /\ pendingKind' = [pendingKind EXCEPT ![l] = [@ EXCEPT ![id] = "none"]]
  /\ UNCHANGED <<state, connected, isInitialized, connecting, reachable,
                nextId, shuttingDown, reconnects>>

StartShutdown(l) ==
  /\ state[l] \in {"connected", "initializing", "initialized"}
  /\ nextId[l] <= MaxPendingId
  /\ CanTransition(state[l], "disconnecting")
  /\ SetLangState(l, "disconnecting")
  /\ pending' = [pending EXCEPT ![l] = {nextId[l]}]
  /\ pendingKind' = [pendingKind EXCEPT ![l] = OnlyPendingKind(nextId[l], "shutdown")]
  /\ nextId' = [nextId EXCEPT ![l] = @ + 1]
  /\ shuttingDown' = [shuttingDown EXCEPT ![l] = TRUE]
  /\ UNCHANGED reconnects

ShutdownClosed(l) ==
  /\ state[l] = "disconnecting"
  /\ CanTransition("disconnecting", "disconnected")
  /\ SetLangState(l, "disconnected")
  /\ pending' = [pending EXCEPT ![l] = {}]
  /\ pendingKind' = [pendingKind EXCEPT ![l] = EmptyPendingKinds]
  /\ shuttingDown' = [shuttingDown EXCEPT ![l] = FALSE]
  /\ UNCHANGED <<nextId, reconnects>>

SocketError(l) ==
  /\ state[l] \in {"connecting", "connected", "initializing", "initialized"}
  /\ CanTransition(state[l], "error")
  /\ SetLangState(l, "error")
  /\ pending' = [pending EXCEPT ![l] = {}]
  /\ pendingKind' = [pendingKind EXCEPT ![l] = EmptyPendingKinds]
  /\ shuttingDown' = [shuttingDown EXCEPT ![l] = FALSE]
  /\ UNCHANGED <<nextId, reconnects>>

UnexpectedClose(l) ==
  /\ state[l] \in {"connected", "initializing", "initialized"}
  /\ shuttingDown[l] = FALSE
  /\ CanTransition(state[l], "disconnected")
  /\ SetLangState(l, "disconnected")
  /\ pending' = [pending EXCEPT ![l] = {}]
  /\ pendingKind' = [pendingKind EXCEPT ![l] = EmptyPendingKinds]
  /\ reconnects' = [reconnects EXCEPT ![l] = IF @ < MaxReconnects THEN @ + 1 ELSE @]
  /\ UNCHANGED <<nextId, shuttingDown>>

Reconnect(l) ==
  /\ state[l] = "disconnected"
  /\ reconnects[l] > 0
  /\ CanTransition("disconnected", "connecting")
  /\ SetLangState(l, "connecting")
  /\ reconnects' = [reconnects EXCEPT ![l] = @ - 1]
  /\ shuttingDown' = [shuttingDown EXCEPT ![l] = FALSE]
  /\ UNCHANGED <<pending, pendingKind, nextId>>

Next ==
  \/ \E l \in Langs:
       StartConnect(l) \/ OpenSocketAndInitialize(l) \/ InitializeResponse(l)
       \/ Request(l) \/ Response(l) \/ StartShutdown(l) \/ ShutdownClosed(l)
       \/ SocketError(l) \/ UnexpectedClose(l) \/ Reconnect(l)
  \/ UNCHANGED vars

Spec == Init /\ [][Next]_vars

TypeOK ==
  /\ state \in [Langs -> States]
  /\ connected \in [Langs -> BOOLEAN]
  /\ isInitialized \in [Langs -> BOOLEAN]
  /\ connecting \in [Langs -> BOOLEAN]
  /\ reachable \in [Langs -> BOOLEAN]
  /\ pending \in [Langs -> SUBSET (1..MaxPendingId)]
  /\ pendingKind \in [Langs -> [1..MaxPendingId -> PendingKinds]]
  /\ nextId \in [Langs -> 1..(MaxPendingId + 1)]
  /\ shuttingDown \in [Langs -> BOOLEAN]
  /\ reconnects \in [Langs -> 0..MaxReconnects]

FlagConsistency ==
  \A l \in Langs:
    /\ connected[l] = ConnectedState(state[l])
    /\ isInitialized[l] = InitializedState(state[l])
    /\ connecting[l] = ConnectingState(state[l])
    /\ reachable[l] = ConnectedState(state[l])

InitializedImpliesConnected ==
  \A l \in Langs: isInitialized[l] => connected[l]

PendingOnlyWhileLive ==
  \A l \in Langs:
    pending[l] # {} => state[l] \in {"initializing", "initialized", "disconnecting"}

GracefulShutdownDoesNotReconnect ==
  \A l \in Langs: shuttingDown[l] => reconnects[l] = 0

ShutdownStateConsistent ==
  \A l \in Langs:
    shuttingDown[l] =>
      /\ state[l] = "disconnecting"
      /\ \E id \in 1..MaxPendingId:
           pending[l] = {id}

PendingKindsMatchPending ==
  \A l \in Langs:
    \A id \in 1..MaxPendingId:
      (id \in pending[l]) <=> (pendingKind[l][id] # "none")

PendingIdsAreIssued ==
  \A l \in Langs:
    \A id \in pending[l]:
      id < nextId[l]

ShutdownPendingIsShutdown ==
  \A l \in Langs:
    shuttingDown[l] =>
      \A id \in pending[l]:
        pendingKind[l][id] = "shutdown"

NoShutdownRequestOutsideShutdown ==
  \A l \in Langs:
    \A id \in pending[l]:
      pendingKind[l][id] = "shutdown" => shuttingDown[l]

ReconnectQueuedOnlyWhileDisconnected ==
  \A l \in Langs:
    reconnects[l] > 0 => state[l] = "disconnected"

ReconnectQueueIsUnit ==
  \A l \in Langs:
    reconnects[l] <= 1

LspInv ==
  /\ TypeOK
  /\ FlagConsistency
  /\ InitializedImpliesConnected
  /\ PendingOnlyWhileLive
  /\ GracefulShutdownDoesNotReconnect
  /\ ShutdownStateConsistent
  /\ PendingKindsMatchPending
  /\ PendingIdsAreIssued
  /\ ShutdownPendingIsShutdown
  /\ NoShutdownRequestOutsideShutdown
  /\ ReconnectQueuedOnlyWhileDisconnected
  /\ ReconnectQueueIsUnit

================================================================================
