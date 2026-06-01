(ns test.lib.lsp.client-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures async]]
   [clojure.core.async :refer [go <! timeout]]
   [clojure.string :as str]
   [clojure.spec.alpha :as s]
   [clojure.test.check :as tc]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [reagent.core :as r]
   ["rxjs" :as rxjs]
   [lib.db :as db :refer [flatten-symbols create-documents! replace-symbols!]]
   [lib.lsp.client :as lsp]
   [lib.workspace :as ws]
   [lib.utils :as lib-utils]
   [test.lib.mock-lsp :refer [parse-message with-mock-lsp]]
   [test.lib.utils :refer [wait-for]]
   [lib.state :refer [set-resource!]]))

(use-fixtures :each
  {:before #(ws/reset-workspace! @ws/default-workspace)})

(deftest connect-mock-websocket
  (async done
         (go
           (let [state (r/atom {})
                 events (rxjs/Subject.)
                 mock-res (<! (with-mock-lsp
                                (fn [mock]
                                  (go
                                    (try
                                      (let [connect-ch (lsp/connect (ws/default-conn) "test" {:url "ws://test"} state events nil)]
                                        (let [wait-res (<! (wait-for #(some? (.-onopen (:sock mock))) 1000))]
                                          (if (= :error (first wait-res))
                                            (throw (second wait-res))
                                            (is (second wait-res) "onopen handler set")))
                                        ((:trigger-open mock))
                                        (let [wait-res (<! (wait-for #(pos? (count @(:sent mock))) 1000))]
                                          (if (= :error (first wait-res))
                                            (throw (second wait-res))
                                            (is (second wait-res) "Message sent after open")))
                                        (let [init-msg (first @(:sent mock))
                                              [_ body] (parse-message init-msg)
                                              id (:id body)
                                              response-js #js {:jsonrpc "2.0"
                                                               :id id
                                                               :result #js {:capabilities #js {}}}
                                              response (js/JSON.stringify response-js)
                                              full (str "Content-Length: " (.-length response) "\r\n\r\n" response)]
                                          ((:trigger-message mock) full))
                                        (<! (timeout 10))
                                        (let [status-val (<! connect-ch)]
                                          (if (= :error (first status-val))
                                            (throw (js/Error. "connect failed" #js {:cause (second status-val)}))
                                            (do
                                              (is (true? (get-in @state [:lsp "test" :connected?])) "connected?")
                                              (is (true? (get-in @state [:lsp "test" :initialized?])) "initialized?")
                                              (is (some? (get-in @state [:lsp "test" :ws])) "ws set"))))
                                        [:ok nil])
                                      (catch :default e
                                        [:error (js/Error. "connect-mock-websocket body failed" #js {:cause e})]))))))]
             (if (= :error (first mock-res))
               (let [err (second mock-res)]
                 (lib-utils/log-error-with-cause err)
                 (is false (str "Mock body failed: " (.-message err))))
               (is true "Connect succeeded")))
           (done))))

(deftest unexpected-close-invokes-reconnect
  (async done
         (go
           (let [state (r/atom {})
                 events (rxjs/Subject.)
                 reconnect-count (atom 0)
                 mock-res (<! (with-mock-lsp
                                (fn [mock]
                                  (go
                                    (try
                                      (let [connect-ch (lsp/connect (ws/default-conn)
                                                                    "test"
                                                                    {:url "ws://test"}
                                                                    state
                                                                    events
                                                                    #(swap! reconnect-count inc))]
                                        (let [wait-res (<! (wait-for #(some? (.-onopen (:sock mock))) 1000))]
                                          (if (= :error (first wait-res))
                                            (throw (second wait-res))
                                            (is (second wait-res) "onopen handler set")))
                                        ((:trigger-open mock))
                                        (let [[status value] (<! connect-ch)]
                                          (when (= :error status)
                                            (throw (js/Error. "connect failed" #js {:cause value}))))
                                        (is (true? (get-in @state [:lsp "test" :initialized?]))
                                            "connection initialized before close")
                                        ((:trigger-close mock))
                                        (is (= 1 @reconnect-count)
                                            "unexpected established close invokes reconnect callback")
                                        [:ok nil])
                                      (catch :default e
                                        [:error (js/Error. "unexpected-close-invokes-reconnect failed" #js {:cause e})]))))))]
             (when (= :error (first mock-res))
               (let [err (second mock-res)]
                 (lib-utils/log-error-with-cause err)
                 (is false (str "Mock body failed: " (.-message err)))))
             (done)))))

(deftest graceful-shutdown-close-does-not-reconnect
  (async done
         (go
           (let [state (r/atom {})
                 events (rxjs/Subject.)
                 reconnect-count (atom 0)
                 mock-res (<! (with-mock-lsp
                                (fn [mock]
                                  (go
                                    (try
                                      (let [connect-ch (lsp/connect (ws/default-conn)
                                                                    "test"
                                                                    {:url "ws://test"}
                                                                    state
                                                                    events
                                                                    #(swap! reconnect-count inc))]
                                        (let [wait-res (<! (wait-for #(some? (.-onopen (:sock mock))) 1000))]
                                          (if (= :error (first wait-res))
                                            (throw (second wait-res))
                                            (is (second wait-res) "onopen handler set")))
                                        ((:trigger-open mock))
                                        (let [[status value] (<! connect-ch)]
                                          (when (= :error status)
                                            (throw (js/Error. "connect failed" #js {:cause value}))))
                                        (swap! state assoc-in [:lsp "test" :shutting-down?] true)
                                        ((:trigger-close mock))
                                        (is (zero? @reconnect-count)
                                            "graceful shutdown close suppresses reconnect callback")
                                        [:ok nil])
                                      (catch :default e
                                        [:error (js/Error. "graceful-shutdown-close-does-not-reconnect failed" #js {:cause e})]))))))]
             (when (= :error (first mock-res))
               (let [err (second mock-res)]
                 (lib-utils/log-error-with-cause err)
                 (is false (str "Mock body failed: " (.-message err)))))
             (done)))))

(deftest flatten-symbols-basic
  (let [syms [{:name "root"
               :kind 12
               :range {:start {:line 0
                               :character 0}
                       :end {:line 0
                             :character 10}}
               :selectionRange {:start {:line 0
                                        :character 0}
                                :end {:line 0
                                      :character 1}}
               :children [{:name "child"
                           :kind 13
                           :range {:start {:line 1
                                           :character 2}
                                   :end {:line 1
                                         :character 3}}
                           :selectionRange {:start {:line 1
                                                    :character 2}
                                            :end {:line 1
                                                  :character 3}}}]}]
        uri "test-uri"
        flat-symbols (flatten-symbols syms nil uri)]
    (create-documents! (ws/default-conn) [{:uri uri
                         :text ""
                         :language "test"
                         :version 1
                         :dirty false
                         :opened true}])
    (replace-symbols! (ws/default-conn) uri flat-symbols)
    (is (= 2 (count flat-symbols)))
    (let [syms (db/symbols (ws/default-conn))
          root (first (filter #(= "root" (:name %)) syms))
          child (first (filter #(= "child" (:name %)) syms))]
      (is (= uri (:uri root)) "URI added to root")
      (is (= uri (:uri child)) "URI added to child")
      (is (not= (:parent root) (:parent child)) "Child parent differs from root"))))

(deftest flatten-symbols-no-children
  (let [syms [{:name "root"
               :kind 12
               :range {:start {:line 0
                               :character 0}
                       :end {:line 0
                             :character 10}}
               :selectionRange {:start {:line 0
                                        :character 0}
                                :end {:line 0
                                      :character 1}}}]
        uri "test-uri"
        flat (flatten-symbols syms nil uri)]
    (is (= 1 (count flat)))
    (is (= "root" (:symbol/name (first flat))))
    (is (nil? (:symbol/parent (first flat))) "No parent for top-level")))

(deftest send-mock-websocket
  (let [ws-mock (js/Object.)
        state (r/atom {:lsp {"test" {:ws ws-mock
                                     :pending {}
                                     :connected? true
                                     :reachable? true
                                     :warned-unreachable? false}}})
        msg {:method "initialized" :params {}}
        sent (atom nil)]
    (set! (.-send ws-mock) (fn [data] (reset! sent data)))
    (set-resource! :lsp "test" ws-mock)
    (lsp/send "test" msg state)
    (is (some? @sent) "Message sent over WebSocket")
    (is (str/includes? @sent "Content-Length:") "Includes Content-Length header")
    (is (str/includes? @sent "\"method\":\"initialized\"") "JSON contains method")))

(deftest flatten-nested-with-multiple-children
  (let [syms [{:name "root"
               :kind 1
               :range {:start {:line 0
                               :character 0}
                       :end {:line 5
                             :character 0}}
               :selectionRange {:start {:line 0
                                        :character 0}
                                :end {:line 0
                                      :character 4}}
               :children [{:name "child1"
                           :kind 2
                           :range {:start {:line 1
                                           :character 2}
                                   :end {:line 1
                                         :character 7}}
                           :selectionRange {:start {:line 1
                                                    :character 2}
                                            :end {:line 1
                                                  :character 7}}}
                          {:name "child2"
                           :kind 2
                           :range {:start {:line 2
                                           :character 2}
                                   :end {:line 4
                                         :character 2}}
                           :selectionRange {:start {:line 2
                                                    :character 2}
                                            :end {:line 2
                                                  :character 7}}
                           :children [{:name "grandchild"
                                       :kind 3
                                       :range {:start {:line 3
                                                       :character 4}
                                               :end {:line 3
                                                     :character 14}}
                                       :selectionRange {:start {:line 3
                                                                :character 4}
                                                        :end {:line 3
                                                              :character 14}}}]}]}]
        uri "test-uri"
        flat (flatten-symbols syms nil uri)]
    (is (= 4 (count flat)))
    (is (= "root" (:symbol/name (first flat))))
    (is (= "child1" (:symbol/name (second flat))))
    (is (= "child2" (:symbol/name (nth flat 2))))
    (is (= "grandchild" (:symbol/name (last flat))))
    (let [root-id (:db/id (first flat))
          child2-id (:db/id (nth flat 2))]
      (is (= root-id (:symbol/parent (second flat))) "child1 parent is root")
      (is (= root-id (:symbol/parent (nth flat 2))) "child2 parent is root")
      (is (= child2-id (:symbol/parent (last flat))) "grandchild parent is child2"))))

(deftest flatten-empty
  (let [syms []
        flat (flatten-symbols syms nil "test-uri")]
    (is (empty? flat) "Empty input returns empty")))

(deftest flatten-single-no-children
  (let [syms [{:name "single"
               :kind 1
               :range {:start {:line 0
                               :character 0}
                       :end {:line 0
                             :character 6}}
               :selectionRange {:start {:line 0
                                        :character 0}
                                :end {:line 0
                                      :character 6}}}]
        flat (flatten-symbols syms nil "test-uri")]
    (is (= 1 (count flat)))
    (is (= "single" (:symbol/name (first flat))))
    (is (nil? (:symbol/parent (first flat))) "No parent for top-level")))

(deftest message-conforms-to-spec
  (testing "Valid request"
    (let [req {:jsonrpc "2.0" :id 1 :method "initialize" :params {}}]
      (is (s/valid? ::lsp/request req))))
  (testing "Valid notification"
    (let [notif {:jsonrpc "2.0" :method "initialized" :params {}}]
      (is (s/valid? ::lsp/notification notif))))
  (testing "Valid response"
    (let [resp {:jsonrpc "2.0" :id 1 :result {}}]
      (is (s/valid? ::lsp/response resp)))))

(deftest handle-message-with-header
  (let [state (r/atom {:lsp {"test-lang" {:pending {1 :initialize}}}})
        events (rxjs/Subject.)
        response-js #js {:jsonrpc "2.0"
                         :id 1
                         :result #js {:capabilities #js {}}}
        response (js/JSON.stringify response-js)
        full (str "Content-Length: " (.-length response) "\r\n\r\n" response)]
    (lsp/handle-message (ws/default-conn) "test-lang" full state events)
    (is (empty? (get-in @state [:lsp "test-lang" :pending])) "Pending cleared")
    (is (true? (get-in @state [:lsp "test-lang" :initialized?])) "Initialized set")))

(deftest handle-message-diagnostics
  (let [state (r/atom {:lsp {"test-lang" {:pending {}}}})
        events (rxjs/Subject.)
        diag-params-js #js {:uri "test-uri"
                            :diagnostics #js [#js {:range #js {:start #js {:line 0
                                                                           :character 0}
                                                               :end #js {:line 0
                                                                         :character 5}}
                                                   :severity 1
                                                   :message "test"}]}
        diag-js #js {:jsonrpc "2.0"
                     :method "textDocument/publishDiagnostics"
                     :params diag-params-js}
        diag-msg (js/JSON.stringify diag-js)
        full (str "Content-Length: " (.-length diag-msg) "\r\n\r\n" diag-msg)]
    (db/create-documents! (ws/default-conn) [{:uri "test-uri"
                            :text ""
                            :language "test-lang"
                            :version 1
                            :dirty false
                            :opened true}])
    (db/update-active-uri! (ws/default-conn) "test-uri")
    (lsp/handle-message (ws/default-conn) "test-lang" full state events)
    (let [diags (db/diagnostics (ws/default-conn))]
      (is (= 1 (count diags)) "Transacted diagnostic")
      (is (= "test" (:message (first diags))) "Message matches")
      (is (= "test-uri" (:uri (first diags))) "URI added"))))

(deftest handle-message-log
  (let [state (r/atom {:lsp {"test-lang" {:pending {}}}})
        events (rxjs/Subject.)
        log-params-js #js {:message "test log"}
        log-js #js {:jsonrpc "2.0" :method "window/logMessage" :params log-params-js}
        log-msg (js/JSON.stringify log-js)
        full (str "Content-Length: " (.-length log-msg) "\r\n\r\n" log-msg)]
    (lsp/handle-message (ws/default-conn) "test-lang" full state events)
    (let [logs (db/logs (ws/default-conn))]
      (is (= 1 (count logs)) "Transacted log")
      (is (= "test log" (:message (first logs))) "Log message matches"))))

(deftest handle-message-symbols
  (let [state (r/atom {:lsp {"test-lang" {:pending {1 {:type :document-symbol
                                                       :uri "test-uri"}}}}})
        events (rxjs/Subject.)
        symbols [{:name "root"
                  :kind 1
                  :range {:start {:line 0
                                  :character 0}
                          :end {:line 0
                                :character 10}}
                  :selectionRange {:start {:line 0
                                           :character 0}
                                   :end {:line 0
                                         :character 1}}
                  :children [{:name "child"
                              :kind 2
                              :range {:start {:line 1
                                              :character 2}
                                      :end {:line 1
                                            :character 3}}
                              :selectionRange {:start {:line 1
                                                       :character 2}
                                               :end {:line 1
                                                     :character 3}}}]}]
        sym-js #js {:jsonrpc "2.0"
                    :id 1
                    :result (clj->js symbols)}
        sym-resp (js/JSON.stringify sym-js)
        full (str "Content-Length: " (.-length sym-resp) "\r\n\r\n" sym-resp)
        doc-tx [{:uri "test-uri"
                 :text ""
                 :language "test-lang"
                 :version 1
                 :dirty false
                 :opened true
                 :type :document}]
        _ (create-documents! (ws/default-conn) doc-tx)]
    (lsp/handle-message (ws/default-conn) "test-lang" full state events)
    (let [syms (db/symbols (ws/default-conn))]
      (is (= 2 (count syms)) "Transacted flattened symbols")
      (let [root (first (filter #(= "root" (:name %)) syms))
            child (first (filter #(= "child" (:name %)) syms))]
        (is (= "test-uri" (:uri root)) "URI added to root")
        (is (= "test-uri" (:uri child)) "URI added to child")
        (is (not= (:parent root) (:parent child)) "Child parent differs from root")))))

(deftest send-initialize-conforms
  (let [state (r/atom {:lsp {"test" {:pending {} :next-id 1}}})
        sent (atom [])]
    (with-redefs [lsp/send-raw (fn [_lang full _state] (swap! sent conj full))]
      (lsp/request-initialize "test" state))
    (is (= 1 (count @sent)))
    (let [msg (js/JSON.parse (subs (first @sent) (str/index-of (first @sent) "{")))]
      (is (s/valid? ::lsp/request (js->clj msg :keywordize-keys true)) "Send-initialize conforms to request spec"))))

(deftest handle-initialize-response-sets-state
  (let [state (r/atom {:lsp {"test-lang" {:pending {1 :initialize}}}})
        events (rxjs/Subject.)
        response-js #js {:jsonrpc "2.0"
                         :id 1
                         :result #js {:capabilities #js {}}}
        response (js/JSON.stringify response-js)
        full (str "Content-Length: " (.-length response) "\r\n\r\n" response)]
    (with-redefs [lsp/send (fn [_ _ _])] ; Mock send-initialized
      (lsp/handle-message (ws/default-conn) "test-lang" full state events))
    (is (true? (get-in @state [:lsp "test-lang" :initialized?])) "Initialized flag set")
    (is (empty? (get-in @state [:lsp "test-lang" :pending])) "Pending cleared")))

(deftest handle-publish-diagnostics-transacts
  (let [state (r/atom {})
        events (rxjs/Subject.)
        params {:uri "test-uri"
                :diagnostics [{:message "err"
                               :severity 1
                               :range {:start {:line 0
                                               :character 0}
                                       :end {:line 0
                                             :character 5}}}]}
        doc-tx [{:uri "test-uri"
                 :text ""
                 :language "test"
                 :version 1
                 :dirty false
                 :opened true}]
        _ (create-documents! (ws/default-conn) doc-tx)]
    (db/update-active-uri! (ws/default-conn) "test-uri")
    (lsp/handle-publish-diagnostics (ws/default-conn) "test" params state events)
    (let [diags (db/diagnostics (ws/default-conn))]
      (is (= 1 (count diags)) "Diagnostic transacted")
      (is (= "err" (:message (first diags))) "Message matches")
      (is (= "test-uri" (:uri (first diags))) "URI added"))))

(let [gen-type (gen/elements [:request :notification])
      gen-request-method (gen/elements ["initialize"])
      gen-notification-method (gen/elements ["textDocument/didOpen"])
      gen-id (gen/one-of [gen/nat gen/string])
      gen-params (gen/map gen/keyword gen/any)
      prop (prop/for-all [type gen-type]
                         (gen/let [method (case type
                                            :request gen-request-method
                                            :notification gen-notification-method)
                                   id (case type
                                        :request gen-id
                                        :notification (gen/return nil))
                                   params gen-params]
                           (let [msg (cond-> {:jsonrpc "2.0" :method method :params params}
                                       id (assoc :id id))
                                 spec (case type :request ::lsp/request :notification ::lsp/notification)]
                             (s/valid? spec msg))))]
  (deftest message-conformance-property
    (let [result (tc/quick-check 100 prop {:seed 42})]
      (is (:result result) "All generated messages conform to specs"))))

(deftest shutdown-sends-request
  (let [state (r/atom {:lsp {"test" {:ws (js/Object.)
                                     :pending {}
                                     :connected? true
                                     :reachable? true}}})
        sent (atom [])]
    (with-redefs [lsp/send-raw (fn [_lang full _state] (swap! sent conj full))]
      (lsp/request-shutdown "test" state))
    (is (= 1 (count @sent)))
    (let [msg (js/JSON.parse (subs (first @sent) (str/index-of (first @sent) "{")))]
      (is (s/valid? ::lsp/request (js->clj msg :keywordize-keys true)) "Shutdown conforms to request spec"))))

(deftest shutdown-clears-unrelated-pending-and-tracks-shutdown
  (let [state (r/atom {:lsp {"test" {:ws (js/Object.)
                                     :state :initialized
                                     :pending {1 :initialize
                                               2 {:type :document-symbol
                                                  :uri "file:///test.rho"}}
                                     :next-id 3
                                     :connected? true
                                     :initialized? true
                                     :reachable? true}}})
        sent (atom [])]
    (with-redefs [lsp/send-raw (fn [_lang full _state] (swap! sent conj full))]
      (lsp/request-shutdown "test" state))
    (is (= :disconnecting (get-in @state [:lsp "test" :state])))
    (is (= {3 :shutdown} (get-in @state [:lsp "test" :pending]))
        "Only the shutdown request remains pending")
    (is (= 4 (get-in @state [:lsp "test" :next-id]))
        "Shutdown consumes exactly one fresh request ID")
    (is (= 1 (count @sent)))))

(deftest exit-sends-notification
  (let [state (r/atom {:lsp {"test" {:ws (js/Object.)
                                     :pending {}
                                     :connected? true
                                     :reachable? true}}})
        sent (atom [])]
    (with-redefs [lsp/send-raw (fn [_lang full _state] (swap! sent conj full))]
      (lsp/notify-exit "test" state))
    (is (= 1 (count @sent)))
    (let [msg (js/JSON.parse (subs (first @sent) (str/index-of (first @sent) "{")))]
      (is (s/valid? ::lsp/notification (js->clj msg :keywordize-keys true)) "Exit conforms to notification spec"))))

(deftest handle-shutdown-response
  (async done
         (go
           (let [res (<! (go
                           (try
                             (let [state (r/atom {:lsp {"test-lang" {:pending {1 :shutdown}}}})
                                   events (rxjs/Subject.)
                                   response-js #js {:jsonrpc "2.0"
                                                    :id 1
                                                    :result #js {}}
                                   response (js/JSON.stringify response-js)
                                   full (str "Content-Length: " (.-length response) "\r\n\r\n" response)
                                   sent (atom [])
                                   closed (atom false)
                                   ws (js/Object.)]
                               (swap! state assoc-in [:lsp "test-lang" :ws] ws)
                               (set-resource! :lsp "test-lang" ws)
                               (set! (.-close ws) (fn [] (reset! closed true)))
                               (with-redefs [lsp/send (fn [_lang msg _state-atom]
                                                        (swap! sent conj msg))]
                                 (lsp/handle-message (ws/default-conn) "test-lang" full state events))
                               (<! (timeout 100))
                               (is (= 1 (count @sent)) "Sent exit after shutdown")
                               (is (= "exit" (:method (first @sent))) "Exit notification sent")
                               (is @closed "WebSocket closed after exit")
                               [:ok nil])
                             (catch :default e
                               [:error (js/Error. "handle-shutdown-response failed" #js {:cause e})]))))]
             (when (= :error (first res))
               (let [err (second res)
                     err-msg (str "Test failed with error: " (pr-str err))]
                 (lib-utils/log-error-with-cause err)
                 (is false err-msg)))
             (done)))))

;; =============================================================================
;; Edge Case Tests
;; =============================================================================

(deftest message-serialization-unicode-preserved
  (testing "Unicode characters in messages are preserved through serialization"
    (let [ws-mock (js/Object.)
          state (r/atom {:lsp {"test" {:ws ws-mock
                                       :pending {}
                                       :connected? true
                                       :reachable? true
                                       :warned-unreachable? false}}})
          unicode-text "// 你好世界 🌍 λx.x Привет"
          msg {:method "textDocument/didChange"
               :params {:textDocument {:uri "file:///test.rho"}
                        :contentChanges [{:text unicode-text}]}}
          sent (atom nil)]
      (set! (.-send ws-mock) (fn [data] (reset! sent data)))
      (set-resource! :lsp "test" ws-mock)
      (lsp/send "test" msg state)
      (is (some? @sent) "Message sent")
      ;; Parse and verify unicode is preserved
      (is (str/includes? @sent "你好世界") "Chinese characters preserved")
      (is (str/includes? @sent "🌍") "Emoji preserved")
      (is (str/includes? @sent "λx.x") "Lambda preserved")
      (is (str/includes? @sent "Привет") "Cyrillic preserved"))))

(deftest content-length-header-byte-vs-char-length
  (testing "Content-Length header value reflects implementation behavior"
    ;; Note: Per LSP spec, Content-Length SHOULD be byte count, but the current
    ;; implementation uses JavaScript string .length (character count in UTF-16).
    ;; This test documents actual behavior, not ideal behavior.
    (let [ws-mock (js/Object.)
          state (r/atom {:lsp {"test" {:ws ws-mock
                                       :pending {}
                                       :connected? true
                                       :reachable? true
                                       :warned-unreachable? false}}})
          ;; This string has multi-byte unicode characters
          ;; "你好" = 6 bytes in UTF-8 (3 bytes each), but 2 chars in JS
          msg {:method "test" :params {:text "你好"}}
          sent (atom nil)]
      (set! (.-send ws-mock) (fn [data] (reset! sent data)))
      (set-resource! :lsp "test" ws-mock)
      (lsp/send "test" msg state)
      ;; Extract Content-Length from sent message
      (let [header-match (re-find #"Content-Length: (\d+)" @sent)
            declared-length (when header-match (js/parseInt (second header-match) 10))
            ;; Find the actual body after \r\n\r\n
            body-start (+ 4 (str/index-of @sent "\r\n\r\n"))
            body (subs @sent body-start)
            ;; Implementation uses JS string length (character count), not byte count
            char-count (.-length body)]
        (is (= declared-length char-count)
            "Content-Length matches JS string length (characters, not bytes)")))))

(deftest request-response-matching-out-of-order
  (testing "Responses are matched to requests by ID regardless of order"
    (let [state (r/atom {:lsp {"test-lang" {:pending {1 :initialize
                                                       2 :shutdown
                                                       3 {:type :document-symbol :uri "test-uri"}}}}})
          events (rxjs/Subject.)
          ;; Respond to request 3 first (out of order)
          response3-js #js {:jsonrpc "2.0"
                            :id 3
                            :result #js []}
          response3 (js/JSON.stringify response3-js)
          full3 (str "Content-Length: " (.-length response3) "\r\n\r\n" response3)]
      ;; Create document for symbol response
      (db/create-documents! (ws/default-conn) [{:uri "test-uri"
                              :text ""
                              :language "test-lang"
                              :version 1
                              :dirty false
                              :opened true}])
      ;; Handle response 3 first
      (lsp/handle-message (ws/default-conn) "test-lang" full3 state events)
      ;; Request 3 should be removed, but 1 and 2 should remain
      (is (not (contains? (get-in @state [:lsp "test-lang" :pending]) 3))
          "Request 3 should be cleared")
      (is (contains? (get-in @state [:lsp "test-lang" :pending]) 1)
          "Request 1 should still be pending")
      (is (contains? (get-in @state [:lsp "test-lang" :pending]) 2)
          "Request 2 should still be pending"))))

(deftest handle-message-ignores-unknown-notifications
  (testing "Unknown notifications are handled gracefully"
    (let [state (r/atom {:lsp {"test-lang" {:pending {}}}})
          events (rxjs/Subject.)
          unknown-js #js {:jsonrpc "2.0"
                          :method "someUnknown/notification"
                          :params #js {:foo "bar"}}
          unknown-msg (js/JSON.stringify unknown-js)
          full (str "Content-Length: " (.-length unknown-msg) "\r\n\r\n" unknown-msg)]
      ;; Should not throw
      (lsp/handle-message (ws/default-conn) "test-lang" full state events)
      ;; State should be unchanged
      (is (empty? (get-in @state [:lsp "test-lang" :pending]))
          "Pending should remain empty"))))

(deftest handle-error-response
  (testing "Error responses are handled correctly"
    (let [state (r/atom {:lsp {"test-lang" {:pending {1 {:type :document-symbol :uri "test-uri"}}}}})
          events (rxjs/Subject.)
          error-js #js {:jsonrpc "2.0"
                        :id 1
                        :error #js {:code -32600
                                    :message "Invalid request"}}
          error-msg (js/JSON.stringify error-js)
          full (str "Content-Length: " (.-length error-msg) "\r\n\r\n" error-msg)]
      ;; Should not throw
      (lsp/handle-message (ws/default-conn) "test-lang" full state events)
      ;; Pending should be cleared even for error
      (is (empty? (get-in @state [:lsp "test-lang" :pending]))
          "Pending should be cleared on error response"))))

(deftest handle-null-result-response
  (testing "Response with null result is handled"
    (let [state (r/atom {:lsp {"test-lang" {:pending {1 {:type :document-symbol :uri "test-uri"}}}}})
          events (rxjs/Subject.)
          null-result-js #js {:jsonrpc "2.0"
                              :id 1
                              :result nil}
          null-result-msg (js/JSON.stringify null-result-js)
          full (str "Content-Length: " (.-length null-result-msg) "\r\n\r\n" null-result-msg)]
      (db/create-documents! (ws/default-conn) [{:uri "test-uri"
                              :text ""
                              :language "test-lang"
                              :version 1
                              :dirty false
                              :opened true}])
      ;; Should not throw
      (lsp/handle-message (ws/default-conn) "test-lang" full state events)
      ;; Pending should be cleared
      (is (empty? (get-in @state [:lsp "test-lang" :pending]))
          "Pending should be cleared on null result"))))

(deftest handle-multiple-diagnostics-for-same-uri
  (testing "Multiple diagnostics for same URI are all stored"
    (let [state (r/atom {:lsp {"test-lang" {:pending {}}}})
          events (rxjs/Subject.)
          diag-params-js #js {:uri "test-uri"
                              :diagnostics #js [#js {:range #js {:start #js {:line 0 :character 0}
                                                                  :end #js {:line 0 :character 5}}
                                                     :severity 1
                                                     :message "Error 1"}
                                                #js {:range #js {:start #js {:line 1 :character 0}
                                                                  :end #js {:line 1 :character 5}}
                                                     :severity 2
                                                     :message "Warning 2"}
                                                #js {:range #js {:start #js {:line 2 :character 0}
                                                                  :end #js {:line 2 :character 5}}
                                                     :severity 3
                                                     :message "Info 3"}]}
          diag-js #js {:jsonrpc "2.0"
                       :method "textDocument/publishDiagnostics"
                       :params diag-params-js}
          diag-msg (js/JSON.stringify diag-js)
          full (str "Content-Length: " (.-length diag-msg) "\r\n\r\n" diag-msg)]
      (db/create-documents! (ws/default-conn) [{:uri "test-uri"
                              :text ""
                              :language "test-lang"
                              :version 1
                              :dirty false
                              :opened true}])
      (db/update-active-uri! (ws/default-conn) "test-uri")
      (lsp/handle-message (ws/default-conn) "test-lang" full state events)
      (let [diags (db/diagnostics-by-uri (ws/default-conn) "test-uri")]
        (is (= 3 (count diags)) "All three diagnostics stored")
        (is (= #{1 2 3} (set (map :severity diags))) "All severities present")))))
