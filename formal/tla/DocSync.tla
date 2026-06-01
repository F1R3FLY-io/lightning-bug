-------------------------------- MODULE DocSync --------------------------------
EXTENDS Naturals, FiniteSets

\* Abstract model of lib.workspace.doc-sync. A workspace text counter stands in
\* for the CodeMirror document; each subscribed pane is seeded from that counter.
\* A user edit applies once to the origin and once to each peer, never back to
\* the origin through the RxJS stream.

\* @type: Set(Str);
CONSTANTS Panes
\* @type: Set(Str);
CONSTANTS Uris
\* @type: Int;
CONSTANTS MaxText

ASSUME PanesAssumption == Panes \in SUBSET STRING
ASSUME UrisAssumption == Uris \in SUBSET STRING
ASSUME MaxTextAssumption == MaxText \in Nat

\* @type: Str -> Set(Str);
VARIABLE subscribers
\* @type: Str -> Int;
VARIABLE workspaceText
\* @type: Str -> (Str -> Int);
VARIABLE paneText
\* @type: Str -> Int;
VARIABLE seq
\* @type: Set([uri: Str, origin: Str, pane: Str, n: Int]);
VARIABLE deliveries

vars == <<subscribers, workspaceText, paneText, seq, deliveries>>

Init ==
  /\ subscribers = [u \in Uris |-> {}]
  /\ workspaceText = [u \in Uris |-> 0]
  /\ paneText = [p \in Panes |-> [u \in Uris |-> 0]]
  /\ seq = [u \in Uris |-> 0]
  /\ deliveries = {}

Subscribe(p, u) ==
  /\ p \notin subscribers[u]
  /\ subscribers' = [subscribers EXCEPT ![u] = @ \cup {p}]
  /\ paneText' = [paneText EXCEPT ![p][u] = workspaceText[u]]
  /\ UNCHANGED <<workspaceText, seq, deliveries>>

Unsubscribe(p, u) ==
  /\ p \in subscribers[u]
  /\ subscribers' = [subscribers EXCEPT ![u] = @ \ {p}]
  /\ UNCHANGED <<workspaceText, paneText, seq, deliveries>>

UserEdit(p, u) ==
  /\ p \in subscribers[u]
  /\ workspaceText[u] < MaxText
  /\ workspaceText' = [workspaceText EXCEPT ![u] = @ + 1]
  /\ paneText' =
       [q \in Panes |->
         [x \in Uris |->
           IF x = u /\ q \in subscribers[u]
           THEN paneText[q][x] + 1
           ELSE paneText[q][x]]]
  /\ deliveries' =
       deliveries \cup
         { [uri |-> u, origin |-> p, pane |-> q, n |-> seq[u]]
             : q \in subscribers[u] \ {p} }
  /\ seq' = [seq EXCEPT ![u] = @ + 1]
  /\ UNCHANGED subscribers

Next ==
  \/ \E p \in Panes, u \in Uris:
       Subscribe(p, u) \/ Unsubscribe(p, u) \/ UserEdit(p, u)
  \/ UNCHANGED vars

Spec == Init /\ [][Next]_vars

TypeOK ==
  /\ subscribers \in [Uris -> SUBSET Panes]
  /\ workspaceText \in [Uris -> 0..MaxText]
  /\ paneText \in [Panes -> [Uris -> 0..MaxText]]
  /\ seq \in [Uris -> 0..MaxText]
  /\ deliveries \in SUBSET
       {[uri |-> u, origin |-> o, pane |-> p, n |-> k]
          : u \in Uris, o \in Panes, p \in Panes, k \in 0..MaxText}

SubscribedPanesConverge ==
  \A u \in Uris:
    \A p \in subscribers[u]:
      paneText[p][u] = workspaceText[u]

NoOriginEcho ==
  \A d \in deliveries: d.origin # d.pane

SeqMatchesEdits ==
  \A u \in Uris: seq[u] = workspaceText[u]

DeliverySeqPrecedesWorkspace ==
  \A d \in deliveries: d.n < workspaceText[d.uri]

DocSyncInv ==
  /\ TypeOK
  /\ SubscribedPanesConverge
  /\ NoOriginEcho
  /\ SeqMatchesEdits
  /\ DeliverySeqPrecedesWorkspace

================================================================================
