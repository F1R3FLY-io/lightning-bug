(ns test.app.views-test
  (:require
   [clojure.test :refer [deftest is use-fixtures]]
   [reagent.core :as r]
   ["react-dom/client" :as rd]
   [re-frame.core :as rf]
   [re-frame.db :refer [app-db]]
   [datascript.core :as d]
   [app.db :refer [ds-conn]]
   [app.events :as events]
   [re-posh.core :as rp]
   [app.views.diagnostics :as diags]
   [app.views.editor :as editor]
   [app.views.explorer :as exp]
   [app.views.main :as main]
   [app.views.output :as out]
   [app.views.search :as search]
   [app.views.symbols :as syms]))

(use-fixtures :once
  {:before (fn []
             (reset! app-db {})
             (d/reset-conn! ds-conn (d/empty-db (:schema (meta ds-conn))))
             (rp/dispatch-sync [::events/initialize])
             (rp/connect! ds-conn)
             (swap! app-db assoc-in [:lsp :connection] true))
   :after (fn [])})

(deftest diagnostics-render
  (let [container (js/document.createElement "div")]
    (js/document.body.appendChild container)
    (let [root (rd/createRoot container)]
      (.render root (r/as-element [diags/component]))
      (r/flush)
      (js/setTimeout (fn []
                       (is (some? (.querySelector container ".display-history")) "Renders display history container")
                       (is (some? (.querySelector container "h6")) "Renders header")
                       (is (some? (.querySelector container ".diagnostic-success")) "Shows success when no diagnostics")) 0)
      (.unmount root))
    (js/document.body.removeChild container)))

(deftest editor-render
  (let [container (js/document.createElement "div")]
    (js/document.body.appendChild container)
    (let [root (rd/createRoot container)]
      (.render root (r/as-element [editor/component]))
      (r/flush)
      (js/setTimeout (fn []
                       (is (some? (.querySelector container ".code-editor")) "Renders CodeMirror editor container")
                       (is (some? (.querySelector container ".status-bar")) "Renders status bar")) 0)
      (.unmount root))
    (js/document.body.removeChild container)))

(deftest explorer-render
  (let [container (js/document.createElement "div")]
    (js/document.body.appendChild container)
    (let [root (rd/createRoot container)]
      (.render root (r/as-element [exp/component]))
      (r/flush)
      (js/setTimeout (fn []
                       (is (some? (.querySelector container ".explore")) "Renders explorer container")
                       (is (some? (.querySelector container "h6")) "Renders header")
                       (is (some? (.querySelector container ".list-group")) "Renders file list")
                       (is (some? (.querySelector container "button.btn.btn-primary")) "Renders new file button")) 0)
      (.unmount root))
    (js/document.body.removeChild container)))

(deftest main-render
  (let [container (js/document.createElement "div")]
    (js/document.body.appendChild container)
    (let [root (rd/createRoot container)]
      (.render root (r/as-element [main/root-component]))
      (r/flush)
      (js/setTimeout (fn []
                       (is (some? (.querySelector container ".container-fluid")) "Renders main layout")
                       (rf/dispatch-sync [:app.events/toggle-search])
                       (is (some? (.querySelector container ".search")) "Renders search pane when visible")) 0)
      (.unmount root))
    (js/document.body.removeChild container)))

(deftest output-render
  (let [container (js/document.createElement "div")]
    (js/document.body.appendChild container)
    (let [root (rd/createRoot container)]
      (.render root (r/as-element [out/component]))
      (r/flush)
      (js/setTimeout (fn []
                       (is (some? (.querySelector container ".log-history")) "Renders log history container")
                       (is (some? (.querySelector container "h6")) "Renders header")
                       (is (some? (.querySelector container "input")) "Renders search input for logs")
                       (is (some? (.querySelector container ".log-terminal")) "Renders log terminal area")) 0)
      (.unmount root))
    (js/document.body.removeChild container)))

(deftest search-render
  (let [container (js/document.createElement "div")]
    (js/document.body.appendChild container)
    (let [root (rd/createRoot container)]
      (.render root (r/as-element [search/component]))
      (r/flush)
      (js/setTimeout (fn []
                       (is (some? (.querySelector container ".search")) "Renders search container")
                       (is (some? (.querySelector container "h6")) "Renders header")
                       (is (some? (.querySelector container "input")) "Renders search input")
                       (is (some? (.querySelector container ".search-results")) "Renders results area")) 0)
      (.unmount root))
    (js/document.body.removeChild container)))

(deftest symbols-render
  (let [container (js/document.createElement "div")]
    (js/document.body.appendChild container)
    (let [root (rd/createRoot container)]
      (.render root (r/as-element [syms/component]))
      (r/flush)
      (js/setTimeout (fn []
                       (is (some? (.querySelector container ".test-agent")) "Renders symbols/test agent container")
                       (is (some? (.querySelector container "h6")) "Renders header")
                       (is (some? (.querySelector container "ul.list-unstyled")) "Renders recursive tree structure")) 0)
      (.unmount root))
    (js/document.body.removeChild container)))
