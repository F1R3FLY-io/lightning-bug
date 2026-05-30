(ns lib.db
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [taoensso.timbre :as log]
   [datascript.core :as d]))

(goog-define ^boolean DEBUG true)

;; =============================================================================
;; Schema Definition
;; =============================================================================
;; Added indexes for frequently queried attributes to improve query performance.
;; Indexes are especially important for:
;; - :document/language - used for filtering documents by language
;; - :document/version - used for version matching in diagnostic queries
;; - :document/opened - used for tracking opened documents per language
;; - :diagnostic/version - used for version-based filtering

(def schema {:symbol/parent {:db/valueType :db.type/ref}
             :symbol/document {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one :db/index true}
             :diagnostic/document {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one :db/index true}
             :diagnostic/version {:db/index true}
             :document/uri {:db/unique :db.unique/identity}
             :document/language {:db/index true}
             :document/version {:db/index true}
             :document/opened {:db/index true}
             ;; Projects group files within a workspace. A document optionally belongs
             ;; to one project (by uri-under-root or explicit assignment).
             :document/project {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one :db/index true}
             :project/id {:db/unique :db.unique/identity}
             :project/root {:db/unique :db.unique/identity}
             :workspace/active-uri {:db/unique :db.unique/identity}})

;; Forward declarations: create-documents! auto-links a new document to a project,
;; but the project accessors are defined later in this namespace.
(declare project-by-id project-for-uri)

;; NOTE: there is intentionally NO module-global conn (multi-editor-workspace
;; refactor). Each workspace (lib.workspace/Workspace) owns its own DataScript conn;
;; every function below takes that `conn` as its first argument. This is what makes
;; the editor multi-instance / multi-workspace (no shared-by-accident global store).
;; `schema` remains public — lib.workspace/make-workspace uses it to build conns.

;; =============================================================================
;; Query-function naming convention
;; =============================================================================
;; - `document-*` / single-attribute accessors return ONE attribute (or a small
;;   fixed tuple) for a document, e.g. `document-id-by-uri`, `document-text-by-uri`.
;; - `doc-*` / `active-uri-*` COALESCED accessors return MULTIPLE attributes in a
;;   single DataScript query (EXP-007), e.g. `active-uri-text-lang-version`,
;;   `doc-text-lang-version-by-uri`. Prefer these on hot paths to avoid N+1 queries.
;; The infrastructure repositories (infrastructure.datascript-adapter) call the
;; coalesced accessors for the hot reads; new hot-path reads should add/extend a
;; coalesced accessor rather than chaining single-attribute ones.
;;
;; CONN THREADING: every query/mutation takes `conn` as its FIRST argument so the
;; library is workspace-instanced (no module-global store). Pure helpers
;; (flatten-*, create-diagnostics, valid-*?, transform-*) take no conn.

