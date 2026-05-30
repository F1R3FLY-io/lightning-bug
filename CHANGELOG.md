# Changelog

All notable changes to Lightning Bug will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **Instantiable multi-editor Workspaces.** New `createWorkspace()` and
  `EditorWorkspaceProvider` exports, plus `workspace` and `uri` props on `<Editor>`. Multiple
  editors can share one Workspace (its open documents, projects, loaded grammars/parsers, and
  LSP connections) or use fully isolated Workspaces. An `<Editor>` with no `workspace` prop
  resolves to a shared process-default Workspace (a `defonce` delay) that survives React
  re-renders and dev hot-reloads. (`lib.workspace`.)
- **Reactive same-file sync for split panes.** When two or more editors in one Workspace show
  the SAME file, edits propagate live between them (RxJS), Google-Docs-style, with each pane
  keeping its own cursor/selection/scroll (selection-mapping + echo suppression). Editing the
  same file in different panes also produces a single coalesced backend sync. (`lib.workspace.doc-sync`.)
- **Projects model.** `lib.db` gains projects (`:project/id`, `:project/root`, `:project/name`,
  and a `:document/project` ref); documents map to a project by longest-URI-under-root, with
  `create-projects!`/`project-for-uri`/`documents-by-project`/`project-of-uri`.
- Types (`types/lib.d.ts`): `createWorkspace`, `EditorWorkspaceProvider`, an opaque `Workspace`
  handle, and `workspace?`/`uri?` on `EditorProps`. The former state-shape `Workspace` (the
  `getState().workspace` payload) is renamed `WorkspaceSnapshot`.
- `app.system`: a dependency-injection container holding the DataScript-backed
  repositories, injectable in tests via `set-system!`/`reset-system!` (delivers the
  testability the `app.cofx`/`app.fx` docstrings always promised).
- `lib.editor.runtime`: namespace extracted from `lib.core` (event emission, CodeMirror
  extension assembly, document activation, and the LSP didOpen lifecycle).
- Test coverage: `infrastructure.datascript-adapter` (repository delegation + EXP-007
  coalesced-query regression guard), `app.system` (the DI seam), and `app.languages`.

### Changed

- **Eliminated the module-global singletons in favor of per-Workspace instances.** The global
  `lib.db/conn`, `lib.state/resources` (tree-sitter parsers + LSP sockets), the LSP connection
  state, and the runtime `pending-idle-syncs`/`pending-lsp-changes` are now owned by an
  instantiable `Workspace`. The `conn` is threaded explicitly through all `lib.db` functions
  (the global `defonce conn` is removed); resources and LSP state are per-Workspace, so two
  Workspaces are fully isolated. Single-`<Editor>` behavior is unchanged (verified by the full
  pre-existing suite passing via the default Workspace).
- **One LSP connection per language per Workspace.** Split panes over one file share a single
  `didOpen` and a single monotonic `didChange` stream (one coalesced producer per file); edits
  made in ANY pane reach the language server. LSP auto-reconnect with exponential backoff is
  active by default (graceful shutdown suppresses it).
- **Wired the three formerly dead abstractions:** the `lib.lsp` keyword finite-state machine
  (`lib.lsp.fsm`) is now the single source of truth for connection state; `app.languages` is
  the demo's language registry/source-of-truth; and `lib.lifecycle` (now per-instance) performs
  the per-editor ordered resource teardown on unmount.
- Completed the hexagonal architecture migration: `app.cofx`/`app.fx` and `lib.core`'s
  ~16 LSP call sites now go through `domain.protocols` (`IDocumentRepository`,
  `ILspClient`) via dependency injection; `lib.lsp.connection-manager/ConnectionManager`
  is the live per-editor LSP client. EXP-007 coalesced-query performance preserved
  (benchmark-verified, no regression).
- `lib/core.cljs` reduced from 1158 to 728 lines (−37%) by extracting `lib.editor.runtime`
  (behavior-preserving byte-exact move; no benchmark regression).
- Consolidated `get-lang-from-ext` into `lib.utils` as the single source of truth.
- Documented the `lib.db` query-naming convention (`document-*` single-attribute vs
  `doc-*`/`active-uri-*` coalesced accessors).

### Fixed

- Re-enabled the Rholang par-operator (`|`) indentation tests: corrected their
  expectations to the branch-alignment semantics (`indents.scm` `(par "|" @branch)`) and
  refreshed stale APIs; `calculate-indent` itself was already correct.
- README WASM-setup docs now reference `npm run prepare:all`; removed the nonexistent
  `scripts/postinstall.js` reference and dropped it from `package.json` `files`.
- Declared previously-transitive test dependencies (`clojure.string`/`clojure.set`/`reagent.core`).

### Removed

- Dead code: orphaned `app.utils`; superseded `lib.utils/debounce`; two never-dispatched
  Re-frame effects (`:timer/debounced-dispatch`, `:editor/with-highlight`); the redundant
  `infrastructure.lsp-adapter`; the unused `domain.entities`; and three never-implemented
  protocols (`IEventEmitter`, `ISyntaxHighlighter`, `IEditorOperations`).

