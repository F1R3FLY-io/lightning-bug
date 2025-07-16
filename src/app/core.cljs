(ns app.core
  (:require [reagent.dom :as rd]
            [re-frame.core :as rf]
            [app.views.main :refer [root-component]]
            [app.events :as e :refer [initialize]]))

(defn init []
  (rf/dispatch-sync [initialize])
  (rd/render [root-component] (js/document.getElementById "app")))

(defn ^:dev/after-load reload []
  (rf/clear-subscription-cache!)
  (init))