(s/def ::id integer?)
(s/def ::dirty boolean?)
(s/def ::text string?)
(s/def ::language string?)
(s/def ::version nat-int?)
(s/def :document/uri ::text)
(s/def :document/version ::version)
(s/def :document/text ::text)
(s/def :document/language ::language)
(s/def :document/dirty ::dirty)
(s/def :document/opened boolean?)  ;; specifies the document is opened in the editor (for LSP languages, set after "textDocument/didOpen" is sent)
(s/def ::document
  (s/and
   (s/keys :req [:document/uri
                 :document/text
                 :document/language
                 :document/version
                 :document/dirty
                 :document/opened])
   #(= :document (:type %))))

(s/def :log/message string?)
(s/def :log/lang string?)
(s/def ::log
  (s/and
   (s/keys :req [:log/message
                 :log/lang])
   #(= :log (:type %))))

(s/def :workspace/active-uri string?)
(s/def ::active-uri
  (s/and
   (s/keys :req [:workspace/active-uri])
   #(= :active-uri (:type %))))

(s/def :project/id string?)
(s/def :project/name string?)
(s/def :project/root string?)
(s/def ::project
  (s/and
   (s/keys :req [:project/id :project/root]
           :opt [:project/name])
   #(= :project (:type %))))

(s/def :diagnostic/message string?)
(s/def :diagnostic/severity pos-int?)
(s/def :diagnostic/start-line nat-int?)
(s/def :diagnostic/start-char nat-int?)
(s/def :diagnostic/end-line nat-int?)
(s/def :diagnostic/end-char nat-int?)
(s/def :diagnostic/version nat-int?)
(s/def ::diagnostic
  (s/and
   (s/keys :req [:diagnostic/document
                 :diagnostic/message
                 :diagnostic/severity
                 :diagnostic/start-line
                 :diagnostic/start-char
                 :diagnostic/end-line
                 :diagnostic/end-char]
           :opt [:document/version])
   #(= :diagnostic (:type %))))

(s/def :symbol/name string?)
(s/def :symbol/kind pos-int?)
(s/def :symbol/start-line nat-int?)
(s/def :symbol/start-char nat-int?)
(s/def :symbol/end-line nat-int?)
(s/def :symbol/end-char nat-int?)
(s/def :symbol/selection-start-line nat-int?)
(s/def :symbol/selection-start-char nat-int?)
(s/def :symbol/selection-end-line nat-int?)
(s/def :symbol/selection-end-char nat-int?)
(s/def :symbol/parent integer?)
(s/def ::symbol
  (s/and
   (s/keys :req [:symbol/document
                 :symbol/name
                 :symbol/kind
                 :symbol/start-line
                 :symbol/start-char
                 :symbol/end-line
                 :symbol/end-char
                 :symbol/selection-start-line
                 :symbol/selection-start-char
                 :symbol/selection-end-line
                 :symbol/selection-end-char]
           :opt [:symbol/parent
                 :db/id])
   #(= :symbol (:type %))))

(defn- valid? [kind data]
  (if (s/valid? kind data)
    true
    (do
      (log/warn "Invalid" kind)
      (s/explain kind data)
      false)))

(defn valid-document? [data]
  (valid? ::document data))

(defn valid-log? [data]
  (valid? ::log data))

(defn valid-active-uri? [data]
  (valid? ::active-uri data))

(defn valid-diagnostic? [data]
  (valid? ::diagnostic data))

(defn valid-symbol? [data]
  (valid? ::symbol data))

(defn valid-project? [data]
  (valid? ::project data))

(defn create-logs!
  [conn logs]
  (let [tx (map (fn [log]
                  {:log/message (:message log)
                   :log/lang (:lang log)
                   :type :log}) logs)]
    (when DEBUG
      (doseq [entity tx]
        (when-not (valid-log? entity)
          (log/warn "Invalid log entity:" (s/explain-str ::log entity)))))
    (log/trace "Executing transaction:" tx)
    (d/transact! conn tx)))

(defn document-id-lang-opened-by-uri
  [conn uri]
  (when DEBUG
    (when-not (s/valid? :document/uri uri)
      (log/warn (s/explain-str :document/uri uri))))
  (or
   (d/q '[:find [?e ?lang ?opened]
          :in $ ?uri
          :where [?e :document/uri ?uri]
                 [?e :document/language ?lang]
                 [?e :document/opened ?opened]]
        @conn uri)
   [nil nil nil]))

(defn active-uri
  [conn]
  (if DEBUG
    (let [uris (d/q '[:find [?uri ...] :where [?e :workspace/active-uri ?uri]] @conn)]
      (when (> (count uris) 1)
        (log/warn "Multiple active URIs found:" uris))
      (first uris))
    (d/q '[:find ?uri .
           :where [?e :workspace/active-uri ?uri]]
         @conn)))

(defn active-text
  [conn]
  (d/q '[:find ?text .
         :where [?a :workspace/active-uri ?uri]
                [?e :document/uri ?uri]
                [?e :document/text ?text]]
       @conn))

(defn active-lang
  [conn]
  (d/q '[:find ?lang .
         :where [?a :workspace/active-uri ?uri]
                [?e :document/uri ?uri]
                [?e :document/language ?lang]]
       @conn))

(defn active-version
  [conn]
  (d/q '[:find ?version .
         :where [?a :workspace/active-uri ?uri]
                [?e :document/uri ?uri]
                [?e :document/version ?version]]
       @conn))

(defn document-text-by-uri
  [conn uri]
  (when DEBUG
    (when-not (s/valid? :document/uri uri)
      (log/warn (s/explain-str :document/uri uri))))
  (d/q '[:find ?text .
         :in $ ?uri
         :where [?e :document/uri ?uri]
                [?e :document/text ?text]]
       @conn uri))

(defn document-version-by-id
  [conn id]
  (when DEBUG
    (when-not (s/valid? ::id id)
      (log/warn (s/explain-str ::id id))))
  (d/q '[:find ?version .
         :in $ ?e
         :where [?e :document/version ?version]]
       @conn id))

(defn document-id-version-by-uri
  [conn uri]
  (when DEBUG
    (when-not (s/valid? :document/uri uri)
      (log/warn (s/explain-str :document/uri uri))))
  (or
   (d/q '[:find [?e ?version]
          :in $ ?uri
          :where [?e :document/uri ?uri]
                 [?e :document/version ?version]]
        @conn uri)
   [nil nil]))

(defn document-opened-by-uri?
  [conn uri]
  (when DEBUG
    (when-not (s/valid? :document/uri uri)
      (log/warn (s/explain-str :document/uri uri))))
  (d/q '[:find ?opened .
         :in $ ?uri
         :where [?e :document/uri ?uri]
                [?e :document/opened ?opened]]
       @conn uri))

(defn document-opened-by-uri
  "Alias for document-opened-by-uri? for API consistency."
  [conn uri]
  (document-opened-by-uri? conn uri))

(defn document-dirty-by-uri
  "Returns the dirty flag for a document by URI."
  [conn uri]
  (when DEBUG
    (when-not (s/valid? :document/uri uri)
      (log/warn (s/explain-str :document/uri uri))))
  (d/q '[:find ?dirty .
         :in $ ?uri
         :where [?e :document/uri ?uri]
                [?e :document/dirty ?dirty]]
       @conn uri))

(defn document-version-by-uri
  "Returns the version for a document by URI."
  [conn uri]
  (when DEBUG
    (when-not (s/valid? :document/uri uri)
      (log/warn (s/explain-str :document/uri uri))))
  (d/q '[:find ?version .
         :in $ ?uri
         :where [?e :document/uri ?uri]
                [?e :document/version ?version]]
       @conn uri))

(defn documents
  [conn]
  (d/q '[:find ?uri ?text ?language ?version ?dirty ?opened
         :keys uri text language version dirty opened
         :where [?e :document/uri ?uri]
                [?e :document/text ?text]
                [?e :document/language ?language]
                [?e :document/version ?version]
                [?e :document/dirty ?dirty]
                [?e :document/opened ?opened]]
       @conn))

(defn logs
  [conn]
  (d/q '[:find ?message ?lang
         :keys message lang
         :where [?e :log/message ?message]
                [?e :log/lang ?lang]]
       @conn))

;; EXP-003: Pull pattern for batch diagnostic extraction
(def ^:private diagnostic-pull-pattern
  [:diagnostic/message :diagnostic/severity
   :diagnostic/start-line :diagnostic/start-char
   :diagnostic/end-line :diagnostic/end-char
   :diagnostic/version])

;; EXP-003: Pull pattern for all-diagnostics (includes document ref for URI and version)
(def ^:private diagnostic-pull-pattern-with-doc
  [:diagnostic/message :diagnostic/severity
   :diagnostic/start-line :diagnostic/start-char
   :diagnostic/end-line :diagnostic/end-char
   :diagnostic/version
   {:diagnostic/document [:document/uri :document/version]}])

(defn- diagnostic-version-matches?
  "Returns true if the diagnostic version matches the document version.
   A diagnostic matches if:
   - Its version is nil (always matches)
   - Its version equals the document version"
  [diag-version doc-version]
  (or (nil? diag-version)
      (= diag-version doc-version)))

(defn- transform-pulled-diagnostic
  "Transforms a pulled diagnostic entity to the expected output format.
   Returns nil if the diagnostic version doesn't match the document version."
  [uri doc-version entity]
  (let [diag-version (:diagnostic/version entity)]
    (when (diagnostic-version-matches? diag-version doc-version)
      {:uri uri
       :message (:diagnostic/message entity)
       :severity (:diagnostic/severity entity)
       :startLine (:diagnostic/start-line entity)
       :startChar (:diagnostic/start-char entity)
       :endLine (:diagnostic/end-line entity)
       :endChar (:diagnostic/end-char entity)
       :version (or diag-version doc-version)})))

(defn- transform-pulled-diagnostic-with-doc
  "Transforms a pulled diagnostic entity (with document) to the expected output format.
   Returns nil if the diagnostic version doesn't match the document version."
  [entity]
  (let [doc (:diagnostic/document entity)
        doc-version (:document/version doc)
        diag-version (:diagnostic/version entity)]
    (when (diagnostic-version-matches? diag-version doc-version)
      {:uri (:document/uri doc)
       :message (:diagnostic/message entity)
       :severity (:diagnostic/severity entity)
       :startLine (:diagnostic/start-line entity)
       :startChar (:diagnostic/start-char entity)
       :endLine (:diagnostic/end-line entity)
       :endChar (:diagnostic/end-char entity)
       :version (or diag-version doc-version)})))

(defn diagnostics
  [conn]
  ;; EXP-003: Use d/pull-many for batch attribute extraction with post-query filtering
  (let [entity-ids (d/q '[:find [?e ...]
                          :where [?e :diagnostic/document _]]
                        @conn)]
    (when (seq entity-ids)
      (into []
            (keep transform-pulled-diagnostic-with-doc)
            (d/pull-many @conn diagnostic-pull-pattern-with-doc entity-ids)))))