## [0.7.7] - 2025-10-28

### Added

- Complete Tree-sitter query files for Rholang language support
  - Added `folds.scm` for code folding
  - Added `injections.scm` for language injection support
  - Added `locals.scm` for scope and variable tracking
  - Added `textobjects.scm` for semantic navigation

### Changed

- Enhanced `highlights.scm` with better semantic highlighting for Rholang
- Improved `indents.scm` with more comprehensive indentation rules

### Fixed

- Fixed editor crash when switching between agent versions in Embers integration
  - Resolved TypeError: "l.length is not a function"
  - Changed improper method call `(.length current-doc)` to idiomatic `(count current-doc)`
  - Bug occurred during version switching in the agent editor

## [0.7.6] - 2025-10-06

### Changed

- Debounces several more event types to avoid issues with rapid reloading during development of Vite/React apps.

### Fixed

- Correctly reloads Tree-Sitter queries on HMR and related reloads in React-based Vite applications.

## [0.7.5] - 2025-09-30

### Changed

- Waits for a document to be opened before initializing the respective syntax (it no longer initializes `text` on initialization).
- Ensures only one instance of each resource type is loaded at a time.
- Improves parallelism of loading resources.

## [0.7.4] - 2025-09-16

### Changed

- Updates CI to test against `ubuntu-latest`, `macos-latest`, and `windows-latest`
- Updates CI to test against `chrome`, `firefox`, `edge`, `opera`, `brave`, and `safari` (on `macos-latest`)
- Consolidates and cleans up the CI logic a bit.

### Fixed
- Updates the core library to replay all events for new subscribers which fixes some browser/operating system integration issues.

## [0.7.3] - 2025-09-12

### Added

- `scripts/prepare-app.js` and `scripts/prepare-test.js` to prepare the `app` and `test` build targets, respectively.

### Removed

- `scripts/postinstall.js` since its logic is only needed for development and not by clients. Its logic has been split into `scripts/prepare-app.js` and `scripts/prepare-test.js`.

## [0.7.2] - 2025-09-12

### Changed

- Optionally disables `postinstall` script with environment variable `SKIP_LIGHTNING_BUG_POSTINSTALL`

## [0.7.1] - 2025-09-11

### Changed

- Improves LSP shutdown request logic.
- Improves log messages (superficial change for debuggability)

## [0.7.0] - 2025-09-10

### Added

- New API methods to `setLogLevel` and `getLogLevel` for the Editor.
- New API method to `shutdownLsp` connections (useful for shutdown hooks).

### Fixed

- Incorrect state reset which fixed some tests but broke the Embers frontend.
- All known Vite HMR (Hot Module Reload) issues, particularly those related to LSP communication.

## [0.6.6] - 2025-09-09

### Changed

- Sets `"type": "module"` in `package.json` so NPM understands the generated sources are ESM modules.
- Removes partial support for source maps from Karma tests since `shadow-cljs` will not generate them for `:karma` targets.
- Renames build targets `karma-test` to `karma-test-debug` and `karma-test-advanced` to `karma-test-release`

## [0.6.5] - 2025-09-08

### Added

- Linter integration with `clj-kondo`, `eastwood`, `splint`, and `kibit`.

### Changed

- `resources/public/js/tree-sitter.wasm` and `resources/public/extensions/` are no longer copied to the demo app.
- Replaces `const ... = await import('...');` statements in demo app with equivalent ESM imports `import ... from '...';`
- Improves error handling in sources and tests.

## [0.6.4] - 2025-09-08

### Fixed

- Use of incorrect WASM path on Editor initialization.

## [0.6.3] - 2025-09-05

### Changed

- Updates TypeScript bindings to include function parameter type as value to `treeSitterWasm`.

## [0.6.2] - 2025-09-05

### Changed

- Renames `highlightQueryPath` to `highlightsQueryPath` in TypeScript bindings

## [0.6.1] - 2025-09-05

### Changed

- Includes `scripts/utils.js` in NPM package

## [0.6.0] - 2025-09-05

### Added

- Support for embedded resources (WASM files, Tree-Sitter SCM query files, and Tree-Sitter parsers).

### Changed

- Made the Node scripts more descriptive and useful, and cleaned them up a bit.
- Test files are now excluded from NPM package.
- Demo app now loads embedded resources.

## [0.5.1] - 2025-09-01

### Added

- Script to strip the occasionally generated, erroneous statement `goog=goog||{};` from the release distribution before publishing artifacts.
- A sanity test that launches the demo app in a headless browser and ensures it loads correctly for both the unoptimized and fully optimized distributions.

### Changed

- The erroneous statement `goog=goog||{};` which caused the error `goog is not defined` is now stripped from the fully-optimized distribution before publication.
- The sanity test is now run in the CI after the automated tests complete for both the unoptimized and fully-optimized jobs.
- All dependencies have been updated.
- On a tagged release, the CI now uploads the same artifact that was tested against in `test-headless-advanced`. This makes it less likely that a bug can slip through.

## [0.5.0] - 2025-08-31

