------------------------------ MODULE DocSyncProofs ------------------------------
EXTENDS DocSync, TLAPS

THEOREM InitEstablishesDocSyncInv ==
  ASSUME Init
  PROVE DocSyncInv
BY PanesAssumption, UrisAssumption, MaxTextAssumption, Z3T(30)
   DEF Init, DocSyncInv, TypeOK, SubscribedPanesConverge, NoOriginEcho,
       SeqMatchesEdits, DeliverySeqPrecedesWorkspace

THEOREM UserEditAppliesDeltaToSubscribers ==
  \A p \in Panes:
    \A u \in Uris:
      UserEdit(p, u) =>
        paneText' =
          [q \in Panes |->
            [x \in Uris |->
              IF x = u /\ q \in subscribers[u]
              THEN paneText[q][x] + 1
              ELSE paneText[q][x]]]
  BY DEF UserEdit

THEOREM UserEditDoesNotEchoToOrigin ==
  \A p \in Panes:
    \A u \in Uris:
      UserEdit(p, u) =>
        \A d \in deliveries' \ deliveries: d.origin # d.pane
  BY DEF UserEdit

THEOREM UserEditAdvancesWorkspaceAndSeq ==
  \A p \in Panes:
    \A u \in Uris:
      UserEdit(p, u) =>
        /\ workspaceText' = [workspaceText EXCEPT ![u] = workspaceText[u] + 1]
        /\ seq' = [seq EXCEPT ![u] = seq[u] + 1]
  BY DEF UserEdit

THEOREM SubscribePreservesDocSyncInv ==
  ASSUME DocSyncInv,
         NEW p \in Panes,
         NEW u \in Uris,
         Subscribe(p, u)
  PROVE DocSyncInv'
BY PanesAssumption, UrisAssumption, MaxTextAssumption, Z3T(30)
   DEF DocSyncInv, Subscribe, TypeOK, SubscribedPanesConverge, NoOriginEcho,
       SeqMatchesEdits, DeliverySeqPrecedesWorkspace

THEOREM UnsubscribePreservesDocSyncInv ==
  ASSUME DocSyncInv,
         NEW p \in Panes,
         NEW u \in Uris,
         Unsubscribe(p, u)
  PROVE DocSyncInv'
BY PanesAssumption, UrisAssumption, MaxTextAssumption, Z3T(30)
   DEF DocSyncInv, Unsubscribe, TypeOK, SubscribedPanesConverge, NoOriginEcho,
       SeqMatchesEdits, DeliverySeqPrecedesWorkspace

THEOREM UserEditPreservesDocSyncInv ==
  ASSUME DocSyncInv,
         NEW p \in Panes,
         NEW u \in Uris,
         UserEdit(p, u)
  PROVE DocSyncInv'
BY PanesAssumption, UrisAssumption, MaxTextAssumption, Z3T(30)
   DEF DocSyncInv, UserEdit, TypeOK, SubscribedPanesConverge, NoOriginEcho,
       SeqMatchesEdits, DeliverySeqPrecedesWorkspace

THEOREM StutterPreservesDocSyncInv ==
  ASSUME DocSyncInv,
         UNCHANGED vars
  PROVE DocSyncInv'
<1>1. subscribers' = subscribers /\ workspaceText' = workspaceText
      /\ paneText' = paneText /\ seq' = seq /\ deliveries' = deliveries
  BY DEF vars
<1>2. TypeOK'
  BY <1>1 DEF DocSyncInv, TypeOK
<1>3. SubscribedPanesConverge'
  BY <1>1 DEF DocSyncInv, SubscribedPanesConverge
<1>4. NoOriginEcho'
  BY <1>1 DEF DocSyncInv, NoOriginEcho
<1>5. SeqMatchesEdits'
  BY <1>1 DEF DocSyncInv, SeqMatchesEdits
<1>6. DeliverySeqPrecedesWorkspace'
  BY <1>1 DEF DocSyncInv, DeliverySeqPrecedesWorkspace
<1> QED
  BY <1>2, <1>3, <1>4, <1>5, <1>6 DEF DocSyncInv

THEOREM DocSyncInvPreservedByNext ==
  DocSyncInv /\ [Next]_vars => DocSyncInv'
<1>1. ASSUME DocSyncInv, [Next]_vars PROVE DocSyncInv'
  <2>1. ASSUME NEW p \in Panes, NEW u \in Uris, Subscribe(p, u)
        PROVE DocSyncInv'
    BY <1>1, <2>1, SubscribePreservesDocSyncInv
  <2>2. ASSUME NEW p \in Panes, NEW u \in Uris, Unsubscribe(p, u)
        PROVE DocSyncInv'
    BY <1>1, <2>2, UnsubscribePreservesDocSyncInv
  <2>3. ASSUME NEW p \in Panes, NEW u \in Uris, UserEdit(p, u)
        PROVE DocSyncInv'
    BY <1>1, <2>3, UserEditPreservesDocSyncInv
  <2>4. ASSUME UNCHANGED vars PROVE DocSyncInv'
    BY <1>1, <2>4, StutterPreservesDocSyncInv
  <2> QED
    BY <1>1, <2>1, <2>2, <2>3, <2>4 DEF Next, vars
<1> QED
  BY <1>1

================================================================================
