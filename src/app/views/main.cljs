(ns app.views.main
  (:require
   [app.views.editor :as editor]
   [app.views.error-boundary :as error-boundary]
   [app.views.logs :as logs]
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
   [error-boundary/component {:children [:f> editor/component]}]
   [status-bar]
   [:f> logs/component]])
