(ns lib.core
  (:require
   ["@codemirror/state" :refer [EditorState]]
   ["@codemirror/view" :refer [EditorView]]
   ["react" :as react]
   ["rxjs" :as rxjs :refer [ReplaySubject]]
   [reagent.core :as r]
   [lib.db :as db]
   [lib.workspace :as ws]
   [lib.workspace.doc-sync :as doc-sync]
   [lib.editor.diagnostics :as diagnostics :refer [set-diagnostic-effect]]
   [lib.editor.runtime :as rt :refer [emit-event clear-emit-timers! get-extensions update-editor-state]]
   [lib.editor.commands :as commands]
   [lib.lsp.connection-manager :as cm]
   [domain.protocols :as p]
   [lib.state :refer [normalize-languages normalize-editor-config validate-editor-config!]]
   [lib.utils :refer [log-error-with-cause]]
   [taoensso.timbre :as log]))

;; Hardcoded default languages for the library; uses string keys.
(defonce ^:const default-languages {"text" {:extensions [".txt"]
                                            :fallback-highlighter "none"}})

;; React context carrying a shared Workspace to a subtree of editors. `defonce` so
;; the context object identity is stable across hot reloads (provider/consumer must
;; agree on the same context object). Editors with no :workspace prop and no
;; provider resolve to the shared lib.workspace/default-workspace (also defonce),
;; so documents survive re-renders AND hot reloads.
(defonce ^:private workspace-context (react/createContext nil))

(defn ^:export EditorWorkspaceProvider
  "Provider component: wrap editors that should SHARE a workspace.
  Usage (JS): <EditorWorkspaceProvider value={ws}>...editors...</EditorWorkspaceProvider>"
  [js-props]
  (react/createElement (.-Provider workspace-context)
                       #js {:value (.-value js-props)}
                       (.-children js-props)))

(defn ^:export createWorkspace
  "Creates a new ISOLATED workspace (its own documents/projects/loaded resources/reactive
  change streams). Pass it to one or more <Editor> instances — via the `workspace` prop or
  <EditorWorkspaceProvider value={ws}> — so they share state (open files propagate between
  editors on the same file). Omit it and editors use a shared default workspace.
  Hold the result somewhere stable (a module binding / defonce) so it survives hot reloads."
  []
  (ws/make-workspace))

