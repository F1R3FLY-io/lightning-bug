(ns test.lib.editor.highlight-test
  (:require
   [clojure.core.async :refer [go]]
   [clojure.test :refer [deftest is async]]
   ["@codemirror/state" :refer [EditorState]]
   ["@codemirror/view" :refer [EditorView]]
   [lib.editor.highlight :as highlight]))

(deftest highlight-plugin-valid
  (async done
    (go
      (let [doc "test content"
            state (.create EditorState #js {:doc doc :extensions #js [highlight/highlight-field highlight/highlight-plugin]})
            view (EditorView. #js {:state state :parent js/document.body})
            plugin-instance (.plugin view highlight/highlight-plugin)]
        (is (some? plugin-instance) "Plugin instance created")
        (is (zero? (.-size (.-decorations plugin-instance))) "No decorations for empty highlight field")
        (.destroy view)
        (done)))))

(defn get-iter
  "Helper to call .iter on RangeSet with type hint."
  [^js range-set from]
  (.iter range-set from))

(deftest highlight-plugin-with-range
  (async done
    (go
      (let [doc "test content"
            range #js {:from #js {:line 1 :column 1} :to #js {:line 1 :column 5}}
            state (.create EditorState #js {:doc doc :extensions #js [highlight/highlight-field highlight/highlight-plugin]})
            view (EditorView. #js {:state state :parent js/document.body})
            plugin-instance (.plugin view highlight/highlight-plugin)]
        (.dispatch view #js {:annotations (.of highlight/highlight-annotation range)})
        (let [decos (.-decorations plugin-instance)]
          (is (= 1 (.-size decos)) "One decoration added for highlight range")
          (let [iter ^js (get-iter decos 0)
                value ^js (.-value iter)]
            (when value
              (let [spec ^js (.-spec value)]
                (is (= "cm-highlight" (.-class spec)) "Decoration has highlight class")))))
        (.destroy view)
        (done)))))

(deftest highlight-plugin-clear
  (async done
    (go
      (let [doc "test content"
            range #js {:from #js {:line 1 :column 1} :to #js {:line 1 :column 5}}
            state (.create EditorState #js {:doc doc :extensions #js [highlight/highlight-field highlight/highlight-plugin]})
            view (EditorView. #js {:state state :parent js/document.body})
            plugin-instance (.plugin view highlight/highlight-plugin)]
        (.dispatch view #js {:annotations (.of highlight/highlight-annotation range)})
        (is (= 1 (.-size (.-decorations plugin-instance))) "Decoration added initially")
        (.dispatch view #js {:annotations (.of highlight/highlight-annotation nil)})
        (is (zero? (.-size (.-decorations plugin-instance))) "Decoration cleared")
        (.destroy view)
        (done)))))

(deftest highlight-plugin-invalid-range
  (async done
    (go
      (let [doc "test content"
            invalid-range #js {:from #js {:line 10 :column 1} :to #js {:line 10 :column 5}} ;; Invalid line
            state (.create EditorState #js {:doc doc :extensions #js [highlight/highlight-field highlight/highlight-plugin]})
            view (EditorView. #js {:state state :parent js/document.body})
            plugin-instance (.plugin view highlight/highlight-plugin)]
        (.dispatch view #js {:annotations (.of highlight/highlight-annotation invalid-range)})
        (let [decos (.-decorations plugin-instance)]
          (is (zero? (.-size decos)) "No decoration added for invalid range"))
        (.destroy view)
        (done)))))
