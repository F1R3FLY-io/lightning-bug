(ns app.views.main
  (:require
   [app.views.editor :as editor]
   [re-frame.core :as rf]))

(defn status-bar
  "Renders the status bar with file name and cursor position."
  []
  (let [name @(rf/subscribe [:active-name])
        cursor @(rf/subscribe [:editor/cursor])]
    [:div.status-bar.d-flex.justify-content-between
     [:span name]
     [:span (str (:line cursor) ":" (:column cursor))]]))

(defn root-component
  "Root view component for the application."
  []
  [:div.d-flex.flex-column.vh-100
   [:f> editor/component]
   [status-bar]])
