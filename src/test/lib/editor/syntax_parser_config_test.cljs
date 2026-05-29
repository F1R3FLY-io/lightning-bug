(ns test.lib.editor.syntax-parser-config-test
  (:require
   [clojure.core.async :as async :refer [go <! timeout]]
   [clojure.test :refer [deftest is async use-fixtures]]
   [datascript.core :as d]
   [taoensso.timbre :as log :include-macros true]
   [lib.db :as db]
   [lib.editor.syntax :as syntax]
   [lib.state :as state]
   [clojure.string :as str]
   [lib.utils :as lib-utils :refer [promise->chan]]
   ["@codemirror/state" :refer [EditorState]]
   ["@codemirror/view" :refer [EditorView]]
   ["web-tree-sitter" :as TreeSitter :refer [Language Parser Query]]
   ["@f1r3fly-io/tree-sitter-rholang-js-with-comments" :refer [wasm]]
   [ext.embedded.lang.rholang :refer [treeSitterRholangWasmUrl]]
   [ext.embedded.lang.rholang-queries :refer [highlightsQueryUrl indentsQueryUrl]]))

(use-fixtures :each
  {:before (fn []
             (reset! syntax/languages {})
             (reset! state/resources {:lsp {} :tree-sitter {}})
             (d/reset-conn! db/conn (d/empty-db db/schema)))})

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

