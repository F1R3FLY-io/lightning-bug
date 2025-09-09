(ns lib.core
  (:require
   ["@codemirror/autocomplete" :refer [closeBrackets]]
   ["@codemirror/commands" :refer [defaultKeymap history historyKeymap indentWithTab]]
   ["@codemirror/language" :refer [bracketMatching]]
   ["@codemirror/search" :refer [getSearchQuery search searchKeymap openSearchPanel]]
   ["@codemirror/state" :refer [Annotation
                                EditorSelection
                                EditorState
                                StateField]]
   ["@codemirror/view" :refer [EditorView
                               keymap
                               lineNumbers]]
   ["react" :as react]
   ["rxjs" :as rxjs :refer [ReplaySubject]]
   [clojure.core.async :refer [go <! put! promise-chan]]
   [clojure.string :as str]
   [datascript.core :as d]
   [reagent.core :as r]
   [lib.db :as db :refer [conn]]
   [lib.editor.diagnostics :as diagnostics :refer [set-diagnostic-effect]]
   [lib.editor.highlight :as highlight]
   [lib.editor.syntax :as syntax]
   [lib.lsp.client :as lsp]
   [lib.state :refer [normalize-languages normalize-editor-config validate-editor-config!]]
   [lib.utils :as lib-utils :refer [split-uri debounce]]
   [taoensso.timbre :as log]))

;; Hardcoded default languages for the library; uses string keys.
(defonce ^:const default-languages {"text" {:extensions [".txt"]
                                            :fallback-highlighter "none"}})

;; Annotation to mark transactions that update diagnostics in the StateField.
(def diagnostic-annotation (.define Annotation))

;; Annotation to mark external full-text set transactions (skips debounced LSP in update listener)
(def external-set-annotation (.define Annotation))

;; StateField to hold the current list of LSP diagnostics.
(def diagnostic-field
  (.define StateField
           #js {:create (fn [_] #js []) ;; Initial empty array of diagnostics.
                :update (fn [value ^js tr]
                          (if-let [new-diags (.annotation tr diagnostic-annotation)]
                            new-diags
                            value))}))

