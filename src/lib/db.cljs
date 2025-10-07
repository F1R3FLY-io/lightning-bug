(ns lib.db
  (:require
   [clojure.spec.alpha :as s]
   [taoensso.timbre :as log]
   [datascript.core :as d]))

(goog-define ^boolean DEBUG true)

(def schema {:symbol/parent {:db/valueType :db.type/ref}
             :symbol/document {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
             :diagnostic/document {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
             :document/uri {:db/unique :db.unique/identity}
             :workspace/active-uri {:db/unique :db.unique/identity}})

(defonce conn (d/create-conn schema))

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
    (when DEBUG
      (doseq [entity tx]
        (when-not (valid-log? entity)
          (log/warn "Invalid log entity:" (s/explain-str ::log entity)))))
    (log/trace "Executing transaction:" tx)
    (d/transact! conn tx)))

(defn document-id-lang-opened-by-uri
  [uri]
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
  []
  (if DEBUG
    (let [uris (d/q '[:find [?uri ...] :where [?e :workspace/active-uri ?uri]] @conn)]
      (when (> (count uris) 1)
        (log/warn "Multiple active URIs found:" uris))
      (first uris))
    (d/q '[:find ?uri .
           :where [?e :workspace/active-uri ?uri]]
         @conn)))

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

(defn active-version
  []
  (d/q '[:find ?version .
         :where [?a :workspace/active-uri ?uri]
                [?e :document/uri ?uri]
                [?e :document/version ?version]]
       @conn))

(defn document-text-by-uri
  [uri]
  (when DEBUG
    (when-not (s/valid? :document/uri uri)
      (log/warn (s/explain-str :document/uri uri))))
  (d/q '[:find ?text .
         :in $ ?uri
         :where [?e :document/uri ?uri]
                [?e :document/text ?text]]
       @conn uri))

(defn document-version-by-id
  [id]
  (when DEBUG
    (when-not (s/valid? ::id id)
      (log/warn (s/explain-str ::id id))))
  (d/q '[:find ?version .
         :in $ ?e
         :where [?e :document/version ?version]]
       @conn id))

(defn document-id-version-by-uri
  [uri]
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
  [uri]
  (when DEBUG
    (when-not (s/valid? :document/uri uri)
      (log/warn (s/explain-str :document/uri uri))))
  (d/q '[:find ?opened .
         :in $ ?uri
         :where [?e :document/uri ?uri]
                [?e :document/opened ?opened]]
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
  (d/q '[:find ?uri ?diag-version ?message ?severity ?start-line ?start-char ?end-line ?end-char
         :keys uri version message severity startLine startChar endLine endChar
         :where [?e :diagnostic/document ?doc]
                [?doc :document/uri ?uri]
                [?doc :document/version ?doc-version]
                [?e :diagnostic/message ?message]
                [?e :diagnostic/severity ?severity]
                [?e :diagnostic/start-line ?start-line]
                [?e :diagnostic/start-char ?start-char]
                [?e :diagnostic/end-line ?end-line]
                [?e :diagnostic/end-char ?end-char]
                (or-join [?e ?diag-version ?doc-version]
                          (and [?e :diagnostic/version ?diag-version]
                               [(= ?diag-version ?doc-version)])
                          (and [(missing? $ ?e :diagnostic/version)]
                               [(identity ?doc-version) ?diag-version])
                          (and [?e :diagnostic/version ?diag-version]
                               [(nil? ?diag-version)]))]
       @conn))

(defn symbols
  []
  (d/q '[:find ?uri
               ?name
               ?kind
               ?start-line
               ?start-char
               ?end-line
               ?end-char
               ?selection-start-line
               ?selection-start-char
               ?selection-end-line
               ?selection-end-char
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
                [?e :symbol/start-line ?start-line]
                [?e :symbol/start-char ?start-char]
                [?e :symbol/end-line ?end-line]
                [?e :symbol/end-char ?end-char]
                [?e :symbol/selection-start-line ?selection-start-line]
                [?e :symbol/selection-start-char ?selection-start-char]
                [?e :symbol/selection-end-line ?selection-end-line]
                [?e :symbol/selection-end-char ?selection-end-char]
                (or-join [?e ?parent]
                          [?e :symbol/parent ?parent]
                          (and [(missing? $ ?e :symbol/parent)]
                              [(ground 0) ?parent]))]
       @conn))

(defn active-uri-text-lang
  []
  (or
   (d/q '[:find [?uri ?text ?lang]
          :where [?a :workspace/active-uri ?uri]
                 [?e :document/uri ?uri]
                 [?e :document/text ?text]
                 [?e :document/language ?lang]]
        @conn)
   [nil nil nil]))

