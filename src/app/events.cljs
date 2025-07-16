(ns app.events
  (:require [re-frame.core :as rf]
            [app.db :as db]
            [app.lsp.client :as lsp]
            [clojure.string :as str]
            [datascript.core :as d]
            [app.utils :as u] ;; Assume UUID gen)))

(rf/reg-event-fx :initialize
  (fn [{:keys [db]} _]
    (let [uuid (u/uuid)]
      {:db (assoc-in db [:workspace :files uuid] {:name "untitled" :content "" :language "rholang" :dirty? false})
       :db (assoc db :workspace/active-file uuid)
       :fx [[:dispatch [:lsp/connect]]]}))

(rf/reg-event-db :file/open
  (fn [db} [_ file-id]
    (assoc db :workspace/active-file file-id)))

(rf/reg-event-db :file/add
  (fn [db _]
    (let [uuid (u/uuid)]
      (assoc-in db [:workspace :files uuid] {:name (str "untitled-" (count (:files (:workspace db)))) :content ""}))))

(rf/reg-event-db :file/remove
  (fn [db [_ file-id]]
    (update db :workspace/files dissoc file-id)))

(rf/reg-event-db :file/rename
  (fn [db [_ file-id new-name]]
    (assoc-in db [:workspace :files file-id :name] new-name)))

(rf/reg-event-db :editor/update-content
  (fn [db [_ content]]
    (assoc-in db [:workspace :files (:workspace/active-file db) :content] content)))

(rf/reg-event-fx :lsp/connect
  (fn [{:keys [db]} _]
    (lsp/connect (:lsp/url db))
    {:db db}))

(rf/reg-event-db :lsp/diagnostics-update
  (fn [db [_ diags]]
    (let [conn (:ds-conn db)]
      (d/transact! conn (map #(assoc % :type :diagnostic) diags)))
    (assoc db :lsp/diagnostics diags)))

;; Similar for symbols, logs, etc. Add LSP didChange, etc.
(rf/reg-event-db :log/append
  (fn [db [_ log]]
    (update db :lsp/logs conj log)))
