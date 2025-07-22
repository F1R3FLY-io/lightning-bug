(ns app.views.main
  (:require
   [re-frame.core :as rf]
   [app.views.editor :as editor]))

(defn status-bar []
  (let [name @(rf/subscribe [:active-name])
        cursor @(rf/subscribe [:editor/cursor])]
    [:div.status-bar.d-flex.justify-content-between
     [:span name]
     [:span (str (:line cursor) ":" (:column cursor))]]))

(defn root-component []
  [:div.d-flex.flex-column.vh-100
   [:f> editor/component]
   [status-bar]])
