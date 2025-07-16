(ns app.subs
  (:require [re-frame.core :as rf]
            [datascript.core :as d]))

(rf/reg-sub :workspace/files
            :<- [:workspace]
            (fn [w _] (:files w)))

(rf/reg-sub :active-file
            :<- [:workspace]
            (fn [w _] (:active-file w)))

(rf/reg-sub :active-content
            (fn [_ _]
              [(rf/subscribe [:active-file])])
            (fn [[active] _]
              (get-in db [:workspace :files active :content])))

(rf/reg-sub :symbols-tree
            (fn [db _]
              (d/q '[:find ?s :where [?s :type :symbol]] (:ds-conn db))))

;; Similar for diagnostics, logs (with filter), etc.
(rf/reg-sub :filtered-logs
            :<- [:lsp/logs]
            :<- [:log/search-term] ; assume event for term
            (fn [[logs term] _]
              (filter #(str/includes? (:message %) term) logs)))
