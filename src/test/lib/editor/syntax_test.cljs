(ns test.lib.editor.syntax-test
  (:require
   [clojure.core.async :as async :refer [go <! timeout]]
   [clojure.test :refer [deftest is async use-fixtures]]
   [taoensso.timbre :as log :include-macros true]
   [lib.db :as db]
   [lib.workspace :as ws]
   [lib.editor.syntax :as syntax]
   [lib.state :as state]
   [lib.utils :as lib-utils :refer [promise->chan]]
   ["@codemirror/state" :refer [ChangeSet EditorState]]
   ["@codemirror/view" :refer [EditorView]]
   ["web-tree-sitter" :as TreeSitter :refer [Language Parser Query]]))

(use-fixtures :each
  {:before (fn []
             (reset! syntax/languages {})
             (reset! state/resources {:lsp {} :tree-sitter {}})
             (ws/reset-workspace! @ws/default-workspace))})

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
                                   parser (doto (Parser.) (.setLanguage lang))
                                   query (Query. lang query-str)
                                   language-state-field (syntax/make-language-state parser)
                                   plugin (syntax/make-highlighter-plugin language-state-field query)]
                               (is (some? plugin) "Plugin created successfully"))
                             [:ok nil]
                             (catch :default e
                               [:error (js/Error. "highlighter-plugin-valid failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
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
                                   parser (doto (Parser.) (.setLanguage lang))
                                   language-state-field (syntax/make-language-state parser)
                                   plugin (syntax/make-highlighter-plugin language-state-field nil)]
                               (is (some? plugin) "Plugin still created for invalid inputs"))
                             [:ok nil]
                             (catch :default e
                               [:error (js/Error. "highlighter-plugin-invalid failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
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
                                   [_ lang] (<! (promise->chan (Language.load wasm-path)))
                                   parser (doto (Parser.) (.setLanguage lang))
                                   query (Query. lang query-str)
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
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest incremental-parse
  (async done
         (go
           (let [res (<! (go
                           (try
                             (<! (promise->chan @syntax/ts-init-promise))
                             (<! (timeout 100))
                             (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
                                   [_ lang] (<! (promise->chan (Language.load wasm-path)))
                                   parser (doto (Parser.) (.setLanguage lang))
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
                 (lib-utils/log-error-with-cause err)
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
                                   [_ lang] (<! (promise->chan (Language.load wasm-path)))
                                   parser (doto (Parser.) (.setLanguage lang))
                                   query (Query. lang query-str)
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
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest language-key-consistency
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
                                   query-str (<! (slurp "/extensions/lang/rholang/tree-sitter/queries/highlights.scm"))
                                   indents-str (<! (slurp "/extensions/lang/rholang/tree-sitter/queries/indents.scm"))
                                   state-atom (atom {:languages {"rholang" {:grammar-wasm wasm-path
                                                                            :highlights-query query-str
                                                                            :indents-query indents-str
                                                                            :extensions [".rho"]}}})]
                               ;; Setup mock active document to ensure db/active-lang returns "rholang"
                               (db/create-documents! (ws/default-conn) [{:uri "test.rho" :text "content" :language "rholang" :version 1 :dirty true :opened false}])
                               (db/update-active-uri! (ws/default-conn) "test.rho")
                               (swap! state-atom assoc :active-uri "test.rho")
                               (let [state (.create EditorState #js {:doc "let x = 1" :extensions #js []})
                                     view (EditorView. #js {:state state :parent js/document.body})
                                     result (<! (syntax/init-syntax view state-atom (ws/default-conn)))]
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
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest keyword-key-fallback
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
                                   query-str (<! (slurp "/extensions/lang/rholang/tree-sitter/queries/highlights.scm"))
                                   indents-str (<! (slurp "/extensions/lang/rholang/tree-sitter/queries/indents.scm"))
                                   state-atom (atom {:languages {:rholang {:grammar-wasm wasm-path
                                                                           :highlights-query query-str
                                                                           :indents-query indents-str
                                                                           :extensions [".rho"]}}})]
                               ;; Setup mock active document to ensure db/active-lang returns "rholang"
                               (db/create-documents! (ws/default-conn) [{:uri "demo.rho" :text "let x = 1" :language "rholang" :version 1 :dirty false :opened true}])
                               (db/update-active-uri! (ws/default-conn) "demo.rho")
                               (swap! state-atom assoc :active-uri "demo.rho")
                               (let [state (.create EditorState #js {:doc "let x = 1" :extensions #js []})
                                     view (EditorView. #js {:state state :parent js/document.body})
                                     result (<! (syntax/init-syntax view state-atom (ws/default-conn)))]
                                 (is (some? (get-in @state-atom [:languages "rholang"])) "Keyword key normalized to string")
                                 (is (nil? (get-in @state-atom [:languages :rholang])) "Keyword key removed")
                                 (.destroy view)
                                 (if (= :ok (first result))
                                   (do
                                     (is (= :success (second result)) "Tree-Sitter plugin applied successfully")
                                     [:ok nil])
                                   [:error (js/Error. "keyword-key-fallback failed" #js {:cause (second result)})])))
                             (catch :default e
                               [:error (js/Error. "keyword-key-fallback failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest wasm-load-failure
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [wasm-path "/invalid/path/to/tree-sitter-rholang.wasm"
                                   state-atom (atom {:languages {"rholang" {:grammar-wasm wasm-path
                                                                            :highlights-query-path "/invalid/path/highlights.scm"
                                                                            :extensions [".rho"]}}})]
                               ;; Setup mock active document to ensure db/active-lang returns "rholang"
                               (db/create-documents! (ws/default-conn) [{:uri "file.rho" :text "let x = 1" :language "rholang" :version 1 :dirty false :opened true}])
                               (db/update-active-uri! (ws/default-conn) "file.rho")
                               (swap! state-atom assoc :active-uri "file.rho")
                               (let [state (.create EditorState #js {:doc "let x = 1" :extensions #js []})
                                     view (EditorView. #js {:state state :parent js/document.body})
                                     result (<! (syntax/init-syntax view state-atom (ws/default-conn)))]
                                 (is (nil? (get @syntax/languages "rholang")) "Language not cached on failure")
                                 (.destroy view)
                                 (if (= :ok (first result))
                                   (do
                                     (is (= :missing-components (second result)) "Falls back for invalid WASM path")
                                     [:ok nil])
                                   [:error (js/Error. "wasm-load-failure failed" #js {:cause (second result)})])))
                             (catch :default e
                               [:error (js/Error. "wasm-load-failure failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest query-load-failure
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
                                   state-atom (atom {:languages {"rholang" {:grammar-wasm wasm-path
                                                                            :highlights-query-path "/invalid/path/highlights.scm"
                                                                            :extensions [".rho"]}}})]
                               ;; Setup mock active document to ensure db/active-lang returns "rholang"
                               (db/create-documents! (ws/default-conn) [{:uri "file.rho" :text "let x = 1" :language "rholang" :version 1 :dirty false :opened true}])
                               (db/update-active-uri! (ws/default-conn) "file.rho")
                               (swap! state-atom assoc :active-uri "file.rho")
                               (let [state (.create EditorState #js {:doc "let x = 1" :extensions #js []})
                                     view (EditorView. #js {:state state :parent js/document.body})
                                     result (<! (syntax/init-syntax view state-atom (ws/default-conn)))]
                                 (is (nil? (get @syntax/languages "rholang")) "Language not cached on query failure")
                                 (.destroy view)
                                 (if (= :ok (first result))
                                   (do
                                     (is (= :missing-components (second result)) "Falls back for invalid query path")
                                     [:ok nil])
                                   [:error (js/Error. "query-load-failure failed" #js {:cause (second result)})])))
                             (catch :default e
                               [:error (js/Error. "query-load-failure failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest indents-query-load
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
                                   highlight-str (<! (slurp "/extensions/lang/rholang/tree-sitter/queries/highlights.scm"))
                                   indents-str (<! (slurp "/extensions/lang/rholang/tree-sitter/queries/indents.scm"))
                                   state-atom (atom {:languages {"rholang" {:grammar-wasm wasm-path
                                                                            :highlights-query highlight-str
                                                                            :indents-query indents-str
                                                                            :indent-size 2
                                                                            :extensions [".rho"]}}})]
                               ;; Setup mock active document to ensure db/active-lang returns "rholang"
                               (db/create-documents! (ws/default-conn) [{:uri "file.rho" :text "{ Nil }" :language "rholang" :version 1 :dirty false :opened true}])
                               (db/update-active-uri! (ws/default-conn) "file.rho")
                               (swap! state-atom assoc :active-uri "file.rho")
                               (let [state (.create EditorState #js {:doc "{ Nil }" :extensions #js []})
                                     view (EditorView. #js {:state state :parent js/document.body})
                                     result (<! (syntax/init-syntax view state-atom (ws/default-conn)))]
                                 (.destroy view)
                                 (if (= :ok (first result))
                                   (do
                                     (is (= :success (second result)) "Initialization succeeds with indents query")
                                     [:ok nil])
                                   [:error (js/Error. "indents-query-load failed" #js {:cause (second result)})])))
                             (catch :default e
                               [:error (js/Error. "indents-query-load failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
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
                                   [_ lang] (<! (promise->chan (Language.load wasm-path)))
                                   parser (doto (Parser.) (.setLanguage lang))
                                   indents-query (Query. lang indents-str)
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
                 (lib-utils/log-error-with-cause err)
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
                                   [_ lang] (<! (promise->chan (Language.load wasm-path)))
                                   parser (doto (Parser.) (.setLanguage lang))
                                   indents-query (Query. lang indents-str)
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
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

;; Re-enabled (tech-debt Phase 4): the par operator `|` is a `@branch` alignment
;; point (indents.scm: `(par "|" @branch)`), so the next parallel process aligns
;; with the indent of the line where the par construct begins. The original tests
;; were disabled because they used single-line docs (construct on the indent-0
;; line → align to 0) yet asserted 2, and used stale aliases. Fixed with multi-line
;; docs where the construct sits at indent 2, and current APIs.
(deftest indentation-after-par
  (async done
         (go
           (let [res (<! (go
                           (try
                             (<! (promise->chan @syntax/ts-init-promise))
                             (<! (timeout 100))
                             (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
                                   indents-str (<! (slurp "/extensions/lang/rholang/tree-sitter/queries/indents.scm"))
                                   [_ lang] (<! (promise->chan (Language.load wasm-path)))
                                   parser (doto (Parser.) (.setLanguage lang))
                                   indents-query (Query. lang indents-str)
                                   doc "new x in {\n  x!(\"Hello\") |\n}"
                                   language-state-field (syntax/make-language-state parser)
                                   state (.create EditorState #js {:doc doc
                                                                   :extensions #js [language-state-field]})
                                   pos (inc (.indexOf doc "|")) ; just after '|'
                                   ctx #js {:state state :pos pos :unit "  "}]
                               (is (= 2 (syntax/calculate-indent ctx pos indents-query 2 language-state-field)) "Aligns the next parallel process with the par construct (2 spaces)")
                               [:ok nil])
                             (catch :default e
                               [:error (js/Error. "indentation-after-par failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest indentation-after-second-par
  (async done
         (go
           (let [res (<! (go
                           (try
                             (<! (promise->chan @syntax/ts-init-promise))
                             (<! (timeout 100))
                             (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
                                   indents-str (<! (slurp "/extensions/lang/rholang/tree-sitter/queries/indents.scm"))
                                   [_ lang] (<! (promise->chan (Language.load wasm-path)))
                                   parser (doto (Parser.) (.setLanguage lang))
                                   indents-query (Query. lang indents-str)
                                   doc "new x in {\n  x!(\"Hello\") |\n  x!(\"World\") |\n}"
                                   language-state-field (syntax/make-language-state parser)
                                   state (.create EditorState #js {:doc doc
                                                                   :extensions #js [language-state-field]})
                                   pos (inc (.lastIndexOf doc "|")) ; just after the second '|'
                                   ctx #js {:state state :pos pos :unit "  "}]
                               (is (= 2 (syntax/calculate-indent ctx pos indents-query 2 language-state-field)) "Aligns subsequent parallel processes (2 spaces)")
                               [:ok nil])
                             (catch :default e
                               [:error (js/Error. "indentation-after-second-par failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest indentation-demo-example
  (async done
         (go
           (let [res (<! (go
                           (try
                             (<! (promise->chan @syntax/ts-init-promise))
                             (<! (timeout 100))
                             (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
                                   indents-str (<! (slurp "/extensions/lang/rholang/tree-sitter/queries/indents.scm"))
                                   [_ lang] (<! (promise->chan (Language.load wasm-path)))
                                   parser (doto (Parser.) (.setLanguage lang))
                                   indents-query (Query. lang indents-str)
                                   doc "new x in {\n  x!(\"Hello\") | Nil\n}"
                                   language-state-field (syntax/make-language-state parser)
                                   state (.create EditorState #js {:doc doc
                                                                   :extensions #js [language-state-field]})
                                   pos (inc (.indexOf doc "|")) ; just after '|'
                                   ctx #js {:state state :pos pos :unit "  "}]
                               (is (= 2 (syntax/calculate-indent ctx pos indents-query 2 language-state-field)) "Aligns the process after '|' with the par construct (2 spaces)")
                               [:ok nil])
                             (catch :default e
                               [:error (js/Error. "indentation-demo-example failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))
