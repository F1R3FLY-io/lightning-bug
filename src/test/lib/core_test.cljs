(ns test.lib.core-test
  (:require
   [clojure.core.async :as async :refer [go <! timeout]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is async use-fixtures]]
   [clojure.test.check :as tc]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [datascript.core :as d]
   [reagent.core :as r]
   ["@codemirror/commands" :refer [indentMore indentLess]]
   ["@codemirror/search" :refer [getSearchQuery setSearchQuery openSearchPanel]]
   ["@codemirror/state" :refer [EditorState]]
   ["@codemirror/view" :refer [EditorView]]
   ["react" :as react]
   ["react-dom/client" :as rdclient]
   [lib.core :refer [Editor]]
   [ext.lang.rholang :refer [language-config]]
   [lib.db :as db]
   [lib.editor.syntax :as syntax]
   [lib.state :refer [normalize-editor-config]]
   [lib.utils :as lib-utils]
   [test.lib.mock-lsp :refer [parse-message with-mock-lsp]]
   [taoensso.timbre :as log]))

(use-fixtures :once
  {:before (fn []
             (set! (.-onbeforeunload js/window) (fn [] "Prevent test reload")))
   :after (fn []
            (set! (.-onbeforeunload js/window) nil))})

(use-fixtures :each
  {:before (fn []
             @syntax/ts-init-promise
             (reset! syntax/languages {})
             (d/reset-conn! db/conn (d/empty-db db/schema)))})

(defn ref->editor
  ([^js ref]
   (.-current ref)))

(defn editor->close-document!
  ([^js editor]
   (.closeDocument editor))
  ([^js editor uri]
   (.closeDocument editor uri)))

(defn editor->save-document!
  ([^js editor]
   (.saveDocument editor))
  ([^js editor uri]
   (.saveDocument editor uri)))

(defn editor->rename-document!
  ([^js editor new-file-or-uri]
   (.renameDocument editor new-file-or-uri))
  ([^js editor new-file-or-uri old-file-or-uri]
   (.renameDocument editor new-file-or-uri old-file-or-uri)))

(defn editor->open-document!
  ([^js editor uri text lang]
   (.openDocument editor uri text lang))
  ([^js editor uri text lang make-active?]
   (.openDocument editor uri text lang make-active?)))

(defn editor->cursor
  ([^js editor]
   (.getCursor editor)))

(defn editor->set-cursor!
  ([^js editor loc]
   (.setCursor editor loc)))

(defn editor->clear-highlight!
  ([^js editor]
   (.clearHighlight editor)))

(defn editor->highlight-range!
  ([^js editor ^js from-js ^js to-js]
   (.highlightRange editor from-js to-js)))

(defn editor->activate-document!
  ([^js editor uri]
   (.activateDocument editor uri)))

(defn editor->set-selection!
  ([^js editor ^js from-js ^js to-js]
   (.setSelection editor from-js to-js)))

(defn editor->set-text!
  ([^js editor text]
   (.setText editor text))
  ([^js editor text uri]
   (.setText editor text uri)))

(defn editor->center-on-range!
  ([^js editor ^js from-js ^js to-js]
   (.centerOnRange editor from-js to-js)))

(defn editor->text
  ([^js editor]
   (.getText editor))
  ([^js editor uri]
   (.getText editor uri)))

(defn editor->events [^js editor]
  (.getEvents editor))

(defn editor->state [^js editor]
  (.getState editor))

(defn editor->file-path
  ([^js editor]
   (.getFilePath editor))
  ([^js editor uri]
   (.getFilePath editor uri)))

(defn editor->file-uri
  ([^js editor]
   (.getFileUri editor))
  ([^js editor uri]
   (.getFileUri editor uri)))

(defn editor->diagnostics
  ([^js editor]
   (.getDiagnostics editor))
  ([^js editor uri]
   (.getDiagnostics editor uri)))

(defn editor->symbols
  ([^js editor]
   (.getSymbols editor))
  ([^js editor uri]
   (.getSymbols editor uri)))

(defn editor->db [^js editor]
  (.getDb editor))

(defn editor->search-term [^js editor]
  (.getSearchTerm editor))

(defn editor-query
  ([^js editor query]
   (.query editor query))
  ([^js editor query params]
   (.query editor query params)))

