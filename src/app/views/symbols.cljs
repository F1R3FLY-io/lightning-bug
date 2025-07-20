(ns app.views.symbols
  (:require
   [re-posh.core :as rp]
   [app.utils :as u]))

;; Returns icon for symbol kind (LSP SymbolKind).
(defn symbol-icon [kind]
  (u/icon {:type :symbol :kind kind :style {:margin-right "5px"}}))

;; Recursively builds tree view from flat symbols with parent refs; prevents cycles with seen set.
(defn build-tree [symbols parent-id seen]
  (when-not (contains? seen parent-id)
    [:ul.list-unstyled
     (doall (for [s (filter #(= (:parent %) parent-id) symbols)]
              ^{:key (:db/id s)} [:li.tree-item {:on-click #(u/navigate-to (:range s))}
                                  [symbol-icon (:kind s)] (:name s)
                                  (build-tree symbols (:db/id s) (conj seen (:db/id s)))]))]))

;; Renders symbols outline as "Test Agent" panel.
(defn component []
  (let [symbols (try @(rp/subscribe [:lsp/symbols]) (catch :default _ []))]
    [:div.test-agent.bg-dark
     [:h6 "Test Agent"]
     (build-tree symbols nil #{})]))
