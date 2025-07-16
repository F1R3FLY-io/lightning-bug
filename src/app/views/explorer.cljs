(ns app.views.explorer
  (:require [re-frame.core :as rf]
            [re-com.core :as rc]))

(defn file-icon [name]
  (let [ext (last (str/split name #"\."))]
    (cond (= ext "rholang") [:i.fas fa-code]
          :else [:i.fas fa-file]])))

(defn component []
  [:div.explorer
   [:ul.list-group
    (for [[id f] @(rf/subscribe [:workspace/files])]
    [:li.list-group-item.tree-item {:on-click #(rf/dispatch [:file/open id])}
     [file-icon (:name f)] (:name f)
     [:button.btn btn-sm {:on-click #(rc/popup-modal {:content [rc/input-text {:model (:name f) :on-change #(rf/dispatch [:file/rename id %])}]})} "Rename"]
     [:button {:on-click #(rf/dispatch [:file/remove id])} "Remove"]]]
   [:button.btn btn-primary {:on-click #(rf/dispatch [:file/add])} "Add File"]]])
