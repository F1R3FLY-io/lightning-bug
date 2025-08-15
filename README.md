# Lightning Bug

Lightning Bug is a modern, extensible text editor built with ClojureScript and CodeMirror 6. The editor features pluggable language support via Tree-Sitter for syntax highlighting and indentation. It also integrates with LSP for advanced features like diagnostics and symbols. The design is decoupled from specific languages or servers. This allows easy extension for new languages.

## License

This project is licensed under the Apache License 2.0. See the [LICENSE.txt](LICENSE.txt) file for details.

## GitHub Repositories

| Repository                  | URL                                          |
|-----------------------------|----------------------------------------------|
| Lightning Bug              | [https://github.com/f1R3FLY-io/lightning-bug](https://github.com/f1R3FLY-io/lightning-bug) |
| Rholang Tree-Sitter Grammar| [https://github.com/dylon/rholang-rs](https://github.com/dylon/rholang-rs) (use branch: `dylon/comments`) |
| Rholang Language Server    | [https://github.com/f1R3FLY-io/rholang-language-server](https://github.com/f1R3FLY-io/rholang-language-server) |

## Installing Rholang Tree-Sitter Parser from NPM

The Tree-Sitter parser for Rholang is available as an NPM package `@f1r3fly-io/tree-sitter-rholang-js-with-comments`. It is listed as a dependency in `package.json`.

To install the dependencies, run the following command:

```
npm install
```

The postinstall script (`scripts/postinstall.js`) automatically copies the WASM file to the appropriate locations in the project. These locations include `resources/public/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm`, as well as the demo and test directories.

If you need to copy the file manually after installation, run the following command:

```
cp node_modules/@f1r3fly-io/tree-sitter-rholang-js-with-comments/tree-sitter-rholang.wasm resources/public/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm
```

## Installation and Compilation

### Dependencies

Install the Clojure CLI tools. Install Node.js dependencies with `npm install`. Install Clojure dependencies with `clojure -P` (a dry run to fetch dependencies).

### Compiling and Watching Targets

| Target | Description | Compile Command | Watch Command | Release Command |
|--------|-------------|-----------------|---------------|-----------------|
| `:libs` | Core library and extensions | `npx shadow-cljs compile libs` | `npx shadow-cljs watch libs` | `npx shadow-cljs release libs` |
| `:app` | Full development app with Re-frame UI | `npx shadow-cljs compile app` | `npx shadow-cljs watch app` | - |
| `:demo` | Minimal standalone demo | `npm run build:demo` | `npx shadow-cljs watch demo` | - |
| `:test` | Browser tests | - | `npx shadow-cljs watch test` | - |

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

The demo app is a standalone HTML file located at `resources/public/demo/index.html`. It demonstrates importing and using the `Editor` component and `RholangExtension` in a browser environment without a server. The demo loads dependencies via an import map and ESM modules, including the compiled library from `node_modules/@f1r3fly-io/lightning-bug/dist/libs/`. This minimal setup is ideal for quick isolated testing of the core editor component. It is separate from the full Re-frame development app (:app target).

### Building the Demo App

To build the demo app (compiles the library and copies assets), run the build script: `npm run build:demo`.

This installs npm dependencies, compiles the `:libs` target to `dist/libs/`, installs demo-specific npm dependencies, and copies Tree-Sitter WASM and extensions to the demo directory.

Open `resources/public/demo/index.html` directly in a browser (no server needed).

### Serving the Demo App for Development

For live-reloading during development (e.g., to test changes to the library), watch the library and demo: `npx shadow-cljs watch libs demo`. Copy the compiled `dist/libs/` to the demo: `mkdir -p resources/public/demo/dist/libs && cp -r dist/libs/* resources/public/demo/dist/libs/`. Serve the demo directory with a static server (e.g., `npx serve resources/public/demo` or use the built-in Shadow-CLJS server on port 3001). Access it at `http://localhost:3001` (adjust port if needed).

### How the Demo App Works

The demo app is a simple HTML file with embedded JavaScript (ES modules). It defines an import map to resolve dependencies like `react`, `react-dom`, `rxjs`, CodeMirror packages, and `web-tree-sitter` from CDNs or local node_modules. It imports the `Editor` component from `@f1r3fly-io/lightning-bug` and `RholangExtension` from `@f1r3fly-io/lightning-bug/extensions`. It creates a React root and renders the `Editor` with initial props (e.g., language "rholang", languages map with `RholangExtension`). It uses a ref to access imperative methods: checks `isReady()`, subscribes to events via `getEvents()`, and calls `openDocument()` to load content. It logs events to the console for demonstration.

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
    const { Editor } = await import('@f1r3fly-io/lightning-bug');
    const { RholangExtension } = await import('@f1r3fly-io/lightning-bug/extensions');
    const { defaultKeymap } = await import('@codemirror/commands');
    const customExtensions = [defaultKeymap];
    const root = createRoot(document.getElementById('app'));
    const editorRef = React.createRef();
    root.render(React.createElement(Editor, {
      ref: editorRef,
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
          "demo.rho",
          "new x in { x!(\"Hello\") | Nil }",
          "rholang"
        );
        console.log('Text:', editorRef.current.getText());
        console.log('File path:', editorRef.current.getFilePath());
        console.log('File URI:', editorRef.current.getFileUri());
        editorRef.current.openDocument("second.rho", "Nil", "rholang", false); // Example with make-active false
        editorRef.current.setActiveDocument("demo.rho");
        editorRef.current.renameDocument("renamed.rho", "second.rho");
        editorRef.current.setCursor({ line: 1, column: 3 });
        console.log('Cursor after set:', editorRef.current.getCursor());
        editorRef.current.setSelection({ line: 1, column: 1 }, { line: 1, column: 6 });
        console.log('Selection after set:', editorRef.current.getSelection());
        editorRef.current.highlightRange({ line: 1, column: 1 }, { line: 1, column: 6 });
        editorRef.current.clearHighlight();
        editorRef.current.centerOnRange({ line: 1, column: 1 }, { line: 1, column: 6 });
        editorRef.current.setText("setText text");
        console.log('Text after setText:', editorRef.current.getText());
        editorRef.current.setText("setText updated");
        console.log('Text after setText:', editorRef.current.getText());
        editorRef.current.saveDocument();
        console.log('State:', editorRef.current.getState());
        editorRef.current.closeDocument();
        subscription.unsubscribe();
      }
    }, 100);
  })();
