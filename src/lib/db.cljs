(ns lib.db
  (:require
   [clojure.spec.alpha :as s]
   [taoensso.timbre :as log]
   [datascript.core :as d]))

(def schema {:symbol/parent {:db/valueType :db.type/ref}
             :symbol/document {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
             :diagnostic/document {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
             :document/uri {:db/unique :db.unique/identity}
             :workspace/active-uri {:db/unique :db.unique/identity}})

(defonce conn (d/create-conn schema))

(s/def :document/uri string?)
(s/def :document/version nat-int?)
(s/def :document/text string?)
(s/def :document/language string?)
(s/def :document/dirty boolean?)
(s/def :document/opened boolean?)
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

(defn create-logs!
  [logs]
  (let [tx (map (fn [log]
                  {:log/message (:message log)
                   :log/lang (:lang log)
                   :type :log}) logs)]
    (doseq [entity tx]
      (when-not (valid-log? entity)
        (log/warn "Invalid log entity:" entity)))
    (d/transact! conn tx)))

(defn document-id-lang-opened-by-uri
  [uri]
  (or
   (d/q '[:find [?e ?lang ?opened]
          :in $ ?uri
          :where [?e :document/uri ?uri]
                 [?e :document/language ?lang]
                 [?e :document/opened ?opened]]
        @conn uri)
   [nil nil nil]))

(defn active-uri
  []
  (d/q '[:find ?uri .
         :where [?e :workspace/active-uri ?uri]]
       @conn))

(defn active-text
  []
  (d/q '[:find ?text .
         :where [?a :workspace/active-uri ?uri]
                [?e :document/uri ?uri]
                [?e :document/text ?text]]
       @conn))

(defn active-lang
  []
  (d/q '[:find ?lang .
         :where [?a :workspace/active-uri ?uri]
                [?e :document/uri ?uri]
                [?e :document/language ?lang]]
       @conn))

(defn document-text-by-uri
  [uri]
  (d/q '[:find ?text .
         :in $ ?uri
         :where [?e :document/uri ?uri]
                [?e :document/text ?text]]
       @conn uri))

(defn document-version-by-id
  [id]
  (d/q '[:find ?version .
         :in $ ?e
         :where [?e :document/version ?version]]
       @conn id))

(defn document-id-version-by-uri
  [uri]
  (or
   (d/q '[:find [?e ?version]
          :in $ ?uri
          :where [?e :document/uri ?uri]
          [?e :document/version ?version]]
        @conn uri)
   [nil nil]))

(defn document-opened-by-uri?
  [uri]
  (d/q '[:find ?opened .
         :in $ ?uri
         :where [?e :document/uri ?uri]
                [?e :document/opened ?opened]]
       @conn uri))

(defn document-dirty-by-uri?
  [uri]
  (d/q '[:find ?dirty .
         :in $ ?uri
         :where [?e :document/uri ?uri]
                [?e :document/dirty ?dirty]]
       @conn uri))

(defn documents
  []
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
  []
  (d/q '[:find ?message ?lang
         :keys message lang
         :where [?e :log/message ?message]
                [?e :log/lang ?lang]]
       @conn))

(defn diagnostics
  []
  (d/q '[:find ?uri ?version ?message ?severity ?startLine ?startChar ?endLine ?endChar
         :keys uri version message severity startLine startChar endLine endChar
         :where [?e :diagnostic/document ?doc]
                [?doc :document/uri ?uri]
                [?e :diagnostic/version ?version]
                [?e :diagnostic/message ?message]
                [?e :diagnostic/severity ?severity]
                [?e :diagnostic/start-line ?startLine]
                [?e :diagnostic/start-char ?startChar]
                [?e :diagnostic/end-line ?endLine]
                [?e :diagnostic/end-char ?endChar]]
       @conn))

(defn symbols
  []
  (d/q '[:find ?uri
               ?name
               ?kind
               ?startLine
               ?startChar
               ?endLine
               ?endChar
               ?selectionStartLine
               ?selectionStartChar
               ?selectionEndLine
               ?selectionEndChar
               ?parent
         :keys uri
               name
               kind
               startLine
               startChar
               endLine
               endChar
               selectionStartLine
               selectionStartChar
               selectionEndLine
               selectionEndChar
               parent
         :where [?e :symbol/document ?doc]
                [?doc :document/uri ?uri]
                [?e :symbol/name ?name]
                [?e :symbol/kind ?kind]
                [?e :symbol/start-line ?startLine]
                [?e :symbol/start-char ?startChar]
                [?e :symbol/end-line ?endLine]
                [?e :symbol/end-char ?endChar]
                [?e :symbol/selection-start-line ?selectionStartLine]
                [?e :symbol/selection-start-char ?selectionStartChar]
                [?e :symbol/selection-end-line ?selectionEndLine]
                [?e :symbol/selection-end-char ?selectionEndChar]
                [?e :symbol/parent ?parent]]
       @conn))

