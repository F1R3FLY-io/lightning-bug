(ns app.db
  (:require
   [ext.lang.rholang :refer [language-config]]))

(def default-db
  (let [langs {"rholang" language-config
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
     :logs-height 200}))
