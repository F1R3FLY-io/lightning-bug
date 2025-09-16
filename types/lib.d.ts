import * as React from 'react';
import { Extension } from '@codemirror/state';
import { Observable } from 'rxjs';
import { Parser } from 'web-tree-sitter';

export type LogLevel = 'trace' | 'debug' | 'info' | 'warn' | 'error' | 'fatal' | 'report';

/**
 * Represents a position in the document (1-based by default).
 */
export interface Position {
  line: number;
  column: number;
}

/**
 * Represents a selection range with start/end positions and selected text.
 */
export interface Selection {
  from: Position;
  to: Position;
  text: string;
}

/**
 * Represents an open document in the workspace.
 */
export interface Document {
  /** URI to the document's location. */
  uri: string;
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
 * Represents the editor workspace.
 */
export interface Workspace {
  /** Map of URIs to document states. */
  documents: Document[];
  /** Currently active document URI (or null). */
  activeUri: string | null;
}

/**
 * Represents a diagnostic entry from LSP.
 */
export interface Diagnostic {
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
}

export interface Symbol {
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
}

/**
 * Internal state of the editor, accessible via getState().
 */
export interface EditorState {
  /** Workspace containing open documents and active URI. */
  workspace: Workspace;
  /** Current cursor position (1-based). */
  cursor: Position;
  /** Current selection range and text (or null if none). */
  selection: Selection | null;
  /** Array of log messages from LSP. */
  logs: Array<{ message: string; lang: string }>;
  /** Array of diagnostics from LSP. */
  diagnostics: Diagnostic[];
  /** Array of symbols from LSP. */
  symbols: Symbol[];
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
 * Configuration for a language extension.
 */
export interface LanguageConfig {
  /** Path or function returning path to Tree-Sitter grammar WASM. */
  grammarWasm?: string | (() => string);
  /** Custom parser function or instance (bypasses grammarWasm). */
  parser?: Parser | (() => Parser) | (() => Promise<Parser>);
  /** Path or function returning path to highlights query. */
  highlightsQueryPath?: string | (() => string);
  /** Raw highlights query string (alternative to path). */
  highlightsQuery?: string;
  /** Path or function returning path to indents query. */
  indentsQueryPath?: string | (() => string);
  /** Raw indents query string (alternative to path). */
  indentsQuery?: string;
  /** URL for LSP server. */
  lspUrl?: string;
  /** File extensions associated with this language. */
  extensions: string[];
  /** Icon for files of this language (CSS class, e.g. `"fas fa-code"`). */
  fileIcon?: string;
  /** Fallback highlighter if Tree-Sitter fails ('none' or 'regex'). */
  fallbackHighlighter?: string;
  /** Indentation size in spaces. */
  indentSize?: number;
}

/**
 * Properties for the Editor component.
 */
export interface EditorProps {
  /** Path or function returning path to Tree-Sitter core WASM. */
  treeSitterWasm?: string | (() => string);
  /** Map of language configurations. */
  languages?: Record<string, LanguageConfig>;
  /** Additional CodeMirror extensions. */
  extraExtensions?: Extension[];
  /** Default protocol for URIs (e.g., 'inmemory://'). */
  defaultProtocol?: string;
  /** Callback for content changes. */
  onContentChange?: (text: string) => void;
}

/**
 * Interface for the Editor instance methods (accessed via ref).
 */
export interface EditorRef {
  /**
   * Returns the full current state (workspace, diagnostics, symbols, etc.).
   * @example editorRef.current.getState()
   * @returns {State} The current editor state.
   */
  getState(): EditorState;

  /**
   * Returns RxJS observable for subscribing to events.
   * @example editorRef.current.getEvents().subscribe(evt => console.log(evt.type, evt.data))
   * @returns {Observable<EditorEvent>} The events subject.
   */
  getEvents(): Observable<EditorEvent>;

  /**
   * Returns current cursor position (1-based) for active document.
   * @example editorRef.current.getCursor()
   * @returns {Position} The cursor position.
   */
  getCursor(): Position;