(defn active-uri-text-lang
  []
  (or
   (d/q '[:find [?uri ?text ?lang]
          :where [?a :workspace/active-uri ?uri]
                 [?e :document/uri ?uri]
                 [?e :document/text ?text]
                 [?e :document/language ?lang]
                 [?e :document/version ?version]]
        @conn)
   [nil nil nil]))

(defn doc-text-version-by-uri
  [uri]
  (or
   (d/q '[:find [?text ?version]
          :in $ ?uri
          :where [?e :document/uri ?uri]
                 [?e :document/text ?text]
                 [?e :document/version ?version]]
        @conn uri)
   [nil nil]))

(defn doc-id-text-lang-by-uri
  [uri]
  (or
   (d/q '[:find [?e ?text ?lang]
          :in $ ?uri
          :where [?e :document/uri ?uri]
                 [?e :document/text ?text]
                 [?e :document/language ?lang]]
        @conn uri)
   [nil nil nil]))

(defn doc-text-lang-dirty-by-uri
  [uri]
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
  [uri]
  (d/q '[:find ?e .
         :in $ ?uri
         :where [?e :document/uri ?uri]]
       @conn uri))

(defn doc-text-lang-by-uri
  [uri]
  (or
   (d/q '[:find [?text ?lang]
          :in $ ?uri
          :where [?e :document/uri ?uri]
                 [?e :document/text ?text]
                 [?e :document/language ?lang]]
        @conn uri)
   [nil nil]))

(defn document-language-opened-by-uri
  [uri]
  (or
   (d/q '[:find [?lang ?opened]
          :in $ ?uri
          :where [?e :document/uri ?uri]
                 [?e :document/language ?lang]
                 [?e :document/opened ?opened]]
        @conn uri)
   [nil nil]))

(defn inc-document-version-by-uri!
  [uri]
  (let [[id old-version] (document-id-version-by-uri uri)]
    (when id
      (let [new-version (inc old-version)
            tx [[:db/add id :document/version new-version]]]
        (when (not (nat-int? new-version))
          (log/warn "Invalid new-version for uri" uri ":" new-version))
        (d/transact! conn tx)
        new-version))))

(defn inc-document-version-by-id!
  [id]
  (let [old-version (document-version-by-id id)]
    (when id
      (let [new-version (inc old-version)
            tx [[:db/add id :document/version new-version]]]
        (when (not (nat-int? new-version))
          (log/warn "Invalid new-version for id" id ":" new-version))
        (d/transact! conn tx)
        new-version))))

(defn update-document-dirty-by-id!
  [id dirty?]
  (let [tx [[:db/add id :document/dirty dirty?]]]
    (when (not (integer? id))
      (log/warn "Invalid id for update-document-dirty-by-id!:" id))
    (when (not (boolean? dirty?))
      (log/warn "Invalid dirty? for update-document-dirty-by-id!:" dirty?))
    (d/transact! conn tx)))

(defn update-document-text-by-id!
  [id text]
  (let [tx [[:db/add id :document/text text]
            [:db/add id :document/dirty true]]]
    (when (not (and (integer? id) (string? text)))
      (log/warn "Invalid parameters for update-document-text-by-id! id:" id "text:" text))
    (d/transact! conn tx)))

(defn update-document-text-by-uri!
  [uri text]
  (let [tx [[:db/add [:document/uri uri] :document/text text]
            [:db/add [:document/uri uri] :document/dirty false]]]
    (when (not (and (string? uri) (string? text)))
      (log/warn "Invalid parameters for update-document-text-by-uri! uri:" uri "text:" text))
    (d/transact! conn tx)))

(defn update-document-uri-language-by-id!
  [id uri lang]
  (let [tx [[:db/add id :document/uri uri]
            [:db/add id :document/language lang]]]
    (when (not (and (integer? id) (string? uri) (string? lang)))
      (log/warn "Invalid parameters for update-document-uri-language-by-id! id:" id "uri:" uri "lang:" lang))
    (d/transact! conn tx)))

(defn update-document-uri-by-id!
  [id uri]
  (let [tx [[:db/add id :document/uri uri]]]
    (when (not (and (integer? id) (string? uri)))
      (log/warn "Invalid parameters for update-document-uri-by-id! id:" id "uri:" uri))
    (d/transact! conn tx)))

