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
  (fn [db _]
    (let [lang (get-in db [:workspace :files (get-in db [:workspace :active-file]) :language] "text")]
      (get-in db [:lsp lang :connection]))))

(rf/reg-sub :search/visible?
  (fn [db _] (:visible? (:search db))))

(rf/reg-sub :filtered-logs
  (fn [db [_ term]]
    (filter #(str/includes? (:message %) term) (:logs db))))

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

(rf/reg-sub :editor/selection
  (fn [db _] (get-in db [:editor :selection])))

(rf/reg-sub
 :lsp/diagnostics
 (fn [_ _]
   (map first (d/q '[:find (pull ?e [*])
                     :where [?e :type :diagnostic]] @ds-conn))))

(rf/reg-sub
 :lsp/symbols
 (fn [_ _]
   (map first (d/q '[:find (pull ?e [*])
                     :where [?e :type :symbol]] @ds-conn))))

(rf/reg-sub :logs
  (fn [db _] (:logs db)))

(rf/reg-sub :logs-visible?
  (fn [db _] (:logs-visible? db)))

(rf/reg-sub :logs-height
  (fn [db _] (or (:logs-height db) 200)))

(rf/reg-sub
 :editor/highlights
 (fn [db _] (get-in db [:editor :highlights])))

(rf/reg-sub
 :editor/ready
 (fn [db _] (get-in db [:editor :ready])))
