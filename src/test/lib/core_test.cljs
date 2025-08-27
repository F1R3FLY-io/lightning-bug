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
   ["@codemirror/state" :refer [EditorState]]
   ["react" :as react]
   ["react-dom/client" :as rdclient]
   ["rxjs" :as rxjs]
   [lib.core :refer [Editor]]
   [ext.lang.rholang :refer [config]]
   [lib.db :as db]
   [lib.editor.syntax :as syntax]
   [lib.utils :as u]
   [test.lib.mock-lsp :as mock]
   [taoensso.timbre :as log]))

(use-fixtures :each
  {:before (fn []
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
  ([^js editor uri]
   (.getFilePath editor uri))
  ([^js editor]
   (.getFilePath editor)))

(defn editor->file-uri
  ([^js editor uri]
   (.getFileUri editor uri))
  ([^js editor]
   (.getFileUri editor)))

(defn editor->diagnostics [^js editor uri]
  (.getDiagnostics editor uri))

(defn editor->symbols [^js editor uri]
  (.getSymbols editor uri))

(defn editor->db [^js editor]
  (.getDb editor))

(defn editor-query
  ([^js editor query params]
   (.query editor query params))
  ([^js editor query]
   (.query editor query)))

(defn wait-for
  [pred timeout-ms]
  (go
    (let [start (js/Date.now)]
      (loop []
        (if (pred)
          true
          (if (> (- (js/Date.now) start) timeout-ms)
            false
            (do
              (<! (timeout 10))
              (recur))))))))

(defn wait-for-ready [editor-ref timeout-ms]
  (wait-for #(some-> (ref->editor editor-ref) .isReady) timeout-ms))

(defn wait-for-uri [uri timeout-ms]
  (wait-for #(db/document-id-by-uri uri) timeout-ms))

(defn wait-for-event [events-atom event-type timeout-ms]
  (go
    (let [start (js/Date.now)]
      (loop []
        (cond
          (some #(= event-type (:type %)) @events-atom) true
          (> (- (js/Date.now) start) timeout-ms) false
          :else (do
                  (<! (timeout 10))
                  (recur)))))))

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

(defn cleanup-container [container]
  (when (.contains js/document.body container)
    (js/document.body.removeChild container)))

(deftest editor-renders
  (async done
         (go
           (let [container (js/document.createElement "div")]
             (js/document.body.appendChild container)
             (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}})]
               (<! (timeout 100))
               (is (some? (.querySelector container ".code-editor")) "Editor container rendered")
               (.unmount root)
               (<! (timeout 100))) ;; Delay to allow React cleanup.
             (cleanup-container container)
             (done)))))

(deftest editor-content-change-callback
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)]
             (js/document.body.appendChild container)
             (let [contents (atom [])
                   root (mount-component container {:onContentChange (fn [content]
                                                                       (swap! contents conj content))
                                                    :languages {"text" {:extensions [".txt"]}}
                                                    :ref ref})]
               (let [ready? (<! (wait-for-ready ref 1000))]
                 (is ready? "Editor ready within timeout"))
               (let [editor (ref->editor ref)]
                 (wrap-flush #(editor->open-document! editor "inmemory://test.txt" "initial" "text"))
                 (<! (timeout 100))
                 (wrap-flush #(editor->set-text! editor "updated" "inmemory://test.txt"))
                 (<! (timeout 100))
                 (is (= 2 (count @contents)) "Expected 2 contents.")
                 (is (= (nth @contents 0) "initial") "Initial text did not fire on-content-change")
                 (is (= (nth @contents 1) "updated") "Updated text did not fire on-content-change")
                 (.unmount root)
                 (<! (timeout 100))) ;; Delay to allow React cleanup.
             (cleanup-container container)
             (done))))))

