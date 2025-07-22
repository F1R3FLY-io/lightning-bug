(ns app.subs
  (:require
   [clojure.string :as str]
   [re-frame.core :as rf]
   [datascript.core :as d]
   [taoensso.timbre :as log]
   [app.db :refer [ds-conn]]))

(rf/reg-sub :workspace/files
            (fn [db _] (:files (:workspace db))))

(rf/reg-sub :active-file
            (fn [db _] (:active-file (:workspace db))))

;; Returns the language of the active file.
(rf/reg-sub :active-lang
            (fn [db _]
              (let [workspace (:workspace db)
                    files-by-uri (:files workspace)
                    active-uri (:active-file workspace)
                    active-file (files-by-uri active-uri)]
                (:language active-file))))

(rf/reg-sub :languages
  (fn [db _] (:languages db)))

(rf/reg-sub :default-language
  (fn [db _] (:default-language db)))

;; Returns the name of the active file, or nil if no active file.
(rf/reg-sub :active-name
  :<- [:workspace/files]
  :<- [:active-file]
  (fn [[files active] _]
    (get-in files [active :name])))

(defn active-content-fn [db _]
  (or (get-in db [:workspace :files (:active-file (:workspace db)) :content]) ""))

(rf/reg-sub :active-content active-content-fn)

(rf/reg-sub :lsp/connected?
  (fn [db _] (get-in db [:lsp :connection])))

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

(rf/reg-sub :editor/cursor
  (fn [db _] (get-in db [:editor :cursor])))

;; Manual query/pull since single conn (avoids posh issues).
(rf/reg-sub
 :lsp/diagnostics
 (fn [_ _]
   (d/q '[:find (pull ?e [*])
          :where [?e :type :diagnostic]] @ds-conn)))

(rf/reg-sub
 :lsp/symbols
 (fn [_ _]
   (d/q '[:find (pull ?e [*])
          :where [?e :type :symbol]] @ds-conn)))

(rf/reg-sub :lsp/logs
  (fn [db _] (:lsp/logs db)))
