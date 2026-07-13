# Syntax & Editing

This document covers the editing internals: **Tree-Sitter** parsing and highlighting, indentation,
the **CodeMirror** state fields and extension stack, and the **keystroke hot path** — the code that
runs synchronously on every keystroke and is therefore performance-critical.

## Tree-Sitter integration

Tree-Sitter is an incremental parser compiled to WebAssembly. `lib.editor.syntax`
(`src/lib/editor/syntax.cljs`) integrates it with CodeMirror.

- **Initialization.** `Parser.init` loads the Tree-Sitter core WASM once (memoized as a `delay`).
  `init-syntax` is the asynchronous orchestrator: it reads the active language's config, resolves the
  grammar WASM and query paths (each may be a string *or* a thunk — for lazy loading or `data:`
  URLs), and loads the grammar/parser, the `highlights.scm` query, and the `indents.scm` query
  **through `lib.state/load-resource`**, which deduplicates concurrent loads and caches per-Workspace
  resources (see [multi-editor-workspaces.md](multi-editor-workspaces.md#per-workspace-resources-and-lsp)).
  A missing grammar degrades to a fallback (plain editing, no Tree-Sitter); each sub-resource is
  loaded through a validator so a partial failure degrades gracefully rather than crashing.
- **The parse-tree `StateField`.** On `:create` it parses the whole document. On `:update` it:
  - **skips** re-parsing when the change set is empty and the content is identical;
  - does a **full re-parse** for `external-set-annotation` (API-driven) edits;
  - does an **incremental parse** for ordinary user edits, by replaying `iterChanges` into
    `tree.edit(...)` (mapping character offsets to Tree-Sitter points) and calling
    `parser.parse(newDoc, editedTree)`.

### Viewport-cached highlighting

Highlighting is a CodeMirror `ViewPlugin` that turns `highlights.scm` captures into decoration marks
(mapping each capture name to a `cm-*` CSS class). To avoid rebuilding decorations on every scroll,
it uses a **viewport cache with a margin**:

- It queries captures only for the range `[viewport.from − 2000, viewport.to + 2000]` characters (a
  ±2000-character margin), caches the covered range and its decorations, and **reuses** them while
  the viewport stays inside the cached range.
- It rebuilds only when the document/language state changes or the viewport moves outside the cached
  range.

This is the `EXP-005` optimization; on a cache hit it is **30–100× faster** than an unconditional
rebuild. Observable cache statistics (hits/misses/rebuilds) are exported for benchmarking. See the
[performance model](../benchmarks/performance-model.md#the-viewport-highlight-cache); the archived record
is [`../archive/benchmarks/EXP-005_viewport-highlight-cache.md`](../archive/benchmarks/EXP-005_viewport-highlight-cache.md).

### Indentation

`indents.scm` drives a CodeMirror `indentService`. `calculate-indent` walks up the parse tree from
the edit point looking for `@indent`/`@branch` captures and computes the base indent from the
captured node's line plus the configured `indentSize`.

## The CodeMirror extension stack

`get-extensions` (`lib.editor.runtime`) assembles the default stack, in order: line numbers, bracket
matching, close-brackets, a merged keymap (`indentWithTab` + default/history/search keymaps), the
**`syntax-compartment`** (initially empty; reconfigured by `init-syntax` once the grammar loads), a
dark theme, the diagnostics `StateField`, the keystroke update listener, history, and search — then
the diagnostics linter extensions and any `extraExtensions` the host passed.

CodeMirror **compartments** let the syntax configuration be swapped at runtime (when a grammar
finishes loading or the language changes) without rebuilding the whole editor.

### Diagnostics rendering

The **live** diagnostics path uses an effect-based `StateField` in `lib.editor.diagnostics`: the pane
dispatches a `set-diagnostic-effect`, consumed by the field, rendered by a memoized CodeMirror
`linter` plus a `lintGutter`. Severity maps to a CSS class (`cm-error-underline`, etc.); the
transform is memoized against a diagnostics hash and the document length.

> **Accuracy note.** There are two state fields named `diagnostic-field` — an annotation-based one in
> `lib.editor.runtime` and the effect-based one in `lib.editor.diagnostics`. Only the effect-based
> one is live; the annotation-based field is vestigial (nothing dispatches its annotation). Docs
> describe the live path.

### Range highlight is opt-in

`lib.editor.highlight` defines a `cm-highlight` range decoration (`StateField` + `ViewPlugin` +
annotation). **It is not part of the default extension stack.** The imperative methods
`highlightRange`/`clearHighlight` always *emit* a `highlight-change` event, but the visible
decoration renders only if a consumer adds the highlight plugin via `extraExtensions`. This is called
out in [React Integration](../guide/react-integration.md) so hosts that need the visible highlight
know to opt in.

## The keystroke hot path

The update listener (`update-ext` in `lib.editor.runtime`) runs synchronously on every CodeMirror
update. Keeping it cheap is why the editor stays responsive during fast typing.

![Keystroke hot-path activity diagram: on each update the listener refreshes cursor/selection from the cheap per-pane atom; if the document changed and there is an active URI, an API-driven (annotated) change takes an immediate callback and full re-parse, whereas a user edit clears stale diagnostics, publishes a sync delta only if peers exist, accumulates an incremental LSP change, schedules an idle DataScript text sync, emits content-change carrying only the uri and length, schedules a debounced didChange, and does an incremental Tree-Sitter parse.](diagrams/keystroke-hot-path.svg)

The design principles visible here — all validated against benchmarks (see the
[performance model](../benchmarks/performance-model.md)) — are:

- **Never serialize the whole document in the listener.** `content-change` carries `{uri, length}`,
  not the text; consumers read the text from DataScript when they need it.
- **The DataScript text sync is debounced and idle-scheduled** (50 ms, max-wait 200 ms, inside a
  `requestIdleCallback`), so it never blocks a keystroke.
- **The active URI is read from a cheap per-pane atom**, not a DataScript query per keystroke.
- **Same-file publishing is gated by `has-peers?`**, so a lone editor pays nothing.
- **API-driven changes bypass the user-edit path** (no publish, no LSP-as-user-edit), discriminated
  by the `external-set-annotation`.

The safety property that stale debounced/idle callbacks must observe an unmounted editor and perform
no mutation is specified by the [BrowserAsync formal model](../formal/models.md#browserasync).

## Related reading

- [data-model.md](data-model.md) — where the idle text sync and diagnostics land.
- [lsp-subsystem.md](lsp-subsystem.md) — the debounced `didChange` this path schedules.
- [event-model.md](event-model.md) — `content-change`, `highlight-change`, `scroll`.
- [Performance Model](../benchmarks/performance-model.md) — the measurements behind these choices.
- [Styling](../guide/styling.md) — the `cm-*` highlight classes.
