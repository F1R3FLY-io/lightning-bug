(ns test.lib.editor.syntax-test
  (:require
   [clojure.core.async :as async :refer [go <! timeout]]
   [clojure.test :refer [deftest is async use-fixtures]]
   [taoensso.timbre :as log :include-macros true]
   [lib.db :as db]
   [lib.editor.syntax :as syntax :refer [promise->chan]]
   [lib.utils :as u]
   ["@codemirror/state" :refer [ChangeSet EditorState]]
   ["@codemirror/view" :refer [EditorView]]
   ["web-tree-sitter" :as TreeSitter :refer [Language Parser Query]]))

(use-fixtures :each
  {:before (fn [] (reset! syntax/languages {}))})

(defn slurp
  "Reads the contents of a file into a string."
  [path]
  (go
    (let [[resp-tag resp] (<! (promise->chan (js/fetch path)))]
      (if (= resp-tag :ok)
        (let [[text-tag text] (<! (promise->chan (.text resp)))]
          (if (= text-tag :ok)
            text
            (do
              (log/error "Failed to read file from" path ":" (.-message text))
              nil)))
        (do
          (log/error "Failed to read file from" path ":" (.-message resp))
          nil)))))

(deftest highlighter-plugin-valid
  (async done
         (go
           (let [res (<! (go
                           (try
                             (<! (promise->chan @syntax/ts-init-promise))
                             (<! (timeout 100))
                             (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
                                   query-str (<! (slurp "/extensions/lang/rholang/tree-sitter/queries/highlights.scm"))
                                   [_ lang] (<! (promise->chan (Language.load wasm-path)))
                                   parser (doto (new Parser) (.setLanguage lang))
                                   query (new (.-Query TreeSitter) lang query-str)
                                   language-state-field (syntax/make-language-state parser)
                                   plugin (syntax/make-highlighter-plugin language-state-field query)]
                               (is (some? plugin) "Plugin created successfully"))
                             [:ok nil]
                             (catch :default e
                               [:error (js/Error. "highlighter-plugin-valid failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (u/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest highlighter-plugin-invalid
  (async done
         (go
           (let [res (<! (go
                           (try
                             (<! (promise->chan @syntax/ts-init-promise))
                             (<! (timeout 100))
                             (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
                                   [_ lang] (<! (promise->chan (Language.load wasm-path)))
                                   parser (doto (new Parser) (.setLanguage lang))
                                   language-state-field (syntax/make-language-state parser)
                                   plugin (syntax/make-highlighter-plugin language-state-field nil)]
                               (is (some? plugin) "Plugin still created for invalid inputs"))
                             [:ok nil]
                             (catch :default e
                               [:error (js/Error. "highlighter-plugin-invalid failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (u/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest highlighter-plugin-missing-state-field
  (async done
         (go
           (let [res (<! (go
                           (try
                             (<! (promise->chan @syntax/ts-init-promise))
                             (<! (timeout 100))
                             (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
                                   query-str (<! (slurp "/extensions/lang/rholang/tree-sitter/queries/highlights.scm"))
                                   [_ lang] (<! (syntax/promise->chan (Language.load wasm-path)))
                                   parser (doto (new Parser) (.setLanguage lang))
                                   query (new (.-Query TreeSitter) lang query-str)
                                   ;; Create a dummy state field that is not attached.
                                   dummy-field (syntax/make-language-state parser)
                                   plugin (syntax/make-highlighter-plugin dummy-field query)
                                   ;; Create state without the field.
                                   state (.create EditorState #js {:doc "test content" :extensions #js [plugin]})
                                   view (EditorView. #js {:state state :parent js/document.body})
                                   plugin-instance (.plugin view plugin)
                                   decorations (when plugin-instance (.-decorations plugin-instance))]
                               (is (some? plugin-instance) "Plugin instance created")
                               (is (some? decorations) "Decorations computed without crash")
                               (.destroy view))
                             [:ok nil]
                             (catch :default e
                               [:error (js/Error. "highlighter-plugin-missing-state-field failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (u/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(defn tree->root-node
  "Helper to extract root node with type hint preserved."
  [^js tree]
  (.rootNode tree))

(deftest incremental-parse
  (async done
         (go
           (let [res (<! (go
                           (try
                             (<! (promise->chan @syntax/ts-init-promise))
                             (<! (timeout 100))
                             (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
                                   [_ lang] (<! (promise->chan (Language.load wasm-path)))
                                   parser (doto (new Parser) (.setLanguage lang))
                                   initial-str "let x = 1"
                                   language-state-field (syntax/make-language-state parser)
                                   state (.create EditorState #js {:doc initial-str :extensions #js [language-state-field]})
                                   old-value (.field state language-state-field)
                                   change-spec #js {:from 9 :to 9 :insert " in y"}
                                   changes (.of ChangeSet change-spec (.-length (.-doc state)))
                                   mock-tr #js {:docChanged true
                                                :changes changes
                                                :startState state
                                                :state state}
                                   update-fn (.-update language-state-field) ; May be nil in fallback cases (e.g., no Tree-Sitter)
                                   ]
                               (if update-fn
                                 (update-fn old-value mock-tr)
                                 old-value) ; Return old-value if no update-fn (fallback behavior)
                               [:ok nil])
                             (catch :default e
                               [:error (js/Error. "incremental-parse failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (u/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest empty-document-handling
  (async done
         (go
           (let [res (<! (go
                           (try
                             (<! (promise->chan @syntax/ts-init-promise))
                             (<! (timeout 100))
                             (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
                                   query-str (<! (slurp "/extensions/lang/rholang/tree-sitter/queries/highlights.scm"))
                                   [_ lang] (<! (syntax/promise->chan (Language.load wasm-path)))
                                   parser (doto (new Parser) (.setLanguage lang))
                                   query (new (.-Query TreeSitter) lang query-str)
                                   initial-doc ""
                                   language-state-field (syntax/make-language-state parser)
                                   plugin (syntax/make-highlighter-plugin language-state-field query)
                                   state (.create EditorState #js {:doc initial-doc :extensions #js [language-state-field plugin]})
                                   view (EditorView. #js {:state state :parent js/document.body})
                                   plugin-instance (.plugin view plugin)
                                   decorations (when plugin-instance (.-decorations plugin-instance))]
                               (is (some? plugin-instance) "Plugin instance created for empty doc")
                               (is (some? decorations) "Decorations computed without crash")
                               (.destroy view))
                             [:ok nil]
                             (catch :default e
                               [:error (js/Error. "empty-document-handling failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (u/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest language-key-consistency
  (async done
         (go
           (let [res (<! (go
                           (try
                             (<! (promise->chan @syntax/ts-init-promise))
                             (<! (timeout 100))
                             (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
                                   query-str (<! (slurp "/extensions/lang/rholang/tree-sitter/queries/highlights.scm"))
                                   state-atom (atom {:language "rholang" :languages {"rholang" {:grammar-wasm wasm-path
                                                                                                :highlight-query query-str
                                                                                                :extensions [".rho"]}}})]
                               ;; Setup mock active document to ensure db/active-lang returns "rholang"
                               (db/create-documents! [{:uri "test.rho" :text "" :language "rholang" :version 1 :dirty false :opened true}])
                               (db/update-active-uri! "test.rho")
                               (let [state (.create EditorState #js {:doc "let x = 1" :extensions #js []})
                                     view (EditorView. #js {:state state :parent js/document.body})
                                     result (<! (syntax/init-syntax view state-atom))]
                                 (is (some? (get-in @state-atom [:languages "rholang"])) "Language config found with string key")
                                 (is (nil? (get-in @state-atom [:languages :rholang])) "No keyword key exists")
                                 (.destroy view)
                                 (if (= :ok (first result))
                                   (do
                                     (is (= :success (second result)) "Tree-Sitter plugin applied successfully")
                                     [:ok nil])
                                   [:error (js/Error. "language-key-consistency failed" #js {:cause (second result)})])))
                             (catch :default e
                               [:error (js/Error. "language-key-consistency failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (u/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest keyword-key-fallback
  (async done
         (go
           (let [res (<! (go
                           (try
                             (<! (promise->chan @syntax/ts-init-promise))
                             (<! (timeout 100))
                             (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
                                   query-str (<! (slurp "/extensions/lang/rholang/tree-sitter/queries/highlights.scm"))
                                   state-atom (atom {:languages {:rholang {:grammar-wasm wasm-path
                                                                           :highlight-query query-str
                                                                           :extensions [".rho"]}}})
                                   state (.create EditorState #js {:doc "let x = 1" :extensions #js []})
                                   view (EditorView. #js {:state state :parent js/document.body})
                                   result (<! (syntax/init-syntax view state-atom))]
                               (log/debug "@state-atom =>" @state-atom)
                               (log/debug "(get-in @state-atom [:languages]) =>" (get-in @state-atom [:languages]))
                               (is (some? (get-in @state-atom [:languages "rholang"])) "Keyword key normalized to string")
                               (is (nil? (get-in @state-atom [:languages :rholang])) "Keyword key removed")
                               (.destroy view)
                               (if (= :ok (first result))
                                 (do
                                   (is (= :success (second result)) "Tree-Sitter plugin applied successfully")
                                   [:ok nil])
                                 [:error (js/Error. "keyword-key-fallback failed" #js {:cause (second result)})]))
                             (catch :default e
                               [:error (js/Error. "keyword-key-fallback failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (u/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest wasm-load-failure
  (async done
         (go
           (let [res (<! (go
                           (try
                             (<! (promise->chan @syntax/ts-init-promise))
                             (<! (timeout 100))
                             (let [wasm-path "/invalid/path/to/tree-sitter-rholang.wasm"
                                   state-atom (atom {:language "rholang"
                                                     :languages {"rholang" {:grammar-wasm wasm-path
                                                                            :highlight-query-path "/invalid/path/highlights.scm"
                                                                            :extensions [".rho"]}}})
                                   state (.create EditorState #js {:doc "let x = 1" :extensions #js []})
                                   view (EditorView. #js {:state state :parent js/document.body})
                                   result (<! (syntax/init-syntax view state-atom))]
                               (is (nil? (get @syntax/languages "rholang")) "Language not cached on failure")
                               (.destroy view)
                               (if (= :ok (first result))
                                 (do
                                   (is (= :missing-components (second result)) "Falls back for invalid WASM path")
                                   [:ok nil])
                                 [:error (js/Error. "wasm-load-failure failed" #js {:cause (second result)})]))
                             (catch :default e
                               [:error (js/Error. "wasm-load-failure failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (u/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest query-load-failure
  (async done
         (go
           (let [res (<! (go
                           (try
                             (<! (promise->chan @syntax/ts-init-promise))
                             (<! (timeout 100))
                             (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
                                   state-atom (atom {:language "rholang"
                                                     :languages {"rholang" {:grammar-wasm wasm-path
                                                                            :highlight-query-path "/invalid/path/highlights.scm"
                                                                            :extensions [".rho"]}}})
                                   state (.create EditorState #js {:doc "let x = 1" :extensions #js []})
                                   view (EditorView. #js {:state state :parent js/document.body})
                                   result (<! (syntax/init-syntax view state-atom))]
                               (is (nil? (get @syntax/languages "rholang")) "Language not cached on query failure")
                               (.destroy view)
                               (if (= :ok (first result))
                                 (do
                                   (is (= :missing-components (second result)) "Falls back for invalid query path")
                                   [:ok nil])
                                 [:error (js/Error. "query-load-failure failed" #js {:cause (second result)})]))
                             (catch :default e
                               [:error (js/Error. "query-load-failure failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (u/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest indents-query-load
  (async done
         (go
           (let [res (<! (go
                           (try
                             (<! (promise->chan @syntax/ts-init-promise))
                             (<! (timeout 100))
                             (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
                                   highlight-str (<! (slurp "/extensions/lang/rholang/tree-sitter/queries/highlights.scm"))
                                   indents-str (<! (slurp "/extensions/lang/rholang/tree-sitter/queries/indents.scm"))
                                   state-atom (atom {:language "rholang"
                                                     :languages {"rholang" {:grammar-wasm wasm-path
                                                                            :highlight-query highlight-str
                                                                            :indents-query indents-str
                                                                            :indent-size 2
                                                                            :extensions [".rho"]}}})
                                   state (.create EditorState #js {:doc "{ Nil }" :extensions #js []})
                                   view (EditorView. #js {:state state :parent js/document.body})
                                   result (<! (syntax/init-syntax view state-atom))]
                               (.destroy view)
                               (if (= :ok (first result))
                                 (do
                                   (is (= :success (second result)) "Initialization succeeds with indents query")
                                   [:ok nil])
                                 [:error (js/Error. "indents-query-load failed" #js {:cause (second result)})]))
                             (catch :default e
                               [:error (js/Error. "indents-query-load failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (u/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest indentation-calculation
  (async done
         (go
           (let [res (<! (go
                           (try
                             (<! (promise->chan @syntax/ts-init-promise))
                             (<! (timeout 100))
                             (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
                                   indents-str (<! (slurp "/extensions/lang/rholang/tree-sitter/queries/indents.scm"))
                                   [_ lang] (<! (syntax/promise->chan (Language.load wasm-path)))
                                   parser (doto (new Parser) (.setLanguage lang))
                                   indents-query (new Query lang indents-str)
                                   doc "{ Nil }"
                                   language-state-field (syntax/make-language-state parser)
                                   state (.create EditorState #js {:doc doc
                                                                   :extensions #js [language-state-field]})
                                   pos 2 ; at 'N' of Nil, inside block
                                   ctx #js {:state state :pos pos :unit "  "}]
                               (is (= 2 (syntax/calculate-indent ctx pos indents-query 2 language-state-field)) "Indents inside block by 2 spaces")
                               [:ok nil])
                             (catch :default e
                               [:error (js/Error. "indentation-calculation failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (u/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest indentation-after-opening-brace
  (async done
         (go
           (let [res (<! (go
                           (try
                             (<! (promise->chan @syntax/ts-init-promise))
                             (<! (timeout 100))
                             (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
                                   indents-str (<! (slurp "/extensions/lang/rholang/tree-sitter/queries/indents.scm"))
                                   [_ lang] (<! (syntax/promise->chan (Language.load wasm-path)))
                                   parser (doto (new Parser) (.setLanguage lang))
                                   indents-query (new Query lang indents-str)
                                   doc "new x in {}"
                                   language-state-field (syntax/make-language-state parser)
                                   state (.create EditorState #js {:doc doc
                                                                   :extensions #js [language-state-field]})
                                   pos 9 ; just after '{'
                                   ctx #js {:state state :pos pos :unit "  "}]
                               (is (= 2 (syntax/calculate-indent ctx pos indents-query 2 language-state-field)) "Indents after opening brace by 2 spaces")
                               [:ok nil])
                             (catch :default e
                               [:error (js/Error. "indentation-after-opening-brace failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (u/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

;; FIXME
;; (deftest indentation-after-par
;;   (async done
;;          (go
;;            (let [res (<! (go
;;                            (try
;;                              (<! (promise->chan @syntax/ts-init-promise))
;;                              (<! (timeout 100))
;;                              (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
;;                                    indents-str (<! (slurp "/extensions/lang/rholang/tree-sitter/queries/indents.scm"))
;;                                    [_ lang] (<! (syntax/promise->chan (Language.load wasm-path)))
;;                                    parser (doto (new Parser) (.setLanguage lang))
;;                                    indents-query (new Query lang indents-str)
;;                                    doc "new x in { x!(\"Hello\") | }"
;;                                    language-state-field (syntax/make-language-state parser)
;;                                    state (.create EditorState #js {:doc doc
;;                                                                    :extensions #js [language-state-field]})
;;                                    pos (+ (str/index-of doc "|") 1) ; just after '|' in 'x!(\"Hello\") |'
;;                                    ctx #js {:state state :pos pos :unit "  "}]
;;                                (is (= 2 (syntax/calculate-indent ctx pos indents-query 2 language-state-field)) "Indents after '|' by 2 spaces, matching block scope")
;;                                [:ok nil])
;;                              (catch :default e
;;                                [:error (js/Error. "indentation-after-par failed" #js {:cause e})]))))]
;;              (when (= :error (first res))
;;                (let [err (second res)
;;                      err-msg (str "Test failed with error: " (pr-str err))]
;;                  (u/log-error-with-cause err)
;;                  (is false err-msg)))
;;              (done)))))

;; FIXME
;; (deftest indentation-after-second-par
;;   (async done
;;          (go
;;            (let [res (<! (go
;;                            (try
;;                              (<! (promise->chan @syntax/ts-init-promise))
;;                              (<! (timeout 100))
;;                              (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
;;                                    indents-str (<! (slurp "/extensions/lang/rholang/tree-sitter/queries/indents.scm"))
;;                                    [_ lang] (<! (syntax/promise->chan (Language.load wasm-path)))
;;                                    parser (doto (new Parser) (.setLanguage lang))
;;                                    indents-query (new Query lang indents-str)
;;                                    doc "new x in { x!(\"Hello\") | x!(\"World\") | }"
;;                                    language-state-field (syntax/make-language-state parser)
;;                                    state (.create EditorState #js {:doc doc
;;                                                                    :extensions #js [language-state-field]})
;;                                    pos (+ (str/last-index-of doc "|") 1) ; just after second '|'
;;                                    ctx #js {:state state :pos pos :unit "  "}]
;;                                (is (= 2 (syntax/calculate-indent ctx pos indents-query 2 language-state-field)) "Indents after second '|' by 2 spaces, aligning with previous processes")
;;                                [:ok nil])
;;                              (catch :default e
;;                                [:error (js/Error. "indentation-after-second-par failed" #js {:cause e})]))))]
;;              (when (= :error (first res))
;;                (let [err (second res)
;;                      err-msg (str "Test failed with error: " (pr-str err))]
;;                  (u/log-error-with-cause err)
;;                  (is false err-msg)))
;;              (done)))))

;; FIXME
;; (deftest indentation-demo-example
;;   (async done
;;          (go
;;            (let [res (<! (go
;;                            (try
;;                              (<! (promise->chan @syntax/ts-init-promise))
;;                              (<! (timeout 100))
;;                              (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
;;                                    indents-str (<! (slurp "/extensions/lang/rholang/tree-sitter/queries/indents.scm"))
;;                                    [_ lang] (<! (syntax/promise->chan (Language.load wasm-path)))
;;                                    parser (doto (new Parser) (.setLanguage lang))
;;                                    indents-query (new Query lang indents-str)
;;                                    doc "new x in { x!(\"Hello\") | Nil }"
;;                                    language-state-field (syntax/make-language-state parser)
;;                                    state (.create EditorState #js {:doc doc
;;                                                                    :extensions #js [language-state-field]})
;;                                    pos 25 ; approximate position after '|' in the example code
;;                                    ctx #js {:state state :pos pos :unit "  "}]
;;                                (is (= 2 (syntax/calculate-indent ctx pos indents-query 2 language-state-field)) "Indents after '|' in demo example by 2 spaces")
;;                                [:ok nil])
;;                              (catch :default e
;;                                [:error (js/Error. "indentation-demo-example failed" #js {:cause e})]))))]
;;              (when (= :error (first res))
;;                (let [err (second res)
;;                      err-msg (str "Test failed with error: " (pr-str err))]
;;                  (u/log-error-with-cause err)
;;                  (is false err-msg)))
;;              (done)))))
