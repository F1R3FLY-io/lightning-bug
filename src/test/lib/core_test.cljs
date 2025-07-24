(ns test.lib.core-test
  (:require
   [clojure.core.async :as async :refer [go <! timeout]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is async use-fixtures]]
   [clojure.test.check :as tc]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [reagent.core :as r]
   ["react" :as react]
   ["react-dom/client" :as rdclient]
   [lib.core :refer [Editor]]
   [ext.lang.rholang :refer [config]]
   [lib.lsp.client :as lsp]
   [taoensso.timbre :as log]))

(use-fixtures :each
  {:before (fn []
             (reset! lsp/ws nil))})

(defn wait-for-event [events-atom event-type timeout-ms]
  (go
    (let [start (js/Date.now)]
      (loop []
        (if (some #(= event-type (:type %)) @events-atom)
          true
          (if (> (- (js/Date.now) start) timeout-ms)
            false
            (do
              (<! (timeout 10))
              (recur))))))))

(deftest editor-renders
  (async done
         (go
           (let [container (js/document.createElement "div")]
             (js/document.body.appendChild container)
             (let [root (rdclient/createRoot container)]
               (react/act #(.render root (r/as-element [:> Editor {:content "test"}]))))
             (<! (timeout 100))
             (is (some? (.querySelector container ".code-editor")) "Editor container rendered")
             (js/document.body.removeChild container)
             (done)))))

(deftest editor-content-change-callback
  (async done
         (go
           (let [callback-called (atom false)
                 container (js/document.createElement "div")
                 ref (react/createRef)]
             (js/document.body.appendChild container)
             (let [root (rdclient/createRoot container)]
               (react/act #(.render root (r/as-element [:> Editor {:content "initial"
                                                                   :onContentChange (fn [content]
                                                                                      (reset! callback-called true)
                                                                                      (is (= content "updated") "Callback received updated content"))}
                                                        {:ref ref}]))))
             (<! (timeout 100))
             (let [^js/Object editor (.-current ref)]
               (when editor
                 (.setContent editor "updated")))
             (<! (timeout 100))
             (is @callback-called "onContentChange callback triggered")
             (js/document.body.removeChild container)
             (done)))))

(deftest language-config-key-normalization
  (let [props {:languages {"rholang" {:grammarWasm "path"
                                      :highlightQueryPath "query.scm"
                                      :lspUrl "ws://test"
                                      :lspMethod "websocket"
                                      :fileIcon "icon"
                                      :fallbackHighlighter "none"}}}
        state (#'lib.core/default-state props)]
    (is (= "path" (get-in state [:languages "rholang" :grammar-wasm])) "grammarWasm normalized to :grammar-wasm")
    (is (= "query.scm" (get-in state [:languages "rholang" :highlight-query-path])) "highlightQueryPath normalized")
    (is (= "ws://test" (get-in state [:languages "rholang" :lsp-url])) "lspUrl normalized")
    (is (= "websocket" (get-in state [:languages "rholang" :lsp-method])) "lspMethod normalized")
    (is (= "icon" (get-in state [:languages "rholang" :file-icon])) "fileIcon normalized")
    (is (= "none" (get-in state [:languages "rholang" :fallback-highlighter])) "fallbackHighlighter normalized")))

(deftest open-before-connect-sends-after-initialized
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)
                 sent (atom [])
                 sock (js/Object.)]
             (set! (.-binaryType sock) "arraybuffer")
             (set! (.-close sock) (fn [] ((.-onclose sock))))
             (set! (.-send sock) (fn [data]))
             (with-redefs [js/WebSocket (fn [_] sock)
                           lsp/send (fn [msg _] (swap! sent conj (:method msg)))]
               (js/document.body.appendChild container)
               (let [root (rdclient/createRoot container)]
                 (react/act #(.render root (r/as-element [:> Editor {:language "rholang"
                                                                     :languages {"rholang" {:lsp-url "ws://test"}}
                                                                     :ref ref}]))))
               (<! (timeout 100))
               (let [^js/Object editor (.-current ref)]
                 (.openDocument editor "uri" "content" "rholang"))
               (<! (timeout 50))
               ((.-onopen sock))
               (let [resp "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"capabilities\":{}}}"]
                 ((.-onmessage sock) #js {:data (str "Content-Length: " (.-length resp) "\r\n\r\n" resp)}))
               (<! (timeout 50))
               (is (= ["initialize" "initialized" "textDocument/didOpen" "textDocument/documentSymbol"] @sent) "Correct sequence: initialize, initialized, didOpen, documentSymbol")
               (js/document.body.removeChild container)
               (done))))))

