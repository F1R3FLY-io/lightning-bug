------------------------------ MODULE DocSyncProofs ------------------------------
EXTENDS DocSync, TLAPS

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

================================================================================
