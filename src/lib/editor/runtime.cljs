(ns lib.editor.runtime
  "Editor runtime: event emission (RxJS + debounce), CodeMirror extension
  assembly, document activation, and the LSP didOpen lifecycle. Extracted from
  lib.core to shrink that namespace; every function here is parameterized over
  the per-editor state-atom, events subject, view-ref, and ILspClient, so the
  move is behavior-preserving (EXP-005/007/008/009/010/011 hot-path tuning intact)."
  (:require
   ["@codemirror/autocomplete" :refer [closeBrackets]]
   ["@codemirror/commands" :refer [defaultKeymap history historyKeymap indentWithTab]]
   ["@codemirror/language" :refer [bracketMatching]]
   ["@codemirror/search" :refer [getSearchQuery search searchKeymap]]
   ["@codemirror/state" :refer [Annotation StateField]]
   ["@codemirror/view" :refer [EditorView keymap lineNumbers]]
   [clojure.core.async :refer [go <! alts! timeout]]
   [clojure.string :as str]
   [lib.db :as db]
   [lib.editor.diagnostics :as diagnostics]
   [lib.editor.annotations :refer [external-set-annotation]]
   [lib.editor.syntax :as syntax]
   [domain.protocols :as p]
   [lib.state]
   [lib.utils :refer [offset->pos]]
   [lib.debounce :as debounce]
   [taoensso.timbre :as log]))

;; Annotation to mark transactions that update diagnostics in the StateField.
(def diagnostic-annotation (.define Annotation))

;; StateField to hold the current list of LSP diagnostics.
(def diagnostic-field
  (.define StateField
           #js {:create (fn [_] #js []) ;; Initial empty array of diagnostics.
                :update (fn [value ^js tr]
                          (if-let [new-diags (.annotation tr diagnostic-annotation)]
                            new-diags
                            value))}))

(defn get-ext-from-path
  "Extracts the file extension from a path (e.g., \".rho\" from \"/demo.rho\"), or nil if none."
  [path]
  (let [idx (str/last-index-of path ".")]
    (when (and idx (< idx (dec (count path))))
      (subs path idx))))

;; =============================================================================
;; EXP-011: Delta-Based Text Synchronization State
;; =============================================================================
;; Multi-editor: this state was formerly held in two module-global `defonce`
;; atoms keyed by URI. With instantiable workspaces, two editors can have the
;; same URI active simultaneously, so global maps would let panes clobber each
;; other's idle-sync handle and concatenate each other's deltas. Both maps now
;; live in the PER-EDITOR `state-atom` under :pending-idle-syncs and
;; :pending-lsp-changes (initialized in lib.core/default-state):
;;   :pending-idle-syncs  -- URI -> requestIdleCallback handle (dedupes the
;;                           idle-deferred CodeMirror->DataScript text sync).
;;   :pending-lsp-changes -- URI -> vector of ContentChangeEvent {:range
;;                           :rangeLength :text} accumulated during the debounce
;;                           window for incremental LSP didChange.

;; =============================================================================
;; Event Emission with Debouncing (EXP-008: Consolidated Debouncing)
;; =============================================================================
;; Uses lib.debounce for centralized debounce coordination.
;; Events are debounced based on type and URI/lang context using string keys.

(def ^:private EVENT-DEBOUNCE-MS
  "Debounce delays for different event types (in milliseconds).
   EXP-008: Optimized delays with max-wait for responsiveness."
  {"content-change" 100
   "selection-change" 50
   "cursor-change" 50
   "search-term-change" 150   ; Reduced from 200ms for better responsiveness
   "lsp-message" 0            ; No debounce for LSP messages
   "diagnostics" 0            ; No debounce for diagnostics
   "symbols" 0                ; No debounce for symbols
   "connect" 0                ; No debounce for connection events
   "disconnect" 0             ; No debounce for disconnection events
   "document-open" 0          ; No debounce for document events
   "document-close" 0
   "language-change" 0
   :default 50})

(def ^:private EVENT-MAX-WAIT-MS
  "Maximum wait times before forced execution during continuous events.
   EXP-008: Ensures responsiveness during rapid input."
  {"content-change" 500       ; Ensure update within 500ms even during rapid typing
   "selection-change" 200     ; Ensure cursor updates within 200ms
   "search-term-change" 400}) ; Ensure search updates within 400ms

(defn- make-emit-key
  "Creates a keyword key for event debouncing based on type and context.
   Uses type and optional URI/lang to deduplicate."
  [type data]
  (let [uri (or (:uri data) "")
        lang (or (:lang data) "")]
    (keyword "emit" (str type ":" uri ":" lang))))

