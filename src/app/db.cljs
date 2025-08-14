(ns app.db
  (:require
   [datascript.core :as d]
   [ext.lang.rholang :refer [config]]
   [lib.db :as lib-db]))

(defonce ds-conn (d/create-conn lib-db/schema))

(def default-db
  (let [langs {"rholang" config
               "text" {:extensions [".txt"]
                       :fallback-highlighter "none"
                       :file-icon "fas fa-file text-secondary"}}
        default-lang "rholang"]
    {:logs []
     :languages langs
     :default-language default-lang
     :editor {:cursor {:line 1 :column 1}
              :selection nil
              :highlights nil
              :ready false}
     :status nil
     :search {:term "" :results [] :visible? false}
     :modals {:rename {:visible? false :new-name ""}}
     :logs-visible? false
     :logs-height 200
     :workspace {:files {} :active-file nil}}))