(defn doc-text-version-by-uri
  [uri]
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
  [uri]
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
  [uri]
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
  [uri]
  (when DEBUG
    (when-not (s/valid? :document/uri uri)
      (log/warn (s/explain-str :document/uri uri))))
  (d/q '[:find ?e .
         :in $ ?uri
         :where [?e :document/uri ?uri]]
       @conn uri))

(defn doc-text-lang-by-uri
  [uri]
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

(defn document-language-by-uri
  [uri]
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
  [uri]
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
  [uri]
  (when DEBUG
    (when-not (s/valid? :document/uri uri)
      (log/warn (s/explain-str :document/uri uri))))
  (when-let [[id old-version] (document-id-version-by-uri uri)]
    (let [new-version (inc old-version)
          tx [[:db/add id :document/version new-version]]]
      (when DEBUG
        (when-not (s/valid? :document/version new-version)
          (log/warn "Invalid new-version for uri" uri ":" (s/explain-str :document/version new-version))))
      (log/trace "Executing transaction:" tx)
      (d/transact! conn tx)
      new-version)))

(defn inc-document-version-by-id!
  [id]
  (when DEBUG
    (when-not (s/valid? ::id id)
      (log/warn (s/explain-str ::id id))))
  (let [old-version (document-version-by-id id)]
    (when id
      (let [new-version (inc old-version)
            tx [[:db/add id :document/version new-version]]]
        (when DEBUG
          (when-not (s/valid? :document/version new-version)
            (log/warn "Invalid new-version for id" id ":" (s/explain-str :document/version new-version))))
        (log/trace "Executing transaction:" tx)
        (d/transact! conn tx)
        new-version))))

(defn update-document-dirty-by-id!
  [id dirty?]
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
  [id text]
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
  [uri text]
  (when DEBUG
    (when-not (s/valid? :document/uri uri)
      (log/warn (s/explain-str :document/uri uri)))
    (when-not (s/valid? ::text text)
      (log/warn (s/explain-str ::text text))))
  (when-let [id (document-id-by-uri uri)]
    (let [tx [[:db/add id :document/text text]
              [:db/add id :document/dirty true]]]
      (log/trace "Executing transaction:" tx)
      (d/transact! conn tx))))

(defn update-document-uri-language-by-id!
  [id uri lang]
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
  [id uri]
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
  [id text lang]
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
  [uri]
  (when DEBUG
    (when-not (s/valid? :document/uri uri)
      (log/warn (s/explain-str :document/uri uri))))
  (when-let [id (document-id-by-uri uri)]
    (let [tx [[:db/add id :document/opened true]]]
      (log/trace "Executing transaction:" tx)
      (d/transact! conn tx))))

(defn document-closed-by-uri!
  [uri]
  (when DEBUG
    (when-not (s/valid? :document/uri uri)
      (log/warn (s/explain-str :document/uri uri))))
  (when-let [id (document-id-by-uri uri)]
    (let [tx [[:db/add id :document/opened false]]]
      (log/trace "Executing transaction:" tx)
      (d/transact! conn tx))))

(defn opened-uris-by-lang
  [lang]
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
  [lang]
  (doseq [uri (opened-uris-by-lang lang)]
    (document-closed-by-uri! uri)))

(defn update-active-uri!
  [uri]
  (when DEBUG
    (when-not (s/valid? :workspace/active-uri uri)
      (log/warn (s/explain-str :workspace/active-uri uri))))
  (let [prev-eids (d/q '[:find [?e ...] :where [?e :workspace/active-uri _]] @conn)
        retracts (for [item prev-eids] [:db/retractEntity item])
        add {:workspace/active-uri uri :type :active-uri}
        tx (conj retracts add)]
    (when DEBUG
      (doseq [entity (filter map? tx)]
        (when-not (valid-active-uri? entity)
          (log/warn "Invalid active-uri entity:" (s/explain-str ::active-uri entity)))))
    (log/trace "Executing transaction:" tx)
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
    (when DEBUG
      (doseq [entity tx]
        (when-not (valid-document? entity)
          (log/warn "Invalid document entity:" (s/explain-str ::document entity)))))
    (log/trace "Executing transaction:" tx)
    (d/transact! conn tx)))

(defn delete-document-by-id!
  [id]
  (when DEBUG
    (when-not (s/valid? ::id id)
      (log/warn (s/explain-str ::id id))))
  (let [tx [[:db/retractEntity id]]]
    (log/trace "Executing transaction:" tx)
    (d/transact! conn tx)))

(defn first-document-uri
  []
  (d/q '[:find ?uri . :where [?e :document/uri ?uri]] @conn))

(defn active-uri?
  [uri]
  (when DEBUG
    (when-not (s/valid? :document/uri uri)
      (log/warn (s/explain-str :document/uri uri))))
  (= uri (active-uri)))

(defn- ensure-document-eid [uri version language text dirty opened]
  (if-let [eid (document-id-by-uri uri)]
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
  [uri version diags]
  (when DEBUG
    (when-not (s/valid? :document/uri uri)
      (log/warn (s/explain-str :document/uri uri)))
    (when-not (or (nil? version) (s/valid? ::version version))
      (log/warn (s/explain-str ::version version))))
  (when-let [doc-eid (document-id-by-uri uri)]
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
    (when DEBUG
      (doseq [entity tx]
        (when-not (valid-symbol? entity)
          (log/warn "Invalid symbol entity:" (s/explain-str ::symbol entity)))))
    tx))

(defn replace-symbols!
  [uri symbols]
  (when DEBUG
    (when-not (s/valid? :document/uri uri)
      (log/warn (s/explain-str :document/uri uri))))
  (when-let [doc-eid (document-id-by-uri uri)]
    (let [old-ids (d/q '[:find [?e ...]
                         :in $ ?doc
                         :where [?e :symbol/document ?doc]]
                       @conn doc-eid)
          deletions (for [item old-ids] [:db/retractEntity item])]
      (if (empty? symbols)
        (when (seq deletions)
          (log/trace "Executing transaction:" deletions)
          (d/transact! conn deletions))
        (let [creations (create-symbols doc-eid uri symbols)
              tx (concat deletions creations)]
          (log/trace "Executing transaction:" tx)
          (d/transact! conn tx))))))

(defn diagnostics-by-uri [uri]
  (when DEBUG
    (when-not (s/valid? :document/uri uri)
      (log/warn (s/explain-str :document/uri uri))))
  (d/q '[:find ?uri ?message ?severity ?start-line ?start-char ?end-line ?end-char ?diag-version
         :keys uri message severity startLine startChar endLine endChar version
         :in $ ?uri
         :where [?doc :document/uri ?uri]
                [?doc :document/version ?doc-version]
                [?e :diagnostic/document ?doc]
                [?e :diagnostic/message ?message]
                [?e :diagnostic/severity ?severity]
                [?e :diagnostic/start-line ?start-line]
                [?e :diagnostic/start-char ?start-char]
                [?e :diagnostic/end-line ?end-line]
                [?e :diagnostic/end-char ?end-char]
                (or-join [?e ?diag-version ?doc-version]
                          (and [?e :diagnostic/version ?diag-version]
                               [(= ?diag-version ?doc-version)])
                          (and [(missing? $ ?e :diagnostic/version)]
                               [(identity ?doc-version) ?diag-version])
                          (and [?e :diagnostic/version ?diag-version]
                               [(nil? ?diag-version)]))]
       @conn uri))

(defn symbols-by-uri [uri]
  (when DEBUG
    (when-not (s/valid? :document/uri uri)
      (log/warn (s/explain-str :document/uri uri))))
  (d/q '[:find ?uri
               ?name
               ?kind
               ?start-line
               ?start-char
               ?end-line
               ?end-char
               ?selection-start-line
               ?selection-start-char
               ?selection-end-line
               ?selection-end-char
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
         :in $ ?uri
         :where [?e :symbol/document ?doc]
                [?doc :document/uri ?uri]
                [?e :symbol/name ?name]
                [?e :symbol/kind ?kind]
                [?e :symbol/start-line ?start-line]
                [?e :symbol/start-char ?start-char]
                [?e :symbol/end-line ?end-line]
                [?e :symbol/end-char ?end-char]
                [?e :symbol/selection-start-line ?selection-start-line]
                [?e :symbol/selection-start-char ?selection-start-char]
                [?e :symbol/selection-end-line ?selection-end-line]
                [?e :symbol/selection-end-char ?selection-end-char]
                (or-join [?e ?parent]
                          [?e :symbol/parent ?parent]
                          (and [(missing? $ ?e :symbol/parent)]
                              [(ground 0) ?parent]))]
       @conn uri))

(defn reset-active-uri!
  []
  (let [prev-eids (d/q '[:find [?e ...]
                         :where [?e :workspace/active-uri _]]
                       @conn)]
    (when (seq prev-eids)
      (let [tx (mapv :db/retractEntity prev-eids)]
        (log/trace "Retracting active-uri on destroy:" tx)
        (d/transact! conn tx)))))