(deftest language-config-key-normalization
  (let [props {:languages {"rholang" {"grammarWasm" "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
                                      "highlightQueryPath" "/extensions/lang/rholang/tree-sitter/queries/highlights.scm"
                                      "indentsQueryPath" "/extensions/lang/rholang/tree-sitter/queries/indents.scm"
                                      "lspUrl" "ws://localhost:41551"
                                      "extensions" [".rho"]
                                      "fileIcon" "fas fa-file-code text-primary"
                                      "fallbackHighlighter" "none"}}}
        state (#'lib.core/default-state props)]
    (is (= (get-in props [:languages "rholang" "grammarWasm"])
           (get-in state [:languages "rholang" :grammar-wasm]))
        "grammarWasm normalized to :grammar-wasm")
    (is (= (get-in props [:languages "rholang" "highlightQueryPath"])
           (get-in state [:languages "rholang" :highlight-query-path]))
        "highlightQueryPath normalized")
    (is (= (get-in props [:languages "rholang" "indentsQueryPath"])
           (get-in state [:languages "rholang" :indents-query-path]))
        "indentsQueryPath normalized")
    (is (= (get-in props [:languages "rholang" "lspUrl"])
           (get-in state [:languages "rholang" :lsp-url]))
        "lspUrl normalized")
    (is (= (get-in props [:languages "rholang" "extensions"])
           (get-in state [:languages "rholang" :extensions]))
        "extensions remain unchanged")
    (is (= (get-in props [:languages "rholang" "fileIcon"])
           (get-in state [:languages "rholang" :file-icon]))
        "fileIcon normalized")
    (is (= (get-in props [:languages "rholang" "fallbackHighlighter"])
           (get-in state [:languages "rholang" :fallback-highlighter]))
        "fallbackHighlighter normalized")))

(deftest open-before-connect-sends-after-initialized
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)
                 state-atom (atom {})
                 events (rxjs/Subject.)
                 mock-lsp-url "ws://mock"
                 mock-config (assoc config :lsp-url mock-lsp-url)
                 root (mount-component container {:languages {"rholang" mock-config}
                                                  :ref ref})]
             (let [ready? (<! (wait-for-ready ref 1000))]
               (is ready? "Editor ready within timeout"))
             (let [editor (ref->editor ref)]
               (<! (mock/with-mock-lsp "rholang" {:url mock-lsp-url} state-atom events
                     (fn [mock]
                       (go
                         (wrap-flush #(editor->open-document! editor "inmemory://test.rho" "content" "rholang"))
                         (<! (timeout 100))
                         (let [sent @(:sent mock)]
                           (is (empty? sent) "No messages sent before open"))
                         ((:trigger-open mock))
                         (<! (timeout 100))
                         (let [sent @(:sent mock)]
                           (is (= 1 (count sent)) "One message after open: initialize")
                           (is (str/includes? (first sent) "initialize") "Sent initialize"))
                         (mock/respond! mock 1 {:capabilities {}})
                         (<! (timeout 100))
                         (let [sent @(:sent mock)]
                           (is (= 3 (count sent)) "Two more after initialize: initialized, didOpen")
                           (is (str/includes? (second sent) "initialized") "Sent initialized")
                           (is (str/includes? (nth sent 2) "didOpen") "Sent didOpen"))
                         (.unmount root)
                         (<! (timeout 100)) ;; Delay to allow React cleanup.
                         (cleanup-container container)
                         (done))))))))))

(defn editor->highlightRange [^js editor ^js from ^js to]
  (editor->highlight-range! editor from to))

(deftest highlight-range-null-view
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)
                 captured-logs (atom [])]
             (log/merge-config! {:appenders {:capture {:enabled? true
                                                       :fn (fn [data] (swap! captured-logs conj data))}}})
             (js/document.body.appendChild container)
             (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                    :ref ref})]
               (let [ready? (<! (wait-for-ready ref 1000))]
                 (is ready? "Editor ready within timeout"))
               (let [editor (ref->editor ref)]
                 (wrap-flush #(editor->highlightRange editor #js {:line 1 :column 1} #js {:line 1 :column 5}))
                 (<! (timeout 100))
                 (.unmount root)
                 (<! (timeout 100))
                 (wrap-flush #(editor->highlightRange editor #js {:line 1 :column 1} #js {:line 1 :column 5}))
                 (<! (timeout 100))
                 (is (some #(str/includes? (str/join " " (:vargs %)) "Cannot highlight range: view-ref is nil") @captured-logs)
                     "Warning logged for null view after unmount")))
             (cleanup-container container)
             (done)))))

