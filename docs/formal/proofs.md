# The Proofs — TLAPS and Rocq

The invariants and liveness properties in [models.md](models.md) are not only model-checked on small
instances (by TLC); they are **machine-proved**, parametrically, two independent ways: with **TLAPS**
(the TLA+ Proof System) and with **Rocq** (constructive type theory). This document describes how
those proofs are structured. The background — inductive invariants, the WF1 leads-to rule, the
Curry–Howard view of Rocq — is in [theory.md](theory.md).

## TLAPS proofs

There are **eight** proof modules under `formal/tla/proofs/`:

| Module | Proves |
|--------|--------|
| `LspConnectionProofs.tla` | Two helper lemmas (flag/connected consistency; shutdown clears unrelated pending). |
| `LspConnectionInductiveProofs.tla` | `LspInv` is established and inductive (the large one — one lemma per action). |
| `LspConnectionLivenessProofs.tla` | The four LspConnection liveness leads-to theorems. |
| `BrowserAsyncProofs.tla` | `BrowserInv` established and inductive. |
| `BrowserAsyncLivenessProofs.tla` | Debounce/idle eventually drain. |
| `DocSyncProofs.tla` | `DocSyncInv` established and inductive (incl. no-echo). |
| `LightningBugAsyncProofs.tla` | `PublicTraceInv` established and inductive. |
| `LightningBugAsyncLivenessProofs.tla` | The three public-trace liveness theorems. |

Obligations are discharged by the back-end solvers — chiefly **Z3** (invoked as `Z3T(30)`, i.e. Z3
with a 30-second budget) and SMT, with **PTL** (propositional temporal logic) for the temporal glue.

### The safety skeleton

Every family's safety proof has the same inductive shape (see
[theory.md](theory.md#invariants-and-inductive-invariants)). For an invariant `$`I`$` with actions
`$`A_1, \dots, A_k`$`:

```math
\underbrace{\mathit{Init} \Rightarrow I}_{\text{InitEstablishes}I}
\qquad
\underbrace{\forall\, j:\; I \wedge A_j \Rightarrow I'}_{\text{one }A_j\text{Preserves}I\text{ lemma each}}
\qquad
\underbrace{I \wedge [\mathit{Next}]_{vars} \Rightarrow I'}_{I\text{PreservedByNext (case split + Stutter)}}
```

Concretely, `LspConnectionInductiveProofs` proves `InitEstablishesLspInv` (the base case, per
conjunct), one `…PreservesLspInv` theorem for each of the 12 actions plus `StutterPreservesLspInv`,
and finally `LspInvPreservedByNext`, which case-splits over the whole `Next` disjunction. The hardest
action, `StartShutdown`, is proved conjunct-by-conjunct with hand lemmas (`OnlyPendingKindTypeOK`,
`OnlyPendingKindMatchesSingleton`, `StartShutdownSourceFacts`). The other families follow the same
pattern (`InitEstablishesBrowserInv` + `BrowserInvPreservedByNext`; `DocSyncInvPreservedByNext` with
`UserEditDoesNotEchoToOrigin`; `PublicTraceInvPreservedByNext` with `FlushDidChangeRequiresOpen`).

### The liveness skeleton

Each `…LivenessProofs` module first proves the always-invariant from the fair specification,

```math
\mathit{FairSpec} \;\Rightarrow\; \Box \mathit{Inv},
\qquad
\mathit{FairSpec} \;\equiv\; \mathit{Init} \wedge \Box[\mathit{Next}]_{vars} \wedge \Box\mathit{Inv} \wedge \mathrm{WF}_{vars}(\langle\text{progress actions}\rangle)
```

and then proves one leads-to theorem per property using the **WF1** rule (enabled → the step closes
the goal → weak fairness keeps it enabled), lifted to `$`\Box(P \Rightarrow \Diamond Q)`$`. For
example `LspConnectionLivenessProofs` proves `FairSpecImpliesConnectingEventuallyResolves` (and the
other three) with per-action `…Enabled` lemmas and generic function-update lemmas
(`FunctionUpdateAtKey`, `FunctionUpdateOtherKey`).

> **Honest reading.** The invariant is available to the liveness reasoning as a conjunct *and*
> separately proven non-vacuous (`$`\mathit{FairSpec} \Rightarrow \Box\mathit{Inv}`$`). So the
> liveness guarantees are **conditional on the modeled weak-fairness** of idealized progress actions
> — they assert the abstract machine cannot starve those steps, not that a real browser/network
> schedules them. See [theory.md](theory.md#fairness-and-liveness).

## Rocq proofs

The five Rocq modules under `formal/rocq/Async/` are small, **parameter-free, decidable, executable**
mirrors of the same core safety facts — a second, independent (constructive) check, deliberately
total (`destruct … reflexivity/congruence`) so they compile in seconds and run in CI.

| Module | Key results | Mirrors (TLA+) |
|--------|-------------|----------------|
| `Fsm.v` | `initialized_implies_connected`, `flags_are_derived`, `initialized_flag_implies_connected_flag`, `valid_transition_complete` (boolean guard ⇔ membership in the transition list) | LspConnection `FlagConsistency` / `InitializedImpliesConnected`. **Also the alignment anchor** — its `all_states`/`all_transitions`/`connected_state` are diffed against the source and the TLA+ model. |
| `Pending.v` | `add_fresh_preserves_unique` (NoDup), `remove_request_not_pending`, `remove_absent_is_noop`, `remove_preserves_subset` | LspConnection `pending`/`pendingKind`/`PendingIdsAreIssued`. |
| `DocSync.v` | `no_origin_echo`, `origin_not_updated_by_remote_echo`, `peer_receives_remote_delta` | DocSync `NoOriginEcho` + peer convergence. |
| `Debounce.v` | `unmounted_callback_no_write`, `mounted_callback_writes_once`, `callback_mutates_only_when_mounted` | BrowserAsync `NoUnmountedMutation`. |
| `PublicApiTrace.v` | `api_inv` (`pending_change ⇒ opened`): `did_open_preserves_inv`, `edit_without_open_is_noop`, `edit_preserves_inv`, `pending_change_after_edit_requires_open`, `initialized_public_lsp_is_connected` (reuses `Fsm`) | LightningBugAsync `DidChangeRequiresDidOpen`. |

`Fsm.v` is compiled first (the others depend on it); the project file `formal/rocq/_CoqProject`
declares the logical root `LightningBug`.

## Why two provers?

TLAPS and Rocq use different logics (classical temporal logic with SMT back-ends vs. constructive
type theory) and are developed by different teams. A mistake — in a definition, a lemma, or a solver
— is unlikely to be mirrored identically in both, so agreement between them is stronger evidence than
either alone. TLAPS covers the temporal/liveness reasoning that Rocq's decidable mirrors do not
attempt; Rocq gives executable, constructive certificates of the core safety facts.

## Related reading

- [models.md](models.md) — the invariants and liveness properties these proofs establish.
- [verification-gate.md](verification-gate.md) — how the proofs are run and what the CI gate checks.
- [theory.md](theory.md) — inductive invariants, WF1, and the constructive (Curry–Howard) view.