</script>
```

This setup allows quick testing of the editor in isolation, without the full Re-frame app.

## Integrating into a React Application

The `Editor` component integrates seamlessly into React-based applications. It provides a high-level API for embedding a text editor with support for syntax highlighting, indentation, LSP features, and more. The component manages its internal CodeMirror instance. It handles state updates, extensions, and event propagation via RxJS.

### Embedding and Initializing the Editor Component

1. Install dependencies. Ensure your project has `react`, `react-dom`, `rxjs`, and `@f1r3fly-io/lightning-bug` installed. For language extensions like Rholang, include `@f1r3fly-io/lightning-bug/extensions`.

   ```
   npm install react react-dom rxjs @f1r3fly-io/lightning-bug
   ```

2. Import the component. In your React file, import the `Editor`:

   ```jsx
   import { Editor } from '@f1r3fly-io/lightning-bug';
   import { RholangExtension } from '@f1r3fly-io/lightning-bug/extensions'; // Optional for Rholang support
   ```

3. Render the component. Use the `Editor` in your JSX. Provide initial props for languages and configurations. Use a React ref to access imperative methods.

   ```jsx
   import React, { useRef } from 'react';

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

   Props include `languages` (map of language configurations as object), and `extraExtensions` (array of additional CodeMirror extensions, optional).

For a complete example, refer to the demo app's `resources/public/demo/index.html`. It uses `React.createElement` to render the `Editor` and demonstrates initialization with a ref.

