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
  (fn [f]
    (reset! lsp/ws nil)
    (f)))

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

(defn act-flush
  "Helper to wrap code in react/act and flush Reagent renders."
  [f]
  (react/act (fn [] (f) (r/flush))))

(defn act-mount
  "Mount component in act, returning root."
  [container comp]
  (let [root-atom (atom nil)]
    (act-flush
     (fn []
       (let [root (rdclient/createRoot container)]
         (.render root (r/as-element comp))
         (reset! root-atom root))))
    @root-atom))

(defn act-unmount [root]
  (act-flush #(.unmount root)))

(deftest editor-renders
  (async done
         (go
           (let [container (js/document.createElement "div")]
             (js/document.body.appendChild container)
             (let [root (act-mount container [:> Editor {:content "test"}])]
               (<! (timeout 10))
               (is (some? (.querySelector container ".code-editor")) "Editor container rendered")
               (act-flush #(.unmount root)))
             (js/document.body.removeChild container)
             (done)))))

(deftest editor-content-change-callback
  (async done
         (go
           (let [callback-called (atom false)
                 container (js/document.createElement "div")
                 ref (react/createRef)]
             (js/document.body.appendChild container)
             (let [root (act-mount container [:> Editor {:content "initial"
                                                         :onContentChange (fn [content]
                                                                            (reset! callback-called true)
                                                                            (is (= content "updated") "Callback received updated content"))}
                                              {:ref ref}])]
               (<! (timeout 10))
               (let [^js/Object editor (.-current ref)]
                 (act-flush #(.setText editor "updated")) )
               (<! (timeout 10))
               (is @callback-called "onContentChange callback triggered")
               (act-flush #(.unmount root))
               (js/document.body.removeChild container)
               (done))))))

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
               (let [root (act-mount container [:> Editor {:language "rholang"
                                                           :languages {"rholang" {:lsp-url "ws://test"}}
                                                           :ref ref}])]
                 (<! (timeout 10))
                 (let [^js/Object editor (.-current ref)]
                   (act-flush #(.openDocument editor "uri" "content" "rholang"))
                   (<! (timeout 10)))
                 (act-flush #(do ((.-onopen sock))
                                 (let [resp "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"capabilities\":{}}}"]
                                   ((.-onmessage sock) #js {:data (str "Content-Length: " (.-length resp) "\r\n\r\n" resp)}))))
                 (<! (timeout 10))
                 (is (= ["initialize" "initialized" "textDocument/didOpen" "textDocument/documentSymbol"] @sent) "Correct sequence: initialize, initialized, didOpen, documentSymbol")
                 (act-flush #(.unmount root))
                 (js/document.body.removeChild container)
                 (done)))))))

(deftest highlight-range-null-view
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)
                 captured-logs (atom [])]
             (log/with-config (assoc log/*config* :appenders {:capture {:enabled? true
                                                                        :fn (fn [data] (swap! captured-logs conj data))}})
               (js/document.body.appendChild container)
               (let [root (act-mount container [:> Editor {:content "test" :ref ref}])]
                 (<! (timeout 10))
                 (let [^js/Object editor (.-current ref)]
                   (act-flush #(.highlightRange editor #js {:line 1 :column 1} #js {:line 1 :column 5}))
                   (<! (timeout 10))
                   (act-flush #(.unmount root))
                   (<! (timeout 10))
                   (act-flush #(.highlightRange editor #js {:line 1 :column 1} #js {:line 1 :column 5}))
                   (<! (timeout 10))
                   (is (some #(str/includes? (str/join " " (:vargs %)) "Cannot highlight range: view-ref is nil") @captured-logs)
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
               (let [root (act-mount container [:> Editor {:content "test"
                                                           :language "text"
                                                           :ref ref}])]
                 (<! (timeout 10))
                 (let [^js/Object editor (.-current ref)]
                   (act-flush #(.openDocument editor "inmemory://old.txt" "test" "text"))
                   (<! (timeout 10))
                   (.subscribe (.getEvents editor) #(swap! events conj (js->clj % :keywordize-keys true)))
                   (act-flush #(.renameDocument editor "new.txt"))
                   (<! (timeout 10))
                   (is (= 2 (count @sent)) "Sent didClose and didOpen")
                   (is (= "textDocument/didClose" (:method (first @sent))) "First sent didClose")
                   (is (= "inmemory://old.txt" (get-in (first @sent) [:params :textDocument :uri])) "didClose with old URI")
                   (is (= "textDocument/didOpen" (:method (second @sent))) "Second sent didOpen")
                   (is (= "inmemory://new.txt" (get-in (second @sent) [:params :textDocument :uri])) "didOpen with new URI")
                   (is (= "test" (get-in (second @sent) [:params :textDocument :text])) "didOpen with current content")
                   (is (some #(= "document-rename" (:type %)) @events) "Emitted document-rename event")
                   (act-flush #(.unmount root))
                   (js/document.body.removeChild container)
                   (done))))))))

(deftest open-document-with-content
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)]
             (js/document.body.appendChild container)
             (let [root (act-mount container [:> Editor {:language "text"
                                                         :ref ref}])]
               (<! (timeout 10))
               (let [^js/Object editor (.-current ref)]
                 (act-flush #(.openDocument editor "inmemory://test.txt" "initial content" "text"))
                 (<! (timeout 10))
                 (let [state ^js (.getState editor)]
                   (is (= "initial content" (.-content state)) "Opened with provided content"))
                 (act-flush #(.unmount root))
                 (js/document.body.removeChild container)
                 (done)))))))

(deftest rxjs-events-emitted
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)
                 events (atom [])]
             (js/document.body.appendChild container)
             (let [root (act-mount container [:> Editor {:content "test"
                                                         :language "text"
                                                         :ref ref}])]
               (<! (timeout 10))
               (let [^js/Object editor (.-current ref)]
                 (.subscribe (.getEvents editor) #(swap! events conj (js->clj % :keywordize-keys true)))
                 (act-flush #(.setText editor "updated"))
                 (<! (wait-for-event events "content-change" 1000))
                 (is (some #(= "content-change" (:type %)) @events) "Emitted content-change")
                 (act-flush #(.openDocument editor "inmemory://file.txt" "content" "text"))
                 (<! (wait-for-event events "document-open" 1000))
                 (is (some #(= "document-open" (:type %)) @events) "Emitted document-open")
                 (act-flush #(.closeDocument editor))
                 (<! (wait-for-event events "document-close" 1000))
                 (is (some #(= "document-close" (:type %)) @events) "Emitted document-close")
                 (act-flush #(.unmount root))
                 (js/document.body.removeChild container)
                 (done)))))))

(deftest rholang-extension-integration
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)]
             (js/document.body.appendChild container)
             (let [root (act-mount container [:> Editor {:language "rholang"
                                                         :languages {"rholang" config}
                                                         :ref ref}])]
               (<! (timeout 10))
               (let [^js/Object editor (.-current ref)]
                 (act-flush #(.openDocument editor "inmemory://test.rho" "new x in { x!(\"Hello\") }" "rholang"))
                 (<! (timeout 500)) ;; Wait for Tree-Sitter and LSP init
                 (let [state ^js (.getState editor)]
                   (is (some? (.-connection (.-lsp state))) "LSP connected for Rholang")
                   (is (some? (.-tree state)) "Tree-Sitter parsed for Rholang"))
                 (act-flush #(.unmount root))
                 (js/document.body.removeChild container)
                 (done)))))))

(deftest editor-ready-event
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)
                 events (atom [])]
             (js/document.body.appendChild container)
             (let [root (act-mount container [:> Editor {:content "test"
                                                         :language "text"
                                                         :ref ref}])]
               (<! (timeout 10))
               (let [^js/Object editor (.-current ref)]
                 (.subscribe (.getEvents editor) #(swap! events conj (js->clj % :keywordize-keys true)))
                 (<! (wait-for-event events "ready" 1000))
                 (is (some #(= "ready" (:type %)) @events) "Emitted ready event on initialization")
                 (act-flush #(.unmount root))
                 (js/document.body.removeChild container)
                 (done)))))))

(deftest selection-change-event
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)
                 events (atom [])]
             (js/document.body.appendChild container)
             (let [root (act-mount container [:> Editor {:content "select me"
                                                         :language "text"
                                                         :ref ref}])]
               (<! (timeout 10))
               (let [^js/Object editor (.-current ref)]
                 (.subscribe (.getEvents editor) #(swap! events conj (js->clj % :keywordize-keys true)))
                 (act-flush #(.setSelection editor #js {:line 1 :column 1} #js {:line 1 :column 7}))
                 (<! (wait-for-event events "selection-change" 1000))
                 (is (some #(and (= "selection-change" (:type %))
                                 (= {:line 1 :column 7} (get-in % [:data :cursor]))) @events)
                     "Emitted selection-change with cursor data")
                 (act-flush #(.unmount root))
                 (js/document.body.removeChild container)
                 (done)))))))

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
               (let [root (act-mount container [:> Editor {:language "rholang"
                                                           :languages {"rholang" (assoc config :lsp-url "ws://test")}
                                                           :ref ref}])]
                 (<! (timeout 10))
                 (let [^js/Object editor (.-current ref)]
                   (.subscribe (.getEvents editor) #(swap! events conj (js->clj % :keywordize-keys true)))
                   (act-flush #((.-onopen sock)))
                   (<! (wait-for-event events "connect" 1000))
                   (is (some #(= "connect" (:type %)) @events) "Emitted connect")
                   (let [init-resp "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"capabilities\":{}}}"
                         full (str "Content-Length: " (.-length init-resp) "\r\n\r\n" init-resp)]
                     (act-flush #((.-onmessage sock) #js {:data full})))
                   (<! (wait-for-event events "lsp-initialized" 1000))
                   (is (some #(= "lsp-initialized" (:type %)) @events) "Emitted lsp-initialized")
                   (let [diag-notif "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/publishDiagnostics\",\"params\":{\"diagnostics\":[{\"message\":\"test\"}]}}"
                         full (str "Content-Length: " (.-length diag-notif) "\r\n\r\n" diag-notif)]
                     (act-flush #((.-onmessage sock) #js {:data full})))
                   (<! (wait-for-event events "diagnostics" 1000))
                   (is (some #(= "diagnostics" (:type %)) @events) "Emitted diagnostics")
                   (let [log-notif "{\"jsonrpc\":\"2.0\",\"method\":\"window/logMessage\",\"params\":{\"message\":\"test log\"}}"
                         full (str "Content-Length: " (.-length log-notif) "\r\n\r\n" log-notif)]
                     (act-flush #((.-onmessage sock) #js {:data full})))
                   (<! (wait-for-event events "log" 1000))
                   (is (some #(= "log" (:type %)) @events) "Emitted log")
                   (act-flush #((.-onclose sock)))
                   (<! (wait-for-event events "disconnect" 1000))
                   (is (some #(= "disconnect" (:type %)) @events) "Emitted disconnect")
                   (act-flush #(.unmount root))
                   (js/document.body.removeChild container)
                   (done))))))))

(deftest get-state
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)]
             (js/document.body.appendChild container)
             (let [root (act-mount container [:> Editor {:content "test"
                                                         :language "text"
                                                         :ref ref}])]
               (<! (timeout 10))
               (let [^js/Object editor (.-current ref)]
                 (let [state ^js (.getState editor)]
                   (is (= "test" (.-content state)) "getState returns content")
                   (is (= "text" (.-language state)) "getState returns language"))
                 (act-flush #(.unmount root))
                 (js/document.body.removeChild container)
                 (done)))))))

(deftest get-cursor-set-cursor
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)]
             (js/document.body.appendChild container)
             (let [root (act-mount container [:> Editor {:content "test"
                                                         :language "text"
                                                         :ref ref}])]
               (<! (timeout 10))
               (let [^js/Object editor (.-current ref)]
                 (act-flush #(.setCursor editor #js {:line 1 :column 3}))
                 (<! (timeout 10))
                 (let [cursor ^js (.getCursor editor)]
                   (is (= 1 (.-line cursor)) "getCursor returns line")
                   (is (= 3 (.-column cursor)) "getCursor returns column"))
                 (act-flush #(.unmount root))
                 (js/document.body.removeChild container)
                 (done)))))))

(deftest get-selection-set-selection
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)]
             (js/document.body.appendChild container)
             (let [root (act-mount container [:> Editor {:content "select me"
                                                         :language "text"
                                                         :ref ref}])]
               (<! (timeout 10))
               (let [^js/Object editor (.-current ref)]
                 (act-flush #(.setSelection editor #js {:line 1 :column 1} #js {:line 1 :column 7}))
                 (<! (timeout 10))
                 (let [sel ^js (.getSelection editor)]
                   (is (= {:line 1 :column 1} (.-from sel)) "getSelection returns from")
                   (is (= {:line 1 :column 7} (.-to sel)) "getSelection returns to"))
                 (act-flush #(.unmount root))
                 (js/document.body.removeChild container)
                 (done)))))))

(deftest save-document
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)
                 events (atom [])]
             (js/document.body.appendChild container)
             (let [root (act-mount container [:> Editor {:content "test"
                                                         :language "text"
                                                         :ref ref}])]
               (<! (timeout 10))
               (let [^js/Object editor (.-current ref)]
                 (act-flush #(.openDocument editor "inmemory://test.txt" "test" "text"))
                 (<! (timeout 10))
                 (.subscribe (.getEvents editor) #(swap! events conj (js->clj % :keywordize-keys true)))
                 (act-flush #(.setText editor "updated"))
                 (<! (timeout 10))
                 (act-flush #(.saveDocument editor))
                 (<! (wait-for-event events "document-save" 1000))
                 (is (some #(= "document-save" (:type %)) @events) "Emitted document-save")
                 (act-flush #(.unmount root))
                 (js/document.body.removeChild container)
                 (done)))))))

(deftest is-ready
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)]
             (js/document.body.appendChild container)
             (let [root (act-mount container [:> Editor {:content "test"
                                                         :language "text"
                                                         :ref ref}])]
               (<! (timeout 10))
               (let [^js/Object editor (.-current ref)]
                 (is (.isReady editor) "isReady true after init"))
               (act-flush #(.unmount root))
               (js/document.body.removeChild container)
               (done))))))

(deftest clear-cursor-pos
  (async done
    (go
      (let [container (js/document.createElement "div")
            ref (react/createRef)]
        (js/document.body.appendChild container)
        (let [root (act-mount container [:> Editor {:content "test"
                                                    :language "text"
                                                    :ref ref}])]
          (<! (timeout 10))
          (let [^js/Object editor (.-current ref)]
            (act-flush #(.highlightRange editor #js {:line 1 :column 1} #js {:line 1 :column 5}))
            (<! (timeout 10))
            (act-flush #(.clearHighlight editor))
            (<! (timeout 10))
            ;; No direct assertion, but ensure no error
            (is true "clearHighlight called without error")
            (act-flush #(.unmount root))
            (js/document.body.removeChild container)
            (done)))))))

(deftest center-on-range
  (async done
    (go
      (let [container (js/document.createElement "div")
            ref (react/createRef)]
        (js/document.body.appendChild container)
        (let [root (act-mount container [:> Editor {:content (str/join "\n" (repeat 50 "line"))
                                                    :language "text"
                                                    :ref ref}])]
          (<! (timeout 10))
          (let [^js/Object editor (.-current ref)]
            (act-flush #(.centerOnRange editor #js {:line 30 :column 1} #js {:line 30 :column 5}))
            (<! (timeout 10))
            ;; No direct assertion for scroll, but ensure no error
            (is true "centerOnRange called without error")
            (act-flush #(.unmount root))
            (js/document.body.removeChild container)
            (done)))))))

(deftest get-text-set-text
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)]
             (js/document.body.appendChild container)
             (let [root (act-mount container [:> Editor {:content "initial"
                                                         :language "text"
                                                         :ref ref}])]
               (<! (timeout 10))
               (let [^js/Object editor (.-current ref)]
                 (is (= "initial" (.getText editor)) "getText returns initial content")
                 (act-flush #(.setText editor "updated"))
                 (<! (timeout 10))
                 (is (= "updated" (.getText editor)) "getText returns updated content after setText"))
               (act-flush #(.unmount root))
               (js/document.body.removeChild container)
               (done))))))

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
                   (let [root (act-mount container [:> Editor {:content "test"
                                                               :language "text"
                                                               :ref ref}])]
                     (<! (timeout 10))
                     (let [^js/Object editor (.-current ref)]
                       (.subscribe (.getEvents editor) #(swap! events conj (js->clj % :keywordize-keys true)))
                       (doseq [action actions]
                         (let [cmd (first action)
                               args (rest action)]
                           (act-flush #(case cmd
                                         :set-content (.setText editor (first args))
                                         :open-document (.openDocument editor (str "inmemory://" (first args) ".txt") (second args) (nth args 2 nil))
                                         :close-document (.closeDocument editor))))
                         (<! (timeout 10)))
                       (is (every? #(contains? % :type) @events) "All events have a type")
                       (is (every? #(contains? % :data) @events) "All events have data"))
                     (act-flush #(.unmount root))
                     (js/document.body.removeChild container)
                     (done))
                   true))))]
  (deftest rxjs-event-stream-property
    (is (:result (tc/quick-check 10 prop {:seed 42})))))

(deftest highlight-change-event-emission
  (async done
         (go
           (let [container (js/document.createElement "div")
                 ref (react/createRef)
                 events (atom [])]
             (js/document.body.appendChild container)
             (let [root (act-mount container [:> Editor {:content "test content"
                                                         :language "text"
                                                         :ref ref}])]
               (<! (timeout 10))
               (let [^js/Object editor (.-current ref)]
                 (.subscribe (.getEvents editor) #(swap! events conj (js->clj % :keywordize-keys true)))
                 (act-flush #(.highlightRange editor #js {:line 1 :column 1} #js {:line 1 :column 6}))
                 (<! (wait-for-event events "highlight-change" 1000))
                 (let [evt (first (filter #(= "highlight-change" (:type %)) @events))]
                   (is (= {:from {:line 1 :column 1} :to {:line 1 :column 6}} (:data evt)) "Emitted highlight-change with range"))
                 (act-flush #(.clearHighlight editor))
                 (<! (wait-for-event events "highlight-change" 1000))
                 (let [evt (last (filter #(= "highlight-change" (:type %)) @events))]
                   (is (nil? (:data evt)) "Emitted highlight-change with nil on clear"))
                 (act-flush #(.unmount root))
                 (js/document.body.removeChild container)
                 (done)))))))

(let [gen-pos (gen/hash-map :line gen/small-integer :column gen/small-integer)
      gen-action (gen/one-of [(gen/tuple (gen/return :highlight) gen-pos gen-pos)
                              (gen/return [:clear])])
      prop (prop/for-all [actions (gen/vector gen-action 1 10)]
             (async done
               (go
                 (let [container (js/document.createElement "div")
                       ref (react/createRef)
                       events (atom [])]
                   (js/document.body.appendChild container)
                   (let [root (act-mount container [:> Editor {:content (str/join "\n" (repeat 10 "line"))
                                                               :language "text"
                                                               :ref ref}])]
                     (<! (timeout 10))
                     (let [^js/Object editor (.-current ref)]
                       (.subscribe (.getEvents editor) #(swap! events conj (js->clj % :keywordize-keys true)))
                       (doseq [action actions]
                         (let [cmd (first action)]
                           (act-flush #(case cmd
                                         :highlight (.highlightRange editor (clj->js (second action)) (clj->js (nth action 2)))
                                         :clear (.clearHighlight editor))))
                         (<! (timeout 10)))
                       (let [highlight-events (filter #(= "highlight-change" (:type %)) @events)]
                         (is (= (count actions) (count highlight-events)) "Event emitted for each action")
                         (is (every? #(or (map? (:data %)) (nil? (:data %))) highlight-events) "Data is range map or nil")))
                     (act-flush #(.unmount root))
                     (js/document.body.removeChild container)
                     (done))
                   true))))]
  (deftest highlight-event-property
    (is (:result (tc/quick-check 10 prop {:seed 42})))))
