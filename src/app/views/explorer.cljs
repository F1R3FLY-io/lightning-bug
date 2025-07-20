(ns app.views.explorer
  (:require
   [re-frame.core :as rf]
   [re-frame.db :refer [app-db]]
   [clojure.string :as str]
   [app.utils :as u]))

;; Returns file icon based on extension/language.
(defn file-icon [name db]
  (let [ext (last (str/split name #"\."))
        lang (some (fn [[k v]] (when (some #{ext} (:extensions v)) k)) (:languages db))]
    (u/icon {:type :file :lang lang :style {:margin-right "5px"}})))

;; Renders file explorer with files list and new file button.
(defn component []
  (let [files @(rf/subscribe [:workspace/files])
        db @app-db]
    [:div.explore.bg-dark
     [:h6 "Explorer"]
     [:ul.list-group.list-group-flush
      (doall (for [[id f] files]
               ^{:key id} [:li.list-group-item.tree-item
                           {:on-click #(rf/dispatch [:app.events/file-open id])}
                           [file-icon (:name f) db] (str (:name f) (when (:dirty? f) " *"))]))]
     [:button.btn.btn-primary.btn-sm.mt-2 {:on-click #(rf/dispatch [:app.events/file-add])} "New File"]]))
