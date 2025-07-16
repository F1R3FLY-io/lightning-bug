(ns app.views.output
  (:require [re-frame.core :as rf]
            [re-com.core :as rc]))

(defn highlight-log [log]
  [:span {:style {:color (case (:level log) :error "red" :warning "orange" "green")}} (:message log)]) ; Simple

(defn component []
  (let [search (r/atom "")]
    [rc/v-box
     [rc/input-text :model search :on-change #(rf/dispatch [:log/set-search @])]
     [:div.log-terminal {:style {:overflow "auto" :height "200px"}}
      (for [log @(rf/subscribe [:filtered-logs])]
        [highlight-log log])]]))