  /**
   * Sets cursor position for active document (triggers `selection-change` event).
   * @example editorRef.current.setCursor({ line: 1, column: 3 })
   * @param {Position} pos The new cursor position.
   */
  setCursor(pos: Position): void;

  /**
   * Returns current selection range and text for active document, or `null` if no selection.
   * @example editorRef.current.getSelection()
   * @returns {Selection | null} The selection.
   */
  getSelection(): Selection | null;

  /**
   * Sets selection range for active document (triggers `selection-change` event).
   * @example editorRef.current.setSelection({ line: 1, column: 1 }, { line: 1, column: 6 })
   * @param {Position} from Start position.
   * @param {Position} to End position.
   */
  setSelection(from: Position, to: Position): void;

  /**
   * Opens or activates a document with file path or URI, optional content and language (triggers `document-open`).
   * Reuses if exists, updates if provided. Notifies LSP if connected. If fourth param makeActive is false,
   * opens without activating.
   * @example editorRef.current.openDocument("demo.rho", "new x in { x!(\"Hello\") | Nil }", "rholang")
   * @example editorRef.current.openDocument("demo.rho") // activates existing
   * @example editorRef.current.openDocument("demo.rho", undefined, undefined, false) // opens without activating
   * @param {string} fileOrUri File path or URI.
   * @param {string} [text] Initial text content.
   * @param {string} [lang] Language key.
   * @param {boolean} [makeActive=true] Whether to activate the document.
   */
  openDocument(fileOrUri: string, text?: string, lang?: string, makeActive?: boolean): void;

  /**
   * Closes the specified or active document (triggers `document-close`). Notifies LSP if open.
   * @example editorRef.current.closeDocument()
   * @example editorRef.current.closeDocument("specific-uri")
   * @param {string} [fileOrUri] File path or URI to close (defaults to active).
   */
  closeDocument(fileOrUri?: string): void;

  /**
   * Renames the specified or active document (updates URI, triggers `document-rename`). Notifies LSP.
   * @example editorRef.current.renameDocument("new-name.rho")
   * @example editorRef.current.renameDocument("new-name.rho", "old-uri")
   * @param {string} newFileOrUri New file path or URI.
   * @param {string} [oldFileOrUri] Old file path or URI (defaults to active).
   */
  renameDocument(newFileOrUri: string, oldFileOrUri?: string): void;

  /**
   * Saves the specified or active document (triggers `document-save`). Notifies LSP via `didSave`.
   * @example editorRef.current.saveDocument()
   * @example editorRef.current.saveDocument("specific-uri")
   * @param {string} [fileOrUri] File path or URI to save (defaults to active).
   */
  saveDocument(fileOrUri?: string): void;

  /**
   * Returns `true` if editor is initialized and ready for methods.
   * @example editorRef.current.isReady()
   * @returns {boolean} Ready status.
   */
  isReady(): boolean;

  /**
   * Highlights a range in active document (triggers `highlight-change` with range).
   * @example editorRef.current.highlightRange({ line: 1, column: 1 }, { line: 1, column: 5 })
   * @param {Position} from Start position.
   * @param {Position} to End position.
   */
  highlightRange(from: Position, to: Position): void;

  /**
   * Clears highlight in active document (triggers `highlight-change` with `null`).
   * @example editorRef.current.clearHighlight()
   */
  clearHighlight(): void;

  /**
   * Scrolls to center on a range in active document (triggers `scroll` event).
   * @example editorRef.current.centerOnRange({ line: 1, column: 1 }, { line: 1, column: 6 })
   * @param {Position} from Start position.
   * @param {Position} to End position.
   */
  centerOnRange(from: Position, to: Position): void;

  /**
   * Returns text for specified or active document, or `null` if not found.
   * @example editorRef.current.getText()
   * @example editorRef.current.getText("specific-uri")
   * @param {string} [fileOrUri] File path or URI (defaults to active).
   * @returns {string | null} The text content.
   */
  getText(fileOrUri?: string): string | null;

