(ns lib.core
  (:require
   ["@codemirror/autocomplete" :refer [closeBrackets]]
   ["@codemirror/commands" :refer [defaultKeymap]]
   ["@codemirror/language" :refer [bracketMatching indentOnInput]]
   ["@codemirror/state" :refer [EditorSelection EditorState]]
   ["@codemirror/view" :as view :refer [EditorView keymap lineNumbers]]
   ["react" :as react]
   ["rxjs" :as rxjs]
   [clojure.string :as str]
   [datascript.core :as d]
   [reagent.core :as r]
   [lib.db :refer [schema]]
   [lib.editor.diagnostics :as diagnostics]
   [lib.editor.highlight :as highlight]
   [lib.editor.syntax :as syntax]
   [lib.lsp.client :as lsp]
   [lib.utils :as u]
   [taoensso.timbre :as log]))

;; Hardcoded default languages for the library; uses string keys.
(defonce ^:const default-languages {"text" {:extensions [".txt"]
                                            :fallback-highlighter "none"}})

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
  Also normalizes inner config keys from camelCase to kebab-case."
  [langs]
  (reduce-kv
   (fn [m k v]
     (let [key-str (if (keyword? k) (name k) (str k))
           config (convert-config-keys v)]
       (assoc m key-str config)))
   {}
   langs))

