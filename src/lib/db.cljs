(ns lib.db
  (:require
   [clojure.spec.alpha :as s]
   [taoensso.timbre :as log]))

(def schema {:symbol/parent {:db/valueType :db.type/ref}})

(s/def :document/uri string?)
(s/def :document/version nat-int?)
(s/def :diagnostic/message string?)
(s/def :diagnostic/severity pos-int?)
(s/def :diagnostic/start-line nat-int?)
(s/def :diagnostic/start-char nat-int?)
(s/def :diagnostic/end-line nat-int?)
(s/def :diagnostic/end-char nat-int?)
(s/def ::diagnostic
  (s/and
   (s/keys :req [:document/uri
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
   (s/keys :req [:symbol/name
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

(defn valid-diagnostic? [data]
  (valid? ::diagnostic data))

(defn valid-symbol? [data]
  (valid? ::symbol data))

(defn flatten-symbols
  "Flattens hierarchical LSP symbols into a list for datascript transaction,
  assigning negative db/ids to avoid conflicts."
  [symbols parent-id]
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
                              s' (cond-> (assoc (dissoc s :name :kind :range :selectionRange :children)
                                                :db/id sid
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

(defn flatten-diags
  [diag-params]
  (let [uri (:uri diag-params)
        version (:version diag-params)]
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
     (:diagnostics diag-params))))

(defn diagnostics-tx
  [diags]
  (let [tx (map (fn [d]
                  (cond-> {:document/uri (:uri d)
                           :diagnostic/message (:message d)
                           :diagnostic/severity (:severity d)
                           :diagnostic/start-line (:startLine d)
                           :diagnostic/start-char (:startChar d)
                           :diagnostic/end-line (:endLine d)
                           :diagnostic/end-char (:endChar d)
                           :type :diagnostic}
                    (:version d) (assoc :document/version (:version d)))) diags)
        valid-tx (filter valid-diagnostic? tx)]
    (when-not (= (count tx) (count valid-tx))
      (log/warn "Filtered" (- (count tx) (count valid-tx)) "invalid diagnostics"))
    valid-tx))

(defn symbols-tx
  [hier-symbols]
  (let [tx (map (fn [s]
                  (cond-> {:symbol/name (:symbol/name s)
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
                    (:symbol/parent s) (assoc :symbol/parent (:symbol/parent s)))) hier-symbols)
        valid-tx (filter valid-symbol? tx)]
    (when-not (= (count tx) (count valid-tx))
      (log/warn "Filtered" (- (count tx) (count valid-tx)) "invalid symbols"))
    valid-tx))
