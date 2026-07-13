# The Formal Models

Four TLA+ model families capture the concurrent behaviours of Lightning Bug. Each family has a **base
model** (state, actions, a conjunctive safety invariant), an **`…InductiveCheck`** variant (a
counterexample-to-induction check), and — except DocSync — a **`…Liveness`** variant (fairness +
temporal properties). This document says what each model states. The notation (`$`\Box`$`,
`$`\Diamond`$`, `$`\leadsto`$`, inductive invariants, fairness) is defined in [theory.md](theory.md).

All base models share the shape `$`\mathit{Spec} \equiv \mathit{Init} \wedge \Box[\mathit{Next}]_{vars}`$`,
carry a `TypeOK` type-correctness invariant, and are checked by **TLC** on **bounded** instances with
tiny constants — so the TLC result is "no violation in this small instance," while TLAPS
(see [proofs.md](proofs.md)) lifts the invariants to all instances.

---

## BrowserAsync

**Abstracts:** the editor's debounced events, idle DataScript sync, and React unmount cleanup
(`lib.core` + `lib.editor.runtime`; see
[editor-component.md](../architecture/editor-component.md#resource-lifecycle) and
[syntax-and-editing.md](../architecture/syntax-and-editing.md#the-keystroke-hot-path)). Stale
callbacks are *allowed* to fire after unmount, but they must observe the unmounted state and mutate
nothing. Constants: `$`\mathit{Panes} = \{\text{pane-a}, \text{pane-b}\}`$`.

**State (per pane):** `mounted`, `pendingDebounce`, `pendingIdle`, `everMounted`, a `writeHistory`
set of `$`\langle \text{pane}, \text{wasMounted} \rangle`$` records, and `shutdowns`.

**Actions:** `Mount`, `ScheduleDebounce`, `ScheduleIdle`, `FireDebounce`/`FireIdle` (only when
`mounted`, appending a `wasMounted = TRUE` write), `StaleDebounceAfterUnmount`/`StaleIdleAfterUnmount`
(fire after unmount but write **nothing**), and `Unmount`.

**Safety — `BrowserInv`:** `TypeOK` conjoined with

- **`NoUnmountedMutation`** — every recorded write has `$`\text{wasMounted} = \mathrm{TRUE}`$` (no
  write ever occurs after unmount):

  ```math
  \forall\, w \in \mathit{writeHistory} : w.\mathit{wasMounted} = \mathrm{TRUE}
  ```

- **`UnmountRequestsShutdown`** — a pane that was mounted and now is not has requested shutdown:
  `$`\mathit{everMounted}[p] \wedge \neg\mathit{mounted}[p] \Rightarrow \mathit{shutdowns}[p]`$`.

**Liveness (`BrowserAsyncLiveness`):** under weak fairness on `DrainDebounce`/`DrainIdle`, it proves
`DebounceEventuallyDrains` and `IdleEventuallyDrains` — every pending callback eventually leaves the
pending set (`$`\mathit{pendingDebounce}[p] \leadsto \neg\mathit{pendingDebounce}[p]`$`).

---

## DocSync

**Abstracts:** the same-file multi-pane reactive sync (`lib.workspace.doc-sync`; see
[multi-editor-workspaces.md](../architecture/multi-editor-workspaces.md#same-file-reactive-synchronization)).
Constants: 2 panes, 2 URIs, `$`\mathit{MaxText} = 3`$`.

**State:** `subscribers` (URI → set of panes), `workspaceText` (URI → counter), `paneText`
(pane → URI → counter), `seq` (URI → edit counter), and a `deliveries` set of
`$`\langle \text{uri}, \text{origin}, \text{pane}, n \rangle`$` fan-out records.

**Actions:** `Subscribe` (seed a pane from the workspace counter), `Unsubscribe`, and `UserEdit`
(increment the origin and each subscribed peer once, fanning deliveries to `subscribers \ {origin}`).

**Safety — `DocSyncInv`:** `TypeOK` conjoined with

- **`SubscribedPanesConverge`** — every subscribed pane's text equals the workspace text:

  ```math
  \forall\, u,\, p \in \mathit{subscribers}[u] : \mathit{paneText}[p][u] = \mathit{workspaceText}[u]
  ```

- **`NoOriginEcho`** — no delivery targets its own origin:
  `$`\forall\, d \in \mathit{deliveries} : d.\mathit{origin} \neq d.\mathit{pane}`$`.
- **`SeqMatchesEdits`** — `$`\mathit{seq}[u] = \mathit{workspaceText}[u]`$`.
- **`DeliverySeqPrecedesWorkspace`** — `$`\forall\, d : d.n < \mathit{workspaceText}[d.\mathit{uri}]`$`.

**Liveness:** *none by design* — there is no `DocSyncLiveness` model. Only safety is specified.

> **Scope of DocSync (do not over-read).** Text is modelled as a **monotone counter** and edits are
> **serialized** (one action at a time). The model therefore proves *convergence*, *no-echo*, and
> *sequence ordering* — it does **not** model concurrent text *merging*, and it is not an operational
> transform or CRDT claim. This matches the implementation, which converges by synchronous ordered
> fan-out, not by conflict resolution.

---

## LightningBugAsync

**Abstracts:** the composed public-API trace — how document handle calls become
open/edit/diagnostic effects, and the ordering the browser implementation must preserve
(`lib.editor.commands` + `lib.editor.runtime`; the LSP document lifecycle). Constants: 2 panes,
2 URIs, `$`\mathit{MaxVersion} = 3`$`.

**State:** `mounted`, `active` (pane → URI | none), `docs`, `lspState`
`$`\in \{\text{disconnected}, \text{connecting}, \text{initialized}\}`$`, `opened`, `version`
(URI → n), `pendingChange`, `diagnosticVersion`, and logs `didChangeLog`, `didCloseLog`.

**Actions:** `Mount`/`Unmount`, `ConnectLsp`/`InitializeLsp`, `OpenDocument`, `EnsureDidOpen`
(idempotent, only when initialized), `Edit` (bumps version, invalidates `diagnosticVersion`, queues
`pendingChange` only if opened), `FlushDidChange`, `ReceiveDiagnostics` (accepts only a matching
version), and `CloseDocument` (suppresses `didClose` + deletion while the URI is still shared).

**Safety — `PublicTraceInv` (8 conjuncts):** `TypeOK`, `ActiveDocumentExists`, and:

- **`DidChangeRequiresDidOpen`** — a pending change implies the document is opened:
  `$`\mathit{pendingChange} \subseteq \mathit{opened}`$`.
- **`OpenedDocumentsExist`** — `$`\mathit{opened} \subseteq \mathit{docs}`$`.
- **`DiagnosticsAreCurrent`** — `$`\mathit{diagnosticVersion}[u] = 0 \,\vee\, \mathit{diagnosticVersion}[u] = \mathit{version}[u]`$`.
- **`NoWorkForClosedDocs`** — `$`\mathit{pendingChange} \subseteq \mathit{docs}`$`.
- **`DidChangeSentAfterOpen`** — every logged `didChange` had the document open.
- **`DidCloseOnlyWhenUnshared`** — every logged `didClose` was for an unshared URI.

**Liveness (`LightningBugAsyncLiveness`):** under weak fairness on `InitializeLspProgress`,
`FlushPendingChange`, `OpenVisibleDocument`, it proves `PendingChangeEventuallyFlushes`,
`ConnectingEventuallyInitializes`, and `VisibleInitializedDocumentEventuallyOpens`.

---

## LspConnection

**Abstracts:** the LSP connection FSM and the browser WebSocket events — explicitly `lib.lsp.fsm`,
`lib.lsp.client`, and `lib.lsp.connection-manager` (see
[lsp-subsystem.md](../architecture/lsp-subsystem.md)). This is the richest model and the one tied to
source by the [alignment gate](verification-gate.md). Constants:
`$`\mathit{Langs} = \{\text{rholang}, \text{text}\}`$`, `$`\mathit{MaxPendingId} = 3`$`,
`$`\mathit{MaxReconnects} = 2`$`.

**State (per language):** `state` (one of the **7 states**), the four derived flags
`connected`/`isInitialized`/`connecting`/`reachable`, `pending` (a set of ids), `pendingKind`
(id → kind, where `$`\mathit{PendingKind} \in \{\text{none}, \text{initialize}, \text{request},
\text{shutdown}\}`$`), a monotone `nextId`, `shuttingDown`, and `reconnects`.

**Actions (the 17 transitions):** `StartConnect`, `OpenSocketAndInitialize` (issues a typed
`initialize`), `InitializeResponse`, `Request`/`Response` (`Response` is disabled while
`shuttingDown` and matches only a `request`-typed pending id), `StartShutdown` (clears unrelated
pending, records exactly one `shutdown`), `ShutdownClosed`, `SocketError`, `UnexpectedClose`
(bumps the bounded `reconnects`), and `Reconnect`.

**Safety — `LspInv` (12 conjuncts):** `TypeOK`, `FlagConsistency` (the four flags always equal their
state projection), `InitializedImpliesConnected`, `PendingOnlyWhileLive`,
`GracefulShutdownDoesNotReconnect`, `ShutdownStateConsistent` (shutting down `$`\Rightarrow`$`
`state = disconnecting` and exactly one pending id), `PendingKindsMatchPending`
(`$`\mathit{id} \in \mathit{pending} \Leftrightarrow \mathit{pendingKind}[\mathit{id}] \neq \text{none}`$`),
`PendingIdsAreIssued` (`$`\mathit{id} < \mathit{nextId}`$`), `ShutdownPendingIsShutdown`,
`NoShutdownRequestOutsideShutdown`, `ReconnectQueuedOnlyWhileDisconnected`, and `ReconnectQueueIsUnit`
(`$`\mathit{reconnects} \le 1`$`).

For example, graceful shutdown never reconnects:

```math
\mathit{GracefulShutdownDoesNotReconnect} \;\equiv\; \mathit{shuttingDown}[\ell] \Rightarrow \mathit{reconnects}[\ell] = 0
```

**Liveness (`LspConnectionLiveness`):** under weak fairness on `ConnectProgress`,
`ShutdownCloseProgress`, `ReconnectProgress`, it proves `ConnectingEventuallyResolves`,
`ShutdownEventuallyDisconnects`, `QueuedReconnectEventuallyConsumed`, and
`QueuedReconnectEventuallyAttempts`.

---

## The `…InductiveCheck` variants

Each family has a five-line companion module — e.g. `BrowserAsyncInductiveCheck.tla`:

```tla
InductiveInit == BrowserInv
InductiveSpec == InductiveInit /\ [][Next]_vars
```

with its `.cfg` using `SPECIFICATION InductiveSpec` and `INVARIANT BrowserInv`. As explained in
[theory.md](theory.md#counterexample-to-induction-cti), this is a **counterexample-to-induction**
check: TLC starts from *every* state satisfying the invariant (not just `Init`) and confirms that one
`Next` step preserves it — mechanical evidence that the invariant is **inductive**, exactly the step
case TLAPS proves parametrically. These `.cfg`s shrink the constants further (enumerating all
invariant-states is expensive), so the TLC inductiveness result holds on a small instance while
TLAPS gives the general version.

## Related reading

- [proofs.md](proofs.md) — the TLAPS proofs of these invariants and liveness properties, and the
  Rocq mirrors.
- [verification-gate.md](verification-gate.md) — how the LspConnection model is kept aligned to the
  source, and the honest scope statement.
- [theory.md](theory.md) — the temporal-logic and inductive-invariant background.
