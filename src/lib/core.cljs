(ns lib.core
  (:require
   ["@codemirror/autocomplete" :refer [closeBrackets]]
   ["@codemirror/commands" :refer [defaultKeymap]]
   ["@codemirror/language" :refer [bracketMatching]]
   ["@codemirror/state" :refer [Annotation EditorSelection EditorState StateField]]
   ["@codemirror/view" :refer [EditorView keymap lineNumbers]]
   ["react" :as react]
   ["rxjs" :as rxjs :refer [ReplaySubject]]
   [clojure.core.async :refer [go <! chan put!]]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [datascript.core :as d]
   [reagent.core :as r]
   [lib.db :as db :refer [conn]]
   [lib.editor.diagnostics :as diagnostics :refer [set-diagnostic-effect]]
   [lib.editor.highlight :as highlight]
   [lib.editor.syntax :as syntax]
   [lib.lsp.client :as lsp]
   [lib.utils :as u :refer [split-uri]]
   [taoensso.timbre :as log]))

;; Hardcoded default languages for the library; uses string keys.
(defonce ^:const default-languages {"text" {:extensions [".txt"]
                                            :fallback-highlighter "none"}})

;; Language configuration specs
(s/def ::grammar-wasm string?)
(s/def ::highlight-query-path string?)
(s/def ::indents-query-path string?)
(s/def ::lsp-url string?)
(s/def ::extensions (s/coll-of string? :min-count 1))
(s/def ::file-icon string?)
(s/def ::fallback-highlighter string?)
(s/def ::indent-size pos-int?)

(s/def ::config (s/keys :req-un [::extensions]
                        :opt-un [::grammar-wasm
                                 ::highlight-query-path
                                 ::indents-query-path
                                 ::lsp-url
                                 ::file-icon
                                 ::fallback-highlighter
                                 ::indent-size]))

;; Annotation to mark transactions that update diagnostics in the StateField.
(def diagnostic-annotation (.define Annotation))

;; StateField to hold the current list of LSP diagnostics.
(def diagnostic-field
  (.define StateField
           #js {:create (fn [_] #js []) ;; Initial empty array of diagnostics.
                :update (fn [value ^js tr]
                          ;; Update with new diagnostics if the transaction has the annotation, else keep current.
                          (if-let [new-diags (.annotation tr diagnostic-annotation)]
                            new-diags
                            value))}))