;; EXP-002: Pull pattern for all-symbols (includes document ref for URI)
(def ^:private symbol-pull-pattern-with-doc
  [:symbol/name :symbol/kind
   :symbol/start-line :symbol/start-char
   :symbol/end-line :symbol/end-char
   :symbol/selection-start-line :symbol/selection-start-char
   :symbol/selection-end-line :symbol/selection-end-char
   :symbol/parent
   {:symbol/document [:document/uri]}])

(defn- transform-pulled-symbol-with-doc
  "Transforms a pulled symbol entity (with document) to the expected output format."
  [entity]
  {:uri (get-in entity [:symbol/document :document/uri])
   :name (:symbol/name entity)
   :kind (:symbol/kind entity)
   :startLine (:symbol/start-line entity)
   :startChar (:symbol/start-char entity)
   :endLine (:symbol/end-line entity)
   :endChar (:symbol/end-char entity)
   :selectionStartLine (:symbol/selection-start-line entity)
   :selectionStartChar (:symbol/selection-start-char entity)
   :selectionEndLine (:symbol/selection-end-line entity)
   :selectionEndChar (:symbol/selection-end-char entity)
   :parent (or (:symbol/parent entity) 0)})

(defn symbols
  [conn]
  ;; EXP-002: Use d/pull-many for batch attribute extraction
  (let [entity-ids (d/q '[:find [?e ...]
                          :where [?e :symbol/document _]]
                        @conn)]
    (when (seq entity-ids)
      (mapv transform-pulled-symbol-with-doc
            (d/pull-many @conn symbol-pull-pattern-with-doc entity-ids)))))

(defn active-uri-text-lang
  [conn]
  (or
   (d/q '[:find [?uri ?text ?lang]
          :where [?a :workspace/active-uri ?uri]
                 [?e :document/uri ?uri]
                 [?e :document/text ?text]
                 [?e :document/language ?lang]]
        @conn)
   [nil nil nil]))

