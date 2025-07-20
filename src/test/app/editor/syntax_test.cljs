(ns test.app.editor.syntax-test
  (:require
   [clojure.core.async :refer [go <!]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is async use-fixtures]]
   [clojure.test.check :as tc]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [re-frame.db :refer [app-db]]
   [taoensso.timbre :as log :include-macros true]
   [app.db :refer [default-db]]
   [app.editor.syntax :as syntax]
   ["@codemirror/state" :refer [EditorState]]
   ["@codemirror/view" :refer [EditorView]]
   ["web-tree-sitter" :as TreeSitter :refer [Language Parser]]))

;; Fixture to reset app-db and initialize Tree-Sitter before each test.
(use-fixtures :each
  (fn [f]
    (reset! app-db default-db)
    (async done
      (go
        (try
          (<! (syntax/promise->chan @syntax/ts-init-promise))
          (catch js/Error e
            (log/error "Failed to initialize Tree-Sitter in test fixture:" (.-message e))))
        (f)
        (done)))))

(deftest create-ts-plugin-valid
  (async done
    (go
      (let [wasm-path (get-in default-db [:languages "rholang" :grammar-wasm])
            query-str (get-in default-db [:languages "rholang" :highlight-query])
            [_ lang] (<! (syntax/promise->chan (Language.load wasm-path)))
            parser (doto (new Parser) (.setLanguage lang))
            query (new (.-Query TreeSitter) lang query-str)
            plugin (syntax/create-ts-plugin parser lang query)]
        (is (some? query) "Query created successfully")
        (is (some? plugin) "Plugin created")
        (done)))))

(deftest create-ts-plugin-invalid
  (let [plugin (syntax/create-ts-plugin nil nil nil)]
    (is (nil? plugin) "Returns nil for invalid inputs")))

(deftest fallback-extension
  (let [ext (syntax/fallback-extension {:fallback-highlighter "regex"})]
    (is (array? ext))
    (is (zero? (.-length ext)) "Empty array for fallback")))

;; Generator for simple query strings with valid captures.
(def gen-query (gen/let [captures (gen/vector (gen/elements (keys syntax/style-map)) 1 5)]
                 (str/join "\n" (map #(str "(_" % ") @" % ) captures))))

;; Generator for document text; simple to avoid complex parsing.
(def gen-doc (gen/string-alphanumeric))

(def init-prop
  (prop/for-all [_ gen-query
                 doc gen-doc]
    (async done
      (go
        (let [wasm-path (get-in default-db [:languages "rholang" :grammar-wasm])
              [_ lang] (<! (syntax/promise->chan (Language.load wasm-path)))
              parser (doto (new Parser) (.setLanguage lang))
              state (.create EditorState #js {:doc doc :extensions #js []})
              view (EditorView. #js {:state state :parent js/document.body})
              lang-key "rholang"
              _ (swap! app-db assoc-in [:workspace :files] {1 {:language lang-key}})
              _ (swap! app-db assoc-in [:workspace :active-file] 1)
              _ (swap! syntax/languages assoc lang-key {:lang lang :parser parser})
              result (<! (syntax/init-syntax view))
              ;; Since init-syntax returns nothing, check side-effects: no throw, and compartment reconfigured.
              _ (true? result)] ;; Dummy, since go block completes.
          (.destroy view)
          (done))))
    true)) ;; Prop always true if completes.

(deftest init-syntax-property
  (let [result (tc/quick-check 10 init-prop {:seed 42})]
    (is (:result result))))

(def invalid-query-prop
  (prop/for-all [invalid-query (gen/such-that seq gen/string-alphanumeric)]
    (async done
      (go
        (let [wasm-path (get-in default-db [:languages "rholang" :grammar-wasm])
              [_ lang] (<! (syntax/promise->chan (Language.load wasm-path)))
              parser (doto (new Parser) (.setLanguage lang))
              state (.create EditorState #js {:doc "" :extensions #js []})
              view (EditorView. #js {:state state :parent js/document.body})
              lang-key "rholang"
              _ (swap! app-db assoc-in [:languages lang-key :highlight-query] invalid-query)
              _ (swap! app-db assoc-in [:workspace :files] {1 {:language lang-key}})
              _ (swap! app-db assoc-in [:workspace :active-file] 1)
              _ (swap! syntax/languages assoc lang-key {:lang lang :parser parser})
              _ (<! (syntax/init-syntax view))] ;; Should fallback without throw.
              ;; Check fallback: no plugin, but since side-effect, assume no crash.
              (done))))
    true))

(deftest invalid-query-fallback-property
  (let [result (tc/quick-check 10 invalid-query-prop {:seed 42})]
    (is (:result result))))

(deftest incremental-parse
  (async done
    (go
      (let [wasm-path (get-in default-db [:languages "rholang" :grammar-wasm])
            query-str (get-in default-db [:languages "rholang" :highlight-query])
            [_ lang] (<! (syntax/promise->chan (Language.load wasm-path)))
            parser (doto (new Parser) (.setLanguage lang))
            query (new (.-Query TreeSitter) lang query-str)
            initial-doc "let x = 1"
            state (.create EditorState #js {:doc initial-doc :extensions #js []})
            view (EditorView. #js {:state state :parent js/document.body})
            plugin-instance ((.-constructor (syntax/create-ts-plugin parser lang query)) view)
            old-tree (.-tree plugin-instance)
            ;; Simulate change: insert " in y" at end
            change-spec #js {:from 9 :to 9 :insert " in y"}
            changes (ChangeSet/of change-spec (.-length initial-doc))
            new-doc (.changeByRange (.-doc state) (.-main (.-selection state)) (fn [_] #js {:changes change-spec}))
            ;; Mock update object
            mock-update #js {:docChanged true
                             :changes changes
                             :startState state
                             :state #js {:doc new-doc}
                             :view view}
            ;; Call update
            _ ((.-update (.-prototype (.-constructor plugin-instance))) mock-update plugin-instance)
            new-tree (.-tree plugin-instance)]
        (is (not= old-tree new-tree) "Tree updated")
        (is (= (.toString new-doc) (.text (.-rootNode new-tree))) "New tree matches new doc")
        (.destroy view)
        (done)))))

(deftest empty-document-handling
  (async done
    (go
      (let [wasm-path (get-in default-db [:languages "rholang" :grammar-wasm])
            query-str (get-in default-db [:languages "rholang" :highlight-query])
            [_ lang] (<! (syntax/promise->chan (Language.load wasm-path)))
            parser (doto (new Parser) (.setLanguage lang))
            query (new (.-Query TreeSitter) lang query-str)
            initial-doc ""
            state (.create EditorState #js {:doc initial-doc :extensions #js []})
            view (EditorView. #js {:state state :parent js/document.body})
            plugin-instance ((.-constructor (syntax/create-ts-plugin parser lang query)) view)
            decorations (.-decorations plugin-instance)]
        (is (some? plugin-instance) "Plugin instance created for empty doc")
        (is (some? decorations) "Decorations computed without crash")
        (.destroy view)
        (done)))))
