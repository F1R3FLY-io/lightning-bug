(ns app.views.search
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [re-com.core :as rc]
   [app.utils :as u]
   [app.events :as events]))

;; Renders global search panel for code and logs.
(defn component []
  (r/with-let [term (r/atom "")]
    (let [results @(rf/subscribe [:search-results])]
      [rc/v-box :class "search bg-dark"
       :children [[:h6 "Search"]
                  [rc/input-text :placeholder "Search code or logs..." :model term :on-change #(do (reset! term %) (rf/dispatch [::events/search @term]))]
                  [:h6 "Search results:"]
                  [:div.search-results
                   (doall (for [res results]
                            ^{:key (or res (rand-int 1000))} [:div.highlight (u/highlight-text res @term)]))]]])))