;; =============================================================================
;; EXP-007: Coalesced Active Document Queries
;; =============================================================================

(defn active-uri-version
  "Returns [uri version] for the active document in a single query.
   EXP-007: Coalesces active-uri + active-version."
  [conn]
  (or
   (d/q '[:find [?uri ?version]
          :where [?a :workspace/active-uri ?uri]
                 [?e :document/uri ?uri]
                 [?e :document/version ?version]]
        @conn)
   [nil nil]))

(defn active-uri-text-lang-version
  "Returns [uri text lang version] for the active document in a single query.
   EXP-007: Coalesces active-uri + text + lang + version for coeffects."
  [conn]
  (or
   (d/q '[:find [?uri ?text ?lang ?version]
          :where [?a :workspace/active-uri ?uri]
                 [?e :document/uri ?uri]
                 [?e :document/text ?text]
                 [?e :document/language ?lang]
                 [?e :document/version ?version]]
        @conn)
   [nil nil nil nil]))

(defn doc-text-version-by-uri
  [conn uri]
  (when DEBUG
    (when-not (s/valid? :document/uri uri)
      (log/warn (s/explain-str :document/uri uri))))
  (or
   (d/q '[:find [?text ?version]
          :in $ ?uri
          :where [?e :document/uri ?uri]
                 [?e :document/text ?text]
                 [?e :document/version ?version]]
        @conn uri)
   [nil nil]))

(defn doc-id-text-lang-by-uri
  [conn uri]
  (when DEBUG
    (when-not (s/valid? :document/uri uri)
      (log/warn (s/explain-str :document/uri uri))))
  (or
   (d/q '[:find [?e ?text ?lang]
          :in $ ?uri
          :where [?e :document/uri ?uri]
                 [?e :document/text ?text]
                 [?e :document/language ?lang]]
        @conn uri)
   [nil nil nil]))

(defn doc-text-lang-dirty-by-uri
  [conn uri]
  (when DEBUG
    (when-not (s/valid? :document/uri uri)
      (log/warn (s/explain-str :document/uri uri))))
  (or
   (d/q '[:find [?text ?lang ?dirty]
          :in $ ?uri
          :where [?e :document/uri ?uri]
                 [?e :document/text ?text]
                 [?e :document/language ?lang]
                 [?e :document/dirty ?dirty]]
        @conn uri)
   [nil nil nil]))

(defn document-id-by-uri
  [conn uri]
  (when DEBUG
    (when-not (s/valid? :document/uri uri)
      (log/warn (s/explain-str :document/uri uri))))
  (d/q '[:find ?e .
         :in $ ?uri
         :where [?e :document/uri ?uri]]
       @conn uri))

(defn doc-text-lang-by-uri
  [conn uri]
  (when DEBUG
    (when-not (s/valid? :document/uri uri)
      (log/warn (s/explain-str :document/uri uri))))
  (or
   (d/q '[:find [?text ?lang]
          :in $ ?uri
          :where [?e :document/uri ?uri]
                 [?e :document/text ?text]
                 [?e :document/language ?lang]]
        @conn uri)
   [nil nil]))

(defn doc-text-lang-version-by-uri
  "Returns [text lang version] for a document by URI in a single query.
   EXP-007: Coalesces text + lang + version for coeffects."
  [conn uri]
  (when DEBUG
    (when-not (s/valid? :document/uri uri)
      (log/warn (s/explain-str :document/uri uri))))
  (or
   (d/q '[:find [?text ?lang ?version]
          :in $ ?uri
          :where [?e :document/uri ?uri]
                 [?e :document/text ?text]
                 [?e :document/language ?lang]
                 [?e :document/version ?version]]
        @conn uri)
   [nil nil nil]))

(defn document-language-by-uri
  [conn uri]
  (when DEBUG
    (when-not (s/valid? :document/uri uri)
      (log/warn (s/explain-str :document/uri uri))))
  (or
   (d/q '[:find ?lang .
          :in $ ?uri
          :where [?e :document/uri ?uri]
                 [?e :document/language ?lang]]
        @conn uri)
   [nil nil]))

(defn document-language-opened-by-uri
  [conn uri]
  (when DEBUG
    (when-not (s/valid? :document/uri uri)
      (log/warn (s/explain-str :document/uri uri))))
  (or
   (d/q '[:find [?lang ?opened]
          :in $ ?uri
          :where [?e :document/uri ?uri]
          [?e :document/language ?lang]
          [?e :document/opened ?opened]]
        @conn uri)
   [nil nil]))

(defn inc-document-version-by-uri!
  [conn uri]
  (when DEBUG
    (when-not (s/valid? :document/uri uri)
      (log/warn (s/explain-str :document/uri uri))))
  (when-let [[id old-version] (document-id-version-by-uri conn uri)]
    (let [new-version (inc old-version)
          tx [[:db/add id :document/version new-version]]]
      (when DEBUG
        (when-not (s/valid? :document/version new-version)
          (log/warn "Invalid new-version for uri" uri ":" (s/explain-str :document/version new-version))))
      (log/trace "Executing transaction:" tx)
      (d/transact! conn tx)
      new-version)))