(deftest rename-document-notifies-lsp
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)
                 state-atom (atom {})
                 events (rxjs/Subject.)
                 mock-lsp-url "ws://mock"
                 mock-config (assoc config :lsp-url mock-lsp-url)
                 root (mount-component container {:languages {"rholang" mock-config}
                                                  :ref ref})]
             (let [ready? (<! (wait-for-ready ref 1000))]
               (is ready? "Editor ready within timeout"))
             (let [editor (ref->editor ref)]
               (<! (mock/with-mock-lsp "rholang" {:url mock-lsp-url} state-atom events
                     (fn [mock]
                       (go
                         (wrap-flush #(editor->open-document! editor "inmemory://old.rho" "test" "rholang"))
                         (<! (timeout 100))
                         ((:trigger-open mock))
                         (<! (timeout 100))
                         (mock/respond! mock 1 {:capabilities {}})
                         (<! (timeout 100))
                         (let [sent @(:sent mock)]
                           (is (= 3 (count sent)) "Sent initialize, initialized, didOpen"))
                         (reset! (:sent mock) [])
                         (wrap-flush #(editor->rename-document! editor "new.rho"))
                         (<! (timeout 100))
                         (<! (wait-for-uri "inmemory://new.rho" 1000))
                         (let [sent @(:sent mock)]
                           (is (= 1 (count sent)) "One message sent after rename: didRenameFiles")
                           (let [msg (first sent)]
                             (is (str/includes? msg "didRenameFiles") "Sent didRenameFiles")
                             (is (str/includes? msg "\"oldUri\":\"inmemory://old.rho\"") "didRenameFiles with old URI")
                             (is (str/includes? msg "\"newUri\":\"inmemory://new.rho\"") "didRenameFiles with new URI")))
                         (.unmount root)
                         (<! (timeout 100)) ;; Delay to allow React cleanup.
                         (cleanup-container container)
                         (done))))))))))

(deftest open-document-with-content
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)]
             (js/document.body.appendChild container)
             (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                    :ref ref})]
               (let [ready? (<! (wait-for-ready ref 1000))]
                 (is ready? "Editor ready within timeout"))
               (let [editor (ref->editor ref)]
                 (wrap-flush #(editor->open-document! editor "inmemory://test.txt" "initial content" "text"))
                 (<! (timeout 100))
                 (<! (wait-for-uri "inmemory://test.txt" 1000))
                 (is (= "initial content" (db/document-text-by-uri "inmemory://test.txt")) "Opened with provided content"))
               (.unmount root)
               (<! (timeout 100))) ;; Delay to allow React cleanup.
             (cleanup-container container)
             (done)))))