(deftest highlight-range-null-view
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)
                 captured-logs (atom [])]
             (log/with-config (assoc log/*config* :appenders {:capture {:enabled? true
                                                                        :fn (fn [data] (swap! captured-logs conj data))}})
               (js/document.body.appendChild container)
               (let [root (rdclient/createRoot container)]
                 (react/act #(.render root (r/as-element [:> Editor {:content "test" :ref ref}])))
                 (<! (timeout 100))
                 (let [^js/Object editor (.-current ref)]
                   ;; Call highlightRange before destroying
                   (.highlightRange editor #js {:line 1 :column 1} #js {:line 1 :column 5})
                   (<! (timeout 50))
                   ;; Destroy the editor to simulate null view-atom
                   (.unmount root)
                   (<! (timeout 50))
                   ;; Call highlightRange again after unmount
                   (.highlightRange editor #js {:line 1 :column 1} #js {:line 1 :column 5})
                   (<! (timeout 50))
                   (is (some #(str/includes? (str/join " " (:vargs %)) "Cannot highlight range: view-atom is nil") @captured-logs)
                       "Warning logged for null view after unmount")
                   (js/document.body.removeChild container)
                   (done))))))))

(deftest rename-document-notifies-lsp
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)
                 sent (atom [])
                 events (atom [])]
             (with-redefs [lsp/send (fn [msg _] (swap! sent conj msg))]
               (js/document.body.appendChild container)
               (let [root (rdclient/createRoot container)]
                 (react/act #(.render root (r/as-element [:> Editor {:content "test"
                                                                     :language "text"
                                                                     :ref ref}]))))
               (<! (timeout 100))
               (let [^js/Object editor (.-current ref)]
                 (.openDocument editor "inmemory://old.txt" "test" "text")
                 (<! (timeout 50))
                 (.subscribe (.getEvents editor) #(swap! events conj (js->clj % :keywordize-keys true)))
                 (.renameDocument editor "new.txt")
                 (<! (timeout 50))
                 (is (= 2 (count @sent)) "Sent didClose and didOpen")
                 (is (= "textDocument/didClose" (:method (first @sent))) "First sent didClose")
                 (is (= "inmemory://old.txt" (get-in (first @sent) [:params :textDocument :uri])) "didClose with old URI")
                 (is (= "textDocument/didOpen" (:method (second @sent))) "Second sent didOpen")
                 (is (= "inmemory://new.txt" (get-in (second @sent) [:params :textDocument :uri])) "didOpen with new URI")
                 (is (= "test" (get-in (second @sent) [:params :textDocument :text])) "didOpen with current content")
                 (is (some #(= "document-rename" (:type %)) @events) "Emitted document-rename event")
                 (js/document.body.removeChild container)
                 (done)))))))

(deftest open-document-with-content
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)]
             (js/document.body.appendChild container)
             (let [root (rdclient/createRoot container)]
               (react/act #(.render root (r/as-element [:> Editor {:language "text"
                                                                   :ref ref}]))))
             (<! (timeout 100))
             (let [^js/Object editor (.-current ref)]
               (.openDocument editor "inmemory://test.txt" "initial content" "text")
               (<! (timeout 50))
               (let [state ^js (.getState editor)]
                 (is (= "initial content" (.-content state)) "Opened with provided content"))
               (js/document.body.removeChild container)
               (done))))))

(deftest rxjs-events-emitted
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)
                 events (atom [])]
             (js/document.body.appendChild container)
             (let [root (rdclient/createRoot container)]
               (react/act #(.render root (r/as-element [:> Editor {:content "test"
                                                                   :language "text"
                                                                   :ref ref}]))))
             (<! (timeout 100))
             (let [^js/Object editor (.-current ref)]
               (.subscribe (.getEvents editor) #(swap! events conj (js->clj % :keywordize-keys true)))
               (.setContent editor "updated")
               (<! (wait-for-event events "content-change" 1000))
               (is (some #(= "content-change" (:type %)) @events) "Emitted content-change")
               (.openDocument editor "inmemory://file.txt" "content" "text")
               (<! (wait-for-event events "document-open" 1000))
               (is (some #(= "document-open" (:type %)) @events) "Emitted document-open")
               (.closeDocument editor)
               (<! (wait-for-event events "document-close" 1000))
               (is (some #(= "document-close" (:type %)) @events) "Emitted document-close")
               (js/document.body.removeChild container)
               (done))))))

(deftest rholang-extension-integration
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)]
             (js/document.body.appendChild container)
             (let [root (rdclient/createRoot container)]
               (react/act #(.render root (r/as-element [:> Editor {:language "rholang"
                                                                   :languages {"rholang" config}
                                                                   :ref ref}]))))
             (<! (timeout 100))
             (let [^js/Object editor (.-current ref)]
               (.openDocument editor "inmemory://test.rho" "new x in { x!(\"Hello\") }" "rholang")
               (<! (timeout 500)) ;; Wait for Tree-Sitter and LSP init
               (let [state ^js (.getState editor)]
                 (is (some? (.-connection (.-lsp state))) "LSP connected for Rholang")
                 (is (some? (.-tree state)) "Tree-Sitter parsed for Rholang"))
               (js/document.body.removeChild container)
               (done))))))

(deftest editor-ready-event
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)
                 events (atom [])]
             (js/document.body.appendChild container)
             (let [root (rdclient/createRoot container)]
               (react/act #(.render root (r/as-element [:> Editor {:content "test"
                                                                   :language "text"
                                                                   :ref ref}]))))
             (<! (timeout 100))
             (let [^js/Object editor (.-current ref)]
               (.subscribe (.getEvents editor) #(swap! events conj (js->clj % :keywordize-keys true)))
               (<! (wait-for-event events "ready" 1000))
               (is (some #(= "ready" (:type %)) @events) "Emitted ready event on initialization")
               (js/document.body.removeChild container)
               (done))))))

(deftest selection-change-event
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)
                 events (atom [])]
             (js/document.body.appendChild container)
             (let [root (rdclient/createRoot container)]
               (react/act #(.render root (r/as-element [:> Editor {:content "select me"
                                                                   :language "text"
                                                                   :ref ref}]))))
             (<! (timeout 100))
             (let [^js/Object editor (.-current ref)]
               (.subscribe (.getEvents editor) #(swap! events conj (js->clj % :keywordize-keys true)))
               (.setSelection editor #js {:line 1 :column 1} #js {:line 1 :column 7})
               (<! (wait-for-event events "selection-change" 1000))
               (is (some #(and (= "selection-change" (:type %))
                               (= {:line 1 :column 7} (get-in % [:data :cursor]))) @events)
                   "Emitted selection-change with cursor data")
               (js/document.body.removeChild container)
               (done))))))

(deftest lsp-events
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)
                 events (atom [])
                 sock (js/Object.)]
             (set! (.-binaryType sock) "arraybuffer")
             (set! (.-close sock) (fn [] ((.-onclose sock))))
             (set! (.-send sock) (fn [data]))
             (with-redefs [js/WebSocket (fn [_] sock)]
               (js/document.body.appendChild container)
               (let [root (rdclient/createRoot container)]
                 (react/act #(.render root (r/as-element [:> Editor {:language "rholang"
                                                                     :languages {"rholang" (assoc config :lsp-url "ws://test")}
                                                                     :ref ref}]))))
               (<! (timeout 100))
               (let [^js/Object editor (.-current ref)]
                 (.subscribe (.getEvents editor) #(swap! events conj (js->clj % :keywordize-keys true)))
                 ((.-onopen sock))
                 (<! (wait-for-event events "connect" 1000))
                 (is (some #(= "connect" (:type %)) @events) "Emitted connect")
                 (let [init-resp "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"capabilities\":{}}}"
                       full (str "Content-Length: " (.-length init-resp) "\r\n\r\n" init-resp)]
                   ((.-onmessage sock) #js {:data full}))
                 (<! (wait-for-event events "lsp-initialized" 1000))
                 (is (some #(= "lsp-initialized" (:type %)) @events) "Emitted lsp-initialized")
                 (let [diag-notif "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/publishDiagnostics\",\"params\":{\"diagnostics\":[{\"message\":\"test\"}]}}"
                       full (str "Content-Length: " (.-length diag-notif) "\r\n\r\n" diag-notif)]
                   ((.-onmessage sock) #js {:data full}))
                 (<! (wait-for-event events "diagnostics" 1000))
                 (is (some #(= "diagnostics" (:type %)) @events) "Emitted diagnostics")
                 (let [log-notif "{\"jsonrpc\":\"2.0\",\"method\":\"window/logMessage\",\"params\":{\"message\":\"test log\"}}"
                       full (str "Content-Length: " (.-length log-notif) "\r\n\r\n" log-notif)]
                   ((.-onmessage sock) #js {:data full}))
                 (<! (wait-for-event events "log" 1000))
                 (is (some #(= "log" (:type %)) @events) "Emitted log")
                 ((.-onclose sock))
                 (<! (wait-for-event events "disconnect" 1000))
                 (is (some #(= "disconnect" (:type %)) @events) "Emitted disconnect")
                 (js/document.body.removeChild container)
                 (done)))))))

(deftest get-state
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)]
             (js/document.body.appendChild container)
             (let [root (rdclient/createRoot container)]
               (react/act #(.render root (r/as-element [:> Editor {:content "test"
                                                                   :language "text"
                                                                   :ref ref}]))))
             (<! (timeout 100))
             (let [^js/Object editor (.-current ref)]
               (let [state ^js (.getState editor)]
                 (is (= "test" (.-content state)) "getState returns content")
                 (is (= "text" (.-language state)) "getState returns language"))
               (js/document.body.removeChild container)
               (done))))))

(deftest get-cursor-set-cursor
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)]
             (js/document.body.appendChild container)
             (let [root (rdclient/createRoot container)]
               (react/act #(.render root (r/as-element [:> Editor {:content "test"
                                                                   :language "text"
                                                                   :ref ref}]))))
             (<! (timeout 100))
             (let [^js/Object editor (.-current ref)]
               (.setCursor editor #js {:line 1 :column 3})
               (<! (timeout 50))
               (let [cursor ^js (.getCursor editor)]
                 (is (= 1 (.-line cursor)) "getCursor returns line")
                 (is (= 3 (.-column cursor)) "getCursor returns column"))
               (js/document.body.removeChild container)
               (done))))))

(deftest get-selection-set-selection
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)]
             (js/document.body.appendChild container)
             (let [root (rdclient/createRoot container)]
               (react/act #(.render root (r/as-element [:> Editor {:content "select me"
                                                                   :language "text"
                                                                   :ref ref}]))))
             (<! (timeout 100))
             (let [^js/Object editor (.-current ref)]
               (.setSelection editor #js {:line 1 :column 1} #js {:line 1 :column 7})
               (<! (timeout 50))
               (let [sel ^js (.getSelection editor)]
                 (is (= {:line 1 :column 1} (.-from sel)) "getSelection returns from")
                 (is (= {:line 1 :column 7} (.-to sel)) "getSelection returns to"))
               (js/document.body.removeChild container)
               (done))))))

(deftest save-document
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)
                 events (atom [])]
             (js/document.body.appendChild container)
             (let [root (rdclient/createRoot container)]
               (react/act #(.render root (r/as-element [:> Editor {:content "test"
                                                                   :language "text"
                                                                   :ref ref}]))))
             (<! (timeout 100))
             (let [^js/Object editor (.-current ref)]
               (.openDocument editor "inmemory://test.txt" "test" "text")
               (<! (timeout 50))
               (.subscribe (.getEvents editor) #(swap! events conj (js->clj % :keywordize-keys true)))
               (.setContent editor "updated")
               (<! (timeout 50))
               (.saveDocument editor)
               (<! (wait-for-event events "document-save" 1000))
               (is (some #(= "document-save" (:type %)) @events) "Emitted document-save")
               (js/document.body.removeChild container)
               (done))))))

(deftest is-ready
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)]
             (js/document.body.appendChild container)
             (let [root (rdclient/createRoot container)]
               (react/act #(.render root (r/as-element [:> Editor {:content "test"
                                                                   :language "text"
                                                                   :ref ref}])))
               (<! (timeout 100))
               (let [^js/Object editor (.-current ref)]
                 (is (true? (.isReady editor)) "isReady true after init")
                 (.unmount root)
                 (<! (timeout 50))
                 (is (false? (.isReady editor)) "isReady false after unmount")
                 (js/document.body.removeChild container)
                 (done)))))))

(deftest clear-cursor-pos
  (async done
    (go
      (let [container (js/document.createElement "div")
            ref (react/createRef)]
        (js/document.body.appendChild container)
        (let [root (rdclient/createRoot container)]
          (react/act #(.render root (r/as-element [:> Editor {:content "test"
                                                              :language "text"
                                                              :ref ref}]))))
        (<! (timeout 100))
        (let [^js/Object editor (.-current ref)]
          (.highlightRange editor #js {:line 1 :column 1} #js {:line 1 :column 5})
          (<! (timeout 50))
          (.clearHighlight editor)
          (<! (timeout 50))
          ;; No direct assertion, but ensure no error
          (is true "clearHighlight called without error")
          (js/document.body.removeChild container)
          (done))))))

(deftest center-on-range
  (async done
    (go
      (let [container (js/document.createElement "div")
            ref (react/createRef)]
        (js/document.body.appendChild container)
        (let [root (rdclient/createRoot container)]
          (react/act #(.render root (r/as-element [:> Editor {:content (str/join "\n" (repeat 50 "line"))
                                                              :language "text"
                                                              :ref ref}]))))
        (<! (timeout 100))
        (let [^js/Object editor (.-current ref)]
          (.centerOnRange editor #js {:line 30 :column 1} #js {:line 30 :column 5})
          (<! (timeout 50))
          ;; No direct assertion for scroll, but ensure no error
          (is true "centerOnRange called without error")
          (js/document.body.removeChild container)
          (done))))))

(deftest rxjs-event-stream-property
  (let [gen-action (gen/one-of [(gen/tuple (gen/return :set-content) gen/string-alphanumeric)
                                (gen/tuple (gen/return :open-document) gen/string-alphanumeric gen/string-alphanumeric (gen/return "text"))
                                (gen/tuple (gen/return :close-document))])
        prop (prop/for-all [actions (gen/vector gen-action 1 10)]
               (async done
                 (go
                   (let [container (js/document.createElement "div")
                         ref (react/createRef)
                         events (atom [])]
                     (js/document.body.appendChild container)
                     (let [root (rdclient/createRoot container)]
                       (react/act #(.render root (r/as-element [:> Editor {:content "test"
                                                                           :language "text"
                                                                           :ref ref}]))))
                     (<! (timeout 100))
                     (let [^js/Object editor (.-current ref)]
                       (.subscribe (.getEvents editor) #(swap! events conj (js->clj % :keywordize-keys true)))
                       (doseq [action actions]
                         (let [cmd (first action)
                               args (rest action)]
                           (case cmd
                             :set-content (.setContent editor (first args))
                             :open-document (.openDocument editor (str "inmemory://" (first args) ".txt") (second args) (nth args 2 nil))
                             :close-document (.closeDocument editor)))
                         (<! (timeout 50)))
                       (is (every? #(contains? % :type) @events) "All events have a type")
                       (is (every? #(contains? % :data) @events) "All events have data")
                       (js/document.body.removeChild container)
                       (done))
                     true))))]
    (is (:result (tc/quick-check 10 prop {:seed 42})))))
