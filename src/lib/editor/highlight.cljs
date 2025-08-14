(ns lib.editor.highlight
  (:require
   ["@codemirror/state" :refer [Annotation RangeSetBuilder StateField]]
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
        state (.-state view)
        range (.field state highlight-field false)]
    (if (identical? range js/undefined)
      (do (log/warn "Highlight field not present in state")
          (.finish builder))
      (if (nil? range)
        (.finish builder)
        (let [{:keys [from to]} range
              ^js doc (.-doc state)
              from-offset (u/pos-to-offset doc from true)
              to-offset (u/pos-to-offset doc to true)]
          (if (and from-offset to-offset (<= from-offset to-offset))
            (.add builder from-offset to-offset (.mark Decoration #js {:class "cm-highlight"}))
            (log/warn "Invalid highlight range: from" from-offset "to" to-offset))
          (.finish builder))))))

(def highlight-plugin
  "ViewPlugin that renders highlights for a specified range, updating on document or viewport changes."
  (.define ViewPlugin
    (fn [^js view]
      #js {:decorations (build-decorations view)
           :update (fn [^js update]
                     (let [rebuilding? (or (.-docChanged update) (.-viewportChanged update)
                                           (not= (.field (.-startState update) highlight-field false)
                                                 (.field (.-state update) highlight-field false)))]
                       (when rebuilding?
                         (this-as ^js self
                                  (set! (.-decorations self) (build-decorations (.-view update)))))))})
    #js {:decorations (fn [^js value]
                        (.-decorations value))}))