(deftest rxjs-events-emitted
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)
                 events (atom [])]
             (js/document.body.appendChild container)
             (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                    :ref ref})]
               (let [ready? (<! (wait-for-ready ref 1000))]
                 (is ready? "Editor ready within timeout"))
               (let [editor (ref->editor ref)]
                 (.subscribe (editor->events editor) #(swap! events conj (js->clj % :keywordize-keys true)))
                 (wrap-flush #(editor->open-document! editor "inmemory://test.txt" "initial" "text"))
                 (<! (timeout 100))
                 (<! (wait-for-event events "content-change" 1000))
                 (is (some #(= "content-change" (:type %)) @events) "Emitted content-change")
                 (wrap-flush #(editor->set-text! editor "updated" "inmemory://test.txt"))
                 (<! (timeout 100))
                 (<! (wait-for-event events "content-change" 1000))
                 (is (some #(= "content-change" (:type %)) @events) "Emitted content-change")
                 (wrap-flush #(editor->open-document! editor "inmemory://file.txt" "content" "text"))
                 (<! (timeout 100))
                 (<! (wait-for-event events "document-open" 1000))
                 (is (some #(= "document-open" (:type %)) @events) "Emitted document-open")
                 (wrap-flush #(editor->set-text! editor "updated"))
                 (<! (timeout 100))
                 (<! (wait-for-event events "content-change" 1000))
                 (is (some #(= "content-change" (:type %)) @events) "Emitted content-change on set-text for dirty")
                 (wrap-flush #(editor->save-document! editor))
                 (<! (timeout 100))
                 (<! (wait-for-event events "document-save" 1000))
                 (is (some #(= "document-save" (:type %)) @events) "Emitted document-save")
                 (wrap-flush #(editor->highlight-range! editor #js {:line 1 :column 1} #js {:line 1 :column 5}))
                 (<! (timeout 100))
                 (<! (wait-for-event events "highlight-change" 1000))
                 (is (some #(= "highlight-change" (:type %)) @events) "Emitted highlight-change")
                 (wrap-flush #(editor->clear-highlight! editor))
                 (<! (timeout 100))
                 (<! (wait-for-event events "highlight-change" 1000))
                 (is (some #(= "highlight-change" (:type %)) @events) "Emitted highlight-change")
                 ;; ----------------------------------------------------------
                 ;; NOTE: The test for "document-close" must come last because
                 ;; it will delete the document:
                 ;; ----------------------------------------------------------
                 (wrap-flush #(editor->close-document! editor))
                 (<! (timeout 100))
                 (<! (wait-for-event events "document-close" 1000))
                 (is (some #(= "document-close" (:type %)) @events) "Emitted document-close"))
               (.unmount root)
               (<! (timeout 100))) ;; Delay to allow React cleanup.
             (cleanup-container container)
             (done)))))

(deftest rholang-extension-integration
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)
                 state-atom (atom {})
                 events (rxjs/Subject.)
                 mock-lsp-url "ws://mock"
                 mock-config (assoc config :lsp-url mock-lsp-url)
                 root (mount-component container {:languages {"rholang" mock-config}
                                                  :ref ref})]
             (let [ready? (<! (wait-for-ready ref 1000))]
               (is ready? "Editor ready within timeout"))
             (let [editor (ref->editor ref)]
               (<! (mock/with-mock-lsp "rholang" {:url mock-lsp-url} state-atom events
                     (fn [mock]
                       (go
                         (wrap-flush #(editor->open-document! editor "inmemory://test.rho" "new x in { x!(\"Hello\") | Nil }" "rholang"))
                         (<! (timeout 100))
                         ((:trigger-open mock))
                         (<! (timeout 100))
                         (mock/respond! mock 1 {:capabilities {}})
                         (<! (timeout 100))
                         (let [state (js->clj (editor->state editor) :keywordize-keys true)]
                           (is (true? (get-in state [:lsp :rholang :initialized?])) "LSP initialized for Rholang"))
                         (.unmount root)
                         (<! (timeout 100)) ;; Delay to allow React cleanup.
                         (cleanup-container container)
                         (done))))))))))

(deftest editor-ready-event
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)
                 events (atom [])]
             (js/document.body.appendChild container)
             (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                    :ref ref})]
               (let [ready? (<! (wait-for-ready ref 1000))]
                 (is ready? "Editor ready within timeout"))
               (let [editor (ref->editor ref)]
                 (.subscribe (editor->events editor) #(swap! events conj (js->clj % :keywordize-keys true)))
                 (<! (wait-for-event events "ready" 1000))
                 (is (some #(= "ready" (:type %)) @events) "Emitted ready event on initialization"))
               (.unmount root)
               (<! (timeout 100))) ;; Delay to allow React cleanup.
             (cleanup-container container)
             (done)))))

(deftest selection-change-event
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)
                 events (atom [])]
             (js/document.body.appendChild container)
             (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                    :ref ref})]
               (let [ready? (<! (wait-for-ready ref 1000))]
                 (is ready? "Editor ready within timeout"))
               (let [editor (ref->editor ref)]
                 (.subscribe (editor->events editor) #(swap! events conj (js->clj % :keywordize-keys true)))
                 (wrap-flush #(editor->set-selection! editor #js {:line 1 :column 1} #js {:line 1 :column 7}))
                 (<! (timeout 100))
                 (<! (wait-for-event events "selection-change" 1000))
                 (is (some #(= "selection-change" (:type %)) @events)
                     "Emitted selection-change with cursor data"))
               (.unmount root)
               (<! (timeout 100))) ;; Delay to allow React cleanup.
             (cleanup-container container)
             (done)))))

(deftest clear-highlight
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)]
             (js/document.body.appendChild container)
             (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                    :ref ref})]
               (let [ready? (<! (wait-for-ready ref 1000))]
                 (is ready? "Editor ready within timeout"))
               (let [editor (ref->editor ref)]
                 (wrap-flush #(editor->highlight-range! editor #js {:line 1 :column 1} #js {:line 1 :column 5}))
                 (<! (timeout 100))
                 (wrap-flush #(editor->clear-highlight! editor))
                 (<! (timeout 100))
                 ;; No direct assertion, but ensure no error
                 (is true "clearHighlight called without error")
                 (.unmount root)
                 (<! (timeout 100))) ;; Delay to allow React cleanup.
             (cleanup-container container)
             (done))))))

(deftest center-on-range
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)]
             (js/document.body.appendChild container)
             (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                    :ref ref})]
               (let [ready? (<! (wait-for-ready ref 1000))]
                 (is ready? "Editor ready within timeout"))
               (let [editor (ref->editor ref)]
                 (wrap-flush #(editor->center-on-range! editor #js {:line 2 :column 1} #js {:line 2 :column 10}))
                 (<! (timeout 100))
                 ;; No direct assertion for scroll, but ensure no error
                 (is true "centerOnRange called without error")
                 (.unmount root)
                 (<! (timeout 100))) ;; Delay to allow React cleanup.
             (cleanup-container container)
             (done))))))

