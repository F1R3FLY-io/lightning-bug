-------------------- MODULE LightningBugAsyncInductiveCheck --------------------
EXTENDS LightningBugAsync

InductiveInit == PublicTraceInv
InductiveSpec == InductiveInit /\ [][Next]_vars

================================================================================
