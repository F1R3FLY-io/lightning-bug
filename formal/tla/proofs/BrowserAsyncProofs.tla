---------------------------- MODULE BrowserAsyncProofs ----------------------------
EXTENDS BrowserAsync, TLAPS

THEOREM InitEstablishesBrowserInv ==
  ASSUME Init
  PROVE BrowserInv
BY PanesAssumption, Z3T(30)
   DEF Init, BrowserInv, TypeOK, NoUnmountedMutation, UnmountRequestsShutdown, WriteRecords

THEOREM StaleDebounceDoesNotRecordWrite ==
  \A p \in Panes:
    StaleDebounceAfterUnmount(p) => writeHistory' = writeHistory
  BY DEF StaleDebounceAfterUnmount

THEOREM FireDebounceWritesOnlyWhenMounted ==
  \A p \in Panes:
    FireDebounce(p) =>
      \A w \in writeHistory' \ writeHistory: w.wasMounted = TRUE
  BY DEF FireDebounce

THEOREM FireIdleWritesOnlyWhenMounted ==
  \A p \in Panes:
    FireIdle(p) =>
      \A w \in writeHistory' \ writeHistory: w.wasMounted = TRUE
  BY DEF FireIdle

THEOREM MountPreservesBrowserInv ==
  ASSUME BrowserInv,
         NEW p \in Panes,
         Mount(p)
  PROVE BrowserInv'
BY PanesAssumption, Z3T(30)
   DEF BrowserInv, Mount, TypeOK, NoUnmountedMutation, UnmountRequestsShutdown, WriteRecords

THEOREM ScheduleDebouncePreservesBrowserInv ==
  ASSUME BrowserInv,
         NEW p \in Panes,
         ScheduleDebounce(p)
  PROVE BrowserInv'
BY PanesAssumption, Z3T(30)
   DEF BrowserInv, ScheduleDebounce, TypeOK, NoUnmountedMutation,
       UnmountRequestsShutdown, WriteRecords

THEOREM ScheduleIdlePreservesBrowserInv ==
  ASSUME BrowserInv,
         NEW p \in Panes,
         ScheduleIdle(p)
  PROVE BrowserInv'
BY PanesAssumption, Z3T(30)
   DEF BrowserInv, ScheduleIdle, TypeOK, NoUnmountedMutation,
       UnmountRequestsShutdown, WriteRecords

THEOREM FireDebouncePreservesBrowserInv ==
  ASSUME BrowserInv,
         NEW p \in Panes,
         FireDebounce(p)
  PROVE BrowserInv'
BY PanesAssumption, Z3T(30)
   DEF BrowserInv, FireDebounce, TypeOK, NoUnmountedMutation,
       UnmountRequestsShutdown, WriteRecords

THEOREM FireIdlePreservesBrowserInv ==
  ASSUME BrowserInv,
         NEW p \in Panes,
         FireIdle(p)
  PROVE BrowserInv'
BY PanesAssumption, Z3T(30)
   DEF BrowserInv, FireIdle, TypeOK, NoUnmountedMutation,
       UnmountRequestsShutdown, WriteRecords

THEOREM StaleDebouncePreservesBrowserInv ==
  ASSUME BrowserInv,
         NEW p \in Panes,
         StaleDebounceAfterUnmount(p)
  PROVE BrowserInv'
BY PanesAssumption, Z3T(30)
   DEF BrowserInv, StaleDebounceAfterUnmount, TypeOK, NoUnmountedMutation,
       UnmountRequestsShutdown, WriteRecords

THEOREM StaleIdlePreservesBrowserInv ==
  ASSUME BrowserInv,
         NEW p \in Panes,
         StaleIdleAfterUnmount(p)
  PROVE BrowserInv'
BY PanesAssumption, Z3T(30)
   DEF BrowserInv, StaleIdleAfterUnmount, TypeOK, NoUnmountedMutation,
       UnmountRequestsShutdown, WriteRecords

THEOREM UnmountPreservesBrowserInv ==
  ASSUME BrowserInv,
         NEW p \in Panes,
         Unmount(p)
  PROVE BrowserInv'
BY PanesAssumption, Z3T(30)
   DEF BrowserInv, Unmount, TypeOK, NoUnmountedMutation, UnmountRequestsShutdown, WriteRecords

THEOREM StutterPreservesBrowserInv ==
  BrowserInv /\ UNCHANGED vars => BrowserInv'
BY PanesAssumption, Z3T(30)
   DEF BrowserInv, vars, TypeOK, NoUnmountedMutation, UnmountRequestsShutdown, WriteRecords

THEOREM BrowserInvPreservedByNext ==
  BrowserInv /\ [Next]_vars => BrowserInv'
<1>1. ASSUME BrowserInv, [Next]_vars PROVE BrowserInv'
  <2>1. ASSUME NEW p \in Panes, Mount(p) PROVE BrowserInv'
    BY <1>1, <2>1, MountPreservesBrowserInv
  <2>2. ASSUME NEW p \in Panes, ScheduleDebounce(p) PROVE BrowserInv'
    BY <1>1, <2>2, ScheduleDebouncePreservesBrowserInv
  <2>3. ASSUME NEW p \in Panes, ScheduleIdle(p) PROVE BrowserInv'
    BY <1>1, <2>3, ScheduleIdlePreservesBrowserInv
  <2>4. ASSUME NEW p \in Panes, FireDebounce(p) PROVE BrowserInv'
    BY <1>1, <2>4, FireDebouncePreservesBrowserInv
  <2>5. ASSUME NEW p \in Panes, FireIdle(p) PROVE BrowserInv'
    BY <1>1, <2>5, FireIdlePreservesBrowserInv
  <2>6. ASSUME NEW p \in Panes, StaleDebounceAfterUnmount(p) PROVE BrowserInv'
    BY <1>1, <2>6, StaleDebouncePreservesBrowserInv
  <2>7. ASSUME NEW p \in Panes, StaleIdleAfterUnmount(p) PROVE BrowserInv'
    BY <1>1, <2>7, StaleIdlePreservesBrowserInv
  <2>8. ASSUME NEW p \in Panes, Unmount(p) PROVE BrowserInv'
    BY <1>1, <2>8, UnmountPreservesBrowserInv
  <2>9. ASSUME UNCHANGED vars PROVE BrowserInv'
    BY <1>1, <2>9, StutterPreservesBrowserInv
  <2> QED
    BY <1>1, <2>1, <2>2, <2>3, <2>4, <2>5, <2>6, <2>7, <2>8,
       <2>9 DEF Next, vars
<1> QED
  BY <1>1

================================================================================
