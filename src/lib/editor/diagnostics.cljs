(ns lib.editor.diagnostics
  (:require
   ["@codemirror/state" :refer [Annotation StateField RangeSetBuilder]]
   ["@codemirror/view" :refer [Decoration ViewPlugin]]
   [clojure.string :as str]
   [lib.utils :as u]
   [taoensso.timbre :as log]))

;; Annotation to mark transactions that update diagnostics in the StateField.
(def diagnostic-annotation (.define Annotation))

;; StateField to hold the current list of LSP diagnostics.
(def diagnostic-field
  (.define StateField
    #js {:create (fn [_] #js []) ;; Initial empty array of diagnostics.
         :update (fn [value ^js tr]
                   ;; Update with new diagnostics if the transaction has the annotation, else keep current.
                   (if-let [new-diags (.annotation tr diagnostic-annotation)]
                     new-diags
                     value))}))

(defn severity-class
  "Maps LSP diagnostic severity to a CSS class for underlining.
  - 1: Error (red wavy)
  - 2: Warning (orange wavy)
  - 3: Information (blue dotted)
  - 4: Hint (gray dotted)"
  [severity]
  (case severity
    1 "cm-error-underline"
    2 "cm-warning-underline"
    3 "cm-info-underline"
    4 "cm-hint-underline"
    ""))

(defn build-decorations
  "Builds a RangeSet of decorations for underlining diagnostics in the visible viewport."
  [^js view]
  (let [builder (RangeSetBuilder.)
        diags (.field (.-state view) diagnostic-field)
        ^js doc (.-doc (.-state view))]
    (doseq [^js diag diags]
      (let [^js range (.-range diag)]
        (if (or (nil? range) (nil? (.-start range)) (nil? (.-end range)))
          (log/warn "Invalid diagnostic range; skipping decoration:" diag)
          (let [^js start (.-start range)
                ^js end (.-end range)
                from (u/pos-to-offset doc {:line (or (.-line start) 0)
                                           :column (or (.-character start) 0)} false)
                to (u/pos-to-offset doc {:line (or (.-line end) 0)
                                         :column (or (.-character end) 0)} false)
                cls (severity-class (or (.-severity diag) 0))]
            (if (or (nil? from) (nil? to) (> from to) (str/blank? cls))
              (log/trace "Skipping invalid decoration for diag:" diag)
              (.add builder from to (.mark Decoration #js {:class cls})))))))
    (.finish builder)))

(def diagnostic-plugin
  "ViewPlugin that renders underlines for diagnostics using decorations."
  (let [PluginClass (fn [^js view]
                      (this-as ^js this
                        (set! (.-decorations this) (build-decorations view))
                        this))]
    (set! (.-prototype ^js PluginClass) (js/Object.create js/Object.prototype))
    (set! (.-update (.-prototype ^js PluginClass))
          (fn [^js update]
            (this-as ^js this
              (when (or (.-docChanged update) (.-viewportChanged update)
                        (not= (.field (.-startState update) diagnostic-field)
                              (.field (.-state update) diagnostic-field)))
                (set! (.-decorations this) (build-decorations (.-view update)))))))
    (.fromClass ViewPlugin PluginClass #js {:decorations (fn [^js instance] (.-decorations instance))})))
