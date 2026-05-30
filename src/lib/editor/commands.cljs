(ns lib.editor.commands
  "Imperative editor operations exposed on the public Editor handle (React
  useImperativeHandle). Extracted from lib.core to shrink the component: build-handle
  takes the per-editor context {:state-atom :view-ref :events :client} plus the current
  `ready` flag and returns the #js handle object. The method bodies are unchanged, so the
  move is behavior-preserving (the public Editor API surface is identical)."
  (:require
   ["@codemirror/state" :refer [EditorSelection]]
   ["@codemirror/view" :refer [EditorView]]
   ["@codemirror/search" :refer [openSearchPanel]]
   [clojure.core.async :refer [go <!]]
   [datascript.core :as d]
   [lib.db :as db]
   [lib.editor.annotations :refer [external-set-annotation]]
   [lib.editor.highlight :as highlight]
   [lib.editor.syntax :as syntax]
   [lib.editor.runtime :as rt :refer [emit-event activate-document ensure-lsp-document-opened get-ext-from-path]]
   [domain.protocols :as p]
   [lib.utils :refer [split-uri pos->offset log-error-with-cause get-lang-from-ext]]
   [taoensso.timbre :as log]))

(defn- normalize-uri [state-atom file-or-uri-js default-protocol]
  (if (and file-or-uri-js (pos? (count file-or-uri-js)))
    (let [file-or-uri (js->clj file-or-uri-js :keywordize-keys true)]
      (if (re-find #"^[a-zA-Z]+:" file-or-uri)
        file-or-uri
        (str (or default-protocol "inmemory://") file-or-uri)))
    (:active-uri @state-atom)))  ;; Returns nil if no active URI

(defn build-handle
  "Builds the imperative #js handle for the Editor ref. ctx is the per-editor context
  {:state-atom :view-ref :events :client}; ready is the current readiness flag."
  [ctx ready]
  (let [{:keys [state-atom view-ref events client conn lsp-atom]} ctx]
                     #js {;; Returns the full current state (workspace, diagnostics, symbols, etc.).
                          ;; Example: (.getState editor)
                          :getState (fn []
                                      (try
                                        (log/trace "Fetching editor state")
                                        ;; :lsp is sourced from the (per-workspace) lsp-atom
                                        ;; so getState reflects the shared connection state
                                        ;; regardless of which pane is queried.
                                        (clj->js (assoc @state-atom
                                                        :lsp (:lsp @lsp-atom)
                                                        :workspace {:documents (db/documents conn)
                                                                    :activeUri (:active-uri @state-atom)}
                                                        :logs (db/logs conn)
                                                        :diagnostics (db/diagnostics conn)
                                                        :symbols (db/symbols conn)
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
                                           (log/trace "Setting cursor to:" pos)
                                           (if-let [^js editor-view (.-current view-ref)]
                                             (let [^js editor-state (.-state editor-view)
                                                   ^js doc (.-doc editor-state)
                                                   offset (pos->offset doc pos true)]
                                               (if offset
                                                 (do
                                                   (.dispatch editor-view #js {:selection (EditorSelection.cursor offset)})
                                                   (emit-event events "selection-change" {:cursor pos
                                                                                          :selection nil
                                                                                          :uri (:active-uri @state-atom)}))
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
                                              (log/trace (str "Setting selection from: " from ", to: " to))
                                              (if-let [^js editor-view (.-current view-ref)]
                                                (let [^js editor-state (.-state editor-view)
                                                      ^js doc (.-doc editor-state)
                                                      from-offset (pos->offset doc from true)
                                                      to-offset (pos->offset doc to true)]
                                                  (if (and from-offset to-offset (<= from-offset to-offset))
                                                    (do
                                                      (.dispatch editor-view #js {:selection (EditorSelection.range from-offset to-offset)})
                                                      (emit-event events "selection-change" {:cursor to
                                                                                             :selection {:from from
                                                                                                         :to to
                                                                                                         :text (.sliceString doc from-offset to-offset)}
                                                                                             :uri (:active-uri @state-atom)}))
                                                    (do
                                                      (emit-event events "error" {:message "Invalid selection range"
                                                                                  :operation "setSelection"
                                                                                  :from from
                                                                                  :to to})
                                                      (log/warn (str "Cannot set selection: invalid offsets: from=" from ", to=" to)))))
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
                                          (if-let [uri (normalize-uri state-atom file-or-uri-js (:default-protocol @state-atom))]
                                            (try
                                              (if-not (db/document-id-by-uri conn uri)
                                                (log/info "Opening document:" uri)
                                                (log/info "Re-opening document:" uri))
                                              (let [text (js->clj text-js :keywordize-keys true)
                                                    lang (js->clj lang-js :keywordize-keys true)
                                                    make-active (if (nil? make-active-js) true (boolean make-active-js))
                                                    [_protocol path] (split-uri uri)
                                                    ext (get-ext-from-path path)
                                                    [id current-text current-lang] (db/doc-id-text-lang-by-uri conn uri)
                                                    effective-lang (or lang
                                                                       current-lang
                                                                       (when ext
                                                                         (get-lang-from-ext (:languages @state-atom) ext))
                                                                       "text")
                                                    effective-text (or text current-text "")
                                                    changed? (not= current-text effective-text)]
                                                (log/debug (str "Document path=" path ", ext=" ext ", effective-lang=" effective-lang))
                                                (if id
                                                  (do
                                                    (db/update-document-text-language-by-id! conn id effective-text effective-lang)
                                                    (when (and changed? (db/document-opened-by-uri? conn uri))
                                                      (let [version (db/inc-document-version-by-uri! conn uri)]
                                                        (p/notify-did-change! client effective-lang uri effective-text version)
                                                        (emit-event events "lsp-message" {:method "textDocument/didChange"
                                                                                          :lang effective-lang
                                                                                          :params {:textDocument {:uri uri
                                                                                                                  :version version}}}))))
                                                  (db/create-documents! conn [{:uri uri
                                                                          :text effective-text
                                                                          :language effective-lang
                                                                          :version 1
                                                                          :dirty (boolean changed?)
                                                                          :opened false}]))
                                                (when make-active
                                                  (activate-document uri state-atom view-ref events client conn))
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
                                             (when-let [uri (normalize-uri state-atom file-or-uri-js (:default-protocol @state-atom))]
                                               (log/info "Closing document:" uri)
                                               (let [[id lang opened?] (db/document-id-lang-opened-by-uri conn uri)]
                                                 (when opened?
                                                   (p/notify-did-close! client lang uri)
                                                   (emit-event events "lsp-message" {:method "textDocument/didClose"
                                                                                     :lang lang
                                                                                     :params {:textDocument {:uri uri}}})
                                                   ;; EXP-010 Phase 3: Clear cache on close
                                                   (swap! state-atom update :lsp-document-opened dissoc uri))
                                                 (db/delete-document-by-id! conn id)
                                                 (when (= uri (:active-uri @state-atom))
                                                   (if-let [next-uri (db/first-document-uri conn)]
                                                     (activate-document next-uri state-atom view-ref events client conn)
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
                          ;; Example: (.renameDocument editor "new-name.rho" "old-uri")
                          :renameDocument (fn [new-file-or-uri-js old-file-or-uri-js]
                                            (go
                                              (try
                                                (when-not new-file-or-uri-js
                                                  (throw
                                                   (js/Error.
                                                    (str "Invalid `new-file-or-uri-js` passed to `renameDocument`:" new-file-or-uri-js))))
                                                (let [default-protocol (:default-protocol @state-atom)
                                                      new-uri (normalize-uri state-atom new-file-or-uri-js default-protocol)
                                                      old-uri (normalize-uri state-atom old-file-or-uri-js default-protocol)]
                                                  (log/info (str "Renaming document from: " old-uri ", to: " new-uri))
                                                  (when (not= new-uri old-uri)
                                                    (let [new-ext (get-ext-from-path new-uri)
                                                          new-lang (when new-ext (get-lang-from-ext (:languages @state-atom) new-ext))
                                                          [id old-lang opened?] (db/document-id-lang-opened-by-uri conn old-uri)
                                                          lang-changed? (and new-lang (not= new-lang old-lang))]
                                                      (when (and old-uri id)
                                                        (when-not (db/document-id-by-uri conn new-uri)
                                                          (when opened?
                                                            (when-not lang-changed?
                                                              (p/notify-did-rename! client old-lang old-uri new-uri)
                                                              (emit-event events "lsp-message" {:method "workspace/didRenameFiles"
                                                                                                :lang old-lang
                                                                                                :params {:files [{:oldUri old-uri
                                                                                                                  :newUri new-uri}]}}))
                                                            ;; EXP-010 Phase 3: Update cache for renamed document
                                                            (swap! state-atom update :lsp-document-opened
                                                                   (fn [m] (-> m (dissoc old-uri) (assoc new-uri true)))))
                                                          (if lang-changed?
                                                            (db/update-document-uri-language-by-id! conn id new-uri new-lang)
                                                            (db/update-document-uri-by-id! conn id new-uri))
                                                          (when (= old-uri (:active-uri @state-atom))
                                                            (do (swap! state-atom assoc :active-uri new-uri) (db/update-active-uri! conn new-uri))
                                                            (when-let [^js editor-view (.-current view-ref)]
                                                              (if-let [res (<! (syntax/init-syntax editor-view state-atom conn))]
                                                                (when (= :error (first res))
                                                                  (throw (js/Error. (str "(.renameDocument this " new-file-or-uri-js " " old-file-or-uri-js ") failed") #js {:cause (second res)})))
                                                                (throw (js/Error. (str "(syntax/init-syntax editor-view state-atom) returned nothing in call to (.renameDocument editor " new-file-or-uri-js " " old-file-or-uri-js ") failed"))))))
                                                          (when-not (db/document-opened-by-uri? conn new-uri)
                                                            (ensure-lsp-document-opened new-lang new-uri state-atom events client conn))
                                                          (emit-event events "document-rename" {:old-uri old-uri
                                                                                                :new-uri new-uri}))))))
                                                [:ok nil]
                                                (catch js/Error error
                                                  (when (and @state-atom (:mounted? @state-atom))
                                                    (emit-event events "error" {:message (.-message error)
                                                                                :operation "renameDocument"
                                                                                :old-uri old-file-or-uri-js
                                                                                :new-uri new-file-or-uri-js}))
                                                  (let [error-with-cause (js/Error. (str "(.renameDocument editor " new-file-or-uri-js " " old-file-or-uri-js ") failed") #js {:cause error})]
                                                    (log-error-with-cause error-with-cause)
                                                    [:error error-with-cause])))))
                          ;; Saves the specified or active document (triggers `document-save`). Notifies LSP via `didSave`.
                          ;; Example: (.saveDocument editor)
                          ;; Example: (.saveDocument editor "specific-uri")
                          :saveDocument (fn [file-or-uri-js]
                                          (try
                                            (let [uri (normalize-uri state-atom file-or-uri-js (:default-protocol @state-atom))
                                                  id (db/document-id-by-uri conn uri)
                                                  [text lang dirty] (db/doc-text-lang-dirty-by-uri conn uri)]
                                              (log/info "Saving document:" uri)
                                              (when (and uri dirty)
                                                (when (get-in @lsp-atom [:lsp lang :connected?])
                                                  (p/notify-did-save! client lang uri text)
                                                  (emit-event events "lsp-message" {:method "textDocument/didSave"
                                                                                    :lang lang
                                                                                    :params {:textDocument {:uri uri}}}))
                                                (db/update-document-dirty-by-id! conn id false)
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
                                                (log/trace (str "Highlighting range from: " from ", to: " to))
                                                (if-let [^js editor-view (.-current view-ref)]
                                                  (let [^js editor-state (.-state editor-view)
                                                        ^js doc (.-doc editor-state)
                                                        from-offset (pos->offset doc from true)
                                                        to-offset (pos->offset doc to true)]
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
                                               (log/trace (str "Centering on range from: " from ", to: " to))
                                               (if-let [^js editor-view (.-current view-ref)]
                                                 (let [^js editor-state (.-state editor-view)
                                                       ^js doc (.-doc editor-state)
                                                       from-offset (pos->offset doc from true)
                                                       to-offset (pos->offset doc to true)]
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
                                                       (log/warn (str "Cannot center on range: invalid offsets: from=" from ", to=" to)))))
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
                                       (let [uri (normalize-uri state-atom file-or-uri-js (:default-protocol @state-atom))
                                             text (db/document-text-by-uri conn uri)]
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
                                             uri (normalize-uri state-atom file-or-uri-js (:default-protocol @state-atom))
                                             id (db/document-id-by-uri conn uri)
                                             [lang opened?] (db/document-language-opened-by-uri conn uri)
                                             current-text (db/document-text-by-uri conn uri)
                                             changed? (not= current-text text)]
                                         (log/info (str "Setting text for uri: " uri ", length: " (count text)))
                                         (when uri
                                           (when changed?
                                             (db/update-document-text-by-id! conn id text)
                                             (when (= uri (:active-uri @state-atom))
                                               (if-let [^js editor-view (.-current view-ref)]
                                                 (let [^js editor-state (.-state editor-view)
                                                       ^js doc (.-doc editor-state)
                                                       len (.-length doc)]
                                                   (.dispatch editor-view #js {:changes #js {:from 0
                                                                                             :to len
                                                                                             :insert text}
                                                                               :annotations (.of external-set-annotation true)}))
                                                 (log/warn "Cannot set editor text: view not ready")))
                                             (when (and lang opened? (get-in @lsp-atom [:lsp lang :connected?]))
                                               (let [version (db/inc-document-version-by-id! conn id)]
                                                 (p/notify-did-change! client lang uri text version)
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
                                           (let [uri (normalize-uri state-atom file-or-uri-js (:default-protocol @state-atom))
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
                                          (let [uri (normalize-uri state-atom file-or-uri-js (:default-protocol @state-atom))]
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
                                                (let [uri (normalize-uri state-atom file-or-uri-js (:default-protocol @state-atom))]
                                                  (if (db/document-id-by-uri conn uri)
                                                    (when (not= uri (:active-uri @state-atom))
                                                      (activate-document uri state-atom view-ref events client conn))
                                                    (do
                                                      (emit-event events "error" {:message "Document not found"
                                                                                  :operation "activateDocument"
                                                                                  :uri uri})
                                                      (log/warn "Document not found for activation:" uri))))
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
                                       (log/trace (str "Executing query=" query " with params=" params))
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
                                              (let [uri (normalize-uri state-atom file-or-uri-js (:default-protocol @state-atom))]
                                                (log/trace "Fetching diagnostics for uri:" uri)
                                                (clj->js (db/diagnostics-by-uri conn uri)))
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
                                          (let [uri (normalize-uri state-atom file-or-uri-js (:default-protocol @state-atom))]
                                            (log/trace "Fetching symbols for uri:" uri)
                                            (clj->js (db/symbols-by-uri conn uri)))
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
                                                 (log/error "Error in openSearchPanel:" (.-message error)))))
                          ;; Returns the current log level from taoensso.timbre.
                          ;; Example: (.getLogLevel editor)
                          :getLogLevel (fn []
                                         (try
                                           (log/trace "Fetching log level")
                                           (name (:min-level log/*config*))
                                           (catch js/Error error
                                             (emit-event events "error" {:message (.-message error)
                                                                         :operation "getLogLevel"})
                                             (log/error "Error in getLogLevel:" (.-message error))
                                             "info")))
                          ;; Sets the log level for taoensso.timbre (accepts 'trace', 'debug', 'info', 'warn', 'error', 'fatal', 'report').
                          ;; Example: (.setLogLevel editor "debug")
                          :setLogLevel (fn [level-js]
                                         (try
                                           (let [level-kw (keyword level-js)]
                                             (log/trace "Setting log level to:" level-kw)
                                             (log/set-min-level! level-kw))
                                           (catch js/Error error
                                             (emit-event events "error" {:message (.-message error)
                                                                         :operation "setLogLevel"
                                                                         :level level-js})
                                             (log/error "Error in setLogLevel:" (.-message error)))))
                          ;; Shuts down LSP connections for all languages or a specific one.
                          ;; Example: (.shutdownLsp editor)
                          ;; Example: (.shutdownLsp editor "text")
                          :shutdownLsp (fn [lang]
                                         (try
                                           (if lang
                                             (do
                                               (log/info "Shutting down LSP connection for lang:" lang)
                                               (p/request-shutdown! client lang))
                                             (do
                                               (log/info "Shutting down all LSP connections")
                                               (p/shutdown-all! client)))
                                           (catch js/Error error
                                             (emit-event events "error" {:message (.-message error)
                                                                         :operation "shutdownAllLsp"})
                                             (log/error "Error in shutdownAllLsp:" (.-message error)))))}))
