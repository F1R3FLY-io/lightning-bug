(ns app.views.diagnostics
  (:require
   [re-frame.core :as rf]
   [re-posh.core :as rp]
   [app.utils :as u]))

;; Returns icon for diagnostic severity.
(defn diag-icon [severity]
  (u/icon {:type :diag :severity severity :style {:margin-right "5px"}}))

;; Renders diagnostics panel; shows success if no diagnostics.
(defn component []
  (let [connected? @(rf/subscribe [:lsp/connected?])
        diags (try @(rp/subscribe [:lsp/diagnostics]) (catch :default _ []))]
    (when connected?
      [:div.display-history.bg-dark
       [:h6 "Display History"]
       (if (empty? diags)
         [:span.diagnostic-success "Validation Success"]
         [:ul.list-group.list-group-flush
          (doall (for [diag diags]
                   ^{:key (or (:message diag) (rand-int 1000))} [:li.list-group-item.tree-item
                                                                 {:on-click #(u/navigate-to (:range diag))
                                                                  :title (:message diag)}
                                                                 [diag-icon (:severity diag)] (str (subs (or (:message diag) "") 0 50) "...")]))])])))
