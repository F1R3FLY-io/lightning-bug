(ns lib.core
  (:require
   ["@codemirror/state" :refer [EditorState]]
   ["@codemirror/view" :refer [EditorView]]
   ["react" :as react]
   ["rxjs" :as rxjs :refer [ReplaySubject]]
   [reagent.core :as r]
   [lib.db :as db]
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
     :search-term ""
     :lsp {}
     :lsp-document-opened {}  ; EXP-010: Cache {uri -> true} for documents opened with LSP
     :languages languages
     :tree-sitter-wasm tree-sitter-wasm
     :extra-extensions extra-extensions
     :lsp-init-timeout-ms lsp-init-timeout-ms
     :default-protocol default-protocol}))


;; Inner React functional component, handling CodeMirror integration and state management.
(let [inner (fn [js-props forwarded-ref]
              (let [props (normalize-editor-config (js->clj js-props :keywordize-keys true))
                    state-ref (react/useRef nil)]
                (when (nil? (.-current state-ref))
                  (set! (.-current state-ref) (r/atom (default-state props))))
                (let [state-atom (.-current state-ref)
                      view-ref (react/useRef nil)
                      [ready set-ready] (react/useState false)
                      events (react/useMemo (fn [] (ReplaySubject.)) #js [])
                      ;; Per-editor LSP client (ILspClient). Constructed once over this
                      ;; editor's state-atom + events; routes all LSP calls through the protocol.
                      client (react/useMemo (fn [] (cm/make-connection-manager state-atom events)) #js [])
                      ;; Per-editor context bundling the deps the imperative handle methods need.
                      ctx (react/useMemo (fn [] {:state-atom state-atom :view-ref view-ref :events events :client client}) #js [])
                      on-content-change (:on-content-change props)
                      container-ref (react/useRef nil)]
                  (react/useImperativeHandle
                   forwarded-ref
                   (fn [] (commands/build-handle ctx ready))
                   #js [@state-atom (.-current view-ref) ready])
                  (react/useEffect
                   (fn []
                     (when-let [^js editor-view (.-current view-ref)]
                       (let [^js editor-state (.-state editor-view)
                             current-doc (str (.-doc editor-state))
                             active-text (db/active-text)]
                         (when (not= active-text current-doc)
                           (log/debug "Updating view content to match db for active uri")
                           (.dispatch editor-view #js {:changes #js {:from 0
                                                                     :to (count current-doc)
                                                                     :insert active-text}})
                           (emit-event events "content-change" {:content active-text
                                                                :uri (db/active-uri)})
                           (when on-content-change
                             (on-content-change active-text)))))
                     js/undefined)
                   #js [(db/active-text)])
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
                             exts (get-extensions state-atom events on-content-change view-ref client)
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
                                                       (when-let [lang (db/document-language-by-uri uri)]
                                                         (p/request-symbols! client lang uri))))))))]
                         (set! (.-current view-ref) editor-view)
                         (js/setTimeout
                          (fn []
                            (emit-event events "ready" {})
                            (set-ready true))
                          0)
                         (update-editor-state editor-state state-atom events (db/active-uri))
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
                           (db/reset-active-uri!)))
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