(defn inc-document-version-by-id!
  [conn id]
  (when DEBUG
    (when-not (s/valid? ::id id)
      (log/warn (s/explain-str ::id id))))
  (let [old-version (document-version-by-id conn id)]
    (when id
      (let [new-version (inc old-version)
            tx [[:db/add id :document/version new-version]]]
        (when DEBUG
          (when-not (s/valid? :document/version new-version)
            (log/warn "Invalid new-version for id" id ":" (s/explain-str :document/version new-version))))
        (log/trace "Executing transaction:" tx)
        (d/transact! conn tx)
        new-version))))

(def increment-document-version-by-uri!
  "Alias for inc-document-version-by-uri! for API consistency."
  inc-document-version-by-uri!)

(defn update-document-dirty-by-id!
  [conn id dirty?]
  (when DEBUG
    (when-not (s/valid? ::id id)
      (log/warn (s/explain-str ::id id)))
    (when-not (s/valid? ::dirty dirty?)
      (log/warn (s/explain-str ::dirty dirty?))))
  (if (d/entity @conn id)
    (let [tx [[:db/add id :document/dirty dirty?]]]
      (log/trace "Executing transaction:" tx)
      (d/transact! conn tx))
    (log/error "No entity exists with id" id)))

(defn update-document-text-by-id!
  [conn id text]
  (when DEBUG
    (when-not (s/valid? ::id id)
      (log/warn (s/explain-str ::id id)))
    (when-not (s/valid? ::text text)
      (log/warn (s/explain-str ::text text))))
  (if (d/entity @conn id)
    (let [tx [[:db/add id :document/text text]
              [:db/add id :document/dirty true]]]
      (log/trace "Executing transaction:" tx)
      (d/transact! conn tx))
    (log/error "No entity exists with id" id)))

(defn update-document-text-by-uri!
  [conn uri text]
  (when DEBUG
    (when-not (s/valid? :document/uri uri)
      (log/warn (s/explain-str :document/uri uri)))
    (when-not (s/valid? ::text text)
      (log/warn (s/explain-str ::text text))))
  (when-let [id (document-id-by-uri conn uri)]
    (let [tx [[:db/add id :document/text text]
              [:db/add id :document/dirty true]]]
      (log/trace "Executing transaction:" tx)
      (d/transact! conn tx))))

(defn update-document-uri-language-by-id!
  [conn id uri lang]
  (when DEBUG
    (when-not (s/valid? ::id id)
      (log/warn (s/explain-str ::id id)))
    (when-not (s/valid? :document/uri uri)
      (log/warn (s/explain-str :document/uri uri)))
    (when-not (s/valid? ::language lang)
      (log/warn (s/explain-str ::language lang))))
  (if (d/entity @conn id)
    (let [tx [[:db/add id :document/uri uri]
              [:db/add id :document/language lang]]]
      (log/trace "Executing transaction:" tx)
      (d/transact! conn tx))
    (log/error "No entity exists with id" id)))

(defn update-document-uri-by-id!
  [conn id uri]
  (when DEBUG
    (when-not (s/valid? ::id id)
      (log/warn (s/explain-str ::id id)))
    (when-not (s/valid? :document/uri uri)
      (log/warn (s/explain-str :document/uri uri))))
  (if (d/entity @conn id)
    (let [tx [[:db/add id :document/uri uri]]]
      (log/trace "Executing transaction:" tx)
      (d/transact! conn tx))
    (log/error "No entity exists with id" id)))

(defn update-document-text-language-by-id!
  [conn id text lang]
  (when DEBUG
    (when-not (s/valid? ::id id)
      (log/warn (s/explain-str ::id id)))
    (when-not (or (nil? text) (s/valid? ::text text))
      (log/warn (s/explain-str ::text text)))
    (when-not (or (nil? lang) (s/valid? ::language lang))
      (log/warn (s/explain-str ::language lang))))
  (if (d/entity @conn id)
    (let [tx (cond-> []
               text (conj [:db/add id :document/text text]
                          [:db/add id :document/dirty true])
               lang (conj [:db/add id :document/language lang]))]
      (log/trace "Executing transaction:" tx)
      (d/transact! conn tx))
    (log/error "No entity exists with id" id)))

(defn document-opened-by-uri!
  [conn uri]
  (when DEBUG
    (when-not (s/valid? :document/uri uri)
      (log/warn (s/explain-str :document/uri uri))))
  (when-let [id (document-id-by-uri conn uri)]
    (let [tx [[:db/add id :document/opened true]]]
      (log/trace "Executing transaction:" tx)
      (d/transact! conn tx))))

(defn document-closed-by-uri!
  [conn uri]
  (when DEBUG
    (when-not (s/valid? :document/uri uri)
      (log/warn (s/explain-str :document/uri uri))))
  (when-let [id (document-id-by-uri conn uri)]
    (let [tx [[:db/add id :document/opened false]]]
      (log/trace "Executing transaction:" tx)
      (d/transact! conn tx))))

(defn document-saved-by-uri!
  "Marks a document as saved (clears dirty flag) by URI."
  [conn uri]
  (when DEBUG
    (when-not (s/valid? :document/uri uri)
      (log/warn (s/explain-str :document/uri uri))))
  (when-let [id (document-id-by-uri conn uri)]
    (let [tx [[:db/add id :document/dirty false]]]
      (log/trace "Executing transaction:" tx)
      (d/transact! conn tx))))

