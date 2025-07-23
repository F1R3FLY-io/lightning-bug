(ns app.db
  (:require
   [clojure.spec.alpha :as s]
   [datascript.core :as d]
   [taoensso.timbre :as log]
   [app.extensions.lang.rholang]
   [app.languages :as langs]))

(def schema {:symbol/parent {:db/valueType :db.type/ref}
             :symbol/range {:db/cardinality :db.cardinality/one}
             :diagnostic/range {:db/cardinality :db.cardinality/one}})

(defonce ds-conn (d/create-conn schema))

(s/def ::config (s/keys :req-un [::extensions]
                        :opt-un [::grammar-wasm
                                 ::highlight-query-path
                                 ::lsp-url
                                 ::file-icon
                                 ::fallback-highlighter]))

(s/def ::languages (s/map-of string? ::config))

(def default-db
  (let [langs @langs/registry
        default-lang (or (first (keys langs)) "text")
        langs (if (empty? langs)
                {"text" {:extensions [".txt"]
                         :fallback-highlighter "none"
                         :file-icon "fas fa-file text-secondary"}}
                langs)]
    (when-not (every? string? (keys langs))
      (log/warn "Non-string keys found in languages registry:" (keys langs)))
    (when-not (s/valid? ::languages langs)
      (throw (ex-info "Invalid language configs" {:explain (s/explain-data ::languages langs)})))
    {:workspace {:files {} :active-file nil}
     :lsp {:connection false
           :logs []}
     :languages langs
     :default-language default-lang
     :editor {:cursor {:line 1 :column 1}}
     :status nil
     :search {:term "" :results [] :visible? false}
     :modals {:rename {:visible? false :new-name ""}}
     :ds-conn ds-conn}))
