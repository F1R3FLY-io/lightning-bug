(ns app.events
  (:require
   [clojure.string :as str]
   [re-frame.core :as rf]
   [taoensso.timbre :as log]
   [app.db :refer [default-db]]
   [app.shared :refer [editor-ref-atom]]
   [lib.db :as lib-db]))

(rf/reg-event-fx
 ::initialize
 (fn [{:keys [_]} _]
   {:db default-db}))

(rf/reg-event-db
 ::handle-editor-event
 (fn [db [_ evt]]
   (let [type (:type evt)
         _data (:data evt)]
     (case type
       ;; placeholder
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

(rf/reg-event-db
 ::search
 (fn [db [_ term]]
   (let [lterm (str/lower-case term)]
     (if (empty? term)
       (assoc db :search {:term term :results []})
       (let [text (or (lib-db/active-text) "")
             logs (lib-db/logs)
             code-results (filter
                           (fn [line]
                             (str/includes? (str/lower-case line) lterm))
                           (str/split-lines text))
             log-results (filter (fn [log] (str/includes? (str/lower-case log) lterm)) logs)]
         (assoc db :search {:term term :results (concat code-results log-results)}))))))

(rf/reg-event-db
 ::toggle-search
 (fn [db _]
   (update-in db [:search :visible?] not)))

(rf/reg-event-db
 ::open-rename-modal
 (fn [db _]
   (let [uri (lib-db/active-uri)
         current-name (when uri (last (str/split uri #"/")))]
     (assoc-in db [:modals :rename] {:visible? true :new-name current-name}))))

(rf/reg-event-db
 ::close-rename-modal
 (fn [db _]
   (assoc-in db [:modals :rename :visible?] false)))

(rf/reg-event-db
 ::set-rename-name
 (fn [db [_ name]]
   (assoc-in db [:modals :rename :new-name] name)))

(rf/reg-event-db
 ::confirm-rename
 (fn [db _]
   (let [active (lib-db/active-uri)
         new-name (get-in db [:modals :rename :new-name])]
     (when (and active new-name)
       (when-let [^js editor (some-> @editor-ref-atom .-current)]
         (.renameDocument editor new-name active)))
     (rf/dispatch [::close-rename-modal])
     db)))

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
 (fn [{:keys [_db]} [_ pos]]
   {:set-editor-cursor pos}))

(rf/reg-fx
 :set-editor-cursor
 (fn [pos]
   (when-let [^js er (.-current @editor-ref-atom)]
     (when (.isReady er)
       (.setCursor er (clj->js pos))))))

(rf/reg-event-fx
 ::set-highlight-range
 (fn [{:keys [_db]} [_ range]]
   {:set-editor-highlight range}))

(rf/reg-fx
 :set-editor-highlight
 (fn [range]
   (when-let [^js er (.-current @editor-ref-atom)]
     (when (.isReady er)
       (if range
         (let [{:keys [from to]} range]
           (.highlightRange er (clj->js from) (clj->js to)))
         (.clearHighlight er))))))

(rf/reg-event-db
 ::update-highlights
 (fn [db [_ range]]
   (assoc-in db [:editor :highlights] range)))

(rf/reg-event-db
 ::editor-ready
 (fn [db _]
   (assoc-in db [:editor :ready] true)))
