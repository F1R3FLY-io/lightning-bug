# Lightning Bug

Lightning Bug is a modern, extensible code editor built with ClojureScript, Reagent, Re-frame, and CodeMirror 6. It features pluggable language support via Tree-Sitter for syntax highlighting and indentation, and LSP integration for advanced features like diagnostics and symbols. The editor is designed to be decoupled from specific language or servers, allowing easy extension for new languages.

## License

This project is licensed under the Apache License 2.0. See the [LICENSE.txt](LICENSE.txt) file for details.

## GitHub Repositories

- Lightning Bug: [http://github.com/f1R3FLY-io/lightning-bug](http://github.com/f1R3FLY-io/lightning-bug)
- Rholang Tree-Sitter Grammar: [https://github.com/dylon/rholang-rs](https://github.com/dylon/rholang-rs) (use branch: `dylon/comments`)
- Rholang Language Server: [https://github.com/f1R3FLY-io/rholang-language-server](https://github.com/f1R3FLY-io/rholang-language-server)

## Compiling Rholang Tree-Sitter Parser WASM

To compile the Tree-Sitter parser for Rholang from the `rholang-rs` repository and copy it to the project:

1. Clone the repository and checkout the branch:
   ```
   git clone https://github.com/dylon/rholang-rs.git
   cd rholang-rs
   git checkout dylon/comments
   ```

2. Build the WASM parser using `tree-sitter-cli` (ensure you have `wasm-bindgen` and `wasm-opt` installed via Cargo if needed):
   ```
   cargo install tree-sitter-cli
   tree-sitter generate
   tree-sitter build-wasm
   ```

3. Copy the generated WASM file to the project:
   ```
   cp tree-sitter-rholang.wasm path/to/lightning-bug/resources/public/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm
   ```

   Replace `path/to/lightning-bug` with your local project path.

## Installation and Compilation

### Dependencies

- Install Clojure CLI tools.
- Install Node.js dependencies: `npm install`.
- Install Clojure dependencies: `clojure -P` (dry run to fetch deps).

### Compiling and Watching Targets

Use `shadow-cljs` for building. Targets include `:lib` (core library), `:ext` (extensions), `:app` (demo app), and `:test` (browser tests).

- Compile a target (e.g., `:app`): `npx shadow-cljs compile app`
- Watch a target (e.g., `:app`): `npx shadow-cljs watch app`
- Release build (e.g., `:lib`): `npx shadow-cljs release lib`

For multiple targets: `npx shadow-cljs watch lib ext app test`

Access the app at `http://localhost:3000` during watch.

## Running Tests

Run browser tests: `npx shadow-cljs watch test`

Open `http://localhost:8021` in a browser to run and view tests.

## Demo App

A standalone demo is available at `resources/public/demo/index.html`. Open it directly in a browser (no server needed). It demonstrates importing the `Editor` component and `RholangExtension`, opening a document, and subscribing to RxJS events.

Example usage in the demo:
```html
<script type="module">
  import { Editor } from 'lightning-bug';
  import { RholangExtension } from 'lightning-bug-extensions';
  // ...
</script>
```

## Configuring LSP for Rholang

The editor supports connecting to the `rholang-language-server` via WebSocket for LSP features like diagnostics and symbols.

### Running the Language Server

1. Clone and build the server:
   ```
   git clone https://github.com/f1R3FLY-io/rholang-language-server.git
   cd rholang-language-server
   # Follow build instructions in the repository
   ```

2. Launch the server in WebSocket mode (port is configurable, default 41551):
   ```
   rholang-language-server --websocket --port 41551 --log-level debug
   ```

### Editor Configuration

In the `languages` prop/map, set `:lsp-url` for `"rholang"` to the WebSocket URL (e.g., `"ws://localhost:41551"`).

Example in JS:
```js
const languages = {
  "rholang": {
    ...RholangExtension,
    lspUrl: "ws://localhost:41551"
  }
};
<Editor languages={languages} ... />
```

In the demo app (`demo/index.html`), update `RholangExtension` to include `lspUrl: "ws://localhost:41551"`.

For the full app (`:app` target), update `src/app/extensions/lang/rholang.cljs` or pass via props.

## Public API

### Editor Component

The `Editor` is a React component exported from the `:lib` target. Props (camelCase in JS):

- `content`: Initial content (string).
- `language`: Language key (string, e.g., `"rholang"`).
- `languages`: Map of language configs (object, e.g., `{"rholang": RholangExtension}`).
- `onContentChange`: Callback for content changes (function(content)).
- `extraExtensions`: Array of additional CodeMirror extensions to extend/override defaults.

Example (JSX/JS):
```jsx
<Editor
  content="new x in { x!('Hello') }"
  language="rholang"
  languages={{ rholang: RholangExtension }}
  extraExtensions={[myCustomExtension]}
  onContentChange={(content) => console.log(content)}
/>
```

### Imperative Methods (via React ref)

- `getState()`: Returns editor state (object with `content`, `language`, `diagnostics`, `symbols`, etc.).
- `setContent(content)`: Sets content.
- `getEvents()`: Returns RxJS observable for events.
- `getCursor()`: Returns cursor position `{line, column}`.
- `setCursor(pos)`: Sets cursor `{line, column}`.
- `getSelection()`: Returns selection `{from: {line, column}, to: {line, column}, text}` or null.
- `setSelection(from, to)`: Sets selection range.
- `openDocument(uri, content, lang)`: Opens a document.
- `closeDocument()`: Closes current document.
- `renameDocument(newName)`: Renames current document.
- `saveDocument()`: Saves (notifies LSP via `didSave`).
- `isReady()`: Returns boolean if editor is initialized.
- `highlightRange(from, to)`: Highlights range `{line, column}`.
- `clearHighlight()`: Clears highlight.
- `centerOnRange(from, to)`: Scrolls to center on range.

### RxJS Events

Subscribe to events via `editorRef.current.getEvents().subscribe(event => { ... })`.

Available events (`event.type`):
- `ready`: Editor initialized.
- `content-change`: Content updated `{content}`.
- `selection-change`: Selection/cursor changed `{cursor: {line, column}, selection}`.
- `document-open`: Document opened `{uri, language}`.
- `document-close`: Document closed `{uri}`.
- `document-rename`: Document renamed `{old-uri, new-uri, name}`.
- `document-save`: Document saved `{uri, content}`.
- `lsp-message`: Raw LSP message `{method, params, ...}`.
- `lsp-initialized`: LSP initialized.
- `diagnostics`: Diagnostics updated (array).
- `symbols-update`: Symbols updated (array).
- `log`: Log message `{message}`.
- `connect`: LSP connected.
- `disconnect`: LSP disconnected.
- `lsp-error`: LSP error `{code, message}`.

Subscribe example:
```js
const subscription = editorRef.current.getEvents().subscribe(event => {
  console.log(event.type, event.data);
});
subscription.unsubscribe(); // Cleanup
```

## Architectural Overview

- **Core Library (`:lib`)**: Exports `Editor` React component. Manages CodeMirror state, Tree-Sitter for syntax/indents, LSP client via WebSocket, diagnostics/highlights as StateFields/Plugins, RxJS for events.
- **Extensions (`:ext`)**: Language configs (e.g., Rholang with Tree-Sitter WASM, queries, LSP URL).
- **App (`:app`)**: Demo UI with Re-frame for state, multi-file workspace, logs panel. Uses Datascript/Re-posh for symbols/diagnostics.
- **Tests (`:test`)**: Browser tests for units/integration/property-based.
- **Decoupling**: Languages pluggable via map; LSP optional (falls back to basic editor); Tree-Sitter/LSP as WASM.
- **Events**: Internal changes/LSP notifications propagated via RxJS.
- **Performance**: Debounced updates, viewport-only decorations, incremental Tree-Sitter parsing.

## Contribution Guidelines

- **Code Style**: Follow Clojure style guide; use kebab-case for keys; group/order imports (core first, local last).
- **Commits**: Use conventional commits (e.g., `feat: add X`, `fix: resolve Y`).
- **Testing**: Add unit/integration/property-based tests; ensure 100% coverage for new features. Use `test.check` for props with fixed seeds.
- **Dependencies**: Consolidate where possible; prefer modern libs.
- **Documentation**: Keep comments/docs up-to-date; add where missing. Do not remove existing unless improving readability.
- **Security**: Apply updates; be mindful in LSP/WebSocket handling.
- **PRs**: Branch from `main`; include tests/docs; reference issues. Do not regenerate unchanged files.
- **Issues**: Use labels (bug, enhancement); provide repro steps.
- **Reviews**: Require 1 approval; focus on readability/maintainability/DRY/separation of concerns.