(defn update-document-language-by-id!
  [id language]
  (let [tx [[:db/add id :document/language language]]]
    (when (not (and (integer? id) (string? language)))
      (log/warn "Invalid parameters for update-document-language-by-id! id:" id "language:" language))
    (d/transact! conn tx)))

(defn update-document-text-language-by-id!
  [id text lang]
  (let [tx (cond-> []
             text (conj [:db/add id :document/text text]
                        [:db/add id :document/dirty true])
             lang (conj [:db/add id :document/language lang]))]
    (when (not (and (integer? id) (or (nil? text) (string? text)) (or (nil? lang) (string? lang))))
      (log/warn "Invalid parameters for update-document-text-language-by-id! id:" id "text:" text "lang:" lang))
    (d/transact! conn tx)))

(defn document-opened-by-uri!
  [uri]
  (let [tx [[:db/add [:document/uri uri] :document/opened true]]]
    (when (not (string? uri))
      (log/warn "Invalid uri for document-opened-by-uri!:" uri))
    (d/transact! conn tx)))

(defn document-dirty-by-id!
  [id]
  (let [tx [[:db/add id :document/dirty false]]]
    (when (not (integer? id))
      (log/warn "Invalid id for document-dirty-by-id!:" id))
    (d/transact! conn tx)))

(defn update-active-uri!
  [uri]
  (let [tx [{:workspace/active-uri uri
             :type :active-uri}]]
    (doseq [entity tx]
      (when-not (valid-active-uri? entity)
        (log/warn "Invalid active-uri entity:" entity)))
    (d/transact! conn tx)))

(defn create-documents!
  [docs]
  (let [tx (map (fn [doc]
                  {:document/uri (:uri doc)
                   :document/text (:text doc)
                   :document/language (:language doc)
                   :document/version (:version doc)
                   :document/dirty (:dirty doc)
                   :document/opened (:opened doc)
                   :type :document}) docs)]
    (doseq [entity tx]
      (when-not (valid-document? entity)
        (log/warn "Invalid document entity:" entity)))
    (d/transact! conn tx)))

(defn delete-document-by-id!
  [id]
  (let [tx [[:db/retractEntity id]]]
    (when (not (integer? id))
      (log/warn "Invalid id for delete-document-by-id!:" id))
    (d/transact! conn tx)))

(defn first-document-uri
  []
  (first (d/q '[:find ?uri . :where [?e :document/uri ?uri]] @conn)))

(defn active-uri?
  [uri]
  (= uri (active-uri)))

(defn- ensure-document-eid [uri version language text dirty opened]
  (let [eid (document-id-by-uri uri)]
    (if eid
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
            tx [entity]
            tx-report (d/transact! conn tx)]
        (when (not (valid-document? entity))
          (log/warn "Invalid document entity in ensure-document-eid:" entity))
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
    (doseq [entity tx]
      (when-not (valid-diagnostic? entity)
        (log/warn "Invalid diagnostic entity:" entity)))
    tx))

(defn replace-diagnostics-by-uri!
  [uri version diags]
  (let [old-ids (d/q '[:find [?e ...]
                       :in $ ?uri
                       :where
                       [?d :document/uri ?uri]
                       [?e :diagnostic/document ?d]]
                     @conn uri)
        deletions (map (fn [id] [:db/retractEntity id]) old-ids)]
    (if (empty? diags)
      (when (seq deletions) (d/transact! conn deletions))
      (let [doc-eid (ensure-document-eid uri version nil nil false false)
            creations (create-diagnostics diags doc-eid version)]
        (d/transact! conn (concat deletions creations))))))

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
  [doc-eid uri flat-symbols]
  (let [effective-doc-eid (or doc-eid (ensure-document-eid uri nil nil nil false false))
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
                           :db/id (:db/id s)
                           :type :symbol}
                    (:symbol/parent s) (assoc :symbol/parent (:symbol/parent s))))
                flat-symbols)]
    (doseq [entity tx]
      (when-not (valid-symbol? entity)
        (log/warn "Invalid symbol entity:" entity)))
    tx))

(defn replace-symbols!
  [uri symbols]
  (let [doc-eid (document-id-by-uri uri)
        old-ids (when doc-eid
                  (d/q '[:find [?e ...]
                         :in $ ?doc
                         :where [?e :symbol/document ?doc]]
                       @conn doc-eid))
        deletions (map (fn [id] [:db/retractEntity id]) old-ids)]
    (if (empty? symbols)
      (when (seq deletions) (d/transact! conn deletions))
      (let [creations (create-symbols doc-eid uri symbols)]
        (d/transact! conn (concat deletions creations))))))
