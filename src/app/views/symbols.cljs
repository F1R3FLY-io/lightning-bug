(ns app.views.symbols
  (:require [re-frame.core :as rf]
            [re-com.core :as rc]))

(defn symbol-icon [kind]
  (case kind :function [:i.fas fa-cogs] [:i.fas fa-code])) ; Per LSP kind

(defn build-tree [symbols] ; Recursive build hierarchical list
  [:ul (for [s symbols :if no parent]
         [:li.tree-item {:on-click #(navigate-to-range (:range s))}
          [symbol-icon (:kind s)] (:name s)
          (build-tree (:children s))])])

(defn component []
  [:div (build-tree @(rf/subscribe [:lsp/symbols]))])
