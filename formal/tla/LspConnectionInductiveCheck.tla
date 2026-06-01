------------------------- MODULE LspConnectionInductiveCheck -------------------------
EXTENDS LspConnection

InductiveInit == LspInv
InductiveSpec == InductiveInit /\ [][Next]_vars

================================================================================