(defn- get-lang-from-ext
  "Returns the language key matching the given extension, or \"text\" if none.
  Logs warning if multiple matches."
  [languages ext]
  (let [matches (filter (fn [[_ conf]] (some #(= ext %) (:extensions conf))) languages)]
    (when (> (count matches) 1)
      (log/warn "Multiple languages match extension" ext ":" (keys matches) "- using first"))
    (or (ffirst matches) "text")))

(defn- get-ext-from-path
  "Extracts the file extension from a path (e.g., \".rho\" from \"/demo.rho\"), or nil if none."
  [path]
  (let [idx (str/last-index-of path ".")]
    (when (and idx (< idx (dec (count path))))
      (subs path idx))))

(defn- default-state
  "Computes the initial editor state from converted CLJS props.
  Ensures language keys are strings and falls back to 'text' if no language is provided."
  [props]
  (validate-editor-config! props)
  (let [languages (normalize-languages (merge default-languages (:languages props)))
        extra-extensions (:extra-extensions props #js [])
        default-protocol (or (:default-protocol props) "inmemory://")
        tree-sitter-wasm (:tree-sitter-wasm props "js/tree-sitter.wasm")]
    (when-not (every? string? (keys languages))
      (log/warn "Non-string keys found in languages map:" (keys languages)))
    {:cursor {:line 1 :column 1}
     :selection nil
     :search-term ""
     :lsp {}
     :languages languages
     :tree-sitter-wasm tree-sitter-wasm
     :extra-extensions extra-extensions
     :default-protocol default-protocol
     :debounce-timer nil}))

(defn- emit-event
  "Emits an event to the RxJS ReplaySubject with debouncing for frequent updates.
  Ensures consistent event emission for all state changes."
  [events type data]
  (log/trace "Emitting event:" type data)
  (.next events (clj->js {:type type :data data})))

(defn- update-editor-state
  "Updates the internal state-atom with cursor and selection info from CodeMirror state.
  Emits a debounced 'selection-change' event for external listeners."
  [^js cm-state state-atom events]
  (let [main-sel (.-main (.-selection cm-state))
        anchor (.-anchor main-sel)
        head (.-head main-sel)
        ^js doc (.-doc cm-state)
        cursor-pos (lib-utils/offset->pos doc head true)
        sel (when (not= anchor head)
              (let [from (min anchor head)
                    to (max anchor head)
                    from-pos (lib-utils/offset->pos doc from true)
                    to-pos (lib-utils/offset->pos doc to true)
                    text (.sliceString doc from to)]
                {:from from-pos :to to-pos :text text}))
        uri (db/active-uri)
        debounced-emit (debounce
                        #(emit-event events "selection-change" {:cursor cursor-pos
                                                                :selection sel
                                                                :uri uri})
                        200)]
    (swap! state-atom assoc :cursor cursor-pos :selection sel)
    (debounced-emit)))

(defn- get-extensions
  "Returns the array of CodeMirror extensions, including dynamic syntax compartment,
  static diagnostic compartment. Appends extra-extensions from state."
  [state-atom events on-content-change]
  (let [debounced-lsp (debounce (fn []
                                  (let [[uri text lang] (db/active-uri-text-lang)]
                                    (when (and uri text lang)
                                      (let [version (db/inc-document-version-by-uri! uri)]
                                        (lsp/notify-did-change lang uri text version state-atom)))))
                                200)
        debounced-search-emit (debounce
                               (fn [term]
                                 (let [uri (db/active-uri)]
                                   (emit-event events "search-term-change" {:term term :uri uri})))
                               200)
        update-ext (.. EditorView -updateListener
                       (of (fn [^js u]
                             (when (or (.-docChanged u) (.-selectionSet u))
                               (update-editor-state (.-state u) state-atom events))
                             (let [old-term (:search-term @state-atom "")
                                   new-term (or (.-search (getSearchQuery (.-state u))) "")]
                               (when (not= old-term new-term)
                                 (swap! state-atom assoc :search-term new-term)
                                 (debounced-search-emit new-term)))
                             (when (.-docChanged u)
                               (when-let [uri (db/active-uri)]
                                 (let [new-text (str (.-doc (.-state u)))]
                                   (log/trace "Document changed for uri:" uri "new length:" (count new-text))
                                   (db/update-document-text-by-uri! uri new-text)
                                   (emit-event events "content-change" {:content new-text :uri uri})
                                   (when on-content-change
                                     (on-content-change new-text))
                                   (when (db/document-opened-by-uri? uri)
                                     (when-not (some #(.annotation % external-set-annotation) (.-transactions u))
                                       (debounced-lsp)))))))))
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

(defn- promise->chan [p]
  (let [ch (promise-chan)]
    (-> p
        (.then #(put! ch :ready))
        (.catch #(put! ch :unreachable)))
    ch))

(defn- get-connect-promise [lang lsp-url state-atom events]
  (or (get-in @state-atom [:lsp lang :connect-promise])
      (let [p (js/Promise. (fn [res-fn rej-fn]
                             (swap! state-atom assoc-in [:lsp lang :promise-res-fn] res-fn)
                             (swap! state-atom assoc-in [:lsp lang :promise-rej-fn] rej-fn)
                             (log/info "Initiating LSP connection for lang:" lang "url:" lsp-url)
                             (lsp/connect lang {:url lsp-url} state-atom events)))]
        (swap! state-atom assoc-in [:lsp lang :connect-promise] p)
        p)))

(defn- ensure-lsp-document-opened
  "Ensures the document is opened in LSP if configured, connecting if necessary.
  Sends didOpen and requests symbols on success, emitting events for LSP actions."
  [lang uri state-atom events]
  (let [[text version] (db/doc-text-version-by-uri uri)]
    (when-let [lsp-url (get-in @state-atom [:languages lang :lsp-url])]
      (let [lsp-state (get-in @state-atom [:lsp lang])
            connected? (:connected? lsp-state)
            initialized? (:initialized? lsp-state)]
        (log/debug "Ensuring LSP document opened for lang:" lang "uri:" uri "connected?" connected? "initialized?" initialized?)
        (go
          (try
            (when-not (and connected? initialized?)
              (let [p (get-connect-promise lang lsp-url state-atom events)
                    res (<! (promise->chan p))]
                (when (and @state-atom (= res :unreachable))
                  (emit-event events "lsp-error" {:message "Failed to connect and initialize LSP"
                                                  :lang lang})
                  (log/error "Failed to connect and initialize LSP for lang" lang))))
            (when (and (get-in @state-atom [:lsp lang :connected?])
                       (get-in @state-atom [:lsp lang :initialized?])
                       (not (db/document-opened-by-uri? uri)))
              (lsp/notify-did-open lang uri text version state-atom)
              (emit-event events "lsp-message" {:method "textDocument/didOpen"
                                                :lang lang
                                                :params {:textDocument {:uri uri
                                                                        :languageId lang
                                                                        :version version
                                                                        :text text}}})
              (db/document-opened-by-uri! uri)
              (emit-event events "document-open" {:uri uri
                                                  :content text
                                                  :language lang
                                                  :opened true}))
            [:ok nil]
            (catch :default e
              [:error (js/Error. (str "(ensure-lsp-document-opened " lang " " uri " state-atom events) failed") #js {:cause e})])))))))

(defn- activate-document
  "Activates the document with the given URI, loading content and re-initializing syntax if language changes.
  Emits events for document activation and LSP open if necessary."
  [uri state-atom view-ref events]
  (go
    (try
      (log/info "Activating document:" uri)
      (let [old-lang (db/active-lang)]
        (db/update-active-uri! uri)
        (let [[text new-lang] (db/doc-text-lang-by-uri uri)]
          (when-let [view (.-current view-ref)]
            (let [current-doc (.-doc (.-state view))
                  current-length (.-length current-doc)]
              (.dispatch view #js {:changes #js {:from 0
                                                 :to current-length
                                                 :insert text}})))
          (when (not= old-lang new-lang)
            (if-let [res (<! (syntax/init-syntax (.-current view-ref) state-atom))]
              (if (= :ok (first res))
                (emit-event events "language-change" {:uri uri :language new-lang})
                (throw (js/Error. (str "(activate-document " uri " state-atom view-ref events) failed") #js {:cause (second res)})))
              (throw (js/Error. (str "(syntax/init-syntax (.-current view-ref) state-atom) returned nothing in call to (activate-document " uri " state-atom view-ref events)")))))
          (if (get-in @state-atom [:languages new-lang :lsp-url])
            (let [res (<! (ensure-lsp-document-opened new-lang uri state-atom events))]
              (when (= :error (first res))
                (throw (js/Error. (str "(activate-document " uri " state-atom view-ref events) failed") #js {:cause (second res)}))))
            (db/document-opened-by-uri! uri))
          (emit-event events "document-open" {:uri uri
                                              :content text
                                              :language new-lang
                                              :activated true})))
      [:ok nil]
      (catch js/Error error
        (emit-event events "error" {:message (.-message error) :uri uri})
        (log/error "Failed to activate document" uri ":" (.-message error))
        [:error (js/Error. (str "(activate-document " uri " state-atom view-ref events) failed") #js {:cause error})]))))

(defn- normalize-uri [file-or-uri-js default-protocol]
  (if (and file-or-uri-js (pos? (count file-or-uri-js)))
    (let [file-or-uri (js->clj file-or-uri-js :keywordize-keys true)]
      (if (re-find #"^[a-zA-Z]+:" file-or-uri)
        file-or-uri
        (str (or default-protocol "inmemory://") file-or-uri)))
    (if-let [active-uri (db/active-uri)]
      active-uri
      (throw
       (js/Error.
        "Invalid URI or file path: either parameter must be non-empty or a document must be active")))))

;; Inner React functional component, handling CodeMirror integration and state management.
(let [inner (fn [js-props forwarded-ref]
              (let [props (normalize-editor-config (js->clj js-props :keywordize-keys true))
                    state-ref (react/useRef nil)]
                (when (nil? (.-current state-ref))
                  (set! (.-current state-ref) (r/atom (default-state props))))
                (let [state-atom (.-current state-ref)
                      view-ref (react/useRef nil)
                      [ready set-ready] (react/useState false)
                      events (react/useMemo (fn [] (ReplaySubject. 1)) #js [])
                      on-content-change (:on-content-change props)
                      container-ref (react/useRef nil)]
                  (react/useImperativeHandle
                   forwarded-ref
                   (fn []
                     #js {;; Returns the full current state (workspace, diagnostics, symbols, etc.).
                          ;; Example: (.getState editor)
                          :getState (fn []
                                      (try
                                        (log/trace "Fetching editor state")
                                        (clj->js (assoc @state-atom
                                                        :workspace {:documents (db/documents)
                                                                    :activeUri (db/active-uri)}
                                                        :logs (db/logs)
                                                        :diagnostics (db/diagnostics)
                                                        :symbols (db/symbols)
                                                        :searchTerm (:search-term @state-atom "")))
                                        (catch js/Error error
                                          (emit-event events "error" {:message (.-message error)
                                                                      :operation "getState"})
                                          (log/error "Error in getState:" (.-message error))
                                          #js {})))
                          ;; Returns RxJS observable for subscribing to events.
                          ;; Example: (.subscribe (.getEvents editor) (fn [evt] (js/console.log (.-type evt) (.-data evt))))
                          :getEvents (fn [] events)
                          ;; Returns current cursor position (1-based) for active document.
                          ;; Example: (.getCursor editor)
                          :getCursor (fn []
                                       (try
                                         (log/trace "Fetching cursor position")
                                         (clj->js (:cursor @state-atom))
                                         (catch js/Error error
                                           (emit-event events "error" {:message (.-message error)
                                                                       :operation "getCursor"})
                                           (log/error "Error in getCursor:" (.-message error))
                                           #js {:line 1 :column 1})))
                          ;; Sets cursor position for active document (triggers `selection-change` event).
                          ;; Example: (.setCursor editor #js {:line 1 :column 3})
                          :setCursor (fn [pos-js]
                                       (try
                                         (let [pos (js->clj pos-js :keywordize-keys true)]
                                           (log/trace "Setting cursor to" pos)
                                           (if-let [^js editor-view (.-current view-ref)]
                                             (let [^js editor-state (.-state editor-view)
                                                   ^js doc (.-doc editor-state)
                                                   offset (lib-utils/pos->offset doc pos true)]
                                               (if offset
                                                 (do
                                                   (.dispatch editor-view #js {:selection (EditorSelection.cursor offset)})
                                                   (emit-event events "selection-change" {:cursor pos
                                                                                          :selection nil
                                                                                          :uri (db/active-uri)}))
                                                 (do
                                                   (emit-event events "error" {:message "Invalid cursor position"
                                                                               :operation "setCursor"
                                                                               :pos pos})
                                                   (log/warn "Invalid cursor position:" pos))))
                                             (do
                                               (emit-event events "error" {:message "View not ready"
                                                                           :operation "setCursor"})
                                               (log/warn "Skipping set cursor: view-ref not ready"))))
                                         (catch js/Error error
                                           (emit-event events "error" {:message (.-message error)
                                                                       :operation "setCursor"})
                                           (log/error "Error in setCursor:" (.-message error)))))
                          ;; Returns current selection range and text for active document, or `null` if no selection.
                          ;; Example: (.getSelection editor)
                          :getSelection (fn []
                                          (try
                                            (log/trace "Fetching selection")
                                            (clj->js (:selection @state-atom))
                                            (catch js/Error error
                                              (emit-event events "error" {:message (.-message error)
                                                                          :operation "getSelection"})
                                              (log/error "Error in getSelection:" (.-message error))
                                              nil)))
                          ;; Sets selection range for active document (triggers `selection-change` event).
                          ;; Example: (.setSelection editor #js {:line 1 :column 1} #js {:line 1 :column 6})
                          :setSelection (fn [from-js to-js]
                                          (try
                                            (let [from (js->clj from-js :keywordize-keys true)
                                                  to (js->clj to-js :keywordize-keys true)]
                                              (log/trace "Setting selection from" from "to" to)
                                              (if-let [^js editor-view (.-current view-ref)]
                                                (let [^js editor-state (.-state editor-view)
                                                      ^js doc (.-doc editor-state)
                                                      from-offset (lib-utils/pos->offset doc from true)
                                                      to-offset (lib-utils/pos->offset doc to true)]
                                                  (if (and from-offset to-offset (<= from-offset to-offset))
                                                    (do
                                                      (.dispatch editor-view #js {:selection (EditorSelection.range from-offset to-offset)})
                                                      (emit-event events "selection-change" {:cursor to
                                                                                             :selection {:from from
                                                                                                         :to to
                                                                                                         :text (.sliceString doc from-offset to-offset)}
                                                                                             :uri (db/active-uri)}))
                                                    (do
                                                      (emit-event events "error" {:message "Invalid selection range"
                                                                                  :operation "setSelection"
                                                                                  :from from
                                                                                  :to to})
                                                      (log/warn "Cannot set selection: invalid offsets" from to))))
                                                (do
                                                  (emit-event events "error" {:message "View not ready"
                                                                              :operation "setSelection"})
                                                  (log/warn "Skipping set selection: view-ref not ready"))))
                                            (catch js/Error error
                                              (emit-event events "error" {:message (.-message error)
                                                                          :operation "setSelection"})
                                              (log/error "Error in setSelection:" (.-message error)))))
                          ;; Opens or activates a document with file path or URI, optional content and language (triggers `document-open`).
                          ;; Reuses if exists, updates if provided. Notifies LSP if connected. If fourth param make-active-js is false,
                          ;; opens without activating.
                          ;; Example: (.openDocument editor "demo.rho" "new x in { x!(\"Hello\") | Nil }" "rholang")
                          ;;          (.openDocument editor "demo.rho") ; activates existing
                          ;;          (.openDocument editor "demo.rho" nil nil false) ; opens without activating
                          :openDocument (fn [file-or-uri-js text-js lang-js & [make-active-js]]
                                          (if-let [uri (normalize-uri file-or-uri-js (:default-protocol @state-atom))]
                                            (try
                                              (log/info "Opening document:" uri)
                                              (let [text (js->clj text-js :keywordize-keys true)
                                                    lang (js->clj lang-js :keywordize-keys true)
                                                    make-active (if (nil? make-active-js) true (boolean make-active-js))
                                                    [_protocol path] (split-uri uri)
                                                    ext (get-ext-from-path path)
                                                    [id current-text current-lang] (db/doc-id-text-lang-by-uri uri)
                                                    effective-lang (or lang
                                                                       current-lang
                                                                       (when ext
                                                                         (get-lang-from-ext (:languages @state-atom) ext))
                                                                       "text")
                                                    effective-text (or text current-text "")
                                                    changed? (not= current-text effective-text)]
                                                (if id
                                                  (do
                                                    (db/update-document-text-language-by-id! id effective-text effective-lang)
                                                    (when (and changed? (db/document-opened-by-uri? uri))
                                                      (let [version (db/inc-document-version-by-uri! uri)]
                                                        (lsp/notify-did-change effective-lang uri effective-text version state-atom)
                                                        (emit-event events "lsp-message" {:method "textDocument/didChange"
                                                                                          :lang effective-lang
                                                                                          :params {:textDocument {:uri uri
                                                                                                                  :version version}}}))))
                                                  (db/create-documents! [{:uri uri
                                                                          :text effective-text
                                                                          :language effective-lang
                                                                          :version 1
                                                                          :dirty (boolean changed?)
                                                                          :opened false}]))
                                                (when make-active
                                                  (activate-document uri state-atom view-ref events))
                                                (emit-event events "document-open" {:uri uri
                                                                                    :content effective-text
                                                                                    :language effective-lang
                                                                                    :activated make-active}))
                                              (catch js/Error error
                                                (emit-event events "error" {:message (.-message error)
                                                                            :operation "openDocument"
                                                                            :uri uri})
                                                (log/error "Error in openDocument:" (.-message error))))
                                            (let [error-message (str "Invalid file path or URI: " file-or-uri-js)]
                                              (emit-event events "error" {:message error-message
                                                                          :operation "openDocument"
                                                                          :uri file-or-uri-js})
                                              (log/error "Failed to open document:" error-message))))
                          ;; Closes the specified or active document (triggers `document-close`). Notifies LSP if open.
                          ;; Example: (.closeDocument editor)
                          ;; Example: (.closeDocument editor "specific-uri")
                          :closeDocument (fn [file-or-uri-js]
                                           (try
                                             (when-let [uri (normalize-uri file-or-uri-js (:default-protocol @state-atom))]
                                               (log/info "Closing document:" uri)
                                               (let [[id lang opened?] (db/document-id-lang-opened-by-uri uri)]
                                                 (when opened?
                                                   (lsp/notify-did-close lang uri state-atom)
                                                   (emit-event events "lsp-message" {:method "textDocument/didClose"
                                                                                     :lang lang
                                                                                     :params {:textDocument {:uri uri}}}))
                                                 (db/delete-document-by-id! id)
                                                 (when (db/active-uri? uri)
                                                   (if-let [next-uri (db/first-document-uri)]
                                                     (activate-document next-uri state-atom view-ref events)
                                                     (emit-event events "document-open" {:uri nil
                                                                                         :content ""
                                                                                         :language "text"
                                                                                         :activated true})))
                                                 (emit-event events "document-close" {:uri uri})))
                                             (catch js/Error error
                                               (emit-event events "error" {:message (.-message error)
                                                                           :operation "closeDocument"
                                                                           :uri file-or-uri-js})
                                               (log/error "Error in closeDocument:" (.-message error)))))
                          ;; Renames the specified or active document (updates URI, triggers `document-rename`). Notifies LSP.
                          ;; Example: (.renameDocument editor "new-name.rho")
                          ;;          (.renameDocument editor "new-name.rho" "old-uri")
                          :renameDocument (fn [new-file-or-uri-js old-file-or-uri-js]
                                            (go
                                              (try
                                                (when-not new-file-or-uri-js
                                                  (throw
                                                   (js/Error.
                                                    (str "Invalid `new-file-or-uri-js` passed to `renameDocument`:" new-file-or-uri-js))))
                                                (let [default-protocol (:default-protocol @state-atom)
                                                      new-uri (normalize-uri new-file-or-uri-js default-protocol)
                                                      old-uri (normalize-uri old-file-or-uri-js default-protocol)]
                                                  (log/info "Renaming document from" old-uri "to" new-uri)
                                                  (when (not= new-uri old-uri)
                                                    (let [new-ext (get-ext-from-path new-uri)
                                                          new-lang (when new-ext (get-lang-from-ext (:languages @state-atom) new-ext))
                                                          [id old-lang opened?] (db/document-id-lang-opened-by-uri old-uri)
                                                          lang-changed? (and new-lang (not= new-lang old-lang))]
                                                      (when (and old-uri id)
                                                        (when-not (db/document-id-by-uri new-uri)
                                                          (when opened?
                                                            (when-not lang-changed?
                                                              (lsp/notify-did-rename-files old-lang old-uri new-uri state-atom)
                                                              (emit-event events "lsp-message" {:method "workspace/didRenameFiles"
                                                                                                :lang old-lang
                                                                                                :params {:files [{:oldUri old-uri
                                                                                                                  :newUri new-uri}]}})))
                                                          (if lang-changed?
                                                            (db/update-document-uri-language-by-id! id new-uri new-lang)
                                                            (db/update-document-uri-by-id! id new-uri))
                                                          (when (= old-uri (db/active-uri))
                                                            (db/update-active-uri! new-uri)
                                                            (when-let [^js editor-view (.-current view-ref)]
                                                              (if-let [res (<! (syntax/init-syntax editor-view state-atom))]
                                                                (when (= :error (first res))
                                                                  (throw (js/Error. (str "(.renameDocument this " new-file-or-uri-js " " old-file-or-uri-js ") failed") #js {:cause (second res)})))
                                                                (throw (js/Error. (str "(syntax/init-syntax editor-view state-atom) returned nothing in call to (.renameDocument editor " new-file-or-uri-js " " old-file-or-uri-js ") failed"))))))
                                                          (when-not (db/document-opened-by-uri? new-uri)
                                                            (ensure-lsp-document-opened new-lang new-uri state-atom events))
                                                          (emit-event events "document-rename" {:old-uri old-uri
                                                                                                :new-uri new-uri}))))))
                                                [:ok nil]
                                                (catch js/Error error
                                                  (emit-event events "error" {:message (.-message error)
                                                                              :operation "renameDocument"
                                                                              :old-uri old-file-or-uri-js
                                                                              :new-uri new-file-or-uri-js})
                                                  (let [error-with-cause (js/Error. (str "(.renameDocument editor " new-file-or-uri-js " " old-file-or-uri-js ") failed") #js {:cause error})]
                                                    (lib-utils/log-error-with-cause error-with-cause))))))
                          ;; Saves the specified or active document (triggers `document-save`). Notifies LSP via `didSave`.
                          ;; Example: (.saveDocument editor)
                          ;; Example: (.saveDocument editor "specific-uri")
                          :saveDocument (fn [file-or-uri-js]
                                          (try
                                            (let [uri (normalize-uri file-or-uri-js (:default-protocol @state-atom))
                                                  id (db/document-id-by-uri uri)
                                                  [text lang dirty] (db/doc-text-lang-dirty-by-uri uri)]
                                              (log/info "Saving document:" uri)
                                              (when (and uri dirty)
                                                (when (get-in @state-atom [:lsp lang :connected?])
                                                  (lsp/notify-did-save lang uri text state-atom)
                                                  (emit-event events "lsp-message" {:method "textDocument/didSave"
                                                                                    :lang lang
                                                                                    :params {:textDocument {:uri uri}}}))
                                                (db/update-document-dirty-by-id! id false)
                                                (emit-event events "document-save" {:uri uri
                                                                                    :content text})))
                                            (catch js/Error error
                                              (emit-event events "error" {:message (.-message error)
                                                                          :operation "saveDocument"
                                                                          :uri file-or-uri-js})
                                              (log/error "Error in saveDocument:" (.-message error)))))
                          ;; Returns `true` if editor is initialized and ready for methods.
                          ;; Example: (.isReady editor)
                          :isReady (fn [] ready)
                          ;; Highlights a range in active document (triggers `highlight-change` with range).
                          ;; Example: (.highlightRange editor #js {:line 1 :column 1} #js {:line 1 :column 5})
                          :highlightRange (fn [from-js to-js]
                                            (try
                                              (let [from (js->clj from-js :keywordize-keys true)
                                                    to (js->clj to-js :keywordize-keys true)]
                                                (log/trace "Highlighting range from" from "to" to)
                                                (if-let [^js editor-view (.-current view-ref)]
                                                  (let [^js editor-state (.-state editor-view)
                                                        ^js doc (.-doc editor-state)
                                                        from-offset (lib-utils/pos->offset doc from true)
                                                        to-offset (lib-utils/pos->offset doc to true)]
                                                    (if (and from-offset to-offset (<= from-offset to-offset))
                                                      (do
                                                        (.dispatch editor-view
                                                                   #js {:annotations (.of highlight/highlight-annotation (clj->js {:from from
                                                                                                                                   :to to}))})
                                                        (emit-event events "highlight-change" {:from from
                                                                                               :to to}))
                                                      (do
                                                        (emit-event events "error" {:message "Invalid highlight range"
                                                                                    :operation "highlightRange"
                                                                                    :from from
                                                                                    :to to})
                                                        (log/warn "Cannot highlight range: invalid offsets"))))
                                                  (do
                                                    (emit-event events "error" {:message "View not ready" :operation "highlightRange"})
                                                    (log/warn "Cannot highlight range: view-ref is nil"))))
                                              (catch js/Error error
                                                (emit-event events "error" {:message (.-message error) :operation "highlightRange"})
                                                (log/error "Error in highlightRange:" (.-message error)))))
                          ;; Clears highlight in active document (triggers `highlight-change` with `null`).
                          ;; Example: (.clearHighlight editor)
                          :clearHighlight (fn []
                                            (try
                                              (log/trace "Clearing highlight")
                                              (if-let [^js editor-view (.-current view-ref)]
                                                (do
                                                  (.dispatch editor-view #js {:annotations (.of highlight/highlight-annotation nil)})
                                                  (emit-event events "highlight-change" nil))
                                                (do
                                                  (emit-event events "error" {:message "View not ready"
                                                                              :operation "clearHighlight"})
                                                  (log/warn "Skipping clear highlight: view-ref not ready")))
                                              (catch js/Error error
                                                (emit-event events "error" {:message (.-message error)
                                                                            :operation "clearHighlight"})
                                                (log/error "Error in clearHighlight:" (.-message error)))))
                          ;; Scrolls to center on a range in active document (triggers `scroll` event).
                          ;; Example: (.centerOnRange editor #js {:line 1 :column 1} #js {:line 1 :column 6})
                          :centerOnRange (fn [from-js to-js]
                                           (try
                                             (let [from (js->clj from-js :keywordize-keys true)
                                                   to (js->clj to-js :keywordize-keys true)]
                                               (log/trace "Centering on range from" from "to" to)
                                               (if-let [^js editor-view (.-current view-ref)]
                                                 (let [^js doc (.-doc ^js (.-state editor-view))
                                                       from-offset (lib-utils/pos->offset doc from true)
                                                       to-offset (lib-utils/pos->offset doc to true)]
                                                   (if (and from-offset to-offset)
                                                     (do
                                                       (.dispatch editor-view #js {:effects (EditorView.scrollIntoView
                                                                                             (.range EditorSelection from-offset to-offset)
                                                                                             #js {:y "center"})})
                                                       (emit-event events "scroll" {:from from :to to}))
                                                     (do
                                                       (emit-event events "error" {:message "Invalid scroll range"
                                                                                   :operation "centerOnRange"
                                                                                   :from from
                                                                                   :to to})
                                                       (log/warn "Cannot center on range: invalid offsets"))))
                                                 (do
                                                   (emit-event events "error" {:message "View not ready"
                                                                               :operation "centerOnRange"})
                                                   (log/warn "Cannot center on range: view-ref not ready"))))
                                             (catch js/Error error
                                               (emit-event events "error" {:message (.-message error)
                                                                           :operation "centerOnRange"})
                                               (log/error "Error in centerOnRange:" (.-message error)))))
                          ;; Returns text for specified or active document, or `null` if not found.
                          ;; Example: (.getText editor)
                          ;; Example: (.getText editor "specific-uri")
                          :getText (fn [file-or-uri-js]
                                     (try
                                       (let [uri (normalize-uri file-or-uri-js (:default-protocol @state-atom))
                                             text (db/document-text-by-uri uri)]
                                         (log/trace "Fetching text for uri:" uri)
                                         (or text nil))
                                       (catch js/Error error
                                         (emit-event events "error" {:message (.-message error)
                                                                     :operation "getText"
                                                                     :uri file-or-uri-js})
                                         (log/error "Error in getText:" (.-message error))
                                         nil)))
                          ;; Replaces entire text for specified or active document (triggers `content-change`).
                          ;; Example: (.setText editor "new text")
                          ;; Example: (.setText editor "new text" "specific-uri")
                          :setText (fn [text-js file-or-uri-js]
                                     (try
                                       (let [text (js->clj text-js :keywordize-keys true)
                                             uri (normalize-uri file-or-uri-js (:default-protocol @state-atom))
                                             id (db/document-id-by-uri uri)
                                             [lang opened?] (db/document-language-opened-by-uri uri)
                                             current-text (db/document-text-by-uri uri)
                                             changed? (not= current-text text)]
                                         (log/info "Setting text for uri:" uri "length:" (count text))
                                         (when uri
                                           (when changed?
                                             (db/update-document-text-by-id! id text)
                                             (when (= uri (db/active-uri))
                                               (if-let [^js editor-view (.-current view-ref)]
                                                 (let [^js editor-state (.-state editor-view)
                                                       ^js doc (.-doc editor-state)
                                                       len (.-length doc)]
                                                   (.dispatch editor-view #js {:changes #js {:from 0
                                                                                             :to len
                                                                                             :insert text}
                                                                               :annotations (.of external-set-annotation true)}))
                                                 (log/warn "Cannot set editor text: view not ready")))
                                             (when (and lang opened? (get-in @state-atom [:lsp lang :connected?]))
                                               (let [version (db/inc-document-version-by-id! id)]
                                                 (lsp/notify-did-change lang uri text version state-atom)
                                                 (emit-event events "lsp-message" {:method "textDocument/didChange"
                                                                                   :lang lang
                                                                                   :params {:textDocument {:uri uri
                                                                                                           :version version}}})))
                                             (emit-event events "content-change" {:content text
                                                                                  :uri uri}))))
                                       (catch js/Error error
                                         (emit-event events "error" {:message (.-message error)
                                                                     :operation "setText"
                                                                     :uri file-or-uri-js})
                                         (log/error "Error in setText:" (.-message error)))))
                          ;; Returns file path (e.g., `"/demo.rho"`) for specified or active, or null if none.
                          ;; Example: (.getFilePath editor)
                          ;; Example: (.getFilePath editor "specific-uri")
                          :getFilePath (fn [file-or-uri-js]
                                         (try
                                           (let [uri (normalize-uri file-or-uri-js (:default-protocol @state-atom))
                                                 [_ path] (split-uri uri)]
                                             (log/trace "Fetching file path for uri:" uri)
                                             (or path nil))
                                           (catch js/Error error
                                             (emit-event events "error" {:message (.-message error)
                                                                         :operation "getFilePath"
                                                                         :uri file-or-uri-js})
                                             (log/error "Error in getFilePath:" (.-message error))
                                             nil)))
                          ;; Returns full URI (e.g., `"inmemory:///demo.rho"`) for specified or active, or `null` if none.
                          ;; Example: (.getFileUri editor)
                          ;; Example: (.getFileUri editor "specific-uri")
                          :getFileUri (fn [file-or-uri-js]
                                        (try
                                          (let [uri (normalize-uri file-or-uri-js (:default-protocol @state-atom))]
                                            (log/trace "Fetching file URI:" uri)
                                            (or uri nil))
                                          (catch js/Error error
                                            (emit-event events "error" {:message (.-message error)
                                                                        :operation "getFileUri"
                                                                        :uri file-or-uri-js})
                                            (log/error "Error in getFileUri:" (.-message error))
                                            nil)))
                          ;; Sets the active document if exists, loads content to view, opens in LSP if not (triggers `document-open`).
                          ;; Example: (.activateDocument editor "demo.rho")
                          :activateDocument (fn [file-or-uri-js]
                                              (try
                                                (let [uri (normalize-uri file-or-uri-js (:default-protocol @state-atom))]
                                                  (if (db/document-id-by-uri uri)
                                                    (when (not= uri (db/active-uri))
                                                      (activate-document uri state-atom view-ref events))
                                                    (do
                                                      (emit-event events "error" {:message "Document not found"
                                                                                  :operation "activateDocument"
                                                                                  :uri uri})
                                                      (log/warn "Document not found for activation" uri))))
                                                (catch js/Error error
                                                  (emit-event events "error" {:message (.-message error)
                                                                              :operation "activateDocument"
                                                                              :uri file-or-uri-js})
                                                  (log/error "Error in activateDocument:" (.-message error)))))
                          ;; Queries the internal DataScript database with the given query and optional params.
                          ;; Returns the result as JS array.
                          ;; Example: (.query editor '[:find ?uri :where [?e :document/uri ?uri]])
                          :query (fn [query-js params-js]
                                   (try
                                     (let [query (js->clj query-js :keywordize-keys true)
                                           params (if params-js (js->clj params-js) [])]
                                       (log/trace "Executing query:" query "with params:" params)
                                       (clj->js (apply d/q query @conn params)))
                                     (catch js/Error error
                                       (emit-event events "error" {:message (.-message error)
                                                                   :operation "query"})
                                       (log/error "Error in query:" (.-message error))
                                       #js [])))
                          ;; Returns the DataScript connection object for direct access (advanced use).
                          ;; Example: (.getDb editor)
                          :getDb (fn [] conn)
                          ;; Retrieves LSP diagnostics for the target file (optional fileOrUri, defaults to active).
                          ;; Example: (.getDiagnostics editor)
                          ;; Example: (.getDiagnostics editor 'inmemory://demo.rho')
                          :getDiagnostics (fn [file-or-uri-js]
                                            (try
                                              (let [uri (normalize-uri file-or-uri-js (:default-protocol @state-atom))]
                                                (log/trace "Fetching diagnostics for uri:" uri)
                                                (clj->js (db/diagnostics-by-uri uri)))
                                              (catch js/Error error
                                                (emit-event events "error" {:message (.-message error)
                                                                            :operation "getDiagnostics"
                                                                            :uri file-or-uri-js})
                                                (log/error "Error in getDiagnostics:" (.-message error))
                                                #js [])))
                          ;; Retrieves LSP symbols for the target file (optional fileOrUri, defaults to active).
                          ;; Example: (.getSymbols editor)
                          ;; Example: (.getSymbols editor 'inmemory://demo.rho')
                          :getSymbols (fn [file-or-uri-js]
                                        (try
                                          (let [uri (normalize-uri file-or-uri-js (:default-protocol @state-atom))]
                                            (log/trace "Fetching symbols for uri:" uri)
                                            (clj->js (db/symbols-by-uri uri)))
                                          (catch js/Error error
                                            (emit-event events "error" {:message (.-message error)
                                                                        :operation "getSymbols"
                                                                        :uri file-or-uri-js})
                                            (log/error "Error in getSymbols:" (.-message error))
                                            #js [])))
                          ;; Returns the current search term.
                          ;; Example: (.getSearchTerm editor)
                          :getSearchTerm (fn []
                                           (try
                                             (log/trace "Fetching search term")
                                             (or (:search-term @state-atom) "")
                                             (catch js/Error error
                                               (emit-event events "error" {:message (.-message error)
                                                                           :operation "getSearchTerm"})
                                               (log/error "Error in getSearchTerm:" (.-message error))
                                               "")))
                          ;; Opens the search panel in the editor.
                          ;; Example: (.openSearchPanel editor)
                          :openSearchPanel (fn []
                                             (try
                                               (if-let [^js editor-view (.-current view-ref)]
                                                 (openSearchPanel editor-view)
                                                 (do
                                                   (emit-event events "error" {:message "View not ready"
                                                                               :operation "openSearchPanel"})
                                                   (log/warn "View not ready for openSearchPanel")))
                                               (catch js/Error error
                                                 (emit-event events "error" {:message (.-message error)
                                                                             :operation "openSearchPanel"})
                                                 (log/error "Error in openSearchPanel:" (.-message error)))))})
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
                                                                     :to (.length current-doc)
                                                                     :insert active-text}})
                           (emit-event events "content-change" {:content active-text
                                                                :uri (db/active-uri)})
                           (when on-content-change
                             (on-content-change active-text)))))
                     js/undefined)
                   #js [(db/active-text)])
                  (react/useEffect
                   (fn []
                     ;; Internal subscription to events for handling diagnostics updates by dispatching
                     ;; a transaction to update the diagnostic StateField.
                     (if-let [^js editor-view (.-current view-ref)]
                       (let [sub (.subscribe events
                                             (fn [evt-js]
                                               (let [evt (js->clj evt-js :keywordize-keys true)
                                                     type (:type evt)]
                                                 (when (= type "diagnostics")
                                                   (let [diags (:data evt)]
                                                     (log/trace "Updating diagnostics in view for uri:" (:uri evt))
                                                     (.dispatch editor-view #js {:effects #js [(.of set-diagnostic-effect (clj->js diags))]})
                                                     (let [uri (:uri evt)]
                                                       (when-let [lang (db/document-language-by-uri uri)]
                                                         (lsp/request-document-symbol lang uri state-atom))))))))]
                         (fn [] (.unsubscribe sub)))
                       js/undefined))
                   #js [(.-current view-ref)])
                  (react/useEffect
                   (fn []
                     (let [shutdown-all (fn []
                                          (doseq [[lang _] (:lsp @state-atom)]
                                            (lsp/request-shutdown lang state-atom)))]
                       (js/window.addEventListener "beforeunload" shutdown-all)
                       (fn []
                         (js/window.removeEventListener "beforeunload" shutdown-all))))
                   #js [])
                  (react/useEffect
                   (fn []
                     (log/info "Editor: Initializing EditorView")
                     (try
                       (let [container (.-current container-ref)
                             exts (get-extensions state-atom events on-content-change)
                             editor-state (EditorState.create #js {:doc ""
                                                                   :extensions exts})
                             editor-view (EditorView. #js {:state editor-state
                                                           :parent container})]
                         (set! (.-current view-ref) editor-view)
                         (js/setTimeout
                          (fn []
                            (emit-event events "ready" {})
                            (set-ready true))
                          0)
                         (syntax/init-syntax editor-view state-atom)
                         (update-editor-state editor-state state-atom events)
                         (fn []
                           (log/info "Editor: Destroying EditorView")
                           (doseq [[lang lsp-state] (:lsp @state-atom)]
                             (lsp/request-shutdown lang state-atom)
                             (when-let [rej-fn (:promise-rej-fn lsp-state)]
                               (rej-fn (js/Error. (str "LSP connection cancelled: editor unmounted for language " lang))))
                             (swap! state-atom update-in [:lsp lang] dissoc :promise-res-fn :promise-rej-fn :connect-promise))
                           (when-let [editor-view (.-current view-ref)]
                             (.destroy editor-view))
                           (set! (.-current view-ref) nil)
                           (reset! state-atom nil)
                           (emit-event events "destroy" {})))
                       (catch js/Error error
                         (emit-event events "error" {:message (.-message error)
                                                     :operation "initEditorView"})
                         (lib-utils/log-error-with-cause "Error initializing EditorView" error)
                         (fn []))))
                   #js [(db/active-lang) (:extra-extensions @state-atom) container-ref])
                  (react/createElement "div" #js {:ref container-ref
                                                  :className "code-editor flex-grow-1"}))))]
  (def editor-comp (react/forwardRef inner))
  (def ^:export Editor editor-comp))
