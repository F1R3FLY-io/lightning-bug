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

Use `shadow-cljs` for building. Targets include `:libs` (core library and extensions), `:app` (demo app), and `:test` (browser tests).

- Compile a target (e.g., `:app`): `npx shadow-cljs compile app`
- Watch a target (e.g., `:app`): `npx shadow-cljs watch app`
- Release build (e.g., `:libs`): `npx shadow-cljs release libs`

For multiple targets: `npx shadow-cljs watch libs app test`

Access the app at `http://localhost:3000` during watch.

The `:libs` target compiles to ESM format for modern browser compatibility. Release builds are minified via advanced compilation. Note that while the outputs are ESM modules, they consist of multiple files due to Closure compilation and external dependencies (e.g., CodeMirror, RxJS). For single-file bundles, consider a post-build step with a tool like esbuild or Rollup (not included in this project).

## TypeScript Bindings

TypeScript bindings (.d.ts files) are manually maintained for the core library and extensions to provide accurate type information for JavaScript/TypeScript consumers. These are located in the `types/` directory:
- `types/lib.d.ts`: Bindings for the main `Editor` component and related types/interfaces.
- `types/ext.d.ts`: Bindings for language extensions (e.g., `RholangExtension`).

The bindings are referenced in `package.json` via the `"types"` fields in `"exports"`. To keep them updated:
- When changing the public API (e.g., adding props to `Editor`, new methods on the ref, or updates to exported configs), review and manually adjust the .d.ts files to match.
- Use tools like GitHub Copilot or an AI assistant (e.g., Grok) to help generate or update the bindings based on the ClojureScript code.
- Always validate changes with `npm run test:types` to ensure type safety and catch mismatches.

## Running Tests

Run browser tests: `npx shadow-cljs watch test`

Open `http://localhost:8021` in a browser to run and view tests.

Validate TypeScript bindings: `npm run test:types`

## Demo App

The demo app is a standalone HTML file located at `resources/public/demo/index.html` that demonstrates importing and using the `Editor` component and `RholangExtension` in a browser environment without a server. It loads dependencies via an import map and ESM modules, including the compiled library from `node_modules/lightning-bug/dist/libs/`.

### Building the Demo App

To build the demo app (compiles the library and copies assets):

1. Run the build script: `./scripts/build-demo.sh`

   This:
   - Installs npm dependencies.
   - Compiles the `:libs` target to `dist/libs/`.
   - Installs demo-specific npm dependencies.
   - Copies Tree-Sitter WASM and extensions to the demo directory.

2. Open `resources/public/demo/index.html` directly in a browser (no server needed).

### Serving the Demo App for Development

For live-reloading during development (e.g., to test changes to the library):

1. Watch the library and demo: `npx shadow-cljs watch libs demo`
2. Copy the compiled `dist/libs/` to the demo: `mkdir -p resources/public/demo/dist/libs && cp -r dist/libs/* resources/public/demo/dist/libs/`
3. Serve the demo directory with a static server (e.g., `npx serve resources/public/demo` or use the built-in Shadow-CLJS server on port 3001).
4. Access at `http://localhost:3001` (adjust port if needed).

### How the Demo App Works

The demo app is a simple HTML file with embedded JavaScript (ES modules) that:
- Defines an import map to resolve dependencies like `react`, `react-dom`, `rxjs`, CodeMirror packages, and `web-tree-sitter` from CDNs or local node_modules.
- Imports the `Editor` component from `lightning-bug` and `RholangExtension` from `lightning-bug/extensions`.
- Creates a React root and renders the `Editor` with initial props (e.g., language "rholang", languages map with `RholangExtension`).
- Uses a ref to access imperative methods: checks `isReady()`, subscribes to events via `getEvents()`, and calls `openDocument()` to load content.
- Logs events to the console for demonstration.

Key code snippet from `demo/index.html`:
```html
<script type="module">
  (async () => {
    // Patch goog BEFORE imports
    globalThis.goog = globalThis.goog || {};
    globalThis.goog.provide = globalThis.goog.constructNamespace_ || function(name) { /* noop or log */ };
    globalThis.goog.require = (globalThis.goog.module && globalThis.goog.module.get) || globalThis.goog.require || function(name) { /* noop */ };

    const React = await import('react');
    const { createRoot } = await import('react-dom/client');
    const { Editor } = await import('lightning-bug');
    const { RholangExtension } = await import('lightning-bug-extensions');
    const { defaultKeymap } = await import('@codemirror/commands');
    const customExtensions = [defaultKeymap];
    const root = createRoot(document.getElementById('app'));
    const editorRef = React.createRef();
    root.render(React.createElement(Editor, {
      ref: editorRef,
      language: "rholang",
      languages: {"rholang": RholangExtension},
      extraExtensions: customExtensions
    }));
    const interval = setInterval(() => {
      if (editorRef.current && editorRef.current.isReady()) {
        clearInterval(interval);
        const subscription = editorRef.current.getEvents().subscribe(event => {
          console.log('Event:', event.type, event.data);
        });
        editorRef.current.openDocument(
          "inmemory://demo.rho",
          "new x in { x!(\"Hello\") | Nil }",
          "rholang"
        );
      }
    }, 100);
  })();
</script>
```

