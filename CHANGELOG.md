# Changelog

All notable changes to Lightning Bug will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