### Customizing the Editor

Customize the editor using a ref for commands, and RxJS for events. For detailed examples in JavaScript and TypeScript, see the sections below.

Use the imperative API (via ref) to access methods like `openDocument(uri, content?, lang?, makeActive?)`, `setCursor(pos)`, `highlightRange(from, to)`, `getText(uri?)`. See "Public API" for the full list.

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

Key events include `ready` (editor initialized), `content-change` (content updated with {content, uri}), `selection-change` (cursor/selection changed with {cursor, selection, uri}), `document-open` (document opened with {uri, content, language, activated}), `document-close` (document closed with {uri}), `document-rename` (document renamed with {old-uri, new-uri}), `document-save` (document saved with {uri, content}), `lsp-message` (raw LSP message with {method, params, ...}), `lsp-initialized` (LSP connection initialized), `diagnostics` (diagnostics updated as array with uri in each), `symbols` (symbols updated as array with uri in each), `log` (log message with {message}), `connect` (LSP connected), `disconnect` (LSP disconnected), `lsp-error` (LSP error with {code, message}), `highlight-change` (highlight range updated or cleared with {from, to} or null).

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

| Attribute              | Type      | Required | Description                                                                 |
|------------------------|-----------|----------|-----------------------------------------------------------------------------|
| `grammarWasm`          | `string`    | No       | Path to the Tree-Sitter grammar WASM file for syntax parsing.               |
| `highlightQueryPath`   | `string`    | No       | Path to the SCM query file for syntax highlighting captures.                |
| `indentsQueryPath`     | `string`    | No       | Path to the SCM query file for indentation rules.                           |
| `lspUrl`               | `string`    | No       | WebSocket URL for connecting to a Language Server Protocol (LSP) server (optional, enables advanced features like diagnostics and symbols). |
| `extensions`           | `string[]`  | Yes      | Array of strings representing file extensions associated with the language (required, e.g., `[\".rho\"]`). |
| `fileIcon`             | `string`    | No       | String CSS class for the file icon in the UI (optional, e.g., `\"fas fa-code\"`). |
| `fallbackHighlighter`  | `string`    | No       | String specifying the fallback highlighting mode if Tree-Sitter fails (optional, e.g., `\"none\"`). |
| `indentSize`           | `integer`   | No       | Integer specifying the number of spaces for indentation (optional, defaults to 2). |

For the pre-configured Rholang extension, the WASM and query files are copied to `resources/public/extensions/lang/rholang/tree-sitter/` during postinstall.

### Overriding Defaults

The editor includes a default `"text"` language with basic support. To override or add languages, pass a custom `languages` map in the `Editor` props. You can extend existing configurations (e.g., `RholangExtension`) or define new ones.

Example in JavaScript:

```js
import { Editor } from '@f1r3fly-io/lightning-bug';
import { RholangExtension } from '@f1r3fly-io/lightning-bug/extensions';

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

<Editor languages={customLanguages} />
```

In TypeScript, use the provided types for validation.

This configuration is normalized internally (camelCase to kebab-case). Invalid configs throw errors during validation.

## Public API

The public API consists of the `Editor` React component's props, imperative methods accessible via a React ref, and RxJS events emitted for lifecycle and state changes. All types are defined in `types/lib.d.ts` for TypeScript users.

### Editor Component Props

The `Editor` component accepts the following props for initialization and configuration.

| Prop Name          | Type                                      | Required | Description                                                                                            |
|--------------------|-------------------------------------------|----------|--------------------------------------------------------------------------------------------------------|
| `languages`        | `Record<string, LanguageConfig>`          | No       | Map of language keys to their configurations. Merges with built-in defaults like `"text"`.             |
| `extraExtensions`  | `Extension[]`                             | No       | Array of additional CodeMirror extensions (from `@codemirror/*` packages) to extend/override defaults. |

#### LanguageConfig Schema