(defn- default-state
  "Computes the initial editor state from converted CLJS props.
  Ensures language keys are strings and falls back to 'text' if no language is provided."
  [props]
  (validate-editor-config! props)
  (let [languages (normalize-languages (merge default-languages (:languages props)))
        extra-extensions (:extra-extensions props #js [])
        default-protocol (or (:default-protocol props) "inmemory://")
        lsp-init-timeout-ms (or (:lsp-init-timeout-ms props) 5000)
        tree-sitter-wasm (:tree-sitter-wasm props "js/tree-sitter.wasm")]
    (log/info "Editor state initialized with languages:" (keys languages))
    (when-not (every? string? (keys languages))
      (log/warn "Non-string keys found in languages map:" (keys languages)))
    ;; EXP-008: Removed :debounce-timer - now using lib.debounce coordination
    ;; EXP-010: Added :lsp-document-opened cache for hot path optimization
    {:mounted? true
     :cursor {:line 1 :column 1}
     :selection nil
     ;; PER-PANE active document: which file THIS editor instance shows. Distinct
     ;; from the workspace FOCUS (conn :workspace/active-uri), which the demo/app
     ;; reads; activate-document sets both, so single-editor behavior is unchanged.
     :active-uri nil
     :search-term ""
     :lsp {}
     :lsp-document-opened {}  ; EXP-010: Cache {uri -> true} for documents opened with LSP
     ;; EXP-011 per-editor sync scratch (moved out of module-global atoms so
     ;; multiple editors over the same URI don't clobber each other):
     :pending-idle-syncs {}   ; URI -> requestIdleCallback handle (idle DataScript sync)
     :pending-lsp-changes {}  ; URI -> vector of ContentChangeEvent (incremental didChange)
     :languages languages
     :tree-sitter-wasm tree-sitter-wasm
     :extra-extensions extra-extensions
     :lsp-init-timeout-ms lsp-init-timeout-ms
     :default-protocol default-protocol}))


;; Inner React functional component, handling CodeMirror integration and state management.
(let [inner (fn [^js js-props forwarded-ref]
              ;; Resolve the effective workspace BEFORE per-editor state. The raw
              ;; :workspace prop is read off js-props directly (NOT through js->clj,
              ;; which would mangle the Workspace record), and stripped from the
              ;; config before validation. Resolution: prop > React context > shared
              ;; default-workspace. useMemo over [prop-ws ctx-ws] keeps identity
              ;; stable across re-renders.
              (let [prop-ws (.-workspace js-props)
                    prop-uri (.-uri js-props)
                    ctx-ws (react/useContext workspace-context)
                    workspace (react/useMemo
                               (fn [] (ws/ensure-workspace (or prop-ws ctx-ws)))
                               #js [prop-ws ctx-ws])
                    conn (:conn workspace)
                    props (normalize-editor-config (dissoc (js->clj js-props :keywordize-keys true)
                                                           :workspace :uri))
                    state-ref (react/useRef nil)]
                (when (nil? (.-current state-ref))
                  (set! (.-current state-ref) (r/atom (default-state props))))
                (let [state-atom (.-current state-ref)
                      view-ref (react/useRef nil)
                      [ready set-ready] (react/useState false)
                      events (react/useMemo (fn [] (ReplaySubject.)) #js [])
                      ;; Stable per-pane identity for reactive cross-pane echo-suppression.
                      pane-id (react/useMemo (fn [] (random-uuid)) #js [])
                      ;; Per-editor LSP client (ILspClient). Constructed once over this
                      ;; editor's state-atom + events + workspace conn; routes all LSP
                      ;; calls through the protocol.
                      client (react/useMemo (fn [] (cm/make-connection-manager state-atom events conn)) #js [])
                      ;; Per-editor context bundling the deps the imperative handle methods need.
                      ctx (react/useMemo (fn [] {:state-atom state-atom :view-ref view-ref :events events
                                                 :client client :conn conn :workspace workspace :pane-id pane-id
                                                 :lsp-atom state-atom}) #js [])
                      on-content-change (:on-content-change props)
                      container-ref (react/useRef nil)]
                  (react/useImperativeHandle
                   forwarded-ref
                   (fn [] (commands/build-handle ctx ready))
                   #js [@state-atom (.-current view-ref) ready])
                  (react/useEffect
                   (fn []
                     (when-let [au (:active-uri @state-atom)]
                       (when-let [^js editor-view (.-current view-ref)]
                         (let [^js editor-state (.-state editor-view)
                               current-doc (str (.-doc editor-state))
                               active-text (db/document-text-by-uri conn au)]
                           (when (and active-text (not= active-text current-doc))
                             (log/debug "Updating view content to match db for active uri")
                             (.dispatch editor-view #js {:changes #js {:from 0
                                                                       :to (count current-doc)
                                                                       :insert active-text}})
                             (emit-event events "content-change" {:content active-text
                                                                  :uri au})
                             (when on-content-change
                               (on-content-change active-text))))))
                     js/undefined)
                   #js [(:active-uri @state-atom)
                        (when-let [au (:active-uri @state-atom)] (db/document-text-by-uri conn au))])
                  ;; Phase 4: subscribe this pane to its active file's reactive change
                  ;; stream, so edits from OTHER panes on the same file apply live here
                  ;; (this pane's cursor/selection preserved via selection-mapping).
                  ;; The (re)subscription is driven by a state-atom WATCH — NOT a React
                  ;; dependency — so it tracks :active-uri changes regardless of the host
                  ;; framework's re-render behavior. A reagent host re-renders on a
                  ;; state-atom mutation; a plain-React host does not, and a React-dep
                  ;; effect would then never re-subscribe when the pane switches files.
                  ;; The watch fires on every state-atom change and re-subscribes only when
                  ;; :active-uri actually changes. Cleanup removes the watch and releases
                  ;; the ref-counted stream.
                  (react/useEffect
                   (fn []
                     (let [sub-state (atom nil)
                           resubscribe!
                           (fn [uri]
                             (when-let [{prev-uri :uri prev-sub :sub} @sub-state]
                               (.unsubscribe prev-sub)
                               (doc-sync/release-stream! workspace prev-uri))
                             (reset! sub-state
                                     (when uri
                                       {:uri uri
                                        :sub (doc-sync/subscribe-pane
                                              workspace uri pane-id
                                              (fn [delta]
                                                (doc-sync/apply-remote-delta! (.-current view-ref) delta)))})))
                           watch-key (keyword "lib.core" (str "doc-sync-" pane-id))]
                       (resubscribe! (:active-uri @state-atom))
                       (add-watch state-atom watch-key
                                  (fn [_ _ old new]
                                    (when (not= (:active-uri old) (:active-uri new))
                                      (resubscribe! (:active-uri new)))))
                       (fn []
                         (remove-watch state-atom watch-key)
                         (resubscribe! nil))))
                   #js [workspace pane-id])
                  (react/useEffect
                   (fn []
                     (let [shutdown-all (fn []
                                          (doseq [[lang _] (:lsp @state-atom)]
                                            (p/request-shutdown! client lang)))]
                       (js/window.addEventListener "beforeunload" shutdown-all)
                       (fn []
                         (js/window.removeEventListener "beforeunload" shutdown-all))))
                   #js [])
                  (react/useEffect
                   (fn []
                     (log/info "Editor: Initializing EditorView")
                     (try
                       (let [container (.-current container-ref)
                             exts (get-extensions state-atom events on-content-change view-ref client conn workspace pane-id)
                             editor-state (EditorState.create #js {:doc ""
                                                                   :extensions exts})
                             editor-view (EditorView. #js {:state editor-state
                                                           :parent container})
                             sub (.subscribe events
                                             (fn [evt-js]
                                               (let [evt (js->clj evt-js :keywordize-keys true)
                                                     type (:type evt)]
                                                 (when (= type "diagnostics")
                                                   (let [diags (:data evt)]
                                                     (log/trace "Updating diagnostics in view for uri:" (:uri evt))
                                                     (.dispatch editor-view #js {:effects #js [(.of set-diagnostic-effect (clj->js diags))]})
                                                     (let [uri (:uri evt)]
                                                       (when-let [lang (db/document-language-by-uri conn uri)]
                                                         (p/request-symbols! client lang uri))))))))]
                         (set! (.-current view-ref) editor-view)
                         (js/setTimeout
                          (fn []
                            (emit-event events "ready" {})
                            (set-ready true))
                          0)
                         (update-editor-state editor-state state-atom events (:active-uri @state-atom))
                         ;; If a `uri` prop was given, activate that document on mount (it must
                         ;; already exist in the workspace) — declarative file selection for
                         ;; split-pane setups. Editors without :uri are driven via the handle.
                         (when prop-uri
                           (rt/activate-document prop-uri state-atom view-ref events client conn))
                         (fn []
                           (log/info "Editor: Destroying EditorView")
                           (swap! state-atom assoc :mounted? false)
                           (p/shutdown-all! client)
                           (when-let [editor-view (.-current view-ref)]
                             (.destroy editor-view))
                           (set! (.-current view-ref) nil)
                           (.unsubscribe sub)
                           (clear-emit-timers!) ;; Clean up pending event timers
                           (emit-event events "destroy" {})
                           (set-ready false)
                           ;; Only clear the workspace FOCUS if THIS pane held it
                           ;; (another pane may be the focused one in a shared workspace).
                           (when (= (:active-uri @state-atom) (db/active-uri conn))
                             (db/reset-active-uri! conn))))
                       (catch js/Error error
                         (emit-event events "error" {:message (.-message error)
                                                     :operation "initEditorView"})
                         (log-error-with-cause "Error initializing EditorView" error)
                         (fn []))))
                   #js [(:extra-extensions @state-atom) container-ref])
                  (react/createElement "div" #js {:ref container-ref
                                                  :className "code-editor flex-grow-1"}))))]
  (def editor-comp (react/forwardRef inner))
  (def ^:export Editor editor-comp))
