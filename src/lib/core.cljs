(ns lib.core
  (:require
   ["@codemirror/autocomplete" :refer [closeBrackets]]
   ["@codemirror/commands" :refer [defaultKeymap]]
   ["@codemirror/language" :refer [bracketMatching indentOnInput]]
   ["@codemirror/state" :refer [Annotation EditorSelection EditorState StateField]]
   ["@codemirror/view" :refer [EditorView keymap lineNumbers]]
   ["react" :as react]
   ["rxjs" :as rxjs :refer [ReplaySubject]]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [datascript.core :as d]
   [reagent.core :as r]
   [lib.db :refer [schema]]
   [lib.editor.diagnostics :as diagnostics :refer [set-diagnostic-effect]]
   [lib.editor.highlight :as highlight]
   [lib.editor.syntax :as syntax]
   [lib.lsp.client :as lsp]
   [lib.utils :as u :refer [split-uri]]
   [taoensso.timbre :as log]))

;; Hardcoded default languages for the library; uses string keys.
(defonce ^:const default-languages {"text" {:extensions [".txt"]
                                            :fallback-highlighter "none"}})

;; Language configuration specs (moved from app.languages)
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

(defn- default-state
  "Computes the initial editor state from converted CLJS props.
  Ensures language keys are strings and falls back to 'text' if no language is provided."
  [props]
  (let [languages (normalize-languages (merge default-languages (:languages props)))
        extra-extensions (:extraExtensions props #js [])]  ;; New: store extra extensions from props (JS array)
    (when-not (every? string? (keys languages))
      (log/warn "Non-string keys found in languages map:" (keys languages)))
    {:workspace {:documents {} :active-uri nil}
     :cursor {:line 1 :column 1}
     :selection nil
     :lsp {}
     :logs []
     :languages languages
     :extra-extensions extra-extensions
     :debounce-timer nil  ;; Timer for debouncing LSP didChange notifications.
     :conn (d/create-conn schema)}))

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
                {:from from-pos :to to-pos :text text}))]
    (swap! state-atom assoc :cursor cursor-pos :selection sel)
    (.next events (clj->js {:type "selection-change" :data {:cursor cursor-pos :selection sel :uri (get-in @state-atom [:workspace :active-uri])}}))))

