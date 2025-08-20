(ns lib.editor.syntax
  (:require
   [clojure.core.async :as async :refer [go <! timeout]]
   [clojure.string :as str]
   [taoensso.timbre :as log]
   ["@codemirror/state" :refer [Compartment RangeSetBuilder StateField Text]]
   ["@codemirror/language" :refer [indentService indentUnit]]
   ["@codemirror/view" :refer [Decoration ViewPlugin]]
   ["web-tree-sitter" :as TreeSitter :refer [Language Parser Query]]
   [lib.db :as db]))

;; Compartment for dynamic reconfiguration of the syntax highlighting extension.
(def syntax-compartment (Compartment.))

;; Deferred promise for initializing Tree-Sitter (loaded once).
(defonce ts-init-promise
  (delay
    (Parser.init #js {:locateFile (fn [_ _] "js/tree-sitter.wasm")})))

;; Cache for loaded languages to avoid redundant loads (keyed by language string).
(defonce languages (atom {}))

;; Mapping from Tree-Sitter query capture names to CodeMirror CSS classes for styling.
(def style-map
  {"keyword" "cm-keyword"
   "operator" "cm-operator"
   "keyword.operator" "cm-keyword cm-operator"
   "variable" "cm-variable"
   "number" "cm-number"
   "string" "cm-string"
   "boolean" "cm-boolean"
   "comment" "cm-comment"
   "type" "cm-type"
   "constant" "cm-constant"
   "constant.builtin" "cm-constant"
   "function" "cm-function"
   "constructor" "cm-type"
   "method" "cm-function"
   "punctuation" "cm-punctuation"
   "punctuation.delimiter" "cm-punctuation"
   "punctuation.bracket" "cm-punctuation cm-bracket"})

(defn promise->chan
  "Converts a JS promise to an async channel, putting [:ok val] on resolve or [:error err] on reject."
  [p]
  (let [ch (async/chan)]
    (-> p
        (.then #(async/put! ch [:ok %]))
        (.catch #(async/put! ch [:error %])))
    ch))

(defn index-to-point
  "Converts a character offset to a Tree-Sitter point (row, column)."
  [^Text doc ^number index]
  (let [^js line (.lineAt doc index)]
    #js {:row (dec (.-number line))
         :column (- index (.-from line))}))

(defn line-indent
  "Calculates the indentation level of a line in spaces, accounting for tabs."
  [^js line ^js state]
  (let [text (.-text line)
        tab-size (.-tabSize state)]
    (loop [i 0
           indent 0]
      (if (>= i (.-length text))
        indent
        (let [code (.charCodeAt text i)]
          (cond
            (= code 9)  ; Tab
            (recur (inc i) (+ indent tab-size (- (mod indent tab-size))))
            (= code 32) ; Space
            (recur (inc i) (inc indent))
            :else indent))))))  ; Stop at first non-whitespace

(defn calculate-indent
  "Calculates the indentation level for a given position in the document.
   Returns the number of spaces to indent based on the indent-size and Tree-Sitter query.
   Handles @indent and @branch captures:
   - For @indent: base indent of the line at the capture start + indent-size.
   - For @branch: base indent of the line at the parent node start + 0 (aligns to the start of the construct, e.g., first process in par)."
  [ctx pos indents-query indent-size language-state-field]
  (let [^js state (.-state ctx)
        pos (or pos (some-> state .-selection .-main .-head) 0)]
    (if-not (number? pos)
      (do
        (log/warn "calculate-indent called with invalid pos:" pos)
        0)
      (let [^js tree (.. state (field language-state-field) -tree)]
        (if (nil? tree)
          (do
            (log/warn "No parse tree available for indentation")
            0)
          (let [^js root (.-rootNode ^js tree)]
            (if (nil? root)
              (do
                (log/warn "No root node in parse tree")
                0)
              (let [bias-pos (max 0 (if (> pos 0) (dec pos) pos))
                    ^js node (or (.descendantForIndex ^js root bias-pos bias-pos)
                                 (when (> bias-pos 0)
                                   (.descendantForIndex ^js root (dec bias-pos) (dec bias-pos)))
                                 root)]
                (if (or (nil? node) (#{"ERROR" "MISSING"} (.-type node)))
                  (do
                    (log/warn "Invalid node for indentation at pos" pos "node-type" (.-type node))
                    0)
                  (let [found (loop [^js cur node]
                                (cond
                                  (nil? cur) nil
                                  :else
                                  (let [caps (.captures ^js indents-query cur)]
                                    (cond
                                      (some #(= "branch" (.-name %)) caps) {:type "branch" :node cur :captures caps}
                                      (some #(= "indent" (.-name %)) caps) {:type "indent" :node cur :captures caps}
                                      :else (recur (.-parent cur))))))]
                    (if (nil? found)
                      0
                      (let [{:keys [type node captures]} found
                            cap (first (filter #(= type (.-name %)) captures))
                            ^js captured-node (.-node cap)
                            ;; For branch, use start of the node (e.g., par start) to align to the construct's beginning.
                            ;; For indent, use start of captured-node (usually the node itself).
                            start (if (= type "branch")
                                    (.-startIndex ^js node)
                                    (.-startIndex ^js captured-node))
                            ^js line (.lineAt (.-doc state) start)
                            base (line-indent line state)
                            add (if (= type "indent") indent-size 0)]
                        (+ base add)))))))))))))

(defn make-language-state
  "Creates a CodeMirror StateField for managing the Tree-Sitter parse tree.
   Updates incrementally on document changes."
  [parser]
  (.define StateField #js {:create (fn [^js state]
                                     (let [tree (.parse parser (.toString (.-doc state)))]
                                       #js {:tree tree :parser parser}))
                           :update (fn [value ^js tr]
                                     (if-not (.-docChanged tr)
                                       value
                                       (let [old-tree (.-tree ^js value)
                                             edited-tree (.copy ^js old-tree)
                                             old-doc (.-doc (.-startState tr))
                                             new-doc (.-doc (.-state tr))
                                             changes (.-changes tr)]
                                         (if (.-isEmpty changes)
                                           (let [new-tree (.parse (.-parser ^js value) (.toString new-doc))]
                                             #js {:tree new-tree :parser (.-parser ^js value)})
                                           (do
                                             (.iterChanges changes
                                                           (fn [fromA toA _fromB toB _]
                                                             (let [start (index-to-point old-doc fromA)
                                                                   old-end (index-to-point old-doc toA)
                                                                   new-end (index-to-point new-doc toB)]
                                                               (.edit ^js edited-tree #js {:startIndex fromA
                                                                                           :oldEndIndex toA
                                                                                           :newEndIndex toB
                                                                                           :startPosition start
                                                                                           :oldEndPosition old-end
                                                                                           :newEndPosition new-end})))
                                                           false)
                                             (let [new-tree (.parse (.-parser ^js value) (.toString new-doc) edited-tree)]
                                               #js {:tree new-tree :parser (.-parser ^js value)}))))))}))

(defn make-highlighter-plugin
  "Creates a ViewPlugin for syntax highlighting using Tree-Sitter queries.
   Builds decorations only for the visible viewport to optimize performance."
  [language-state-field highlight-query]
  (let [style-js (clj->js style-map)
        build-decorations (fn [view-or-update]
                            (let [view (if (.-view view-or-update) (.-view view-or-update) view-or-update)
                                  ^js state (.-state view)
                                  ^js lang-state (.field state language-state-field false)
                                  tree (when lang-state (.-tree lang-state))
                                  doc (.-doc state)
                                  builder (RangeSetBuilder.)]
                              (if (nil? tree)
                                (.finish builder)
                                (let [start-point (index-to-point doc (.-from (.-viewport view)))
                                      end-point (index-to-point doc (.-to (.-viewport view)))
                                      captures (.captures ^js highlight-query (.-rootNode ^js tree) start-point end-point)]
                                  (doseq [capture captures]
                                    (let [cls (aget style-js (.-name capture))
                                          node ^js (.-node capture)]
                                      (when cls
                                        (.add builder (.-startIndex node) (.-endIndex node) (.mark Decoration #js {:class cls})))))
                                  (.finish builder)))))]
    (.define ViewPlugin
             (fn [^js view]
               #js {:decorations (build-decorations view)
                    :update (fn [^js update]
                              (let [rebuilding? (or (.-docChanged update) (.-viewportChanged update)
                                                    (not= (.field (.-startState update) language-state-field)
                                                          (.field (.-state update) language-state-field)))]
                                (when rebuilding?
                                  (this-as ^js self
                                           (set! (.-decorations self) (build-decorations update))))))})
             #js {:decorations (fn [^js value] (.-decorations value))})))

(defn- make-indent-ext
  "Creates an indentService extension for CodeMirror using Tree-Sitter indentation queries."
  [indents-query indent-size language-state-field]
  (.of indentService (fn [ctx]
                       (calculate-indent ctx nil indents-query indent-size language-state-field))))

(defn fallback-extension
  "Returns a fallback extension when Tree-Sitter is unavailable (e.g., basic indentation)."
  [lang-config]
  (if (= (:fallback-highlighter lang-config) "regex")
    #js [] ;; Placeholder for regex-based fallback if implemented.
    #js []))

(defn init-syntax
  "Initializes syntax highlighting and indentation for the editor view asynchronously.
   Loads Tree-Sitter grammar and queries, configures extensions, and reconfigures the compartment.
   Falls back to basic mode on failure."
  [^js view state-atom]
  (go
    (let [lang-key (or (db/active-lang) "text")]
      (try
        (when-not (string? lang-key)
          (log/warn "Language key is not a string:" lang-key))
        (let [lang-config (get-in @state-atom [:languages lang-key])]
          (if-not lang-config
            (do
              (log/warn "No configuration found for" lang-key "- falling back to basic mode")
              (.dispatch view #js {:effects (.reconfigure syntax-compartment (fallback-extension {}))})
              :no-config)
            (let [wasm-path (:grammar-wasm lang-config)
                  ;; Skip core Tree-Sitter init if no grammar-wasm (e.g., for "text" fallback).
                  ;; This avoids unnecessary WASM loads and potential delays/hangs in tests or basic modes.
                  _ (when wasm-path
                      (<! (promise->chan @ts-init-promise))
                      (<! (timeout 100)))
                  indent-size (or (:indent-size lang-config) 2)
                  indent-unit-str (str/join (repeat indent-size " "))
                  indent-unit-ext (.of indentUnit indent-unit-str)
                  cached (get @languages lang-key)
                  highlight-query-str (or (:highlight-query lang-config)
                                          (when-let [path (:highlight-query-path lang-config)]
                                            (let [[resp-tag resp] (<! (promise->chan (js/fetch path)))]
                                              (if (= resp-tag :ok)
                                                (let [[text-tag text] (<! (promise->chan (.text resp)))]
                                                  (if (= text-tag :ok)
                                                    (do
                                                      (swap! state-atom assoc-in [:languages lang-key :highlight-query] text)
                                                      text)
                                                    (do
                                                      (log/error "Failed to read query text for" lang-key ":" (.-message text))
                                                      nil)))
                                                (do
                                                  (log/error "Failed to fetch query for" lang-key ":" (.-message resp))
                                                  nil)))))
                  indents-query-str (or (:indents-query lang-config)
                                        (when-let [path (:indents-query-path lang-config)]
                                          (let [[resp-tag resp] (<! (promise->chan (js/fetch path)))]
                                            (if (= resp-tag :ok)
                                              (let [[text-tag text] (<! (promise->chan (.text resp)))]
                                                (if (= text-tag :ok)
                                                  (do
                                                    (swap! state-atom assoc-in [:languages lang-key :indents-query] text)
                                                    text)
                                                  (do
                                                    (log/error "Failed to read indents query text for" lang-key ":" (.-message text))
                                                    nil)))
                                              (do
                                                (log/error "Failed to fetch indents query for" lang-key ":" (.-message resp))
                                                nil)))))
                  lang (or (:lang cached)
                           (when wasm-path
                             (let [[tag val] (<! (promise->chan (.load Language wasm-path)))]
                               (if (= tag :ok)
                                 val
                                 (do
                                   (log/error "Failed to load language WASM for" lang-key ":" (.-message val))
                                   nil)))))
                  parser (or (:parser cached)
                             (when lang
                               (doto (new Parser)
                                 (.setLanguage lang))))
                  highlight-query (when (and lang highlight-query-str)
                                    (try
                                      (new Query lang highlight-query-str)
                                      (catch js/Error e
                                        (log/error "Failed to create highlight query for" lang-key ":" (.-message e))
                                        nil)))
                  indents-query (when (and lang indents-query-str)
                                  (try
                                    (new Query lang indents-query-str)
                                    (catch js/Error e
                                      (log/error "Failed to create indents query for" lang-key ":" (.-message e))
                                      nil)))
                  language-state-field (when (and lang parser) (make-language-state parser))
                  highlight-plugin (when highlight-query (make-highlighter-plugin language-state-field highlight-query))
                  indent-ext (when indents-query (make-indent-ext indents-query indent-size language-state-field))
                  extensions (cond-> []
                               language-state-field (conj language-state-field)
                               highlight-plugin (conj highlight-plugin)
                               indent-ext (conj indent-ext)
                               true (conj indent-unit-ext))]
              (when-not wasm-path
                (log/debug "No grammar-wasm path provided for" lang-key))
              (when-not highlight-query-str
                (log/debug "No valid highlight query string for" lang-key))
              (when-not indents-query-str
                (log/debug "No indents query for" lang-key "; default indentation behavior will apply"))
              (when-not lang
                (log/debug "No language loaded for" lang-key))
              (when-not parser
                (log/debug "No parser created for" lang-key))
              (when (and (not cached) lang parser)
                (swap! languages assoc lang-key {:lang lang :parser parser}))
              (if (and lang parser highlight-query)
                (do
                  (.dispatch view #js {:effects (.reconfigure syntax-compartment (clj->js extensions))})
                  :success)
                (do
                  (log/debug "Missing required components for" lang-key ": lang=" (boolean lang) ", parser=" (boolean parser) ", highlight-query=" (boolean highlight-query) "; falling back to basic mode")
                  (.dispatch view #js {:effects (.reconfigure syntax-compartment (fallback-extension lang-config))})
                  :missing-components)))))
        (catch js/Error e
          (log/error "Failed to initialize Tree-Sitter syntax for" lang-key ":" (.-message e) "- falling back to basic mode")
          (when view (.dispatch view #js {:effects (.reconfigure syntax-compartment #js [])}))
          :error)))))
