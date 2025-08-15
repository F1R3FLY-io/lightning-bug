(ns lib.editor.diagnostics
  (:require
   ["@codemirror/lint" :refer [lintGutter linter]]
   ["@codemirror/state" :refer [StateField StateEffect]]
   [lib.utils :as u]))

;; StateEffect to set new diagnostics in the field.
(def set-diagnostic-effect (.define StateEffect))

;; StateField to hold the current list of LSP diagnostics (array).
(def diagnostic-field
  (.define StateField
           #js {:create (fn [_] #js [])
                :update (fn [^js value
                            ^js tr]
                          (reduce (fn [v ^js e]
                                    (if (.is e set-diagnostic-effect)
                                      (.-value e)
                                      v))
                                  value (.-effects tr)))}))

(def diagnostic-lint
  (linter (fn [view]
            (let [diags (.field ^js (.-state view) diagnostic-field false)]
              (clj->js (map (fn [^js diag]
                              #js {:from (u/pos-to-offset (.-doc ^js (.-state view))
                                                          {:line (inc (.-startLine diag))
                                                           :column (inc (.-startChar diag))}
                                                          true)
                                   :to (u/pos-to-offset (.-doc ^js (.-state view))
                                                        {:line (inc (.-endLine diag))
                                                         :column (inc (.-endChar diag))}
                                                        true)
                                   :severity (case (.-severity diag)
                                               1 "error"
                                               2 "warning"
                                               3 "info"
                                               "hint")
                                   :message (.-message diag)})
                            diags))))))

(def extensions #js [diagnostic-field diagnostic-lint (lintGutter)])