### Added

- Undo/Redo functionality.
- Tab indentation and Shift+Tab dedentation.
- Find and replace.
- Unload hook to ensure LSP server connection is cleanly terminated.

### Changed

- `go`-blocks now return Rust-like `Result` pairs consisting of either the keyword `:ok` or `:error` followed by either the expected return value for `:ok` or an instance of `js/Error` for `:error`. For example: `[:ok true]` or `[:error (js/Error. "Failed to perform some action" #js {:cause e})]`. Exceptions are also chained to improve traceability across asyncrhonous contexts and the chained causes are printed at the end of failing tests.
- Improved debuggability of headless tests.

## [0.4.1] - 2025-08-27

### Added

- Added new build target `karma-test-advanced` to run headless tests against release-compiled library.
- Unload hook to ensure LSP server connection is cleanly terminated.

### Changed

- Merges `.github/workflows/release-npm-package.yaml` into `.github/workflows/ci.yaml`.

## [0.4.0] - 2025-08-27

### Added

- Added `getDiagnostics` and `getSymbols` API methods to retrieve LSP diagnostics and symbols for a file.
- Added `defaultProtocol` prop to Editor for configuring the default URI protocol.
- Added `test:headless` `npm` script to execute the browser tests in a headless instance of Chrome.
- Added `.github/workflows/ci.yaml` to run the tests against PRs and merges to `main`

### Changed

- Public API methods now accept file paths or URIs for document identifiers. Paths are prepended with a configurable default protocol (defaults to `"inmemory://"`). Renamed parameters to `fileOrUri`.
- Enabled conditional spec validation based on build mode (enabled in dev, disabled in release).
- Replaced manual predicate checks with spec validations, using s/explain-str for detailed errors.
- Runs the headless browser tests before releasing a new NPM package.

### Fixed

- All browser tests now pass.

## [0.3.0] - 2025-08-20

### Changed

- Replaced map-based queries with complete integration with DataScript (where sensible).

### Added

- Added public API methods to both query the internal DataScript database and retrieve its connection.

### Fixed

- Removed the need to stub `goog` at run time.

## [0.2.2] - 2025-08-18

### Changed
- Added Google Closure bindings for minification.

### Fixed
- Automatic indentation in the demo app.
- LSP integration in the demo app.

## [0.2.0] - 2025-08-14

### Added
- Added full imperative API for the `Editor` component via React ref, including methods like `getState()`, `getEvents()`, `openDocument()`, `closeDocument()`, `renameDocument()`, `saveDocument()`, `getText()`, `setText()`, `getCursor()`, `setCursor()`, `getSelection()`, `setSelection()`, `highlightRange()`, `clearHighlight()`, `centerOnRange()`, `getFilePath()`, `getFileUri()`, `setActiveDocument()`, and `isReady()`.
- Added RxJS event emission for lifecycle and state changes: `ready`, `content-change`, `selection-change`, `document-open`, `document-close`, `document-rename`, `document-save`, `lsp-message`, `lsp-initialized`, `diagnostics`, `symbols`, `log`, `connect`, `disconnect`, `lsp-error`, and `highlight-change`.
- Added `extraExtensions` prop to `Editor` for passing additional CodeMirror extensions.
- Added pluggable language configurations via `languages` prop, with validation and normalization (camelCase to kebab-case keys).
- Added fallback to basic text editor behavior when no LSP server is available.
- Added datascript integration for managing diagnostics and symbols, with Posh for reactive queries.
- Added debounce logic for LSP `didChange` notifications and UI updates to improve performance.
- Added TypeScript bindings (.d.ts files) for core library and extensions, with type tests via `tsd`.
- Added demo app build script and standalone HTML for quick testing without server.
- Added detailed README sections on installation, compilation, tests, demo app, integration, customization, public API, events, styling, and architecture.

### Changed
- Updated Tree-Sitter grammar to support comments (using branch `dylon/comments` from `rholang-rs`).
- Normalized language keys to strings, converting keywords if provided.
- Refactored state management to use Reagent atoms and Reagent components for better React integration.
- Consolidated dependencies, preferring modern alternatives (e.g., React 19 compatibility).
- Grouped imports by package and ordered lexicographically, with core first and local last.
- Maintained integration with `re-posh` for database transactions and queries, conforming to schema.
- Handled events asynchronously with debounce where appropriate to avoid UI sluggishness.
- Updated demo app to demonstrate full API usage, including ref methods and event subscription.
- Ensured all public API methods are annotated and exported correctly.

### Fixed
- Fixed potential issues with unmounted views by checking `view-ref` in imperative methods.
- Fixed LSP connection handling to prevent sends before initialization.
- Fixed indentation and highlighting for Rholang-specific constructs using updated queries.
- Fixed potential nil refs in error boundary and logs components.
- Fixed missing Posh atom attachment after Datascript transactions.

## [0.1.3] - 2025-06-01
- Initial release with core editor component, Tree-Sitter for Rholang, LSP client, and basic UI.

## [0.1.0] - 2025-05-01
- Project initialization with basic structure and dependencies.
