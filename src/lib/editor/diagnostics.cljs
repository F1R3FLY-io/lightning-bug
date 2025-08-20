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

(defn severity-class
  "Maps LSP diagnostic severity to a CSS class for underlining.
  - 1: Error (red wavy)
  - 2: Warning (orange wavy)
  - 3: Information (blue dotted)
  - 4: Hint (gray dotted)"
  [severity]
  (case severity
    1 "error"
    2 "warning"
    3 "info"
    4 "hint"
    ""))

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
                                   :severity (severity-class (.-severity diag))
                                   :message (.-message diag)})
                            diags))))))

(def extensions #js [diagnostic-field diagnostic-lint (lintGutter)])
