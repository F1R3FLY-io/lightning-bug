------------------------- MODULE DocSyncInductiveCheck -------------------------
EXTENDS DocSync

InductiveInit == DocSyncInv
InductiveSpec == InductiveInit /\ [][Next]_vars

================================================================================