(defn emit-event
  "Emits an event to the RxJS ReplaySubject with debouncing for frequent updates.
   Uses lib.debounce for centralized coordination.

   EXP-008: Consolidated debouncing with max-wait for responsiveness.
   - Selection/cursor changes: 50ms debounce, 200ms max-wait
   - Content changes: 100ms debounce, 500ms max-wait
   - Search term changes: 150ms debounce, 400ms max-wait
   - LSP/diagnostic events: immediate (no debounce)"
  [events type data]
  (log/trace "Emitting event:" type)
  (let [key (make-emit-key type data)
        ms (get EVENT-DEBOUNCE-MS type (:default EVENT-DEBOUNCE-MS))
        max-wait (get EVENT-MAX-WAIT-MS type)]
    (if (zero? ms)
      ;; Immediate emission for critical events
      (.next events (clj->js {:type type :data data}))
      ;; Debounced emission with optional max-wait for responsiveness
      (debounce/debounced-call
       key
       #(.next events (clj->js {:type type :data data}))
       ms
       (if max-wait
         {:max-wait max-wait}
         {})))))

(defn clear-emit-timers!
  "Cancels all pending event emissions. Call on unmount."
  []
  (debounce/cancel-matching #(and (keyword? %)
                                  (= "emit" (namespace %)))))

(defn update-editor-state
  "Updates the internal state-atom with cursor and selection info from CodeMirror state.
  Emits a debounced 'selection-change' event for external listeners.
  EXP-008: Uses centralized debounce coordination.
  EXP-009: Accepts URI as parameter to avoid redundant queries."
  [^js cm-state state-atom events uri]
  (let [main-sel (.-main (.-selection cm-state))
        anchor (.-anchor main-sel)
        head (.-head main-sel)
        ^js doc (.-doc cm-state)
        cursor-pos (offset->pos doc head true)
        sel (when (not= anchor head)
              (let [from (min anchor head)
                    to (max anchor head)
                    from-pos (offset->pos doc from true)
                    to-pos (offset->pos doc to true)
                    text (.sliceString doc from to)]
                {:from from-pos :to to-pos :text text}))]
    (swap! state-atom assoc :cursor cursor-pos :selection sel)
    ;; EXP-008: Consolidated debounce - selection-change already debounced in emit-event
    ;; No need for double debouncing here
    (emit-event events "selection-change" {:cursor cursor-pos
                                           :selection sel
                                           :uri uri})))