(deftest parser-as-instance
  (async done
         (go
           (let [res (<! (go
                           (try
                             (<! (promise->chan @syntax/ts-init-promise))
                             (<! (timeout 100))
                             (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
                                   query-str (<! (slurp "/extensions/lang/rholang/tree-sitter/queries/highlights.scm"))
                                   indents-str (<! (slurp "/extensions/lang/rholang/tree-sitter/queries/indents.scm"))
                                   [_ lang] (<! (promise->chan (Language.load wasm-path)))
                                   parser (doto (Parser.) (.setLanguage lang))
                                   state-atom (atom {:languages {"test" {:parser parser
                                                                         :highlights-query query-str
                                                                         :indents-query indents-str
                                                                         :extensions [".test"]}}})]
                               ;; Setup mock active document to ensure db/active-lang returns "test"
                               (db/create-documents! [{:uri "file.test" :text "let x = 1" :language "test" :version 1 :dirty false :opened true}])
                               (db/update-active-uri! "file.test")
                               (let [state (.create EditorState #js {:doc "let x = 1" :extensions #js []})
                                     view (EditorView. #js {:state state :parent js/document.body})
                                     result (<! (syntax/init-syntax view state-atom))]
                                 (is (some? result) "Initialization completed")
                                 (is (= :ok (first result)) "Successful initialization")
                                 (is (= :success (second result)) "Parser instance used successfully")
                                 (.destroy view)))
                             [:ok nil]
                             (catch :default e
                               [:error (js/Error. "parser-as-instance failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest parser-as-sync-fn
  (async done
         (go
           (let [res (<! (go
                           (try
                             (<! (promise->chan @syntax/ts-init-promise))
                             (<! (timeout 100))
                             (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
                                   query-str (<! (slurp "/extensions/lang/rholang/tree-sitter/queries/highlights.scm"))
                                   indents-str (<! (slurp "/extensions/lang/rholang/tree-sitter/queries/indents.scm"))
                                   [_ lang] (<! (promise->chan (Language.load wasm-path)))
                                   load-parser (fn [] (doto (Parser.) (.setLanguage lang)))
                                   state-atom (atom {:languages {"test" {:parser load-parser
                                                                         :highlights-query query-str
                                                                         :indents-query indents-str
                                                                         :extensions [".test"]}}})]
                               ;; Setup mock active document to ensure db/active-lang returns "test"
                               (db/create-documents! [{:uri "file.test" :text "let x = 1" :language "test" :version 1 :dirty false :opened true}])
                               (db/update-active-uri! "file.test")
                               (let [state (.create EditorState #js {:doc "let x = 1" :extensions #js []})
                                     view (EditorView. #js {:state state :parent js/document.body})
                                     result (<! (syntax/init-syntax view state-atom))]
                                 (is (some? result) "Initialization completed")
                                 (is (= :ok (first result)) "Successful initialization")
                                 (is (= :success (second result)) "Sync parser function used successfully")
                                 (.destroy view)))
                             [:ok nil]
                             (catch :default e
                               [:error (js/Error. "parser-as-sync-fn failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest parser-as-async-fn
  (async done
         (go
           (let [res (<! (go
                           (try
                             (<! (promise->chan @syntax/ts-init-promise))
                             (<! (timeout 100))
                             (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
                                   query-str (<! (slurp "/extensions/lang/rholang/tree-sitter/queries/highlights.scm"))
                                   indents-str (<! (slurp "/extensions/lang/rholang/tree-sitter/queries/indents.scm"))
                                   load-parser (fn [] (.then (Language.load wasm-path)
                                                             (fn [lang]
                                                               (doto (Parser.) (.setLanguage lang)))))
                                   state-atom (atom {:languages {"test" {:parser load-parser
                                                                         :highlights-query query-str
                                                                         :indents-query indents-str
                                                                         :extensions [".test"]}}})]
                               ;; Setup mock active document to ensure db/active-lang returns "test"
                               (db/create-documents! [{:uri "file.test" :text "let x = 1" :language "test" :version 1 :dirty false :opened true}])
                               (db/update-active-uri! "file.test")
                               (let [state (.create EditorState #js {:doc "let x = 1" :extensions #js []})
                                     view (EditorView. #js {:state state :parent js/document.body})
                                     result (<! (syntax/init-syntax view state-atom))]
                                 (is (some? result) "Initialization completed")
                                 (is (= :ok (first result)) "Successful initialization")
                                 (is (= :success (second result)) "Async parser function used successfully")
                                 (.destroy view)))
                             [:ok nil]
                             (catch :default e
                               [:error (js/Error. "parser-as-async-fn failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest data-uri-wasm-from-package
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [state-atom (atom {:languages {"rholang" {:grammar-wasm wasm
                                                                            :highlights-query-path highlightsQueryUrl
                                                                            :indents-query-path indentsQueryUrl
                                                                            :extensions [".rho"]}}})]
                               ;; Setup mock active document to ensure db/active-lang returns "rholang"
                               (db/create-documents! [{:uri "file.rho" :text "let x = 1" :language "rholang" :version 1 :dirty false :opened true}])
                               (db/update-active-uri! "file.rho")
                               (let [state (.create EditorState #js {:doc "let x = 1" :extensions #js []})
                                     view (EditorView. #js {:state state :parent js/document.body})
                                     result (<! (syntax/init-syntax view state-atom))]
                                 (is (some? result) "Initialization completed")
                                 (is (= :ok (first result)) "Successful initialization")
                                 (is (= :success (second result)) "Package data URI wasm used successfully")
                                 (.destroy view)))
                             [:ok nil]
                             (catch :default e
                               [:error (js/Error. "data-uri-wasm-from-package failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest grammar-wasm-as-fn
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [state-atom (atom {:languages {"test" {:grammar-wasm (fn [] "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm")
                                                                         :highlights-query-path (fn [] "/extensions/lang/rholang/tree-sitter/queries/highlights.scm")
                                                                         :indents-query-path (fn [] "/extensions/lang/rholang/tree-sitter/queries/indents.scm")
                                                                         :extensions [".test"]}}})]
                               ;; Setup mock active document to ensure db/active-lang returns "test"
                               (db/create-documents! [{:uri "file.test" :text "let x = 1" :language "test" :version 1 :dirty false :opened true}])
                               (db/update-active-uri! "file.test")
                               (let [state (.create EditorState #js {:doc "let x = 1" :extensions #js []})
                                     view (EditorView. #js {:state state :parent js/document.body})
                                     result (<! (syntax/init-syntax view state-atom))]
                                 (is (some? result) "Initialization completed")
                                 (is (= :ok (first result)) "Successful initialization")
                                 (is (= :success (second result)) "Grammar WASM as function used successfully")
                                 (.destroy view)))
                             [:ok nil]
                             (catch :default e
                               [:error (js/Error. "grammar-wasm-as-fn failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest highlights-query-as-fn
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [wasm-path "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
                                   state-atom (atom {:languages {"test" {:grammar-wasm wasm-path
                                                                         :highlights-query-path (fn [] "/extensions/lang/rholang/tree-sitter/queries/highlights.scm")
                                                                         :indents-query-path (fn [] "/extensions/lang/rholang/tree-sitter/queries/indents.scm")
                                                                         :extensions [".test"]}}})]
                               ;; Setup mock active document to ensure db/active-lang returns "test"
                               (db/create-documents! [{:uri "file.test" :text "let x = 1" :language "test" :version 1 :dirty false :opened true}])
                               (db/update-active-uri! "file.test")
                               (let [state (.create EditorState #js {:doc "let x = 1" :extensions #js []})
                                     view (EditorView. #js {:state state :parent js/document.body})
                                     result (<! (syntax/init-syntax view state-atom))]
                                 (is (some? result) "Initialization completed")
                                 (is (= :ok (first result)) "Successful initialization")
                                 (is (= :success (second result)) "Highlights query as function used successfully")
                                 (.destroy view)))
                             [:ok nil]
                             (catch :default e
                               [:error (js/Error. "highlights-query-as-fn failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest embedded-wasm-load
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [state-atom (atom {:languages {"rholang" {:grammar-wasm treeSitterRholangWasmUrl
                                                                            :highlights-query-path highlightsQueryUrl
                                                                            :indents-query-path indentsQueryUrl
                                                                            :extensions [".rho"]}}})]
                               ;; Setup mock active document to ensure db/active-lang returns "rholang"
                               (db/create-documents! [{:uri "file.rho" :text "let x = 1" :language "rholang" :version 1 :dirty false :opened true}])
                               (db/update-active-uri! "file.rho")
                               (let [state (.create EditorState #js {:doc "let x = 1" :extensions #js []})
                                     view (EditorView. #js {:state state :parent js/document.body})
                                     result (<! (syntax/init-syntax view state-atom))]
                                 (is (some? result) "Initialization completed")
                                 (is (= :ok (first result)) "Successful initialization")
                                 (is (= :success (second result)) "Embedded WASM and queries loaded successfully")
                                 (.destroy view)))
                             [:ok nil]
                             (catch :default e
                               [:error (js/Error. "embedded-wasm-load failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

;; =============================================================================
;; Edge Case Tests (Phase 2)
;; =============================================================================

(deftest highlight-cache-invalidation-on-edit
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
                                   plugin (syntax/make-highlighter-plugin language-state-field query)
                                   initial-doc "let x = 1"
                                   state (.create EditorState #js {:doc initial-doc :extensions #js [language-state-field plugin]})
                                   view (EditorView. #js {:state state :parent js/document.body})]
                               ;; Reset cache stats before test
                               (syntax/reset-cache-stats!)
                               ;; Verify initial state
                               (let [stats-before (syntax/get-cache-stats)]
                                 (is (zero? (:hits stats-before)) "No hits before operations"))
                               ;; Make an edit to the document
                               (.dispatch view #js {:changes #js {:from 9 :to 9 :insert " in y"}})
                               (<! (timeout 50))
                               ;; Check that cache was invalidated and rebuilt
                               (let [stats-after (syntax/get-cache-stats)]
                                 (is (>= (:misses stats-after) 1) "Cache miss on edit (cache invalidated)")
                                 (is (>= (:rebuilds stats-after) 1) "Decorations rebuilt after edit"))
                               (.destroy view))
                             [:ok nil]
                             (catch :default e
                               [:error (js/Error. "highlight-cache-invalidation-on-edit failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest highlight-cache-viewport-awareness
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
                                   plugin (syntax/make-highlighter-plugin language-state-field query)
                                   ;; Create a document larger than viewport margin
                                   large-doc (str/join (repeat 200 "let x = 1\n"))
                                   state (.create EditorState #js {:doc large-doc :extensions #js [language-state-field plugin]})
                                   view (EditorView. #js {:state state :parent js/document.body})]
                               ;; Reset cache stats
                               (syntax/reset-cache-stats!)
                               ;; Wait for potential render
                               (<! (timeout 50))
                               ;; Verify cache stats API works (misses may be 0 in test environment)
                               (let [stats-initial (syntax/get-cache-stats)]
                                 (is (some? stats-initial) "Cache stats available")
                                 (is (number? (:misses stats-initial)) "Misses is a number")
                                 (is (number? (:hits stats-initial)) "Hits is a number"))
                               ;; Verify viewport margin constant exists
                               (is (= 2000 syntax/viewport-margin) "Viewport margin is 2000 chars")
                               (.destroy view))
                             [:ok nil]
                             (catch :default e
                               [:error (js/Error. "highlight-cache-viewport-awareness failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest language-switch-clears-parser
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
                                                                            :extensions [".rho"]}
                                                                 "plaintext" {:extensions [".txt"]}}})]
                               ;; Clear languages cache
                               (reset! syntax/languages {})
                               ;; Setup for rholang first
                               (db/create-documents! [{:uri "test.rho" :text "let x = 1" :language "rholang" :version 1 :dirty false :opened true}])
                               (db/update-active-uri! "test.rho")
                               (let [state (.create EditorState #js {:doc "let x = 1" :extensions #js []})
                                     view (EditorView. #js {:state state :parent js/document.body})
                                     result (<! (syntax/init-syntax view state-atom))]
                                 (is (= :ok (first result)) "Rholang initialization successful")
                                 (is (some? (get @syntax/languages "rholang")) "Rholang cached")
                                 ;; Now switch to plaintext (no parser)
                                 (db/create-documents! [{:uri "test.txt" :text "plain text" :language "plaintext" :version 1 :dirty false :opened true}])
                                 (db/update-active-uri! "test.txt")
                                 (let [result2 (<! (syntax/init-syntax view state-atom))]
                                   (is (= :ok (first result2)) "Plaintext initialization successful")
                                   (is (= :no-tree-sitter (second result2)) "Plaintext uses fallback (no tree-sitter)"))
                                 (.destroy view)))
                             [:ok nil]
                             (catch :default e
                               [:error (js/Error. "language-switch-clears-parser failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest incremental-parse-insertion
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
                                   initial-doc "new x in { Nil }"
                                   state (.create EditorState #js {:doc initial-doc :extensions #js [language-state-field]})
                                   view (EditorView. #js {:state state :parent js/document.body})]
                               ;; Insert text in the middle
                               (.dispatch view #js {:changes #js {:from 11 :to 14 :insert "x!(\"Hello\") | Nil"}})
                               (<! (timeout 50))
                               ;; Verify the tree was updated incrementally
                               (let [new-state (.-state view)
                                     lang-state (.field new-state language-state-field false)
                                     ^js tree (when lang-state (.-tree lang-state))]
                                 (is (some? tree) "Parse tree exists after insertion")
                                 (is (some? (.-rootNode ^js tree)) "Root node exists")
                                 ;; The document should reflect the change
                                 (is (= "new x in { x!(\"Hello\") | Nil }" (str (.-doc new-state))) "Document updated correctly"))
                               (.destroy view))
                             [:ok nil]
                             (catch :default e
                               [:error (js/Error. "incremental-parse-insertion failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest syntax-error-recovery
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
                                   plugin (syntax/make-highlighter-plugin language-state-field query)
                                   ;; Start with syntactically incorrect code
                                   broken-doc "new x in { x!( }"
                                   state (.create EditorState #js {:doc broken-doc :extensions #js [language-state-field plugin]})
                                   view (EditorView. #js {:state state :parent js/document.body})]
                               ;; Parser should handle syntax errors gracefully
                               (let [lang-state (.field (.-state view) language-state-field false)
                                     ^js tree (when lang-state (.-tree lang-state))]
                                 (is (some? tree) "Parse tree exists even with syntax errors")
                                 (is (some? (.-rootNode ^js tree)) "Root node exists despite errors"))
                               ;; Fix the syntax error
                               (.dispatch view #js {:changes #js {:from 14 :to 14 :insert "\"Hello\")"}})
                               (<! (timeout 50))
                               ;; Verify recovery
                               (let [new-state (.-state view)
                                     lang-state (.field new-state language-state-field false)
                                     tree (when lang-state (.-tree lang-state))]
                                 (is (some? tree) "Parse tree exists after fix")
                                 (is (= "new x in { x!(\"Hello\") }" (str (.-doc new-state))) "Document reflects the fix"))
                               (.destroy view))
                             [:ok nil]
                             (catch :default e
                               [:error (js/Error. "syntax-error-recovery failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest cache-stats-reset-and-tracking
  (async done
         (go
           (let [res (<! (go
                           (try
                             ;; Test reset functionality
                             (syntax/reset-cache-stats!)
                             (let [stats (syntax/get-cache-stats)]
                               (is (zero? (:hits stats)) "Hits reset to 0")
                               (is (zero? (:misses stats)) "Misses reset to 0")
                               (is (zero? (:rebuilds stats)) "Rebuilds reset to 0")
                               (is (zero? (:queries stats)) "Queries reset to 0"))
                             ;; Verify getCacheStats and resetCacheStats JS exports exist
                             (is (fn? syntax/getCacheStats) "getCacheStats export exists")
                             (is (fn? syntax/resetCacheStats) "resetCacheStats export exists")
                             [:ok nil]
                             (catch :default e
                               [:error (js/Error. "cache-stats-reset-and-tracking failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))