(deftest get-text-set-text
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)]
             (js/document.body.appendChild container)
             (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                    :ref ref})]
               (let [ready? (<! (wait-for-ready ref 1000))]
                 (is ready? "Editor ready within timeout"))
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
             (done)))))

(deftest get-file-path-and-uri
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)]
             (js/document.body.appendChild container)
             (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                    :ref ref})]
               (let [ready? (<! (wait-for-ready ref 1000))]
                 (is ready? "Editor ready within timeout"))
               (let [editor (ref->editor ref)]
                 (wrap-flush #(editor->open-document! editor "demo.txt" "content" "text"))
                 (<! (timeout 100))
                 (<! (wait-for-uri "inmemory://demo.txt" 1000))
                 (is (= "demo.txt" (editor->file-path editor)) "getFilePath returns path")
                 (is (= "inmemory://demo.txt" (editor->file-uri editor)) "getFileUri returns full URI"))
               (.unmount root)
               (<! (timeout 100))) ;; Delay to allow React cleanup.
             (cleanup-container container)
             (done)))))

(deftest activate-document
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)]
             (js/document.body.appendChild container)
             (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                    :ref ref})]
               (let [ready? (<! (wait-for-ready ref 1000))]
                 (is ready? "Editor ready within timeout"))
               (let [editor (ref->editor ref)]
                 (wrap-flush #(editor->open-document! editor "inmemory://first.txt" "first" "text" false))
                 (<! (timeout 100))
                 (<! (wait-for-uri "inmemory://first.txt" 1000))
                 (wrap-flush #(editor->open-document! editor "inmemory://second.txt" "second" "text"))
                 (<! (timeout 100))
                 (<! (wait-for-uri "inmemory://second.txt" 1000))
                 (is (= "second" (editor->text editor)) "Second document active")
                 (wrap-flush #(editor->activate-document! editor "inmemory://first.txt"))
                 (<! (timeout 100))
                 (is (= "first" (editor->text editor)) "Activated first document"))
               (.unmount root)
               (<! (timeout 100))) ;; Delay to allow React cleanup.
             (cleanup-container container)
             (done)))))

(deftest query-and-get-db
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)]
             (js/document.body.appendChild container)
             (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                    :ref ref})]
               (let [ready? (<! (wait-for-ready ref 1000))]
                 (is ready? "Editor ready within timeout"))
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
             (done)))))

