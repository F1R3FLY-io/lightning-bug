------------------------------- MODULE BrowserAsync -------------------------------
EXTENDS Naturals

\* Browser callback model for the editor's debounced events, idle DataScript sync,
\* and React unmount cleanup. Stale callbacks are allowed to fire, but they must
\* observe the unmounted state and avoid mutation.

\* @type: Set(Str);
CONSTANTS Panes
\* @type: Int;
CONSTANTS MaxWrites

\* @type: Str -> Bool;
VARIABLE mounted
\* @type: Set(Str);
VARIABLE pendingDebounce
\* @type: Set(Str);
VARIABLE pendingIdle
\* @type: Str -> Int;
VARIABLE dbWrites
\* @type: Bool;
VARIABLE unsafeWrites
\* @type: Str -> Bool;
VARIABLE shutdowns

vars == <<mounted, pendingDebounce, pendingIdle, dbWrites, unsafeWrites, shutdowns>>

Init ==
  /\ mounted = [p \in Panes |-> FALSE]
  /\ pendingDebounce = {}
  /\ pendingIdle = {}
  /\ dbWrites = [p \in Panes |-> 0]
  /\ unsafeWrites = FALSE
  /\ shutdowns = [p \in Panes |-> FALSE]

Mount(p) ==
  /\ mounted[p] = FALSE
  /\ mounted' = [mounted EXCEPT ![p] = TRUE]
  /\ shutdowns' = [shutdowns EXCEPT ![p] = FALSE]
  /\ UNCHANGED <<pendingDebounce, pendingIdle, dbWrites, unsafeWrites>>

ScheduleDebounce(p) ==
  /\ mounted[p]
  /\ pendingDebounce' = pendingDebounce \cup {p}
  /\ UNCHANGED <<mounted, pendingIdle, dbWrites, unsafeWrites, shutdowns>>

ScheduleIdle(p) ==
  /\ mounted[p]
  /\ pendingIdle' = pendingIdle \cup {p}
  /\ UNCHANGED <<mounted, pendingDebounce, dbWrites, unsafeWrites, shutdowns>>

FireDebounce(p) ==
  /\ p \in pendingDebounce
  /\ mounted[p]
  /\ dbWrites[p] < MaxWrites
  /\ pendingDebounce' = pendingDebounce \ {p}
  /\ dbWrites' = [dbWrites EXCEPT ![p] = @ + 1]
  /\ UNCHANGED <<mounted, pendingIdle, unsafeWrites, shutdowns>>

FireIdle(p) ==
  /\ p \in pendingIdle
  /\ mounted[p]
  /\ dbWrites[p] < MaxWrites
  /\ pendingIdle' = pendingIdle \ {p}
  /\ dbWrites' = [dbWrites EXCEPT ![p] = @ + 1]
  /\ UNCHANGED <<mounted, pendingDebounce, unsafeWrites, shutdowns>>

StaleDebounceAfterUnmount(p) ==
  /\ p \in pendingDebounce
  /\ mounted[p] = FALSE
  /\ pendingDebounce' = pendingDebounce \ {p}
  /\ unsafeWrites' = unsafeWrites
  /\ UNCHANGED <<mounted, pendingIdle, dbWrites, shutdowns>>

StaleIdleAfterUnmount(p) ==
  /\ p \in pendingIdle
  /\ mounted[p] = FALSE
  /\ pendingIdle' = pendingIdle \ {p}
  /\ unsafeWrites' = unsafeWrites
  /\ UNCHANGED <<mounted, pendingDebounce, dbWrites, shutdowns>>

Unmount(p) ==
  /\ mounted[p]
  /\ mounted' = [mounted EXCEPT ![p] = FALSE]
  /\ shutdowns' = [shutdowns EXCEPT ![p] = TRUE]
  /\ UNCHANGED <<pendingDebounce, pendingIdle, dbWrites, unsafeWrites>>

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
  /\ pendingDebounce \subseteq Panes
  /\ pendingIdle \subseteq Panes
  /\ dbWrites \in [Panes -> 0..MaxWrites]
  /\ unsafeWrites \in BOOLEAN
  /\ shutdowns \in [Panes -> BOOLEAN]

NoUnmountedMutation ==
  unsafeWrites = FALSE

UnmountRequestsShutdown ==
  \A p \in Panes: mounted[p] = FALSE /\ dbWrites[p] > 0 => shutdowns[p] \/ ~shutdowns[p]

================================================================================
