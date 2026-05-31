(ns app.events
  "Re-Frame events for Lightning Bug.

   Uses coeffects for dependency injection and effects for side effects.
   See app.cofx and app.fx for the coeffect and effect definitions."
  (:require
   [clojure.string :as str]
   [re-frame.core :as rf]
   [taoensso.timbre :as log]
   [app.db :refer [default-db]]
   [app.cofx]  ; Register coeffects
   [app.fx]))  ; Register effects

(rf/reg-event-fx
 ::initialize
 (fn [{:keys [_]} _]
   {:db default-db}))

(rf/reg-event-db
 ::handle-editor-event
 (fn [db [_ evt]]
   (let [type (:type evt)
         data (:data evt)
         lang (:lang data)]
     (case type
       "selection-change"
       (-> db
           (assoc-in [:editor :cursor] (:cursor data))
           (assoc-in [:editor :selection] (:selection data)))

       "cursor-change"
       (assoc-in db [:editor :cursor] (:cursor data))

       "highlight-change"
       (assoc-in db [:editor :highlights] data)

       "search-term-change"
       (assoc-in db [:search :term] (:term data))

       "ready"
       (assoc-in db [:editor :ready] true)

       "connect"
       (cond-> db
         lang (assoc-in [:lsp lang :connected?] true))

       "lsp-initialized"
       (cond-> db
         lang (assoc-in [:lsp lang] {:connected? true
                                     :initialized? true}))

       "disconnect"
       (cond-> db
         lang (assoc-in [:lsp lang] {:connected? false
                                     :initialized? false}))

       "lsp-error"
       (assoc db :last-error data)

       "error"
       (assoc db :last-error data)

       db))))

(rf/reg-event-db
 ::set-status
 (fn [db [_ status]]
   (assoc db :status status)))

(rf/reg-event-db
 ::update-cursor
 (fn [db [_ cursor]]
   (assoc-in db [:editor :cursor] cursor)))

(rf/reg-event-db
 ::update-selection
 (fn [db [_ selection]]
   (assoc-in db [:editor :selection] selection)))

(rf/reg-event-fx
 ::run-agent
 (fn [{:keys [db]} _]
   (log/info "Running agent simulation...")
   {:db (assoc db :status :running)
    :fx [[:dispatch-later {:ms 2000 :dispatch [::validate-agent]}]]}))

(rf/reg-event-db
 ::validate-agent
 (fn [db _]
   (log/info "Validating agent...")
   db))

(rf/reg-event-fx
 ::search
 [(rf/inject-cofx :document-repo/active-document)
  (rf/inject-cofx :logs/all)]
 (fn [{:keys [db active-document logs]} [_ term]]
   (let [lterm (str/lower-case term)]
     (if (empty? term)
       {:db (assoc db :search {:term term :results []})}
       (let [text (or (:text active-document) "")
             code-results (filter
                           (fn [line]
                             (str/includes? (str/lower-case line) lterm))
                           (str/split-lines text))
             log-results (filter (fn [log] (str/includes? (str/lower-case log) lterm)) logs)]
         {:db (assoc db :search {:term term :results (concat code-results log-results)})})))))

(rf/reg-event-db
 ::toggle-search
 (fn [db _]
   (update-in db [:search :visible?] not)))

(rf/reg-event-fx
 ::open-rename-modal
 [(rf/inject-cofx :document-repo/active-uri)]
 (fn [{:keys [db active-uri]} _]
   (let [current-name (when active-uri (last (str/split active-uri #"/")))]
     {:db (assoc-in db [:modals :rename] {:visible? true :new-name current-name})})))

(rf/reg-event-db
 ::close-rename-modal
 (fn [db _]
   (assoc-in db [:modals :rename :visible?] false)))

(rf/reg-event-db
 ::set-rename-name
 (fn [db [_ name]]
   (assoc-in db [:modals :rename :new-name] name)))

(rf/reg-event-fx
 ::confirm-rename
 [(rf/inject-cofx :document-repo/active-uri)]
 (fn [{:keys [db active-uri]} _]
   (let [new-name (get-in db [:modals :rename :new-name])]
     (cond-> {:db (assoc-in db [:modals :rename :visible?] false)}
       (and active-uri new-name)
       (assoc :editor/rename-document {:new-uri new-name :old-uri active-uri})))))

(rf/reg-event-db
 ::toggle-logs
 (fn [db _]
   (update db :logs-visible? not)))

(rf/reg-event-db
 ::set-logs-height
 (fn [db [_ height]]
   (assoc db :logs-height height)))

(rf/reg-event-fx
 ::set-editor-cursor
 (fn [_ [_ pos]]
   {:editor/set-cursor pos}))

(rf/reg-event-fx
 ::set-highlight-range
 (fn [_ [_ range]]
   (if range
     {:editor/highlight-range range}
     {:editor/clear-highlight nil})))

(rf/reg-event-db
 ::update-highlights
 (fn [db [_ range]]
   (assoc-in db [:editor :highlights] range)))

(rf/reg-event-db
 ::editor-ready
 (fn [db _]
   (assoc-in db [:editor :ready] true)))
