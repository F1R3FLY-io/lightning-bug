(ns test.lib.editor.syntax-test
  (:require
   ["fs" :as fs]
   [clojure.core.async :as async :refer [go <! timeout]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is async use-fixtures]]
   [clojure.test.check :as tc]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [taoensso.timbre :as log :include-macros true]
   [lib.editor.syntax :as syntax]
   ["@codemirror/state" :refer [ChangeSet EditorState]]
   ["@codemirror/view" :refer [EditorView]]
   ["web-tree-sitter" :as TreeSitter :refer [Language Parser Query]]))

(defn slurp
  "Reads the contents of a file into a string."
  [file-path]
  (.readFileSync fs file-path "utf-8"))

(use-fixtures :each
  (fn [f]
    (async done
      (go
        (try
          (<! (syntax/promise->chan @syntax/ts-init-promise))
          (<! (timeout 100))
          (catch js/Error e
            (log/error "Failed to initialize Tree-Sitter in test fixture:" (.-message e))))
        (f)
        (done)))))

(deftest highlighter-plugin-valid
  (async done
    (go
      (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
            query-str (slurp "/extensions/lang/rholang/tree-sitter/queries/highlights.scm")
            [_ lang] (<! (syntax/promise->chan (Language.load wasm-path)))
            parser (doto (new Parser) (.setLanguage lang))
            query (new (.-Query TreeSitter) lang query-str)
            language-state-field (syntax/make-language-state parser)
            plugin (syntax/make-highlighter-plugin language-state-field query)]
        (is (some? query) "Query created successfully")
        (is (some? plugin) "Plugin created")
        (done)))))

(deftest highlighter-plugin-invalid
  (async done
    (go
      (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
            [_ lang] (<! (syntax/promise->chan (Language.load wasm-path)))
            parser (doto (new Parser) (.setLanguage lang))
            language-state-field (syntax/make-language-state parser)
            plugin (syntax/make-highlighter-plugin language-state-field nil)]
        (is (nil? plugin) "Returns nil for invalid inputs")
        (done)))))