(defn- kebab-keyword
  "Converts a camelCase keyword to kebab-case keyword.
  e.g., :grammarWasm -> :grammar-wasm"
  [k]
  (let [n (name k)
        kebab (str/replace n #"([a-z])([A-Z])" (fn [[_ a b]] (str a "-" (str/lower-case b))))]
    (keyword kebab)))

(defn- convert-config-keys
  "Recursively converts all camelCase keys in a config map to kebab-case keywords."
  [config]
  (into {} (map (fn [[k v]] [(kebab-keyword k) (if (map? v) (convert-config-keys v) v)]) config)))

(defn- normalize-languages
  "Ensures all keys in the languages map are strings, converting keywords if necessary.
  Also normalizes inner config keys from camelCase to kebab-case.
  Validates each config with spec and throws on invalid."
  [langs]
  (reduce-kv
   (fn [m k v]
     (let [key-str (if (keyword? k) (name k) (str k))
           config (convert-config-keys v)]
       (when-not (s/valid? ::config config)
         (throw (ex-info "Invalid language config" {:lang key-str :explain (s/explain-data ::config config)})))
       (assoc m key-str config)))
   {}
   langs))

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
  (let [languages (normalize-languages (merge default-languages (:languages props)))
        extra-extensions (:extraExtensions props #js [])]
    (when-not (every? string? (keys languages))
      (log/warn "Non-string keys found in languages map:" (keys languages)))
    {:cursor {:line 1 :column 1}
     :selection nil
     :lsp {}
     :languages languages
     :extra-extensions extra-extensions
     :debounce-timer nil}))

(defn- update-editor-state
  "Updates the internal state-atom with cursor and selection info from CodeMirror state.
  Emits an event for external listeners."
  [^js cm-state state-atom events]
  (let [main-sel (.-main (.-selection cm-state))
        anchor (.-anchor main-sel)
        head (.-head main-sel)
        ^js doc (.-doc cm-state)
        cursor-pos (u/offset-to-pos doc head true)
        sel (when (not= anchor head)
              (let [from (min anchor head)
                    to (max anchor head)
                    from-pos (u/offset-to-pos doc from true)
                    to-pos (u/offset-to-pos doc to true)
                    text (.sliceString doc from to)]
                {:from from-pos :to to-pos :text text}))
        uri (db/active-uri)]
    (swap! state-atom assoc :cursor cursor-pos :selection sel)
    (.next events (clj->js {:type "selection-change" :data {:cursor cursor-pos :selection sel :uri uri}}))))

(defn- get-extensions
  "Returns the array of CodeMirror extensions, including dynamic syntax compartment,
  static diagnostic compartment. Appends extra-extensions from state. Removes highlight-plugin from defaults."
  [state-atom events]
  (let [debounced-lsp (u/debounce (fn []
                                    (let [[uri text lang] (db/active-uri-text-lang)]
                                      (when text
                                        (let [version (db/inc-document-version-by-uri! uri)]
                                          (lsp/send lang
                                                    {:method "textDocument/didChange"
                                                     :params {:textDocument {:uri uri :version version}
                                                              :contentChanges [{:text text}]}}
                                                    state-atom)))))
                                  200)
        update-ext (.. EditorView -updateListener
                       (of (fn [^js u]
                             (when (or (.-docChanged u) (.-selectionSet u))
                               (update-editor-state (.-state u) state-atom events))
                             (when (.-docChanged u)
                               (when-let [uri (db/active-uri)]
                                 (let [new-text (.toString (.-doc (.-state u)))]
                                   (db/update-document-text-by-uri! uri new-text)
                                   (.next events (clj->js {:type "content-change" :data {:content new-text
                                                                                         :uri uri}}))
                                   (when (db/document-opened-by-uri? uri)
                                     (debounced-lsp))))))))
        default-exts [(lineNumbers)
                      (bracketMatching)
                      (closeBrackets)
                      (.of keymap defaultKeymap)
                      (.of syntax/syntax-compartment #js [])
                      (.theme EditorView #js {} #js {:dark true})
                      diagnostic-field
                      update-ext]
        extra-extensions (:extra-extensions @state-atom #js [])]
    (into-array
     (concat default-exts diagnostics/extensions extra-extensions))))

(defn- ensure-lsp-document-opened
  "Ensures the document is opened in LSP if configured, connecting if necessary.
  Sends didOpen and requests symbols on success."
  [lang uri state-atom events]
  (let [[text version] (db/doc-text-version-by-uri uri)]
    (when-let [lsp-url (get-in @state-atom [:languages lang :lsp-url])]
      (let [connected? (get-in @state-atom [:lsp lang :connected?])
            initialized? (get-in @state-atom [:lsp lang :initialized?])]
        (go
          (when-not (and connected? initialized?)
            (let [conn-ch (or (get-in @state-atom [:lsp lang :connect-chan])
                              (let [new-ch (chan)]
                                (swap! state-atom assoc-in [:lsp lang :connect-chan] new-ch)
                                (lsp/connect lang {:url lsp-url} state-atom events new-ch)
                                new-ch))
                  res (<! conn-ch)]
              ;; Replay the message
              (put! conn-ch res)
              (when (= res :error)
                (log/error "Failed to connect and initialize LSP for lang" lang))))
          (when (and (get-in @state-atom [:lsp lang :connected?])
                     (get-in @state-atom [:lsp lang :initialized?])
                     (not (db/document-opened-by-uri? uri)))
            (lsp/send lang
                      {:method "textDocument/didOpen"
                       :params {:textDocument {:uri uri
                                               :languageId lang
                                               :version version
                                               :text text}}}
                      state-atom)
            (lsp/request-symbols lang uri state-atom)
            (db/document-opened-by-uri! uri)))))))

(defn- activate-document
  "Activates the document with the given URI, loading content and re-initializing syntax if language changes.
  Also handles LSP open if not already opened."
  [uri state-atom view-ref events]
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
        (syntax/init-syntax (.-current view-ref) state-atom))
      (when (get-in @state-atom [:languages new-lang :lsp-url])
        (ensure-lsp-document-opened new-lang uri state-atom events))
      (.next events (clj->js {:type "document-open" :data {:uri uri
                                                           :content text
                                                           :language new-lang
                                                           :activated true}})))))

;; Inner React functional component, handling CodeMirror integration and state management.
(let [inner (fn [js-props forwarded-ref]
              (let [props (js->clj js-props :keywordize-keys true)
                    state-ref (react/useRef nil)
                    _ (when (nil? (.-current state-ref))
                        (set! (.-current state-ref) (r/atom (default-state props))))
                    state-atom (.-current state-ref)
                    view-ref (react/useRef nil)
                    events (react/useMemo (fn [] (ReplaySubject. 1)) #js [])
                    on-content-change (:onContentChange props)
                    container-ref (react/useRef nil)]
                (react/useImperativeHandle
                 forwarded-ref
                 (fn []
                   #js {;; Returns the full current state (workspace, diagnostics, symbols, etc.).
                        ;; Example: (.getState editor)
                        :getState (fn []
                                    (clj->js (assoc @state-atom
                                                    :workspace {:documents (db/documents)
                                                                :activeUri (db/active-uri)}
                                                    :logs (db/logs)
                                                    :diagnostics (db/diagnostics)
                                                    :symbols (db/symbols))))
                        ;; Returns RxJS observable for subscribing to events.
                        ;; Example: (.subscribe (.getEvents editor) (fn [evt] (js/console.log (.-type evt) (.-data evt))))
                        :getEvents (fn [] events)
                        ;; Returns current cursor position (1-based) for active document.
                        ;; Example: (.getCursor editor)
                        :getCursor (fn [] (clj->js (:cursor @state-atom)))
                        ;; Sets cursor position for active document (triggers `selection-change` event).
                        ;; Example: (.setCursor editor #js {:line 1 :column 3})
                        :setCursor (fn [pos-js]
                                     (let [pos (js->clj pos-js :keywordize-keys true)]
                                       (if (.-current view-ref)
                                         (let [^js doc (.-doc ^js (.-state ^js (.-current view-ref)))
                                               offset (u/pos-to-offset doc pos true)]
                                           (when offset
                                             (.dispatch ^js (.-current view-ref) #js {:selection (EditorSelection.cursor offset)})))
                                         (log/warn "Skipping set cursor: view-ref not ready"))))
                        ;; Returns current selection range and text for active document, or `null` if no selection.
                        ;; Example: (.getSelection editor)
                        :getSelection (fn [] (clj->js (:selection @state-atom)))
                        ;; Sets selection range for active document (triggers `selection-change` event).
                        ;; Example: (.setSelection editor #js {:line 1 :column 1} #js {:line 1 :column 6})
                        :setSelection (fn [from-js to-js]
                                        (let [from (js->clj from-js :keywordize-keys true)
                                              to (js->clj to-js :keywordize-keys true)]
                                          (if (.-current view-ref)
                                            (let [^js doc (.-doc ^js (.-state ^js (.-current view-ref)))
                                                  from-offset (u/pos-to-offset doc from true)
                                                  to-offset (u/pos-to-offset doc to true)]
                                              (when (and from-offset to-offset)
                                                (.dispatch ^js (.-current view-ref) #js {:selection (EditorSelection.range from-offset to-offset)})))
                                            (log/warn "Skipping set selection: view-ref not ready"))))
                        ;; Opens or activates a document with URI, optional content and language (triggers `document-open`).
                        ;; Reuses if exists, updates if provided. Notifies LSP if connected.
                        ;; If fourth param make-active is false, opens without activating.
                        ;; Example: (.openDocument editor "demo.rho" "new x in { x!(\"Hello\") | Nil }" "rholang")
                        ;;          (.openDocument editor "demo.rho") ; activates existing
                        ;;          (.openDocument editor "demo.rho" nil nil false) ; opens without activating
                        :openDocument (fn [uri-js text-js lang-js & [make-active-js]]
                                        (let [uri-str (js->clj uri-js :keywordize-keys true)
                                              text (js->clj text-js :keywordize-keys true)
                                              lang (js->clj lang-js :keywordize-keys true)
                                              make-active (if (nil? make-active-js) true (boolean make-active-js))
                                              [protocol path] (split-uri uri-str)
                                              ext (get-ext-from-path path)
                                              effective-uri (str (or protocol "inmemory://") path)
                                              [id current-text current-lang] (db/doc-id-text-lang-by-uri effective-uri)
                                              effective-lang (or lang
                                                                 current-lang
                                                                 (when ext
                                                                   (get-lang-from-ext (:languages @state-atom) ext))
                                                                 "text")
                                              effective-text (or text current-text "")]
                                          (if id
                                            (db/update-document-text-language-by-id! id effective-text effective-lang)
                                            (db/create-documents! [{:uri effective-uri
                                                                    :text effective-text
                                                                    :language effective-lang
                                                                    :version 1
                                                                    :dirty false
                                                                    :opened false}]))
                                          (when (and text (db/document-opened-by-uri? effective-uri))
                                            (let [version (db/inc-document-version-by-uri! effective-uri)]
                                              (lsp/send effective-lang
                                                        {:method "textDocument/didChange"
                                                         :params {:textDocument {:uri effective-uri
                                                                                 :version version}
                                                                  :contentChanges [{:text effective-text}]}}
                                                        state-atom)))
                                          (when make-active
                                            (activate-document effective-uri state-atom view-ref events))))
                        ;; Closes the specified or active document (triggers `document-close`). Notifies LSP if open.
                        ;; Example: (.closeDocument editor)
                        ;;          (.closeDocument editor "specific-uri")
                        :closeDocument (fn [uri-js]
                                         (let [uri (or uri-js (db/active-uri))
                                               [id lang opened?] (db/document-id-lang-opened-by-uri uri)]
                                           (when uri
                                             (when opened?
                                               (lsp/send lang {:method "textDocument/didClose"
                                                               :params {:textDocument {:uri uri}}} state-atom))
                                             (db/delete-document-by-id! id)
                                             (when (db/active-uri? uri)
                                               (let [next-uri (db/first-document-uri)]
                                                 (activate-document next-uri state-atom view-ref events)))
                                             (.next events (clj->js {:type "document-close" :data {:uri uri}})))))
                        ;; Renames the specified or active document (updates URI, triggers `document-rename`). Notifies LSP.
                        ;; Example: (.renameDocument editor "new-name.rho")
                        ;;          (.renameDocument editor "new-name.rho" "old-uri")
                        :renameDocument (fn [new-uri-js old-uri-js]
                                          (let [new-uri-str (js->clj new-uri-js :keywordize-keys true)
                                                old-uri (or old-uri-js (db/active-uri))
                                                [old-protocol _old-path] (split-uri old-uri)
                                                effective-new-uri (str (or old-protocol "inmemory://") new-uri-str)
                                                new-ext (get-ext-from-path new-uri-str)
                                                potential-new-lang (when new-ext (get-lang-from-ext (:languages @state-atom) new-ext))
                                                [id old-lang opened?] (db/document-id-lang-opened-by-uri old-uri)
                                                lang-changed? (and potential-new-lang (not= potential-new-lang old-lang))]
                                            (when (and old-uri id)
                                              (when-not (db/document-id-by-uri effective-new-uri)
                                                (when opened?
                                                  (lsp/send old-lang {:method "textDocument/didClose"
                                                                      :params {:textDocument {:uri old-uri}}} state-atom)
                                                  (when-not lang-changed?
                                                    (lsp/send old-lang
                                                              {:method "workspace/didRenameFiles"
                                                               :params {:files [{:oldUri old-uri
                                                                                 :newUri effective-new-uri}]}}
                                                              state-atom)))
                                                (if lang-changed?
                                                  (db/update-document-uri-language-by-id!
                                                   id effective-new-uri potential-new-lang)
                                                  (db/update-document-uri-by-id! id effective-new-uri))
                                                (when (= old-uri (db/active-uri))
                                                  (db/update-active-uri! effective-new-uri)
                                                  (syntax/init-syntax (.-current view-ref) state-atom))
                                                (when-not (db/document-opened-by-uri? effective-new-uri)
                                                  (ensure-lsp-document-opened potential-new-lang effective-new-uri state-atom events))
                                                (.next events (clj->js {:type "document-rename"
                                                                        :data {:old-uri old-uri
                                                                               :new-uri effective-new-uri}}))))))
                        ;; Saves the specified or active document (triggers `document-save`). Notifies LSP via `didSave`.
                        ;; Example: (.saveDocument editor)
                        ;;          (.saveDocument editor "specific-uri")
                        :saveDocument (fn [uri-js]
                                        (let [uri (or uri-js (db/active-uri))
                                              id (db/document-id-by-uri uri)
                                              [text lang dirty] (db/doc-text-lang-dirty-by-uri uri)]
                                          (when (and uri dirty)
                                            (when (get-in @state-atom [:lsp lang :connected?])
                                              (lsp/send lang
                                                        {:method "textDocument/didSave"
                                                         :params {:textDocument {:uri uri}
                                                                  :text text}}
                                                        state-atom))
                                            (db/update-document-dirty-by-id! id false)
                                            (.next events (clj->js {:type "document-save"
                                                                    :data {:uri uri
                                                                           :content text}})))))
                        ;; Returns `true` if editor is initialized and ready for methods.
                        ;; Example: (.isReady editor)
                        :isReady (fn [] (boolean (.-current view-ref)))
                        ;; Highlights a range in active document (triggers `highlight-change` with range).
                        ;; Example: (.highlightRange editor #js {:line 1 :column 1} #js {:line 1 :column 6})
                        :highlightRange (fn [from-js to-js]
                                          (let [from (js->clj from-js :keywordize-keys true)
                                                to (js->clj to-js :keywordize-keys true)]
                                            (if (.-current view-ref)
                                              (let [^js doc (.-doc ^js (.-state ^js (.-current view-ref)))
                                                    from-offset (u/pos-to-offset doc from true)
                                                    to-offset (u/pos-to-offset doc to true)]
                                                (if (and from-offset to-offset (<= from-offset to-offset))
                                                  (do
                                                    (.dispatch
                                                     ^js (.-current view-ref)
                                                     #js {:annotations (.of highlight/highlight-annotation
                                                                            (clj->js {:from from :to to}))})
                                                    (.next events (clj->js {:type "highlight-change"
                                                                            :data {:from from
                                                                                   :to to}})))
                                                  (log/warn "Cannot highlight range: invalid offsets")))
                                              (log/warn "Cannot highlight range: view-ref is nil"))))
                        ;; Clears highlight in active document (triggers `highlight-change` with `null`).
                        ;; Example: (.clearHighlight editor)
                        :clearHighlight (fn []
                                          (if (.-current view-ref)
                                            (do
                                              (.dispatch
                                               ^js (.-current view-ref)
                                               #js {:annotations (.of highlight/highlight-annotation nil)})
                                              (.next events (clj->js {:type "highlight-change" :data nil})))
                                            (log/warn "Skipping clear highlight: view-ref not ready")))
                        ;; Scrolls to center on a range in active document.
                        ;; Example: (.centerOnRange editor #js {:line 1 :column 1} #js {:line 1 :column 6})
                        :centerOnRange (fn [from-js to-js]
                                         (let [from (js->clj from-js :keywordize-keys true)
                                               to (js->clj to-js :keywordize-keys true)]
                                           (if (.-current view-ref)
                                             (let [^js doc (.-doc ^js (.-state ^js (.-current view-ref)))
                                                   from-offset (u/pos-to-offset doc from true)
                                                   to-offset (u/pos-to-offset doc to true)]
                                               (if (and from-offset to-offset)
                                                 (.dispatch
                                                  ^js (.-current view-ref)
                                                  #js {:effects (EditorView.scrollIntoView
                                                                 (.range EditorSelection from-offset to-offset)
                                                                 #js {:y "center"})})
                                                 (log/warn "Cannot center on range: invalid offsets")))
                                             (log/warn "Cannot center on range: view-ref not ready"))))
                        ;; Returns text for specified or active document, or `null` if not found.
                        ;; Example: (.getText editor)
                        ;;          (.getText editor "specific-uri")
                        :getText (fn [uri-js]
                                   (let [uri (or uri-js (db/active-uri))
                                         text (db/document-text-by-uri uri)]
                                     (or text nil)))
                        ;; Replaces entire text for specified or active document (triggers `content-change`).
                        ;; Example: (.setText editor "new text")
                        ;;          (.setText editor "new text" "specific-uri")
                        :setText (fn [text-js uri-js]
                                   (let [text (js->clj text-js :keywordize-keys true)
                                         uri (or uri-js (db/active-uri))
                                         id (db/document-id-by-uri uri)
                                         [lang opened?] (db/document-language-opened-by-uri uri)]
                                     (when uri
                                       (db/update-document-text-by-id! id text)
                                       (when (= uri (db/active-uri))
                                         (if (.-current view-ref)
                                           (let [^js v (.-current view-ref)
                                                 ^js doc (.-doc ^js (.-state ^js v))
                                                 len (.-length doc)]
                                             (.dispatch v #js {:changes #js {:from 0 :to len :insert text}}))
                                           (log/warn "Cannot set editor text: view not ready")))
                                       (when (and lang opened? (get-in @state-atom [:lsp lang :connected?]))
                                         (let [version (db/inc-document-version-by-id! id)]
                                           (lsp/send lang
                                                     {:method "textDocument/didChange"
                                                      :params {:textDocument {:uri uri
                                                                              :version version}
                                                               :contentChanges [{:text text}]}}
                                                     state-atom)))
                                       (.next events (clj->js {:type "content-change" :data {:content text :uri uri}})))))
                        ;; Returns file path (e.g., `"/demo.rho"`) for specified or active, or null if none.
                        ;; Example: (.getFilePath editor)
                        ;;          (.getFilePath editor "specific-uri")
                        :getFilePath (fn [uri-js]
                                       (let [uri (or uri-js (db/active-uri))
                                             [_ path] (split-uri uri)]
                                         (or path nil)))
                        ;; Returns full URI (e.g., `"inmemory:///demo.rho"`) for specified or active, or `null` if none.
                        ;; Example: (.getFileUri editor)
                        ;;          (.getFileUri editor "specific-uri")
                        :getFileUri (fn [uri-js]
                                      (let [uri (or uri-js (db/active-uri))]
                                        (or uri nil)))
                        ;; Activates the document with the given URI, loading content to view, opens in LSP if not.
                        ;; Example: (.activateDocument editor "demo.rho")
                        :activateDocument (fn [uri-js]
                                            (let [uri (js->clj uri-js :keywordize-keys true)]
                                              (if (db/document-id-by-uri uri)
                                                (when (not= uri (db/active-uri))
                                                  (activate-document uri state-atom view-ref events))
                                                (log/warn "Document not found for activation" uri))))
                        ;; Queries the internal DataScript database with the given query and optional params.
                        ;; Returns the result as JS array.
                        ;; Example: (.query editor '[:find ?uri :where [?e :document/uri ?uri]])
                        :query (fn [query-js params-js]
                                 (let [query (js->clj query-js :keywordize-keys true)
                                       params (if params-js (js->clj params-js) [])]
                                   (clj->js (apply d/q query @conn params))))
                        ;; Returns the DataScript connection object for direct access (advanced use).
                        ;; Example: (.getDb editor)
                        :getDb (fn [] conn)})
                 #js [@state-atom (.-current view-ref)])
                (react/useEffect
                 (fn []
                   (when-let [prop-lang (:language props)]
                     (when (not= prop-lang (db/active-lang))
                       (let [uri (db/active-uri)
                             id (db/document-id-by-uri uri)]
                         (db/update-document-language-by-id! id prop-lang))
                       (syntax/init-syntax (.-current view-ref) state-atom)))
                   js/undefined)
                 #js [(:language props)])
                (react/useEffect
                 (fn []
                   (let [prop-text (:content props)
                         uri (db/active-uri)]
                     (when (and (not= prop-text (db/active-text))
                                (not (db/document-dirty-by-uri? uri)))
                       (db/update-document-text-by-uri! uri prop-text)))
                   js/undefined)
                 #js [(:content props)])
                (react/useEffect
                 (fn []
                   (when (.-current view-ref)
                     (let [current-doc (.toString (.-doc ^js (.-state ^js (.-current view-ref))))
                           active-text (db/active-text)]
                       (when (not= active-text current-doc)
                         (.dispatch
                          ^js (.-current view-ref)
                          #js {:changes #js {:from 0
                                             :to (.length current-doc)
                                             :insert active-text}})
                         (when on-content-change
                           (on-content-change active-text)))))
                   js/undefined)
                 #js [(db/active-text)])
                (react/useEffect
                 (fn []
                   ;; Internal subscription to events for handling diagnostics updates by dispatching
                   ;; a transaction to update the diagnostic StateField.
                   (let [sub (.subscribe events
                                         (fn [evt-js]
                                           (let [evt (js->clj evt-js :keywordize-keys true)
                                                 type (:type evt)]
                                             (when (and (= type "diagnostics") (.-current view-ref))
                                               (let [diags (:data evt)]
                                                 (.dispatch
                                                  ^js (.-current view-ref)
                                                  #js {:changes #js {:from 0 :to 0}
                                                       :effects #js [(.of set-diagnostic-effect (clj->js diags))]}))))))]
                     (fn [] (.unsubscribe sub))))
                 #js [(.-current view-ref)])
                (react/useEffect
                 (fn []
                   (log/info "Editor: Initializing EditorView")
                   (let [container (.-current container-ref)
                         exts (get-extensions state-atom events)
                         editor-state (EditorState.create #js {:doc "" :extensions exts})
                         v (EditorView. #js {:state editor-state :parent container})]
                     (set! (.-current view-ref) v)
                     (syntax/init-syntax v state-atom)
                     (js/setTimeout (fn [] (.next events (clj->js {:type "ready"}))) 0)
                     (update-editor-state editor-state state-atom events)
                     (fn []
                       (log/info "Editor: Destroying EditorView")
                       (when-let [v (.-current view-ref)]
                         (.destroy v))
                       (set! (.-current view-ref) nil))))
                 #js [(db/active-lang) (:extra-extensions @state-atom) container-ref])
                (react/createElement "div" #js {:ref container-ref :className "code-editor flex-grow-1"})))]
  (def editor-comp (react/forwardRef inner))
  (def ^:export Editor editor-comp))
