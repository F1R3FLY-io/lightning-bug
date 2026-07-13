# Theory Primer — Temporal Logic, Invariants, and the Refinement Gap

This primer is self-contained: it defines the concepts the models and proofs rely on, so a reader
without a formal-methods background can follow [models.md](models.md) and [proofs.md](proofs.md). It
also states precisely the one theoretical point on which the project is deliberately honest — the
**refinement gap** between a model and the code.

Throughout, mathematics is written in MathJax. Symbols are defined before use.

## Systems as behaviours

**TLA+** (the Temporal Logic of Actions) describes a system by the set of **behaviours** it can
exhibit. A *behaviour* is an infinite sequence of **states**,

```math
\sigma \;=\; s_0 \rightarrow s_1 \rightarrow s_2 \rightarrow \cdots
```

where each state `$`s_i`$` assigns values to the system's variables. A **specification** is a
temporal formula that is true of exactly the allowed behaviours. The canonical shape is

```math
\mathit{Spec} \;\equiv\; \mathit{Init} \,\wedge\, \Box[\mathit{Next}]_{\mathit{vars}}
```

read as: the first state satisfies the initial predicate `$`\mathit{Init}`$`, and *every* step
either satisfies the next-state relation `$`\mathit{Next}`$` or leaves `$`\mathit{vars}`$` unchanged
(a **stuttering** step). The subscript `$`[\,\cdot\,]_{\mathit{vars}}`$` is what permits stuttering,
and stuttering-invariance is what lets a coarse model correspond to a finely-stepped implementation.

`$`\mathit{Next}`$` is written as a disjunction of **actions** — guarded state transitions. For
example, the LSP model's next-state relation is `$`\mathit{StartConnect} \vee \mathit{Open} \vee
\mathit{InitializeResponse} \vee \cdots`$`, one disjunct per edge of the connection state machine.

## Safety vs. liveness

Two kinds of property partition what one usually wants to prove (Alpern & Schneider, 1985):

- A **safety** property says *nothing bad ever happens*: it is violated, if at all, by a finite
  prefix of a behaviour. "The connection is never `initialized` while the socket is closed" is
  safety.
- A **liveness** property says *something good eventually happens*: it can only be violated by an
  infinite behaviour. "A connecting socket eventually resolves" is liveness.

The most important safety properties are **invariants** — state predicates `$`I`$` that hold in
every reachable state:

```math
\mathit{Spec} \;\Rightarrow\; \Box I
```

where `$`\Box`$` ("box") is the temporal operator **always**: `$`\Box I`$` is true of a behaviour iff
`$`I`$` holds in every state of it.

Liveness uses two more operators. `$`\Diamond`$` ("diamond") is **eventually**: `$`\Diamond I`$` holds
iff `$`I`$` is true in *some* state. **Leads-to**, written `$`P \leadsto Q`$`, abbreviates
`$`\Box(P \Rightarrow \Diamond Q)`$` — "every state satisfying `$`P`$` is eventually followed by one
satisfying `$`Q`$`." All of the project's liveness theorems have this leads-to shape, e.g.
`$`\mathit{connecting} \leadsto (\mathit{initialized} \vee \mathit{disconnected})`$`.

## Invariants and *inductive* invariants

To prove `$`\mathit{Spec} \Rightarrow \Box I`$` deductively (rather than by exploring states) one
shows `$`I`$` is **inductive**:

```math
\text{(base)}\quad \mathit{Init} \Rightarrow I
\qquad\qquad
\text{(step)}\quad I \,\wedge\, [\mathit{Next}]_{\mathit{vars}} \Rightarrow I'
```

Here `$`I'`$` is `$`I`$` with every variable primed (its value in the *next* state). The base case
says the invariant holds initially; the step case says every action preserves it. Together they give
`$`\Box I`$` by induction over the length of the behaviour.

Not every invariant is inductive on its own: an `$`I`$` may be true in all reachable states yet not
imply `$`I'`$` after an arbitrary step from an `$`I`$`-state, because that step may start from an
*unreachable* `$`I`$`-state. The fix is to strengthen `$`I`$` — conjoin the auxiliary facts that make
it self-supporting — until the step case goes through. The project's invariants (e.g. `LspInv`,
`PublicTraceInv`) are exactly such **strengthened, inductive** conjunctions.