(def gen-query (gen/let [captures (gen/vector (gen/elements (keys syntax/style-map)) 1 10)]
  (str/join "\n" (map #(str "(_" % ") @" %) captures))))

(def gen-doc gen/string-alphanumeric)

(def init-prop
  (prop/for-all [query-str gen-query
                 doc gen-doc]
    (async done
      (let [result-chan (async/chan)]
        (go
          (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
                [_ lang] (<! (syntax/promise->chan (Language.load wasm-path)))
                parser (doto (new Parser) (.setLanguage lang))
                state-atom (atom {:language "rholang" :languages {"rholang" {:highlight-query query-str}}})
                state (.create EditorState #js {:doc doc :extensions #js []})
                view (new EditorView #js {:state state})
                result (<! (syntax/init-syntax view state-atom))]
            (.destroy view)
            (async/put! result-chan (= result :success)))
          (done))
        (<! result-chan)
        true))))

(deftest init-syntax-property
  (let [result (tc/quick-check 10 init-prop {:seed 42})]
    (is (:result result))))

(def invalid-query-prop
  (prop/for-all [invalid-query (gen/such-that seq gen/string-alphanumeric)]
    (async done
      (go
        (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
              [_ lang] (<! (syntax/promise->chan (Language.load wasm-path)))
              parser (doto (new Parser) (.setLanguage lang))
              state-atom (atom {:language "rholang" :languages {"rholang" {:highlight-query invalid-query}}})
              state (.create EditorState #js {:doc "" :extensions #js []})
              view (new EditorView #js {:state state})
              result (<! (syntax/init-syntax view state-atom))]
          (.destroy view)
          (is (= result :missing-components) "Falls back for invalid query")
          (done)))
      true)))

(deftest incremental-parse
  (async done
    (go
      (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
            query-str (slurp "/extensions/lang/rholang/tree-sitter/queries/highlights.scm")
            [_ lang] (<! (syntax/promise->chan (Language.load wasm-path)))
            parser (doto (new Parser) (.setLanguage lang))
            query (new (.-Query TreeSitter) lang query-str)
            initial-str "let x = 1"
            language-state-field (syntax/make-language-state parser)
            highlighter-ext (syntax/make-highlighter-plugin language-state-field query)
            state (.create EditorState #js {:doc initial-str :extensions #js [language-state-field highlighter-ext]})
            initial-doc (.-doc state)
            view (EditorView. #js {:state state :parent js/document.body})
            plugin-instance (.plugin view highlighter-ext)
            old-tree (.-tree plugin-instance)
            change-spec #js {:from 9 :to 9 :insert " in y"}
            changes (.of ChangeSet change-spec (.-length initial-doc))
            new-str (str (subs initial-str 0 9) " in y" (subs initial-str 9))
            new-doc (.-doc (.create EditorState #js {:doc new-str}))
            mock-update #js {:docChanged true
                             :changes changes
                             :startState state
                             :state #js {:doc new-doc}
                             :view view}
            _ ((.-update (.-prototype (.-constructor plugin-instance))) mock-update plugin-instance)
            new-tree (.-tree plugin-instance)]
        (is (not= old-tree new-tree) "Tree updated")
        (is (= (.toString new-doc) (.-text ^js (.-rootNode ^js new-tree))) "New tree matches new doc")
        (.destroy view)
        (done)))))

(deftest empty-document-handling
  (async done
    (go
      (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
            query-str (slurp "/extensions/lang/rholang/tree-sitter/queries/highlights.scm")
            [_ lang] (<! (syntax/promise->chan (Language.load wasm-path)))
            parser (doto (new Parser) (.setLanguage lang))
            query (new (.-Query TreeSitter) lang query-str)
            initial-doc ""
            language-state-field (syntax/make-language-state parser)
            highlighter-ext (syntax/make-highlighter-plugin language-state-field query)
            state (.create EditorState #js {:doc initial-doc :extensions #js [language-state-field highlighter-ext]})
            view (EditorView. #js {:state state :parent js/document.body})
            plugin-instance (.plugin view highlighter-ext)
            decorations (.-decorations plugin-instance)]
        (is (some? plugin-instance) "Plugin instance created for empty doc")
        (is (some? decorations) "Decorations computed without crash")
        (.destroy view)
        (done)))))

(deftest language-key-consistency
  (async done
    (go
      (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
            query-str (slurp "/extensions/lang/rholang/tree-sitter/queries/highlights.scm")
            [_ lang] (<! (syntax/promise->chan (Language.load wasm-path)))
            parser (doto (new Parser) (.setLanguage lang))
            state-atom (atom {:language "rholang"
                              :languages {"rholang" {:grammar-wasm wasm-path
                                                     :highlight-query query-str
                                                     :extensions [".rho"]}}})
            state (.create EditorState #js {:doc "let x = 1" :extensions #js []})
            view (new EditorView #js {:state state})
            result (<! (syntax/init-syntax view state-atom))]
        (is (some? (get-in @state-atom [:languages "rholang"])) "Language config found with string key")
        (is (nil? (get-in @state-atom [:languages :rholang])) "No keyword key exists")
        (is (= result :success) "Tree-Sitter plugin applied successfully")
        (.destroy view)
        (done)))))

(deftest keyword-key-fallback
  (async done
    (go
      (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
            query-str (slurp "/extensions/lang/rholang/tree-sitter/queries/highlights.scm")
            [_ lang] (<! (syntax/promise->chan (Language.load wasm-path)))
            parser (doto (new Parser) (.setLanguage lang))
            state-atom (atom {:language "rholang"
                              :languages {:rholang {:grammar-wasm wasm-path
                                                    :highlight-query query-str
                                                    :extensions [".rho"]}}})
            state (.create EditorState #js {:doc "let x = 1" :extensions #js []})
            view (new EditorView #js {:state state})
            result (<! (syntax/init-syntax view state-atom))]
        (is (some? (get-in @state-atom [:languages "rholang"])) "Keyword key normalized to string")
        (is (nil? (get-in @state-atom [:languages :rholang])) "Keyword key removed")
        (is (= result :success) "Tree-Sitter plugin applied successfully")
        (.destroy view)
        (done)))))

(deftest wasm-load-failure
  (async done
    (go
      (let [wasm-path "/invalid/path/to/tree-sitter-rholang.wasm"
            query-str (slurp "/extensions/lang/rholang/tree-sitter/queries/highlights.scm")
            state-atom (atom {:language "rholang"
                              :languages {"rholang" {:grammar-wasm wasm-path
                                                     :highlight-query query-str
                                                     :extensions [".rho"]}}})
            state (.create EditorState #js {:doc "let x = 1" :extensions #js []})
            view (new EditorView #js {:state state})
            result (<! (syntax/init-syntax view state-atom))]
        (is (= result :missing-components) "Falls back for invalid WASM path")
        (is (nil? (get @syntax/languages "rholang")) "Language not cached on failure")
        (.destroy view)
        (done)))))

(deftest query-load-failure
  (async done
    (go
      (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
            state-atom (atom {:language "rholang"
                              :languages {"rholang" {:grammar-wasm wasm-path
                                                     :highlight-query-path "/invalid/path/highlights.scm"
                                                     :extensions [".rho"]}}})
            state (.create EditorState #js {:doc "let x = 1" :extensions #js []})
            view (new EditorView #js {:state state})
            result (<! (syntax/init-syntax view state-atom))]
        (is (= result :missing-components) "Falls back for invalid query path")
        (is (nil? (get @syntax/languages "rholang")) "Language not cached on query failure")
        (.destroy view)
        (done)))))

(deftest indents-query-load
  (async done
    (go
      (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
            highlight-str (slurp "/extensions/lang/rholang/tree-sitter/queries/highlights.scm")
            indents-str (slurp "/extensions/lang/rholang/tree-sitter/queries/indents.scm")
            [_ lang] (<! (syntax/promise->chan (Language.load wasm-path)))
            parser (doto (new Parser) (.setLanguage lang))
            state-atom (atom {:language "rholang"
                              :languages {"rholang" {:grammar-wasm wasm-path
                                                     :highlight-query highlight-str
                                                     :indents-query indents-str
                                                     :indent-size 2
                                                     :extensions [".rho"]}}})
            state (.create EditorState #js {:doc "{ Nil }" :extensions #js []})
            view (new EditorView #js {:state state})
            result (<! (syntax/init-syntax view state-atom))]
        (is (= result :success) "Initialization succeeds with indents query")
        (.destroy view)
        (done)))))

(deftest indentation-calculation
  (async done
    (go
      (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
            indents-str (slurp "/extensions/lang/rholang/tree-sitter/queries/indents.scm")
            [_ lang] (<! (syntax/promise->chan (Language.load wasm-path)))
            parser (doto (new Parser) (.setLanguage lang))
            indents-query (new Query lang indents-str)
            doc "{ Nil }"
            tree (.parse parser doc)
            language-state-field (syntax/make-language-state parser)
            state (.create EditorState #js {:doc doc
                                            :extensions #js [language-state-field]})
            pos 2 ; at 'N' of Nil, inside block
            ctx #js {:state state :pos pos :unit "  "}]
        (is (= 2 (syntax/calculate-indent ctx pos indents-query 2 language-state-field)) "Indents inside block by 2 spaces")
        (done)))))

(deftest indentation-after-opening-brace
  (async done
    (go
      (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
            indents-str (slurp "/extensions/lang/rholang/tree-sitter/queries/indents.scm")
            [_ lang] (<! (syntax/promise->chan (Language.load wasm-path)))
            parser (doto (new Parser) (.setLanguage lang))
            indents-query (new Query lang indents-str)
            doc "new x in {}"
            tree (.parse parser doc)
            language-state-field (syntax/make-language-state parser)
            state (.create EditorState #js {:doc doc
                                            :extensions #js [language-state-field]})
            pos 9 ; just after '{'
            ctx #js {:state state :pos pos :unit "  "}]
        (is (= 2 (syntax/calculate-indent ctx pos indents-query 2 language-state-field)) "Indents after opening brace by 2 spaces")
        (done)))))

(deftest indentation-after-par
  (async done
    (go
      (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
            indents-str (slurp "/extensions/lang/rholang/tree-sitter/queries/indents.scm")
            [_ lang] (<! (syntax/promise->chan (Language.load wasm-path)))
            parser (doto (new Parser) (.setLanguage lang))
            indents-query (new Query lang indents-str)
            doc "new x in { x!(\"Hello\") | }"
            tree (.parse parser doc)
            language-state-field (syntax/make-language-state parser)
            state (.create EditorState #js {:doc doc
                                            :extensions #js [language-state-field]})
            pos (+ (str/index-of doc "|") 1) ; just after '|' in 'x!(\"Hello\") |'
            ctx #js {:state state :pos pos :unit "  "}]
        (is (= 2 (syntax/calculate-indent ctx pos indents-query 2 language-state-field)) "Indents after '|' by 2 spaces, matching block scope")
        (done)))))

(deftest indentation-after-second-par
  (async done
    (go
      (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
            indents-str (slurp "/extensions/lang/rholang/tree-sitter/queries/indents.scm")
            [_ lang] (<! (syntax/promise->chan (Language.load wasm-path)))
            parser (doto (new Parser) (.setLanguage lang))
            indents-query (new Query lang indents-str)
            doc "new x in { x!(\"Hello\") | x!(\"World\") | }"
            tree (.parse parser doc)
            language-state-field (syntax/make-language-state parser)
            state (.create EditorState #js {:doc doc
                                            :extensions #js [language-state-field]})
            pos (+ (str/last-index-of doc "|") 1) ; just after second '|' 
            ctx #js {:state state :pos pos :unit "  "}]
        (is (= 2 (syntax/calculate-indent ctx pos indents-query 2 language-state-field)) "Indents after second '|' by 2 spaces, aligning with previous processes")
        (done)))))
