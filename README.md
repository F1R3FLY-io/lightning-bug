# Lightning Bug

[![CI Status](https://github.com/f1R3FLY-io/lightning-bug/actions/workflows/ci.yaml/badge.svg)](https://github.com/f1R3FLY-io/lightning-bug/actions/workflows/ci.yaml)

Lightning Bug is a modern, extensible code editor for the browser, built with **ClojureScript** and
**CodeMirror 6**. It ships as a React `<Editor>` component with pluggable language support via
**Tree-Sitter** (syntax highlighting and indentation), optional **Language Server Protocol (LSP)**
integration over WebSockets (diagnostics and symbols), and an in-memory **DataScript** database for
editor state. It supports **multiple, independent or collaborating editor instances** on one page.
The design is decoupled from any specific language or server, so new languages are easy to add.

> **Documentation.** This README is a focused landing page: installation, embedding, the core API,
> and pointers. The full documentation set — architecture, usage guides, formal verification,
> benchmarking, security, and development — lives under **[`docs/`](docs/README.md)**. See the
> [documentation map](#documentation) below.

## License

Licensed under the Apache License 2.0. See [LICENSE.txt](LICENSE.txt).

## GitHub repositories

| Repository | URL |
|------------|-----|
| Lightning Bug | [github.com/f1R3FLY-io/lightning-bug](https://github.com/f1R3FLY-io/lightning-bug) |
| Rholang Tree-Sitter Grammar | [github.com/dylon/rholang-rs](https://github.com/dylon/rholang-rs) (branch `dylon/comments`) |
| Rholang Language Server | [github.com/f1R3FLY-io/rholang-language-server](https://github.com/f1R3FLY-io/rholang-language-server) |

## Installation

Install from npm (the package is `@f1r3fly-io/lightning-bug`, published to GitHub Packages):

```bash
npm install react react-dom rxjs @f1r3fly-io/lightning-bug
```

Then copy the Tree-Sitter WASM assets into place (there is **no** automatic postinstall step):

```bash
npm run prepare:all   # runs prepare:app + prepare:test; copies tree-sitter*.wasm into resources/
```

### GitHub Packages authentication

The package is hosted on GitHub Packages, so you need a Personal Access Token (PAT) with the
`read:packages` scope, exposed as `NODE_AUTH_TOKEN` before `npm install`:

```bash
export NODE_AUTH_TOKEN=your_pat_here
npm install
```

The `.npmrc` is configured to read this token. In CI, store it as a secret. See
[GitHub's npm registry docs](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-npm-registry)
and, for token-handling security, [`docs/security/threat-model.md`](docs/security/threat-model.md#secrets-in-the-toolchain-buildci).

## Quick start — embedding the Editor

```jsx
import React, { useRef } from 'react';
import { Editor } from '@f1r3fly-io/lightning-bug';
import { RholangExtension } from '@f1r3fly-io/lightning-bug/extensions'; // optional: Rholang support

function MyEditor() {
  const editorRef = useRef(null);
  return (
    <Editor
      ref={editorRef}
      languages={{ rholang: RholangExtension }}
    />
  );
}
```

Use the `ref` to drive the editor imperatively and subscribe to its RxJS event stream:

```jsx
if (editorRef.current?.isReady()) {
  const sub = editorRef.current.getEvents().subscribe((e) => console.log(e.type, e.data));
  editorRef.current.openDocument('demo.rho', 'new x in { x!("Hello") | Nil }', 'rholang');
}
```

The full embedding guide — every prop, the imperative method reference, and the event catalog — is
in [`docs/guide/react-integration.md`](docs/guide/react-integration.md).

## Multiple editors and Workspaces

An `<Editor>` belongs to a **Workspace** — the container for its documents, loaded grammars, and LSP
connections. With no `workspace` prop, an editor uses a shared process-default Workspace. To run
independent editors, or to have two editors collaborate on the same file, create a Workspace and
share it:

```jsx
import { Editor, createWorkspace, EditorWorkspaceProvider } from '@f1r3fly-io/lightning-bug';

const ws = createWorkspace(); // hold this in a stable binding so it survives hot reloads

function SplitPane() {
  // Two panes over the SAME Workspace and file: edits sync live, each keeps its own cursor.
  return (
    <EditorWorkspaceProvider value={ws}>
      <Editor uri="a.rho" languages={{ rholang: RholangExtension }} />
      <Editor uri="a.rho" languages={{ rholang: RholangExtension }} />
    </EditorWorkspaceProvider>
  );
}
```

Editors given **distinct** Workspaces share nothing. See
[`docs/architecture/multi-editor-workspaces.md`](docs/architecture/multi-editor-workspaces.md) for
the model and [`docs/guide/react-integration.md`](docs/guide/react-integration.md) for the API.

## The public API at a glance

The `<Editor>` exposes an imperative handle (via `ref`) and an RxJS event stream. The imperative
methods, grouped:

- **Lifecycle / state:** `isReady`, `getState`, `getEvents`
- **Documents:** `openDocument`, `closeDocument`, `renameDocument`, `saveDocument`, `activateDocument`
- **Text:** `getText`, `setText`, `getFilePath`, `getFileUri`
- **Cursor / selection:** `getCursor`, `setCursor`, `getSelection`, `setSelection`
- **Highlight / scroll:** `highlightRange`, `clearHighlight`, `centerOnRange`
- **LSP data:** `getDiagnostics`, `getSymbols`, `shutdownLsp`
- **DataScript:** `query`, `getDb`
- **Search / logging:** `getSearchTerm`, `openSearchPanel`, `getLogLevel`, `setLogLevel`

The authoritative TypeScript types are in [`types/lib.d.ts`](types/lib.d.ts); a consumer-facing
walkthrough is in [`docs/guide/react-integration.md`](docs/guide/react-integration.md), and the
TypeScript-bindings notes (including that `getState().workspace` is a `WorkspaceSnapshot`, distinct
from the opaque `Workspace` handle returned by `createWorkspace()`) are in
[`docs/guide/typescript-bindings.md`](docs/guide/typescript-bindings.md).

## Language extensions

Language support is pluggable via the `languages` prop — a map of language name to a `LanguageConfig`
(grammar WASM, highlight/indent queries, optional `lspUrl`, file extensions, and more). A default
`"text"` language provides plain editing with no grammar. The bundled Rholang extension
(`RholangExtension`) is a complete example. See
[`docs/guide/language-extensions.md`](docs/guide/language-extensions.md).

## LSP for Rholang

The editor connects to `rholang-language-server` over a WebSocket for diagnostics and symbols. Launch
the server in WebSocket mode (default port 41551):

```bash
rholang-language-server --websocket --port 41551 --log-level debug
```

and point the language's `lspUrl` at it. LSP is optional — the editor degrades to syntax-only without
it. Full setup: [`docs/guide/lsp-configuration.md`](docs/guide/lsp-configuration.md). Because a
language server is untrusted input, review [`docs/security/README.md`](docs/security/README.md) before
connecting a non-localhost server.

## Building from source

Install the Clojure CLI and Node.js, then `npm install` and `clojure -P`. The build targets:

| Target | Description | Compile | Watch |
|--------|-------------|---------|-------|
| `:libs` | Core library + extensions (the published package) | `npm run build:debug` / `build:release` | `npm run watch:libs` |
| `:app` | Full re-frame demo application (also the DI composition root) | `npx shadow-cljs compile app` | `npm run serve:app` (`:3000`) |
| `:demo` | Minimal standalone HTML demo | `npm run build:demo` | `npm run serve:demo` |
| `:test` | Browser test suite | — | `npm run serve:test` |

The complete build/test reference, the CI pipeline, and the documentation-diagram workflow are in
[`docs/development/building-and-testing.md`](docs/development/building-and-testing.md).

## Architecture in brief

The library is organized as a **partial hexagonal (ports-and-adapters) architecture**. The demo
application consumes domain ports (`domain.protocols`) through re-frame coeffects/effects, with
`app.system` as a dependency-injection container over DataScript-backed adapters; the editor library
itself is a parallel stack (`lib.core` React shell → `lib.editor.runtime`/`lib.editor.commands`,
Tree-Sitter syntax, the LSP client, the Workspace model, and the DataScript database). Editor state
lives in a per-Workspace DataScript database queried with Datalog. Events propagate to the host via
RxJS.

The full treatment — with diagrams — is in [`docs/architecture/`](docs/architecture/README.md).

## Formal verification

The trickiest concurrent behaviours — the LSP connection state machine, same-file multi-pane sync,
the document lifecycle, and React-unmount safety — are specified in **TLA+** (checked with TLC and
proved with TLAPS) and **Rocq**, and a CI gate keeps the LSP state machine's definition aligned
across the source, the TLA+ model, and the Rocq model. The models, proofs, and the honest statement
of what is and is not guaranteed are in [`docs/formal/`](docs/formal/README.md).

## Documentation

| Section | Contents |
|---------|----------|
| [Architecture](docs/architecture/README.md) | System design: the hexagon, the editor component, multi-editor Workspaces, the data model, LSP, syntax & editing, and the event model. |
| [Usage Guide](docs/guide/README.md) | Getting started, React integration, language extensions, LSP configuration, querying DataScript, styling, TypeScript bindings. |
| [Formal Verification](docs/formal/README.md) | TLA+/Rocq models & proofs, the theory primer, and the source↔formal alignment gate. |
| [Benchmarks](docs/benchmarks/README.md) | The statistical methodology and the durable performance model. |
| [Development](docs/development/building-and-testing.md) | Building, testing, contributing, and releasing. |
| [Security](docs/security/README.md) | Trust boundaries and the threat model. |
| [Archive](docs/archive/README.md) | Preserved historical ledgers (completed campaigns, benchmark experiments). |

## Supported platforms

Tested in CI:

- **Operating systems:** macOS, Windows, Linux (Ubuntu)
- **Browsers:** Chrome, Firefox, Edge, Opera, Brave, and Safari (on macOS)

The library and demo are compatible with **React 19**; development tooling (re-frame-10x) uses the
React 18 preload.

## Contributing

Contributions follow the Clojure style guide (kebab-case keys, grouped imports), conventional
commits, and a full test + lint gate; new features should add unit, integration, and property-based
tests. Branch from `main`. The full guidelines are in
[`docs/development/contributing.md`](docs/development/contributing.md), and the release process
(Keep a Changelog + Semantic Versioning) is in
[`docs/development/release-process.md`](docs/development/release-process.md).

## Gitignore

The `.gitignore` blacklists everything by default (`*`), then whitelists specific patterns (e.g.
`!/docs/**/*.md`, `!/src/**/*.cljs`). A new file whose type/location is not covered by a whitelist
rule will be silently ignored — add a rule if `git status` does not show a file you expect. See
[`docs/development/contributing.md`](docs/development/contributing.md#the-gitignore-whitelist-gotcha).
