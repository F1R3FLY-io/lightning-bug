(ns test.lib.lsp.client-test
  (:require
   [clojure.core.async :as async :refer [chan]]
   [clojure.test :refer [deftest is testing]]
   [clojure.string :as str]
   [datascript.core :as d]
   [posh.reagent :as p] ;; For verifying Posh atom attachment.
   [reagent.core :as r]
   ["rxjs" :as rxjs]
   [lib.db :refer [flatten-symbols]]
   [lib.lsp.client :as lsp]))

(deftest connect-mock-websocket
  (let [state (r/atom {:lsp {:pending {}} :conn (d/create-conn)})
        events (rxjs/Subject.)
        ch (chan)
        sock (js/Object.)]
    (set! (.-close sock) (fn [] ((.-onclose sock))))
    (set! (.-send sock) (fn [_data]))
    (with-redefs [js/WebSocket (fn [_] sock)]
      (lsp/connect "test" {:url "ws://test"} state events ch)
      (is (some? (get-in @state [:lsp "test" :ws]))))))

(deftest flatten-symbols-basic
  (let [syms [{:name "a"
               :kind 12 ;; Function
               :range {:start {:line 0 :character 0}
                       :end {:line 0 :character 10}}
               :selectionRange {:start {:line 0 :character 0}
                                :end {:line 0 :character 1}}
               :children [{:name "b"
                           :kind 13 ;; Method
                           :range {:start {:line 1 :character 2}
                                   :end {:line 1 :character 3}}
                           :selectionRange {:start {:line 1 :character 2}
                                            :end {:line 1 :character 3}}}]}]
        flat (flatten-symbols syms nil "test-uri")
        conn (d/create-conn {:symbol/parent {:db/valueType :db.type/ref}
                             :document/uri {:db/unique :db.unique/identity}})]
    (is (= 2 (count flat)))
    (d/transact! conn flat)
    (is (= 2 (count (d/q '[:find ?e :where [?e]] @conn))) "Transacts without nil ref error")
    (is (= "test-uri" (:document/uri (d/pull @conn '[*] [:symbol/name "a"]))) "URI added to symbols")))

(deftest send-mock-websocket
  (let [state (r/atom {:lsp {"test" {:ws (js/Object.) :pending {}}} :conn (d/create-conn)})
        msg {:method "test"}
        sent (atom nil)]
    (set! (.-send (get-in @state [:lsp "test" :ws])) (fn [data] (reset! sent data)))
    (lsp/send "test" msg state)
    (is (some? @sent) "Message sent over WebSocket")
    (is (str/includes? @sent "Content-Length:") "Includes Content-Length header")
    (is (str/includes? @sent "\"method\":\"test\"") "JSON contains method")))

(deftest handle-message-with-header
  (let [state (r/atom {:lsp {"test-lang" {:pending {1 :initialize}}} :conn (d/create-conn)})
        events (rxjs/Subject.)
        response "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"capabilities\":{}}}"
        full (str "Content-Length: " (.-length response) "\r\n\r\n" response)]
    (lsp/handle-message "test-lang" full state events)
    (is (empty? (get-in @state [:lsp "test-lang" :pending])) "Pending cleared")
    (is (true? (get-in @state [:lsp "test-lang" :initialized?])) "Initialized set")))

(deftest posh-atom-attachment
  (testing "Posh atom is attached after posh!"
    (let [conn (d/create-conn)]
      (is (nil? (:posh-atom @conn)) "No Posh atom initially")
      (p/posh! conn)
      (is (some? (:posh-atom @conn)) "Posh atom attached to DB map")
      ;; Simulate a transaction to verify listener re-attaches Posh atom to new DB.
      (d/transact! conn [[:db/add -1 :test "value"]])
      (is (some? (:posh-atom @conn)) "Posh atom preserved after transaction"))))

(deftest handle-message-diagnostics
  (let [state (r/atom {:lsp {"test-lang" {:pending {}}} :conn (d/create-conn)})
        events (rxjs/Subject.)
        diag-msg "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/publishDiagnostics\",\"params\":{\"uri\":\"test-uri\",\"diagnostics\":[{\"range\":{\"start\":{\"line\":0,\"character\":0},\"end\":{\"line\":0,\"character\":5}},\"severity\":1,\"message\":\"test\"}]}}"
        full (str "Content-Length: " (.-length diag-msg) "\r\n\r\n" diag-msg)]
    (lsp/handle-message "test-lang" full state events)
    (let [diags (d/q '[:find (pull ?e [*]) :where [?e :type :diagnostic]] @(:conn @state))]
      (is (= 1 (count diags)) "Transacted diagnostic")
      (is (= "test" (:diagnostic/message (first (first diags)))) "Message matches")
      (is (= "test-uri" (:document/uri (first (first diags)))) "URI matches"))))

(deftest handle-message-log
  (let [state (r/atom {:lsp {"test-lang" {:pending {}}} :conn (d/create-conn)})
        events (rxjs/Subject.)
        log-msg "{\"jsonrpc\":\"2.0\",\"method\":\"window/logMessage\",\"params\":{\"message\":\"test log\"}}"
        full (str "Content-Length: " (.-length log-msg) "\r\n\r\n" log-msg)]
    (lsp/handle-message "test-lang" full state events)
    (is (= 1 (count (:logs @state))) "Appended log message")
    (is (= "test log" (:message (first (:logs @state)))) "Log message matches")))

(deftest handle-message-symbols
  (let [state (r/atom {:lsp {"test-lang" {:pending {1 {:type :document-symbol :uri "test-uri"}}}} :conn (d/create-conn)})
        events (rxjs/Subject.)
        symbols [{:name "root" :kind 1 :range {:start {:line 0 :character 0} :end {:line 2 :character 0}}
                  :selectionRange {:start {:line 0 :character 0} :end {:line 0 :character 4}}
                  :children [{:name "child" :kind 2 :range {:start {:line 1 :character 2} :end {:line 1 :character 7}}
                              :selectionRange {:start {:line 1 :character 2} :end {:line 1 :character 7}}}]}]
        sym-resp (js/JSON.stringify #js {:jsonrpc "2.0" :id 1 :result (clj->js symbols)})
        full (str "Content-Length: " (.-length sym-resp) "\r\n\r\n" sym-resp)]
    (lsp/handle-message "test-lang" full state events)
    (let [syms (d/q '[:find (pull ?e [*]) :where [?e :type :symbol]] @(:conn @state))]
      (is (= 2 (count syms)) "Transacted flattened symbols")
      (is (= "test-uri" (:document/uri (first (first syms)))) "URI added to symbols")
      (is (some? (:symbol/parent (first (filter #(= "child" (:symbol/name %)) (map first syms))))) "Parent ref set"))))