(deftest get-diagnostics-and-symbols
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)]
             (js/document.body.appendChild container)
             (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                    :ref ref})]
               (let [ready? (<! (wait-for-ready ref 1000))]
                 (is ready? "Editor ready within timeout"))
               (let [editor (ref->editor ref)]
                 (wrap-flush #(editor->open-document! editor "inmemory://test.txt" "test" "text"))
                 (<! (timeout 100))
                 ;; Mock diagnostics and symbols in db
                 (let [db (editor->db editor)
                       doc-id (db/document-id-by-uri "inmemory://test.txt")
                       diag-tx [{:db/id -1
                                 :diagnostic/document doc-id
                                 :diagnostic/message "test diag"
                                 :diagnostic/severity 1
                                 :diagnostic/start-line 0
                                 :diagnostic/start-char 0
                                 :diagnostic/end-line 0
                                 :diagnostic/end-char 4
                                 :type :diagnostic}]
                       sym-tx [{:db/id -2
                                :symbol/document doc-id
                                :symbol/name "test sym"
                                :symbol/kind 1
                                :symbol/start-line 0
                                :symbol/start-char 0
                                :symbol/end-line 0
                                :symbol/end-char 4
                                :symbol/selection-start-line 0
                                :symbol/selection-start-char 0
                                :symbol/selection-end-line 0
                                :symbol/selection-end-char 4
                                :type :symbol}]]
                   (d/transact! db diag-tx)
                   (d/transact! db sym-tx)
                   (let [diags (editor->diagnostics editor "inmemory://test.txt")]
                     (is (= 1 (.-length diags)) "getDiagnostics returns array")
                     (is (= "test diag" (.-message (aget diags 0))) "Diagnostic message matches"))
                   (let [syms (editor->symbols editor "inmemory://test.txt")]
                     (is (= 1 (.-length syms)) "getSymbols returns array")
                     (is (= "test sym" (.-name (aget syms 0))) "Symbol name matches"))))
               (.unmount root)
               (<! (timeout 100))) ;; Delay to allow React cleanup.
             (cleanup-container container)
             (done)))))

(deftest highlight-event-emission
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)
                 events (atom [])]
             (js/document.body.appendChild container)
             (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                    :ref ref})]
               (let [ready? (<! (wait-for-ready ref 1000))]
                 (is ready? "Editor ready within timeout"))
               (let [editor (ref->editor ref)]
                 (.subscribe (editor->events editor) #(swap! events conj (js->clj % :keywordize-keys true)))
                 (wrap-flush #(editor->open-document! editor "inmemory://test.txt" "123456" "text"))
                 (wrap-flush #(editor->highlight-range! editor #js {:line 1 :column 1} #js {:line 1 :column 5}))
                 (<! (timeout 100))
                 (<! (wait-for-event events "highlight-change" 1000))
                 (let [evt (first (filter #(= "highlight-change" (:type %)) @events))]
                   (is (= {:from {:line 1 :column 1} :to {:line 1 :column 5}} (:data evt)) "Emitted highlight-change with range"))
                 (wrap-flush #(editor->clear-highlight! editor))
                 (<! (timeout 100))
                 (<! (wait-for-event events "highlight-change" 1000))
                 (let [evt (last (filter #(= "highlight-change" (:type %)) @events))]
                   (is (nil? (:data evt)) "Emitted highlight-change with nil on clear")))
               (.unmount root)
               (<! (timeout 100))) ;; Delay to allow React cleanup.
             (cleanup-container container)
             (done)))))

(let [gen-line gen/small-integer
      gen-column gen/small-integer
      gen-pos (gen/hash-map :line gen-line :column gen-column)
      gen-action (gen/one-of [(gen/tuple (gen/return :highlight) gen-pos gen-pos)
                              (gen/return [:clear])])
      prop (prop/for-all [actions (gen/vector gen-action 1 10)]
                         (async done
                                (go
                                  (let [container (js/document.createElement "div")
                                        ref (react/createRef)
                                        events (atom [])]
                                    (js/document.body.appendChild container)
                                    (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                                           :ref ref})]
                                      (let [ready? (<! (wait-for-ready ref 1000))]
                                        (is ready? "Editor ready within timeout"))
                                      (let [editor (ref->editor ref)]
                                        (.subscribe (editor->events editor) #(swap! events conj (js->clj % :keywordize-keys true)))
                                        (doseq [action actions]
                                          (let [cmd (first action)]
                                            (wrap-flush #(case cmd
                                                           :highlight (editor->highlight-range! editor (clj->js (second action)) (clj->js (nth action 2)))
                                                           :clear (editor->clear-highlight! editor))))
                                          (<! (timeout 10)))
                                        (let [highlight-events (filter #(= "highlight-change" (:type %)) @events)]
                                          (is (= (count actions) (count highlight-events)) "Event emitted for each action")
                                          (is (every? #(or (map? (:data %)) (nil? (:data %))) highlight-events) "Data is range map or nil")))
                                      (.unmount root)
                                      (<! (timeout 100))) ;; Delay to allow React cleanup.
                                    (cleanup-container container)
                                    (done))
                                  true)))]
  (deftest highlight-event-property
    (let [result (tc/quick-check 10 prop {:seed 42})]
      (is (:result result)))))

(deftest highlight-change-event-emission
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)
                 events (atom [])]
             (js/document.body.appendChild container)
             (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                    :ref ref})]
               (let [ready? (<! (wait-for-ready ref 1000))]
                 (is ready? "Editor ready within timeout"))
               (let [editor (ref->editor ref)]
                 (.subscribe (editor->events editor) #(swap! events conj (js->clj % :keywordize-keys true)))
                 (wrap-flush #(editor->open-document! editor "inmemory://test.txt" "123456" "text"))
                 (wrap-flush #(editor->highlight-range! editor #js {:line 1 :column 1} #js {:line 1 :column 6}))
                 (<! (timeout 100))
                 (<! (wait-for-event events "highlight-change" 1000))
                 (let [evt (first (filter #(= "highlight-change" (:type %)) @events))]
                   (is (= {:from {:line 1 :column 1} :to {:line 1 :column 6}} (:data evt)) "Emitted highlight-change with range"))
                 (wrap-flush #(editor->clear-highlight! editor))
                 (<! (timeout 100))
                 (<! (wait-for-event events "highlight-change" 1000))
                 (let [evt (last (filter #(= "highlight-change" (:type %)) @events))]
                   (is (nil? (:data evt)) "Emitted highlight-change with nil on clear")))
               (.unmount root)
               (<! (timeout 100))) ;; Delay to allow React cleanup.
             (cleanup-container container)
             (done)))))

(deftest default-protocol-in-open-document
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)]
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
             (done)))))

(deftest open-document-without-activating
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)]
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
             (done)))))

