(ns test.lib.core-test
  (:require
   [clojure.core.async :as async :refer [go <! timeout]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is async use-fixtures]]
   [datascript.core :as d]
   ["react" :as react]
   [lib.core]
   [ext.lang.rholang :refer [language-config]]
   [lib.db :as db]
   [lib.workspace :as ws]
   [lib.state :refer [normalize-editor-config]]
   [lib.utils :as lib-utils]
   [test.lib.mock-lsp :refer [parse-message with-mock-lsp]]
   [test.lib.utils :refer [wait-for wait-for-ready wait-for-event wait-for-uri wait-for-opened-uri
                           ref->editor editor->close-document! editor->save-document!
                           editor->rename-document! editor->open-document!
                           editor->clear-highlight! editor->highlight-range!
                           editor->activate-document! editor->set-selection! editor->set-text!
                           editor->center-on-range! editor->text editor->events editor->state
                           editor->file-path editor->file-uri editor->diagnostics editor->symbols
                           editor->db editor->query]]
   [taoensso.timbre :as log]
   [test.lib.core-test-common :refer [mount-component wrap-flush cleanup-container editor->highlightRange once-fixtures each-fixtures]]))

(use-fixtures :once once-fixtures)
(use-fixtures :each each-fixtures)

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
                                   events-atom (atom [])
                                   mock-lsp-url "ws://mock"
                                   mock-config (assoc language-config :lsp-url mock-lsp-url)
                                   root (mount-component container {:languages {"rholang" mock-config}
                                                                    :ref ref})]
                               (let [ready-res (<! (wait-for-ready ref 1000))]
                                 (if (= :error (first ready-res))
                                   (throw (second ready-res))
                                   (is (second ready-res) "Editor ready within timeout")))
                               (let [editor (ref->editor ref)]
                                 (.subscribe (editor->events editor) #(swap! events-atom conj (js->clj % :keywordize-keys true)))
                                 (let [mock-res (<! (with-mock-lsp
                                                      (fn [mock]
                                                        (go
                                                          (try
                                                            ;; Open document before triggering connection
                                                            (wrap-flush #(editor->open-document! editor "inmemory://test.rho" "content" "rholang"))
                                                            (<! (timeout 100))
                                                            (let [sock (:sock mock)
                                                                  wait-res (<! (wait-for #(some? (.-onopen sock)) 3000))]
                                                              (if (= :error (first wait-res))
                                                                (throw (second wait-res))
                                                                (is (second wait-res) "onopen handler set")))
                                                            (let [sent @(:sent mock)]
                                                              (is (empty? sent) "No messages sent before open"))
                                                            ((:trigger-open mock))
                                                            (let [init-res (<! (wait-for-event events-atom "lsp-initialized" 3000))]
                                                              (if (= :error (first init-res))
                                                                (throw (second init-res))
                                                                (is (second init-res) "lsp-initialized emitted")))
                                                            (<! (timeout 100))
                                                            (let [sent @(:sent mock)]
                                                              (is (= 3 (count sent)) "Sent initialize, initialized, didOpen")
                                                              (let [messages (->> sent (map parse-message) (mapv second))]
                                                                (is (= "initialize" (:method (first messages))) "First message is initialize")
                                                                (is (= "initialized" (:method (second messages))) "Second message is initialized")
                                                                (is (= "textDocument/didOpen" (:method (nth messages 2))) "Third message is didOpen")
                                                                (is (= "inmemory://test.rho" (get-in (nth messages 2) [:params :textDocument :uri])) "didOpen with correct URI")
                                                                (is (= "content" (get-in (nth messages 2) [:params :textDocument :text])) "didOpen with correct content")))
                                                            (let [error-events (filter #(= "lsp-error" (:type %)) @events-atom)]
                                                              (is (empty? error-events) "No lsp-error events emitted"))
                                                            (let [wait-res (<! (wait-for-opened-uri "inmemory://test.rho" 1000))]
                                                              (if (= :error (first wait-res))
                                                                (throw (second wait-res))
                                                                (is (second wait-res) "Document opened in LSP")))
                                                            (.unmount root)
                                                            (<! (timeout 100))
                                                            (cleanup-container container)
                                                            [:ok nil]
                                                            (catch :default e
                                                              [:error (js/Error. "open-before-connect-sends-after-initialized failed" #js {:cause e})]))))))]
                                   (if (= :error (first mock-res))
                                     (throw (second mock-res))
                                     [:ok nil]))))
                             (catch :default e
                               [:error (js/Error. "open-before-connect-sends-after-initialized failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

(deftest open-after-connect-sends-didopen
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [^js/HTMLDivElement container (js/document.createElement "div")
                                   ^js/React.RefObject ref (react/createRef)
                                   events-atom (atom [])
                                   mock-lsp-url "ws://mock"
                                   mock-config (assoc language-config :lsp-url mock-lsp-url)
                                   root (mount-component container {:languages {"rholang" mock-config}
                                                                    :ref ref})]
                               (let [ready-res (<! (wait-for-ready ref 1000))]
                                 (if (= :error (first ready-res))
                                   (throw (second ready-res))
                                   (is (second ready-res) "Editor ready within timeout")))
                               (let [editor (ref->editor ref)]
                                 (.subscribe (editor->events editor) #(swap! events-atom conj (js->clj % :keywordize-keys true)))
                                 (let [mock-res (<! (with-mock-lsp
                                                      (fn [mock]
                                                        (go
                                                          (try
                                                            (wrap-flush #(editor->open-document! editor "inmemory://dummy.rho" "dummy" "rholang"))
                                                            (<! (timeout 100))
                                                            (let [sock (:sock mock)
                                                                  wait-res (<! (wait-for #(some? (.-onopen sock)) 3000))]
                                                              (if (= :error (first wait-res))
                                                                (throw (second wait-res))
                                                                (is (second wait-res) "onopen handler set")))
                                                            ((:trigger-open mock))
                                                            (let [init-res (<! (wait-for-event events-atom "lsp-initialized" 3000))]
                                                              (if (= :error (first init-res))
                                                                (throw (second init-res))
                                                                (is (second init-res) "lsp-initialized emitted")))
                                                            (<! (timeout 100))
                                                            (let [sent @(:sent mock)]
                                                              (js/console.log "Sent messages for dummy:" (clj->js sent))
                                                              (is (= 3 (count sent)) "Sent initialize, initialized, didOpen for dummy")
                                                              (let [messages (->> sent (map parse-message) (mapv second))]
                                                                (js/console.log "Parsed messages for dummy:" (clj->js messages))
                                                                (is (= "initialize" (:method (first messages))) "First message is initialize")
                                                                (is (= "initialized" (:method (second messages))) "Second message is initialized")
                                                                (is (= "textDocument/didOpen" (:method (nth messages 2))) "Third message is didOpen for dummy")
                                                                (is (= "inmemory://dummy.rho" (get-in (nth messages 2) [:params :textDocument :uri])) "didOpen for dummy")))
                                                            (reset! (:sent mock) [])
                                                            (wrap-flush #(editor->close-document! editor "inmemory://dummy.rho"))
                                                            (<! (timeout 100))
                                                            (let [sent @(:sent mock)]
                                                              (is (= 1 (count sent)) "Sent didClose for dummy")
                                                              (let [messages (->> sent (map parse-message) (mapv second))]
                                                                (is (= "textDocument/didClose" (:method (first messages))) "Sent didClose for dummy")))
                                                            (reset! (:sent mock) [])
                                                            (wrap-flush #(editor->open-document! editor "inmemory://test.rho" "content" "rholang"))
                                                            (<! (timeout 100))
                                                            (let [wait-res (<! (wait-for-opened-uri "inmemory://test.rho" 1000))]
                                                              (if (= :error (first wait-res))
                                                                (throw (second wait-res))
                                                                (is (second wait-res) "Document opened")))
                                                            (let [sent @(:sent mock)]
                                                              (is (= 1 (count sent)) "Sent didOpen after open")
                                                              (let [messages (->> sent (map parse-message) (mapv second))]
                                                                (is (= "textDocument/didOpen" (:method (first messages))) "Sent didOpen")
                                                                (is (= "inmemory://test.rho" (get-in (first messages) [:params :textDocument :uri])) "didOpen with correct URI")))
                                                            (.unmount root)
                                                            (<! (timeout 100))
                                                            (cleanup-container container)
                                                            [:ok nil]
                                                            (catch :default e
                                                              [:error (js/Error. "open-after-connect-sends-didopen failed" #js {:cause e})]))))))]
                                   (if (= :error (first mock-res))
                                     (throw (second mock-res))
                                   [:ok nil]))))
                             (catch :default e
                               [:error (js/Error. "open-after-connect-sends-didopen failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))


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
                                   (is (= "initial content" (db/document-text-by-uri (ws/default-conn) "inmemory://test.txt")) "Opened with provided content"))
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
                                         result (editor->query editor q)]
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
                                         test-id (db/document-id-by-uri (ws/default-conn) "inmemory://test.txt")
                                         other-id (db/document-id-by-uri (ws/default-conn) "inmemory://other.txt")
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

