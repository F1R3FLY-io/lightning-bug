(ns app.events
  (:require
   [clojure.string :as str]
   [datascript.core :as d]
   [re-frame.core :as rf]
   [re-posh.core :as rp]
   [taoensso.timbre :as log]
   [app.db :refer [default-db ds-conn]]
   [app.shared :refer [editor-ref-atom]]
   [lib.db :refer [diagnostics-tx symbols-tx]]
   [lib.utils :refer [split-uri]]))

(rf/reg-event-fx
 ::initialize
 (fn [{:keys [_]} _]
   {:db default-db}))

(rf/reg-event-db
 ::handle-editor-event
 (fn [db [_ evt]]
   (let [type (:type evt)
         data (:data evt)]
     (case type
       "document-open" (let [uri (:uri data)
                             [_ path] (split-uri uri)
                             name (last (str/split path #"/"))
                             lang (:language data)
                             content (:content data)]
                         (-> db
                             (assoc-in [:workspace :files uri] {:name name :content content :language lang :dirty? false})
                             (assoc-in [:workspace :active-file] uri)))
       "content-change" db ;; Remove immediate update, let debounced handle.
       "document-close" (let [uri (:uri data)]
                          (-> db
                              (update-in [:workspace :files] dissoc uri)
                              (assoc-in [:workspace :active-file] (if (= uri (:active-file (:workspace db)))
                                                                    (first (keys (:files (:workspace db))))
                                                                    (:active-file (:workspace db))))))
       "document-rename" (let [old-uri (:old-uri data)
                               new-uri (:new-uri data)
                               [_ new-path] (split-uri new-uri)
                               new-name (last (str/split new-path #"/"))]
                           (if (contains? (get-in db [:workspace :files]) old-uri)
                             (-> db
                                 (update-in [:workspace :files] assoc new-uri (assoc (get-in db [:workspace :files old-uri]) :name new-name))
                                 (update-in [:workspace :files] dissoc old-uri)
                                 (assoc-in [:workspace :active-file] (if (= old-uri (:active-file (:workspace db)))
                                                                       new-uri
                                                                       (:active-file (:workspace db)))))
                             db))
       "document-save" (let [uri (:uri data)
                              ;; content (:content data)
                             ]
                         (if (contains? (get-in db [:workspace :files]) uri)
                           (assoc-in db [:workspace :files uri :dirty?] false)
                           db))
       db))))

(rf/reg-event-db
 ::editor-update-content
 (fn [db [_ content uri]]
   (let [effective-uri (or uri (get-in db [:workspace :active-file]))]
     (if effective-uri
       (-> db
           (assoc-in [:workspace :files effective-uri :content] content)
           (assoc-in [:workspace :files effective-uri :dirty?] true))
       db))))

(rf/reg-event-db
 ::lsp-set-connection
 (fn [db [_ lang connected?]]
   (assoc-in db [:lsp lang :connected?] connected?)))

(rp/reg-event-fx
 ::lsp-diagnostics-update
 (fn [{:keys [_db]} [_ diags]]
   ;; Dereference ds-conn directly to get the current DataScript database value for querying.
   (let [current-db @ds-conn
         old-eids (d/q '[:find [?e ...] :where [?e :type :diagnostic]] current-db)
         retract-tx (map (fn [eid] [:db/retractEntity eid]) old-eids)
         tx (diagnostics-tx diags)
         status (if (empty? diags) :success :error)]
     {:transact (concat retract-tx tx)
      :dispatch [::set-status status]})))

(rf/reg-event-db
 ::set-status
 (fn [db [_ status]]
   (assoc db :status status)))

(rp/reg-event-fx
 ::lsp-symbols-update
 (fn [{:keys [_db]} [_ symbols]]
   ;; Dereference ds-conn directly to get the current DataScript database value for querying.
   (let [current-db @ds-conn
         old-eids (d/q '[:find [?e ...] :where [?e :type :symbol]] current-db)
         retract-tx (map (fn [eid] [:db/retractEntity eid]) old-eids)
         tx (symbols-tx symbols nil)] ;; uri in symbols
     {:transact (concat retract-tx tx)})))

(rf/reg-event-db
 ::log-append
 (fn [db [_ log]]
   (update db :logs conj log)))

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
       (let [content (or (get-in db [:workspace :files (:active-file db) :content]) "")
             logs (:logs db)
             code-results (filter (fn [line] (str/includes? (str/lower-case line) lterm)) (str/split-lines content))
             log-results (filter (fn [log] (str/includes? (str/lower-case (:message log)) lterm)) logs)]
         (assoc db :search {:term term :results (concat code-results (map :message log-results))}))))))

(rf/reg-event-db
 ::toggle-search
 (fn [db _]
   (update-in db [:search :visible?] not)))

(rf/reg-event-db
 ::open-rename-modal
 (fn [db _]
   (let [current-name (get-in db [:workspace :files (get-in db [:workspace :active-file]) :name])]
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
   (let [active (get-in db [:workspace :active-file])
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
 ;; Updates the highlighted range in the editor state (or clears if nil).
 ;; Triggered from RxJS "highlight-change" subscription.
 (fn [db [_ range]]
   (assoc-in db [:editor :highlights] range)))

(rf/reg-event-db
 ::editor-ready
 (fn [db _]
   (assoc-in db [:editor :ready] true)))
