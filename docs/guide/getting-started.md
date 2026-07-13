# Getting Started

This page takes you from nothing to a rendered editor. It covers authenticating to the package
registry, installing the library and its peer dependencies, putting the Tree-Sitter WebAssembly
(**WASM** — a portable binary instruction format that runs in browsers) files in place, and
rendering a minimal React editor. When you are done, continue to
[React Integration](./react-integration.md) for the full API.

> **Terms used on this page.** **npm** = Node Package Manager. **PAT** = Personal Access Token (a
> GitHub credential). **LSP** = Language Server Protocol (optional; see
> [LSP Configuration](./lsp-configuration.md)). **ESM** = ECMAScript Modules, the browser-native
> module system the library ships in.

## Prerequisites

| Tool | Why | Notes |
|------|-----|-------|
| **Node.js** (18+) and **npm** | Install and bundle the package | Any modern bundler that understands ESM works (Vite, webpack, esbuild, Rollup). |
| A **GitHub PAT** with `read:packages` | The package lives on GitHub Packages, not the public npm registry | See [Authenticating](#authenticating-to-github-packages) below. |
| **React 18 or 19** and **react-dom** | `<Editor>` is a React component | React 19 is supported; see [React 18 vs 19](#react-18-vs-19). |
| **rxjs** | The event stream returned by `getEvents()` is an RxJS `Observable` | [RxJS](https://rxjs.dev/) = Reactive Extensions for JavaScript. |

To **build the library from source** (rather than consume the published package) you additionally
need the **Clojure CLI** tools, because the editor is written in
[ClojureScript](https://clojurescript.org/) and compiled with
[shadow-cljs](https://github.com/thheller/shadow-cljs). Source builds are covered under
[Building the repository](#building-the-repository-optional) and, in depth, in
[Development: building & testing](../development/building-and-testing.md).

## Authenticating to GitHub Packages

The package is published as **`@f1r3fly-io/lightning-bug`** on
[GitHub Packages](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-npm-registry),
which requires authentication even for reads.

1. Create a **classic** PAT at <https://github.com/settings/tokens> with the **`read:packages`**
   scope, and copy it.
2. Add an `.npmrc` next to your `package.json` that points the `@f1r3fly-io` scope at GitHub Packages
   and reads the token from an environment variable:

   ```ini
   @f1r3fly-io:registry=https://npm.pkg.github.com
   //npm.pkg.github.com/:_authToken=${NODE_AUTH_TOKEN}
   ```

   (This is exactly the `.npmrc` the Lightning Bug repository itself ships.)
3. Export the token before installing:

   ```bash
   export NODE_AUTH_TOKEN=your_pat_here
   npm install
   ```

For CI, set `NODE_AUTH_TOKEN` from a repository secret (for public repositories the built-in
`${{ secrets.GITHUB_TOKEN }}` is usually sufficient; private consumers need a custom PAT secret).

## Installing the package

Install the editor together with its peer dependencies:

```bash
npm install react react-dom rxjs @f1r3fly-io/lightning-bug
```

For bundled language support (Rholang), the language extension ships from the same package under a
subpath export — no extra install is required:

```js
import { Editor, createWorkspace, EditorWorkspaceProvider } from '@f1r3fly-io/lightning-bug';
import { RholangExtension } from '@f1r3fly-io/lightning-bug/extensions';
```

The package's `exports` map exposes these entry points:

| Import specifier | Provides |
|------------------|----------|
| `@f1r3fly-io/lightning-bug` | `Editor`, `createWorkspace`, `EditorWorkspaceProvider` |
| `@f1r3fly-io/lightning-bug/extensions` | `RholangExtension` (a `LanguageConfig`) |
| `@f1r3fly-io/lightning-bug/tree-sitter` | `treeSitterWasmUrl()` — a data-URL for the core Tree-Sitter WASM |
| `@f1r3fly-io/lightning-bug/extensions/lang/rholang/tree-sitter` | `treeSitterRholangWasmUrl()` — data-URL for the Rholang grammar WASM |
| `@f1r3fly-io/lightning-bug/extensions/lang/rholang/tree-sitter/queries` | `highlightsQueryUrl()`, `indentsQueryUrl()` |

The `*Url()` functions return in-memory blob/data URLs for the embedded WASM and query files, which
lets you avoid serving those assets yourself. See
[Language Extensions](./language-extensions.md#dynamic-resolution-thunks-and-data-urls) for when to
use them.

## Providing the Tree-Sitter WASM assets

Tree-Sitter parses source into a syntax tree for highlighting and indentation, and it runs as a WASM
module in the browser. The editor therefore needs two kinds of WASM at runtime: the **core**
Tree-Sitter runtime and a **per-language grammar** (for example the Rholang grammar).

There is **no automatic install hook** (no `postinstall`). You have two options:

- **Serve the files yourself** and point the editor at their URLs with the `treeSitterWasm` prop
  (core runtime) and each language's `grammarWasm` field (grammar). This is the normal path for an
  app consuming the published package.
- **Use the embedded data-URL exports** (`treeSitterWasmUrl()`, `treeSitterRholangWasmUrl()`, …)
  listed above, so the bytes are inlined and nothing extra needs to be hosted.

When you are **working inside the Lightning Bug repository** (running the demo, the dev app, or the
tests), copy the bundled WASM into the repo's public folders with:

```bash
npm run prepare:all
```

`prepare:all` runs `prepare:app` **and** `prepare:test`. It copies `tree-sitter.wasm` and
`tree-sitter-rholang.wasm` from `node_modules` into `resources/public/js/`,
`resources/public/extensions/lang/rholang/tree-sitter/`, and the test resource directories. If you
ever need to place the grammar manually:

```bash
cp node_modules/@f1r3fly-io/tree-sitter-rholang-js-with-comments/tree-sitter-rholang.wasm \
   resources/public/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm
```

## Building the repository (optional)

If you cloned the repository to build the library or run the examples, these are the compile/watch
targets. Install dependencies first with `npm install` (JavaScript) and `clojure -P` (Clojure, a
dry-run that fetches dependencies).

| Target | What it is | Compile | Watch | Release |
|--------|------------|---------|-------|---------|
| **`:libs`** | Core library + extensions (the published artifact) | `npm run build:debug` | `npm run watch:libs` | `npm run build:release` |
| **`:app`** | Full Re-frame development app (multi-file workspace, logs) | `npx shadow-cljs compile app` | `npm run serve:app` | — |
| **`:demo`** | Minimal standalone HTML demo | `npm run build:demo` | `npm run serve:demo` | — |
| **`:test`** | Browser test build | — | `npm run serve:test` | — |

Watch several at once with `npx shadow-cljs watch libs app test`. The dev app serves at
`http://localhost:3000` (when watching `:app`); the interactive test runner at
`http://localhost:8021` (when watching `:test`). The `:libs` target compiles to **ESM** for modern
browsers; release builds are minified via Google Closure advanced compilation. For a comprehensive
script and target reference, see
[Development: building & testing](../development/building-and-testing.md).

### Running the demo

The demo is a single, server-free HTML file at `resources/public/demo/index.html`. It uses an
[import map](https://developer.mozilla.org/en-US/docs/Web/HTML/Element/script/type/importmap) to
resolve `react`, `react-dom`, `rxjs`, CodeMirror, and `web-tree-sitter`, then imports `Editor` and
`RholangExtension` from the compiled library:

```bash
npm run build:demo                 # compiles :libs and copies assets into the demo folder
# then open resources/public/demo/index.html directly in a browser (no server needed)
```

The demo renders an `<Editor>`, waits for `isReady()`, subscribes to `getEvents()`, and calls
`openDocument()` with a small Rholang program — a compact, end-to-end reference you can copy from.

## Your first editor

Here is the smallest useful React integration. It renders an editor, and once the editor signals
readiness, opens a document.

```jsx
import React, { useRef, useEffect } from 'react';
import { Editor } from '@f1r3fly-io/lightning-bug';
import { RholangExtension } from '@f1r3fly-io/lightning-bug/extensions';

export function HelloEditor() {
  const editorRef = useRef(null);

  useEffect(() => {
    // The imperative handle is only usable once the editor is initialized.
    const id = setInterval(() => {
      const ed = editorRef.current;
      if (ed && ed.isReady()) {
        clearInterval(id);
        ed.openDocument('demo.rho', 'new x in { x!("Hello") | Nil }', 'rholang');
      }
    }, 50);
    return () => clearInterval(id);
  }, []);

  return (
    <div className="code-editor" style={{ height: '400px' }}>
      <Editor ref={editorRef} languages={{ rholang: RholangExtension }} />
    </div>
  );
}
```

Two things to note, both expanded on in [React Integration](./react-integration.md):

- The wrapper carries the **`.code-editor`** class and an explicit height. The editor fills its
  parent, so the parent must have a size. See [Styling](./styling.md).
- We wait for **`isReady()`** before calling imperative methods. A cleaner, event-driven alternative
  is to subscribe to `getEvents()` and act on the `ready` event; see
  [subscribing to events](./react-integration.md#subscribing-to-events).

## React 18 vs 19

The library and demo are compatible with **React 19**, and the core component also works with React
18. (The repository's development tooling — for example `re-frame-10x` — pins a React 18 preload for
its own devtools; that does not constrain your app.)

## Next steps

- **[React Integration](./react-integration.md)** — props, the imperative ref API, the event
  catalog, and the split-pane / multi-editor workflow.
- **[Language Extensions](./language-extensions.md)** — add a language or override Rholang.
- **[LSP Configuration](./lsp-configuration.md)** — turn on diagnostics and symbols.
- **[Styling](./styling.md)** — theme the editor.
- **[TypeScript Bindings](./typescript-bindings.md)** — type-safe usage.
