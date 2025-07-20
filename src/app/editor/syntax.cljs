(ns app.editor.syntax
  (:require
   [clojure.core.async :as async :refer [go <!]]
   [re-frame.db :refer [app-db]]
   [taoensso.timbre :as log]
   ["@codemirror/state" :refer [ChangeSet Compartment Line RangeSetBuilder Text]]
   ["@codemirror/view" :refer [Decoration ViewPlugin]]
   ["web-tree-sitter" :as TreeSitter :refer [Language Parser Tree]]))

;; Compartment for dynamic reconfiguration of the syntax highlighting extension.
;; Allows hot-swapping the highlighting plugin without recreating the entire editor state.
(def syntax-compartment (Compartment.))

;; Deferred promise for initializing Tree-Sitter. Using `delay` defers evaluation until dereferenced,
;; preventing premature calls during namespace load or hot-reload.
(defonce ts-init-promise
  (delay
    (Parser.init #js {:locateFile (fn [_ _] "/js/tree-sitter.wasm")})))

;; Cache for loaded languages to avoid redundant loads and improve performance.
(defonce languages (atom {}))

;; Mapping from Tree-Sitter query capture names to CodeMirror CSS classes for styling.
;; This decouples the query captures from hardcoded styles, allowing easy theme adjustments.
;; Follows standard Tree-sitter conventions (e.g., @keyword.operator for word-based operators).
;; Updated to include captures from the Neovim-style query (e.g., function, constant.builtin).
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
   "constructor" "cm-type" ; Mapped to type for collections
   "method" "cm-function" ; Unified with function
   "punctuation" "cm-punctuation"
   "punctuation.delimiter" "cm-punctuation"
   "punctuation.bracket" "cm-punctuation cm-bracket"})

(defn promise->chan [p]
  (let [ch (async/chan)]
    (-> p
        (.then #(async/put! ch [:ok %]))
        (.catch #(async/put! ch [:error %])))
    ch))

;; Converts a byte index to a Tree-sitter point {row, column}.
(defn index-to-point [^Text doc ^number index]
  (let [line ^Line (.lineAt doc index)]
    #js {:row (dec (.-number line))
         :column (- index (.-from line))}))

;; Creates a CodeMirror ViewPlugin for Tree-Sitter-based syntax highlighting.
;; The plugin parses the document and applies decorations based on query captures within the visible viewport.
;; Returns nil if language or query is invalid to trigger fallback.
(defn create-ts-plugin [parser lang query]
  (when (and lang query)
    (let [style-js (clj->js style-map)
          ;; Builds decorations only for the visible viewport to optimize performance.
          build-decorations-fn (fn [this view]
                                 (let [builder (RangeSetBuilder.)
                                       vp (.-viewport view)
                                       from (.-from vp)
                                       to (.-to vp)
                                       doc (.-doc (.-state view))
                                       startPoint (index-to-point doc from)
                                       endPoint (index-to-point doc to)
                                       captures (.captures (.-query this) (.-rootNode (.-tree ^js this)) startPoint endPoint)]
                                   (doseq [capture captures]
                                     (let [cls (aget style-js (.-name capture))]
                                       (when cls
                                         (.add builder (.-startIndex (.-node capture)) (.-endIndex (.-node capture)) (.mark Decoration #js {:class cls})))))
                                   (.finish builder)))
          ;; Updates the parse tree and decorations on document or viewport changes.
          ;; Uses incremental parsing with Tree-sitter's edit and parse APIs for efficiency.
          update-fn (fn [update]
                      (this-as this
                        (when (.-viewportChanged ^js update)
                          (set! (.-decorations this) (build-decorations-fn this (.-view update))))
                        (when (.-docChanged ^js update)
                          (let [old-doc (.-doc (.-startState update))
                                new-doc (.-doc (.-state update))
                                changes ^ChangeSet (.-changes update)
                                tree ^Tree (.-tree this)]
                            (.iterChanges changes
                                         (fn [fromA toA _ toB _inserted]
                                           (let [start-pos (index-to-point old-doc fromA)
                                                 old-end-pos (index-to-point old-doc toA)
                                                 new-end-pos (index-to-point new-doc toB)]
                                             (.edit ^Tree tree #js {:startIndex fromA
                                                                    :oldEndIndex toA
                                                                    :newEndIndex toB
                                                                    :startPosition start-pos
                                                                    :oldEndPosition old-end-pos
                                                                    :newEndPosition new-end-pos})))
                                         false) ; Non-individual changes (batched)
                            (set! (.-tree this) (.parse parser (.toString new-doc) tree))
                            (set! (.-decorations this) (build-decorations-fn this (.-view update)))))))
          ;; Plugin constructor: initializes parser, query, tree, and initial decorations.
          PluginClass (fn [view]
                        (this-as this
                          (set! (.-parser this) parser)
                          (set! (.-query this) query)
                          (set! (.-tree this) (.parse parser (.toString (.-doc (.-state view)))))
                          (set! (.-decorations this) (build-decorations-fn this view))
                          this))]
      (set! (.-prototype ^js PluginClass) (js/Object.create js/Object.prototype))
      (set! (.-update (.-prototype ^js PluginClass)) update-fn)
      (set! (.-buildDecorations (.-prototype ^js PluginClass)) build-decorations-fn)
      (.fromClass ViewPlugin PluginClass #js {:decorations (fn [instance] (.-decorations instance))}))))

;; Returns a fallback extension array when Tree-Sitter is unavailable.
;; Currently empty (no highlighting); could extend with regex-based highlighting for basic support.
(defn fallback-extension [lang-config]
  (if (= (:fallback-highlighter lang-config) "regex")
    #js [] ;; Placeholder: Implement simple regex decoration plugin if needed.
    #js []))

;; Initializes syntax highlighting for the current language in the editor view.
;; Asynchronously initializes Tree-Sitter, loads the language grammar if not cached,
;; and reconfigures the syntax compartment with the highlighting plugin.
;; Falls back to no highlighting on failure. All async operations are awaited to ensure proper sequencing.
;; Wrapped in try-catch for error resilience, logging issues without flooding the console.
(defn init-syntax [view]
  (go
    (try
      (let [[init-tag init-cause] (<! (promise->chan @ts-init-promise))]
        (if (= init-tag :ok)
          (log/info "Tree-Sitter initialized successfully")
          (do
            (log/error "Tree-Sitter initialization failed:" init-cause)
            (throw (ex-info "Tree-Sitter initialization failed" {:cause init-cause})))))
      (let [lang-key (get-in @app-db [:workspace :files (get-in @app-db [:workspace :active-file]) :language])
            lang-config (get-in @app-db [:languages lang-key])]
        (when-not lang-key
          (log/debug "No active language; skipping syntax init")
          (throw (ex-info "No active language" {})))
        (let [wasm-path (:grammar-wasm lang-config)
              query-str (:highlight-query lang-config)
              cached (get @languages lang-key)
              lang (or (:lang cached)
                       (when wasm-path
                         (let [[tag val] (<! (promise->chan (Language.load wasm-path)))]
                           (if (= tag :ok)
                             (do
                               (log/info "Successfully loaded language WASM for" lang-key "from" wasm-path)
                               val)
                             (do
                               (log/error "Failed to load language WASM for" lang-key ":" (.-message val))
                               nil)))))
              parser (or (:parser cached)
                         (when lang
                           (doto (new Parser)
                             (.setLanguage lang))))
              query (when (and lang query-str)
                      (try
                        (let [q (new (.-Query TreeSitter) lang query-str)]
                          (log/info "Successfully created highlight query for" lang-key)
                          q)
                        (catch js/Error e
                          (log/error "Failed to create highlight query for" lang-key ":" (.-message e))
                          nil)))] ;; Create query here to catch errors early.
          (when (and (not cached) lang parser)
            (swap! languages assoc lang-key {:lang lang :parser parser})
            (log/info "Tree-Sitter loaded and configured for" lang-key))
          (if (and lang parser query)
            (let [plugin (create-ts-plugin parser lang query)]
              (if plugin
                (do
                  (log/debug "Reconfiguring syntax compartment with Tree-Sitter plugin for" lang-key)
                  (.dispatch view #js {:effects (.reconfigure syntax-compartment #js [plugin])}))
                (do
                  (log/warn "Failed to create Tree-Sitter plugin for" lang-key "; falling back to basic mode")
                  (.dispatch view #js {:effects (.reconfigure syntax-compartment (fallback-extension lang-config))}))))
            (do
              (log/debug "No Tree-Sitter grammar, parser, or valid query for" lang-key "; falling back to basic mode")
              (.dispatch view #js {:effects (.reconfigure syntax-compartment (fallback-extension lang-config))})))))
      (catch js/Error e
        (log/error "Failed to initialize Tree-Sitter:" (.-message e) "- falling back to basic mode")
        (.dispatch view #js {:effects (.reconfigure syntax-compartment #js [])})))))
