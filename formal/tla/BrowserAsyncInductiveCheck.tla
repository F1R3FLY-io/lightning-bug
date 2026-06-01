----------------------- MODULE BrowserAsyncInductiveCheck -----------------------
EXTENDS BrowserAsync

InductiveInit == BrowserInv
InductiveSpec == InductiveInit /\ [][Next]_vars

================================================================================
