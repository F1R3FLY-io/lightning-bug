# TypeScript Bindings

Lightning Bug is written in [ClojureScript](https://clojurescript.org/) but ships hand-maintained
**TypeScript declaration files** (`.d.ts`) so JavaScript and TypeScript consumers get accurate types
for every prop, imperative method, event, and exported configuration. This page lists those files,
shows how they are validated, and clarifies two intentional design points: the **`Workspace` vs
`WorkspaceSnapshot`** distinction and the deliberately **`any`**-typed `query` / `getDb` boundary.

> **Terms used on this page.** **`.d.ts`** = a TypeScript declaration file (types only, no runtime
> code). **[tsd](https://github.com/tsdjs/tsd)** = a tool that type-checks `*.test-d.ts` assertion
> files against the declarations. **Opaque type** = a type whose internal structure is deliberately
> hidden from consumers.

## The declaration files

The declarations live in the repository's `types/` directory and are wired into the package's
`exports` map (each entry point carries a `types` field):

| File | Declares | Import entry point |
|------|----------|--------------------|
| [`types/lib.d.ts`](../../types/lib.d.ts) | `Editor`, `createWorkspace`, `EditorWorkspaceProvider`, and all core types (`EditorProps`, `EditorRef`, `EditorEvent`, `EditorState`, `WorkspaceSnapshot`, `Workspace`, `LanguageConfig`, `Document`, `Diagnostic`, `Symbol`, `Position`, `Selection`, `LogLevel`) | `@f1r3fly-io/lightning-bug` |
| `types/ext.d.ts` | `RholangExtension: LanguageConfig` | `@f1r3fly-io/lightning-bug/extensions` |
| `types/embedded.tree-sitter.d.ts` | `treeSitterWasmUrl(): string` | `@f1r3fly-io/lightning-bug/tree-sitter` |
| `types/embedded.rholang.d.ts` | `treeSitterRholangWasmUrl(): string` | `@f1r3fly-io/lightning-bug/extensions/lang/rholang/tree-sitter` |
| `types/embedded.rholang-queries.d.ts` | `highlightsQueryUrl(): string`, `indentsQueryUrl(): string` | `@f1r3fly-io/lightning-bug/extensions/lang/rholang/tree-sitter/queries` |

Because the bindings are maintained by hand, changing the public API (a new prop, a new ref method, a
changed event) means editing the matching `.d.ts` and re-running the type tests below.

## Validating the bindings with `tsd`

Each declaration has a sibling `*.test-d.ts` file containing `tsd` assertions (`expectType`,
`expectAssignable`, and `// @ts-expect-error` negatives). Run them with:

```bash
npm run test:types   # runs `tsd`
```

This is part of the full `npm test` gate (alongside the browser test suite — 516 tests at version
`0.7.7`). `tsd` fails if the runtime-facing API and the declarations drift apart, so it is the
guardrail that keeps these types honest. For example, `types/lib.test-d.ts` asserts that
`getState()` returns an `EditorState`, that `query(...)` returns `any`, and that `setLogLevel` rejects
an invalid level.

## Importing the types

Import types alongside values from the same entry point:

```tsx
import * as React from 'react';
import { useRef } from 'react';
import {
  Editor,
  createWorkspace,
  EditorWorkspaceProvider,
  type EditorRef,
  type EditorProps,
  type EditorEvent,
  type WorkspaceSnapshot,
  type Workspace,
  type LanguageConfig,
} from '@f1r3fly-io/lightning-bug';
import { RholangExtension } from '@f1r3fly-io/lightning-bug/extensions';

const workspace: Workspace = createWorkspace();

function TypedEditor(): React.JSX.Element {
  const ref = useRef<EditorRef>(null);
  const languages: Record<string, LanguageConfig> = { rholang: RholangExtension };

  const handle = (event: EditorEvent) => {
    if (event.type === 'diagnostics') {
      // event.data is narrowed to the diagnostics array here.
      console.log(event.data.length);
    }
  };
  void handle;

  return (
    <EditorWorkspaceProvider value={workspace}>
      <Editor ref={ref} languages={languages} />
    </EditorWorkspaceProvider>
  );
}
```

`EditorEvent` is a **discriminated union** keyed on `type`, so a `switch (event.type)` (or an
`if (event.type === ...)`) narrows `event.data` to the right shape — see the
[event catalog](./react-integration.md#the-event-catalog-editorevent).

## `Workspace` vs `WorkspaceSnapshot`

These two names are easy to confuse, and older documentation conflated them. They are **different
types with different roles**:

- **`Workspace`** is an **opaque handle** — declared as `type Workspace = unknown`. It is what
  `createWorkspace()` returns and what you pass to the `workspace` prop or
  `<EditorWorkspaceProvider value={...}>`. You never inspect its fields; you only hold it and hand it
  to editors.

  ```typescript
  export type Workspace = unknown;
  export function createWorkspace(): Workspace;
  ```

- **`WorkspaceSnapshot`** is a **plain data snapshot** of an editor's open-document state, returned
  by `getState().workspace`. It is fully structural:

  ```typescript
  export interface WorkspaceSnapshot {
    documents: Document[];        // the open documents
    activeUri: string | null;     // the active document's URI, or null
  }
  ```

So `getState().workspace` gives you a `WorkspaceSnapshot` you can read:

```typescript
const snap: WorkspaceSnapshot = ref.current!.getState().workspace;
console.log(snap.activeUri, snap.documents.map(d => d.uri));
```

whereas the value you pass into the `workspace` prop is the opaque `Workspace`:

```typescript
const ws: Workspace = createWorkspace();
// <Editor workspace={ws} uri="inmemory:///a.rho" />
```

Mnemonic: **`createWorkspace()` → `Workspace` (a handle you pass in); `getState().workspace` →
`WorkspaceSnapshot` (data you read out).**

## The intentionally-`any` `query` / `getDb` boundary

The DataScript access methods are deliberately loose:

```typescript
query(query: any, params?: any[]): any;
getDb(): any;
```

This is a design choice, not an omission. [Datalog](./querying-datascript.md) result shapes are
**dynamic** — they depend on the query's `:find` clause — so no single static type describes them.
`getDb()` likewise returns an opaque DataScript connection meant for use with the external
`datascript` library, which brings its own types. Narrow at the call site:

```typescript
// The programmer knows this :find returns a scalar string.
const text = ref.current!.query('[:find ?t . :where [?a :workspace/active-uri ?u] ' +
  '[?e :document/uri ?u] [?e :document/text ?t]]') as string;

// A tuple query returns rows.
const rows = ref.current!.query('[:find ?uri ?lang :where [?e :document/uri ?uri] ' +
  '[?e :document/language ?lang]]') as Array<[string, string]>;
```

Prefer the strongly-typed convenience methods where they exist: `getDiagnostics(uri?): Diagnostic[]`
and `getSymbols(uri?): Symbol[]` return concrete types for the two most common reads, so you rarely
need raw Datalog for those. See [Querying DataScript](./querying-datascript.md) for the query API in
full.

## Keeping bindings in sync (contributors)

When you change the public surface:

1. Edit the relevant `types/*.d.ts` to match the new API.
2. Update or add assertions in the sibling `types/*.test-d.ts`.
3. Run `npm run test:types` until it passes; then run the full `npm test` gate.

See [Development: building & testing](../development/building-and-testing.md) for the complete gate
and [the release process](../development/release-process.md) for versioning the change.

## See also

- [React Integration](./react-integration.md) — the props, methods, and events these types describe.
- [Querying DataScript](./querying-datascript.md) — the `any`-typed Datalog API.
- [Language Extensions](./language-extensions.md) — `LanguageConfig` and `RholangExtension`.
