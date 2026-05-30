(ns test.lib.core-test-common
  "Shared fixtures and React-mounting helpers for the core_test suites
  (extracted from core_test so the suite can be split into focused files
  without duplicating the setup)."
  (:require
   [reagent.core :as r]
   ["react" :as react]
   ["react-dom/client" :as rdclient]
   [lib.core :refer [Editor]]
   [lib.workspace :as ws]
   [lib.editor.syntax :as syntax]
   [lib.state :refer [resources]]
   [test.lib.utils :refer [editor->highlight-range!]]))

(def once-fixtures
  {:before (fn []
             (set! (.-onbeforeunload js/window) (fn [] "Prevent test reload")))
   :after (fn []
            (set! (.-onbeforeunload js/window) nil))})

(def each-fixtures
  {:before (fn []
             (reset! resources {:lsp {} :tree-sitter {}})
             @syntax/ts-init-promise
             (reset! syntax/languages {})
             (ws/reset-workspace! @ws/default-workspace))})

(defn flush-render []
  (r/flush))

(defn mount-component [container comp-props]
  (let [root (rdclient/createRoot container)]
    (.render root (react/createElement Editor (clj->js comp-props)))
    (flush-render)
    root))

(defn wrap-flush [f]
  (f)
  (flush-render))

(defn cleanup-container [^js/HTMLDivElement container]
  (when #_{:splint/disable [style/prefer-clj-string]}
        (.contains js/document.body container)
        (js/document.body.removeChild container)))

(defn editor->highlightRange [^js editor ^js from ^js to]
  (editor->highlight-range! editor from to))
