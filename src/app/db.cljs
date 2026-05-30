(ns app.db
  (:require
   [ext.lang.rholang :refer [language-config]]
   [app.languages :as languages]))

;; Register the demo's languages with app.languages (the demo's language registry),
;; then build the initial app-db from it — so app.languages is the source of truth for
;; the demo's language configuration rather than an unused parallel registry.
(languages/register-language "rholang" language-config)
(languages/register-language "text" {:extensions [".txt"]
                                     :fallback-highlighter "none"
                                     :file-icon "fas fa-file text-secondary"})
(languages/set-default-lang "rholang")

(def default-db
  {:logs []
   :languages @languages/registry
   :default-language @languages/default-lang
   :editor {:cursor {:line 1 :column 1}
            :selection nil
            :highlights nil
            :ready false}
   :status nil
   :search {:term "" :results [] :visible? false}
   :modals {:rename {:visible? false :new-name ""}}
   :logs-visible? false
   :logs-height 200})
