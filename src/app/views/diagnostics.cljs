(ns app.views.diagnostics
  (:require [re-frame.core :as rf]
            [re-com.core :as rc]))

(defn component []
  [:ul.list-group
   (for [diag @(rf/subscribe [:lsp/diagnostics])]
     [:li.list-group-item {:on-click #(navigate-to (:location diag))
                           :on-mouse-over #(rc/popover-tooltip :content (:message diag))}
      [:i.fas (if (= (:severity diag) "error") fa-exclamation-circle fa-info-circle)] (:message diag)])])
