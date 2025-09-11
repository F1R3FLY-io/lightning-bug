# Changelog

All notable changes to Lightning Bug will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
