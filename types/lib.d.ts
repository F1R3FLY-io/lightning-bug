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
  /** Optional callback for content changes, triggered after updates. */
  onContentChange?: (content: string) => void;
  /** Default protocol for file paths (e.g., "inmemory://"). Defaults to "inmemory://". */
  defaultProtocol?: string;
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
  text: string;
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
  pending: Record<number, string | { type: string; uri: string }>;
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
    /** Document URI. */
    uri: string;
    /** Document version at time of diagnostic. */
    version?: number;
    /** Diagnostic message. */
    message: string;
    /** Severity (1: Error, 2: Warning, 3: Info, 4: Hint). */
    severity: number;
    /** Start line (0-based). */
    startLine: number;
    /** Start character (0-based). */
    startChar: number;
    /** End line (0-based). */
    endLine: number;
    /** End character (0-based). */
    endChar: number;
  }>;
  /** Array of symbols from LSP. */
  symbols: Array<{
    /** Document URI. */
    uri: string;
    /** Symbol name. */
    name: string;
    /** Symbol kind (LSP standard). */
    kind: number;
    /** Start line (0-based). */
    startLine: number;
    /** Start character (0-based). */
    startChar: number;
    /** End line (0-based). */
    endLine: number;
    /** End character (0-based). */
    endChar: number;
    /** Selection start line (0-based). */
    selectionStartLine: number;
    /** Selection start character (0-based). */
    selectionStartChar: number;
    /** Selection end line (0-based). */
    selectionEndLine: number;
    /** Selection end character (0-based). */
    selectionEndChar: number;
    /** Parent symbol ID (or null). */
    parent?: number;
  }>;
  /** Current search term. */
  searchTerm: string;
}

/**
 * Event data emitted by the editor's RxJS Observable.
 */
export type EditorEvent =
  | { type: 'ready'; data: {} }
  | { type: 'content-change'; data: { content: string; uri: string } }
  | { type: 'selection-change'; data: { cursor: { line: number; column: number }; selection: { from: { line: number; column: number }; to: { line: number; column: number }; text: string } | null; uri: string } }
  | { type: 'document-open'; data: { uri: string; content: string; language: string; activated: boolean } }
  | { type: 'document-close'; data: { uri: string } }
  | { type: 'document-rename'; data: { oldUri: string; newUri: string } }
  | { type: 'document-save'; data: { uri: string; content: string } }
  | { type: 'lsp-message'; data: { method: string; lang: string; params?: any } }
  | { type: 'lsp-initialized'; data: { lang: string } }
  | { type: 'diagnostics'; data: Array<{ uri: string; version?: number; message: string; severity: number; startLine: number; startChar: number; endLine: number; endChar: number }> }
  | { type: 'symbols'; data: Array<{ uri: string; name: string; kind: number; startLine: number; startChar: number; endLine: number; endChar: number; selectionStartLine: number; selectionStartChar: number; selectionEndLine: number; selectionEndChar: number; parent?: number }> }
  | { type: 'log'; data: { message: string; lang: string } }
  | { type: 'connect'; data: { lang: string } }
  | { type: 'disconnect'; data: { lang: string } }
  | { type: 'lsp-error'; data: { message: string; lang: string } }
  | { type: 'highlight-change'; data: { from: { line: number; column: number }; to: { line: number; column: number } } | null }
  | { type: 'language-change'; data: { uri: string; language: string } }
  | { type: 'scroll'; data: { from: { line: number; column: number }; to: { line: number; column: number } } }
  | { type: 'destroy'; data: {} }
  | { type: 'error'; data: { message: string; operation: string; [key: string]: any } }
  | { type: 'search-term-change'; data: { term: string; uri: string } };

/**
 * Ref interface for imperative methods on the Editor component.
 * All positions are 1-based (line/column starting at 1).
 */
export interface EditorRef {
  /** Returns the full current state (workspace, diagnostics, symbols, etc.). */
  getState(): EditorState;
  /** Returns RxJS observable for subscribing to events. */
  getEvents(): Observable<EditorEvent>;
  /** Returns current cursor position (1-based) for active document. */
  getCursor(): { line: number; column: number };
  /** Sets cursor position for active document (triggers `selection-change` event). */
  setCursor(pos: { line: number; column: number }): void;
  /** Returns current selection range and text (or null if none). */
  getSelection(): { from: { line: number; column: number }; to: { line: number; column: number }; text: string } | null;
  /** Sets selection range for active document (triggers `selection-change` event). */
  setSelection(from: { line: number; column: number }, to: { line: number; column: number }): void;
  /** Opens or activates a document with file path or URI, optional content and language (triggers `document-open`). Reuses if exists, updates if provided. Notifies LSP if connected. If makeActive is false, opens without activating. */
  openDocument(fileOrUri: string, text?: string, language?: string, makeActive?: boolean): void;
  /** Closes the specified or active document (triggers `document-close`). Notifies LSP if open. */
  closeDocument(fileOrUri?: string): void;
  /** Renames the specified or active document (updates URI, triggers `document-rename`). Notifies LSP. */
  renameDocument(newFileOrUri: string, oldFileOrUri?: string): void;
  /** Saves the specified or active document (triggers `document-save`). Notifies LSP via `didSave`. */
  saveDocument(fileOrUri?: string): void;
  /** Returns `true` if editor is initialized and ready for methods. */
  isReady(): boolean;
  /** Highlights a range in active document (triggers `highlight-change` with range). */
  highlightRange(from: { line: number; column: number }, to: { line: number; column: number }): void;
  /** Clears highlight in active document (triggers `highlight-change` with `null`). */
  clearHighlight(): void;
  /** Scrolls to center on a range in active document. */
  centerOnRange(from: { line: number; column: number }, to: { line: number; column: number }): void;
  /** Returns text for specified or active document, or `null` if not found. */
  getText(fileOrUri?: string): string | null;
  /** Replaces entire text for specified or active document (triggers `content-change`). */
  setText(text: string, fileOrUri?: string): void;
  /** Returns file path (e.g., `"/demo.rho"`) for specified or active, or null if none. */
  getFilePath(fileOrUri?: string): string | null;
  /** Returns full URI (e.g., `"inmemory:///demo.rho"`) for specified or active, or `null` if none. */
  getFileUri(fileOrUri?: string): string | null;
  /** Sets the active document if exists, loads content to view, opens in LSP if not. */
  activateDocument(fileOrUri: string): void;
  /** Queries the internal DataScript database with the given query and optional params. */
  query(query: any, params?: any[]): any;
  /** Returns the DataScript connection object for direct access (advanced use). */
  getDb(): any; // DataScript connection object
  /** Retrieves LSP diagnostics for the target file (optional fileOrUri, defaults to active). */
  getDiagnostics(fileOrUri?: string): Array<{message: string; severity: number; startLine: number; startChar: number; endLine: number; endChar: number; version?: number}>;
  /** Retrieves LSP symbols for the target file (optional fileOrUri, defaults to active). */
  getSymbols(fileOrUri?: string): Array<{name: string; kind: number; startLine: number; startChar: number; endLine: number; endChar: number; selectionStartLine: number; selectionStartChar: number; selectionEndLine: number; selectionEndChar: number; parent?: number}>;
  /** Returns the current search term. */
  getSearchTerm(): string;
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
