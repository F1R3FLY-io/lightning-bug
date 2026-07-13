# Formal Verification

Lightning Bug ships **formal models and machine-checked proofs** of its trickiest concurrent
behaviour. This section explains *why*, *what is (and is not) guaranteed*, and *how to read* the
models under [`formal/`](../../formal/) in the repository.

> **Read the scope honestly.** These artifacts give strong, mechanized evidence about *abstract
> models* of the system, and they keep one critical abstraction (the LSP state machine) in lockstep
> with the source on every build. They are **not** a proof that the ClojureScript implementation
> refines the models. The precise boundary is stated in
> [verification-gate.md](verification-gate.md#what-is-and-is-not-guaranteed) and motivated in
> [theory.md](theory.md#the-refinement-gap). Nothing in this section should be read as a stronger
> guarantee than that.

## Why formally verify a browser editor?

Most of Lightning Bug is ordinary UI code, but a handful of behaviours are genuinely concurrent and
notoriously easy to get subtly wrong — exactly the situations where a model checker or proof pays for
itself:

- **React unmount races.** Debounced and idle-scheduled callbacks can fire *after* a component
  unmounts; they must observe the unmounted state and mutate nothing (see
  [editor-component.md](../architecture/editor-component.md#resource-lifecycle)).
- **Same-file multi-pane sync.** Two panes editing one file must converge and must not echo an edit
  back to its origin (see [multi-editor-workspaces.md](../architecture/multi-editor-workspaces.md#same-file-reactive-synchronization)).
- **The LSP connection lifecycle.** A seven-state machine with shutdown, error, and reconnect paths,
  where responses must only be accepted for outstanding requests and graceful shutdown must not
  trigger reconnect (see [lsp-subsystem.md](../architecture/lsp-subsystem.md#the-connection-finite-state-machine)).
- **The public document-lifecycle trace.** `didChange` must never precede `didOpen`; diagnostics
  must be current; `didClose` must not fire while a file is still shared.

Each of these is captured by a formal model.

![Model-to-source map: BrowserAsync abstracts React unmount + debounce + idle sync; DocSync abstracts the same-file reactive sync; LightningBugAsync abstracts the public-API document lifecycle; LspConnection abstracts the LSP FSM and WebSocket. Only LspConnection's state/transition shape is mechanically tied to source.](diagrams/model-source-map.svg)

## Two formalisms, on purpose

The project uses **two independent formal tools**, so an error in one is unlikely to be mirrored in
the other:

- **TLA+** (Temporal Logic of Actions; Leslie Lamport) — a specification language for concurrent
  systems. Models are checked two ways:
  - **TLC**, a *model checker*, exhaustively explores all reachable states of a **bounded** instance
    (tiny constants: 2 panes, 2 URIs, 2 languages) and reports any invariant violation or missing
    liveness.
  - **TLAPS**, the *TLA+ Proof System*, mechanically checks **parametric** proofs (for all
    constants satisfying the stated assumptions), discharging obligations with the back-end solvers
    **Z3**, **Zenon**, and **Isabelle**.
- **Rocq** (formerly Coq) — a proof assistant based on constructive type theory. Five small,
  decidable, *executable* Rocq modules re-prove the core safety facts as a second, independent check.

The distinction between **model checking** (exhaustive search of a finite state space) and
**theorem proving** (a deductive proof for all instances) matters and is explained in
[theory.md](theory.md).

## What is in `formal/`

| Group | Files | Purpose |
|-------|-------|---------|
| TLA+ models | `formal/tla/{BrowserAsync,DocSync,LightningBugAsync,LspConnection}.tla` (+ `.cfg`) | The four base models; each also has an `…InductiveCheck.tla` and (except DocSync) a `…Liveness.tla`. |
| TLAPS proofs | `formal/tla/proofs/*.tla` (8 modules) | Machine-checked safety and liveness proofs. |
| Rocq proofs | `formal/rocq/Async/{Fsm,Pending,DocSync,Debounce,PublicApiTrace}.v` | Constructive mirrors of the core safety facts. |
| Verification tooling | `scripts/verify-{tla,tlaps,rocq,formal-alignment}.js` | The runners and the source↔formal alignment gate. |

The reader-facing docs for these are:

| Doc | Contents |
|-----|----------|
| [theory.md](theory.md) | A self-contained primer: safety vs liveness, the temporal operators, inductive invariants, fairness, and the refinement gap. |
| [models.md](models.md) | Each of the four TLA+ families: state, safety invariants, liveness, and what the inductive-check variants add. |
| [proofs.md](proofs.md) | The structure of the TLAPS proofs and the Rocq mirrors. |
| [verification-gate.md](verification-gate.md) | The `verify-*` runners, the alignment gate, the CI wiring, and the honest scope statement. |

## How the checks run

![Verification stack: TLA+ models feed TLC and TLAPS; Rocq feeds rocq compile; the FSM source and the TLA+/Rocq FSM definitions feed the alignment script. The CI gate runs alignment + Rocq and blocks release; the full local gate additionally runs TLC and TLAPS. CI deliberately excludes TLC/TLAPS, which the alignment gate self-guards.](diagrams/verification-stack.svg)

Two tiers, wired in `package.json`:

- **`npm run verify:formal:ci`** = alignment + Rocq. This runs in Continuous Integration (CI); the
  `formal-verification` job gates `release`. It is fast and needs only Node + Rocq.
- **`npm run verify:formal`** = alignment + TLC + TLAPS + Rocq. The full, developer-run gate; it
  needs the heavier TLA+ toolchain installed locally.

The details, including why CI deliberately excludes TLC/TLAPS, are in
[verification-gate.md](verification-gate.md).

## Suggested reading order

1. [theory.md](theory.md) — the concepts (skip if you know TLA+).
2. [models.md](models.md) — what each model says.
3. [proofs.md](proofs.md) — how the proofs are structured.
4. [verification-gate.md](verification-gate.md) — how it is kept honest and wired into CI.
