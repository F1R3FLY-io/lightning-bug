(ns app.events
  (:require
   [clojure.string :as str]
   [datascript.core :as d]
   [re-frame.core :as rf]
   [taoensso.timbre :as log]
   [app.db :refer [default-db ds-conn]]
   [app.utils :as u]))

;; Flattens hierarchical LSP symbols into a list for datascript transaction, assigning negative db/ids to avoid conflicts.
(defn flatten-symbols [symbols parent-id]
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

;; Use namespaced keywords (::event-name) for all event registrations to prevent collisions and improve debuggability.
;; This resolves mismatches where dispatches use ::e/event-name (namespaced) but registrations use simple :event-name.

(rf/reg-event-fx ::initialize
  (fn [{:keys [_]} _]
    (let [uuid (u/generate-uuid)
          lang (:default-language default-db)
          name (u/new-untitled-name default-db 0)]
      {:db (-> default-db
               (assoc-in [:workspace :files uuid] {:name name :content "" :language lang :dirty? false})
               (assoc-in [:workspace :active-file] uuid))})))

;; Opens a file by setting it as active.
(rf/reg-event-db ::file-open
  (fn [db [_ file-id]]
    (assoc-in db [:workspace :active-file] file-id)))

;; Adds a new untitled file and sets it as active.
(rf/reg-event-db ::file-add
  (fn [db _]
    (let [uuid (u/generate-uuid)
          lang (:default-language db)
          count (count (:files (:workspace db)))
          name (u/new-untitled-name db count)]
      (-> db
          (assoc-in [:workspace :files uuid] {:name name :content "" :language lang :dirty? false})
          (assoc-in [:workspace :active-file] uuid)))))

;; Removes a file; sets active to first remaining if deleted.
(rf/reg-event-db ::file-remove
  (fn [db [_ file-id]]
    (let [files (dissoc (get-in db [:workspace :files]) file-id)
          active (if (= file-id (get-in db [:workspace :active-file]))
                   (first (keys files))
                   (get-in db [:workspace :active-file]))]
      (-> db
          (assoc-in [:workspace :files] files)
          (assoc-in [:workspace :active-file] active)))))

;; Renames the active file and updates its language based on the new extension.
(rf/reg-event-db ::file-rename
  (fn [db [_ file-id new-name]]
    (let [langs (:languages db)
          ext (when-let [pos (str/last-index-of new-name ".")]
                (subs new-name pos))
          lang (or (ffirst
                    (filter
                     (fn [[_ v]] (some #{ext} (:extensions v)))
                     langs))
                   (:default-language db))]
      (-> db
          (assoc-in [:workspace :files file-id :name] new-name)
          (assoc-in [:workspace :files file-id :language] lang)))))

;; Updates active file content and marks as dirty.
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

;; Transacts diagnostics to datascript and updates status.
(rf/reg-event-db ::lsp-diagnostics-update
  (fn [db [_ diags]]
    (let [tx (map #(assoc % :type :diagnostic) diags)
          status (if (empty? diags) :success :error)]
      (log/debug "Updated diagnostics:" (count diags))
      (d/transact! ds-conn tx)
      (rf/dispatch [::set-status status])
      db)))

(rf/reg-event-db ::set-status
  (fn [db [_ status]]
    (assoc db :status status)))

;; Transacts flattened symbols to datascript.
(rf/reg-event-db ::lsp-symbols-update
  (fn [db [_ symbols]]
    (let [flat (flatten-symbols symbols nil)
          tx (map #(assoc % :type :symbol) flat)]
      (log/debug "Updated symbols:" (count flat))
      (d/transact! ds-conn tx)
      db)))

(rf/reg-event-db ::log-append
  (fn [db [_ log]]
    (log/info "Appended log:" (:message log))
    (update db :lsp/logs conj log)))

(rf/reg-event-db ::update-cursor
  (fn [db [_ cursor]]
    (assoc-in db [:editor :cursor] cursor)))

;; Simulates running an agent; sets status and dispatches validation after delay.
(rf/reg-event-fx ::run-agent
  (fn [{:keys [db]} _]
    (log/info "Running agent simulation...")
    {:db (assoc db :status :running)
     :fx [[:dispatch-later {:ms 2000 :dispatch [::validate-agent]}]]}))

;; Validates the active file content via LSP.
(rf/reg-event-db ::validate-agent
  (fn [db _]
    (log/info "Validating agent...")
    db))

;; Searches code and logs for term; updates search results.
(rf/reg-event-db ::search
  (fn [db [_ term]]
    (let [lterm (str/lower-case term)
          content (or (get-in db [:workspace :files (:active-file db) :content]) "")
          logs (:lsp/logs db)
          code-results (filter #(str/includes? (str/lower-case %) lterm) (str/split-lines content))
          log-results (filter #(str/includes? (str/lower-case (:message %)) lterm) logs)]
      (log/debug "Search results found:" (+ (count code-results) (count log-results)))
      (assoc db :search {:term term :results (concat code-results (map :message log-results))}))))
(rf/reg-event-db ::toggle-search
  (fn [db _]
    (update-in db [:search :visible?] not)))

(rf/reg-event-db ::open-rename-modal
  (fn [db _]
    (let [current-name (get-in db [:workspace :files (:active-file (:workspace db)) :name])]
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
        (rf/dispatch [::file-rename file-id new-name]))
      (rf/dispatch [::close-rename-modal])
      db)))