```typescript
interface LanguageConfig {
  grammarWasm?: string;          // Path to Tree-Sitter grammar WASM file.
  highlightQueryPath?: string;   // Path to SCM query file for syntax highlighting.
  indentsQueryPath?: string;     // Path to SCM query file for indentation rules.
  lspUrl?: string;               // WebSocket URL for LSP server (enables diagnostics/symbols).
  extensions: string[];          // File extensions associated with the language (required, e.g., [".rho"]).
  fileIcon?: string;             // CSS class for file icon (e.g., "fas fa-code").
  fallbackHighlighter?: string;  // Fallback mode if Tree-Sitter fails (e.g., "none").
  indentSize?: number;           // Spaces per indent level (defaults to 2).
}
```

### Imperative Methods (via React Ref)

Use a React ref to access these methods for runtime control. All positions are 1-based (line/column starting at 1).

| Method Name       | Parameters                                                                           | Return Type                                                                                             | Description                                                                                             | Example |
|-------------------|--------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------|---------|
| `getState`        | none                                                                                 | `EditorState`                                                                                           | Returns the full current state (workspace, diagnostics, symbols, etc.).                                 | editor.getState(); |
| `getEvents`       | none                                                                                 | `Observable<{ type: string; data: any }>`                                                               | Returns RxJS observable for subscribing to events.                                                      | editor.getEvents().subscribe(event => console.log(event.type, event.data)); |
| `getCursor`       | none                                                                                 | `{ line: number; column: number }`                                                                      | Returns current cursor position (1-based) for active document.                                          | editor.getCursor(); |
| `setCursor`       | `pos: { line: number; column: number }`                                              | `void`                                                                                                  | Sets cursor position for active document (triggers `selection-change` event).                           | editor.setCursor({ line: 1, column: 3 }); |
| `getSelection`    | none                                                                                 | `{ from: { line: number; column: number }; to: { line: number; column: number }; text: string } \| null` | Returns current selection range and text for active document, or `null` if no selection.                | editor.getSelection(); |
| `setSelection`    | `from`: `{ line: number; column: number }`, `to`: `{ line: number; column: number }` | `void`                                                                                                  | Sets selection range for active document (triggers `selection-change` event).                           | editor.setSelection({ line: 1, column: 1 }, { line: 1, column: 6 }); |
| `openDocument`    | `uri`: `string`, `content?`: `string`, `lang?`: `string`, `makeActive?`: `boolean` = `true` | `void`                                                                                                  | Opens or activates a document with URI, optional content and language (triggers `document-open`). Reuses if exists, updates if provided. Notifies LSP if connected. If makeActive is false, opens without activating. | editor.openDocument("demo.rho", "content", "rholang");<br>editor.openDocument("demo.rho"); // activates existing<br>editor.openDocument("demo.rho", null, null, false); // opens without activating |
| `closeDocument`   | `uri?`: `string`                                                                     | `void`                                                                                                  | Closes the specified or active document (triggers `document-close`). Notifies LSP if open.              | editor.closeDocument();<br>editor.closeDocument("specific-uri"); |
| `renameDocument`  | `newName: string`, `oldUri?`: `string`                                               | `void`                                                                                                  | Renames the specified or active document (updates URI, triggers `document-rename`). Notifies LSP.       | editor.renameDocument("new-name.rho");<br>editor.renameDocument("new-name.rho", "old-uri"); |
| `saveDocument`    | `uri?`: `string`                                                                     | `void`                                                                                                  | Saves the specified or active document (triggers `document-save`). Notifies LSP via `didSave`.          | editor.saveDocument();<br>editor.saveDocument("specific-uri"); |
| `isReady`         | none                                                                                 | `boolean`                                                                                               | Returns `true` if editor is initialized and ready for methods.                                          | editor.isReady(); |
| `highlightRange`  | `from`: `{ line: number; column: number }`, `to`: `{ line: number; column: number }` | `void`                                                                                                  | Highlights a range in active document (triggers `highlight-change` with range).                         | editor.highlightRange({ line: 1, column: 1 }, { line: 1, column: 6 }); |
| `clearHighlight`  | none                                                                                 | `void`                                                                                                  | Clears highlight in active document (triggers `highlight-change` with `null`).                          | editor.clearHighlight(); |
| `centerOnRange`   | `from`: `{ line: number; column: number }`, `to`: `{ line: number; column: number }` | `void`                                                                                                  | Scrolls to center on a range in active document.                                                        | editor.centerOnRange({ line: 1, column: 1 }, { line: 1, column: 6 }); |
| `getText`         | `uri?`: `string`                                                                     | `string \| null`                                                                                         | Returns text for specified or active document, or `null` if not found.                                 | editor.getText();<br>editor.getText("specific-uri"); |
| `setText`         | `text: string`, `uri?`: `string`                                                     | `void`                                                                                                  | Replaces entire text for specified or active document (triggers `content-change`).                      | editor.setText("new text");<br>editor.setText("new text", "specific-uri"); |
| `getFilePath`     | `uri?`: `string`                                                                     | `string \| null`                                                                                         | Returns file path (e.g., `"/demo.rho"`) for specified or active, or null if none.                       | editor.getFilePath();<br>editor.getFilePath("specific-uri"); |
| `getFileUri`      | `uri?`: `string`                                                                     | `string \| null`                                                                                         | Returns full URI (e.g., `"inmemory:///demo.rho"`) for specified or active, or `null` if none.           | editor.getFileUri();<br>editor.getFileUri("specific-uri"); |
| `setActiveDocument` | `uri: string`                                                                      | `void`                                                                                                  | Sets the active document if exists, loads content to view, opens in LSP if not.                         | editor.setActiveDocument("demo.rho"); |

