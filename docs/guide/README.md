# Usage Guide

This section is the **task-oriented guide** to embedding and driving Lightning Bug — the browser
code editor shipped as a React component (`<Editor>`), built on
[CodeMirror 6](https://codemirror.net/), [Tree-Sitter](https://tree-sitter.github.io/tree-sitter/)
(compiled to **WebAssembly**, abbreviated **WASM**), an optional
[Language Server Protocol](https://microsoft.github.io/language-server-protocol/) (**LSP**) client
over WebSockets, and an in-memory [DataScript](https://github.com/tonsky/datascript) database.

If you want the *design* rationale behind these pieces, read the
[Architecture](../architecture/README.md) section instead; this guide stays practical. The
documentation as a whole is indexed by the [top-level docs README](../README.md), and everything here
tracks the current release, version `0.7.7`.

## Where to start

| If you want to… | Read |
|-----------------|------|
| Install the package and render your first editor | [Getting Started](./getting-started.md) |
| Wire the component into a React app (props, ref, events, split panes) | [React Integration](./react-integration.md) |
| Add or override a language (grammar, queries, icons) | [Language Extensions](./language-extensions.md) |
| Connect a language server for diagnostics and symbols | [LSP Configuration](./lsp-configuration.md) |
| Query the editor's internal database with Datalog | [Querying DataScript](./querying-datascript.md) |
| Theme the editor with CSS classes and Bootstrap | [Styling](./styling.md) |
| Use the editor from TypeScript with full type safety | [TypeScript Bindings](./typescript-bindings.md) |

## Recommended reading order

1. **[Getting Started](./getting-started.md)** — authenticate to GitHub Packages, install
   `@f1r3fly-io/lightning-bug` and its peers, copy the Tree-Sitter WASM into place with
   `npm run prepare:all`, and render a minimal editor.
2. **[React Integration](./react-integration.md)** — the deep dive: every `EditorProps` field, the
   imperative `EditorRef` methods, the RxJS event catalog, and the multi-editor / split-pane
   workflow built on `createWorkspace()`.
3. **[Language Extensions](./language-extensions.md)** and **[LSP Configuration](./lsp-configuration.md)**
   — make the editor understand your language and (optionally) talk to a language server.
4. **[Querying DataScript](./querying-datascript.md)**, **[Styling](./styling.md)**, and
   **[TypeScript Bindings](./typescript-bindings.md)** — reference material you will reach for as
   your integration matures.

## Conventions used throughout this guide

- **Positions are 1-based** in the public API: line 1, column 1 is the first character. (The LSP wire
  protocol and the raw diagnostic/symbol records are 0-based; this guide flags the boundary wherever
  it matters.)
- **URI** = Uniform Resource Identifier — the string that names a document, for example
  `inmemory:///demo.rho`. A bare file path such as `demo.rho` is expanded against the editor's
  `defaultProtocol` (default `inmemory://`).
- Code snippets are given in **JavaScript** and, where the types add value, **TypeScript** (`.tsx`).
  Every snippet targets the public API declared in
  [`types/lib.d.ts`](../../types/lib.d.ts); nothing here relies on internal ClojureScript names.
- Acronyms are expanded on first use in each document, so you can enter any page directly.

## See also

- [Architecture: the editor component](../architecture/editor-component.md) and
  [multi-editor Workspaces](../architecture/multi-editor-workspaces.md)
- [Architecture: the RxJS event model](../architecture/event-model.md)
- [Development: building & testing](../development/building-and-testing.md)
