import * as React from 'react';
import { Extension } from '@codemirror/state';
import { Observable } from 'rxjs';

/**
 * Props interface for the Editor component.
 * Configures languages and additional CodeMirror extensions.
 */
export interface EditorProps {
  /** Map of language keys (strings) to their configurations. Merges with defaults like "text". */
  languages?: Record<string, LanguageConfig>;
  /** Array of additional CodeMirror extensions (e.g., keymaps, themes) to extend/override defaults. */
  extraExtensions?: Extension[];
}

/**
 * Configuration interface for a language extension.
 * Defines paths and settings for Tree-Sitter, LSP, and file handling.
 */
export interface LanguageConfig {
  /** Path to the Tree-Sitter grammar WASM file for syntax parsing. */
  grammarWasm?: string;
  /** Path to the SCM query file for syntax highlighting captures. */
  highlightQueryPath?: string;
  /** Path to the SCM query file for indentation rules. */
  indentsQueryPath?: string;
  /** WebSocket URL for connecting to a Language Server Protocol (LSP) server (enables diagnostics/symbols). */
  lspUrl?: string;
  /** Array of file extensions associated with the language (required, e.g., [".rho"]). */
  extensions: string[];
  /** CSS class for the file icon in the UI (e.g., "fas fa-code"). */
  fileIcon?: string;
  /** Fallback highlighting mode if Tree-Sitter fails (e.g., "none"). */
  fallbackHighlighter?: string;
  /** Number of spaces per indent level (defaults to 2). */
  indentSize?: number;
}

/**
 * Internal state of a document in the workspace.
 */
export interface DocState {
  /** Current text content of the document. */
  content: string;
  /** Language key associated with the document (e.g., "rholang"). */
  language: string;
  /** Version number for LSP synchronization. */
  version: number;
  /** Flag indicating if the document has unsaved changes. */
  dirty: boolean;
  /** Flag indicating if the document is open in LSP. */
  opened: boolean;
}

/**
 * Internal state of an LSP connection for a language.
 */
export interface LspState {
  /** Connection status (true if connected). */
  connection: boolean;
  /** WebSocket URL for the LSP server. */
  url: string | null;
  /** Map of pending request IDs to their types. */
  pending: Record<number, string>;
  /** Flag indicating if the LSP is initialized. */
  initialized?: boolean;
}

/**
 * Full internal state of the editor, accessible via getState().
 */
export interface EditorState {
  /** Workspace containing open documents and active URI. */
  workspace: {
    /** Map of URIs to document states. */
    documents: Record<string, DocState>;
    /** Currently active document URI (or null). */
    activeUri: string | null;
  };
  /** Current cursor position (1-based). */
  cursor: { line: number; column: number };
  /** Current selection range and text (or null if none). */
  selection: { from: { line: number; column: number }; to: { line: number; column: number }; text: string } | null;
  /** LSP connections by language key. */
  lsp: Record<string, LspState>;
  /** Array of log messages from LSP. */
  logs: Array<{ message: string; lang: string }>;
  /** Map of configured languages. */
  languages: Record<string, LanguageConfig>;
  /** Array of diagnostics from LSP. */
  diagnostics: Array<{
    /** Document metadata. */
    document: { uri: string; version?: number };
    /** Diagnostic details. */
    diagnostic: { message: string; severity: number; startLine: number; startChar: number; endLine: number; endChar: number };
    /** Type discriminator. */
    type: 'diagnostic';
  }>;
  /** Array of symbols from LSP. */
  symbols: Array<{
    /** Symbol details. */
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
    /** Type discriminator. */
    type: 'symbol';
  }>;
}

/**
 * Ref interface for imperative methods on the Editor component.
 * All positions are 1-based (line/column starting at 1).
 */
export interface EditorRef {
  /** Returns the full current state (workspace, diagnostics, symbols, etc.). */
  getState(): EditorState;
  /** Returns RxJS observable for subscribing to events. */
  getEvents(): Observable<{ type: string; data: any }>;
  /** Returns current cursor position (1-based) for active document. */
  getCursor(): { line: number; column: number };
  /** Sets cursor position for active document (triggers `selection-change` event). */
  setCursor(pos: { line: number; column: number }): void;
  /** Returns current selection range and text for active document, or `null` if no selection. */
  getSelection(): { from: { line: number; column: number }; to: { line: number; column: number }; text: string } | null;
  /** Sets selection range for active document (triggers `selection-change` event). */
  setSelection(from: { line: number; column: number }, to: { line: number; column: number }): void;
  /** Opens or activates a document with URI, optional content and language (triggers `document-open`). Reuses if exists, updates if provided. Notifies LSP if connected. If makeActive is false, opens without activating. */
  openDocument(uri: string, content?: string, lang?: string, makeActive?: boolean): void;
  /** Closes the specified or active document (triggers `document-close`). Notifies LSP if open. */
  closeDocument(uri?: string): void;
  /** Renames the specified or active document (updates URI, triggers `document-rename`). Notifies LSP. */
  renameDocument(newName: string, oldUri?: string): void;
  /** Saves the specified or active document (triggers `document-save`). Notifies LSP via `didSave`. */
  saveDocument(uri?: string): void;
  /** Returns `true` if editor is initialized and ready for methods. */
  isReady(): boolean;
  /** Highlights a range in active document (triggers `highlight-change` with range). */
  highlightRange(from: { line: number; column: number }, to: { line: number; column: number }): void;
  /** Clears highlight in active document (triggers `highlight-change` with `null`). */
  clearHighlight(): void;
  /** Scrolls to center on a range in active document. */
  centerOnRange(from: { line: number; column: number }, to: { line: number; column: number }): void;
  /** Returns text for specified or active document, or `null` if not found. */
  getText(uri?: string): string | null;
  /** Replaces entire text for specified or active document (triggers `content-change`). */
  setText(text: string, uri?: string): void;
  /** Returns file path (e.g., `"/demo.rho"`) for specified or active, or null if none. */
  getFilePath(uri?: string): string | null;
  /** Returns full URI (e.g., `"inmemory:///demo.rho"`) for specified or active, or `null` if none. */
  getFileUri(uri?: string): string | null;
  /** Sets the active document if exists, loads content to view, opens in LSP if not. */
  setActiveDocument(uri: string): void;
}

/**
 * The main Editor React component.
 * Embeds a CodeMirror-based text editor with pluggable language support.
 * @example
 * import { Editor } from '@f1r3fly-io/lightning-bug';
 * const editorRef = React.createRef<EditorRef>();
 * <Editor ref={editorRef} languages={{ text: { extensions: ['.txt'] } }} />;
 */
export const Editor: React.ForwardRefExoticComponent<EditorProps & React.RefAttributes<EditorRef>>;