(defn get-extensions
  "Returns the array of CodeMirror extensions, including dynamic syntax compartment,
  static diagnostic compartment. Appends extra-extensions from state.
  EXP-008: Uses centralized debounce coordination for LSP and search.
  EXP-009: Optimizes keystroke hot path with:
    - Phase 1: Debounced DataScript text sync
    - Phase 2: LSP-aware lazy text serialization
    - Phase 3: Cached active URI within handler"
  [state-atom events on-content-change view-ref client conn]
  (let [update-ext (.. EditorView -updateListener
                       (of (fn [^js u]
                             ;; EXP-009 Phase 3: Cache URI once per handler invocation
                             (let [uri (:active-uri @state-atom)]
                               (when (or (.-docChanged u) (.-selectionSet u))
                                 ;; EXP-009 Phase 4: Pass cached URI to update-editor-state
                                 (update-editor-state (.-state u) state-atom events uri))
                               (let [old-term (:search-term @state-atom "")
                                     new-term (or (.-search (getSearchQuery (.-state u))) "")]
                                 (when (not= old-term new-term)
                                   (swap! state-atom assoc :search-term new-term)
                                   ;; EXP-008: search-term-change is debounced in emit-event
                                   ;; EXP-009: Use cached URI
                                   (emit-event events "search-term-change" {:term new-term
                                                                            :uri uri})))
                               (when (and (.-docChanged u) uri)
                                 ;; EXP-010: Removed lazy text that was still forced on every keystroke
                                 (let [^js doc (.-doc (.-state u))
                                       doc-length (.-length doc)
                                       ;; EXP-010 Phase 3: Use cached LSP status instead of DataScript query
                                       lsp-connected? (get-in @state-atom [:lsp-document-opened uri] false)
                                       from-api? (some #(.annotation % external-set-annotation) (.-transactions u))]
                                   (log/trace (str "Document changed for uri: " uri ", length:" doc-length))
                                   ;; EXP-011 Phase 2b: Accumulate incremental changes for LSP
                                   ;; Only accumulate for user edits (not API calls) when LSP is connected
                                   (when (and lsp-connected? (not from-api?))
                                     (let [old-doc (.-doc (.-startState u))
                                           changes (.-changes u)]
                                       (.iterChanges changes
                                                     (fn [fromA toA _fromB _toB inserted]
                                                       (let [start-pos (offset->pos old-doc fromA false)
                                                             end-pos (offset->pos old-doc toA false)]
                                                         (swap! state-atom update-in [:pending-lsp-changes uri] (fnil conj [])
                                                                {:range {:start {:line (:line start-pos)
                                                                                 :character (:column start-pos)}
                                                                         :end {:line (:line end-pos)
                                                                               :character (:column end-pos)}}
                                                                 :rangeLength (- toA fromA)
                                                                 :text (str inserted)})))
                                                     false)))
                                   ;; EXP-011 Phase 1: Idle-deferred DataScript sync
                                   ;; CodeMirror is the source of truth during editing.
                                   ;; DataScript only needs eventual consistency for LSP and persistence.
                                   ;; Use requestIdleCallback to avoid blocking the main thread with O(n) serialization.
                                   (debounce/debounced-call
                                    [:db-text-sync uri]
                                    (fn []
                                      (when-let [_view (.-current view-ref)]
                                        (let [sync-fn (fn []
                                                        ;; Clear the pending handle before executing
                                                        (swap! state-atom update :pending-idle-syncs dissoc uri)
                                                        (when (.-current view-ref)
                                                          (let [current-text (str (.-doc (.-state (.-current view-ref))))]
                                                            (db/update-document-text-by-uri! conn uri current-text))))]
                                          ;; Cancel any pending idle callback for this URI to prevent duplicates
                                          (when-let [pending-handle (get-in @state-atom [:pending-idle-syncs uri])]
                                            (when (exists? js/cancelIdleCallback)
                                              (js/cancelIdleCallback pending-handle)))
                                          (if (exists? js/requestIdleCallback)
                                            ;; Use requestIdleCallback for non-blocking sync
                                            (let [handle (js/requestIdleCallback
                                                          (fn [deadline]
                                                            (when (pos? (.timeRemaining deadline))
                                                              (sync-fn)))
                                                          #js {:timeout 500})]  ; Guarantee sync within 500ms
                                              (swap! state-atom assoc-in [:pending-idle-syncs uri] handle))
                                            ;; Fallback for unsupported browsers (Safari)
                                            (js/setTimeout sync-fn 0)))))
                                    50      ; 50ms debounce
                                    {:max-wait 200})  ; Force sync within 200ms
                                   ;; EXP-010: Don't serialize text in hot path - consumers fetch from DataScript
                                   ;; DataScript is kept in sync via the debounced db-text-sync above
                                   (emit-event events "content-change"
                                               {:uri uri :length doc-length})
                                   ;; EXP-010: Handle on-content-change based on source
                                   ;; - API calls (external-set-annotation): immediate callback
                                   ;; - Keystrokes: debounced to avoid O(n) stringify every keystroke
                                   (when on-content-change
                                     (if from-api?
                                       ;; Immediate callback for API calls (setText, openDocument)
                                       (on-content-change (str doc))
                                       ;; Debounced callback for keystrokes
                                       (debounce/debounced-call
                                        [:on-content-change uri]
                                        (fn []
                                          (when-let [view (.-current view-ref)]
                                            (on-content-change (str (.-doc (.-state view))))))
                                        50
                                        {:max-wait 200})))
                                   ;; LSP notification (still debounced separately for server rate limiting)
                                   (when lsp-connected?
                                     (when-not from-api?
                                       ;; EXP-011 Phase 2: Use incremental sync when available
                                       (debounce/debounced-call
                                        :lsp-did-change
                                        (fn []
                                          (let [uri (:active-uri @state-atom) [text lang] (when uri (db/doc-text-lang-by-uri conn uri))]
                                            (when (and uri text lang)
                                              (let [version (db/inc-document-version-by-uri! conn uri)
                                                    changes (get-in @state-atom [:pending-lsp-changes uri])
                                                    incremental? (and (seq changes)
                                                                      (get-in @state-atom [:lsp lang :incremental-sync?]))]
                                                (if incremental?
                                                  (do
                                                    (p/notify-did-change-incremental! client lang uri changes version)
                                                    (swap! state-atom update :pending-lsp-changes dissoc uri))
                                                  ;; Fallback to full sync - clear any accumulated changes
                                                  (do
                                                    (p/notify-did-change! client lang uri text version)
                                                    (swap! state-atom update :pending-lsp-changes dissoc uri)))))))
                                        150  ; Reduced from 200ms
                                        {:max-wait 500})))))))))
        default-exts [(lineNumbers)
                      (bracketMatching)
                      (closeBrackets)
                      (.of keymap (clj->js (concat [indentWithTab] defaultKeymap historyKeymap searchKeymap)))
                      (.of syntax/syntax-compartment #js [])
                      (.theme EditorView #js {} #js {:dark true})
                      diagnostic-field
                      update-ext
                      (history)
                      (search)] ; Added search extension for the panel and commands
        extra-extensions (:extra-extensions @state-atom #js [])]
    (into-array
     (concat default-exts diagnostics/extensions extra-extensions))))

(defn ensure-lsp-document-opened
  "Ensures the document is opened in LSP if configured, connecting if necessary.
  Sends didOpen and requests symbols on success, emitting events for LSP actions.
  Waits for an ongoing connection if one is in progress."
  [lang uri state-atom events client conn]
  (let [[text version] (db/doc-text-version-by-uri conn uri)]
    (when-let [lsp-url (get-in @state-atom [:languages lang :lsp-url])]
      (let [lsp-state (get-in @state-atom [:lsp lang])
            connected? (:connected? lsp-state false)
            initialized? (:initialized? lsp-state false)
            connecting? (:connecting? lsp-state false)]
        (log/debug (str "Ensuring LSP document opened for lang: " lang
                        ", uri: " uri
                        ", connected? " connected?
                        ", initialized? " initialized?
                        ", connecting? " connecting?))
        (go
          (try
            (if (and connected? initialized?)
              ;; Already connected and initialized, proceed with didOpen
              (when-not (db/document-opened-by-uri? conn uri)
                (p/notify-did-open! client lang uri text version)
                (emit-event events "lsp-message" {:method "textDocument/didOpen"
                                                  :lang lang
                                                  :params {:textDocument {:languageId lang
                                                                          :uri uri
                                                                          :version version
                                                                          :text text}}})
                (db/document-opened-by-uri! conn uri)
                ;; EXP-010 Phase 3: Update cache for hot path
                (swap! state-atom assoc-in [:lsp-document-opened uri] true)
                (emit-event events "document-open" {:uri uri
                                                    :content text
                                                    :language lang
                                                    :opened true}))
              ;; Not connected or initialized; check if connecting
              (let [existing (lib.state/get-resource-promise :lsp lang)]
                (if connecting?
                  ;; Wait for existing connection channel
                  (do
                    (log/debug "Waiting for existing LSP connection for lang:" lang)
                    (let [res (<! existing)]
                      (if (and (seqable? res) (= :error (first res)))
                        (do
                          (emit-event events "lsp-error" {:message "Failed to connect and initialize LSP"
                                                          :lang lang
                                                          :cause (if (instance? js/Error (second res))
                                                                   (.-message (second res))
                                                                   (str (second res)))})
                          (throw (js/Error. "Failed to connect and initialize LSP" #js {:cause (second res)})))
                        (do
                          (log/debug "Existing LSP connection resolved for lang:" lang)
                          (when-not (db/document-opened-by-uri? conn uri)
                            (p/notify-did-open! client lang uri text version)
                            (emit-event events "lsp-message" {:method "textDocument/didOpen"
                                                              :lang lang
                                                              :params {:textDocument {:languageId lang
                                                                                      :uri uri
                                                                                      :version version
                                                                                      :text text}}})
                            (db/document-opened-by-uri! conn uri)
                            ;; EXP-010 Phase 3: Update cache for hot path
                            (swap! state-atom assoc-in [:lsp-document-opened uri] true)
                            (emit-event events "document-open" {:uri uri
                                                                :content text
                                                                :language lang
                                                                :opened true}))))))
                  ;; Start new connection
                  (let [supplier (p/connect-supplier client lang lsp-url)
                        ch (lib.state/load-resource :lsp lang supplier)
                        res (<! ch)]
                    (if (and (seqable? res) (= :error (first res)))
                      (do
                        (emit-event events "lsp-error" {:message "Failed to connect and initialize LSP"
                                                        :lang lang
                                                        :cause (if (instance? js/Error (second res))
                                                                 (.-message (second res))
                                                                 (str (second res)))})
                        (throw (js/Error. "Failed to connect and initialize LSP" #js {:cause (second res)})))
                      (do
                        (log/debug "LSP connected and initialized for lang:" lang)
                        (when-not (db/document-opened-by-uri? conn uri)
                          (p/notify-did-open! client lang uri text version)
                          (emit-event events "lsp-message" {:method "textDocument/didOpen"
                                                            :lang lang
                                                            :params {:textDocument {:languageId lang
                                                                                    :uri uri
                                                                                    :version version
                                                                                    :text text}}})
                          (db/document-opened-by-uri! conn uri)
                          ;; EXP-010 Phase 3: Update cache for hot path
                          (swap! state-atom assoc-in [:lsp-document-opened uri] true)
                          (emit-event events "document-open" {:uri uri
                                                              :content text
                                                              :language lang
                                                              :opened true}))))))))
            [:ok nil]
            (catch js/Error error
              (when (and @state-atom (:mounted? @state-atom))
                (emit-event events "error" {:message (.-message error)
                                            :uri uri
                                            :operation "ensure-lsp-document-opened"
                                            :cause (if (.-cause error)
                                                     (.-message (.-cause error))
                                                     (str (.-cause error)))}))
              (log/error (str "Failed to ensure LSP document opened " uri ": " (.-message error)))
              [:error (js/Error. (str "(ensure-lsp-document-opened " lang " " uri " state-atom events) failed") #js {:cause error})])))))))

(defn activate-document
  "Activates the document with the given URI, loading content and re-initializing syntax if language changes.
  Emits events for document activation and LSP open if necessary.
  EXP-008: Uses centralized debounce coordination to handle rapid calls."
  [uri state-atom view-ref events client conn]
  (debounce/debounced-call
   [:activate-document uri]
   (fn []
     (go
       (try
         (log/trace "Activating document:" uri)
         (let [old-lang (db/document-language-by-uri conn (:active-uri @state-atom))]
           (when (not= uri (:active-uri @state-atom))
             (log/debug "Updating active URI for document with old-lang:" old-lang)
             (do (swap! state-atom assoc :active-uri uri) (db/update-active-uri! conn uri)))
           (let [[text new-lang] (db/doc-text-lang-by-uri conn uri)]
             (log/debug "New language for activation:" new-lang)
             (when-let [view (.-current view-ref)]
               (let [current-doc (.-doc (.-state view))
                     current-length (.-length current-doc)]
                 (.dispatch view #js {:changes #js {:from 0
                                                    :to current-length
                                                    :insert text}
                                      :annotations (.of external-set-annotation true)})))
             (let [lsp-ch (go
                            (try
                              (if (get-in @state-atom [:languages new-lang :lsp-url])
                                (<! (ensure-lsp-document-opened new-lang uri state-atom events client conn))
                                (do
                                  (db/document-opened-by-uri! conn uri)
                                  [:ok nil]))
                              (catch :default e
                                [:error (js/Error. "LSP init in activate-document failed" #js {:cause e})])))
                   syntax-ch (go
                               (try
                                 (<! (syntax/init-syntax (.-current view-ref) state-atom conn))
                                 (catch :default e
                                   [:error (js/Error. "Syntax init in activate-document failed" #js {:cause e})])))
                   lsp-timeout-ms (:lsp-init-timeout-ms @state-atom 5000)
                   [lsp-val lsp-ch'] (alts! [lsp-ch (timeout lsp-timeout-ms)])
                   lsp-res (if (identical? lsp-ch lsp-ch') lsp-val [:error (js/Error. "LSP init timeout")])
                   [syntax-val syntax-ch'] (alts! [syntax-ch (timeout 5000)])
                   syntax-res (if (identical? syntax-ch syntax-ch') syntax-val [:error (js/Error. "Syntax init timeout")])]
               (when (and (:mounted? @state-atom) (= :error (first lsp-res)))
                 (log/warn "LSP init timeout or failed for lang" new-lang ", continuing without LSP:" (.-message (second lsp-res))))
               (if (= :error (first syntax-res))
                 (throw (js/Error. (str "(activate-document " uri " state-atom view-ref events) failed") #js {:cause (second syntax-res)}))
                 (emit-event events "language-change" {:uri uri :language new-lang}))
               (emit-event events "document-open" {:uri uri
                                                   :content text
                                                   :language new-lang
                                                   :activated true}))))
         [:ok nil]
         (catch js/Error error
           (when (and @state-atom (:mounted? @state-atom))
             (emit-event events "error" {:message (.-message error) :uri uri}))
           (log/error (str "Failed to activate document " uri ": " (.-message error)))
           [:error (js/Error. (str "(activate-document " uri " state-atom view-ref events) failed") #js {:cause error})]))))
   50))