This setup allows quick testing of the editor in isolation, without the full Re-frame app.

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

The `Editor` is a React component exported from the `:libs` target. Props (camelCase in JS):

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
- `setText(text)`: Replaces the entire editor text (similar to setContent but directly updates the document).

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
- `highlight-change`: Highlight range updated `{from: {line, column}, to: {line, column}}` or `null` on clear.

Subscribe example:
```js
const subscription = editorRef.current.getEvents().subscribe(event => {
  console.log(event.type, event.data);
});
subscription.unsubscribe(); // Cleanup
```

## Customizing the Editor Component

The `Editor` component can be customized using props for initial setup and a React ref for imperative control. This allows dynamic interactions without modifying the Lightning Bug source code. Below are step-by-step guides for vanilla JavaScript and TypeScript.

### Customizing with Vanilla JavaScript

1. **Import Dependencies:** Use an import map or script tags to load React, React DOM, RxJS, and Lightning Bug modules.

   ```html
   <script type="importmap">
     {
       "imports": {
         "react": "https://esm.sh/react@19",
         "react-dom": "https://esm.sh/react-dom@19",
         "react-dom/client": "https://esm.sh/react-dom@19/client",
         "rxjs": "https://esm.sh/rxjs@7",
         "lightning-bug": "./node_modules/lightning-bug/dist/libs/lib.core.js",
         "lightning-bug/extensions": "./node_modules/lightning-bug/dist/libs/ext.lang.rholang.js"
       }
     }
   </script>
   <script type="module">
     import React from 'react';
     import { createRoot } from 'react-dom/client';
     import { Editor } from 'lightning-bug';
     import { RholangExtension } from 'lightning-bug/extensions';
   </script>
   ```

2. **Render the Editor:** Create a root and render the `Editor` with props.

   ```javascript
   const root = createRoot(document.getElementById('app'));
   const editorRef = React.createRef();
   root.render(React.createElement(Editor, {
     ref: editorRef,
     content: 'initial content',
     language: 'rholang',
     languages: { rholang: RholangExtension },
     onContentChange: (content) => console.log('Content changed:', content)
   }));
   ```

3. **Use Ref Methods:** Access methods via the ref after the editor is ready.

   ```javascript
   const interval = setInterval(() => {
     if (editorRef.current && editorRef.current.isReady()) {
       clearInterval(interval);
       // Subscribe to events
       const sub = editorRef.current.getEvents().subscribe(event => console.log(event.type, event.data));
       // Open a document
       editorRef.current.openDocument('inmemory://test.rho', 'new x in { x!("Hello") }', 'rholang');
       // Get state
       const state = editorRef.current.getState();
       console.log('State:', state.content, state.language);
       // Set content
       editorRef.current.setContent('updated content');
       // Get cursor
       const cursor = editorRef.current.getCursor();
       console.log('Cursor:', cursor.line, cursor.column);
       // Set cursor
       editorRef.current.setCursor({ line: 1, column: 5 });
       // Get selection
       const selection = editorRef.current.getSelection();
       if (selection) console.log('Selection:', selection.text);
       // Set selection
       editorRef.current.setSelection({ line: 1, column: 1 }, { line: 1, column: 6 });
       // Close document
       editorRef.current.closeDocument();
       // Rename document (after reopening)
       editorRef.current.openDocument('inmemory://old.rho', 'content', 'rholang');
       editorRef.current.renameDocument('new.rho');
       // Save document
       editorRef.current.saveDocument();
       // Highlight range
       editorRef.current.highlightRange({ line: 1, column: 1 }, { line: 1, column: 5 });
       // Clear highlight
       editorRef.current.clearHighlight();
       // Center on range
       editorRef.current.centerOnRange({ line: 2, column: 1 }, { line: 2, column: 10 });
       // Set text (replace all)
       editorRef.current.setText('new text');
       // Unsubscribe
       sub.unsubscribe();
     }
   }, 100);
   ```

### Customizing with TypeScript

To use Lightning Bug in a TypeScript project, import the types from the package.

