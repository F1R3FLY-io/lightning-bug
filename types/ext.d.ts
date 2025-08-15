/**
 * Configuration interface for the Rholang language extension.
 * Defines paths and settings for Tree-Sitter grammar, queries, LSP, and file handling.
 */
export interface RholangExtensionConfig {
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

/**
 * Pre-configured extension for Rholang language support.
 * Includes Tree-Sitter for highlighting/indentation and optional LSP integration.
 * @example
 * import { RholangExtension } from '@f1r3fly-io/lightning-bug/extensions';
 * <Editor languages={{ rholang: RholangExtension }} />
 */
export const RholangExtension: RholangExtensionConfig;