(defn- default-state
  "Computes the initial editor state from converted CLJS props.
  Ensures language keys are strings and falls back to 'text' if no language is provided."
  [props]
  (let [lang (or (:language props) "text")
        languages (normalize-languages (merge default-languages (:languages props)))
        extra-extensions (:extra-extensions props #js [])]  ;; New: store extra extensions from props (JS array)
    (when-not (every? string? (keys languages))
      (log/warn "Non-string keys found in languages map:" (keys languages)))
    (log/debug "Initializing editor state with language:" lang "and languages:" (keys languages))
    {:uri nil
     :content (or (:content props) "")
     :language lang
     :version 0
     :dirty? false
     :opened? false
     :cursor {:line 1 :column 1}
     :selection nil
     :lsp {:connection nil
           :url nil
           :pending {}
           :initialized? false
           :logs []}
     :languages languages
     :extra-extensions extra-extensions  ;; New: store for use in get-extensions
     :debounce-timer nil  ;; Timer for debouncing LSP didChange notifications.
     :conn (d/create-conn schema)}))

(defn- update-editor-state
  "Updates the internal state-atom with cursor and selection info from CodeMirror state.
  Emits an event for external listeners."
  [^js state state-atom events]
  (let [main-sel (.-main (.-selection state))
        anchor (.-anchor main-sel)
        head (.-head main-sel)
        ^js doc (.-doc state)
        cursor-pos (u/offset-to-pos doc head true)
        sel (when (not= anchor head)
              (let [from (min anchor head)
                    to (max anchor head)
                    from-pos (u/offset-to-pos doc from true)
                    to-pos (u/offset-to-pos doc to true)
                    text (.sliceString doc from to)]
                {:from from-pos :to to-pos :text text}))]
    (swap! state-atom assoc :cursor cursor-pos :selection sel)
    (.next events (clj->js {:type "selection-change" :data {:cursor cursor-pos :selection sel}}))))

(defn- get-extensions
  "Returns the array of CodeMirror extensions, including dynamic syntax compartment,
  static diagnostic, and highlight extensions. Appends extra-extensions from state."
  [state-atom events]
  (let [update-ext (.. view/EditorView -updateListener
                       (of (fn [^js u]
                             (when (or (.-docChanged u) (.-selectionSet u))
                               (update-editor-state (.-state u) state-atom events))
                             (when (.-docChanged u)
                               (let [new-content (.toString (.-doc (.-state u)))]
                                 (swap! state-atom assoc :content new-content :dirty? true)
                                 (.next events (clj->js {:type "content-change" :data {:content new-content}}))
                                 (when-let [uri (:uri @state-atom)]
                                   ;; Debounce LSP didChange: clear existing timer before scheduling a new send.
                                   (when-let [timer (:debounce-timer @state-atom)]
                                     (js/clearTimeout timer))
                                   (swap! state-atom assoc :debounce-timer
                                          (js/setTimeout
                                           (fn []
                                             (swap! state-atom update :version inc)
                                             (lsp/send {:method "textDocument/didChange"
                                                        :params {:textDocument {:uri uri :version (:version @state-atom)}
                                                                 :contentChanges [{:text new-content}]}} state-atom)
                                             (log/debug "Debounced LSP didChange sent for URI:" uri))
                                           300))))))))  ;; 300ms debounce delay.
        default-exts [(lineNumbers)
                      (indentOnInput)
                      (bracketMatching)
                      (closeBrackets)
                      (.of keymap defaultKeymap)
                      (.of syntax/syntax-compartment #js [])
                      (.theme view/EditorView #js {} #js {:dark true})
                      diagnostics/diagnostic-field
                      diagnostics/diagnostic-plugin
                      highlight/highlight-field
                      highlight/highlight-plugin
                      update-ext]
        extra (:extra-extensions @state-atom #js [])]  ;; New: get extras from state
    (into-array (concat default-exts extra))))  ;; Append extras to defaults

;; Inner React functional component, handling CodeMirror integration and state management.
(let [inner (fn [js-props forwarded-ref]
              (let [props (js->clj js-props :keywordize-keys true)
                    state-atom (r/atom (default-state props))
                    view-atom (r/atom nil)
                    events (rxjs/BehaviorSubject.)
                    on-content-change (:onContentChange props)
                    container-ref (react/useRef nil)]
                (react/useImperativeHandle
                 forwarded-ref
                 (fn []
                   #js {:getState (fn []
                                    ;; Log query execution for debugging.
                                    (log/debug "Executing Datascript query for getState diagnostics and symbols")
                                    (clj->js (assoc @state-atom
                                                    :diagnostics (d/q '[:find (pull ?e [*])
                                                                        :where [?e :type :diagnostic]]
                                                                      @(:conn @state-atom))
                                                    :symbols (d/q '[:find (pull ?e [*])
                                                                    :where [?e :type :symbol]]
                                                                  @(:conn @state-atom)))))
                        :setContent (fn [content]
                                      (swap! state-atom assoc :content content :dirty? true))
                        :getEvents (fn [] events)
                        :getCursor (fn [] (clj->js (:cursor @state-atom)))
                        :setCursor (fn [pos-js]
                                     (let [pos (js->clj pos-js :keywordize-keys true)]
                                       (if @view-atom
                                         (let [^js doc (.-doc (.-state ^js @view-atom))
                                               offset (u/pos-to-offset doc pos true)]
                                           (when offset
                                             (.dispatch ^js @view-atom #js {:selection (EditorSelection.cursor offset)})
                                             (log/trace "Set cursor to" pos)))
                                         (log/trace "Skipping set cursor: view-atom not ready, pos:" pos))))
                        :getSelection (fn [] (clj->js (:selection @state-atom)))
                        :setSelection (fn [from-js to-js]
                                        (let [from (js->clj from-js :keywordize-keys true)
                                              to (js->clj to-js :keywordize-keys true)]
                                          (if @view-atom
                                            (let [^js doc (.-doc (.-state ^js @view-atom))
                                                  from-offset (u/pos-to-offset doc from true)
                                                  to-offset (u/pos-to-offset doc to true)]
                                              (when (and from-offset to-offset)
                                                (.dispatch ^js @view-atom #js {:selection (EditorSelection.range from-offset to-offset)})
                                                (log/trace "Set selection from" from "to" to)))
                                            (log/trace "Skipping set selection: view-atom not ready, from:" from "to:" to))))
                        :openDocument (fn [uri content lang]
                                        (let [effective-lang (or lang (:language @state-atom) "text")
                                              effective-content (or content "")]
                                          (when-not lang
                                            (log/warn "openDocument called with nil language; falling back to" effective-lang))
                                          (swap! state-atom assoc :uri uri :content effective-content :language effective-lang :version 1 :dirty? false :opened? false)
                                          (.next events (clj->js {:type "document-open" :data {:uri uri :language effective-lang}}))
                                          (when (and (get-in @state-atom [:lsp :connection]) (get-in @state-atom [:lsp :initialized?]))
                                            (lsp/send {:method "textDocument/didOpen"
                                                       :params {:textDocument {:uri uri :languageId effective-lang :version 1 :text effective-content}}} state-atom)
                                            (lsp/request-symbols uri state-atom)
                                            (swap! state-atom assoc :opened? true))))
                        :closeDocument (fn []
                                         (let [uri (:uri @state-atom)]
                                           (when uri
                                             (when (and (:opened? @state-atom) (get-in @state-atom [:lsp :connection]))
                                               (lsp/send {:method "textDocument/didClose"
                                                          :params {:textDocument {:uri uri}}} state-atom))
                                             (.next events (clj->js {:type "document-close" :data {:uri uri}})))
                                           (swap! state-atom assoc :uri nil :content "" :version 0 :dirty? false :opened? false)))
                        :renameDocument (fn [new-name]
                                          (when (and new-name (not (str/blank? new-name)))
                                            (let [old-uri (:uri @state-atom)
                                                  new-uri (str "inmemory://" new-name)]
                                              (swap! state-atom assoc :uri new-uri)
                                              (when-let [conn (get-in @state-atom [:lsp :connection])]
                                                (lsp/send {:method "textDocument/didClose"
                                                           :params {:textDocument {:uri old-uri}}} state-atom)
                                                (swap! state-atom update :version inc)
                                                (lsp/send {:method "textDocument/didOpen"
                                                           :params {:textDocument {:uri new-uri
                                                                                   :languageId (:language @state-atom)
                                                                                   :version (:version @state-atom)
                                                                                   :text (:content @state-atom)}}} state-atom)
                                                (lsp/request-symbols new-uri state-atom))
                                              (.next events (clj->js {:type "document-rename"
                                                                      :data {:old-uri old-uri
                                                                             :new-uri new-uri
                                                                             :name new-name}})))))
                        :saveDocument (fn []
                                        (let [uri (:uri @state-atom)
                                              content (:content @state-atom)]
                                          (when (and uri (:dirty? @state-atom))
                                            (when (get-in @state-atom [:lsp :connection])
                                              (lsp/send {:method "textDocument/didSave"
                                                         :params {:textDocument {:uri uri}
                                                                  :text content}} state-atom))
                                            (swap! state-atom assoc :dirty? false)
                                            (.next events (clj->js {:type "document-save" :data {:uri uri :content content}})))))
                        :isReady (fn [] (boolean @view-atom))
                        :highlightRange (fn [from-js to-js]
                                          (let [from (js->clj from-js :keywordize-keys true)
                                                to (js->clj to-js :keywordize-keys true)]
                                            (if @view-atom
                                              (let [^js doc (.-doc (.-state ^js @view-atom))
                                                    from-offset (u/pos-to-offset doc from true)
                                                    to-offset (u/pos-to-offset doc to true)]
                                                (if (and from-offset to-offset (<= from-offset to-offset))
                                                  (do
                                                    (.dispatch ^js @view-atom #js {:annotations (.of highlight/highlight-annotation (clj->js {:from from :to to}))})
                                                    (log/trace "Highlighted range from" from "to" to))
                                                  (log/warn "Cannot highlight range: invalid offsets, from:" from "to:" to)))
                                              (log/warn "Cannot highlight range: view-atom is nil, from:" from "to:" to))))
                        :clearHighlight (fn []
                                          (if @view-atom
                                            (do
                                              (.dispatch ^js @view-atom #js {:annotations (.of highlight/highlight-annotation nil)})
                                              (log/trace "Cleared highlight"))
                                            (log/trace "Skipping clear highlight: view-atom not ready")))
                        :centerOnRange (fn [from-js to-js]
                                         (let [from (js->clj from-js :keywordize-keys true)
                                               to (js->clj to-js :keywordize-keys true)]
                                           (if @view-atom
                                             (let [^js doc (.-doc (.-state ^js @view-atom))
                                                   from-offset (u/pos-to-offset doc from true)
                                                   to-offset (u/pos-to-offset doc to true)]
                                               (if (and from-offset to-offset)
                                                 (do
                                                   (.dispatch ^js @view-atom #js {:effects (EditorView.scrollIntoView (.range EditorSelection from-offset to-offset) #js {:y "center"})})
                                                   (log/trace "Centered on range from" from "to" to))
                                                 (log/warn "Cannot center on range: invalid offsets, from:" from "to:" to)))
                                             (log/trace "Skipping center on range: view-atom not ready, from:" from "to:" to))))}))
                (react/useEffect
                 (fn []
                   (log/debug "Editor: Initializing EditorView")
                   (let [container (.-current container-ref)
                         exts (get-extensions state-atom events)
                         editor-state (EditorState.create #js {:doc (:content @state-atom) :extensions exts})
                         v (EditorView. #js {:state editor-state :parent container})]
                     (log/trace "Editor: EditorView created, setting view-atom")
                     (reset! view-atom v)
                     (.next events (clj->js {:type "ready"}))
                     (update-editor-state editor-state state-atom events)
                     (fn []
                       (log/debug "Editor: Destroying EditorView")
                       (.destroy v)
                       (reset! view-atom nil))))
                 #js [])
                (react/useEffect
                 (fn []
                   ;; Internal subscription to events for handling diagnostics updates by dispatching
                   ;; a transaction to update the diagnostic StateField.
                   (let [sub (.subscribe events
                                         (fn [evt-js]
                                           (let [evt (js->clj evt-js :keywordize-keys true)
                                                 type (:type evt)]
                                             (when (and (= type "diagnostics") @view-atom)
                                               (let [diags (:data evt)]
                                                 (.dispatch ^js @view-atom #js {:annotations (.of diagnostics/diagnostic-annotation (clj->js diags))}))))))]
                     (fn [] (.unsubscribe sub))))
                 #js [])
                (react/useEffect
                 (fn []
                   (let [prop-lang (:language props)]
                     (when (not= prop-lang (:language @state-atom))
                       (swap! state-atom assoc :language prop-lang)))
                   js/undefined)
                 #js [(:language props)])
                (react/useEffect
                 (fn []
                   (let [prop-content (:content props)]
                     (when (not= prop-content (:content @state-atom))
                       (swap! state-atom assoc :content prop-content :dirty? false)))
                   js/undefined)
                 #js [(:content props)])
                (react/useEffect
                 (fn []
                   (when @view-atom
                     (let [current-doc (.toString (.-doc (.-state ^js @view-atom)))]
                       (when (not= (:content @state-atom) current-doc)
                         (.dispatch ^js @view-atom #js {:changes #js {:from 0 :to (.-length current-doc) :insert (:content @state-atom)}})
                         (when on-content-change
                           (on-content-change (:content @state-atom))))))
                   js/undefined)
                 #js [(:content @state-atom)])
                (react/useEffect
                 (fn []
                   (when @view-atom
                     (syntax/init-syntax @view-atom state-atom))
                   js/undefined)
                 #js [(:language @state-atom) @view-atom])
                (react/useEffect
                 (fn []
                   (let [lang (:language @state-atom)
                         config (get-in @state-atom [:languages lang])]
                     (when-not config
                       (log/warn "No language configuration for" lang "; LSP connection skipped"))
                     (when-let [url (:lsp-url config)]
                       (lsp/connect {:url url} state-atom events)))
                   js/undefined)
                 #js [(:language @state-atom)])
                (react/createElement "div" #js {:ref container-ref :className "code-editor flex-grow-1"})))]
  (def editor-comp (react/forwardRef inner))
  (def Editor editor-comp))
