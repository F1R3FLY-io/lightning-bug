(ns ext.embedded.lang.rholang-queries
  (:require-macros [lib.embedded.macros :refer [embed-text]]))

(def highlights (embed-text "resources/public/extensions/lang/rholang/tree-sitter/queries/highlights.scm"))

(def indents (embed-text "resources/public/extensions/lang/rholang/tree-sitter/queries/indents.scm"))

(defn ^:export highlightsQueryUrl []
  (let [blob (js/Blob. #js [highlights] #js {:type "text/plain"})]
    (js/URL.createObjectURL blob)))

(defn ^:export indentsQueryUrl []
  (let [blob (js/Blob. #js [indents] #js {:type "text/plain"})]
    (js/URL.createObjectURL blob)))
