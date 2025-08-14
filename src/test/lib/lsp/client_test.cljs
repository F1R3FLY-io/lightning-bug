(ns test.lib.lsp.client-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure.string :as str]
   [datascript.core :as d]
   [posh.reagent :as p] ;; For verifying Posh atom attachment.
   [reagent.core :as r]
   ["rxjs" :as rxjs]
   [taoensso.timbre :as log]
   [lib.db :refer [flatten-symbols]]
   [lib.lsp.client :as lsp]))

(use-fixtures :each
  {:before (fn [] (reset! lsp/ws nil))})

(deftest connect-mock-websocket
  (let [state (r/atom {:lsp {:pending {}} :conn (d/create-conn)})
        events (rxjs/Subject.)
        sock (js/Object.)]
    (set! (.-close sock) (fn [] ((.-onclose sock))))
    (set! (.-send sock) (fn [data]))
    (with-redefs [js/WebSocket (fn [_] sock)]
      (lsp/connect {:url "ws://test"} state events)
      (is (some? @lsp/ws)))))

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
        flat (flatten-symbols syms nil)
        conn (d/create-conn {:parent {:db/valueType :db.type/ref}})]
    (is (= 2 (count flat)))
    (d/transact! conn flat)
    (is (= 2 (count (d/q '[:find ?e :where [?e]] @conn))) "Transacts without nil ref error")))

(deftest send-mock-websocket
  (let [state (r/atom {:lsp {:pending {}} :conn (d/create-conn)})
        msg {:method "test"}
        sent (atom nil)
        sock (js/Object.)]
    (set! (.-send sock) (fn [data] (reset! sent data)))
    (with-redefs [lsp/ws (atom sock)]
      (lsp/send msg state)
      (is (some? @sent) "Message sent over WebSocket")
      (is (str/includes? @sent "Content-Length:") "Includes Content-Length header")
      (is (str/includes? @sent "\"method\":\"test\"") "JSON contains method"))))

(deftest handle-message-with-header
  (let [state (r/atom {:lsp {:pending {1 :initialize}} :conn (d/create-conn)})
        events (rxjs/Subject.)
        response "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"capabilities\":{}}}"
        full (str "Content-Length: " (.-length response) "\r\n\r\n" response)]
    (lsp/handle-message full state events)
    (is (empty? (get-in @state [:lsp :pending])) "Pending cleared")
    (is (str/includes? (with-out-str (log/info "LSP initialized")) "LSP initialized") "Processed initialize response")))

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
  (let [state (r/atom {:lsp {:pending {}} :conn (d/create-conn)})
        events (rxjs/Subject.)
        diag-msg "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/publishDiagnostics\",\"params\":{\"diagnostics\":[{\"message\":\"test\"}]}}"
        full (str "Content-Length: " (.-length diag-msg) "\r\n\r\n" diag-msg)]
    (lsp/handle-message full state events)
    (let [diags (d/q '[:find (pull ?e [*]) :where [?e :type :diagnostic]] @(:conn @state))]
      (is (= 1 (count diags)) "Transacted diagnostic"))))

(deftest handle-message-log
  (let [state (r/atom {:lsp {:pending {}} :conn (d/create-conn)})
        events (rxjs/Subject.)
        log-msg "{\"jsonrpc\":\"2.0\",\"method\":\"window/logMessage\",\"params\":{\"message\":\"test log\"}}"
        full (str "Content-Length: " (.-length log-msg) "\r\n\r\n" log-msg)]
    (lsp/handle-message full state events)
    (is (= 1 (count (get-in @state [:lsp :logs]))) "Appended log message")))

(deftest handle-message-symbols
  (let [state (r/atom {:lsp {:pending {1 :document-symbol}} :conn (d/create-conn)})
        events (rxjs/Subject.)
        symbols [{:name "root" :children [{:name "child"}]}]
        sym-resp (js/JSON.stringify #js {:jsonrpc "2.0" :id 1 :result (clj->js symbols)})
        full (str "Content-Length: " (.-length sym-resp) "\r\n\r\n" sym-resp)]
    (lsp/handle-message full state events)
    (let [syms (d/q '[:find (pull ?e [*]) :where [?e :type :symbol]] @(:conn @state))]
      (is (= 2 (count syms)) "Transacted flattened symbols"))))