(defn opened-uris-by-lang
  [conn lang]
  (when DEBUG
    (when-not (s/valid? :document/language lang)
      (log/warn (s/explain-str :document/language lang))))
  (d/q '[:find [?uri ...]
         :in $ ?lang
         :where [?e :document/language ?lang]
                [?e :document/opened true]
                [?e :document/uri ?uri]]
       @conn lang))

(defn close-all-opened-by-lang!
  [conn lang]
  (doseq [uri (opened-uris-by-lang conn lang)]
    (document-closed-by-uri! conn uri)))

(defn update-active-uri!
  [conn uri]
  (when DEBUG
    (when-not (s/valid? :workspace/active-uri uri)
      (log/warn (s/explain-str :workspace/active-uri uri))))
  (let [;; Retract only OTHER active-uri entities (those holding a DIFFERENT uri). A blanket
        ;; retract is wrong here: :workspace/active-uri is :db.unique/identity, so the `add`
        ;; below upserts onto the existing entity for `uri`; retracting that same entity would
        ;; then clear the focus we just set. This manifested when a 2nd pane re-activated the
        ;; already-focused file (split panes) — it silently blanked :workspace/active-uri.
        prev (d/q '[:find ?e ?u :where [?e :workspace/active-uri ?u]] @conn)
        retracts (for [[e u] prev :when (not= u uri)] [:db/retractEntity e])
        add {:workspace/active-uri uri :type :active-uri}
        tx (conj (vec retracts) add)]
    (when DEBUG
      (doseq [entity (filter map? tx)]
        (when-not (valid-active-uri? entity)
          (log/warn "Invalid active-uri entity:" (s/explain-str ::active-uri entity)))))
    (log/trace "Executing transaction:" tx)
    (d/transact! conn tx)))

(defn create-documents!
  [conn docs]
  (let [tx (map (fn [doc]
                  ;; Link the document to a project: explicit :project id wins, else the
                  ;; project whose :project/root is a prefix of the uri (nil if no projects).
                  (let [pid (or (:project doc) (project-for-uri conn (:uri doc)))]
                    (cond-> {:document/uri (:uri doc)
                             :document/text (:text doc)
                             :document/language (:language doc)
                             :document/version (:version doc)
                             :document/dirty (:dirty doc)
                             :document/opened (:opened doc)
                             :type :document}
                      (and pid (project-by-id conn pid)) (assoc :document/project [:project/id pid])))) docs)]
    (when DEBUG
      (doseq [entity tx]
        (when-not (valid-document? entity)
          (log/warn "Invalid document entity:" (s/explain-str ::document entity)))))
    (log/trace "Executing transaction:" tx)
    (d/transact! conn tx)))

(defn delete-document-by-id!
  [conn id]
  (when DEBUG
    (when-not (s/valid? ::id id)
      (log/warn (s/explain-str ::id id))))
  (let [tx [[:db/retractEntity id]]]
    (log/trace "Executing transaction:" tx)
    (d/transact! conn tx)))

(defn first-document-uri
  [conn]
  (d/q '[:find ?uri . :where [?e :document/uri ?uri]] @conn))

(defn active-uri?
  [conn uri]
  (when DEBUG
    (when-not (s/valid? :document/uri uri)
      (log/warn (s/explain-str :document/uri uri))))
  (= uri (active-uri conn)))

(defn- ensure-document-eid [conn uri version language text dirty opened]
  (if-let [eid (document-id-by-uri conn uri)]
    eid
    (let [temp-id -1
          entity {:db/id temp-id
                  :document/uri uri
                  :document/version (or version 0)
                  :document/text (or text "")
                  :document/language (or language "unknown")
                  :document/dirty (boolean dirty)
                  :document/opened (boolean opened)
                  :type :document}
          tx [entity]]
      (when DEBUG
        (when-not (valid-document? entity)
          (log/warn "Invalid document entity in ensure-document-eid:" (s/explain-str ::document entity))))
      (log/trace "Executing transaction:" tx)
      (let [tx-report (d/transact! conn tx)]
        (get (:tempids tx-report) temp-id)))))

(defn flatten-diags
  [diags uri version]
  (map
   (fn [diag]
     (let [range (:range diag {})
           start (:start range {})
           end (:end range {})]
       (cond-> {:uri uri
                :message (:message diag)
                :severity (:severity diag 1)
                :startLine (:line start 0)
                :startChar (:character start 0)
                :endLine (:line end 0)
                :endChar (:character end 0)}
         version (assoc :version version))))
   diags))

(defn create-diagnostics [diags doc-eid version]
  (let [tx (map (fn [d]
                  (cond-> {:diagnostic/document doc-eid
                           :diagnostic/message (:message d)
                           :diagnostic/severity (:severity d)
                           :diagnostic/start-line (:startLine d)
                           :diagnostic/start-char (:startChar d)
                           :diagnostic/end-line (:endLine d)
                           :diagnostic/end-char (:endChar d)
                           :type :diagnostic}
                    version (assoc :diagnostic/version version)))
                diags)]
    (when DEBUG
      (doseq [entity tx]
        (when-not (valid-diagnostic? entity)
          (log/warn "Invalid diagnostic entity:" (s/explain-str ::diagnostic entity)))))
    tx))

(defn replace-diagnostics-by-uri!
  [conn uri version diags]
  (when DEBUG
    (when-not (s/valid? :document/uri uri)
      (log/warn (s/explain-str :document/uri uri)))
    (when-not (or (nil? version) (s/valid? ::version version))
      (log/warn (s/explain-str ::version version))))
  (when-let [doc-eid (document-id-by-uri conn uri)]
    (let [old-ids (d/q '[:find [?e ...]
                         :in $ ?uri
                         :where
                         [?d :document/uri ?uri]
                         [?e :diagnostic/document ?d]]
                       @conn uri)
          deletions (for [item old-ids] [:db/retractEntity item])]
      (if (empty? diags)
        (do
          (log/trace "Executing transaction:" deletions)
          (when (seq deletions) (d/transact! conn deletions)))
        (let [creations (create-diagnostics diags doc-eid version)
              tx (concat deletions creations)]
          (log/trace "Executing transaction:" tx)
          (d/transact! conn tx))))))

(defn flatten-symbols
  "Flattens hierarchical LSP symbols into a list for datascript transaction, assigning negative db/ids to avoid conflicts."
  [symbols parent-id uri]
  (let [id-counter (atom -1)]
    (letfn [(flatten-rec [syms parent]
              (mapcat (fn [s]
                        (let [sid (swap! id-counter dec)
                              range (:range s {})
                              start (:start range {})
                              end (:end range {})
                              sel-range (:selectionRange s {})
                              sel-start (:start sel-range {})
                              sel-end (:end sel-range {})
                              s' (cond-> (dissoc s :name :kind :range :selectionRange :children)
                                   :always (assoc :db/id sid
                                                  :uri uri
                                                  :symbol/name (:name s)
                                                  :symbol/kind (:kind s)
                                                  :symbol/start-line (:line start 0)
                                                  :symbol/start-char (:character start 0)
                                                  :symbol/end-line (:line end 0)
                                                  :symbol/end-char (:character end 0)
                                                  :symbol/selection-start-line (:line sel-start 0)
                                                  :symbol/selection-start-char (:character sel-start 0)
                                                  :symbol/selection-end-line (:line sel-end 0)
                                                  :symbol/selection-end-char (:character sel-end 0))
                                   parent (assoc :symbol/parent parent))
                              children (:children s)]
                          (cons s' (when children (flatten-rec children sid)))))
                      syms))]
      (flatten-rec symbols parent-id))))

(defn create-symbols
  [conn doc-eid uri flat-symbols]
  (let [effective-doc-eid (or doc-eid (ensure-document-eid conn uri nil nil nil false false))
        tx (map (fn [s]
                  (cond-> {:symbol/document effective-doc-eid
                           :symbol/name (:symbol/name s)
                           :symbol/kind (:symbol/kind s)
                           :symbol/start-line (:symbol/start-line s)
                           :symbol/start-char (:symbol/start-char s)
                           :symbol/end-line (:symbol/end-line s)
                           :symbol/end-char (:symbol/end-char s)
                           :symbol/selection-start-line (:symbol/selection-start-line s)
                           :symbol/selection-start-char (:symbol/selection-start-char s)
                           :symbol/selection-end-line (:symbol/selection-end-line s)
                           :symbol/selection-end-char (:symbol/selection-end-char s)
                           :type :symbol}
                    (:db/id s) (assoc :db/id (:db/id s))
                    (:symbol/parent s) (assoc :symbol/parent (:symbol/parent s))))
                flat-symbols)]
    (when DEBUG
      (doseq [entity tx]
        (when-not (valid-symbol? entity)
          (log/warn "Invalid symbol entity:" (s/explain-str ::symbol entity)))))
    tx))

(defn replace-symbols!
  [conn uri symbols]
  (when DEBUG
    (when-not (s/valid? :document/uri uri)
      (log/warn (s/explain-str :document/uri uri))))
  (when-let [doc-eid (document-id-by-uri conn uri)]
    (let [old-ids (d/q '[:find [?e ...]
                         :in $ ?doc
                         :where [?e :symbol/document ?doc]]
                       @conn doc-eid)
          deletions (for [item old-ids] [:db/retractEntity item])]
      (if (empty? symbols)
        (when (seq deletions)
          (log/trace "Executing transaction:" deletions)
          (d/transact! conn deletions))
        (let [creations (create-symbols conn doc-eid uri symbols)
              tx (concat deletions creations)]
          (log/trace "Executing transaction:" tx)
          (d/transact! conn tx))))))

(defn diagnostics-by-uri [conn uri]
  (when DEBUG
    (when-not (s/valid? :document/uri uri)
      (log/warn (s/explain-str :document/uri uri))))
  ;; EXP-003: Use d/pull-many for batch attribute extraction with post-query filtering
  (when-let [[doc-id doc-version] (document-id-version-by-uri conn uri)]
    (let [entity-ids (d/q '[:find [?e ...]
                            :in $ ?doc
                            :where [?e :diagnostic/document ?doc]]
                          @conn doc-id)]
      (when (seq entity-ids)
        (into []
              (keep #(transform-pulled-diagnostic uri doc-version %))
              (d/pull-many @conn diagnostic-pull-pattern entity-ids))))))

;; EXP-002: Pull pattern for batch symbol extraction
(def ^:private symbol-pull-pattern
  [:symbol/name :symbol/kind
   :symbol/start-line :symbol/start-char
   :symbol/end-line :symbol/end-char
   :symbol/selection-start-line :symbol/selection-start-char
   :symbol/selection-end-line :symbol/selection-end-char
   :symbol/parent])

(defn- transform-pulled-symbol
  "Transforms a pulled symbol entity to the expected output format."
  [uri entity]
  {:uri uri
   :name (:symbol/name entity)
   :kind (:symbol/kind entity)
   :startLine (:symbol/start-line entity)
   :startChar (:symbol/start-char entity)
   :endLine (:symbol/end-line entity)
   :endChar (:symbol/end-char entity)
   :selectionStartLine (:symbol/selection-start-line entity)
   :selectionStartChar (:symbol/selection-start-char entity)
   :selectionEndLine (:symbol/selection-end-line entity)
   :selectionEndChar (:symbol/selection-end-char entity)
   :parent (or (:symbol/parent entity) 0)})

(defn symbols-by-uri [conn uri]
  (when DEBUG
    (when-not (s/valid? :document/uri uri)
      (log/warn (s/explain-str :document/uri uri))))
  ;; EXP-002: Use d/pull-many for batch attribute extraction
  (let [entity-ids (d/q '[:find [?e ...]
                          :in $ ?uri
                          :where [?doc :document/uri ?uri]
                                 [?e :symbol/document ?doc]]
                        @conn uri)]
    (when (seq entity-ids)
      (mapv #(transform-pulled-symbol uri %)
            (d/pull-many @conn symbol-pull-pattern entity-ids)))))

(defn reset-active-uri!
  [conn]
  (let [prev-eids (d/q '[:find [?e ...]
                         :where [?e :workspace/active-uri _]]
                       @conn)]
    (when (seq prev-eids)
      (let [tx (mapv (fn [eid] [:db/retractEntity eid]) prev-eids)]
        (log/trace "Retracting active-uri on destroy:" tx)
        (d/transact! conn tx)))))

;; =============================================================================
;; Projects — a workspace groups its files into projects
;; =============================================================================

(defn create-projects!
  "Creates project entities. Each project map: {:id <string> :root <uri-prefix string>
   :name <string?>}. :id and :root are unique-identity."
  [conn projects]
  (let [tx (map (fn [p]
                  (cond-> {:project/id (:id p)
                           :project/root (:root p)
                           :type :project}
                    (:name p) (assoc :project/name (:name p))))
                projects)]
    (when DEBUG
      (doseq [entity tx]
        (when-not (valid-project? entity)
          (log/warn "Invalid project entity:" (s/explain-str ::project entity)))))
    (log/trace "Executing transaction:" tx)
    (d/transact! conn tx)))

