------------------------------- MODULE BrowserAsync -------------------------------
EXTENDS Naturals

\* Browser callback model for the editor's debounced events, idle DataScript sync,
\* and React unmount cleanup. Stale callbacks are allowed to fire, but they must
\* observe the unmounted state and avoid mutation.

\* @type: Set(Str);
CONSTANTS Panes

ASSUME PanesAssumption == Panes \in SUBSET STRING

\* @type: Str -> Bool;
VARIABLE mounted
\* @type: Set(Str);
VARIABLE pendingDebounce
\* @type: Set(Str);
VARIABLE pendingIdle
\* @type: Str -> Bool;
VARIABLE everMounted
\* @type: Set([pane: Str, wasMounted: Bool]);
VARIABLE writeHistory
\* @type: Str -> Bool;
VARIABLE shutdowns

vars == <<mounted, pendingDebounce, pendingIdle, everMounted, writeHistory,
          shutdowns>>

WriteRecords == {[pane |-> p, wasMounted |-> TRUE] : p \in Panes}

Init ==
  /\ mounted = [p \in Panes |-> FALSE]
  /\ pendingDebounce = {}
  /\ pendingIdle = {}
  /\ everMounted = [p \in Panes |-> FALSE]
  /\ writeHistory = {}
  /\ shutdowns = [p \in Panes |-> FALSE]

Mount(p) ==
  /\ mounted[p] = FALSE
  /\ mounted' = [mounted EXCEPT ![p] = TRUE]
  /\ everMounted' = [everMounted EXCEPT ![p] = TRUE]
  /\ shutdowns' = [shutdowns EXCEPT ![p] = FALSE]
  /\ UNCHANGED <<pendingDebounce, pendingIdle, writeHistory>>

ScheduleDebounce(p) ==
  /\ mounted[p]
  /\ pendingDebounce' = pendingDebounce \cup {p}
  /\ UNCHANGED <<mounted, pendingIdle, everMounted, writeHistory, shutdowns>>

ScheduleIdle(p) ==
  /\ mounted[p]
  /\ pendingIdle' = pendingIdle \cup {p}
  /\ UNCHANGED <<mounted, pendingDebounce, everMounted, writeHistory, shutdowns>>

FireDebounce(p) ==
  /\ p \in pendingDebounce
  /\ mounted[p]
  /\ pendingDebounce' = pendingDebounce \ {p}
  /\ writeHistory' = writeHistory \cup {[pane |-> p, wasMounted |-> TRUE]}
  /\ UNCHANGED <<mounted, pendingIdle, everMounted, shutdowns>>

FireIdle(p) ==
  /\ p \in pendingIdle
  /\ mounted[p]
  /\ pendingIdle' = pendingIdle \ {p}
  /\ writeHistory' = writeHistory \cup {[pane |-> p, wasMounted |-> TRUE]}
  /\ UNCHANGED <<mounted, pendingDebounce, everMounted, shutdowns>>

StaleDebounceAfterUnmount(p) ==
  /\ p \in pendingDebounce
  /\ mounted[p] = FALSE
  /\ pendingDebounce' = pendingDebounce \ {p}
  /\ UNCHANGED <<mounted, pendingIdle, everMounted, writeHistory, shutdowns>>

StaleIdleAfterUnmount(p) ==
  /\ p \in pendingIdle
  /\ mounted[p] = FALSE
  /\ pendingIdle' = pendingIdle \ {p}
  /\ UNCHANGED <<mounted, pendingDebounce, everMounted, writeHistory, shutdowns>>

Unmount(p) ==
  /\ mounted[p]
  /\ mounted' = [mounted EXCEPT ![p] = FALSE]
  /\ shutdowns' = [shutdowns EXCEPT ![p] = TRUE]
  /\ UNCHANGED <<pendingDebounce, pendingIdle, everMounted, writeHistory>>

Next ==
  \/ \E p \in Panes:
       Mount(p) \/ ScheduleDebounce(p) \/ ScheduleIdle(p)
       \/ FireDebounce(p) \/ FireIdle(p)
       \/ StaleDebounceAfterUnmount(p) \/ StaleIdleAfterUnmount(p)
       \/ Unmount(p)
  \/ UNCHANGED vars

Spec == Init /\ [][Next]_vars

TypeOK ==
  /\ mounted \in [Panes -> BOOLEAN]
  /\ pendingDebounce \in SUBSET Panes
  /\ pendingIdle \in SUBSET Panes
  /\ everMounted \in [Panes -> BOOLEAN]
  /\ writeHistory \in SUBSET WriteRecords
  /\ shutdowns \in [Panes -> BOOLEAN]

NoUnmountedMutation ==
  \A w \in writeHistory: w.wasMounted = TRUE

UnmountRequestsShutdown ==
  \A p \in Panes: everMounted[p] /\ mounted[p] = FALSE => shutdowns[p]

BrowserInv ==
  /\ TypeOK
  /\ NoUnmountedMutation
  /\ UnmountRequestsShutdown

================================================================================
