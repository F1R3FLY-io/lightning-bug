# Lightning Bug

[![CI Status](https://github.com/f1R3FLY-io/lightning-bug/actions/workflows/ci.yaml/badge.svg)](https://github.com/f1R3FLY-io/lightning-bug/actions/workflows/ci.yaml)

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

### Setting up GitHub Personal Access Token

To access F1R3FLY.io's NPM packages hosted on GitHub Packages (e.g., `@f1r3fly-io/lightning-bug`), you need to set up a GitHub Personal Access Token (PAT) with the `read:packages` scope.

1. Go to your GitHub account settings: [https://github.com/settings/tokens](https://github.com/settings/tokens).
2. Click "Generate new token" (classic token).
3. Select the `read:packages` scope.
4. Generate the token and copy it.
5. Set the token as the `NODE_AUTH_TOKEN` environment variable before running `npm install`:

   ```
   export NODE_AUTH_TOKEN=your_pat_here
   npm install
   ```

For CI/CD workflows (e.g., GitHub Actions), set `NODE_AUTH_TOKEN` as a secret in your repository settings and reference it in the workflow YAML (e.g., `${{ secrets.GITHUB_TOKEN }}` for public repos, or a custom PAT for private).

The `.npmrc` file is configured to use this token for authentication with the GitHub NPM registry.

For more information about Github's NPM registry, take a look at [Working with the npm registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-npm-registry).

## Installation and Compilation

### Dependencies

Install the Clojure CLI tools. Install Node.js dependencies with `npm install`. Install Clojure dependencies with `clojure -P` (a dry run to fetch dependencies).

### Compiling and Watching Targets

| Target | Description | Compile Command | Watch Command | Release Command |
|--------|-------------|-----------------|---------------|-----------------|
| `:libs` | Core library and extensions | `npm run build:debug` | `npm run watch:libs` | `npm run build:release` |
| `:app` | Full development app with Re-frame UI | `npx shadow-cljs compile app` | `npm run serve:app` | - |
| `:demo` | Minimal standalone demo | `npm run build:demo` | `npm run serve:demo` | - |
| `:test` | Browser tests | - | `npm run serve:test` | - |
| `:karma-test` | Karma tests | `npm run test:debug` | - | - |
| `:karma-test-advanced` | Karma tests against release-compiled library | `npm run test:release` | - | - |

For multiple targets, run `npx shadow-cljs watch libs app test`.

Access the full development app at `http://localhost:3000` during watch (requires watching `:app`).

The `:libs` target compiles to ESM format for modern browser compatibility. Release builds are minified via advanced compilation. The outputs consist of multiple files due to Closure compilation and external dependencies (e.g., CodeMirror, RxJS). For single-file bundles, consider a post-build step with a tool like esbuild or Rollup (not included in this project).

## TypeScript Bindings

TypeScript bindings (.d.ts files) are manually maintained for the core library and extensions. They provide accurate type information for JavaScript/TypeScript consumers. These files are located in the `types/` directory:

- `types/lib.d.ts`: Bindings for the main `Editor` component and related types/interfaces.
- `types/ext.d.ts`: Bindings for language extensions (e.g., `RholangExtension`).
- `types/embedded.tree-sitter.d.ts`: Bindings for the embedded Tree-Sitter WASM URL.
- `types/embedded.rholang.d.ts`: Bindings for the embedded Rholang grammar WASM URL.
- `types/embedded.rholang-queries.d.ts`: Bindings for the embedded Rholang query URLs (highlights and indents).

Type tests (`.test-d.ts` files) validate the bindings using `tsd` and are run via `npm run test:types`.

The bindings are referenced in `package.json` via the `"types"` fields in `"exports"`. To keep them updated, change the public API (e.g., add props to `Editor`, new methods on the ref, or updates to exported configs). Then, review and manually adjust the .d.ts files to match. Use tools like GitHub Copilot or an AI assistant (e.g., Grok) to help generate or update the bindings based on the ClojureScript code. Always validate changes with `npm run test:types` to ensure type safety and catch mismatches.

## Running Tests

To run browser tests interactively, execute `npm run serve:test`. Open `http://localhost:8021` in a browser to run and view tests.

To run headless tests for CI or command-line, execute `npm run test:debug`.

To run headless tests against the release-compiled library, execute `npm run test:release`.

To validate TypeScript bindings, run `npm run test:types`.

## Development App (:app target)

The development app is a full-featured Re-frame application located under `src/app/`. It includes a multi-file workspace, logs panel, and integration with Datascript/Re-posh for managing diagnostics and symbols. The app serves as the primary environment for developing and testing the editor's UI and features.

To watch, run `npm run serve:app` (or include in multi-watch). Access it at `http://localhost:3000`.

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

```javascript
(async () => {
  const React = await import('react');
  const { createRoot } = await import('react-dom/client');
  const { Editor } = await import('@f1r3fly-io/lightning-bug');
  const { RholangExtension } = await import('@f1r3fly-io/lightning-bug/extensions');
  const { keymap } = await import('@codemirror/view');
  const { defaultKeymap } = await import('@codemirror/commands');
  const customExtensions = [keymap.of(defaultKeymap)];
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
      console.log('State:', editorRef.current.getState());
      editorRef.current.setCursor({ line: 1, column: 3 });
      console.log('Cursor:', editorRef.current.getCursor());
      editorRef.current.setSelection({ line: 1, column: 1 }, { line: 1, column: 6 });
      console.log('Selection:', editorRef.current.getSelection());
      console.log('Text:', editorRef.current.getText());
      console.log('File Path:', editorRef.current.getFilePath());
      console.log('File URI:', editorRef.current.getFileUri());
      console.log('Diagnostics:', editorRef.current.getDiagnostics());
      console.log('Symbols:', editorRef.current.getSymbols());
    }
  }, 100);
})();
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

Use the imperative API (via ref) to access methods like `openDocument(uri, text?, lang?, makeActive?)`, `setCursor(pos)`, `highlightRange(from, to)`, `getText(uri?)`. See "Public API" for the full list.

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

Key events include `ready` (editor initialized), `content-change` (content updated with `{text, uri}`), `selection-change` (cursor/selection changed with `{cursor, selection, uri}`), `document-open` (document opened with `{uri, text, language, activated}`), `document-close` (document closed with `{uri}`), `document-rename` (document renamed with `{old-uri, new-uri}`), `document-save` (document saved with `{uri, text}`), `lsp-message` (raw LSP message with `{method, params, ...}`), `lsp-initialized` (LSP connection initialized), `diagnostics` (diagnostics updated as array with uri in each), `symbols` (symbols updated as array with uri in each), `log` (log message with `{message}`), `connect` (LSP connected), `disconnect` (LSP disconnected), `lsp-error` (LSP error with `{code, message}`), `highlight-change` (highlight range updated or cleared with `{from, to}` or null).

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

Each language configuration can include the following attributes. Note that some attributes (e.g., paths) support dynamic resolution via functions for lazy loading or computed values (e.g., data URLs for embedded resources).

| Attribute              | Type                        | Required | Description                                                                 |
|------------------------|-----------------------------|----------|-----------------------------------------------------------------------------|
| `grammarWasm`          | `string \| () => string`    | No       | Path or function returning the path to the Tree-Sitter grammar WASM file for syntax parsing. Optional if `parser` is provided. |
| `parser`               | `(() => Parser \| Promise<Parser>) \| Parser` | No       | Alternative to `grammarWasm`: a function returning a Tree-Sitter Parser instance (sync or async Promise) or the instance directly. Useful for custom or pre-loaded parsers. |
| `highlightQueryPath`   | `string \| () => string`    | No       | Path or function returning the path to the SCM query file for syntax highlighting captures. Alternative: provide `highlightsQuery` directly. |
| `highlightsQuery`      | `string`                    | No       | Direct SCM query string for syntax highlighting (alternative to `highlightQueryPath`). |
| `indentsQueryPath`     | `string \| () => string`    | No       | Path or function returning the path to the SCM query file for indentation rules. Alternative: provide `indentsQuery` directly. |
| `indentsQuery`         | `string`                    | No       | Direct SCM query string for indentation rules (alternative to `indentsQueryPath`). |
| `lspUrl`               | `string`                    | No       | WebSocket URL for connecting to a Language Server Protocol (LSP) server (optional, enables advanced features like diagnostics and symbols). |
| `extensions`           | `string[]`                  | Yes      | Array of strings representing file extensions associated with the language (required, e.g., `[".rho"]`). |
| `fileIcon`             | `string`                    | No       | String CSS class for the file icon in the UI (optional, e.g., `"fas fa-code"`). |
| `fallbackHighlighter`  | `string`                    | No       | String specifying the fallback highlighting mode if Tree-Sitter fails (optional, e.g., `"none"`). |
| `indentSize`           | `integer`                   | No       | Integer specifying the number of spaces for indentation (optional, defaults to 2). |

For the pre-configured Rholang extension, the WASM and query files are copied to `resources/public/extensions/lang/rholang/tree-sitter/` during postinstall. Bundled extensions (e.g., Rholang) may use exported functions like `treeSitterRholangWasmUrl` for data URLs.

### Overriding Defaults

The editor includes a default `"text"` language with basic support. To override or add languages, pass a custom `languages` map in the `Editor` props. You can extend existing configurations (e.g., `RholangExtension`) or define new ones. Paths and WASM can be provided as functions for dynamic resolution (e.g., for embedded data URLs).

Example in JavaScript:

```js
import { Editor } from '@f1r3fly-io/lightning-bug';
import { RholangExtension } from '@f1r3fly-io/lightning-bug/extensions';
import { treeSitterRholangWasmUrl } from '@f1r3fly-io/lightning-bug/extensions/lang/rholang/tree-sitter';
import { highlightsQueryUrl } from '@f1r3fly-io/lightning-bug/extensions/lang/rholang/tree-sitter/queries';

const customLanguages = {
  "rholang": {
    ...RholangExtension, // Start with the default Rholang config
    grammarWasm: treeSitterRholangWasmUrl, // Function returning data URL for WASM
    highlightsQueryPath: highlightsQueryUrl, // Function returning data URL for query
    lspUrl: "ws://custom-server:port", // Override LSP URL
    indentSize: 4 // Override indent size
  },
  "customLang": {
    extensions: [".ext"], // Required
    grammarWasm: () => "/path/to/custom-grammar.wasm", // Function for lazy loading
    highlightsQuery: "(block) @indent", // Direct query string (alternative to path)
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
| `defaultProtocol`  | `string`                                  | No       | Default protocol for file paths (e.g., "inmemory://"). Defaults to "inmemory://".                      |

#### LanguageConfig Schema

```typescript
interface LanguageConfig {
  grammarWasm?: string | (() => string);          // Path or function returning path to Tree-Sitter grammar WASM file. Optional if parser provided.
  parser?: (() => Parser | Promise<Parser>) | Parser;  // Function returning Parser (sync/async) or direct instance. Optional if grammarWasm provided.
  highlightQueryPath?: string | (() => string);   // Path or function returning path to SCM query file for syntax highlighting.
  highlightsQuery?: string;                       // Direct SCM query string for syntax highlighting (alternative to highlightQueryPath).
  indentsQueryPath?: string | (() => string);     // Path or function returning path to SCM query file for indentation rules.
  indentsQuery?: string;                          // Direct SCM query string for indentation rules (alternative to indentsQueryPath).
  lspUrl?: string;               // WebSocket URL for LSP server (enables diagnostics/symbols).
  extensions: string[];          // File extensions associated with the language (required, e.g., [".rho"]).
  fileIcon?: string;             // CSS class for file icon (e.g., "fas fa-code").
  fallbackHighlighter?: string;  // Fallback mode if Tree-Sitter fails (e.g., "none").
  indentSize?: number;           // Spaces per indent level (defaults to 2).
}
```

### Imperative Methods (via React Ref)

Use a React ref to access these methods for runtime control. All positions are 1-based (line/column starting at 1).

| Method Signature | Description | Examples |
|------------------|-------------|----------|
| `activateDocument(fileOrUri: string): void;` | Sets the active document if exists, loads text to view, opens in LSP if not. | <table><tr><td>`editor.activateDocument("demo.rho");`</td></tr></table> |
| `centerOnRange(from: Position, to: Position): void;` | Scrolls to center on a range in active document. | <table><tr><td>`editor.centerOnRange({ line: 1, column: 1 }, { line: 1, column: 6 });`</td></tr></table> |
| `clearHighlight(): void;` | Clears highlight in active document (triggers `highlight-change` with `null`). | <table><tr><td>`editor.clearHighlight();`</td></tr></table> |
| `closeDocument(fileOrUri?: string): void;` | Closes the specified or active document (triggers `document-close`). Notifies LSP if open. | <table><tr><td>`editor.closeDocument();`</td></tr><tr><td>`editor.closeDocument("specific-uri");`</td></tr></table> |
| `getCursor(): Position;` | Returns current cursor position (1-based) for active document. | <table><tr><td>`editor.getCursor();`</td></tr></table> |
| `getDb(): any;` | Returns the DataScript connection object for direct access (advanced use). | <table><tr><td>`const db = editor.getDb();`</td></tr></table> |
| `getDiagnostics(fileOrUri?: string): Diagnostic[];` | Retrieves the LSP diagnostics for the specified or active file. | <table><tr><td>`const diags = editorRef.current.getDiagnostics();`</td></tr><tr><td>`const diags = editorRef.current.getDiagnostics('inmemory://demo.rho');`</td></tr></table> |
| `getEvents(): Observable<EditorEvent>;` | Returns RxJS observable for subscribing to events. | <table><tr><td>`editor.getEvents().subscribe(event => console.log(event.type, event.data));`</td></tr></table> |
| `getFilePath(fileOrUri?: string): string | null;` | Returns file path (e.g., `"/demo.rho"`) for specified or active, or null if none. | <table><tr><td>`editor.getFilePath();`</td></tr><tr><td>`editor.getFilePath("specific-uri");`</td></tr></table> |
| `getFileUri(fileOrUri?: string): string | null;` | Returns full URI (e.g., `"inmemory:///demo.rho"`) for specified or active, or `null` if none. | <table><tr><td>`editor.getFileUri();`</td></tr><tr><td>`editor.getFileUri("specific-uri");`</td></tr></table> |
| `getLogLevel(): LogLevel;` | Returns the current log level as a string ('trace', 'debug', etc.). | <table><tr><td>`editor.getLogLevel();`</td></tr></table> |
| `getSearchTerm(): string;` | Returns the current search term. | <table><tr><td>`editor.getSearchTerm();`</td></tr></table> |
| `getSelection(): Selection | null;` | <table><tr><td>none</td></tr></table> | <table><tr><td>`{ from: { line: number; column: number }; to: { line: number; column: number }; text: string } \| null`</td></tr></table> | Returns current selection range and text for active document, or `null` if no selection. | <table><tr><td>`editor.getSelection();`</td></tr></table> |
| `getState(): EditorState;` | Returns the full current state (workspace, diagnostics, symbols, etc.). | <table><tr><td>`editor.getState();`</td></tr></table> |
| `getSymbols(fileOrUri?: string): Symbol[];` | Retrieves the LSP symbols for the specified or active file. | <table><tr><td>`const syms = editorRef.current.getSymbols();`</td></tr><tr><td>`const syms = editorRef.current.getSymbols('inmemory://demo.rho');`</td></tr></table> |
| `getText(fileOrUri?: string): string | null;` | Returns text for specified or active document, or `null` if not found. | <table><tr><td>`editor.getText();`</td></tr><tr><td>`editor.getText("specific-uri");`</td></tr></table> |
| `highlightRange(from: Position, to: Position): void;` | Highlights a range in active document (triggers `highlight-change` with range). | <table><tr><td>`editor.highlightRange({ line: 1, column: 1 }, { line: 1, column: 6 });`</td></tr></table> |
| `isReady(): boolean;` | Returns `true` if editor is initialized and ready for methods. | <table><tr><td>`editor.isReady();`</td></tr></table> |
| `openDocument(fileOrUri: string, text?: string, language?: string, makeActive?: boolean): void;` | Opens or activates a document with file path or URI, optional text and language (triggers `document-open`). Reuses if exists, updates if provided. Notifies LSP if connected. If makeActive is false, opens without activating. | <table><tr><td>`editor.openDocument("demo.rho", "text", "rholang");`</td></tr><tr><td>`editor.openDocument("demo.rho"); // activates existing`</td></tr><tr><td>`editor.openDocument("demo.rho", null, null, false); // opens without activating`</td></tr></table> |
| `openSearchPanel(): void;` | Opens the search panel in the editor. | <table><tr><td>`editor.openSearchPanel();`</td></tr></table> |
| `query(query: any, params?: any[]): any;` | Queries the internal DataScript database with the given query and optional params. | <table><tr><td>`editor.query('[:find ?uri :where [?e :document/uri ?uri]]');`</td></tr></table> |
| `renameDocument(newFileOrUri: string, oldFileOrUri?: string): void;` | Renames the specified or active document (updates URI, triggers `document-rename`). Notifies LSP. | <table><tr><td>`editor.renameDocument("new-name.rho");`</td></tr><tr><td>`editor.renameDocument("new-name.rho", "old-uri");`</td></tr></table> |
| `saveDocument(fileOrUri?: string): void;` | Saves the specified or active document (triggers `document-save`). Notifies LSP via `didSave`. | <table><tr><td>`editor.saveDocument();`</td></tr><tr><td>`editor.saveDocument("specific-uri");`</td></tr></table> |
| `setCursor(pos: Position): void;` | Sets cursor position for active document (triggers `selection-change` event). | <table><tr><td>`editor.setCursor({ line: 1, column: 3 });`</td></tr></table> |
| `setLogLevel(level: LogLevel): void;` | Sets the log level for taoensso.timbre (accepts 'trace', 'debug', 'info', 'warn', 'error', 'fatal', 'report'). | <table><tr><td>`editor.setLogLevel("debug");`</td></tr></table> |
| `setSelection(from: Position, to: Position): void;` | Sets selection range for active document (triggers `selection-change` event). | <table><tr><td>`editor.setSelection({ line: 1, column: 1 }, { line: 1, column: 6 });`</td></tr></table> |
| `setText(text: string, fileOrUri?: string): void;` | Replaces entire text for specified or active document (triggers `content-change`). | <table><tr><td>`editor.setText("new text");`</td></tr><tr><td>`editor.setText("new text", "specific-uri");`</td></tr></table> |
| `shutdownLsp(lang?: string): void;` | Shuts down LSP connections for all languages or a specific one. | <table><tr><td>`editor.shutdownLsp();`</td></tr><tr><td>`editor.shutdownLsp("text");`</td></tr></table> |

#### EditorState Schema (Return Value for getState)

```typescript
interface EditorState {
  workspace: {
    documents: Record<string, {
      text: string;
      language: string;
      version: number;
      dirty: boolean;
      opened: boolean;
    }>;
    activeUri: string | null;
  };
  cursor: { line: number; column: number };
  selection: { from: { line: number; column: number }; to: { line: number; column: number }; text: string } | null;
  lsp: Record<string, {
    connection: boolean;
    url: string | null;
    pending: Record<number, string>;
    initialized?: boolean;
  }>;
  logs: Array<{ message: string; lang: string }>;
  languages: Record<string, LanguageConfig>;
  diagnostics: Array<{
    uri: string;
    version?: number;
    message: string;
    severity: number;
    startLine: number;
    startChar: number;
    endLine: number;
    endChar: number;
  }>;
  symbols: Array<{
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
  }>;
}
```

### Querying the DataScript Database

The editor exposes the DataScript connection via `getDb()` for advanced querying using DataScript's Datalog language (similar to Datomic but with some differences in schema and queries).

To query, use `query(query, params?)` which runs the query with optional parameters and returns the result as JS array. If params is omitted, an empty array is used.

Example in vanilla JavaScript (no params):

```javascript
const query = '[:find ?text . :where [?a :workspace/active-uri ?uri] [?e :document/uri ?uri] [?e :document/text ?text]]';
const text = editorRef.current.query(query);
console.log('Active text:', text);
```

With params:

```javascript
const query = '[:find ?text . :in $ ?uri :where [?e :document/uri ?uri] [?e :document/text ?text]]';
const params = ['inmemory://demo.rho'];
const text = editorRef.current.query(query, params);
console.log('Specific text:', text);
```

For a more complex query with aggregation (e.g., count open documents):

```javascript
const query = '[:find (count ?e) :where [?e :type :document] [?e :document/opened true]]';
const count = editorRef.current.query(query);
console.log('Open documents count:', count[0][0]); // Returns [[N]], so access [0][0] for the count
```

For even more complex operations like pulling nested data (e.g., hierarchical symbols with parents), use `getDb()` and the DataScript library directly (require 'datascript' in your project, as `query` uses d/q which doesn't support pull).

Example using pull for nested symbols:

```javascript
// Assume datascript is imported as ds
const conn = editorRef.current.getDb();
const query = '[:find ?e :where [?e :symbol/name "parent-symbol"]]'; // Find parent entity ID
const parentId = ds.q(query, ds.db(conn))[0][0];
const pulled = ds.pull(ds.db(conn), '[* {:symbol/parent [*]}]', parentId); // Pull with recursion on parent
console.log('Nested symbol:', pulled);
```

Note: DataScript schema is fixed; see source for attributes like `:document/text`, `:diagnostic/message`, etc.

### RxJS Events

Subscribe to events via `getEvents()` for reactive updates. Each event is an object with `type` (string) and `data` (object or null).

| Event Type           | Data Schema                                                                   | Description                   |
|----------------------|-------------------------------------------------------------------------------|-------------------------------|
| `ready`              | `null`                                                                        | Editor initialized and ready. |
| `content-change`     | `{ content: string, uri: string }`                                            | Content updated (e.g., typing or setText). |
| `selection-change`   | `{ cursor: { line: number; column: number }; selection: { from: { line: number; column: number }; to: { line: number; column: number }; text: string } | null, uri: string }` | Cursor or selection changed. |
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
| `highlight-change`   | `{ from: { line: number; column: number }; to: { line: number; column: number } } | null` | Highlight range updated or cleared. |

## Customizing the Editor Component

The `Editor` can be customized using props for initial setup and a React ref for imperative control. This allows dynamic interactions without modifying the Lightning Bug source code. Below are step-by-step guides for vanilla JavaScript and TypeScript.

### Customizing

Below demonstrates how to override default resource locations with their embedded representations:

1. Import dependencies. Use an import map or script tags to load React, React DOM, RxJS, and Lightning Bug modules.

   ```html
   <script type="importmap">
     {
       "imports": {
         "@f1r3fly-io/lightning-bug": "./node_modules/@f1r3fly-io/lightning-bug/dist/libs/lib.core.js",
         "@f1r3fly-io/lightning-bug/extensions": "./node_modules/@f1r3fly-io/lightning-bug/dist/libs/ext.lang.rholang.js",
         "@f1r3fly-io/lightning-bug/tree-sitter": "./node_modules/@f1r3fly-io/lightning-bug/dist/libs/embedded.tree-sitter.js",
         "@f1r3fly-io/lightning-bug/extensions/lang/rholang/tree-sitter": "./node_modules/@f1r3fly-io/lightning-bug/dist/libs/embedded.rholang.js",
         "@f1r3fly-io/lightning-bug/extensions/lang/rholang/tree-sitter/queries": "./node_modules/@f1r3fly-io/lightning-bug/dist/libs/embedded.rholang-queries.js",
         "@f1r3fly-io/tree-sitter-rholang-js-with-comments": "./node_modules/@f1r3fly-io/tree-sitter-rholang-js-with-comments/dist/tree-sitter-rholang-js.es.js",
         "react": "https://esm.sh/react@19.0.0-rc-f994737d14-20240522",
         "react-dom": "https://esm.sh/react-dom@19.0.0-rc-f994737d14-20240522",
         "react-dom/client": "https://esm.sh/react-dom@19.0.0-rc-f994737d14-20240522/client",
         "rxjs": "https://esm.sh/rxjs@7.8.2",
         "@codemirror/autocomplete": "./node_modules/@codemirror/autocomplete/dist/index.js",
         "@codemirror/commands": "./node_modules/@codemirror/commands/dist/index.js",
         "@codemirror/language": "./node_modules/@codemirror/language/dist/index.js",
         "@codemirror/lint": "./node_modules/@codemirror/lint/dist/index.js",
         "@codemirror/search": "./node_modules/@codemirror/search/dist/index.js",
         "@codemirror/state": "./node_modules/@codemirror/state/dist/index.js",
         "@codemirror/view": "./node_modules/@codemirror/view/dist/index.js",
         "@lezer/common": "./node_modules/@lezer/common/dist/index.js",
         "@lezer/highlight": "./node_modules/@lezer/highlight/dist/index.js",
         "style-mod": "./node_modules/style-mod/src/style-mod.js",
         "w3c-keyname": "./node_modules/w3c-keyname/index.js",
         "crelt": "./node_modules/crelt/index.js",
         "@marijn/find-cluster-break": "./node_modules/@marijn/find-cluster-break/src/index.js",
         "web-tree-sitter": "./node_modules/web-tree-sitter/tree-sitter.js"
       }
     }
   </script>
   <script type="module">
     import * as React from 'react';
     import { createRoot } from 'react-dom/client';
     import { Editor } from '@f1r3fly-io/lightning-bug';
     import { RholangExtension } from '@f1r3fly-io/lightning-bug/extensions';
     import { treeSitterWasmUrl } from '@f1r3fly-io/lightning-bug/tree-sitter';
     import { highlightsQueryUrl, indentsQueryUrl } from '@f1r3fly-io/lightning-bug/extensions/lang/rholang/tree-sitter/queries';
     import { wasm } from '@f1r3fly-io/tree-sitter-rholang-js-with-comments';
   </script>
   ```

2. Render the editor. Create a root and render the `Editor` with props.

   ```javascript
   const root = createRoot(document.getElementById('app'));
   const editorRef = React.createRef();
   root.render(React.createElement(Editor, {
     ref: editorRef,
     treeSitterWasm: treeSitterWasmUrl,
     languages: {"rholang": {
       ...RholangExtension,
       grammarWasm: wasm,
       highlightsQueryPath: highlightsQueryUrl,
       indentsQueryPath: indentsQueryUrl,
     }}
   }));
   ```

3. Await the ready state before proceeding.

   ```javascript
   const waitForReady = (ref) => new Promise(resolve => {
     const check = () => {
       console.log("Checking ready...");
       if (ref.current && ref.current.isReady()) {
         console.log("Is ready");
         resolve();
       } else {
         setTimeout(check, 100);
       }
     };
     check();
   });
   await waitForReady(editorRef);
   ```

4. Use ref methods. Access methods via the ref after the editor is ready.

   ```javascript
   console.log("Document opened and activated");
   console.log('State:', editorRef.current.getState());
   editorRef.current.setCursor({ line: 1, column: 3 });
   console.log('Cursor:', editorRef.current.getCursor());
   editorRef.current.setSelection({ line: 1, column: 1 }, { line: 1, column: 6 });
   console.log('Selection:', editorRef.current.getSelection());
   console.log('Text:', editorRef.current.getText());
   console.log('File Path:', editorRef.current.getFilePath());
   console.log('File URI:', editorRef.current.getFileUri());
   console.log('Diagnostics:', editorRef.current.getDiagnostics());
   console.log('Symbols:', editorRef.current.getSymbols());
   console.log('Search Term:', editorRef.current.getSearchTerm());
   const subscription = editorRef.current.getEvents().subscribe(event => {
     console.log('Event:', event.type, event.data);
   });
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

## Data Management with DataScript and Datalog

Lightning Bug uses DataScript, an in-memory database library for ClojureScript, to manage internal state such as open documents, diagnostics, symbols, and logs. DataScript is inspired by Datomic, a persistent database system, but is designed for client-side use with features like immutable data structures, transactional updates, and efficient querying via Datalog.

### What is Datalog?

Datalog is a declarative query language for relational databases, rooted in logic programming (similar to Prolog). It allows expressing complex queries concisely using rules, patterns, and joins. Unlike SQL, Datalog is side-effect free and focuses on "what" data to retrieve rather than "how" to retrieve it. Queries are written as vectors of clauses (e.g., `:find`, `:where`), making them composable and easy to reason about.

In Lightning Bug, Datalog queries fetch state like the active document's content or all diagnostics without imperative loops or conditionals.

### DataScript vs. Datomic

- **DataScript**: An embeddable, in-memory database for Clojure/ClojureScript. It supports Datalog queries, schema definitions, and transactions. Data is stored as an immutable index, enabling time-travel queries (query past states). In this project, DataScript manages the editor's workspace as a single DB connection (`conn`), with entities for documents (`:type :document`), diagnostics (`:type :diagnostic`), symbols (`:type :symbol`), and logs (`:type :log`). It's lightweight, requires no server, and integrates with Re-frame/Re-posh for reactive UI updates. Emphasis: Client-side focus makes it ideal for browser apps; no persistence needed here, but extensible via IndexedDB if desired.

- **Datomic**: A distributed, persistent database for Clojure, also using Datalog. It emphasizes facts over places (immutable data accrual) and supports historical queries. Differences from DataScript: Server-based, scales horizontally, includes peer libraries for client querying. Lightning Bug uses DataScript for its in-browser simplicity, but the Datalog API is similar, easing migration if needed.

Key similarities: Both use entity-attribute-value (EAV) tuples, Datalog for queries, and transactions for atomic updates. Differences from DataScript: DataScript is ephemeral/in-memory; Datomic is durable/across time.

### Usage in Lightning Bug

DataScript stores editor state in a schema-defined DB (see `lib.db/schema`). Transactions (e.g., `d/transact!`) add/retract facts atomically. Queries (e.g., `d/q`) fetch data efficiently. Re-posh bridges DataScript to Re-frame for reactive subs.

Examples:
- Fetch active content: `(d/q '[:find ?text . :where [?a :workspace/active-uri ?uri] [?e :document/uri ?uri] [?e :document/text ?text]] @conn)`
- Update document: `(d/transact! conn [[:db/add eid :document/text "new"]])`

Public API exposes querying via `query()` and `getDb()` for custom Datalog access.

### Learning References

- **Datalog**:
  - [Wikipedia: Datalog](https://en.wikipedia.org/wiki/Datalog) – Overview and history.
  - [Learn Datalog Today](https://datomic.learn-some.com/) – Interactive tutorial with exercises.
  - "Datalog and Recursive Query Processing" by Todd J. Green et al. – Academic paper on foundations.

- **DataScript**:
  - [GitHub Repo](https://github.com/tonsky/datascript) – Source code and docs.
  - [DataScript Tutorial](https://github.com/kristianmandrup/datascript-tutorial) – Hands-on guide.
  - [DataScript Internals](https://tonsky.me/blog/datascript-internals/) – Blog post by creator Nikita Prokopov.

- **Datomic**:
  - [Official Site](https://www.datomic.com/) – Downloads and resources.
  - [Datomic Docs](https://docs.datomic.com/) – Comprehensive guides and API reference.
  - [Day of Datomic](https://youtube.com/playlist?list=PLZdCLR02grLoMy4TXE4DZYIuxs3Q9uc4i&si=kCZCrCSx8iIUBe_L) – Video tutorials.

Explore these to deepen understanding of query-driven state management in ClojureScript apps like Lightning Bug.

## React 19 Compatibility

The library and demo app are compatible with React 19. Development tools like re-frame-10x use the React 18 preload for compatibility. The core editor component works seamlessly with React 19.

## Supported Platforms

The following operating systems and browsers are tested in the CI workflow and are supported.

### Operating Systems

- macOS
- Windows
- Linux (Ubuntu)

### Browsers

- Chrome
- Firefox
- Edge
- Opera
- Brave
- Safari (on macOS)

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

## Gitignore

The `.gitignore` file blacklists everything by default with `*`, then whitelists specific file patterns like `!/README.md`. Directories are included with `!*/` to allow whitelisting files inside them. Additional blacklists (e.g., `/resources/public/js/test/*`) and whitelists can follow as necessary. If a new file is added that is not included in the whitelisted patterns, a new rule to include it must be added to the `.gitignore` (if a new file is not staged in your git changes, this almost certainly is why).

## Release Process

To release a new version of the library:

1. Update `CHANGELOG.md` with the new version section (e.g., `[X.Y.Z] - YYYY-MM-DD`) and list changes under appropriate categories (Added, Changed, Fixed, etc.). Follow the [Keep a Changelog](https://keepachangelog.com/en/1.0.0/) format.
2. Update `package.json` with the new version number.
3. Commit the changes with a message like "Prepare vX.Y.Z release".
4. Tag the release: `git tag vX.Y.Z`.
5. Push the tag: `git push origin vX.Y.Z`. This triggers the GitHub Actions workflow to build and publish the package to GitHub Packages.
6. Verify the release on GitHub and in the NPM registry (under `@f1r3fly-io/lightning-bug`).

This project adheres to [Semantic Versioning (SemVer)](https://semver.org/). SemVer uses the format MAJOR.MINOR.PATCH to communicate changes in software. The key rules are:

- **MAJOR**: Increment when making incompatible API changes (e.g., 1.0.0 to 2.0.0). Reset MINOR and PATCH to 0.
- **MINOR**: Increment for backward-compatible new functionality or deprecations (e.g., 1.0.0 to 1.1.0). Reset PATCH to 0; may include patch-level changes.
- **PATCH**: Increment for backward-compatible bug fixes (e.g., 1.0.0 to 1.0.1). No reset needed for other components.

**Pre-release versions** are denoted by appending a hyphen and identifiers (e.g., 1.0.0-alpha, 1.0.0-alpha.1), indicating instability and lower precedence than the normal version. **Build metadata** is denoted by appending a plus sign and identifiers (e.g., 1.0.0+20130313144700), which does not affect version precedence.

**Handling changes**:
- Breaking changes require a MAJOR increment.
- New features (backward-compatible) require a MINOR increment.
- Bug fixes (backward-compatible) require a PATCH increment.

Version 0.y.z is for initial development, with potential for any changes, while 1.0.0 marks a stable public API. Once released, version contents must not be modified; changes require a new version.
