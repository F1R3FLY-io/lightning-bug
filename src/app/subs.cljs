(ns app.subs
  (:require
   [clojure.string :as str]
   [re-frame.core :as rf]
   [re-posh.core :as rp]))

(rf/reg-sub :workspace/files
  (fn [db _] (:files (:workspace db))))

(rf/reg-sub :active-file
  (fn [db _] (:active-file (:workspace db))))

;; Returns the language of the active file.
(rf/reg-sub :active-lang
  (fn [db _] (get-in db [:workspace :files (:active-file db) :language])))

(rf/reg-sub :languages
  (fn [db _] (:languages db)))

(defn active-content-fn [db _]
  (or (get-in db [:workspace :files (:active-file (:workspace db)) :content]) ""))

(rf/reg-sub :active-content active-content-fn)

(rp/reg-sub
 :lsp/diagnostics
 (fn [_ _]
   {:type :query
    :query '[:find (pull ?e [*])
             :where [?e :type :diagnostic]]}))

(rp/reg-sub
 :lsp/symbols
 (fn [_ _]
   {:type :query
    :query '[:find (pull ?e [*])
             :where [?e :type :symbol]]}))

(rf/reg-sub :lsp/logs
  (fn [db _] (:lsp/logs db)))

(rf/reg-sub :lsp/connected?
  (fn [db _] (some? (get-in db [:lsp :connection]))))

(rf/reg-sub :search/visible?
  (fn [db _] (:visible? (:search db))))

(rf/reg-sub :filtered-logs
  :<- [:lsp/logs]
  (fn [logs [_ term]]
    (filter #(str/includes? (:message %) term) logs)))

(rf/reg-sub :status
  (fn [db _] (:status db)))

(rf/reg-sub :search-results
  (fn [db _] (:results (:search db))))

(rf/reg-sub :rename/visible?
  (fn [db _]
    (get-in db [:modals :rename :visible?])))

(rf/reg-sub :rename/new-name
  (fn [db _]
    (get-in db [:modals :rename :new-name])))