(deftest language-change-event
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)
                 events (atom [])]
             (js/document.body.appendChild container)
             (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}
                                                                "rholang" config}
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
               (<! (timeout 100))) ;; Delay to allow React cleanup.
             (cleanup-container container)
             (done)))))

(deftest destroy-event-on-unmount
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)
                 events (atom [])]
             (js/document.body.appendChild container)
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
               (done))))))

(deftest error-event-on-invalid-method
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)
                 events (atom [])]
             (js/document.body.appendChild container)
             (let [root (mount-component container {:languages {"text" {:extensions [".txt"]}}
                                                    :ref ref})]
               (let [ready? (<! (wait-for-ready ref 1000))]
                 (is ready? "Editor ready within timeout"))
               (let [editor (ref->editor ref)]
                 (.subscribe (editor->events editor) #(swap! events conj (js->clj % :keywordize-keys true)))
                 (wrap-flush #(editor->set-cursor! editor #js {:line -1 :column 1})) ;; Invalid pos
                 (<! (timeout 100))
                 (is (some #(= "error" (:type %)) @events)
                     "Emitted error for invalid cursor"))
               (.unmount root)
               (<! (timeout 100))) ;; Delay to allow React cleanup.
             (cleanup-container container)
             (done)))))

(deftest fallback-to-basic-on-no-lsp
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)]
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
             (done)))))

(deftest open-document-full-uri
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)]
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
             (done)))))

(deftest query-complex
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)]
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
             (done)))))

(let [gen-line gen/small-integer
      gen-column gen/small-integer
      gen-pos (gen/hash-map :line gen-line :column gen-column)
      gen-doc (gen/vector gen/string-alphanumeric 1 100)
      prop (prop/for-all [doc gen-doc
                          pos gen-pos]
                         (let [text (str/join "\n" doc)
                               state (.create EditorState #js {:doc text :extensions #js []})
                               ^js cm-doc (.-doc state)
                               offset (u/pos-to-offset cm-doc pos true)
                               back-pos (when offset (u/offset-to-pos cm-doc offset true))]
                           (if (and offset (<= (:line pos) (count doc)))
                             (= pos back-pos)
                             true)))]
  (deftest pos-offset-roundtrip-property
    (let [result (tc/quick-check 50 prop {:seed 42})]
      (is (:result result)))))
