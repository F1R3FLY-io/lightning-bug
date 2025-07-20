(ns app.views.output
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [re-com.core :as rc]
   [app.utils :as u]))

;; Renders a highlighted log message.
(defn highlight-log [log]
  [:div {:class (u/log-class (:level log))}
   (:message log)])

;; Renders log history panel with search filter.
(defn component []
  (r/with-let [query-term (r/atom "")]
    (let [logs @(rf/subscribe [:filtered-logs @query-term])]
      [rc/v-box :class "log-history bg-dark"
       :children [[:h6 "Log History"]
                  [rc/input-text :placeholder "Search logs..." :model query-term :on-change #(reset! query-term %)]
                  [:div.log-terminal
                   (doall (for [log logs]
                            ^{:key (or (:message log) (rand-int 1000))} [highlight-log log]))]]])))