#### EditorState Schema (Return Value for getState)

```typescript
interface EditorState {
  workspace: {
    documents: Record<string, {
      content: string;
      language: string;
      version: number;
      dirty: boolean;
      opened: boolean;
    }>;
    activeUri: string | null;
  };
  cursor: { line: number; column: number };
  selection: { from: { line: number; column: number }; to: { line: number; column: number }; text: string } | null;
  lsp: {
    connection: boolean;
    url: string | null;
    pending: Record<number, string>;
    initialized?: boolean;
    logs: Array<{ message: string }>;
  };
  languages: Record<string, LanguageConfig>;
  diagnostics: Array<{
    document: { uri: string; version?: number };
    diagnostic: { message: string; severity: number; startLine: number; startChar: number; endLine: number; endChar: number };
    type: 'diagnostic';
  }>;
  symbols: Array<{
    symbol: {
      name: string;
      kind: number;
      startLine: number;
      startChar: number;
      endLine: number;
      endChar: number;
      selectionStartLine: number;
      selectionStartChar: number;
      selectionEndLine: number;
      selectionEndChar: number;
      parent?: number;
    };
    type: 'symbol';
  }>;
}
```

### RxJS Events

Subscribe to events via `getEvents()` for reactive updates. Each event is an object with `type` (string) and `data` (object or null).

