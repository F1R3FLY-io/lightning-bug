(ns infrastructure.datascript-adapter
  "DataScript-backed implementations of domain repository protocols.

   This adapter wraps the existing lib.db functionality, providing a clean
   interface that conforms to the domain protocols. Each repository record holds
   the workspace `conn` it operates on (multi-editor-workspace refactor: lib.db is
   no longer a global singleton), so the methods destructure `conn` from the record."
  (:require [domain.protocols :as p]
            [lib.db :as db]))

;; =============================================================================
;; Document Repository Implementation
;; =============================================================================

(defrecord DataScriptDocumentRepository [conn]
  p/IDocumentRepository

  ;; === Query Operations ===

  (get-document [{:keys [conn]} uri]
    (let [[text lang] (db/doc-text-lang-by-uri conn uri)]
      (when text
        (let [[_ version] (db/document-id-version-by-uri conn uri)
              [_ _ dirty] (db/doc-text-lang-dirty-by-uri conn uri)
              opened (db/document-opened-by-uri? conn uri)]
          {:uri uri
           :text text
           :language lang
           :version version
           :dirty dirty
           :opened opened}))))

  (get-document-text [{:keys [conn]} uri]
    (db/document-text-by-uri conn uri))

  (get-document-language [{:keys [conn]} uri]
    (db/document-language-by-uri conn uri))

  (get-document-version [{:keys [conn]} uri]
    (let [[_ version] (db/document-id-version-by-uri conn uri)]
      version))

  (get-active-uri [{:keys [conn]}]
    (db/active-uri conn))

  (get-active-document [{:keys [conn]}]
    ;; EXP-007: single coalesced query (preserves the cofx hot-path perf).
    (let [[uri text lang version] (db/active-uri-text-lang-version conn)]
      (when uri
        {:uri uri :text text :language lang :version version})))

  (get-document-summary [{:keys [conn]} uri]
    ;; EXP-007: single coalesced query for the :document-repo/document coeffect.
    (let [[text lang version] (db/doc-text-lang-version-by-uri conn uri)]
      (when text
        {:uri uri :text text :language lang :version version})))

  (list-documents [{:keys [conn]}]
    (db/documents conn))

  (document-opened? [{:keys [conn]} uri]
    (db/document-opened-by-uri? conn uri))

  (list-opened-documents-by-language [{:keys [conn]} language]
    (db/opened-uris-by-lang conn language))

  ;; === Mutation Operations ===

  (create-document! [{:keys [conn]} doc]
    (db/create-documents! conn [(merge {:version 0 :dirty false :opened false} doc)])
    (db/document-id-by-uri conn (:uri doc)))

  (update-document-text! [{:keys [conn]} uri text]
    (db/update-document-text-by-uri! conn uri text))

  (update-document-language! [{:keys [conn]} uri language]
    (when-let [id (db/document-id-by-uri conn uri)]
      (db/update-document-uri-language-by-id! conn id uri language)))

  (increment-version! [{:keys [conn]} uri]
    (db/inc-document-version-by-uri! conn uri))

  (mark-document-opened! [{:keys [conn]} uri]
    (db/document-opened-by-uri! conn uri))

  (mark-document-closed! [{:keys [conn]} uri]
    (db/document-closed-by-uri! conn uri))

  (set-active-document! [{:keys [conn]} uri]
    (db/update-active-uri! conn uri))

  (delete-document! [{:keys [conn]} uri]
    (when-let [id (db/document-id-by-uri conn uri)]
      (db/delete-document-by-id! conn id)))

  (rename-document! [{:keys [conn]} old-uri new-uri]
    (when-let [id (db/document-id-by-uri conn old-uri)]
      (db/update-document-uri-by-id! conn id new-uri))))

;; =============================================================================
;; Diagnostics Repository Implementation
;; =============================================================================

(defrecord DataScriptDiagnosticsRepository [conn]
  p/IDiagnosticsRepository

  (get-diagnostics [{:keys [conn]}]
    (db/diagnostics conn))

  (get-diagnostics-by-uri [{:keys [conn]} uri]
    (db/diagnostics-by-uri conn uri))

  (replace-diagnostics! [{:keys [conn]} uri version diagnostics]
    (let [flat-diags (if (seq diagnostics)
                       (db/flatten-diags diagnostics uri version)
                       [])]
      (db/replace-diagnostics-by-uri! conn uri version flat-diags))))

;; =============================================================================
;; Symbols Repository Implementation
;; =============================================================================

(defrecord DataScriptSymbolsRepository [conn]
  p/ISymbolsRepository

  (get-symbols [{:keys [conn]}]
    (db/symbols conn))

  (get-symbols-by-uri [{:keys [conn]} uri]
    (db/symbols-by-uri conn uri))

  (replace-symbols! [{:keys [conn]} uri symbols]
    (let [flat-symbols (if (seq symbols)
                         (db/flatten-symbols symbols nil uri)
                         [])]
      (db/replace-symbols! conn uri flat-symbols))))

;; =============================================================================
;; Log Repository Implementation
;; =============================================================================

(defrecord DataScriptLogRepository [conn]
  p/ILogRepository

  (get-logs [{:keys [conn]}]
    (db/logs conn))

  (add-log! [{:keys [conn]} log]
    (db/create-logs! conn [log])))

;; =============================================================================
;; Factory Functions
;; =============================================================================

(defn make-document-repository
  "Creates a DataScript-backed document repository over the given workspace conn."
  [conn]
  (->DataScriptDocumentRepository conn))

(defn make-diagnostics-repository
  "Creates a DataScript-backed diagnostics repository over the given workspace conn."
  [conn]
  (->DataScriptDiagnosticsRepository conn))

(defn make-symbols-repository
  "Creates a DataScript-backed symbols repository over the given workspace conn."
  [conn]
  (->DataScriptSymbolsRepository conn))

(defn make-log-repository
  "Creates a DataScript-backed log repository over the given workspace conn."
  [conn]
  (->DataScriptLogRepository conn))
