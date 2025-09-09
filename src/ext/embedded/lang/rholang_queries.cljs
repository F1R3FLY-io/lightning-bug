(ns ext.embedded.lang.rholang-queries
  (:require-macros [lib.embedded.macros :refer [embed-text]]))

(def highlights (embed-text "resources/public/extensions/lang/rholang/tree-sitter/queries/highlights.scm"))

(def indents (embed-text "resources/public/extensions/lang/rholang/tree-sitter/queries/indents.scm"))

(defn highlights-query-url []
  (let [blob (js/Blob. #js [highlights] #js {:type "text/plain"})]
    (js/URL.createObjectURL blob)))

(defn indents-query-url []
  (let [blob (js/Blob. #js [indents] #js {:type "text/plain"})]
    (js/URL.createObjectURL blob)))

#_{:splint/disable [naming/lisp-case]}
(def ^:export highlightsQueryUrl highlights-query-url)

#_{:splint/disable [naming/lisp-case]}
(def ^:export indentsQueryUrl indents-query-url)