| Event Type           | Data Schema                                                                   | Description                   |
|----------------------|-------------------------------------------------------------------------------|-------------------------------|
| `ready`              | `null`                                                                        | Editor initialized and ready. |
| `content-change`     | `{ content: string, uri: string }`                                            | Content updated (e.g., typing or setText). |
| `selection-change`   | `{ cursor: { line: number; column: number }; selection: { from: { line: number; column: number }; to: { line: number; column: number }; text: string } \| null, uri: string }` | Cursor or selection changed. |
| `document-open`      | `{ uri: string; content: string; language: string; activated: boolean }`                          | Document opened or activated (activated true if made active). |
| `document-close`     | `{ uri: string }`                                                             | Document closed. |
| `document-rename`    | `{ old-uri: string; new-uri: string }`                                        | Document renamed. |
| `document-save`      | `{ uri: string; content: string }`                                            | Document saved (LSP notified). |
| `lsp-message`        | `{ method: string; params?: any; id?: number; result?: any; error?: any }`    | Raw LSP message received. |
| `lsp-initialized`    | `null`                                                                        | LSP connection initialized. |
| `diagnostics`        | `Array<{ uri: string; message: string; severity: number; startLine: number; startChar: number; endLine: number; endChar: number; version?: number }>` | Diagnostics updated from LSP. |
| `symbols`            | `Array<{ uri: string; name: string; kind: number; startLine: number; startChar: number; endLine: number; endChar: number; selectionStartLine: number; selectionStartChar: number; selectionEndLine: number; selectionEndChar: number; parent?: number }>` | Symbols updated from LSP. |
| `log`                | `{ message: string }`                                                         | Log message from LSP. |
| `connect`            | `null`                                                                        | LSP connected. |
| `disconnect`         | `null`                                                                        | LSP disconnected. |
| `lsp-error`          | `{ code: number; message: string }`                                           | LSP error occurred. |
| `highlight-change`   | `{ from: { line: number; column: number }; to: { line: number; column: number } } \| null` | Highlight range updated or cleared. |

## Customizing the Editor Component

