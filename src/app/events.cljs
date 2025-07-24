(ns app.events
  (:require
   [app.db :refer [default-db ds-conn]]
   [app.utils :as u]
   [app.shared :refer [editor-ref-atom]]
   [clojure.string :as str]
   [datascript.core :as d]
   [re-frame.core :as rf]
   [re-posh.core :as rp]
   [taoensso.timbre :as log]))

(def debounced-set-rename-name (u/debounce #(rf/dispatch [::set-rename-name %]) 200))

(defn flatten-symbols
  "Flattens hierarchical LSP symbols into a list for datascript transaction,
  assigning negative db/ids to avoid conflicts."
  [symbols parent-id]
  (let [id-counter (atom -1)]
    (letfn [(flatten-rec [syms parent]
              (mapcat (fn [s]
                        (let [sid (swap! id-counter dec)
                              s' (cond-> (dissoc s :children)
                                   true (assoc :db/id sid)
                                   parent (assoc :parent parent))
                              children (:children s)]
                          (cons s' (when children (flatten-rec children sid)))))
                      syms))]
      (flatten-rec symbols parent-id))))

(rf/reg-event-fx ::initialize
  (fn [{:keys [_]} _]
    (let [uuid (u/generate-uuid)
          lang (:default-language default-db)
          name (u/new-untitled-name default-db 0)]
      {:db (-> default-db
               (assoc-in [:workspace :files uuid] {:name name :content "" :language lang :dirty? false})
               (assoc-in [:workspace :active-file] uuid))})))

(rf/reg-event-db ::file-open
  (fn [db [_ file-id]]
    (assoc-in db [:workspace :active-file] file-id)))

(rf/reg-event-db ::file-add
  (fn [db _]
    (let [uuid (u/generate-uuid)
          lang (:default-language db)
          count (count (:files (:workspace db)))
          name (u/new-untitled-name db count)]
      (-> db
          (assoc-in [:workspace :files uuid] {:name name :content "" :language lang :dirty? false})
          (assoc-in [:workspace :active-file] uuid)))))

(rf/reg-event-db ::file-remove
  (fn [db [_ file-id]]
    (let [files (dissoc (get-in db [:workspace :files]) file-id)
          active (if (= file-id (get-in db [:workspace :active-file]))
                   (first (keys files))
                   (get-in db [:workspace :active-file]))]
      (-> db
          (assoc-in [:workspace :files] files)
          (assoc-in [:workspace :active-file] active)))))

(rf/reg-event-db ::file-rename
  (fn [db [_ file-id new-name]]
    (let [ext (when-let [pos (str/last-index-of new-name ".")]
                (subs new-name pos))
          lang (u/get-lang-from-ext db ext)]
      (-> db
          (assoc-in [:workspace :files file-id :name] new-name)
          (assoc-in [:workspace :files file-id :language] lang)))))

(rf/reg-event-db ::editor-update-content
  (fn [db [_ content]]
    (let [file-id (:active-file (:workspace db))]
      (if file-id
        (-> db
            (assoc-in [:workspace :files file-id :content] content)
            (assoc-in [:workspace :files file-id :dirty?] true))
        db))))

(rf/reg-event-db ::lsp-set-connection
  (fn [db [_ connected?]]
    (assoc-in db [:lsp :connection] connected?)))

(rp/reg-event-fx ::lsp-diagnostics-update
  (fn [{:keys [db]} [_ diags]]
    (let [old-eids (d/q '[:find [?e ...] :where [?e :type :diagnostic]] @ds-conn)
          retract-tx (map (fn [eid] [:db/retractEntity eid]) old-eids)
          new-tx (map #(assoc % :type :diagnostic) diags)
          status (if (empty? diags) :success :error)]
      (log/debug "Updated diagnostics:" (count diags))
      {:transact (concat retract-tx new-tx)
       :dispatch [::set-status status]})))

(rf/reg-event-db ::set-status
  (fn [db [_ status]]
    (assoc db :status status)))

(rp/reg-event-fx ::lsp-symbols-update
  (fn [_ [_ symbols]]
    (let [flat (flatten-symbols symbols nil)
          tx (map #(assoc % :type :symbol) flat)]
      (log/debug "Updated symbols:" (count flat))
      {:transact tx})))

(rf/reg-event-db ::log-append
  (fn [db [_ log]]
    (log/info "Appended log:" (:message log))
    (update-in db [:lsp :logs] conj log)))

(rf/reg-event-db ::update-cursor
  (fn [db [_ cursor]]
    (assoc-in db [:editor :cursor] cursor)))

(rf/reg-event-fx ::run-agent
  (fn [{:keys [db]} _]
    (log/info "Running agent simulation...")
    {:db (assoc db :status :running)
     :fx [[:dispatch-later {:ms 2000 :dispatch [::validate-agent]}]]}))

(rf/reg-event-db ::validate-agent
  (fn [db _]
    (log/info "Validating agent...")
    db))

(rf/reg-event-db ::search
  (fn [db [_ term]]
    (let [lterm (str/lower-case term)]
      (if (empty? term)
        (assoc db :search {:term term :results []})
        (let [content (or (get-in db [:workspace :files (:active-file db) :content]) "")
              logs (:lsp :logs db)
              code-results (filter #(str/includes? (str/lower-case %) lterm) (str/split-lines content))
              log-results (filter #(str/includes? (str/lower-case (:message %)) lterm) logs)]
          (log/debug "Search results found:" (+ (count code-results) (count log-results)))
          (assoc db :search {:term term :results (concat code-results (map :message log-results))}))))))

(rf/reg-event-db ::toggle-search
  (fn [db _]
    (update-in db [:search :visible?] not)))

(rf/reg-event-db ::open-rename-modal
  (fn [db _]
    (let [current-name (get-in db [:workspace :files (get-in db [:workspace :active-file]) :name])]
      (assoc-in db [:modals :rename] {:visible? true :new-name current-name}))))

(rf/reg-event-db ::close-rename-modal
  (fn [db _]
    (assoc-in db [:modals :rename :visible?] false)))

(rf/reg-event-db ::set-rename-name
  (fn [db [_ name]]
    (assoc-in db [:modals :rename :new-name] name)))

(rf/reg-event-db ::confirm-rename
  (fn [db _]
    (let [file-id (get-in db [:workspace :active-file])
          new-name (get-in db [:modals :rename :new-name])]
      (when (and file-id new-name)
        (rf/dispatch [::file-rename file-id new-name])
        (when-let [editor (some-> @editor-ref-atom .-current)]
          (.renameDocument ^js editor new-name)))
      (rf/dispatch [::close-rename-modal])
      db)))

(rf/reg-event-db ::toggle-logs
  (fn [db _]
    (update db :logs-visible? not)))

(rf/reg-event-db ::set-logs-height
  (fn [db [_ height]]
    (assoc db :logs-height height)))

(rf/reg-event-db ::set-cursor-pos
  (fn [db [_ pos]]
    (assoc db :editor-cursor-pos pos)))

(rf/reg-event-db ::clear-cursor-pos
  (fn [db _]
    (assoc db :editor-cursor-pos nil)))

(rf/reg-event-db ::set-highlight-range
  (fn [db [_ range]]
    (assoc db :highlight-range range)))
