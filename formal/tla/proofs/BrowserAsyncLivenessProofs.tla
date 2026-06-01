------------------------ MODULE BrowserAsyncLivenessProofs ------------------------
EXTENDS BrowserAsyncLiveness, BrowserAsyncProofs, TLAPS

THEOREM FairSpecImpliesAlwaysBrowserInv ==
  FairSpec => []BrowserInv
<1>1. Init => BrowserInv
  BY InitEstablishesBrowserInv
<1>2. BrowserInv /\ [Next]_vars => BrowserInv'
  BY BrowserInvPreservedByNext
<1> QED
  BY <1>1, <1>2, PTL DEF FairSpec

THEOREM RemovePendingChangesSet ==
  ASSUME NEW S,
         NEW x \in S
  PROVE S \ {x} # S
BY Z3T(30)

THEOREM DebounceDrainEnabled ==
  ASSUME BrowserInv,
         NEW p \in Panes,
         p \in pendingDebounce
  PROVE ENABLED <<DrainDebounce(p)>>_vars
<1>1. pendingDebounce \ {p} # pendingDebounce
  BY RemovePendingChangesSet
<1> QED
  BY <1>1, ExpandENABLED, AutoUSE DEF DrainDebounce, vars

THEOREM DebounceDrainStepClearsPending ==
  ASSUME NEW p \in Panes,
         p \in pendingDebounce,
         <<DrainDebounce(p)>>_vars
  PROVE p \notin pendingDebounce'
BY DEF DrainDebounce

THEOREM DebounceDrainLeadsToClear ==
  ASSUME NEW p \in Panes
  PROVE FairSpec => (p \in pendingDebounce ~> p \notin pendingDebounce)
<1> DEFINE P == p \in pendingDebounce
<1> DEFINE Q == p \notin pendingDebounce
<1> DEFINE BSpec == /\ []BrowserInv
                     /\ [][Next]_vars
                     /\ WF_vars(DrainDebounce(p))
<1>1. BrowserInv /\ P /\ [Next]_vars => P' \/ Q'
  BY DEF P, Q
<1>2. BrowserInv /\ P /\ <<Next /\ DrainDebounce(p)>>_vars => Q'
  BY DebounceDrainStepClearsPending DEF P, Q
<1>3. BrowserInv /\ P => ENABLED <<DrainDebounce(p)>>_vars
  BY DebounceDrainEnabled DEF P
<1>4. BSpec => (P ~> Q)
  BY <1>1, <1>2, <1>3, PTL DEF BSpec
<1>5. FairSpec => BSpec
  BY DEF FairSpec, BSpec
<1> QED
  BY <1>4, <1>5, PTL DEF P, Q

THEOREM IdleDrainEnabled ==
  ASSUME BrowserInv,
         NEW p \in Panes,
         p \in pendingIdle
  PROVE ENABLED <<DrainIdle(p)>>_vars
<1>1. pendingIdle \ {p} # pendingIdle
  BY RemovePendingChangesSet
<1> QED
  BY <1>1, ExpandENABLED, AutoUSE DEF DrainIdle, vars

THEOREM IdleDrainStepClearsPending ==
  ASSUME NEW p \in Panes,
         p \in pendingIdle,
         <<DrainIdle(p)>>_vars
  PROVE p \notin pendingIdle'
BY DEF DrainIdle

THEOREM IdleDrainLeadsToClear ==
  ASSUME NEW p \in Panes
  PROVE FairSpec => (p \in pendingIdle ~> p \notin pendingIdle)
<1> DEFINE P == p \in pendingIdle
<1> DEFINE Q == p \notin pendingIdle
<1> DEFINE BSpec == /\ []BrowserInv
                     /\ [][Next]_vars
                     /\ WF_vars(DrainIdle(p))
<1>1. BrowserInv /\ P /\ [Next]_vars => P' \/ Q'
  BY DEF P, Q
<1>2. BrowserInv /\ P /\ <<Next /\ DrainIdle(p)>>_vars => Q'
  BY IdleDrainStepClearsPending DEF P, Q
<1>3. BrowserInv /\ P => ENABLED <<DrainIdle(p)>>_vars
  BY IdleDrainEnabled DEF P
<1>4. BSpec => (P ~> Q)
  BY <1>1, <1>2, <1>3, PTL DEF BSpec
<1>5. FairSpec => BSpec
  BY DEF FairSpec, BSpec
<1> QED
  BY <1>4, <1>5, PTL DEF P, Q

THEOREM FairSpecImpliesDebounceEventuallyDrains ==
  FairSpec => DebounceEventuallyDrains
<1>1. ASSUME NEW p \in Panes
      PROVE FairSpec => [](p \in pendingDebounce => <>(p \notin pendingDebounce))
  BY DebounceDrainLeadsToClear, PTL
<1> QED
  BY <1>1 DEF DebounceEventuallyDrains

THEOREM FairSpecImpliesIdleEventuallyDrains ==
  FairSpec => IdleEventuallyDrains
<1>1. ASSUME NEW p \in Panes
      PROVE FairSpec => [](p \in pendingIdle => <>(p \notin pendingIdle))
  BY IdleDrainLeadsToClear, PTL
<1> QED
  BY <1>1 DEF IdleEventuallyDrains

================================================================================
