# Lightning Bug

Lightning Bug is a modern, extensible text editor built with ClojureScript and CodeMirror 6. The editor features pluggable language support via Tree-Sitter for syntax highlighting and indentation. It also integrates with LSP for advanced features like diagnostics and symbols. The design is decoupled from specific languages or servers. This allows easy extension for new languages.

## License

This project is licensed under the Apache License 2.0. See the [LICENSE.txt](LICENSE.txt) file for details.

## GitHub Repositories

- Lightning Bug: [http://github.com/f1R3FLY-io/lightning-bug](http://github.com/f1R3FLY-io/lightning-bug)
- Rholang Tree-Sitter Grammar: [https://github.com/dylon/rholang-rs](https://github.com/dylon/rholang-rs) (use branch: `dylon/comments`)
- Rholang Language Server: [https://github.com/f1R3FLY-io/rholang-language-server](https://github.com/f1R3FLY-io/rholang-language-server)

## Installing Rholang Tree-Sitter Parser from NPM

The Tree-Sitter parser for Rholang is available as an NPM package `@f1r3fly-io/tree-sitter-rholang-js`. It is listed as a dependency in `package.json`.

To install the dependencies, run the following command:

```
npm install
```

The postinstall script (`scripts/postinstall.sh`) automatically copies the WASM file to the appropriate locations in the project. These locations include `resources/public/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm`, as well as the demo and test directories.

If you need to copy the file manually after installation, run the following command:

```
cp node_modules/@f1r3fly-io/tree-sitter-rholang-js/tree-sitter-rholang.wasm resources/public/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm
```

## Installation and Compilation

### Dependencies

Install the Clojure CLI tools. Install Node.js dependencies with `npm install`. Install Clojure dependencies with `clojure -P` (a dry run to fetch dependencies).

### Compiling and Watching Targets

Use `shadow-cljs` for building. Targets include `:libs` (core library and extensions), `:app` (full development app with Re-frame UI), `:demo` (minimal standalone demo), and `:test` (browser tests).

To compile a target (e.g., `:app`), run `npx shadow-cljs compile app`. To watch a target (e.g., `:app`), run `npx shadow-cljs watch app`. To create a release build (e.g., `:libs`), run `npx shadow-cljs release libs`.

For multiple targets, run `npx shadow-cljs watch libs app test`.

Access the full development app at `http://localhost:3000` during watch (requires watching `:app`).

The `:libs` target compiles to ESM format for modern browser compatibility. Release builds are minified via advanced compilation. The outputs consist of multiple files due to Closure compilation and external dependencies (e.g., CodeMirror, RxJS). For single-file bundles, consider a post-build step with a tool like esbuild or Rollup (not included in this project).

## TypeScript Bindings

TypeScript bindings (.d.ts files) are manually maintained for the core library and extensions. They provide accurate type information for JavaScript/TypeScript consumers. These files are located in the `types/` directory:

- `types/lib.d.ts`: Bindings for the main `Editor` component and related types/interfaces.
- `types/ext.d.ts`: Bindings for language extensions (e.g., `RholangExtension`).

The bindings are referenced in `package.json` via the `"types"` fields in `"exports"`. To keep them updated, change the public API (e.g., add props to `Editor`, new methods on the ref, or updates to exported configs). Then, review and manually adjust the .d.ts files to match. Use tools like GitHub Copilot or an AI assistant (e.g., Grok) to help generate or update the bindings based on the ClojureScript code. Always validate changes with `npm run test:types` to ensure type safety and catch mismatches.

## Running Tests

To run browser tests, execute `npx shadow-cljs watch test`. Open `http://localhost:8021` in a browser to run and view tests.

To validate TypeScript bindings, run `npm run test:types`.

## Development App (:app target)

The development app is a full-featured Re-frame application located under `src/app/`. It includes a multi-file workspace, logs panel, and integration with Datascript/Re-posh for managing diagnostics and symbols. The app serves as the primary environment for developing and testing the editor's UI and features.

To watch, run `npx shadow-cljs watch app` (or include in multi-watch). Access it at `http://localhost:3000`.

Features include file management, search, rename modals, LSP diagnostics in logs panel, and cursor/selection subscriptions.

## Demo App (:demo target)

The demo app is a standalone HTML file located at `resources/public/demo/index.html`. It demonstrates importing and using the `Editor` component and `RholangExtension` in a browser environment without a server. The demo loads dependencies via an import map and ESM modules, including the compiled library from `node_modules/lightning-bug/dist/libs/`. This minimal setup is ideal for quick isolated testing of the core editor component. It is separate from the full Re-frame development app (:app target).

### Building the Demo App

To build the demo app (compiles the library and copies assets), run the build script: `./scripts/build-demo.sh`.

This installs npm dependencies, compiles the `:libs` target to `dist/libs/`, installs demo-specific npm dependencies, and copies Tree-Sitter WASM and extensions to the demo directory.

Open `resources/public/demo/index.html` directly in a browser (no server needed).

### Serving the Demo App for Development

For live-reloading during development (e.g., to test changes to the library), watch the library and demo: `npx shadow-cljs watch libs demo`. Copy the compiled `dist/libs/` to the demo: `mkdir -p resources/public/demo/dist/libs && cp -r dist/libs/* resources/public/demo/dist/libs/`. Serve the demo directory with a static server (e.g., `npx serve resources/public/demo` or use the built-in Shadow-CLJS server on port 3001). Access it at `http://localhost:3001` (adjust port if needed).

### How the Demo App Works

The demo app is a simple HTML file with embedded JavaScript (ES modules). It defines an import map to resolve dependencies like `react`, `react-dom`, `rxjs`, CodeMirror packages, and `web-tree-sitter` from CDNs or local node_modules. It imports the `Editor` component from `lightning-bug` and `RholangExtension` from `lightning-bug/extensions`. It creates a React root and renders the `Editor` with initial props (e.g., language "rholang", languages map with `RholangExtension`). It uses a ref to access imperative methods: checks `isReady()`, subscribes to events via `getEvents()`, and calls `openDocument()` to load content. It logs events to the console for demonstration.

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

## Integrating into a React Application

The `Editor` component integrates seamlessly into React-based applications. It provides a high-level API for embedding a text editor with support for syntax highlighting, indentation, LSP features, and more. The component manages its internal CodeMirror instance. It handles state updates, extensions, and event propagation via RxJS.

### Embedding and Initializing the Editor Component

1. Install dependencies. Ensure your project has `react`, `react-dom`, `rxjs`, and `lightning-bug` installed. For language extensions like Rholang, include `lightning-bug/extensions`.

   ```
   npm install react react-dom rxjs lightning-bug
   ```

2. Import the component. In your React file, import the `Editor`:

   ```jsx
   import { Editor } from 'lightning-bug';
   import { RholangExtension } from 'lightning-bug/extensions'; // Optional for Rholang support
   ```

3. Render the component. Use the `Editor` in your JSX. Provide initial props for content, language, and configurations. Use a React ref to access imperative methods.

   ```jsx
   import React, { useRef } from 'react';

   function MyEditor() {
     const editorRef = useRef(null);

     return (
       <Editor
         ref={editorRef}
         content="initial code"
         language="rholang"
         languages={{ rholang: RholangExtension }}
         onContentChange={(content) => console.log('Content changed:', content)}
       />
     );
   }
   ```

   Props include `content` (initial text as string), `language` (starting language key as string, e.g., `"rholang"`), `languages` (map of language configurations as object), `onContentChange` (callback for content updates as function(content)), and `extraExtensions` (array of additional CodeMirror extensions, optional).

For a complete example, refer to the demo app's `resources/public/demo/index.html`. It uses `React.createElement` to render the `Editor` and demonstrates initialization with a ref.

### Customizing the Editor

Customize the editor using props for initial setup, a ref for commands, and RxJS for events. For detailed examples in JavaScript and TypeScript, see the sections below.

Use the imperative API (via ref) to access methods like `openDocument(uri, content, lang)`, `setCursor(pos)`, `highlightRange(from, to)`. See "Public API" for the full list.

For language customization, override or add languages in the `languages` prop. For example, add LSP URL or change indent size.

For extensions, pass `extraExtensions` for custom CodeMirror extensions (e.g., keymaps, themes).

For styling, use CSS classes like `.cm-editor`, `.cm-content` for theming. See "Styling the Editor".

### Lifecycle of Events

The `Editor` emits events for internal activity (e.g., content changes, LSP notifications) via an RxJS observable from `getEvents()`. Subscribe to react to these events and integrate with your app's state.

Subscribe example:

```jsx
useEffect(() => {
  if (editorRef.current && editorRef.current.isReady()) {
    const subscription = editorRef.current.getEvents().subscribe((event) => {
      console.log(event.type, event.data);
      // Handle events, e.g., update app state on 'content-change'.
    });
    return () => subscription.unsubscribe();
  }
}, [editorRef]);
```

Key events include `ready` (editor initialized), `content-change` (content updated with {content}), `selection-change` (cursor/selection changed with {cursor, selection}), `document-open` (document opened with {uri, language}), `lsp-initialized` (LSP connection initialized), `diagnostics` (diagnostics updated as array), `symbols-update` (symbols updated as array), and `highlight-change` (highlight range updated or cleared with {from, to} or null).

For a practical example, see the demo app's interval-based check for `isReady()` and subscription to events.

Refer to the `:demo` build target for a minimal React integration. It demonstrates embedding, initialization, customization via ref, and event handling.

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

## Language Extensions

Lightning Bug supports pluggable language extensions via the `languages` prop. This prop is a map where keys are language names (strings) and values are configuration objects. This allows adding support for new languages or overriding existing ones.

### Configuration Attributes

Each language configuration can include the following attributes:

- `grammarWasm`: String path to the Tree-Sitter grammar WASM file for syntax parsing.
- `highlightQueryPath`: String path to the SCM query file for syntax highlighting captures.
- `indentsQueryPath`: String path to the SCM query file for indentation rules.
- `lspUrl`: String WebSocket URL for connecting to a Language Server Protocol (LSP) server (optional, enables advanced features like diagnostics and symbols).
- `extensions`: Array of strings representing file extensions associated with the language (required, e.g., `[".rho"]`).
- `fileIcon`: String CSS class for the file icon in the UI (optional, e.g., `"fas fa-code"`).
- `fallbackHighlighter`: String specifying the fallback highlighting mode if Tree-Sitter fails (optional, e.g., `"none"`).
- `indentSize`: Integer specifying the number of spaces for indentation (optional, defaults to 2).

For the pre-configured Rholang extension, the WASM and query files are copied to `resources/public/extensions/lang/rholang/tree-sitter/` during postinstall.

### Overriding Defaults

The editor includes a default `"text"` language with basic support. To override or add languages, pass a custom `languages` map in the `Editor` props. You can extend existing configurations (e.g., `RholangExtension`) or define new ones.

Example in JavaScript:

```js
import { Editor } from 'lightning-bug';
import { RholangExtension } from 'lightning-bug/extensions';

const customLanguages = {
  "rholang": {
    ...RholangExtension, // Start with the default Rholang config
    lspUrl: "ws://custom-server:port", // Override LSP URL
    indentSize: 4 // Override indent size
  },
  "customLang": {
    extensions: [".ext"], // Required
    grammarWasm: "/path/to/custom-grammar.wasm",
    highlightQueryPath: "/path/to/highlights.scm",
    indentsQueryPath: "/path/to/indents.scm",
    lspUrl: "ws://custom-lsp:port",
    fileIcon: "fas fa-file-code",
    fallbackHighlighter: "none",
    indentSize: 2
  }
};

<Editor languages={customLanguages} language="customLang" ... />
```

In TypeScript, use the provided types for validation.

This configuration is normalized internally (camelCase to kebab-case). Invalid configs throw errors during validation.

## Public API

### Editor Component

The `Editor` is a React component exported from the `:libs` target. Props (camelCase in JS) include:

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

- `getState()`: Returns editor state (object with `content`, `language`, `diagnostics`, `symbols`).
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

Available events (`event.type`) include:

- `ready`: Editor initialized.
- `content-change`: Content updated ({content}).
- `selection-change`: Selection/cursor changed ({cursor: {line, column}, selection}).
- `document-open`: Document opened ({uri, language}).
- `document-close`: Document closed ({uri}).
- `document-rename`: Document renamed ({old-uri, new-uri, name}).
- `document-save`: Document saved ({uri, content}).
- `lsp-message`: Raw LSP message ({method, params, ...}).
- `lsp-initialized`: LSP initialized.
- `diagnostics`: Diagnostics updated (array).
- `symbols-update`: Symbols updated (array).
- `log`: Log message ({message}).
- `connect`: LSP connected.
- `disconnect`: LSP disconnected.
- `lsp-error`: LSP error ({code, message}).
- `highlight-change`: Highlight range updated ({from: {line, column}, to: {line, column}}) or `null` on clear.

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

1. Import dependencies. Use an import map or script tags to load React, React DOM, RxJS, and Lightning Bug modules.

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

2. Render the editor. Create a root and render the `Editor` with props.

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

3. Use ref methods. Access methods via the ref after the editor is ready.

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

1. Setup and imports. Use TypeScript with module resolution.

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

2. Use ref methods. Type-safe access to methods.

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

The core library and extensions (`:libs`) export the `Editor` React component from the core library module. They also export language configs (e.g., Rholang with Tree-Sitter WASM, queries, LSP URL) from the extensions module. They manage CodeMirror state, Tree-Sitter for syntax/indents, LSP client via WebSocket, diagnostics/highlights as StateFields/Plugins, and RxJS for events.

The app (`:app`) is a demo UI with Re-frame for state, multi-file workspace, and logs panel. It uses Datascript/Re-posh for symbols/diagnostics.

Tests (`:test`) include browser tests for units, integration, and property-based.

Decoupling ensures languages are pluggable via map. LSP is optional (falls back to basic editor). Tree-Sitter/LSP use WASM.

Events propagate internal changes/LSP notifications via RxJS.

Performance uses debounced updates, viewport-only decorations, and incremental Tree-Sitter parsing.

## React 19 Compatibility

The library and demo app are compatible with React 19. Development tools like re-frame-10x use the React 18 preload for compatibility. The core editor component works seamlessly with React 19.

## Contribution Guidelines

Follow the Clojure style guide. Use kebab-case for keys. Group and order imports (core first, local last).

Use conventional commits (e.g., `feat: add X`, `fix: resolve Y`).

Add unit, integration, and property-based tests. Ensure 100% coverage for new features. Use `test.check` for props with fixed seeds.

Consolidate dependencies where possible. Prefer modern libraries.

Keep comments and docs up-to-date. Add where missing. Do not remove existing unless improving readability.

Apply security updates. Be mindful in LSP/WebSocket handling.

Branch from `main` for PRs. Include tests/docs. Reference issues.

Use labels (bug, enhancement) for issues. Provide repro steps.

Require 1 approval for reviews. Focus on readability, maintainability, DRY, and separation of concerns.
