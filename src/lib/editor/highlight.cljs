(ns lib.editor.highlight
  (:require
   ["@codemirror/state" :refer [Annotation StateField RangeSetBuilder]] ;; Corrected import for RangeSetBuilder
   ["@codemirror/view" :refer [Decoration ViewPlugin]]
   [lib.utils :as u]
   [taoensso.timbre :as log]))

;; Annotation to mark transactions that update the highlight range in the StateField.
(def highlight-annotation (.define Annotation))

;; StateField to hold the current highlight range (or nil if no highlight).
(def highlight-field
  (.define StateField
    #js {:create (fn [_] nil)
         :update (fn [value ^js tr]
                   (or (.annotation tr highlight-annotation) value))}))

(defn- build-decorations
  "Builds a RangeSet of decorations for highlighting a specific range in the editor.
   Returns an empty RangeSet if no range is set or if the range is invalid."
  [^js view]
  (let [builder (RangeSetBuilder.)
        range (.field (.-state view) highlight-field)]
    (when range
      (let [{:keys [from to]} range
            ^js doc (.-doc (.-state view))
            from-offset (u/pos-to-offset doc from true)
            to-offset (u/pos-to-offset doc to true)]
        (if (and from-offset to-offset (<= from-offset to-offset))
          (do
            (log/trace "Building highlight decoration from" from-offset "to" to-offset)
            (.add builder from-offset to-offset (.mark Decoration #js {:class "cm-highlight"})))
          (log/warn "Invalid highlight range: from" from-offset "to" to-offset))))
    (.finish builder)))

(def highlight-plugin
  "ViewPlugin that renders highlights for a specified range, updating on document or viewport changes."
  (let [PluginClass (fn [^js view]
                      (this-as ^js this
                        (set! (.-decorations this) (build-decorations view))
                        this))]
    (set! (.-prototype ^js PluginClass) (js/Object.create js/Object.prototype))
    (set! (.-update (.-prototype ^js PluginClass))
          (fn [^js update]
            (this-as ^js this
              (when (or (.-docChanged update) (.-viewportChanged update)
                        (not= (.field (.-startState update) highlight-field)
                              (.field (.-state update) highlight-field)))
                (log/trace "Updating highlight decorations due to" (cond
                                                                    (.-docChanged update) "document change"
                                                                    (.-viewportChanged update) "viewport change"
                                                                    :else "highlight field change"))
                (set! (.-decorations this) (build-decorations (.-view update)))))))
    (.fromClass ViewPlugin PluginClass #js {:decorations (fn [^js instance] (.-decorations instance))})))
