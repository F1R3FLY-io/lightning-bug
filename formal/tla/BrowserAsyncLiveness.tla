-------------------------- MODULE BrowserAsyncLiveness --------------------------
EXTENDS BrowserAsync

DrainDebounce(p) ==
  /\ p \in pendingDebounce
  /\ pendingDebounce' = pendingDebounce \ {p}
  /\ writeHistory' =
       IF mounted[p]
       THEN writeHistory \cup {[pane |-> p, wasMounted |-> TRUE]}
       ELSE writeHistory
  /\ UNCHANGED <<mounted, pendingIdle, everMounted, shutdowns>>

DrainIdle(p) ==
  /\ p \in pendingIdle
  /\ pendingIdle' = pendingIdle \ {p}
  /\ writeHistory' =
       IF mounted[p]
       THEN writeHistory \cup {[pane |-> p, wasMounted |-> TRUE]}
       ELSE writeHistory
  /\ UNCHANGED <<mounted, pendingDebounce, everMounted, shutdowns>>

FairSpec ==
  /\ Init
  /\ [][Next]_vars
  /\ []BrowserInv
  /\ \A p \in Panes:
       /\ WF_vars(DrainDebounce(p))
       /\ WF_vars(DrainIdle(p))

DebounceEventuallyDrains ==
  \A p \in Panes:
    [](p \in pendingDebounce => <>(p \notin pendingDebounce))

IdleEventuallyDrains ==
  \A p \in Panes:
    [](p \in pendingIdle => <>(p \notin pendingIdle))

================================================================================