(defn- get-extensions
  "Returns the array of CodeMirror extensions, including dynamic syntax compartment,
  static diagnostic compartment. Appends extra-extensions from state. Removes highlight-plugin from defaults."
  [state-atom events]
  (let [debounced-lsp (u/debounce (fn []
                                    (let [uri (get-in @state-atom [:workspace :active-uri])
                                          content (get-in @state-atom [:workspace :documents uri :content])
                                          lang (get-in @state-atom [:workspace :documents uri :language])]
                                      (when uri
                                        (swap! state-atom update-in [:workspace :documents uri :version] inc)
                                        (lsp/send lang {:method "textDocument/didChange"
                                                        :params {:textDocument {:uri uri :version (get-in @state-atom [:workspace :documents uri :version])}
                                                                 :contentChanges [{:text content}]}} state-atom)))) 300)
        update-ext (.. EditorView -updateListener
                       (of (fn [^js u]
                             (when (or (.-docChanged u) (.-selectionSet u))
                               (update-editor-state (.-state u) state-atom events))
                             (when (.-docChanged u)
                               (let [new-content (.toString (.-doc (.-state u)))
                                     uri (get-in @state-atom [:workspace :active-uri])]
                                 (when uri
                                   (swap! state-atom assoc-in [:workspace :documents uri :content] new-content)
                                   (swap! state-atom assoc-in [:workspace :documents uri :dirty?] true)
                                   (.next events (clj->js {:type "content-change" :data {:content new-content :uri uri}}))
                                   (when (get-in @state-atom [:workspace :documents uri :opened?])
                                     (debounced-lsp))))))))
        default-exts [(lineNumbers)
                      (indentOnInput)
                      (bracketMatching)
                      (closeBrackets)
                      (.of keymap defaultKeymap)
                      (.of syntax/syntax-compartment #js [])
                      (.theme EditorView #js {} #js {:dark true})
                      diagnostic-field
                      update-ext]
        extra (:extra-extensions @state-atom #js [])]
    (into-array (concat default-exts diagnostics/extensions extra))))

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
                                                    :diagnostics (d/q '[:find (pull ?e [*])
                                                                        :where [?e :type :diagnostic]]
                                                                      @(:conn @state-atom))
                                                    :symbols (d/q '[:find (pull ?e [*])
                                                                    :where [?e :type :symbol]]
                                                                  @(:conn @state-atom)))))
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
                        ;; If fourth param make-active is false, opens without activating or loading to view.
                        ;; Example: (.openDocument editor "demo.rho" "new x in { x!(\"Hello\") | Nil }" "rholang")
                        ;;          (.openDocument editor "demo.rho") ; activates existing
                        ;;          (.openDocument editor "demo.rho" nil nil false) ; opens without activating
                        :openDocument (fn [uri-js content-js lang-js & [make-active-js]]
                                        (let [uri-str (js->clj uri-js :keywordize-keys true)
                                              content (js->clj content-js :keywordize-keys true)
                                              lang (js->clj lang-js :keywordize-keys true)
                                              make-active (if (nil? make-active-js) true (boolean make-active-js))
                                              [protocol path] (split-uri uri-str)
                                              effective-uri (str (or protocol "inmemory://") path)
                                              exists? (contains? (get-in @state-atom [:workspace :documents]) effective-uri)
                                              current-doc (if exists? (get-in @state-atom [:workspace :documents effective-uri]) {})
                                              effective-lang (or lang (:language current-doc) "text")
                                              effective-content (or content (:content current-doc) "")]
                                          (when make-active
                                            (swap! state-atom assoc-in [:workspace :active-uri] effective-uri))
                                          (if exists?
                                            (do
                                              (when content
                                                (swap! state-atom assoc-in [:workspace :documents effective-uri :content] effective-content)
                                                (swap! state-atom assoc-in [:workspace :documents effective-uri :dirty?] true)
                                                (when make-active
                                                  (when-let [view (.-current view-ref)]
                                                    (let [current-cm-doc (.-doc (.-state view))
                                                          current-length (.-length current-cm-doc)]
                                                      (.dispatch view #js {:changes #js {:from 0 :to current-length :insert effective-content}})))
                                                  (when (get-in @state-atom [:workspace :documents effective-uri :opened?])
                                                    (swap! state-atom update-in [:workspace :documents effective-uri :version] inc)
                                                    (lsp/send effective-lang {:method "textDocument/didChange"
                                                                              :params {:textDocument {:uri effective-uri :version (get-in @state-atom [:workspace :documents effective-uri :version])}
                                                                                       :contentChanges [{:text effective-content}]}} state-atom))))
                                              (when lang
                                                (swap! state-atom assoc-in [:workspace :documents effective-uri :language] effective-lang)
                                                (when make-active
                                                  (syntax/init-syntax (.-current view-ref) state-atom)))
                                              (when-let [lsp-url (get-in @state-atom [:languages effective-lang :lsp-url])]
                                                (when-not (get-in @state-atom [:lsp effective-lang :connection])
                                                  (lsp/connect effective-lang {:url lsp-url} state-atom events))
                                                (when (and (get-in @state-atom [:lsp effective-lang :connection]) (get-in @state-atom [:lsp effective-lang :initialized?]) (not (:opened? current-doc)))
                                                  (lsp/send effective-lang {:method "textDocument/didOpen"
                                                                            :params {:textDocument {:uri effective-uri :languageId effective-lang :version (get-in @state-atom [:workspace :documents effective-uri :version]) :text effective-content}}} state-atom)
                                                  (lsp/request-symbols effective-lang effective-uri state-atom)
                                                  (swap! state-atom assoc-in [:workspace :documents effective-uri :opened?] true))))
                                            (do
                                              (swap! state-atom assoc-in [:workspace :documents effective-uri] {:content effective-content :language effective-lang :version 1 :dirty? false :opened? false})
                                              (when make-active
                                                (when-let [view (.-current view-ref)]
                                                  (let [current-cm-doc (.-doc (.-state view))
                                                        current-length (.-length current-cm-doc)]
                                                    (.dispatch view #js {:changes #js {:from 0 :to current-length :insert effective-content}})))
                                                (syntax/init-syntax (.-current view-ref) state-atom))
                                              (when-let [lsp-url (get-in @state-atom [:languages effective-lang :lsp-url])]
                                                (when-not (get-in @state-atom [:lsp effective-lang :connection])
                                                  (lsp/connect effective-lang {:url lsp-url} state-atom events))
                                                (when (and (get-in @state-atom [:lsp effective-lang :connection]) (get-in @state-atom [:lsp effective-lang :initialized?]))
                                                  (lsp/send effective-lang {:method "textDocument/didOpen"
                                                                            :params {:textDocument {:uri effective-uri :languageId effective-lang :version 1 :text effective-content}}} state-atom)
                                                  (lsp/request-symbols effective-lang effective-uri state-atom)
                                                  (swap! state-atom assoc-in [:workspace :documents effective-uri :opened?] true)))
                                              (.next events (clj->js {:type "document-open" :data {:uri effective-uri :content effective-content :language effective-lang :activated make-active}}))))))
                        ;; Closes the specified or active document (triggers `document-close`). Notifies LSP if open.
                        ;; Example: (.closeDocument editor)
                        ;;          (.closeDocument editor "specific-uri")
                        :closeDocument (fn [uri-js]
                                         (let [uri (or uri-js (get-in @state-atom [:workspace :active-uri]))]
                                           (when uri
                                             (let [doc (get-in @state-atom [:workspace :documents uri])
                                                   lang (:language doc)]
                                               (when (:opened? doc)
                                                 (lsp/send lang {:method "textDocument/didClose"
                                                                 :params {:textDocument {:uri uri}}} state-atom))
                                               (d/transact! (:conn @state-atom) [[:db/retractEntity [:document/uri uri]]])
                                               (swap! state-atom update-in [:workspace :documents] dissoc uri)
                                               (when (= uri (get-in @state-atom [:workspace :active-uri]))
                                                 (let [new-active (first (keys (get-in @state-atom [:workspace :documents])))
                                                       new-content (get-in @state-atom [:workspace :documents new-active :content] "")]
                                                   (swap! state-atom assoc-in [:workspace :active-uri] new-active)
                                                   (when-let [view (.-current view-ref)]
                                                     (let [current-doc (.-doc (.-state view))
                                                           current-length (.-length current-doc)]
                                                       (.dispatch view #js {:changes #js {:from 0 :to current-length :insert new-content}})))
                                                   (syntax/init-syntax (.-current view-ref) state-atom)))
                                               (.next events (clj->js {:type "document-close" :data {:uri uri}}))))))
                        ;; Renames the specified or active document (updates URI, triggers `document-rename`). Notifies LSP.
                        ;; Example: (.renameDocument editor "new-name.rho")
                        ;;          (.renameDocument editor "new-name.rho" "old-uri")
                        :renameDocument (fn [new-uri-js old-uri-js]
                                          (let [new-uri-str (js->clj new-uri-js :keywordize-keys true)
                                                old-uri (or old-uri-js (get-in @state-atom [:workspace :active-uri]))
                                                [old-protocol _] (split-uri old-uri)
                                                effective-new-uri (str (or old-protocol "inmemory://") new-uri-str)]
                                            (when (and old-uri (contains? (get-in @state-atom [:workspace :documents]) old-uri))
                                              (when-not (contains? (get-in @state-atom [:workspace :documents]) effective-new-uri)
                                                (let [doc (get-in @state-atom [:workspace :documents old-uri])
                                                      lang (:language doc)]
                                                  (swap! state-atom update-in [:workspace :documents] assoc effective-new-uri doc)
                                                  (swap! state-atom update-in [:workspace :documents] dissoc old-uri)
                                                  (when (= old-uri (get-in @state-atom [:workspace :active-uri]))
                                                    (swap! state-atom assoc-in [:workspace :active-uri] effective-new-uri)
                                                    (when-let [view (.-current view-ref)]
                                                      (let [current-doc (.-doc (.-state view))
                                                            current-length (.-length current-doc)]
                                                        (.dispatch view #js {:changes #js {:from 0 :to current-length :insert (:content doc)}})))
                                                    (syntax/init-syntax (.-current view-ref) state-atom))
                                                  (when (:opened? doc)
                                                    (lsp/send lang {:method "workspace/didRenameFiles"
                                                                    :params {:files [{:oldUri old-uri :newUri effective-new-uri}]}} state-atom)
                                                    (lsp/request-symbols lang effective-new-uri state-atom)
                                                    (d/transact! (:conn @state-atom) [[:db/retractEntity [:document/uri old-uri]]]))
                                                  (.next events (clj->js {:type "document-rename"
                                                                          :data {:old-uri old-uri
                                                                                 :new-uri effective-new-uri}})))))))
                        ;; Saves the specified or active document (triggers `document-save`). Notifies LSP via `didSave`.
                        ;; Example: (.saveDocument editor)
                        ;;          (.saveDocument editor "specific-uri")
                        :saveDocument (fn [uri-js]
                                        (let [uri (or uri-js (get-in @state-atom [:workspace :active-uri]))
                                              doc (get-in @state-atom [:workspace :documents uri])
                                              lang (:language doc)]
                                          (when (and uri (:dirty? doc))
                                            (when (get-in @state-atom [:lsp lang :connection])
                                              (lsp/send lang {:method "textDocument/didSave"
                                                              :params {:textDocument {:uri uri}
                                                                       :text (:content doc)}} state-atom))
                                            (swap! state-atom assoc-in [:workspace :documents uri :dirty?] false)
                                            (.next events (clj->js {:type "document-save" :data {:uri uri :content (:content doc)}})))))
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
                                                    (.dispatch ^js (.-current view-ref) #js {:annotations (.of highlight/highlight-annotation (clj->js {:from from :to to}))})
                                                    (.next events (clj->js {:type "highlight-change" :data {:from from :to to}})) ;; Emit highlight-change event with range
                                                    )
                                                  (log/warn "Cannot highlight range: invalid offsets")))
                                              (log/warn "Cannot highlight range: view-ref is nil"))))
                        ;; Clears highlight in active document (triggers `highlight-change` with `null`).
                        ;; Example: (.clearHighlight editor)
                        :clearHighlight (fn []
                                          (if (.-current view-ref)
                                            (do
                                              (.dispatch ^js (.-current view-ref) #js {:annotations (.of highlight/highlight-annotation nil)})
                                              (.next events (clj->js {:type "highlight-change" :data nil})) ;; Emit highlight-change event with nil
                                              )
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
                                                 (.dispatch ^js (.-current view-ref) #js {:effects (EditorView.scrollIntoView (.range EditorSelection from-offset to-offset) #js {:y "center"})})
                                                 (log/warn "Cannot center on range: invalid offsets")))
                                             (log/warn "Cannot center on range: view-ref not ready"))))
                        ;; Returns text for specified or active document, or `null` if not found.
                        ;; Example: (.getText editor)
                        ;;          (.getText editor "specific-uri")
                        :getText (fn [uri-js]
                                   (let [uri (or uri-js (get-in @state-atom [:workspace :active-uri]))
                                         content (get-in @state-atom [:workspace :documents uri :content])]
                                     (or content nil)))
                        ;; Replaces entire text for specified or active document (triggers `content-change`).
                        ;; Example: (.setText editor "new text")
                        ;;          (.setText editor "new text" "specific-uri")
                        :setText (fn [text-js uri-js]
                                   (let [text (js->clj text-js :keywordize-keys true)
                                         uri (or uri-js (get-in @state-atom [:workspace :active-uri]))]
                                     (when uri
                                       (swap! state-atom assoc-in [:workspace :documents uri :content] text)
                                       (swap! state-atom assoc-in [:workspace :documents uri :dirty?] true)
                                       (when (= uri (get-in @state-atom [:workspace :active-uri]))
                                         (if (.-current view-ref)
                                           (let [^js v (.-current view-ref)
                                                 ^js doc (.-doc ^js (.-state ^js v))
                                                 len (.-length doc)]
                                             (.dispatch v #js {:changes #js {:from 0 :to len :insert text}}))
                                           (log/warn "Cannot set editor text: view not ready")))
                                       (let [lang (get-in @state-atom [:workspace :documents uri :language])]
                                         (when (and lang (get-in @state-atom [:workspace :documents uri :opened?])
                                                    (get-in @state-atom [:lsp lang :connection]))
                                           (lsp/send lang {:method "textDocument/didChange"
                                                           :params {:textDocument {:uri uri :version (inc (get-in @state-atom [:workspace :documents uri :version]))}
                                                                    :contentChanges [{:text text}]}} state-atom)))
                                       (.next events (clj->js {:type "content-change" :data {:content text :uri uri}})))))
                        ;; Returns file path (e.g., `"/demo.rho"`) for specified or active, or null if none.
                        ;; Example: (.getFilePath editor)
                        ;;          (.getFilePath editor "specific-uri")
                        :getFilePath (fn [uri-js]
                                       (let [uri (or uri-js (get-in @state-atom [:workspace :active-uri]))
                                             [_ path] (split-uri uri)]
                                         (or path nil)))
                        ;; Returns full URI (e.g., `"inmemory:///demo.rho"`) for specified or active, or `null` if none.
                        ;; Example: (.getFileUri editor)
                        ;;          (.getFileUri editor "specific-uri")
                        :getFileUri (fn [uri-js]
                                      (let [uri (or uri-js (get-in @state-atom [:workspace :active-uri]))]
                                        (or uri nil)))
                        ;; Sets the active document if exists, loads content to view, opens in LSP if not.
                        ;; Example: (.setActiveDocument editor "demo.rho")
                        :setActiveDocument (fn [uri-js]
                                             (let [uri (js->clj uri-js :keywordize-keys true)]
                                               (when (contains? (get-in @state-atom [:workspace :documents]) uri)
                                                 (when (not= uri (get-in @state-atom [:workspace :active-uri]))
                                                   (swap! state-atom assoc-in [:workspace :active-uri] uri)
                                                   (let [content (get-in @state-atom [:workspace :documents uri :content])
                                                         lang (get-in @state-atom [:workspace :documents uri :language])]
                                                     (when-let [view (.-current view-ref)]
                                                       (let [current-doc (.-doc (.-state view))
                                                             current-length (.-length current-doc)]
                                                         (.dispatch view #js {:changes #js {:from 0 :to current-length :insert content}})))
                                                     (when (not= lang (get-in @state-atom [:workspace :documents (get-in @state-atom [:workspace :active-uri]) :language]))
                                                       (syntax/init-syntax (.-current view-ref) state-atom))
                                                     (when-let [lsp-url (get-in @state-atom [:languages lang :lsp-url])]
                                                       (when-not (get-in @state-atom [:lsp lang :connection])
                                                         (lsp/connect lang {:url lsp-url} state-atom events))
                                                       (when (and (get-in @state-atom [:lsp lang :connection]) (get-in @state-atom [:lsp lang :initialized?]) (not (get-in @state-atom [:workspace :documents uri :opened?])))
                                                         (lsp/send lang {:method "textDocument/didOpen"
                                                                         :params {:textDocument {:uri uri :languageId lang :version (get-in @state-atom [:workspace :documents uri :version]) :text content}}} state-atom)
                                                         (lsp/request-symbols lang uri state-atom)
                                                         (swap! state-atom assoc-in [:workspace :documents uri :opened?] true))))))))})
                 #js [@state-atom (.-current view-ref)])
                (react/useEffect
                 (fn []
                   (let [prop-lang (:language props)]
                     (when (not= prop-lang (get-in @state-atom [:workspace :documents (get-in @state-atom [:workspace :active-uri]) :language]))
                       (swap! state-atom assoc-in [:workspace :documents (get-in @state-atom [:workspace :active-uri]) :language] prop-lang)
                       (syntax/init-syntax (.-current view-ref) state-atom)))
                   js/undefined)
                 #js [(:language props)])
                (react/useEffect
                 (fn []
                   (let [prop-content (:content props)]
                     (when (and (not= prop-content (get-in @state-atom [:workspace :documents (get-in @state-atom [:workspace :active-uri]) :content])) (not (get-in @state-atom [:workspace :documents (get-in @state-atom [:workspace :active-uri]) :dirty?])))
                       (swap! state-atom assoc-in [:workspace :documents (get-in @state-atom [:workspace :active-uri]) :content] prop-content)
                       (swap! state-atom assoc-in [:workspace :documents (get-in @state-atom [:workspace :active-uri]) :dirty?] false)))
                   js/undefined)
                 #js [(:content props)])
                (react/useEffect
                 (fn []
                   (when (.-current view-ref)
                     (let [current-doc (.toString (.-doc ^js (.-state ^js (.-current view-ref))))]
                       (when (not= (get-in @state-atom [:workspace :documents (get-in @state-atom [:workspace :active-uri]) :content]) current-doc)
                         (.dispatch ^js (.-current view-ref) #js {:changes #js {:from 0 :to (.length current-doc) :insert (get-in @state-atom [:workspace :documents (get-in @state-atom [:workspace :active-uri]) :content])}})
                         (when on-content-change
                           (on-content-change (get-in @state-atom [:workspace :documents (get-in @state-atom [:workspace :active-uri]) :content]))))))
                   js/undefined)
                 #js [(get-in @state-atom [:workspace :documents (get-in @state-atom [:workspace :active-uri]) :content])])
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
                                                 (.dispatch ^js (.-current view-ref) #js {:changes #js {:from 0 :to 0} :effects #js [(.of set-diagnostic-effect (clj->js diags))]}))))))]
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
                 #js [(get-in @state-atom [:workspace :documents (get-in @state-atom [:workspace :active-uri]) :language]) (:extra-extensions @state-atom) container-ref])
                (react/createElement "div" #js {:ref container-ref :className "code-editor flex-grow-1"})))]
  (def editor-comp (react/forwardRef inner))
  (def Editor editor-comp))
