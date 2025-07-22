(ns lib.editor.syntax
  (:require
   [clojure.core.async :as async :refer [go <!]]
   [clojure.string :as str]
   [taoensso.timbre :as log]
   ["@codemirror/state" :refer [ChangeSet Compartment Line RangeSetBuilder StateField Text]]
   ["@codemirror/language" :refer [indentService indentUnit]]
   ["@codemirror/view" :refer [Decoration ViewPlugin]]
   ["web-tree-sitter" :as TreeSitter :refer [Language Parser Query Tree]]))

;; Compartment for dynamic reconfiguration of the syntax highlighting extension.
(def syntax-compartment (Compartment.))

;; Deferred promise for initializing Tree-Sitter.
(defonce ts-init-promise
  (delay
    (Parser.init #js {:locateFile (fn [_ _] "/js/tree-sitter.wasm")})))

;; Cache for loaded languages to avoid redundant loads.
(defonce languages (atom {}))

;; Mapping from Tree-Sitter query capture names to CodeMirror CSS classes.
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

(defn promise->chan [p]
  (let [ch (async/chan)]
    (-> p
        (.then #(async/put! ch [:ok %]))
        (.catch #(async/put! ch [:error %])))
    ch))

(defn index-to-point [^Text doc ^number index]
  (let [line ^Line (.lineAt doc index)]
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
                    ^js node (or (.descendantForIndex ^js root bias-pos bias-pos) root)]
                (log/debug "Calculating indent at pos" pos "bias-pos" bias-pos "node-type" (.-type node)
                           "node-range" {:start (.-startIndex node) :end (.-endIndex node)})
                (if (or (nil? node) (#{"ERROR" "MISSING"} (.-type node)))
                  (do
                    (log/warn "Invalid node for indentation at pos" pos "node-type" (.-type node))
                    0)
                  (let [found (loop [^js cur node]
                                (cond
                                  (nil? cur) nil
                                  :else
                                  (let [caps (.captures indents-query cur)]
                                    (cond
                                      (some #(= "branch" (.-name %)) caps) {:type "branch" :node cur :captures caps}
                                      (some #(= "indent" (.-name %)) caps) {:type "indent" :node cur :captures caps}
                                      :else (recur (.-parent cur))))))]
                    (if (nil? found)
                      (do
                        (log/debug "No indent or branch node found, using base indent 0")
                        0)
                      (let [{:keys [type node captures]} found
                            cap (first (filter #(= type (.-name %)) captures))
                            captured-node (.-node cap)
                            ;; For branch, use start of the node (e.g., par start) to align to the construct's beginning.
                            ;; For indent, use start of captured-node (usually the node itself).
                            start (if (= type "branch")
                                    (.-startIndex node)
                                    (.-startIndex captured-node))
                            ^js line (.lineAt (.-doc state) start)
                            base (line-indent line state)
                            add (if (= type "indent") indent-size 0)]
                        (log/debug "Indentation level calculated: type" type "base" base "plus" add "start" start "captured-type" (.-type captured-node))
                        (+ base add)))))))))))))

(defn make-language-state
  "Creates a CodeMirror StateField for managing the Tree-Sitter parse tree."
  [parser]
  (.define StateField #js {:create (fn [^js state]
                                     (let [tree (.parse parser (.toString (.-doc state)))]
                                       (log/debug "Initial Tree-Sitter parse completed")
                                       #js {:tree tree :parser parser}))
                           :update (fn [value ^js tr]
                                     (if-not (.-docChanged tr)
                                       value
                                       (let [old-tree (.-tree value)
                                             edited-tree (.copy old-tree)
                                             changes ^ChangeSet (.-changes tr)
                                             old-doc (.-doc (.-startState tr))
                                             new-doc (.-doc (.-state tr))]
                                         (.iterChanges changes
                                                       (fn [fromA toA fromB toB _]
                                                         (let [start (index-to-point old-doc fromA)
                                                               old-end (index-to-point old-doc toA)
                                                               new-end (index-to-point new-doc toB)]
                                                           (.edit ^Tree edited-tree #js {:startIndex fromA
                                                                                         :oldEndIndex toA
                                                                                         :newEndIndex toB
                                                                                         :startPosition start
                                                                                         :oldEndPosition old-end
                                                                                         :newEndPosition new-end})))
                                                       false)
                                         (let [new-tree (.parse (.-parser value) (.toString new-doc) edited-tree)]
                                           (log/debug "Tree-Sitter tree incrementally updated")
                                           #js {:tree new-tree :parser (.-parser value)}))))}))

(defn make-highlighter-plugin
  "Creates a ViewPlugin for syntax highlighting using Tree-Sitter queries."
  [language-state-field highlight-query]
  (let [style-js (clj->js style-map)
        build-decorations (fn [^js view]
                            (let [builder (RangeSetBuilder.)
                                  vp (.-viewport view)
                                  from (.-from vp)
                                  to (.-to vp)
                                  doc (.-doc (.-state view))
                                  tree (.. (.-state view) (field language-state-field) -tree)
                                  start-point (index-to-point doc from)
                                  end-point (index-to-point doc to)
                                  captures (.captures highlight-query (.-rootNode tree) start-point end-point)]
                              (doseq [capture captures]
                                (let [cls (aget style-js (.-name capture))]
                                  (when cls
                                    (.add builder (.-startIndex (.-node capture)) (.-endIndex (.-node capture)) (.mark Decoration #js {:class cls})))))
                              (.finish builder)))
        PluginClass (fn [^js view]
                      (this-as this
                        (set! (.-decorations this) (build-decorations view))
                        this))]
    (set! (.-prototype ^js PluginClass) (js/Object.create js/Object.prototype))
    (set! (.-update (.-prototype ^js PluginClass)) (fn [^js update]
                                                      (this-as this
                                                        (when (or (.-viewportChanged update) (.-docChanged update))
                                                          (set! (.-decorations this) (build-decorations (.-view update)))))))
    (.fromClass ViewPlugin PluginClass #js {:decorations (fn [instance] (.-decorations instance))})))

(defn- make-indent-ext
  "Creates an indentService extension for CodeMirror using Tree-Sitter indentation queries."
  [indents-query indent-size language-state-field]
  (.of indentService (fn [ctx]
                       (calculate-indent ctx nil indents-query indent-size language-state-field))))

(defn fallback-extension
  "Returns a fallback extension when Tree-Sitter is unavailable."
  [lang-config]
  (if (= (:fallback-highlighter lang-config) "regex")
    #js []
    #js []))

(defn init-syntax
  "Initializes syntax highlighting and indentation for the editor view."
  [view state-atom]
  (go
    (try
      (let [[init-tag init-cause] (<! (promise->chan @ts-init-promise))]
        (if (= init-tag :ok)
          (log/info "Tree-Sitter initialized successfully")
          (do
            (log/error "Tree-Sitter initialization failed:" (.-message init-cause))
            (throw (ex-info "Tree-Sitter initialization failed" {:cause init-cause})))))
      (<! (async/timeout 100))
      (let [lang-key (or (:language @state-atom) "text")]
        (when-not (string? lang-key)
          (log/warn "Language key is not a string:" lang-key))
        (let [lang-config (get-in @state-atom [:languages lang-key])]
          (log/debug "state-atom" @state-atom)
          (log/debug "languages" (:languages @state-atom))
          (log/debug "lang-key" lang-key)
          (log/debug "lang-config" lang-config)
          (if-not lang-config
            (do
              (log/warn "No configuration found for language:" lang-key "- falling back to basic mode")
              (.dispatch view #js {:effects (.reconfigure syntax-compartment (fallback-extension {}))})
              :no-config)
            (let [wasm-path (:grammar-wasm lang-config)
                  indent-size (or (:indent-size lang-config) 2)
                  indent-unit-str (str/join (repeat indent-size " "))
                  cached (get @languages lang-key)
                  highlight-query-str (or (:highlight-query lang-config)
                                          (when-let [path (:highlight-query-path lang-config)]
                                            (log/debug "Fetching highlight query from" path)
                                            (let [[resp-tag resp] (<! (promise->chan (js/fetch path)))]
                                              (if (= resp-tag :ok)
                                                (let [[text-tag text] (<! (promise->chan (.text resp)))]
                                                  (if (= text-tag :ok)
                                                    (do
                                                      (swap! state-atom assoc-in [:languages lang-key :highlight-query] text)
                                                      (log/info "Loaded highlight query for" lang-key "from" path)
                                                      text)
                                                    (do
                                                      (log/error "Failed to read query text for" lang-key ":" (.-message text))
                                                      nil)))
                                                (do
                                                  (log/error "Failed to fetch query for" lang-key ":" (.-message resp))
                                                  nil)))))
                  indents-query-str (or (:indents-query lang-config)
                                        (when-let [path (:indents-query-path lang-config)]
                                          (log/debug "Fetching indents query from" path)
                                          (let [[resp-tag resp] (<! (promise->chan (js/fetch path)))]
                                            (if (= resp-tag :ok)
                                              (let [[text-tag text] (<! (promise->chan (.text resp)))]
                                                (if (= text-tag :ok)
                                                  (do
                                                    (swap! state-atom assoc-in [:languages lang-key :indents-query] text)
                                                    (log/info "Loaded indents query for" lang-key "from" path)
                                                    text)
                                                  (do
                                                    (log/error "Failed to read indents query text for" lang-key ":" (.-message text))
                                                    nil)))
                                              (do
                                                (log/error "Failed to fetch indents query for" lang-key ":" (.-message resp))
                                                nil)))))
                  lang (or (:lang cached)
                           (when wasm-path
                             (log/debug "Loading language WASM for" lang-key "from" wasm-path)
                             (let [[tag val] (<! (promise->chan (.load Language wasm-path)))]
                               (if (= tag :ok)
                                 (do
                                   (log/info "Successfully loaded language WASM for" lang-key "from" wasm-path)
                                   val)
                                 (do
                                   (log/error "Failed to load language WASM for" lang-key ":" (.-message val))
                                   nil)))))
                  parser (or (:parser cached)
                             (when lang
                               (log/debug "Creating parser for" lang-key)
                               (doto (new Parser)
                                 (.setLanguage lang))))
                  highlight-query (when (and lang highlight-query-str)
                                    (try
                                      (let [q (new Query lang highlight-query-str)]
                                        (log/info "Successfully created highlight query for" lang-key)
                                        q)
                                      (catch js/Error e
                                        (log/error "Failed to create highlight query for" lang-key ":" (.-message e))
                                        nil)))
                  indents-query (when (and lang indents-query-str)
                                  (try
                                    (let [q (new Query lang indents-query-str)]
                                      (log/info "Successfully created indents query for" lang-key)
                                      q)
                                    (catch js/Error e
                                      (log/error "Failed to create indents query for" lang-key ":" (.-message e))
                                      nil)))
                  language-state-field (when (and lang parser) (make-language-state parser))
                  highlight-plugin (when highlight-query (make-highlighter-plugin language-state-field highlight-query))
                  indent-ext (when indents-query (make-indent-ext indents-query indent-size language-state-field))
                  indent-unit-ext (.of indentUnit indent-unit-str)
                  extensions (cond-> []
                               language-state-field (conj language-state-field)
                               highlight-plugin (conj highlight-plugin)
                               indent-ext (conj indent-ext)
                               true (conj indent-unit-ext))]
              (when-not wasm-path
                (log/warn "No grammar-wasm path provided for" lang-key))
              (when-not highlight-query-str
                (log/warn "No valid highlight query string for" lang-key))
              (when-not indents-query-str
                (log/info "No indents query for" lang-key "; default indentation behavior will apply"))
              (when-not lang
                (log/warn "No language loaded for" lang-key))
              (when-not parser
                (log/warn "No parser created for" lang-key))
              (when (and (not cached) lang parser)
                (swap! languages assoc lang-key {:lang lang :parser parser})
                (log/info "Tree-Sitter cached for" lang-key))
              (if (and lang parser highlight-query)
                (do
                  (log/debug "Reconfiguring syntax compartment with Tree-Sitter extensions for" lang-key)
                  (.dispatch view #js {:effects (.reconfigure syntax-compartment (clj->js extensions))})
                  :success)
                (do
                  (log/warn "Missing required components for" lang-key ": lang=" (boolean lang) ", parser=" (boolean parser) ", highlight-query=" (boolean highlight-query) "; falling back to basic mode")
                  (.dispatch view #js {:effects (.reconfigure syntax-compartment (fallback-extension lang-config))})
                  :missing-components))))))
      (catch js/Error e
        (log/error "Failed to initialize Tree-Sitter syntax for" (:language @state-atom) ":" (.-message e) "- falling back to basic mode")
        (when view (.dispatch view #js {:effects (.reconfigure syntax-compartment #js [])}))
        :error))))
