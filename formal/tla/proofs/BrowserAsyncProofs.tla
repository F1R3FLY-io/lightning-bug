---------------------------- MODULE BrowserAsyncProofs ----------------------------
EXTENDS BrowserAsync, TLAPS

THEOREM StaleDebounceDoesNotWrite ==
  \A p \in Panes:
    StaleDebounceAfterUnmount(p) => dbWrites' = dbWrites /\ writeHistory' = writeHistory
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

================================================================================
