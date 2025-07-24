(ns app.subs
  (:require
   [app.db :refer [ds-conn]]
   [clojure.string :as str]
   [datascript.core :as d]
   [re-frame.core :as rf]
   [taoensso.timbre :as log]))

(rf/reg-sub :workspace/files
            (fn [db _] (:files (:workspace db))))

(rf/reg-sub :workspace/active-file
            (fn [db _] (:active-file (:workspace db))))

(rf/reg-sub :active-lang
  :<- [:workspace/files]
  :<- [:workspace/active-file]
  (fn [[files active] _]
    (get-in files [active :language])))

(rf/reg-sub :languages
  (fn [db _] (:languages db)))

(rf/reg-sub :default-language
  (fn [db _] (:default-language db)))

(rf/reg-sub :active-name
  :<- [:workspace/files]
  :<- [:workspace/active-file]
  (fn [[files active] _]
    (get-in files [active :name])))

(rf/reg-sub :active-content
  :<- [:workspace/files]
  :<- [:workspace/active-file]
  (fn [[files active] _]
    (get-in files [active :content] "")))

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

(rf/reg-sub
 :lsp/diagnostics
 (fn [_ _]
   (log/debug "Executing Datascript query for diagnostics sub")
   (map first (d/q '[:find (pull ?e [*])
                     :where [?e :type :diagnostic]] @ds-conn))))

(rf/reg-sub
 :lsp/symbols
 (fn [_ _]
   (log/debug "Executing Datascript query for symbols sub")
   (map first (d/q '[:find (pull ?e [*])
                     :where [?e :type :symbol]] @ds-conn))))

(rf/reg-sub :lsp/logs
  (fn [db _] (:lsp/logs db)))

(rf/reg-sub :logs-visible?
  (fn [db _] (:logs-visible? db)))

(rf/reg-sub :logs-height
  (fn [db _] (or (:logs-height db) 200)))

(rf/reg-sub :editor-cursor-pos
  (fn [db _] (:editor-cursor-pos db)))

(rf/reg-sub :highlight-range
  (fn [db _] (:highlight-range db)))
