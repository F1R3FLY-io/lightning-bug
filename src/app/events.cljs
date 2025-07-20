(ns app.events
  (:require
   [clojure.string :as str]
   [re-frame.core :as rf]
   [re-posh.core :as rp]
   [taoensso.timbre :as log]
   [app.db :refer [default-db ds-conn]]
   [app.editor.syntax :as syntax]
   [app.lsp.client :as lsp]
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

(rf/reg-event-fx ::initialize
  (fn [{:keys [_]} _]
    (let [uuid (u/generate-uuid)]
      {:db (-> default-db
               (assoc-in [:workspace :files uuid] {:name "untitled" :content "" :language "rholang" :dirty? false})
               (assoc-in [:workspace :active-file] uuid))
       :fx [[:dispatch [:app.events/lsp-connect]]]})))

;; Opens a file by setting it as active; reconnects LSP and reloads syntax if language changes.
(rf/reg-event-db :app.events/file-open
  (fn [db [_ file-id]]
    (let [current-lang (get-in db [:workspace :files (:active-file db) :language])
          new-lang (get-in db [:workspace :files file-id :language])]
      (when (not= current-lang new-lang)
        (rf/dispatch [:app.events/lsp-connect])
        (rf/dispatch [:reload-syntax]))
      (assoc-in db [:workspace :active-file] file-id))))

;; Adds a new untitled file and sets it as active.
(rf/reg-event-db :app.events/file-add
  (fn [db _]
    (let [uuid (u/generate-uuid)
          count (count (:files (:workspace db)))]
      (-> db
          (assoc-in [:workspace :files uuid] {:name (str "untitled-" count) :content "" :language "rholang" :dirty? false})
          (assoc-in [:workspace :active-file] uuid)))))

;; Removes a file; sets active to first remaining if deleted.
(rf/reg-event-db :app.events/file-remove
  (fn [db [_ file-id]]
    (let [files (dissoc (get-in db [:workspace :files]) file-id)
          active (if (= file-id (get-in db [:workspace :active-file]))
                   (first (keys files))
                   (get-in db [:workspace :active-file]))]
      (-> db
          (assoc-in [:workspace :files] files)
          (assoc-in [:workspace :active-file] active)))))

;; Renames the active file.
(rf/reg-event-db :app.events/file-rename
  (fn [db [_ file-id new-name]]
    (assoc-in db [:workspace :files file-id :name] new-name)))

;; Updates active file content and marks as dirty.
(rf/reg-event-db :app.events/editor-update-content
  (fn [db [_ content]]
    (let [file-id (:active-file (:workspace db))]
      (if file-id
        (-> db
            (assoc-in [:workspace :files file-id :content] content)
            (assoc-in [:workspace :files file-id :dirty?] true))
        db))))

;; Connects to LSP server for current language; falls back if no URL.
(rf/reg-event-fx :app.events/lsp-connect
  (fn [{:keys [db]} _]
    (let [active (:active-file (:workspace db))
          lang (when active (get-in db [:workspace :files active :language]))
          url (when lang (get-in db [:languages lang :lsp-url]))
          retry? (get-in db [:lsp :retry?] false)]
      (if url
        (do (log/info "Connecting to LSP server for" lang "at" url (when retry? "(retry)"))
            (lsp/connect url)
            {:db (assoc-in db [:lsp :retry?] false)})
        (do (if lang
              (log/warn "No LSP URL for" lang "; falling back to basic mode")
              (log/debug "No active file or language; falling back to basic mode"))
            {:db (assoc-in db [:lsp :connection] false)})))))

(rf/reg-event-db :app.events/lsp-set-connection
  (fn [db [_ connected?]]
    (assoc-in db [:lsp :connection] connected?)))

(rf/reg-event-db :app.events/lsp-set-pending
  (fn [db [_ id type]]
    (assoc-in db [:lsp :pending id] type)))

(rf/reg-event-db :app.events/lsp-clear-pending
  (fn [db [_ id]]
    (update-in db [:lsp :pending] dissoc id)))

;; Transacts diagnostics to datascript and updates status.
(rp/reg-event-ds ::lsp-diagnostics-update
  (fn [_ [_ diags]]
    (let [tx (map #(assoc % :type :diagnostic) diags)
          status (if (empty? diags) :success :error)]
      (log/debug "Updated diagnostics:" (count diags))
      (rf/dispatch [:set-status status])
      tx)))

(rf/reg-event-db :set-status
  (fn [db [_ status]]
    (assoc db :status status)))

;; Transacts flattened symbols to datascript.
(rp/reg-event-ds ::lsp-symbols-update
  (fn [_ [_ symbols]]
    (let [flat (flatten-symbols symbols nil)
          tx (map #(assoc % :type :symbol) flat)]
      (log/debug "Updated symbols:" (count flat))
      tx)))

(rf/reg-event-db :app.events/log-append
  (fn [db [_ log]]
    (log/info "Appended log:" (:message log))
    (update db :lsp/logs conj log)))

;; Simulates running an agent; sets status and dispatches validation after delay.
(rf/reg-event-fx :app.events/run-agent
  (fn [{:keys [db]} _]
    (log/info "Running agent simulation...")
    {:db (assoc db :status :running)
     :fx [[:dispatch-later {:ms 2000 :dispatch [:app.events/validate-agent]}]]}))

;; Validates the active file content via LSP.
(rf/reg-event-db :app.events/validate-agent
  (fn [db _]
    (log/info "Validating agent...")
    (let [lang (get-in db [:workspace :files (:active-file db) :language])
          method (get-in db [:languages lang :lsp-method])
          content (or (get-in db [:workspace :files (:active-file db) :content]) "")]
      (lsp/send {:method method :params {:text content}} :response-type :validate))
    db))

;; Searches code and logs for term; updates search results.
(defn search [db [_ term]]
  (let [lterm (str/lower-case term)
        content (or (get-in db [:workspace :files (:active-file db) :content]) "")
        logs (:lsp/logs db)
        code-results (filter #(str/includes? (str/lower-case %) lterm) (str/split-lines content))
        log-results (filter #(str/includes? (str/lower-case (:message %)) lterm) logs)]
    (log/debug "Search results found:" (count (concat code-results (map :message log-results))))
    (assoc db :search {:term term :results (concat code-results (map :message log-results))})))

(rf/reg-event-db :app.events/search search)

(rf/reg-event-db :app.events/toggle-search
  (fn [db _]
    (update-in db [:search :visible?] not)))

(rf/reg-event-db :open-rename-modal
  (fn [db _]
    (let [current-name (get-in db [:workspace :files (:active-file (:workspace db)) :name])]
      (assoc-in db [:modals :rename] {:visible? true :new-name current-name}))))

(rf/reg-event-db :close-rename-modal
  (fn [db _]
    (assoc-in db [:modals :rename :visible?] false)))

(rf/reg-event-db :set-rename-name
  (fn [db [_ name]]
    (assoc-in db [:modals :rename :new-name] name)))

(rf/reg-event-db :confirm-rename
  (fn [db _]
    (let [file-id (get-in db [:workspace :active-file])
          new-name (get-in db [:modals :rename :new-name])]
      (when (and file-id new-name)
        (rf/dispatch [:app.events/file-rename file-id new-name]))
      (rf/dispatch [:close-rename-modal])
      db)))

;; Reloads syntax highlighting for the current language (e.g., on language switch).
(rf/reg-event-fx :reload-syntax
  (fn [{:keys [db]} _]
    {:init-syntax nil}))

(rf/reg-fx :init-syntax
  (fn [_]
    (syntax/init-syntax @app.editor.state/editor-view)))