(defn projects
  "Returns all projects as maps {:id :name :root} (name is \"\" when unset)."
  [conn]
  (d/q '[:find ?id ?name ?root
         :keys id name root
         :where [?e :project/id ?id]
                [?e :project/root ?root]
                [(get-else $ ?e :project/name "") ?name]]
       @conn))

(defn project-by-id
  "Returns the entity id of the project with the given :project/id, or nil."
  [conn id]
  (when DEBUG
    (when-not (s/valid? :project/id id)
      (log/warn (s/explain-str :project/id id))))
  (d/q '[:find ?e .
         :in $ ?id
         :where [?e :project/id ?id]]
       @conn id))

(defn project-by-root
  "Returns the :project/id of the project whose :project/root equals root, or nil."
  [conn root]
  (d/q '[:find ?id .
         :in $ ?root
         :where [?e :project/root ?root]
                [?e :project/id ?id]]
       @conn root))

(defn project-for-uri
  "Returns the :project/id of the project whose :project/root is the LONGEST prefix
   of uri, or nil if no project root matches."
  [conn uri]
  (when uri
    (let [roots (d/q '[:find ?id ?root
                       :where [?e :project/id ?id]
                              [?e :project/root ?root]]
                     @conn)]
      (->> roots
           (filter (fn [[_ root]] (str/starts-with? uri root)))
           (sort-by (fn [[_ root]] (- (count root))))
           ffirst))))

