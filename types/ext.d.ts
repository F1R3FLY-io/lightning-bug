/**
 * Configuration for the Rholang language extension.
 */
export interface RholangExtension {
  /** Path to the Tree-Sitter grammar WASM file for syntax parsing. */
  grammarWasm: string;
  /** Path to the SCM query file for syntax highlighting captures. */
  highlightQueryPath: string;
  /** Path to the SCM query file for indentation rules. */
  indentsQueryPath: string;
  /** WebSocket URL for connecting to the Rholang LSP server (enables diagnostics/symbols). */
  lspUrl: string;
  /** Array of file extensions associated with Rholang (required, e.g., [".rho"]). */
  extensions: string[];
  /** CSS class for the file icon in the UI (e.g., "fas fa-code"). */
  fileIcon: string;
  /** Fallback highlighting mode if Tree-Sitter fails (e.g., "none"). */
  fallbackHighlighter: string;
}
