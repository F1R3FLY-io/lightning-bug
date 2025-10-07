(ns lib.editor.syntax
  (:require
   [clojure.core.async :as async :refer [go <! timeout promise-chan put!]]
   [clojure.string :as str]
   [taoensso.timbre :as log]
   ["@codemirror/state" :refer [Compartment RangeSetBuilder StateField Text]]
   ["@codemirror/language" :refer [indentService indentUnit]]
   ["@codemirror/view" :refer [Decoration ViewPlugin]]
   ["web-tree-sitter" :as TreeSitter :refer [Language Parser Query]]
   [lib.db :as db]
   [lib.state :refer [normalize-languages load-resource]]
   [lib.utils :refer [log-error-with-cause promise->chan]]
   [lib.editor.annotations :refer [external-set-annotation]]))

;; Compartment for dynamic reconfiguration of the syntax highlighting extension.
(def syntax-compartment (Compartment.))

;; Deferred promise for initializing Tree-Sitter (loaded once).
(defonce ts-init-promise
  (delay
    (log/trace "Initializing Tree-Sitter with wasm path: js/tree-sitter.wasm")
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

(defn index->point
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
            :else indent))))))  ;; Stop at first non-whitespace

(defn calculate-indent
  "Calculates the indentation level for a given position in the document."
  [ctx position indents-query indent-size language-state-field]
  (let [^js state (.-state ctx)
        position (or position (some-> state .-selection .-main .-head) 0)]
    (if-not (number? position)
      (do
        (log/warn "calculate-indent called with invalid pos:" position)
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
              (let [bias-position (max 0 (if (pos? position) (dec position) position))
                    ^js node (or (.descendantForIndex ^js root bias-position bias-position)
                                 (when (pos? bias-position)
                                   (.descendantForIndex ^js root (dec bias-position) (dec bias-position)))
                                 root)]
                (if (or (nil? node) (#{"ERROR" "MISSING"} (.-type node)))
                  (do
                    (log/warn (str "Invalid node for indentation at pos=" position ", node-type=" (.-type node)))
                    0)
                  (let [found (loop [^js cur node]
                                (cond
                                  (nil? cur) nil
                                  :else
                                  (let [caps (.captures ^js indents-query cur)]
                                    (condp some caps
                                      #(= "branch" (.-name %)) {:type "branch" :node cur :captures caps}
                                      #(= "indent" (.-name %)) {:type "indent" :node cur :captures caps}
                                      (recur (.-parent cur))))))]
                    (if (nil? found)
                      0
                      (let [{:keys [type node captures]} found
                            cap (first (filter #(= type (.-name %)) captures))
                            ^js captured-node (.-node cap)
                            start (if (= type "branch")
                                    (.-startIndex ^js node)
                                    (.-startIndex ^js captured-node))
                            ^js line (.lineAt (.-doc state) start)
                            base (line-indent line state)
                            add (if (= type "indent") indent-size 0)]
                        (+ base add)))))))))))))

(defn make-language-state
  "Creates a CodeMirror StateField for managing the Tree-Sitter parse tree."
  [parser]
  (.define StateField #js {:create (fn [^js state]
                                     (let [tree (.parse parser (str (.-doc state)))]
                                       #js {:tree tree :parser parser}))
                           :update (fn [value ^js tr]
                                     (if-not (.-docChanged tr)
                                       value
                                       (if (some #(.annotation % external-set-annotation) (.-transactions tr))
                                         (let [new-tree (.parse (.-parser ^js value) (str (.-doc (.-state tr))))]
                                           #js {:tree new-tree :parser (.-parser ^js value)})
                                         (let [old-tree (.-tree ^js value)
                                               edited-tree (.copy ^js old-tree)
                                               old-doc (.-doc (.-startState tr))
                                               new-doc (.-doc (.-state tr))
                                               changes (.-changes tr)]
                                           (if (.-isEmpty changes)
                                             (let [new-tree (.parse (.-parser ^js value) (str new-doc))]
                                               #js {:tree new-tree :parser (.-parser ^js value)})
                                             (do
                                               (.iterChanges changes
                                                             (fn [fromA toA _fromB toB _]
                                                               (let [start (index->point old-doc fromA)
                                                                     old-end (index->point old-doc toA)
                                                                     new-end (index->point new-doc toB)]
                                                                 (.edit ^js edited-tree #js {:startIndex fromA
                                                                                             :oldEndIndex toA
                                                                                             :newEndIndex toB
                                                                                             :startPosition start
                                                                                             :oldEndPosition old-end
                                                                                             :newEndPosition new-end})))
                                                             false)
                                               (let [new-tree (.parse (.-parser ^js value) (str new-doc) edited-tree)]
                                                 #js {:tree new-tree :parser (.-parser ^js value)})))))))}))

(defn make-highlighter-plugin
  "Creates a ViewPlugin for syntax highlighting using Tree-Sitter queries."
  [language-state-field highlights-query]
  (let [style-js (clj->js style-map)
        build-decorations (fn [view-or-update]
                            (let [view (or (.-view view-or-update) view-or-update)
                                  ^js state (.-state view)
                                  ^js lang-state (.field state language-state-field false)
                                  tree (when lang-state (.-tree lang-state))
                                  doc (.-doc state)
                                  builder (RangeSetBuilder.)]
                              (if (nil? tree)
                                (.finish builder)
                                (let [start-point (index->point doc (.-from (.-viewport view)))
                                      end-point (index->point doc (.-to (.-viewport view)))
                                      captures (.captures ^js highlights-query (.-rootNode ^js tree) start-point end-point)]
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
                              (when (or (.-docChanged update) (.-viewportChanged update)
                                        (not= (.field (.-startState update) language-state-field)
                                              (.field (.-state update) language-state-field)))
                                (this-as ^js self
                                         (set! (.-decorations self) (build-decorations update)))))})
             #js {:decorations (fn [^js value] (.-decorations value))})))

(defn- make-indent-ext
  "Creates an indentService extension for CodeMirror using Tree-Sitter indentation queries."
  [indents-query indent-size language-state-field]
  (.of indentService (fn [ctx]
                       (calculate-indent ctx nil indents-query indent-size language-state-field))))

(defn fallback-extension
  "Returns a fallback extension when Tree-Sitter is unavailable."
  [_lang-config]
  #js [])

(defn load-resource-with-validator
  "Loads a resource and validates it with the provided predicate, logging errors if invalid."
  [type lang-key resource-key supplier pred error-msg]
  (go
    (try
      (let [[status result] (<! (load-resource type resource-key supplier))]
        (log/trace "Loaded resource" resource-key "for" lang-key "with status" status "and type" (type result))
        (if (= :error status)
          (do
            (log/error error-msg lang-key (.-message result))
            [:error result])
          (if (pred result)
            [:ok result]
            (let [err (js/Error. (str error-msg lang-key ": failed validation, got " (type result)))]
              (log/error error-msg lang-key (.-message err))
              [:error err]))))
      (catch js/Error e
        (log/error error-msg lang-key (.-message e))
        [:error e]))))

(defn init-syntax
  "Initializes syntax highlighting and indentation for the editor view asynchronously.
   Loads Tree-Sitter grammar and queries, configures extensions, and reconfigures the compartment."
  [^js view state-atom]
  (go
    (try
      (if-let [lang-key (db/active-lang)]
        (do
          (log/info "Initializing syntax for language:" lang-key)
          (let [langs (:languages @state-atom)]
            (when (some keyword? (keys langs))
              (log/debug "Normalizing language keys in state-atom")
              (swap! state-atom assoc :languages (normalize-languages langs))))
          (let [lang-config (get-in @state-atom [:languages lang-key])]
            (log/debug "Language config:" lang-config)
            (if-not lang-config
              (do
                (log/warn "No configuration found for" lang-key "- falling back to basic mode")
                (.dispatch view #js {:effects (.reconfigure syntax-compartment (fallback-extension {}))})
                [:ok :no-config])
              (let [ts-wasm-path (let [p (:tree-sitter-wasm @state-atom)]
                                   (cond (string? p) p
                                         (fn? p) (p)
                                         (nil? p) "js/tree-sitter.wasm"
                                         :else (throw (js/Error. "Invalid :tree-sitter-wasm: must be string or function"))))
                    parser-raw (:parser lang-config)
                    lang-wasm-path (when-not parser-raw
                                     (let [p (:grammar-wasm lang-config)]
                                       (cond (nil? p) nil
                                             (string? p) p
                                             (fn? p) (p)
                                             :else (throw (js/Error. "Invalid :grammar-wasm: must be string or function")))))]
                (log/trace "Loading syntax for" lang-key "with ts-wasm-path:" ts-wasm-path "lang-wasm-path:" lang-wasm-path)
                (if (and (nil? parser-raw) (nil? lang-wasm-path))
                  (do
                    (log/debug (str "No parser or grammar-wasm for " lang-key "; using fallback mode"))
                    (.dispatch view #js {:effects (.reconfigure syntax-compartment (fallback-extension lang-config))})
                    [:ok :no-tree-sitter])
                  (let [cached (get @languages lang-key)]
                    (if-let [extensions (:extensions cached)]
                      (do
                        (log/debug "Using cached syntax extensions for" lang-key)
                        (.dispatch view #js {:effects (.reconfigure syntax-compartment extensions)})
                        [:ok :from-cache])
                      (do
                        ;; Initialize Tree-Sitter
                        (let [ts-init-promise (delay
                                                (Parser.init #js {:locateFile (fn [_ _] ts-wasm-path)}))
                              ts-init-res (<! (promise->chan @ts-init-promise))]
                          (if (= :error (first ts-init-res))
                            (do
                              (log/error "Failed to initialize Tree-Sitter for" lang-key ":" (.-message (second ts-init-res)))
                              (throw (js/Error. "Failed to initialize Tree-Sitter" #js {:cause (second ts-init-res)})))
                            (log/debug "Tree-Sitter initialized for" lang-key)))
                        (<! (timeout 100))
                        ;; Load queries and parser in parallel
                        (let [indent-size (or (:indent-size lang-config) 2)
                              indent-unit-str (str/join (repeat indent-size " "))
                              indent-unit-ext (.of indentUnit indent-unit-str)
                              hq-str-ch (load-resource-with-validator
                                         :tree-sitter
                                         lang-key
                                         (keyword lang-key "highlights-query-str")
                                         (fn []
                                           [:ok (js/Promise.
                                                 (fn [resolve reject]
                                                   (let [query (:highlights-query lang-config)
                                                         query-path (:highlights-query-path lang-config)]
                                                     (cond
                                                       (:highlights-query cached) (resolve (:highlights-query cached))
                                                       query (resolve query)
                                                       query-path
                                                       (let [effective-p (cond (string? query-path) query-path
                                                                               (fn? query-path) (query-path)
                                                                               :else (do
                                                                                       (reject (js/Error. "Invalid :highlights-query-path: must be string or function"))
                                                                                       nil))]
                                                         (log/trace "Fetching highlights query for" lang-key "from" effective-p)
                                                         (-> (js/fetch effective-p)
                                                             (.then (fn [resp]
                                                                      (if (.-ok resp)
                                                                        (.text resp)
                                                                        (reject (js/Error. (str "Failed to fetch query for " lang-key ": HTTP " (.-status resp) " - " (.-statusText resp)))))))
                                                             (.then resolve)
                                                             (.catch reject)))
                                                       :else (resolve nil)))))])
                                         string?
                                         "Failed to load highlights-query-str for ")
                              iq-str-ch (load-resource-with-validator
                                         :tree-sitter
                                         lang-key
                                         (keyword lang-key "indents-query-str")
                                         (fn []
                                           [:ok (js/Promise.
                                                 (fn [resolve reject]
                                                   (let [query (:indents-query lang-config)
                                                         query-path (:indents-query-path lang-config)]
                                                     (cond
                                                       (:indents-query cached) (resolve (:indents-query cached))
                                                       query (resolve query)
                                                       query-path
                                                       (let [effective-p (cond (string? query-path) query-path
                                                                               (fn? query-path) (query-path)
                                                                               :else (do
                                                                                       (reject (js/Error. "Invalid :indents-query-path: must be string or function"))
                                                                                       nil))]
                                                         (log/trace "Fetching indents query for" lang-key "from" effective-p)
                                                         (-> (js/fetch effective-p)
                                                             (.then (fn [resp]
                                                                      (if (.-ok resp)
                                                                        (.text resp)
                                                                        (reject (js/Error. (str "Failed to fetch indents query for " lang-key ": HTTP " (.-status resp) " - " (.-statusText resp)))))))
                                                             (.then resolve)
                                                             (.catch reject)))
                                                       :else (resolve nil)))))])
                                         string?
                                         "Failed to load indents-query-str for ")
                              parser-ch (load-resource-with-validator
                                         :tree-sitter
                                         lang-key
                                         (keyword lang-key "parser")
                                         (fn []
                                           [:ok (js/Promise.
                                                 (fn [resolve reject]
                                                   (try
                                                     (if-let [cached-parser (:parser cached)]
                                                       (resolve cached-parser)
                                                       (if parser-raw
                                                         (let [maybe-promise (if (fn? parser-raw) (parser-raw) parser-raw)]
                                                           (if (instance? js/Promise maybe-promise)
                                                             (-> maybe-promise
                                                                 (.then resolve)
                                                                 (.catch reject))
                                                             (resolve maybe-promise)))
                                                         (if lang-wasm-path
                                                           (do
                                                             (log/trace "Loading Tree-Sitter grammar for" lang-key "from" lang-wasm-path)
                                                             (-> (Language.load lang-wasm-path)
                                                                 (.then (fn [language]
                                                                          (let [parser (Parser.)]
                                                                            (.setLanguage parser language)
                                                                            (resolve parser))))
                                                                 (.catch reject)))
                                                           (reject (js/Error. "No parser or grammar-wasm provided for language")))))
                                                     (catch js/Error e
                                                       (reject e)))))])
                                         #(instance? Parser %)
                                         "Failed to load parser for ")
                              [hq-status highlights-query-str] (<! hq-str-ch)
                              [parser-status parser] (<! parser-ch)
                              [lang-status lang] (if (= :error parser-status)
                                                   [:ok nil]
                                                   [:ok (.-language parser)])
                              hq-query-ch (if (or (= :error hq-status) (nil? highlights-query-str) (= :error lang-status))
                                            (doto (promise-chan) (put! [:ok nil]))
                                            (load-resource-with-validator
                                             :tree-sitter
                                             lang-key
                                             (keyword lang-key "highlights-query")
                                             (fn []
                                               [:ok (js/Promise.
                                                     (fn [resolve reject]
                                                       (if-let [cached-query (:highlights-query cached)]
                                                         (resolve cached-query)
                                                         (if (and lang highlights-query-str)
                                                           (try
                                                             (resolve (Query. lang highlights-query-str))
                                                             (catch js/Error e
                                                               (log/error (str "Failed to create highlight query for " lang-key ": " (.-message e)))
                                                               (reject e)))
                                                           (resolve nil)))))])
                                             #(instance? Query %)
                                             "Failed to load highlights-query for "))
                              [iq-status indents-query-str] (<! iq-str-ch)
                              iq-query-ch (if (or (= :error iq-status) (nil? indents-query-str) (= :error lang-status))
                                            (doto (promise-chan) (put! [:ok nil]))
                                            (load-resource-with-validator
                                             :tree-sitter
                                             lang-key
                                             (keyword lang-key "indents-query")
                                             (fn []
                                               [:ok (js/Promise.
                                                     (fn [resolve reject]
                                                       (if-let [cached-query (:indents-query cached)]
                                                         (resolve cached-query)
                                                         (if (and lang indents-query-str)
                                                           (try
                                                             (resolve (Query. lang indents-query-str))
                                                             (catch js/Error e
                                                               (log/error (str "Failed to create indents query for " lang-key ": " (.-message e)))
                                                               (reject e)))
                                                           (resolve nil)))))])
                                             #(instance? Query %)
                                             "Failed to load indents-query for "))
                              lsf-ch (if (= :error parser-status)
                                       (doto (promise-chan) (put! [:ok nil]))
                                       (load-resource-with-validator
                                        :tree-sitter
                                        lang-key
                                        (keyword lang-key "language-state-field")
                                        (fn []
                                          [:ok (js/Promise.
                                                (fn [resolve reject]
                                                  (if-let [cached-field (:language-state-field cached)]
                                                    (resolve cached-field)
                                                    (if parser
                                                      (resolve (make-language-state parser))
                                                      (reject (js/Error. "No parser available for language-state-field"))))))])
                                        #(instance? StateField %)
                                        "Failed to load language-state-field for "))
                              [lsf-status language-state-field] (<! lsf-ch)
                              [hq-query-status highlights-query] (<! hq-query-ch)
                              hp-ch (if (or (= :error lsf-status) (= :error hq-query-status) (nil? highlights-query))
                                      (doto (promise-chan) (put! [:ok nil]))
                                      (load-resource-with-validator
                                       :tree-sitter
                                       lang-key
                                       (keyword lang-key "highlight-plugin")
                                       (fn []
                                         [:ok (js/Promise.
                                               (fn [resolve _]
                                                 (if-let [cached-plugin (:highlight-plugin cached)]
                                                   (resolve cached-plugin)
                                                   (if highlights-query
                                                     (resolve (make-highlighter-plugin language-state-field highlights-query))
                                                     (resolve nil)))))])
                                       #(instance? ViewPlugin %)
                                       "Failed to load highlight-plugin for "))
                              [iq-query-status indents-query] (<! iq-query-ch)
                              ie-ch (if (or (= :error lsf-status) (= :error iq-query-status) (nil? indents-query))
                                      (doto (promise-chan) (put! [:ok nil]))
                                      (load-resource-with-validator
                                       :tree-sitter
                                       lang-key
                                       (keyword lang-key "indent-ext")
                                       (fn []
                                         [:ok (js/Promise.
                                               (fn [resolve _]
                                                 (if-let [cached-ext (:indent-ext cached)]
                                                   (resolve cached-ext)
                                                   (if indents-query
                                                     (resolve (make-indent-ext indents-query indent-size language-state-field))
                                                     (resolve nil)))))])
                                       (constantly true)
                                       "Failed to load indent-ext for "))
                              [hp-status highlight-plugin] (<! hp-ch)
                              [ie-status indent-ext] (<! ie-ch)
                              extensions (cond-> []
                                           language-state-field (conj language-state-field)
                                           highlight-plugin (conj highlight-plugin)
                                           indent-ext (conj indent-ext)
                                           true (conj indent-unit-ext))
                              artifacts {:parser parser
                                         :lang lang
                                         :highlights-query highlights-query
                                         :indents-query indents-query
                                         :language-state-field language-state-field
                                         :highlight-plugin highlight-plugin
                                         :indent-ext indent-ext
                                         :extensions (clj->js extensions)}]
                          (if (= :ok hq-status)
                            (log/debug "Loaded highlights-query string for" lang-key)
                            (log/error "No valid highlights-query string for" lang-key))
                          (if (= :ok iq-status)
                            (log/debug "Loaded indents-query for" lang-key)
                            (log/error "No indents query for" lang-key "; default indentation behavior will apply"))
                          (if (= :ok parser-status)
                            (log/debug "Loaded parser for" lang-key)
                            (log/error "Parser loading failed for" lang-key ":" (.-message parser)))
                          (if (= :ok lang-status)
                            (log/debug "Loaded language for" lang-key)
                            (log/error "No language loaded for" lang-key))
                          (if (= :ok hq-query-status)
                            (log/debug "Loaded highlights-query for" lang-key)
                            (log/error "No highlights query loaded for" lang-key))
                          (if (= :ok iq-query-status)
                            (log/debug "Loaded indents-query for" lang-key)
                            (log/error "No indents query loaded for" lang-key))
                          (if (= :ok lsf-status)
                            (log/debug "Loaded language state field for" lang-key)
                            (log/error "No language state field loaded for" lang-key))
                          (if (= :ok hp-status)
                            (log/debug "Loaded highlight plugin for" lang-key)
                            (log/error "No highlight plugin loaded for" lang-key))
                          (if (= :ok ie-status)
                            (log/debug "Loaded indent extension for" lang-key)
                            (log/error "No indent extension loaded for" lang-key))
                          (log/debug "Syntax initialization complete for" lang-key
                                     ": parser=" (boolean parser)
                                     ", lang=" (boolean lang)
                                     ", highlights-query=" (boolean highlights-query)
                                     ", indents-query=" (boolean indents-query)
                                     ", language-state-field=" (boolean language-state-field)
                                     ", highlight-plugin=" (boolean highlight-plugin)
                                     ", indent-ext=" (boolean indent-ext))
                          (if (and lang parser highlights-query language-state-field highlight-plugin indent-ext)
                            (do
                              (swap! languages assoc lang-key artifacts)
                              (.dispatch view #js {:effects (.reconfigure syntax-compartment (clj->js extensions))})
                              [:ok :success])
                            (do
                              (log/debug (str "Missing required components for " lang-key
                                              ": lang=" (boolean lang)
                                              ", parser=" (boolean parser)
                                              ", highlights-query=" (boolean highlights-query)
                                              ", language-state-field=" (boolean language-state-field)
                                              ", highlight-plugin=" (boolean highlight-plugin)
                                              ", indent-ext=" (boolean indent-ext)
                                              "; falling back to basic mode"))
                              (.dispatch view #js {:effects (.reconfigure syntax-compartment (fallback-extension lang-config))})
                              [:ok :missing-components])))))))))))
        (do
          (log/debug "No active language.")
          [:ok :no-active-lang]))
      (catch js/Error error
        (let [error-with-cause (js/Error. "Syntax initialization failed" #js {:cause error})]
          (log-error-with-cause error-with-cause)
          (.dispatch view #js {:effects (.reconfigure syntax-compartment (fallback-extension {}))})
          [:error error-with-cause])))))