(defn documents-by-project
  "Returns the uris of documents belonging to the given :project/id."
  [conn project-id]
  (d/q '[:find [?uri ...]
         :in $ ?pid
         :where [?p :project/id ?pid]
                [?e :document/project ?p]
                [?e :document/uri ?uri]]
       @conn project-id))

(defn project-of-uri
  "Returns the project {:id :name :root} a document belongs to, or nil."
  [conn uri]
  (when DEBUG
    (when-not (s/valid? :document/uri uri)
      (log/warn (s/explain-str :document/uri uri))))
  (when-let [[id name root] (d/q '[:find [?id ?name ?root]
                                   :in $ ?uri
                                   :where [?d :document/uri ?uri]
                                          [?d :document/project ?p]
                                          [?p :project/id ?id]
                                          [?p :project/root ?root]
                                          [(get-else $ ?p :project/name "") ?name]]
                                 @conn uri)]
    {:id id :name name :root root}))

(defn link-document-to-project!
  "Associates an existing document (by uri) with a project (by :project/id)."
  [conn uri project-id]
  (when-let [doc-id (document-id-by-uri conn uri)]
    (when (project-by-id conn project-id)
      (let [tx [[:db/add doc-id :document/project [:project/id project-id]]]]
        (log/trace "Executing transaction:" tx)
        (d/transact! conn tx)))))