The `Editor` can be customized using props for initial setup and a React ref for imperative control. This allows dynamic interactions without modifying the Lightning Bug source code. Below are step-by-step guides for vanilla JavaScript and TypeScript.

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
         "@f1r3fly-io/lightning-bug": "./node_modules/@f1r3fly-io/lightning-bug/dist/libs/lib.core.js",
         "@f1r3fly-io/lightning-bug/extensions": "./node_modules/@f1r3fly-io/lightning-bug/dist/libs/ext.lang.rholang.js"
       }
     }
   </script>
   <script type="module">
     import React from 'react';
     import { createRoot } from 'react-dom/client';
     import { Editor } from '@f1r3fly-io/lightning-bug';
     import { RholangExtension } from '@f1r3fly-io/lightning-bug/extensions';
   </script>
   ```

2. Render the editor. Create a root and render the `Editor` with props.

   ```javascript
   const root = createRoot(document.getElementById('app'));
   const editorRef = React.createRef();
   root.render(React.createElement(Editor, {
     ref: editorRef,
     languages: { rholang: RholangExtension }
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
       editorRef.current.openDocument('inmemory://test.rho', 'new x in { x!(\"Hello\") }', 'rholang');
       // Get state
       const state = editorRef.current.getState();
       console.log('State:', state.workspace.documents, state.workspace.activeUri);
       // Set content
       editorRef.current.setText('updated');
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
       editorRef.current.renameDocument('new.rho', 'inmemory://old.rho');
       // Save document
       editorRef.current.saveDocument();
       // Highlight range
       editorRef.current.highlightRange({ line: 1, column: 1 }, { line: 1, column: 5 });
       // Clear highlight
       editorRef.current.clearHighlight();
       // Center on range
       editorRef.current.centerOnRange({ line: 2, column: 1 }, { line: 2, column: 10 });
       // Get text
       const text = editorRef.current.getText();
       console.log('Text:', text);
       // Set text (replace all)
       editorRef.current.setText('new text');
       // Switch active
       editorRef.current.setActiveDocument('inmemory://new.rho');
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
   import { Editor, EditorRef, LanguageConfig } from '@f1r3fly-io/lightning-bug';
   import { RholangExtension } from '@f1r3fly-io/lightning-bug/extensions';
   import { Observable } from 'rxjs';

   const languages: Record<string, LanguageConfig> = {
     rholang: RholangExtension
   };

   const root = createRoot(document.getElementById('app')!);
   const editorRef = createRef<EditorRef>();
   root.render(<Editor ref={editorRef} languages={languages} />);
   ```

2. Use ref methods. Type-safe access to methods.

   ```typescript
   const interval = setInterval(() => {
     if (editorRef.current && editorRef.current.isReady()) {
       clearInterval(interval);
       const sub: Subscription = editorRef.current.getEvents().subscribe((event: { type: string; data: any }) => console.log(event.type, event.data));
       editorRef.current.openDocument('inmemory://test.rho', 'new x in { x!(\"Hello\") }', 'rholang');
       const state = editorRef.current.getState();
       console.log('State:', state.workspace.documents, state.workspace.activeUri);
       editorRef.current.setText('updated');
       const cursor = editorRef.current.getCursor();
       console.log('Cursor:', cursor.line, cursor.column);
       editorRef.current.setCursor({ line: 1, column: 5 });
       const selection = editorRef.current.getSelection();
       if (selection) console.log('Selection:', selection.text);
       editorRef.current.setSelection({ line: 1, column: 1 }, { line: 1, column: 6 });
       editorRef.current.closeDocument();
       editorRef.current.openDocument('inmemory://old.rho', 'content', 'rholang');
       editorRef.current.renameDocument('new.rho', 'inmemory://old.rho');
       editorRef.current.saveDocument();
       editorRef.current.highlightRange({ line: 1, column: 1 }, { line: 1, column: 5 });
       editorRef.current.clearHighlight();
       editorRef.current.centerOnRange({ line: 2, column: 1 }, { line: 2, column: 10 });
       const text = editorRef.current.getText();
       console.log('Text:', text);
       editorRef.current.setText('new text');
       editorRef.current.setActiveDocument('inmemory://new.rho');
       sub.unsubscribe();
     }
   }, 100);
   ```

## Styling the Editor

The `Editor` can be styled using CSS classes and Bootstrap utilities without modifying the component. Apply styles via external CSS files or inline styles on the parent container. Below are all customizable CSS classes from the default theme (`main.css` or `demo.css`).

### Customizable CSS Classes

| Class                         | Description                                      |
|-------------------------------|--------------------------------------------------|
| `.code-editor`                | Wrapper for the entire editor (set height/width).|
| `.cm-editor`                  | CodeMirror editor container (background, borders). |
| `.cm-content`                 | Editable content area (font, padding, colors).   |
| `.cm-gutters`                 | Gutter for line numbers (background, alignment).|
| `.cm-lineNumbers .cm-gutterElement` | Individual line numbers (text alignment, padding). |
| `.cm-keyword`                 | Keyword syntax highlighting.                     |
| `.cm-number`                  | Number syntax highlighting.                      |
| `.cm-string`                  | String syntax highlighting.                      |
| `.cm-boolean`                 | Boolean syntax highlighting.                     |
| `.cm-variable`                | Variable syntax highlighting.                    |
| `.cm-comment`                 | Comment syntax highlighting.                     |
| `.cm-operator`                | Operator syntax highlighting.                    |
| `.cm-type`                    | Type syntax highlighting.                        |
| `.cm-function`                | Function syntax highlighting.                    |
| `.cm-constant`                | Constant syntax highlighting.                    |
| `.cm-error-underline`         | Error diagnostic underline.                      |
| `.cm-warning-underline`       | Warning diagnostic underline.                    |
| `.cm-info-underline`          | Info diagnostic underline.                       |
| `.cm-hint-underline`          | Hint diagnostic underline.                       |
| `.cm-highlight`               | Highlighted range background.                    |

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

- Follow the Clojure style guide. Use kebab-case for keys. Group and order imports (core first, local last).
- Use conventional commits (e.g., `feat: add X`, `fix: resolve Y`).
- Add unit, integration, and property-based tests. Ensure 100% coverage for new features. Use `test.check` for props with fixed seeds.
- Consolidate dependencies where possible. Prefer modern libraries.
- Keep comments and docs up-to-date. Add where missing. Do not remove existing unless improving readability.
- Apply security updates. Be mindful in LSP/WebSocket handling.
- Branch from `main` for PRs. Include tests/docs. Reference issues.
- Use labels (bug, enhancement) for issues. Provide repro steps.
- Require 1 approval for reviews. Focus on readability, maintainability, DRY, and separation of concerns.