(defn wait-for
  [pred timeout-ms]
  (go
    (try
      (let [start (js/Date.now)]
        (loop []
          (if (pred)
            [:ok true]
            (if (> (- (js/Date.now) start) timeout-ms)
              [:ok false]
              (do
                (<! (timeout 10))
                (recur))))))
      (catch :default e
        [:error (js/Error. (str "(wait-for pred " timeout-ms ") failed") #js {:cause e})]))))

(defn wait-for-ready [editor-ref timeout-ms]
  (wait-for #(some-> (ref->editor editor-ref) .isReady) timeout-ms))

(defn wait-for-uri [uri timeout-ms]
  (wait-for #(db/document-id-by-uri uri) timeout-ms))

(defn wait-for-event [events-atom event-type timeout-ms]
  (go
    (try
      (let [start (js/Date.now)]
        (loop []
          (cond
            (some #(= event-type (:type %)) @events-atom) [:ok true]
            (> (- (js/Date.now) start) timeout-ms) [:ok false]
            :else (do
                    (<! (timeout 10))
                    (recur)))))
      (catch :default e
        [:error (js/Error. (str "(wait-for-event events-atom " event-type " " timeout-ms ") failed") #js {:cause e})]))))

(defn flush-render []
  (r/flush))

(defn mount-component [container comp-props]
  (let [root (rdclient/createRoot container)]
    (.render root (react/createElement Editor (clj->js comp-props)))
    (flush-render)
    root))

(defn wrap-flush [f]
  (f)
  (flush-render))

(defn cleanup-container [^js/HTMLDivElement container]
  (when #_{:splint/disable [style/prefer-clj-string]} (.contains js/document.body container)
        (js/document.body.removeChild container)))

(deftest editor-renders
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [^js/HTMLDivElement container (js/document.createElement "div")]
                               (js/document.body.appendChild container)
                               (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}})]
                                 (<! (timeout 100))
                                 (is (some? (.querySelector container ".code-editor")) "Editor container rendered")
                                 (.unmount root)
                                 (<! (timeout 100))) ;; Delay to allow React cleanup.
                               (cleanup-container container)
                               [:ok nil])
                             (catch :default e
                               [:error (js/Error. "editor-renders failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest editor-content-change-callback
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [^js/HTMLDivElement container (js/document.createElement "div")
                                   ^js/React.RefObject ref (react/createRef)]
                               (js/document.body.appendChild container)
                               (let [contents (atom [])
                                     root (mount-component container {:onContentChange (fn [content]
                                                                                         (swap! contents conj content))
                                                                      :languages {"text" {:extensions [".txt"]}}
                                                                      :ref ref})]
                                 (let [ready-res (<! (wait-for-ready ref 1000))]
                                   (if (= :error (first ready-res))
                                     (throw (second ready-res))
                                     (is (second ready-res) "Editor ready within timeout")))
                                 (let [editor (ref->editor ref)]
                                   (wrap-flush #(editor->open-document! editor "inmemory://test.txt" "initial" "text"))
                                   (<! (timeout 100))
                                   (wrap-flush #(editor->set-text! editor "updated" "inmemory://test.txt"))
                                   (<! (timeout 100))
                                   (is (= 2 (count @contents)) "Expected 2 contents.")
                                   (is (= "initial" (nth @contents 0)) "Initial text did not fire on-content-change")
                                   (is (= "updated" (nth @contents 1)) "Updated text did not fire on-content-change")
                                   (.unmount root)
                                   (<! (timeout 100))) ;; Delay to allow React cleanup.
                                 (cleanup-container container)
                                 [:ok nil]))
                             (catch :default e
                               [:error (js/Error. "editor-content-change-callback failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest language-config-key-normalization
  (let [config {:languages {"rholang" {"grammarWasm" "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
                                       "highlightsQueryPath" "/extensions/lang/rholang/tree-sitter/queries/highlights.scm"
                                       "indentsQueryPath" "/extensions/lang/rholang/tree-sitter/queries/indents.scm"
                                       "lspUrl" "ws://localhost:41551"
                                       "extensions" [".rho"]
                                       "fileIcon" "fas fa-file-code text-primary"
                                       "fallbackHighlighter" "none"}}}
        state (#'lib.core/default-state (normalize-editor-config config))]
    (is (= (get-in config [:languages "rholang" "grammarWasm"])
           (get-in state [:languages "rholang" :grammar-wasm]))
        "grammarWasm normalized to :grammar-wasm")
    (is (= (get-in config [:languages "rholang" "highlightsQueryPath"])
           (get-in state [:languages "rholang" :highlights-query-path]))
        "highlightsQueryPath normalized")
    (is (= (get-in config [:languages "rholang" "indentsQueryPath"])
           (get-in state [:languages "rholang" :indents-query-path]))
        "indentsQueryPath normalized")
    (is (= (get-in config [:languages "rholang" "lspUrl"])
           (get-in state [:languages "rholang" :lsp-url]))
        "lspUrl normalized")
    (is (= (get-in config [:languages "rholang" "extensions"])
           (get-in state [:languages "rholang" :extensions]))
        "extensions remain unchanged")
    (is (= (get-in config [:languages "rholang" "fileIcon"])
           (get-in state [:languages "rholang" :file-icon]))
        "fileIcon normalized")
    (is (= (get-in config [:languages "rholang" "fallbackHighlighter"])
           (get-in state [:languages "rholang" :fallback-highlighter]))
        "fallbackHighlighter normalized")))

(deftest open-before-connect-sends-after-initialized
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [^js/HTMLDivElement container (js/document.createElement "div")
                                   ^js/React.RefObject ref (react/createRef)
                                   mock-lsp-url "ws://mock"
                                   mock-config (assoc language-config :lsp-url mock-lsp-url)
                                   root (mount-component container {:languages {"rholang" mock-config}
                                                                    :ref ref})]
                               (let [ready-res (<! (wait-for-ready ref 1000))]
                                 (if (= :error (first ready-res))
                                   (throw (second ready-res))
                                   (is (second ready-res) "Editor ready within timeout")))
                               (let [editor (ref->editor ref)
                                     mock-res (<! (with-mock-lsp
                                                    (fn [mock]
                                                      (go
                                                        (try
                                                          (wrap-flush #(editor->open-document! editor "inmemory://test.rho" "content" "rholang"))
                                                          (<! (timeout 100))
                                                          (let [sock (:sock mock)
                                                                wait-res (<! (wait-for #(some? (.-onopen sock)) 1000))]
                                                            (if (= :error (first wait-res))
                                                              (throw (second wait-res))
                                                              (is (second wait-res) "onopen handler set")))
                                                          (let [sent @(:sent mock)]
                                                            (is (empty? sent) "No messages sent before open"))
                                                          ((:trigger-open mock))
                                                          (<! (timeout 100))
                                                          (let [sent @(:sent mock)]
                                                            (is (= 3 (count sent)) "One message after open: initialize")
                                                            (let [messages (->> sent (map parse-message) (mapv second))]
                                                              (is (= "initialize" (:method (first messages))))
                                                              (is (= "initialized" (:method (second messages))))
                                                              (is (= "textDocument/didOpen" (:method (nth messages 2))))
                                                              (is (= "inmemory://test.rho"
                                                                     (get-in (nth messages 2) [:params :textDocument :uri])))))
                                                          (.unmount root)
                                                          (<! (timeout 100)) ;; Delay to allow React cleanup.
                                                          (cleanup-container container)
                                                          [:ok nil]
                                                          (catch :default e
                                                            [:error (js/Error. "open-before-connect-sends-after-initialized failed"
                                                                               #js {:cause e})]))))))]
                                 (if (= :error (first mock-res))
                                   (throw (second mock-res))
                                   [:ok nil])))
                             (catch :default e
                               [:error (js/Error. "open-before-connect-sends-after-initialized failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(defn editor->highlightRange [^js editor ^js from ^js to]
  (editor->highlight-range! editor from to))

(deftest highlight-range-null-view
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [^js/HTMLDivElement container (js/document.createElement "div")
                                   ^js/React.RefObject ref (react/createRef)
                                   captured-logs (atom [])]
                               (log/merge-config! {:appenders {:capture {:enabled? true
                                                                         :fn (fn [data] (swap! captured-logs conj data))}}})
                               (js/document.body.appendChild container)
                               (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                                      :ref ref})]
                                 (let [ready-res (<! (wait-for-ready ref 1000))]
                                   (if (= :error (first ready-res))
                                     (throw (second ready-res))
                                     (is (second ready-res) "Editor ready within timeout")))
                                 (let [editor (ref->editor ref)]
                                   ;; Open a document first to ensure valid content/offsets
                                   (wrap-flush #(editor->open-document! editor "inmemory://test.txt" "12345" "text"))
                                   (<! (timeout 100))
                                   ;; First call: should succeed (no warning)
                                   (wrap-flush #(editor->highlightRange editor #js {:line 1 :column 1} #js {:line 1 :column 5}))
                                   (<! (timeout 100))
                                   (is (empty? (filter #(str/includes? (str/join " " (:vargs %)) "Cannot highlight range") @captured-logs))
                                       "No warning on first highlightRange (valid doc)")
                                   (.unmount root)
                                   (<! (timeout 100))
                                   ;; Second call: after unmount, view-ref is nil
                                   (wrap-flush #(editor->highlightRange editor #js {:line 1 :column 1} #js {:line 1 :column 5}))
                                   (<! (timeout 100))
                                   (is (some #(str/includes? (str/join " " (:vargs %)) "Cannot highlight range: view-ref is nil") @captured-logs)
                                       "Warning logged for null view after unmount"))
                                 (cleanup-container container)
                                 [:ok nil]))
                             (catch :default e
                               [:error (js/Error. "highlight-range-null-view failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest rename-document-notifies-lsp
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [^js/HTMLDivElement container (js/document.createElement "div")
                                   ^js/React.RefObject ref (react/createRef)
                                   mock-lsp-url "ws://mock"
                                   mock-config (assoc language-config :lsp-url mock-lsp-url)
                                   root (mount-component container {:languages {"rholang" mock-config}
                                                                    :ref ref})]
                               (let [ready-res (<! (wait-for-ready ref 1000))]
                                 (if (= :error (first ready-res))
                                   (throw (second ready-res))
                                   (is (second ready-res) "Editor ready within timeout")))
                               (let [editor (ref->editor ref)
                                     mock-res (<! (with-mock-lsp
                                                    (fn [mock]
                                                      (go
                                                        (try
                                                          (wrap-flush #(editor->open-document! editor "inmemory://old.rho" "test" "rholang"))
                                                          (<! (timeout 100))
                                                          (let [sock (:sock mock)
                                                                wait-res (<! (wait-for #(some? (.-onopen sock)) 1000))]
                                                            (if (= :error (first wait-res))
                                                              (throw (second wait-res))
                                                              (is (second wait-res) "onopen handler set")))
                                                          (let [sent @(:sent mock)]
                                                            (is (empty? sent) "No messages sent before open"))
                                                          ((:trigger-open mock))
                                                          (<! (timeout 100))
                                                          (let [sent @(:sent mock)]
                                                            (is (= 3 (count sent)) "Sent initialize, initialized, didOpen"))
                                                          (reset! (:sent mock) [])
                                                          (wrap-flush #(editor->rename-document! editor "new.rho"))
                                                          (<! (timeout 100))
                                                          (let [wait-res (<! (wait-for-uri "inmemory://new.rho" 1000))]
                                                            (if (= :error (first wait-res))
                                                              (throw (second wait-res))
                                                              (is (second wait-res) "New URI exists")))
                                                          (let [sent @(:sent mock)]
                                                            (is (= 1 (count sent)) "One message sent after rename: didRenameFiles")
                                                            (let [msg (first sent)]
                                                              (is (str/includes? msg "didRenameFiles") "Sent didRenameFiles")
                                                              (is (str/includes? msg "\"oldUri\":\"inmemory://old.rho\"") "didRenameFiles with old URI")
                                                              (is (str/includes? msg "\"newUri\":\"inmemory://new.rho\"") "didRenameFiles with new URI")))
                                                          (.unmount root)
                                                          (<! (timeout 100)) ;; Delay to allow React cleanup.
                                                          (cleanup-container container)
                                                          [:ok nil]
                                                          (catch :default e
                                                            [:error (js/Error. "rename-document-notifies-lsp failed" #js {:cause e})]))))))]
                                 (if (= :error (first mock-res))
                                   (throw (second mock-res))
                                   [:ok nil])))
                             (catch :default e
                               [:error (js/Error. "rename-document-notifies-lsp failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest open-document-with-content
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [^js/HTMLDivElement container (js/document.createElement "div")
                                   ^js/React.RefObject ref (react/createRef)]
                               (js/document.body.appendChild container)
                               (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                                      :ref ref})]
                                 (let [ready-res (<! (wait-for-ready ref 1000))]
                                   (if (= :error (first ready-res))
                                     (throw (second ready-res))
                                     (is (second ready-res) "Editor ready within timeout")))
                                 (let [editor (ref->editor ref)]
                                   (wrap-flush #(editor->open-document! editor "inmemory://test.txt" "initial content" "text"))
                                   (<! (timeout 100))
                                   (let [wait-res (<! (wait-for-uri "inmemory://test.txt" 1000))]
                                     (if (= :error (first wait-res))
                                       (throw (second wait-res))
                                       (is (second wait-res) "URI exists")))
                                   (is (= "initial content" (db/document-text-by-uri "inmemory://test.txt")) "Opened with provided content"))
                                 (.unmount root)
                                 (<! (timeout 100))) ;; Delay to allow React cleanup.
                               (cleanup-container container)
                               [:ok nil])
                             (catch :default e
                               [:error (js/Error. "open-document-with-content failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest rxjs-events-emitted
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [^js/HTMLDivElement container (js/document.createElement "div")
                                   ^js/React.RefObject ref (react/createRef)
                                   events (atom [])]
                               (js/document.body.appendChild container)
                               (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                                      :ref ref})]
                                 (let [ready-res (<! (wait-for-ready ref 1000))]
                                   (if (= :error (first ready-res))
                                     (throw (second ready-res))
                                     (is (second ready-res) "Editor ready within timeout")))
                                 (let [editor (ref->editor ref)]
                                   (.subscribe (editor->events editor) #(swap! events conj (js->clj % :keywordize-keys true)))
                                   (wrap-flush #(editor->open-document! editor "inmemory://test.txt" "initial" "text"))
                                   (<! (timeout 100))
                                   (let [wait-res (<! (wait-for-event events "content-change" 1000))]
                                     (if (= :error (first wait-res))
                                       (throw (second wait-res))
                                       (is (second wait-res) "Wait for content-change succeeded")))
                                   (is (some #(= "content-change" (:type %)) @events) "Emitted content-change")
                                   (wrap-flush #(editor->set-text! editor "updated" "inmemory://test.txt"))
                                   (<! (timeout 100))
                                   (let [wait-res (<! (wait-for-event events "content-change" 1000))]
                                     (if (= :error (first wait-res))
                                       (throw (second wait-res))
                                       (is (second wait-res) "Wait for content-change succeeded")))
                                   (is (some #(= "content-change" (:type %)) @events) "Emitted content-change")
                                   (wrap-flush #(editor->open-document! editor "inmemory://file.txt" "content" "text"))
                                   (<! (timeout 100))
                                   (let [wait-res (<! (wait-for-event events "document-open" 1000))]
                                     (if (= :error (first wait-res))
                                       (throw (second wait-res))
                                       (is (second wait-res) "Wait for document-open succeeded")))
                                   (is (some #(= "document-open" (:type %)) @events) "Emitted document-open")
                                   (wrap-flush #(editor->set-text! editor "updated"))
                                   (<! (timeout 100))
                                   (let [wait-res (<! (wait-for-event events "content-change" 1000))]
                                     (if (= :error (first wait-res))
                                       (throw (second wait-res))
                                       (is (second wait-res) "Wait for content-change succeeded")))
                                   (is (some #(= "content-change" (:type %)) @events) "Emitted content-change on set-text for dirty")
                                   (wrap-flush #(editor->save-document! editor))
                                   (<! (timeout 100))
                                   (let [wait-res (<! (wait-for-event events "document-save" 1000))]
                                     (if (= :error (first wait-res))
                                       (throw (second wait-res))
                                       (is (second wait-res) "Wait for document-save succeeded")))
                                   (is (some #(= "document-save" (:type %)) @events) "Emitted document-save")
                                   (wrap-flush #(editor->highlight-range! editor #js {:line 1 :column 1} #js {:line 1 :column 5}))
                                   (<! (timeout 100))
                                   (let [wait-res (<! (wait-for-event events "highlight-change" 1000))]
                                     (if (= :error (first wait-res))
                                       (throw (second wait-res))
                                       (is (second wait-res) "Wait for highlight-change succeeded")))
                                   (is (some #(= "highlight-change" (:type %)) @events) "Emitted highlight-change")
                                   (wrap-flush #(editor->clear-highlight! editor))
                                   (<! (timeout 100))
                                   (let [wait-res (<! (wait-for-event events "highlight-change" 1000))]
                                     (if (= :error (first wait-res))
                                       (throw (second wait-res))
                                       (is (second wait-res) "Wait for highlight-change succeeded")))
                                   (is (some #(= "highlight-change" (:type %)) @events) "Emitted highlight-change")
                                   ;; ----------------------------------------------------------
                                   ;; NOTE: The test for "document-close" must come last because
                                   ;; it will delete the document:
                                   ;; ----------------------------------------------------------
                                   (wrap-flush #(editor->close-document! editor))
                                   (<! (timeout 100))
                                   (let [wait-res (<! (wait-for-event events "document-close" 1000))]
                                     (if (= :error (first wait-res))
                                       (throw (second wait-res))
                                       (is (second wait-res) "Wait for document-close succeeded")))
                                   (is (some #(= "document-close" (:type %)) @events) "Emitted document-close"))
                                 (.unmount root)
                                 (<! (timeout 100))) ;; Delay to allow React cleanup.
                               (cleanup-container container)
                               [:ok nil])
                             (catch :default e
                               [:error (js/Error. "rxjs-events-emitted failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest rholang-extension-integration
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [^js/HTMLDivElement container (js/document.createElement "div")
                                   ^js/React.RefObject ref (react/createRef)
                                   mock-lsp-url "ws://mock"
                                   mock-config (assoc language-config :lsp-url mock-lsp-url)
                                   root (mount-component container {:languages {"rholang" mock-config}
                                                                    :ref ref})]
                               (let [ready-res (<! (wait-for-ready ref 1000))]
                                 (if (= :error (first ready-res))
                                   (throw (second ready-res))
                                   (is (second ready-res) "Editor ready within timeout")))
                               (let [editor (ref->editor ref)
                                     mock-res (<! (with-mock-lsp
                                                    (fn [mock]
                                                      (go
                                                        (try
                                                          (wrap-flush #(editor->open-document! editor "inmemory://test.rho" "new x in { x!(\"Hello\") | Nil }" "rholang"))
                                                          (<! (timeout 100))
                                                          (let [sock (:sock mock)
                                                                wait-res (<! (wait-for #(some? (.-onopen sock)) 1000))]
                                                            (if (= :error (first wait-res))
                                                              (throw (second wait-res))
                                                              (is (second wait-res) "onopen handler set")))
                                                          (let [sent @(:sent mock)]
                                                            (is (empty? sent) "No messages sent before open"))
                                                          ((:trigger-open mock))
                                                          (<! (timeout 100))
                                                          (let [state (js->clj (editor->state editor) :keywordize-keys true)]
                                                            (is (true? (get-in state [:lsp :rholang :initialized?])) "LSP initialized for Rholang"))
                                                          (.unmount root)
                                                          (<! (timeout 100)) ;; Delay to allow React cleanup.
                                                          (cleanup-container container)
                                                          [:ok nil]
                                                          (catch :default e
                                                            [:error (js/Error. "rholang-extension-integration failed" #js {:cause e})]))))))]
                                 (if (= :error (first mock-res))
                                   (throw (second mock-res))
                                   [:ok nil])))
                             (catch :default e
                               [:error (js/Error. "rholang-extension-integration failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest editor-ready-event
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [^js/HTMLDivElement container (js/document.createElement "div")
                                   ^js/React.RefObject ref (react/createRef)
                                   events (atom [])]
                               (js/document.body.appendChild container)
                               (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                                      :ref ref})]
                                 (let [ready-res (<! (wait-for-ready ref 1000))]
                                   (if (= :error (first ready-res))
                                     (throw (second ready-res))
                                     (is (second ready-res) "Editor ready within timeout")))
                                 (let [editor (ref->editor ref)]
                                   (.subscribe (editor->events editor) #(swap! events conj (js->clj % :keywordize-keys true)))
                                   (let [wait-res (<! (wait-for-event events "ready" 1000))]
                                     (if (= :error (first wait-res))
                                       (throw (second wait-res))
                                       (is (second wait-res) "Wait for ready event succeeded")))
                                   (is (some #(= "ready" (:type %)) @events) "Emitted ready event on initialization"))
                                 (.unmount root)
                                 (<! (timeout 100))) ;; Delay to allow React cleanup.
                               (cleanup-container container)
                               [:ok nil])
                             (catch :default e
                               [:error (js/Error. "editor-ready-event failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest selection-change-event
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [^js/HTMLDivElement container (js/document.createElement "div")
                                   ^js/React.RefObject ref (react/createRef)
                                   events (atom [])]
                               (js/document.body.appendChild container)
                               (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                                      :ref ref})]
                                 (let [ready-res (<! (wait-for-ready ref 1000))]
                                   (if (= :error (first ready-res))
                                     (throw (second ready-res))
                                     (is (second ready-res) "Editor ready within timeout")))
                                 (let [editor (ref->editor ref)]
                                   (.subscribe (editor->events editor) #(swap! events conj (js->clj % :keywordize-keys true)))
                                   ;; Open a document with sufficient content to make selection valid
                                   (wrap-flush #(editor->open-document! editor "inmemory://test.txt" "1234567" "text"))
                                   (<! (timeout 100))  ;; Wait for document to load
                                   (wrap-flush #(editor->set-selection! editor #js {:line 1 :column 1} #js {:line 1 :column 7}))
                                   (<! (timeout 100))
                                   (let [wait-res (<! (wait-for-event events "selection-change" 1000))]
                                     (if (= :error (first wait-res))
                                       (throw (second wait-res))
                                       (is (second wait-res) "Wait for selection-change succeeded")))
                                   (is (some #(= "selection-change" (:type %)) @events)
                                       "Emitted selection-change with cursor data"))
                                 (.unmount root)
                                 (<! (timeout 100))) ;; Delay to allow React cleanup.
                               (cleanup-container container)
                               [:ok nil])
                             (catch :default e
                               [:error (js/Error. "selection-change-event failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest clear-highlight
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [^js/HTMLDivElement container (js/document.createElement "div")
                                   ^js/React.RefObject ref (react/createRef)]
                               (js/document.body.appendChild container)
                               (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                                      :ref ref})]
                                 (let [ready-res (<! (wait-for-ready ref 1000))]
                                   (if (= :error (first ready-res))
                                     (throw (second ready-res))
                                     (is (second ready-res) "Editor ready within timeout")))
                                 (let [editor (ref->editor ref)]
                                   ;; Open a document with sufficient content to make selection valid
                                   (wrap-flush #(editor->open-document! editor "inmemory://test.txt" "1234567" "text"))
                                   (<! (timeout 100))  ;; Wait for document to load
                                   (wrap-flush #(editor->highlight-range! editor #js {:line 1 :column 1} #js {:line 1 :column 5}))
                                   (<! (timeout 100))
                                   (wrap-flush #(editor->clear-highlight! editor))
                                   (<! (timeout 100))
                                   ;; No direct assertion, but ensure no error
                                   (is true "clearHighlight called without error")
                                   (.unmount root)
                                   (<! (timeout 100))) ;; Delay to allow React cleanup.
                                 (cleanup-container container)
                                 [:ok nil]))
                             (catch :default e
                               [:error (js/Error. "clear-highlight failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest center-on-range
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [^js/HTMLDivElement container (js/document.createElement "div")
                                   ^js/React.RefObject ref (react/createRef)]
                               (js/document.body.appendChild container)
                               (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                                      :ref ref})]
                                 (let [ready-res (<! (wait-for-ready ref 1000))]
                                   (if (= :error (first ready-res))
                                     (throw (second ready-res))
                                     (is (second ready-res) "Editor ready within timeout")))
                                 (let [editor (ref->editor ref)]
                                   ;; Open a document with sufficient content to make selection valid
                                   (wrap-flush #(editor->open-document! editor "inmemory://test.txt" "0123456789\nabcdefghijk" "text"))
                                   (<! (timeout 100))  ;; Wait for document to load
                                   (wrap-flush #(editor->center-on-range! editor #js {:line 2 :column 1} #js {:line 2 :column 10}))
                                   (<! (timeout 100))
                                   ;; No direct assertion for scroll, but ensure no error
                                   (is true "centerOnRange called without error")
                                   (.unmount root)
                                   (<! (timeout 100))) ;; Delay to allow React cleanup.
                                 (cleanup-container container)
                                 [:ok nil]))
                             (catch :default e
                               [:error (js/Error. "center-on-range failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest get-text-set-text
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [^js/HTMLDivElement container (js/document.createElement "div")
                                   ^js/React.RefObject ref (react/createRef)]
                               (js/document.body.appendChild container)
                               (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                                      :ref ref})]
                                 (let [ready-res (<! (wait-for-ready ref 1000))]
                                   (if (= :error (first ready-res))
                                     (throw (second ready-res))
                                     (is (second ready-res) "Editor ready within timeout")))
                                 (let [editor (ref->editor ref)]
                                   (wrap-flush #(editor->open-document! editor "inmemory://test.txt" "initial" "text"))
                                   (<! (timeout 100))
                                   (is (= "initial" (editor->text editor)) "getText returns initial content")
                                   (wrap-flush #(editor->set-text! editor "updated" "inmemory://test.txt"))
                                   (<! (timeout 100))
                                   (is (= "updated" (editor->text editor)) "getText returns updated content after setText"))
                                 (.unmount root)
                                 (<! (timeout 100))) ;; Delay to allow React cleanup.
                               (cleanup-container container)
                               [:ok nil])
                             (catch :default e
                               [:error (js/Error. "get-text-set-text failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest get-file-path-and-uri
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [^js/HTMLDivElement container (js/document.createElement "div")
                                   ^js/React.RefObject ref (react/createRef)]
                               (js/document.body.appendChild container)
                               (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                                      :ref ref})]
                                 (let [ready-res (<! (wait-for-ready ref 1000))]
                                   (if (= :error (first ready-res))
                                     (throw (second ready-res))
                                     (is (second ready-res) "Editor ready within timeout")))
                                 (let [editor (ref->editor ref)]
                                   (wrap-flush #(editor->open-document! editor "demo.txt" "content" "text"))
                                   (<! (timeout 100))
                                   (let [wait-res (<! (wait-for-uri "inmemory://demo.txt" 1000))]
                                     (if (= :error (first wait-res))
                                       (throw (second wait-res))
                                       (is (second wait-res) "URI exists")))
                                   (is (= "demo.txt" (editor->file-path editor)) "getFilePath returns path")
                                   (is (= "inmemory://demo.txt" (editor->file-uri editor)) "getFileUri returns full URI"))
                                 (.unmount root)
                                 (<! (timeout 100))) ;; Delay to allow React cleanup.
                               (cleanup-container container)
                               [:ok nil])
                             (catch :default e
                               [:error (js/Error. "get-file-path-and-uri failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest activate-document
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [^js/HTMLDivElement container (js/document.createElement "div")
                                   ^js/React.RefObject ref (react/createRef)]
                               (js/document.body.appendChild container)
                               (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                                      :ref ref})]
                                 (let [ready-res (<! (wait-for-ready ref 1000))]
                                   (if (= :error (first ready-res))
                                     (throw (second ready-res))
                                     (is (second ready-res) "Editor ready within timeout")))
                                 (let [editor (ref->editor ref)]
                                   (wrap-flush #(editor->open-document! editor "inmemory://first.txt" "first" "text" false))
                                   (<! (timeout 100))
                                   (let [wait-res (<! (wait-for-uri "inmemory://first.txt" 1000))]
                                     (if (= :error (first wait-res))
                                       (throw (second wait-res))
                                       (is (second wait-res) "URI exists")))
                                   (wrap-flush #(editor->open-document! editor "inmemory://second.txt" "second" "text"))
                                   (<! (timeout 100))
                                   (let [wait-res (<! (wait-for-uri "inmemory://second.txt" 1000))]
                                     (if (= :error (first wait-res))
                                       (throw (second wait-res))
                                       (is (second wait-res) "URI exists")))
                                   (is (= "second" (editor->text editor)) "Second document active")
                                   (wrap-flush #(editor->activate-document! editor "inmemory://first.txt"))
                                   (<! (timeout 100))
                                   (is (= "first" (editor->text editor)) "Activated first document"))
                                 (.unmount root)
                                 (<! (timeout 100))) ;; Delay to allow React cleanup.
                               (cleanup-container container)
                               [:ok nil])
                             (catch :default e
                               [:error (js/Error. "activate-document failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest query-and-get-db
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [^js/HTMLDivElement container (js/document.createElement "div")
                                   ^js/React.RefObject ref (react/createRef)]
                               (js/document.body.appendChild container)
                               (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                                      :ref ref})]
                                 (let [ready-res (<! (wait-for-ready ref 1000))]
                                   (if (= :error (first ready-res))
                                     (throw (second ready-res))
                                     (is (second ready-res) "Editor ready within timeout")))
                                 (let [editor (ref->editor ref)]
                                   (wrap-flush #(editor->open-document! editor "inmemory://test.txt" "test" "text"))
                                   (<! (timeout 100))
                                   (let [db (editor->db editor)]
                                     (is (some? db) "getDb returns connection"))
                                   (let [q '[:find ?uri . :where [?e :workspace/active-uri ?uri]]
                                         result (editor-query editor q)]
                                     (is (= "inmemory://test.txt" result) "query returns active URI")))
                                 (.unmount root)
                                 (<! (timeout 100))) ;; Delay to allow React cleanup.
                               (cleanup-container container)
                               [:ok nil])
                             (catch :default e
                               [:error (js/Error. "query-and-get-db failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest get-diagnostics-and-symbols
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [^js/HTMLDivElement container (js/document.createElement "div")
                                   ^js/React.RefObject ref (react/createRef)]
                               (js/document.body.appendChild container)
                               (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                                      :ref ref})]
                                 (let [ready-res (<! (wait-for-ready ref 1000))]
                                   (if (= :error (first ready-res))
                                     (throw (second ready-res))
                                     (is (second ready-res) "Editor ready within timeout")))
                                 (let [editor (ref->editor ref)]
                                   (wrap-flush #(editor->open-document! editor "inmemory://test.txt" "test" "text"))
                                   (<! (timeout 100))
                                   (wrap-flush #(editor->open-document! editor "inmemory://other.txt" "other" "text"))
                                   (<! (timeout 100))
                                   ;; Mock diags/symbols
                                   (let [db (editor->db editor)
                                         test-id (db/document-id-by-uri "inmemory://test.txt")
                                         other-id (db/document-id-by-uri "inmemory://other.txt")
                                         diag-tx [{:db/id -1 :diagnostic/document test-id :diagnostic/message "diag1" :diagnostic/severity 1 :diagnostic/start-line 0 :diagnostic/start-char 0 :diagnostic/end-line 0 :diagnostic/end-char 4 :type :diagnostic}
                                                  {:db/id -2 :diagnostic/document other-id :diagnostic/message "diag2" :diagnostic/severity 2 :diagnostic/start-line 0 :diagnostic/start-char 0 :diagnostic/end-line 0 :diagnostic/end-char 5 :type :diagnostic}]
                                         sym-tx [{:db/id -3 :symbol/document test-id :symbol/name "sym1" :symbol/kind 1 :symbol/start-line 0 :symbol/start-char 0 :symbol/end-line 0 :symbol/end-char 4 :symbol/selection-start-line 0 :symbol/selection-start-char 0 :symbol/selection-end-line 0 :symbol/selection-end-char 4 :type :symbol}
                                                 {:db/id -4 :symbol/document other-id :symbol/name "sym2" :symbol/kind 2 :symbol/start-line 0 :symbol/start-char 0 :symbol/end-line 0 :symbol/end-char 5 :symbol/selection-start-line 0 :symbol/selection-start-char 0 :symbol/selection-end-line 0 :symbol/selection-end-char 5 :type :symbol}]]
                                     (d/transact! db diag-tx)
                                     (d/transact! db sym-tx)
                                     (let [all-diags (editor->diagnostics editor)
                                           test-diags (editor->diagnostics editor "inmemory://test.txt")]
                                       (is (= 1 (.-length all-diags)) "getDiagnostics without URI returns those for the active URI")
                                       (is (= 1 (.-length test-diags)) "getDiagnostics with URI returns filtered")
                                       (is (= "diag1" (.-message (aget test-diags 0))) "Filtered diag matches"))
                                     (let [all-syms (editor->symbols editor)
                                           test-syms (editor->symbols editor "inmemory://test.txt")]
                                       (is (= 1 (.-length all-syms)) "getSymbols without URI returns those for the active URI")
                                       (is (= 1 (.-length test-syms)) "getSymbols with URI returns filtered")
                                       (is (= "sym1" (.-name (aget test-syms 0))) "Filtered sym matches"))))
                                 (.unmount root)
                                 (<! (timeout 100))) ;; Delay to allow React cleanup.
                               (cleanup-container container)
                               [:ok nil])
                             (catch :default e
                               [:error (js/Error. "get-diagnostics-and-symbols failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest highlight-event-emission
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [^js/HTMLDivElement container (js/document.createElement "div")
                                   ^js/React.RefObject ref (react/createRef)
                                   events (atom [])]
                               (js/document.body.appendChild container)
                               (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                                      :ref ref})]
                                 (let [ready? (<! (wait-for-ready ref 1000))]
                                   (is ready? "Editor ready within timeout"))
                                 (let [editor (ref->editor ref)]
                                   (.subscribe (editor->events editor) #(swap! events conj (js->clj % :keywordize-keys true)))
                                   (wrap-flush #(editor->open-document! editor "inmemory://test.txt" "123456" "text"))
                                   (<! (timeout 100))
                                   (let [wait-res (<! (wait-for-event events "document-open" 1000))]
                                     (is (second wait-res) "document-open emitted"))
                                   (wrap-flush #(editor->highlight-range! editor #js {:line 1 :column 1} #js {:line 1 :column 5}))
                                   (<! (timeout 100))
                                   (let [wait-res (<! (wait-for-event events "highlight-change" 1000))]
                                     (is (second wait-res) "highlight-change emitted"))
                                   (let [evt (first (filter #(= "highlight-change" (:type %)) @events))]
                                     (is (= {:from {:line 1 :column 1} :to {:line 1 :column 5}} (:data evt)) "Emitted highlight-change with range"))
                                   (wrap-flush #(editor->clear-highlight! editor))
                                   (<! (timeout 100))
                                   (let [wait-res (<! (wait-for-event events "highlight-change" 1000))]
                                     (is (second wait-res) "highlight-change nil emitted"))
                                   (let [evt (last (filter #(= "highlight-change" (:type %)) @events))]
                                     (is (nil? (:data evt)) "Emitted highlight-change with nil on clear")))
                                 (.unmount root)
                                 (<! (timeout 100))) ;; Delay to allow React cleanup.
                               (cleanup-container container)
                               [:ok nil])
                             (catch :default e
                               [:error (js/Error. "highlight-event-emission failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(let [gen-line gen/small-integer
      gen-column gen/small-integer
      gen-pos (gen/hash-map :line gen-line :column gen-column)
      prop (prop/for-all [pos gen-pos]
                         (async done
                                (go
                                  (let [res (<! (go
                                                  (try
                                                    (let [^js/HTMLDivElement container (js/document.createElement "div")
                                                          ^js/React.RefObject ref (react/createRef)]
                                                      (js/document.body.appendChild container)
                                                      (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                                                             :ref ref})]
                                                        (let [ready-res (<! (wait-for-ready ref 1000))]
                                                          (if (= :error (first ready-res))
                                                            (throw (second ready-res))
                                                            (is (second ready-res) "Editor ready within timeout")))
                                                        (let [editor (ref->editor ref)]
                                                          (wrap-flush #(editor->set-cursor! editor (clj->js pos)))
                                                          (<! (timeout 100))
                                                          (let [got (js->clj (editor->cursor editor) :keywordize-keys true)]
                                                            (is (= pos got) "setCursor/getCursor roundtrip")))
                                                        (.unmount root)
                                                        (<! (timeout 100))) ;; Delay to allow React cleanup.
                                                      (cleanup-container container)
                                                      [:ok true])
                                                    (catch :default e
                                                      [:error (js/Error. "position-property failed" #js {:cause e})]))))]
                                    (if (= :error (first res))
                                      (do
                                        (is false (str "Test failed with error: " (pr-str (second res))))
                                        (done))
                                      (done))))))]
  (deftest position-property
    (let [result (tc/quick-check 10 prop {:seed 42})]
      (is (:result result)))))

(deftest highlight-change-event-emission
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [^js/HTMLDivElement container (js/document.createElement "div")
                                   ^js/React.RefObject ref (react/createRef)
                                   events (atom [])]
                               (js/document.body.appendChild container)
                               (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                                      :ref ref})]
                                 (let [ready? (<! (wait-for-ready ref 1000))]
                                   (is ready? "Editor ready within timeout"))
                                 (let [editor (ref->editor ref)]
                                   (.subscribe (editor->events editor) #(swap! events conj (js->clj % :keywordize-keys true)))
                                   (wrap-flush #(editor->open-document! editor "inmemory://test.txt" "123456" "text"))
                                   (<! (timeout 100))
                                   (let [wait-res (<! (wait-for-event events "document-open" 1000))]
                                     (is (second wait-res) "document-open emitted"))
                                   (wrap-flush #(editor->highlight-range! editor #js {:line 1 :column 1} #js {:line 1 :column 6}))
                                   (<! (timeout 100))
                                   (let [wait-res (<! (wait-for-event events "highlight-change" 1000))]
                                     (is (second wait-res) "highlight-change emitted"))
                                   (let [evt (first (filter #(= "highlight-change" (:type %)) @events))]
                                     (is (= {:from {:line 1 :column 1} :to {:line 1 :column 6}} (:data evt)) "Emitted highlight-change with range"))
                                   (wrap-flush #(editor->clear-highlight! editor))
                                   (<! (timeout 100))
                                   (let [wait-res (<! (wait-for-event events "highlight-change" 1000))]
                                     (is (second wait-res) "highlight-change nil emitted"))
                                   (let [evt (last (filter #(= "highlight-change" (:type %)) @events))]
                                     (is (nil? (:data evt)) "Emitted highlight-change with nil on clear")))
                                 (.unmount root)
                                 (<! (timeout 100))) ;; Delay to allow React cleanup.
                               (cleanup-container container)
                               [:ok nil])
                             (catch :default e
                               [:error (js/Error. "highlight-change-event-emission failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest default-protocol-in-open-document
  (async done
         (go (let [res (<! (go
                             (try
                               (let [^js/HTMLDivElement container (js/document.createElement "div")
                                     ^js/React.RefObject ref (react/createRef)]
                                 (js/document.body.appendChild container)
                                 (let [root (mount-component container {:defaultProtocol "file://"
                                                                        :languages {"text" {:extensions [".txt"]}}
                                                                        :ref ref})]
                                   (let [ready? (<! (wait-for-ready ref 1000))]
                                     (is ready? "Editor ready within timeout"))
                                   (let [editor (ref->editor ref)]
                                     (wrap-flush #(editor->open-document! editor "test.txt" "content" "text"))
                                     (<! (timeout 100))
                                     (<! (wait-for-uri "file://test.txt" 1000))
                                     (is (= "file://test.txt" (editor->file-uri editor)) "Uses defaultProtocol for file path"))
                                   (.unmount root)
                                   (<! (timeout 100))) ;; Delay to allow React cleanup.
                                 (cleanup-container container)
                                 [:ok nil])
                               (catch :default e
                                 [:error (js/Error. "default-protocol-in-open-document failed" #js {:cause e})]))))]
               (if (= :error (first res))
                 (do
                   (is false (str "Test failed with error: " (pr-str (second res))))
                   (done))
                 (done))))))

(deftest open-document-without-activating
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [^js/HTMLDivElement container (js/document.createElement "div")
                                   ^js/React.RefObject ref (react/createRef)]
                               (js/document.body.appendChild container)
                               (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                                      :ref ref})]
                                 (let [ready? (<! (wait-for-ready ref 1000))]
                                   (is ready? "Editor ready within timeout"))
                                 (let [editor (ref->editor ref)]
                                   (wrap-flush #(editor->open-document! editor "inmemory://active.txt" "active" "text"))
                                   (<! (timeout 100))
                                   (<! (wait-for-uri "inmemory://active.txt" 1000))
                                   (wrap-flush #(editor->open-document! editor "inmemory://background.txt" "background" "text" false))
                                   (<! (timeout 100))
                                   (<! (wait-for-uri "inmemory://background.txt" 1000))
                                   (is (= "active" (editor->text editor)) "Active document remains after opening without activate")
                                   (is (= "background" (editor->text editor "inmemory://background.txt")) "Background document opened"))
                                 (.unmount root)
                                 (<! (timeout 100))) ;; Delay to allow React cleanup.
                               (cleanup-container container)
                               [:ok nil])
                             (catch :default e
                               [:error (js/Error. "open-document-without-activating failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (is false (str "Test failed with error: " (pr-str (second res)))))
             (done)))))

(deftest language-change-event
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [^js/HTMLDivElement container (js/document.createElement "div")
                                   ^js/React.RefObject ref (react/createRef)
                                   events (atom [])]
                               (js/document.body.appendChild container)
                               (let [mock-res (<! (with-mock-lsp
                                                    (fn [_mock]
                                                      (go
                                                        (try
                                                          (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}
                                                                                                             "rholang" (assoc language-config :lsp-url "ws://mock")}
                                                                                                 :ref ref})]
                                                            (let [ready? (<! (wait-for-ready ref 1000))]
                                                              (is ready? "Editor ready within timeout"))
                                                            (let [editor (ref->editor ref)]
                                                              (.subscribe (editor->events editor) #(swap! events conj (js->clj % :keywordize-keys true)))
                                                              (wrap-flush #(editor->open-document! editor "inmemory://test.txt" "test" "text"))
                                                              (<! (timeout 100))
                                                              (wrap-flush #(editor->open-document! editor "inmemory://test.rho" "new x in {}" "rholang"))
                                                              (<! (timeout 100))
                                                              (<! (wait-for-event events "language-change" 1000))
                                                              (is (some #(= "language-change" (:type %)) @events)
                                                                  "Emitted language-change on switch"))
                                                            (.unmount root)
                                                            (<! (timeout 100))
                                                            (cleanup-container container)
                                                            [:ok nil])
                                                          (catch :default e
                                                            [:error (js/Error. "language-change-event failed" #js {:cause e})]))))))]
                                 (if (= :error (first mock-res))
                                   (throw (second mock-res))
                                   [:ok nil])))
                             (catch :default e
                               [:error (js/Error. "language-change-event failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (is false (str "Test failed with error: " (pr-str (second res)))))
             (done)))))

(deftest destroy-event-on-unmount
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [^js/HTMLDivElement container (js/document.createElement "div")
                                   ^js/React.RefObject ref (react/createRef)
                                   events (atom [])]
                               (js/document.body.appendChild container)
                               ;; Mock LSP to isolate (no-op handler since no connection expected)
                               (let [mock-res (<! (with-mock-lsp
                                                    (fn [_mock]  ;; No-op: ignore any sends
                                                      (go
                                                        (try
                                                          (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                                                                 :ref ref})]
                                                            (let [ready? (<! (wait-for-ready ref 1000))]
                                                              (is ready? "Editor ready within timeout"))
                                                            (let [editor (ref->editor ref)]
                                                              (.subscribe (editor->events editor) #(swap! events conj (js->clj % :keywordize-keys true))))
                                                            (.unmount root)
                                                            (<! (timeout 100))
                                                            (is (some #(= "destroy" (:type %)) @events) "Emitted destroy on unmount")
                                                            (cleanup-container container)
                                                            [:ok nil])
                                                          (catch :default e
                                                            [:error (js/Error. "Mocked destroy-event-on-unmount failed" #js {:cause e})]))))))]
                                 (if (= :error (first mock-res))
                                   (throw (second mock-res))
                                   [:ok nil])))
                             (catch :default e
                               [:error (js/Error. "destroy-event-on-unmount failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (is false (str "Test failed with error: " (pr-str (second res)))))
             (done)))))

(deftest fallback-to-basic-on-no-lsp
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [^js/HTMLDivElement container (js/document.createElement "div")
                                   ^js/React.RefObject ref (react/createRef)]
                               (js/document.body.appendChild container)
                               (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                                      :ref ref})]
                                 (let [ready? (<! (wait-for-ready ref 1000))]
                                   (is ready? "Editor ready within timeout"))
                                 (let [editor (ref->editor ref)]
                                   (wrap-flush #(editor->open-document! editor "inmemory://test.txt" "test" "text"))
                                   (<! (timeout 100))
                                   (is (nil? (get-in (editor->state editor) [:lsp "text" :url])) "No LSP for text, fallback to basic"))
                                 (.unmount root)
                                 (<! (timeout 100))) ;; Delay to allow React cleanup.
                               (cleanup-container container)
                               [:ok nil])
                             (catch :default e
                               [:error (js/Error. "fallback-to-basic-on-no-lsp failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (is false (str "Test failed with error: " (pr-str (second res)))))
             (done)))))

(deftest open-document-full-uri
  (async done
         (go (let [res (<! (go
                             (try
                               (let [^js/HTMLDivElement container (js/document.createElement "div")
                                     ^js/React.RefObject ref (react/createRef)]
                                 (js/document.body.appendChild container)
                                 (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                                        :ref ref})]
                                   (let [ready? (<! (wait-for-ready ref 1000))]
                                     (is ready? "Editor ready within timeout"))
                                   (let [editor (ref->editor ref)]
                                     (wrap-flush #(editor->open-document! editor "file://full-uri.txt" "content" "text"))
                                     (<! (timeout 100))
                                     (<! (wait-for-uri "file://full-uri.txt" 1000))
                                     (is (= "file://full-uri.txt" (editor->file-uri editor)) "Uses full URI without prepending"))
                                   (.unmount root)
                                   (<! (timeout 100))) ;; Delay to allow React cleanup.
                                 (cleanup-container container)
                                 [:ok nil])
                               (catch :default e
                                 [:error (js/Error. "open-document-full-uri failed" #js {:cause e})]))))]
               (if (= :error (first res))
                 (do
                   (is false (str "Test failed with error: " (pr-str (second res))))
                   (done))
                 (done))))))

(deftest query-complex
  (async done
         (go (let [res (<! (go
                             (try
                               (let [^js/HTMLDivElement container (js/document.createElement "div")
                                     ^js/React.RefObject ref (react/createRef)]
                                 (js/document.body.appendChild container)
                                 (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                                        :ref ref})]
                                   (let [ready? (<! (wait-for-ready ref 1000))]
                                     (is ready? "Editor ready within timeout"))
                                   (let [editor (ref->editor ref)]
                                     (wrap-flush #(editor->open-document! editor "inmemory://one.txt" "one" "text"))
                                     (<! (timeout 100))
                                     (wrap-flush #(editor->open-document! editor "inmemory://two.txt" "two" "text"))
                                     (<! (timeout 100))
                                     (<! (wait-for-uri "inmemory://one.txt" 1000))
                                     (<! (wait-for-uri "inmemory://two.txt" 1000))
                                     (let [q '[:find (count ?e) :where [?e :type :document] [?e :document/opened true]]
                                           result (js->clj (editor-query editor q))]
                                       (is (= [[2]] result) "Counts open documents")))
                                   (.unmount root)
                                   (<! (timeout 100))) ;; Delay to allow React cleanup.
                                 (cleanup-container container)
                                 [:ok nil])
                               (catch :default e
                                 [:error (js/Error. "query-complex failed" #js {:cause e})]))))]
               (if (= :error (first res))
                 (do
                   (is false (str "Test failed with error: " (pr-str (second res))))
                   (done))
                 (done))))))

(let [gen-line gen/small-integer
      gen-column gen/small-integer
      gen-pos (gen/hash-map :line gen-line :column gen-column)
      gen-doc (gen/vector gen/string-alphanumeric 1 100)
      prop (prop/for-all [doc gen-doc
                          pos gen-pos]
                         (let [text (str/join "\n" doc)
                               state (.create EditorState #js {:doc text :extensions #js []})
                               ^js cm-doc (.-doc state)
                               offset (lib-utils/pos->offset cm-doc pos true)
                               back-pos (when offset (lib-utils/offset->pos cm-doc offset true))]
                           (if (and offset (<= (:line pos) (count doc)))
                             (= pos back-pos)
                             true)))]
  (deftest pos-offset-roundtrip-property
    (let [result (tc/quick-check 50 prop {:seed 42})]
      (is (:result result)))))

(deftest undo-sends-didchange
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [^js/HTMLDivElement container (js/document.createElement "div")
                                   ^js/React.RefObject ref (react/createRef)
                                   mock-lsp-url "ws://mock"
                                   mock-config (assoc language-config :lsp-url mock-lsp-url)
                                   root (mount-component container {:languages {"rholang" mock-config}
                                                                    :ref ref})]
                               (let [ready-res (<! (wait-for-ready ref 1000))]
                                 (if (= :error (first ready-res))
                                   (throw (second ready-res))
                                   (is (second ready-res) "Editor ready within timeout")))
                               (let [editor (ref->editor ref)
                                     mock-res (<! (with-mock-lsp
                                                    (fn [mock]
                                                      (go
                                                        (try
                                                          (wrap-flush #(editor->open-document! editor "inmemory://test.rho" "initial" "rholang"))
                                                          (<! (timeout 100))
                                                          (let [sock (:sock mock)
                                                                wait-res (<! (wait-for #(some? (.-onopen sock)) 1000))]
                                                            (if (= :error (first wait-res))
                                                              (throw (second wait-res))
                                                              (is (second wait-res) "onopen handler set")))
                                                          (let [sent @(:sent mock)]
                                                            (is (empty? sent) "No messages sent before open"))
                                                          ((:trigger-open mock))
                                                          (<! (timeout 100))
                                                          (reset! (:sent mock) [])
                                                          (wrap-flush #(editor->set-text! editor "updated" "inmemory://test.rho"))
                                                          (<! (timeout 300)) ;; Wait for debounce
                                                          (let [sent @(:sent mock)]
                                                            (is (= 1 (count sent)) "didChange sent after setText")
                                                            (is (str/includes? (first sent) "didChange") "didChange message"))
                                                          (reset! (:sent mock) [])
                                                          (wrap-flush #(editor->set-text! editor "initial" "inmemory://test.rho")) ;; Simulate undo by setting back
                                                          (<! (timeout 300)) ;; Wait for debounce
                                                          (let [sent @(:sent mock)]
                                                            (is (= 1 (count sent)) "didChange sent after 'undo'")
                                                            (is (str/includes? (first sent) "didChange") "didChange message")
                                                            (is (str/includes? (first sent) "initial") "'Undo' restores initial content in didChange"))
                                                          (.unmount root)
                                                          (<! (timeout 100)) ;; Delay to allow React cleanup.
                                                          (cleanup-container container)
                                                          [:ok nil]
                                                          (catch :default e
                                                            [:error (js/Error. "undo-sends-didchange failed" #js {:cause e})]))))))]
                                 (if (= :error (first mock-res))
                                   (throw (second mock-res))
                                   [:ok nil])))
                             (catch :default e
                               [:error (js/Error. "undo-sends-didchange failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest indent-dedent-fires-change
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [^js/HTMLDivElement container (js/document.createElement "div")
                                   ^js/React.RefObject ref (react/createRef)
                                   events (atom [])]
                               (js/document.body.appendChild container)
                               (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                                      :ref ref})]
                                 (let [ready-res (<! (wait-for-ready ref 1000))]
                                   (if (= :error (first ready-res))
                                     (throw (second ready-res))
                                     (is (second ready-res) "Editor ready within timeout")))
                                 (let [editor (ref->editor ref)
                                       cm-el (.querySelector container ".cm-editor")
                                       view (.findFromDOM EditorView cm-el)]
                                   (.subscribe (editor->events editor) #(swap! events conj (js->clj % :keywordize-keys true)))
                                   (wrap-flush #(editor->open-document! editor "inmemory://test.txt" "line1\nline2" "text"))
                                   (<! (timeout 100))
                                   (wrap-flush #(editor->set-selection! editor #js {:line 1 :column 1} #js {:line 2 :column 5}))
                                   (<! (timeout 100))
                                   ; For TAB (indentMore)
                                   (wrap-flush #(indentMore view))
                                   (<! (timeout 300))
                                   (is (some #(= "content-change" (:type %)) @events) "Indent fires content-change")
                                   ; For SHIFT-TAB (indentLess)
                                   (wrap-flush #(indentLess view))
                                   (<! (timeout 300))
                                   (is (some #(= "content-change" (:type %)) @events) "Dedent fires content-change"))
                                 (.unmount root)
                                 (<! (timeout 100))) ;; Delay to allow React cleanup.
                               (cleanup-container container)
                               [:ok nil])
                             (catch :default e
                               [:error (js/Error. "indent-dedent-fires-change failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest search-extension-enabled
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [^js/HTMLDivElement container (js/document.createElement "div")]
                               (js/document.body.appendChild container)
                               (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}})]
                                 (<! (timeout 100))
                                 (let [cm-el (.querySelector container ".cm-editor")
                                       view (.findFromDOM EditorView cm-el)]
                                   (openSearchPanel view) ; Open to verify
                                   (<! (timeout 50))
                                   (is (some? (.querySelector cm-el ".cm-search")) "Search panel opened, extension loaded"))
                                 (.unmount root)
                                 (<! (timeout 100))) ;; Delay to allow React cleanup.
                               (cleanup-container container)
                               [:ok nil])
                             (catch :default e
                               [:error (js/Error. "search-extension-enabled failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest search-term-event-debounced
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [^js/HTMLDivElement container (js/document.createElement "div")
                                   ^js/React.RefObject ref (react/createRef)
                                   events (atom [])]
                               (js/document.body.appendChild container)
                               (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                                      :ref ref})]
                                 (let [ready-res (<! (wait-for-ready ref 1000))]
                                   (if (= :error (first ready-res))
                                     (throw (second ready-res))
                                     (is (second ready-res) "Editor ready within timeout")))
                                 (let [editor (ref->editor ref)
                                       cm-el (.querySelector container ".cm-editor")
                                       view (.findFromDOM EditorView cm-el)]
                                   (.subscribe (editor->events editor) #(swap! events conj (js->clj % :keywordize-keys true)))
                                   (openSearchPanel view)
                                   (<! (timeout 50))
                                   (let [q (getSearchQuery (.-state view))
                                         new-q (js/Object.assign (js/Object.create (js/Object.getPrototypeOf q)) q)]
                                     (set! (.-search new-q) "term")
                                     (.dispatch view #js {:effects (.of setSearchQuery new-q)}))
                                   (<! (timeout 250)) ;; Wait for debounce
                                   (let [wait-res (<! (wait-for-event events "search-term-change" 1000))]
                                     (if (= :error (first wait-res))
                                       (throw (second wait-res))
                                       (is (second wait-res) "Wait for search-term-change succeeded")))
                                   (is (some #(= "search-term-change" (:type %)) @events) "Emitted search-term-change")
                                   (let [evt (first (filter #(= "search-term-change" (:type %)) @events))]
                                     (is (= "term" (get-in evt [:data :term])) "Data term matches"))
                                   (is (= "term" (editor->search-term editor)) "getSearchTerm returns term"))
                                 (.unmount root)
                                 (<! (timeout 100))) ;; Delay to allow React cleanup.
                               (cleanup-container container)
                               [:ok nil])
                             (catch :default e
                               [:error (js/Error. "search-term-event-debounced failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(let [gen-term gen/string-alphanumeric
      prop (prop/for-all [term gen-term]
                         (async done
                                (go
                                  (let [res (<! (go
                                                  (try
                                                    (let [^js/HTMLDivElement container (js/document.createElement "div")
                                                          ^js/React.RefObject ref (react/createRef)]
                                                      (js/document.body.appendChild container)
                                                      (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                                                             :ref ref})]
                                                        (let [ready-res (<! (wait-for-ready ref 1000))]
                                                          (if (= :error (first ready-res))
                                                            (throw (second ready-res))
                                                            (is (second ready-res) "Editor ready within timeout")))
                                                        (let [editor (ref->editor ref)
                                                              cm-el (.querySelector container ".cm-editor")
                                                              view (.findFromDOM EditorView cm-el)]
                                                          (openSearchPanel view)
                                                          (<! (timeout 50))
                                                          (let [input (.querySelector cm-el ".cm-textfield")]
                                                            (set! (.-value input) term)
                                                            (.dispatchEvent input (js/Event. "input" #js {:bubbles true})))
                                                          (<! (timeout 250)) ;; Wait for debounce
                                                          (is (= term (editor->search-term editor)) "getSearchTerm matches generated term"))
                                                        (.unmount root)
                                                        (<! (timeout 100))) ;; Delay to allow React cleanup.
                                                      (cleanup-container container)
                                                      [:ok true])
                                                    (catch :default e
                                                      [:error (js/Error. "search-term-property failed" #js {:cause e})]))))]
                                    (if (= :error (first res))
                                      (do
                                        (is false (str "Test failed with error: " (pr-str (second res))))
                                        (done))
                                      (done))))))]
  (deftest search-term-property
    (let [result (tc/quick-check 10 prop {:seed 42})]
      (is (:result result)))))

(deftest save-document-notifies-lsp
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [^js/HTMLDivElement container (js/document.createElement "div")
                                   ^js/React.RefObject ref (react/createRef)
                                   mock-lsp-url "ws://mock"
                                   mock-config (assoc language-config :lsp-url mock-lsp-url)
                                   root (mount-component container {:languages {"rholang" mock-config}
                                                                    :ref ref})]
                               (let [ready-res (<! (wait-for-ready ref 1000))]
                                 (if (= :error (first ready-res))
                                   (throw (second ready-res))
                                   (is (second ready-res) "Editor ready within timeout")))
                               (let [editor (ref->editor ref)
                                     mock-res (<! (with-mock-lsp
                                                    (fn [mock]
                                                      (go
                                                        (try
                                                          (wrap-flush #(editor->open-document! editor "inmemory://test.rho" "test" "rholang"))
                                                          (<! (timeout 100))
                                                          (let [sock (:sock mock)
                                                                wait-res (<! (wait-for #(some? (.-onopen sock)) 1000))]
                                                            (if (= :error (first wait-res))
                                                              (throw (second wait-res))
                                                              (is (second wait-res) "onopen handler set")))
                                                          (let [sent @(:sent mock)]
                                                            (is (empty? sent) "No messages sent before open"))
                                                          ((:trigger-open mock))
                                                          (<! (timeout 100))
                                                          (reset! (:sent mock) [])
                                                          (wrap-flush #(editor->save-document! editor))
                                                          (<! (timeout 100))
                                                          (let [sent @(:sent mock)]
                                                            (is (= 1 (count sent)) "didSave sent")
                                                            (is (str/includes? (first sent) "didSave") "didSave message")
                                                            (is (str/includes? (first sent) "\"text\":\"test\"") "didSave with text"))
                                                          (.unmount root)
                                                          (<! (timeout 100)) ;; Delay to allow React cleanup.
                                                          (cleanup-container container)
                                                          [:ok nil]
                                                          (catch :default e
                                                            [:error (js/Error. "save-document-notifies-lsp failed" #js {:cause e})]))))))]
                                 (if (= :error (first mock-res))
                                   (throw (second mock-res))
                                   [:ok nil])))
                             (catch :default e
                               [:error (js/Error. "save-document-notifies-lsp failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest center-on-range-no-error
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [^js/HTMLDivElement container (js/document.createElement "div")
                                   ^js/React.RefObject ref (react/createRef)]
                               (js/document.body.appendChild container)
                               (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                                      :ref ref})]
                                 (let [ready-res (<! (wait-for-ready ref 1000))]
                                   (if (= :error (first ready-res))
                                     (throw (second ready-res))
                                     (is (second ready-res) "Editor ready within timeout")))
                                 (let [editor (ref->editor ref)]
                                   (wrap-flush #(editor->open-document! editor "inmemory://test.txt" (str/join "\n" (repeat 50 "line")) "text"))
                                   (<! (timeout 100))
                                   (wrap-flush #(editor->center-on-range! editor #js {:line 25 :column 1} #js {:line 25 :column 4}))
                                   (<! (timeout 100))
                                   (is true "centerOnRange called without error"))
                                 (.unmount root)
                                 (<! (timeout 100))) ;; Delay to allow React cleanup.
                               (cleanup-container container)
                               [:ok nil])
                             (catch :default e
                               [:error (js/Error. "center-on-range-no-error failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest query-with-params
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [^js/HTMLDivElement container (js/document.createElement "div")
                                   ^js/React.RefObject ref (react/createRef)]
                               (js/document.body.appendChild container)
                               (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                                      :ref ref})]
                                 (let [ready-res (<! (wait-for-ready ref 1000))]
                                   (if (= :error (first ready-res))
                                     (throw (second ready-res))
                                     (is (second ready-res) "Editor ready within timeout")))
                                 (let [editor (ref->editor ref)]
                                   (wrap-flush #(editor->open-document! editor "inmemory://test.txt" "test" "text"))
                                   (<! (timeout 100))
                                   (let [q '[:find ?text . :in $ ?uri :where [?e :document/uri ?uri] [?e :document/text ?text]]
                                         params ["inmemory://test.txt"]
                                         result (editor-query editor q (clj->js params))]
                                     (is (= "test" result) "Query with param returns text")))
                                 (.unmount root)
                                 (<! (timeout 100))) ;; Delay to allow React cleanup.
                               (cleanup-container container)
                               [:ok nil])
                             (catch :default e
                               [:error (js/Error. "query-with-params failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest get-diagnostics-symbols-with-without-uri
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [^js/HTMLDivElement container (js/document.createElement "div")
                                   ^js/React.RefObject ref (react/createRef)]
                               (js/document.body.appendChild container)
                               (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                                      :ref ref})]
                                 (let [ready-res (<! (wait-for-ready ref 1000))]
                                   (if (= :error (first ready-res))
                                     (throw (second ready-res))
                                     (is (second ready-res) "Editor ready within timeout")))
                                 (let [editor (ref->editor ref)]
                                   (wrap-flush #(editor->open-document! editor "inmemory://test.txt" "test" "text"))
                                   (<! (timeout 100))
                                   (wrap-flush #(editor->open-document! editor "inmemory://other.txt" "other" "text"))
                                   (<! (timeout 100))
                                   ;; Mock diags/symbols
                                   (let [db (editor->db editor)
                                         test-id (db/document-id-by-uri "inmemory://test.txt")
                                         other-id (db/document-id-by-uri "inmemory://other.txt")
                                         diag-tx [{:db/id -1 :diagnostic/document test-id :diagnostic/message "diag1" :diagnostic/severity 1 :diagnostic/start-line 0 :diagnostic/start-char 0 :diagnostic/end-line 0 :diagnostic/end-char 4 :type :diagnostic}
                                                  {:db/id -2 :diagnostic/document other-id :diagnostic/message "diag2" :diagnostic/severity 2 :diagnostic/start-line 0 :diagnostic/start-char 0 :diagnostic/end-line 0 :diagnostic/end-char 5 :type :diagnostic}]
                                         sym-tx [{:db/id -3 :symbol/document test-id :symbol/name "sym1" :symbol/kind 1 :symbol/start-line 0 :symbol/start-char 0 :symbol/end-line 0 :symbol/end-char 4 :symbol/selection-start-line 0 :symbol/selection-start-char 0 :symbol/selection-end-line 0 :symbol/selection-end-char 4 :type :symbol}
                                                 {:db/id -4 :symbol/document other-id :symbol/name "sym2" :symbol/kind 2 :symbol/start-line 0 :symbol/start-char 0 :symbol/end-line 0 :symbol/end-char 5 :symbol/selection-start-line 0 :symbol/selection-start-char 0 :symbol/selection-end-line 0 :symbol/selection-end-char 5 :type :symbol}]]
                                     (d/transact! db diag-tx)
                                     (d/transact! db sym-tx)
                                     (let [all-diags (editor->diagnostics editor)
                                           test-diags (editor->diagnostics editor "inmemory://test.txt")]
                                       (is (= 1 (.-length all-diags)) "getDiagnostics without URI returns those for the active URI")
                                       (is (= 1 (.-length test-diags)) "getDiagnostics with URI returns filtered")
                                       (is (= "diag1" (.-message (aget test-diags 0))) "Filtered diag matches"))
                                     (let [all-syms (editor->symbols editor)
                                           test-syms (editor->symbols editor "inmemory://test.txt")]
                                       (is (= 1 (.-length all-syms)) "getSymbols without URI returns those for the active URI")
                                       (is (= 1 (.-length test-syms)) "getSymbols with URI returns filtered")
                                       (is (= "sym1" (.-name (aget test-syms 0))) "Filtered sym matches"))))
                                 (.unmount root)
                                 (<! (timeout 100))) ;; Delay to allow React cleanup.
                               (cleanup-container container)
                               [:ok nil])
                             (catch :default e
                               [:error (js/Error. "get-diagnostics-symbols-with-without-uri failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest error-on-invalid-uri
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [^js/HTMLDivElement container (js/document.createElement "div")
                                   ^js/React.RefObject ref (react/createRef)
                                   events (atom [])]
                               (js/document.body.appendChild container)
                               (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                                      :ref ref})]
                                 (let [ready-res (<! (wait-for-ready ref 1000))]
                                   (if (= :error (first ready-res))
                                     (throw (second ready-res))
                                     (is (second ready-res) "Editor ready within timeout")))
                                 (let [editor (ref->editor ref)]
                                   (.subscribe (editor->events editor) #(swap! events conj (js->clj % :keywordize-keys true)))
                                   (try
                                     (wrap-flush #(editor->open-document! editor "" "content" "text")) ;; Invalid URI
                                     (<! (timeout 100))
                                     (catch js/Error error
                                       (is (str/includes? (.-message error) "Invalid URI or file path") "Error message for invalid URI"))))
                                 (.unmount root)
                                 (<! (timeout 100))) ;; Delay to allow React cleanup.
                               (cleanup-container container)
                               [:ok nil])
                             (catch :default e
                               [:error (js/Error. "error-on-invalid-uri failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest shutdown-on-unmount
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [^js/HTMLDivElement container (js/document.createElement "div")
                                   ^js/React.RefObject ref (react/createRef)
                                   events (atom [])
                                   mock-lsp-url "ws://mock"
                                   mock-config (assoc language-config :lsp-url mock-lsp-url)
                                   root (mount-component container {:languages {"rholang" mock-config}
                                                                    :ref ref})]
                               (let [ready-res (<! (wait-for-ready ref 2000))]
                                 (if (= :error (first ready-res))
                                   (throw (second ready-res))
                                   (is (second ready-res) "Editor ready within timeout")))
                               (let [editor (ref->editor ref)]
                                 (.subscribe (editor->events editor) #(swap! events conj (js->clj % :keywordize-keys true)))
                                 (let [mock-res (<! (with-mock-lsp
                                                      (fn [mock]
                                                        (go
                                                          (try
                                                            (wrap-flush #(editor->open-document! editor "inmemory://test.rho" "test" "rholang"))
                                                            (<! (timeout 100))
                                                            (let [sock (:sock mock)
                                                                  wait-res (<! (wait-for #(some? (.-onopen sock)) 1000))]
                                                              (if (= :error (first wait-res))
                                                                (throw (second wait-res))
                                                                (is (second wait-res) "onopen handler set")))
                                                            (let [sent @(:sent mock)]
                                                              (is (empty? sent) "No messages sent before open"))
                                                            ((:trigger-open mock))
                                                            (<! (timeout 100))
                                                            (let [wait-res (<! (wait-for-event events "lsp-initialized" 1000))]
                                                              (if (= :error (first wait-res))
                                                                (throw (second wait-res))
                                                                (is (second wait-res) "Wait for lsp-initialized succeeded")))
                                                            (reset! (:sent mock) [])
                                                            (<! (timeout 100))
                                                            (.unmount root)
                                                            (<! (timeout 100))
                                                            (let [sent @(:sent mock)]
                                                              (is (= 2 (count sent)) "Sent shutdown on unmount")
                                                              (is (str/includes? (first sent) "shutdown") "Sent shutdown and exit requests")
                                                              (is (str/includes? (second sent) "exit") "Sent exit notification"))
                                                            (cleanup-container container)
                                                            [:ok nil]
                                                            (catch :default e
                                                              [:error (js/Error. "shutdown-on-unmount failed" #js {:cause e})]))))))]
                                   (if (= :error (first mock-res))
                                     (throw (second mock-res))
                                     [:ok nil]))))
                             (catch :default e
                               [:error (js/Error. "shutdown-on-unmount failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest shutdown-on-window-close
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [^js/HTMLDivElement container (js/document.createElement "div")
                                   ^js/React.RefObject ref (react/createRef)
                                   events (atom [])
                                   mock-lsp-url "ws://mock"
                                   mock-config (assoc language-config :lsp-url mock-lsp-url)
                                   root (mount-component container {:languages {"rholang" mock-config}
                                                                    :ref ref})]
                               (let [ready-res (<! (wait-for-ready ref 2000))]
                                 (if (= :error (first ready-res))
                                   (throw (second ready-res))
                                   (is (second ready-res) "Editor ready within timeout")))
                               (let [editor (ref->editor ref)]
                                 (.subscribe (editor->events editor) #(swap! events conj (js->clj % :keywordize-keys true)))
                                 (let [mock-res (<! (with-mock-lsp
                                                      (fn [mock]
                                                        (go
                                                          (try
                                                            (wrap-flush #(editor->open-document! editor "inmemory://test.rho" "test" "rholang"))
                                                            (<! (timeout 100))
                                                            (let [sock (:sock mock)
                                                                  wait-res (<! (wait-for #(some? (.-onopen sock)) 1000))]
                                                              (if (= :error (first wait-res))
                                                                (throw (second wait-res))
                                                                (is (second wait-res) "onopen handler set")))
                                                            (let [sent @(:sent mock)]
                                                              (is (empty? sent) "No messages sent before open"))
                                                            ((:trigger-open mock))
                                                            (<! (timeout 100))
                                                            (let [wait-res (<! (wait-for-event events "lsp-initialized" 1000))]
                                                              (if (= :error (first wait-res))
                                                                (throw (second wait-res))
                                                                (is (second wait-res) "Wait for lsp-initialized succeeded")))
                                                            (reset! (:sent mock) [])
                                                            (<! (timeout 100))
                                                            (js/window.dispatchEvent (js/Event. "beforeunload"))
                                                            (<! (timeout 100))
                                                            (let [sent @(:sent mock)]
                                                              (is (= 2 (count sent)) "Sent shutdown and exit on beforeunload")
                                                              (is (str/includes? (first sent) "shutdown") "Sent shutdown request")
                                                              (is (str/includes? (second sent) "exit") "Sent exit notification"))
                                                            [:ok nil]
                                                            (catch :default e
                                                              [:error (js/Error. "shutdown-on-window-close failed" #js {:cause e})]))))))]
                                   (if (= :error (first mock-res))
                                     (throw (second mock-res))
                                     [:ok nil])))
                               (.unmount root)
                               (<! (timeout 100)))
                             (catch :default e
                               [:error (js/Error. "shutdown-on-window-close failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))