### Counterexample to induction (CTI)

A cheap mechanical way to *test* inductiveness is a **counterexample-to-induction** search: start
from **every** state satisfying `$`I`$` (not just the initial ones) and check that one `$`Next`$`
step preserves `$`I`$`. If some `$`I`$`-state has a successor violating `$`I`$`, that pair is a CTI and
`$`I`$` is not inductive. This is exactly what the `…InductiveCheck` TLA+ modules do with the model
checker (see [models.md](models.md#the-inductivecheck-variants)): their specification starts from
`$`I`$` itself,

```math
\mathit{InductiveSpec} \;\equiv\; I \,\wedge\, \Box[\mathit{Next}]_{\mathit{vars}}
```

and TLC checks `$`\Box I`$`. Passing means no single step breaks `$`I`$` — mechanical confirmation of
the step case that TLAPS then proves parametrically.

## Model checking vs. theorem proving

The project uses both, and they are complementary:

| | **Model checking** (TLC) | **Theorem proving** (TLAPS, Rocq) |
|--|--------------------------|-----------------------------------|
| Method | Exhaustively explore reachable states | Deduce a proof from axioms/rules |
| Scope | A **bounded** instance (fixed small constants) | **Parametric** — all instances meeting the assumptions |
| Strength | Finds concrete counterexamples fast | Covers unbounded/infinite state spaces |
| Weakness | Only the instance checked (state explosion) | Labour; needs the invariant made inductive |

TLC gives fast, concrete feedback on a 2-pane/2-URI instance; TLAPS then lifts the same invariants to
*all* constants satisfying the model's `ASSUME`s. Getting the same result two ways raises confidence.
(Foundational references: model checking — Clarke, Emerson & Sistla, 1986; temporal logic of
programs — Pnueli, 1977.)

## Fairness and liveness

Liveness is impossible without ruling out behaviours that simply *refuse to make progress*. That is
what **fairness** assumptions do. For an action `$`A`$`:

- **Weak fairness** `$`\mathrm{WF}_{\mathit{vars}}(A)`$`: if `$`A`$` becomes *and stays*
  continuously enabled, it eventually occurs. (No starving a persistently-ready step.)
- **Strong fairness** `$`\mathrm{SF}_{\mathit{vars}}(A)`$`: if `$`A`$` is enabled *infinitely often*
  (even if not continuously), it eventually occurs.

The project's liveness models add **weak fairness** on the idealized *progress* actions — draining a
debounce queue, completing a connect, flushing a pending change — and then prove leads-to properties.
The proofs use the standard **WF1** rule (Owicki & Lamport, 1982), which discharges `$`P \leadsto Q`$`
from three obligations: (i) a `$`P`$`-step either reaches `$`Q`$` or stays in `$`P`$`; (ii) the
progress action `$`A`$` takes `$`P`$` to `$`Q`$`; and (iii) `$`P`$` keeps `$`A`$` enabled.

The honest reading: these liveness guarantees are **conditional on the modeled weak-fairness of
idealized progress actions**. They assert the *abstract* machine cannot starve those steps — not that
a real browser event loop or a real network schedules them.

## Constructive proofs (the Rocq mirrors)

The Rocq (formerly Coq) modules re-prove the core safety facts in a different foundation:
**constructive type theory**. By the **Curry–Howard correspondence** — *propositions are types, and a
proof is a program inhabiting that type* — a Rocq proof of, say, "a removed request is no longer
pending" is a total function whose existence *is* the proof, and which the type-checker verifies. The
project keeps these mirrors small, decidable, and *executable* (`destruct … reflexivity`), so they
compile in seconds and run in CI. They are an independent second opinion on the same facts the TLA+
side proves. (References: Howard, 1980; the Coq'Art book, Bertot & Castéran, 2004.)

## The refinement gap

This is the crux. A model is an **abstraction** of the code; a proof about the model is only as
relevant to the code as the abstraction is faithful. The gold-standard link is a **refinement**:
one shows the implementation `$`\mathit{Impl}`$` *implements* the specification,

```math
\mathit{Impl} \;\Rightarrow\; \mathit{Spec}
```

meaning every behaviour of the implementation, after hiding internal steps, is an allowed behaviour
of the spec (trace inclusion, closed under stuttering). Establishing this in general requires a
**refinement mapping** from implementation states to specification states — and, by Abadi & Lamport
(1991), such a mapping exists under precise conditions (possibly after adding history/prophecy
variables).

Lightning Bug **does not** claim `$`\mathit{Impl} \Rightarrow \mathit{Spec}`$`. The models' actions are
hand-written abstractions of ClojureScript that also elides real CodeMirror internals, real network
timing, DataScript query semantics, and concurrent text merging. Constructing and proving a
refinement mapping from the running code to each model would be a research effort disproportionate to
the payoff for an editor.

Instead the project uses a weaker but **automatable** substitute — a **source↔formal alignment
gate**:

1. For the one model whose faithfulness matters most (the LSP FSM), the gate *extracts* the state
   set, transition relation, and connected-state projection from **all three** of `fsm.cljs`,
   `LspConnection.tla`, and `Async/Fsm.v`, and requires them **identical** on every build. This makes
   the FSM's *shape* provably synchronized with the code (not merely once, but continuously).
2. For behaviours the state machine cannot capture (e.g. "`didClose` is suppressed while a URI is
   shared"), the gate asserts, by targeted source checks, that the code contains the guarding logic
   the model assumes.

This is a *keep-the-model-honest* mechanism, not a refinement proof. It cannot prove the code refines
the spec; it can, and does, prevent the code and the spec from silently drifting apart. The full
mechanism and its exact guarantees are in
[verification-gate.md](verification-gate.md#what-is-and-is-not-guaranteed).

## References

- B. Alpern, F. B. Schneider (1985). "Defining liveness." *Information Processing Letters* 21(4).
  [doi:10.1016/0020-0190(85)90056-0](https://doi.org/10.1016/0020-0190(85)90056-0)
- A. Pnueli (1977). "The temporal logic of programs." *18th FOCS*.
  [doi:10.1109/SFCS.1977.32](https://doi.org/10.1109/SFCS.1977.32)
- L. Lamport (1994). "The temporal logic of actions." *ACM TOPLAS* 16(3).
  [doi:10.1145/177492.177726](https://doi.org/10.1145/177492.177726)
- L. Lamport (2002). *Specifying Systems: The TLA+ Language and Tools for Hardware and Software
  Engineers.* Addison-Wesley.
  [Free PDF](https://lamport.azurewebsites.net/tla/book.html)
- S. Owicki, L. Lamport (1982). "Proving liveness properties of concurrent programs." *ACM TOPLAS*
  4(3). [doi:10.1145/357172.357178](https://doi.org/10.1145/357172.357178)
- E. M. Clarke, E. A. Emerson, A. P. Sistla (1986). "Automatic verification of finite-state
  concurrent systems using temporal logic specifications." *ACM TOPLAS* 8(2).
  [doi:10.1145/5397.5399](https://doi.org/10.1145/5397.5399)
- M. Abadi, L. Lamport (1991). "The existence of refinement mappings." *Theoretical Computer Science*
  82(2). [doi:10.1016/0304-3975(91)90224-P](https://doi.org/10.1016/0304-3975(91)90224-P)
- Z. Manna, A. Pnueli (1992). *The Temporal Logic of Reactive and Concurrent Systems: Specification.*
  Springer. [doi:10.1007/978-1-4612-0931-7](https://doi.org/10.1007/978-1-4612-0931-7)
- Y. Bertot, P. Castéran (2004). *Interactive Theorem Proving and Program Development (Coq'Art).*
  Springer. [doi:10.1007/978-3-662-07964-5](https://doi.org/10.1007/978-3-662-07964-5)

## Related reading

- [models.md](models.md) — the four models, with their invariants written in this notation.
- [proofs.md](proofs.md) — how the TLAPS and Rocq proofs are structured.
- [verification-gate.md](verification-gate.md) — the alignment gate and the precise scope statement.