  /**
   * Replaces entire text for specified or active document (triggers `content-change`).
   * @example editorRef.current.setText("new text")
   * @example editorRef.current.setText("new text", "specific-uri")
   * @param {string} text New text content.
   * @param {string} [fileOrUri] File path or URI (defaults to active).
   */
  setText(text: string, fileOrUri?: string): void;

  /**
   * Returns file path (e.g., `"/demo.rho"`) for specified or active, or null if none.
   * @example editorRef.current.getFilePath()
   * @example editorRef.current.getFilePath("specific-uri")
   * @param {string} [fileOrUri] File path or URI (defaults to active).
   * @returns {string | null} The file path.
   */
  getFilePath(fileOrUri?: string): string | null;

  /**
   * Returns full URI (e.g., `"inmemory:///demo.rho"`) for specified or active, or `null` if none.
   * @example editorRef.current.getFileUri()
   * @example editorRef.current.getFileUri("specific-uri")
   * @param {string} [fileOrUri] File path or URI (defaults to active).
   * @returns {string | null} The URI.
   */
  getFileUri(fileOrUri?: string): string | null;

  /**
   * Sets the active document if exists, loads content to view, opens in LSP if not (triggers `document-open`).
   * @example editorRef.current.activateDocument("demo.rho")
   * @param {string} fileOrUri File path or URI.
   */
  activateDocument(fileOrUri: string): void;

  /**
   * Queries the internal DataScript database with the given query and optional params.
   * Returns the result as JS array.
   * @example editorRef.current.query([:find ?uri :where [?e :document/uri ?uri]])
   * @param {any} query The DataScript query.
   * @param {any[]} [params] Optional parameters.
   * @returns {any} Query results.
   */
  query(query: any, params?: any[]): any;

  /**
   * Returns the DataScript connection object for direct access (advanced use).
   * @example editorRef.current.getDb()
   * @returns {any} The DataScript connection.
   */
  getDb(): any;

  /**
   * Retrieves LSP diagnostics for the target file (optional fileOrUri, defaults to active).
   * @example editorRef.current.getDiagnostics()
   * @example editorRef.current.getDiagnostics('inmemory://demo.rho')
   * @param {string} [fileOrUri] File path or URI.
   * @returns {Diagnostic[]} Array of diagnostics.
   */
  getDiagnostics(fileOrUri?: string): Diagnostic[];

  /**
   * Retrieves LSP symbols for the target file (optional fileOrUri, defaults to active).
   * @example editorRef.current.getSymbols()
   * @example editorRef.current.getSymbols('inmemory://demo.rho')
   * @param {string} [fileOrUri] File path or URI.
   * @returns {Symbol[]} Array of symbols.
   */
  getSymbols(fileOrUri?: string): Symbol[];

  /**
   * Returns the current search term.
   * @example editorRef.current.getSearchTerm()
   * @returns {string} The search term.
   */
  getSearchTerm(): string;

  /**
   * Opens the search panel in the editor.
   * @example editorRef.current.openSearchPanel()
   */
  openSearchPanel(): void;

  /**
   * Returns the current log level from taoensso.timbre.
   * @example editorRef.current.getLogLevel()
   * @returns {LogLevel} The log level (e.g., 'info').
   */
  getLogLevel(): LogLevel;

  /**
   * Sets the log level for taoensso.timbre (accepts 'trace', 'debug', 'info', 'warn', 'error', 'fatal', 'report').
   * @example editorRef.current.setLogLevel("debug")
   * @param {LogLevel} level The new log level.
   */
  setLogLevel(level: LogLevel): void;

  /**
   * Shuts down LSP connections for all languages or a specific one.
   * @example editorRef.current.shutdownLsp()
   * @example editorRef.current.shutdownLsp("text")
   * @param {string} [lang] Specific language to shutdown (all if omitted).
   */
  shutdownLsp(lang?: string): void;
}

/**
 * The Lightning Bug Editor component.
 */
export const Editor: React.ForwardRefExoticComponent<EditorProps & React.RefAttributes<EditorRef>>;
