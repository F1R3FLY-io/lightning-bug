# Lightning Bug - Browser-Based Text Editor

## Overview

This is a modern, browser-based text editor built with ClojureScript,
specifically designed for editing Rholang code from F1R3FLY.io. It features
syntax highlighting via Tree-Sitter, integration with a WASM-compiled LSP
server, and a responsive UI inspired by IDEs like VS Code. The editor supports
multiple panes for file exploration, symbol navigation, diagnostics, and
logging. It starts with an "untitled" document and allows adding, renaming, and
removing files.

The project adheres to the latest ClojureScript paradigms using shadow-cljs for
builds and hot-reloading. It leverages:
- [re-frame](https://github.com/day8/re-frame) for state management.
- [re-com](https://github.com/day8/re-com) for UI components.
- [Bootstrap](https://getbootstrap.com/) for layout and styling.
- [Datascript](https://github.com/tonsky/datascript) for querying LSP data
  (symbols, diagnostics).
- CodeMirror 6 for the editor core.
- Web-Tree-Sitter for syntax highlighting.
- Font: Fira Code for code readability.
- Icons: Font Awesome for IDE-like visuals.

The design is modular, decoupled from Rholang (configurable for other
languages), and prepared for future extensions like DAP or nREPL.

## Features

- **Editor Pane**: Syntax highlighting with Tree-Sitter (Rholang grammar), line
  numbers, cursor position status bar, auto-indentation, and bracket matching.
- **Workspace File Explorer**: List files with type-based icons; add, remove,
  rename files; click to open in editor.
- **Symbol Tree Navigation**: Hierarchical symbols from LSP; click to navigate;
  icons per symbol kind.
- **Diagnostics Terminal**: LSP errors/warnings with hover feedback; click to
  jump to location.
- **Output Terminal**: Syntax-highlighted logs; filterable and searchable.
- **Toolbar Actions**: Run Agent (simulate execution), Validate (check
  diagnostics), Search (across code/logs).
- **Overlays**: "Agent is Running" and "Validation Success/Failed" messages.
- **Dark Theme**: Navy-blue UI matching the Figma demo.
- **LSP Integration**: WebSocket default (proxied stdio/TCP); falls back to
  basic editing.
- **Search Panel**: Bottom pane for results with highlights.

## Installation

### Prerequisites
- Node.js (v14+)
- Clojure CLI
- Java JDK (v11+)
- (Optional) Emscripten for compiling Tree-Sitter grammar to WASM.

### Setup
1. Clone the repository:
   ```
   git clone <repo-url>
   cd rholang-editor
   ```

2. Install NPM dependencies:
   ```
   npm install
   ```

3. (Optional) Compile Tree-Sitter Grammar:
   - Save the provided `grammar.js` in a directory (e.g., `tree-sitter-rholang`).
   - Install Tree-Sitter CLI: `npm install -g tree-sitter-cli`
   - Install Emscripten: Follow steps in the compilation guide below.
   - Generate and build: `tree-sitter generate && tree-sitter build --wasm`
   - Copy `tree-sitter-rholang.wasm` to `resources/wasm/`.

4. Run Rholang LSP Server (from https://github.com/f1R3FLY-io/rholang-language-server/):
   - Build and run the Rust server (e.g., `cargo run -- --tcp 1234`).
   - Proxy to WebSocket: Use `websockify 8080 localhost:1234`.

## Usage

### Development Mode
- Start the dev server:
  ```
  npx shadow-cljs watch app
  ```
- Open http://localhost:3000 in your browser.
- Edit Rholang code; use toolbar for actions.
- Hot-reloading enabled for changes.

### Production Build
- Compile optimized JS:
  ```
  npx shadow-cljs release app
  ```
- Serve `resources/public/` (e.g., via `npx http-server resources/public`).
- Open http://localhost:8080.

### Key Interactions
- Click files in "Explore" to open.
- Use "Test Agent" for symbol navigation.
- View errors in "Display History"; logs in "Log History".
- Search via toolbar to show results panel.

## Compiling Tree-Sitter Grammar to WASM

1. Install Tree-Sitter CLI: `npm install -g tree-sitter-cli`.
2. Clone Emscripten SDK:
   ```
   git clone https://github.com/emscripten-core/emsdk.git
   cd emsdk
   ./emsdk install latest
   ./emsdk activate latest
   source ./emsdk_env.sh
   cd ..
   ```
3. In grammar dir: `tree-sitter generate && tree-sitter build --wasm`.
4. Copy WASM to project.

(Docker alternative available in project notes.)

## LSP Configuration
- Default: WebSocket at `ws://localhost:8080`.
- Edit `:lsp/url` in `src/app/db.cljs` if needed.
- For other transports: Extend `src/app/lsp/client.cljs`.

## Contributing
- Fork and PR.
- Issues: Report bugs or features.
- Tests: Add via shadow-cljs test runner.

## License
MIT License. See LICENSE file.
