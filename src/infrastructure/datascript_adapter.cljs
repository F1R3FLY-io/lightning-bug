(ns infrastructure.datascript-adapter
  "DataScript-backed implementations of domain repository protocols.

   This adapter wraps the existing lib.db functionality, providing a clean
   interface that conforms to the domain protocols."
  (:require [domain.protocols :as p]
            [lib.db :as db]))

;; =============================================================================
;; Document Repository Implementation
;; =============================================================================

(defrecord DataScriptDocumentRepository []
  p/IDocumentRepository

  ;; === Query Operations ===

  (get-document [_this uri]
    (let [[text lang] (db/doc-text-lang-by-uri uri)]
      (when text
        (let [[_ version] (db/document-id-version-by-uri uri)
              [_ _ dirty] (db/doc-text-lang-dirty-by-uri uri)
              opened (db/document-opened-by-uri? uri)]
          {:uri uri
           :text text
           :language lang
           :version version
           :dirty dirty
           :opened opened}))))

  (get-document-text [_this uri]
    (db/document-text-by-uri uri))

  (get-document-language [_this uri]
    (db/document-language-by-uri uri))

  (get-document-version [_this uri]
    (let [[_ version] (db/document-id-version-by-uri uri)]
      version))

  (get-active-uri [_this]
    (db/active-uri))

  (get-active-document [this]
    (when-let [uri (p/get-active-uri this)]
      (p/get-document this uri)))

  (list-documents [_this]
    (db/documents))

  (document-opened? [_this uri]
    (db/document-opened-by-uri? uri))

  (list-opened-documents-by-language [_this language]
    (db/opened-uris-by-lang language))

  ;; === Mutation Operations ===

  (create-document! [_this doc]
    (db/create-documents! [(merge {:version 0 :dirty false :opened false} doc)])
    (db/document-id-by-uri (:uri doc)))

  (update-document-text! [_this uri text]
    (db/update-document-text-by-uri! uri text))

  (update-document-language! [_this uri language]
    (when-let [id (db/document-id-by-uri uri)]
      (db/update-document-uri-language-by-id! id uri language)))

  (increment-version! [_this uri]
    (db/inc-document-version-by-uri! uri))

  (mark-document-opened! [_this uri]
    (db/document-opened-by-uri! uri))

  (mark-document-closed! [_this uri]
    (db/document-closed-by-uri! uri))

  (set-active-document! [_this uri]
    (db/update-active-uri! uri))

  (delete-document! [_this uri]
    (when-let [id (db/document-id-by-uri uri)]
      (db/delete-document-by-id! id)))

  (rename-document! [_this old-uri new-uri]
    (when-let [id (db/document-id-by-uri old-uri)]
      (db/update-document-uri-by-id! id new-uri))))

;; =============================================================================
;; Diagnostics Repository Implementation
;; =============================================================================

(defrecord DataScriptDiagnosticsRepository []
  p/IDiagnosticsRepository

  (get-diagnostics [_this]
    (db/diagnostics))

  (get-diagnostics-by-uri [_this uri]
    (db/diagnostics-by-uri uri))

  (replace-diagnostics! [_this uri version diagnostics]
    (let [flat-diags (if (seq diagnostics)
                       (db/flatten-diags diagnostics uri version)
                       [])]
      (db/replace-diagnostics-by-uri! uri version flat-diags))))

;; =============================================================================
;; Symbols Repository Implementation
;; =============================================================================

(defrecord DataScriptSymbolsRepository []
  p/ISymbolsRepository

  (get-symbols [_this]
    (db/symbols))

  (get-symbols-by-uri [_this uri]
    (db/symbols-by-uri uri))

  (replace-symbols! [_this uri symbols]
    (let [flat-symbols (if (seq symbols)
                         (db/flatten-symbols symbols nil uri)
                         [])]
      (db/replace-symbols! uri flat-symbols))))

;; =============================================================================
;; Log Repository Implementation
;; =============================================================================

(defrecord DataScriptLogRepository []
  p/ILogRepository

  (get-logs [_this]
    (db/logs))

  (add-log! [_this log]
    (db/create-logs! [log])))

;; =============================================================================
;; Factory Functions
;; =============================================================================

(defn make-document-repository
  "Creates a DataScript-backed document repository."
  []
  (->DataScriptDocumentRepository))

(defn make-diagnostics-repository
  "Creates a DataScript-backed diagnostics repository."
  []
  (->DataScriptDiagnosticsRepository))

(defn make-symbols-repository
  "Creates a DataScript-backed symbols repository."
  []
  (->DataScriptSymbolsRepository))

(defn make-log-repository
  "Creates a DataScript-backed log repository."
  []
  (->DataScriptLogRepository))
