(ns domain.protocols
  "Domain layer protocols defining contracts between application and infrastructure layers.

   This module establishes the hexagonal/clean architecture boundaries by defining
   interfaces (protocols) that infrastructure adapters must implement. This allows:
   - Testability through mock implementations
   - Loose coupling between layers
   - Clear contracts for data flow
   - Easy substitution of implementations")

;; =============================================================================
;; Document Repository Protocol
;; =============================================================================

(defprotocol IDocumentRepository
  "Protocol for document persistence and retrieval operations.
   Implementations handle storage (DataScript, localStorage, etc.)"

  ;; === Query Operations ===

  (get-document [this uri]
    "Retrieves a document by URI. Returns a map with keys:
     :uri, :text, :language, :version, :dirty, :opened
     or nil if not found.")

  (get-document-text [this uri]
    "Retrieves only the text content of a document by URI.
     Returns string or nil if not found.")

  (get-document-language [this uri]
    "Retrieves the language identifier of a document by URI.
     Returns string or nil if not found.")

  (get-document-version [this uri]
    "Retrieves the version number of a document by URI.
     Returns nat-int or nil if not found.")

  (get-active-uri [this]
    "Returns the URI of the currently active document, or nil.")

  (get-active-document [this]
    "Returns the currently active document as a map, or nil.")

  (get-document-summary [this uri]
    "Lightweight coalesced read returning {:uri :text :language :version} or nil.
     Single-query hot path used by re-frame coeffects (EXP-007).")

  (list-documents [this]
    "Returns a collection of all documents as maps.")

  (document-opened? [this uri]
    "Returns true if the document has been opened in the editor (LSP didOpen sent).")

  (list-opened-documents-by-language [this language]
    "Returns a collection of URIs for documents opened in the given language.")

  ;; === Mutation Operations ===

  (create-document! [this doc]
    "Creates a new document. doc should be a map with keys:
     :uri (required), :text, :language, :version, :dirty, :opened
     Returns the created document's entity ID.")

  (update-document-text! [this uri text]
    "Updates the text content of a document by URI.
     Also marks the document as dirty. Returns nil.")

  (update-document-language! [this uri language]
    "Updates the language of a document by URI. Returns nil.")

  (increment-version! [this uri]
    "Increments the document version by 1. Returns new version number.")

  (mark-document-opened! [this uri]
    "Marks a document as opened (LSP didOpen sent). Returns nil.")

  (mark-document-closed! [this uri]
    "Marks a document as closed (LSP didClose sent). Returns nil.")

  (set-active-document! [this uri]
    "Sets the active document by URI. Returns nil.")

  (delete-document! [this uri]
    "Deletes a document by URI. Returns nil.")

  (rename-document! [this old-uri new-uri]
    "Renames a document from old-uri to new-uri. Returns nil."))

;; =============================================================================
;; Diagnostics Repository Protocol
;; =============================================================================

(defprotocol IDiagnosticsRepository
  "Protocol for diagnostic storage and retrieval.
   Diagnostics are linked to documents and versions."

  (get-diagnostics [this]
    "Returns all diagnostics as a collection of maps with keys:
     :uri, :version, :message, :severity, :startLine, :startChar, :endLine, :endChar")

  (get-diagnostics-by-uri [this uri]
    "Returns diagnostics for a specific document URI.")

  (replace-diagnostics! [this uri version diagnostics]
    "Replaces all diagnostics for a document with new ones.
     diagnostics should be a collection of maps with:
     :message, :severity, :startLine, :startChar, :endLine, :endChar
     version can be nil for unversioned diagnostics."))

;; =============================================================================
;; Symbols Repository Protocol
;; =============================================================================

(defprotocol ISymbolsRepository
  "Protocol for document symbol storage and retrieval.
   Symbols represent code structure (functions, classes, etc.)"

  (get-symbols [this]
    "Returns all symbols as a collection of maps.")

  (get-symbols-by-uri [this uri]
    "Returns symbols for a specific document URI.")

  (replace-symbols! [this uri symbols]
    "Replaces all symbols for a document with new ones.
     symbols should be a collection of flattened symbol maps."))

;; =============================================================================
;; LSP Client Protocol
;; =============================================================================

(defprotocol ILspClient
  "Protocol for Language Server Protocol communication.
   Implementations handle WebSocket transport and message serialization."

  ;; === Connection Management ===

  (connect! [this language config]
    "Establishes LSP connection for a language.
     config should contain :url and optional timeout settings.
     Returns a channel with [:ok socket] or [:error reason].")

  (disconnect! [this language]
    "Closes the LSP connection for a language. Returns nil.")

  (connected? [this language]
    "Returns true if LSP is connected for the given language.")

  (initialized? [this language]
    "Returns true if LSP is fully initialized for the given language.")

  (connect-supplier [this language url]
    "Returns a 0-arg supplier fn (for lib.state/load-resource) that establishes the
     connection using this client's state-atom and events. Preserves the resource-managed
     connect flow while keeping callers free of a direct lib.lsp.client dependency.")

  ;; === Document Lifecycle Notifications ===

  (notify-did-open! [this language uri text version]
    "Sends textDocument/didOpen notification.")

  (notify-did-change! [this language uri text version]
    "Sends textDocument/didChange notification.")

  (notify-did-change-incremental! [this language uri changes version]
    "Sends textDocument/didChange with incremental contentChanges (delta sync, EXP-011).")

  (notify-did-close! [this language uri]
    "Sends textDocument/didClose notification.")

  (notify-did-save! [this language uri text]
    "Sends textDocument/didSave notification.")

  (notify-did-rename! [this language old-uri new-uri]
    "Sends workspace/didRenameFiles notification.")

  ;; === Request Operations ===

  (request-symbols! [this language uri]
    "Requests document symbols. Results handled asynchronously via events.")

  (request-shutdown! [this language]
    "Requests graceful shutdown of the language server.")

  (shutdown-all! [this]
    "Requests shutdown of all connected languages (mirrors lib.lsp.client/request-shutdown 1-arity)."))

;; =============================================================================
;; Resource Lifecycle Protocol
;; =============================================================================

(defprotocol IResourceLifecycle
  "Protocol for managing resource lifecycles.
   Ensures proper initialization and cleanup ordering."

  (start! [this]
    "Initializes the resource. Returns a channel with [:ok resource] or [:error reason].")

  (stop! [this]
    "Cleans up the resource. Returns a channel with [:ok] or [:error reason].")

  (started? [this]
    "Returns true if the resource has been started.")

  (restart! [this]
    "Stops and then starts the resource. Returns a channel with [:ok resource] or [:error reason]."))

;; Removed (Phase 2): IEventEmitter — events are an RxJS ReplaySubject managed directly in
;; lib.core/emit-event (with type-aware debounce); no adapter consumer exists.

;; =============================================================================
;; Debounce Coordinator Protocol
;; =============================================================================

(defprotocol IDebounceCoordinator
  "Protocol for centralized debouncing of operations.
   Ensures consistent debounce behavior across the application."

  (debounced-call [this key f delay-ms]
    "Schedules a debounced call identified by key.
     If called again with same key before delay expires, previous call is cancelled.")

  (cancel [this key]
    "Cancels any pending debounced call for the given key.")

  (cancel-all [this]
    "Cancels all pending debounced calls."))

;; Removed (Phase 2): ISyntaxHighlighter — lib.editor.syntax integrates via CodeMirror
;; compartments/effects (EXP-005 viewport cache); a tree-returning protocol does not fit and
;; has no consumer.
;; Removed (Phase 2): IEditorOperations — editor imperative ops are the public JS handle
;; (lib.core useImperativeHandle); app.fx calls it directly and tests mock the :editor/* effects.
;; A CLJS protocol adds no testability and risks API drift.

;; =============================================================================
;; Log Repository Protocol
;; =============================================================================

(defprotocol ILogRepository
  "Protocol for log storage and retrieval."

  (get-logs [this]
    "Returns all logs as a collection of maps with :message and :lang.")

  (add-log! [this log]
    "Adds a log entry. log should have :message and :lang."))