1. **Setup and Imports:** Use TypeScript with module resolution.

   ```typescript
   import React, { createRef } from 'react';
   import { createRoot } from 'react-dom/client';
   import { Editor, EditorRef, LanguageConfig } from 'lightning-bug';
   import { RholangExtension } from 'lightning-bug/extensions';
   import { Observable } from 'rxjs';

   const languages: Record<string, LanguageConfig> = {
     rholang: RholangExtension
   };

   const root = createRoot(document.getElementById('app')!);
   const editorRef = createRef<EditorRef>();
   root.render(<Editor ref={editorRef} content="initial" language="rholang" languages={languages} onContentChange={(content) => console.log(content)} />);
   ```

2. **Use Ref Methods:** Type-safe access to methods.

   ```typescript
   const interval = setInterval(() => {
     if (editorRef.current && editorRef.current.isReady()) {
       clearInterval(interval);
       const sub: Subscription = editorRef.current.getEvents().subscribe((event: { type: string; data: any }) => console.log(event.type, event.data));
       editorRef.current.openDocument('inmemory://test.rho', 'new x in { x!("Hello") }', 'rholang');
       const state = editorRef.current.getState();
       console.log('State:', state.content, state.language);
       editorRef.current.setContent('updated');
       const cursor = editorRef.current.getCursor();
       console.log('Cursor:', cursor.line, cursor.column);
       editorRef.current.setCursor({ line: 1, column: 5 });
       const selection = editorRef.current.getSelection();
       if (selection) console.log('Selection:', selection.text);
       editorRef.current.setSelection({ line: 1, column: 1 }, { line: 1, column: 6 });
       editorRef.current.closeDocument();
       editorRef.current.openDocument('inmemory://old.rho', 'content', 'rholang');
       editorRef.current.renameDocument('new.rho');
       editorRef.current.saveDocument();
       editorRef.current.highlightRange({ line: 1, column: 1 }, { line: 1, column: 5 });
       editorRef.current.clearHighlight();
       editorRef.current.centerOnRange({ line: 2, column: 1 }, { line: 2, column: 10 });
       editorRef.current.setText('new text');
       sub.unsubscribe();
     }
   }, 100);
   ```

## Styling the Editor

The `Editor` can be styled using CSS classes and Bootstrap utilities without modifying the component. Apply styles via external CSS files or inline styles on the parent container. Below are all customizable CSS classes from the default theme (`main.css` or `demo.css`).

### Customizable CSS Classes

- `.code-editor`: Wrapper for the entire editor (set height/width).
- `.cm-editor`: CodeMirror editor container (background, borders).
- `.cm-content`: Editable content area (font, padding, colors).
- `.cm-gutters`: Gutter for line numbers (background, alignment).
- `.cm-lineNumbers .cm-gutterElement`: Individual line numbers (text alignment, padding).
- `.status-bar`: Bottom status bar (background, padding, colors).
- `.cm-keyword`, `.cm-number`, `.cm-string`, `.cm-boolean`, `.cm-variable`, `.cm-comment`, `.cm-operator`, `.cm-type`, `.cm-function`, `.cm-constant`: Syntax highlighting classes.
- `.cm-error-underline`, `.cm-warning-underline`, `.cm-info-underline`, `.cm-hint-underline`: Diagnostic underlines.
- `.cm-highlight`: Highlighted range background.
- `.logs-panel`, `.logs-header`, `.logs-content`: Logs panel components.

### Styling with CSS

Override classes in a custom CSS file loaded after the default:

```css
.cm-content {
  font-family: 'Monaco', monospace !important;
  background-color: #f0f0f0 !important;
  color: #333 !important;
}

.cm-keyword { color: #ff0000 !important; }

.cm-highlight { background-color: #ffff00 !important; }
```

### Styling with Bootstrap Classes

Add Bootstrap classes to the parent container or via props (if extended). For example, in HTML:

```html
<div class="container-fluid p-0">
  <!-- Editor here -->
</div>
```

Use utilities like `h-100`, `bg-dark`, `text-light` for layout and themes.

## Architectural Overview

- **Core Library and Extensions (`:libs`)**: Exports `Editor` React component from core library module and language configs (e.g., Rholang with Tree-Sitter WASM, queries, LSP URL) from extensions module. Manages CodeMirror state, Tree-Sitter for syntax/indents, LSP client via WebSocket, diagnostics/highlights as StateFields/Plugins, RxJS for events.
- **App (`:app`)**: Demo UI with Re-frame for state, multi-file workspace, logs panel. Uses Datascript/Re-posh for symbols/diagnostics.
- **Tests (`:test`)**: Browser tests for units/integration/property-based.
- **Decoupling**: Languages pluggable via map; LSP optional (falls back to basic editor); Tree-Sitter/LSP as WASM.
- **Events**: Internal changes/LSP notifications propagated via RxJS.
- **Performance**: Debounced updates, viewport-only decorations, incremental Tree-Sitter parsing.

## React 19 Compatibility

The library and demo app are compatible with React 19. Development tools like re-frame-10x use the React 18 preload for compatibility, but the core editor component works seamlessly with React 19.

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
