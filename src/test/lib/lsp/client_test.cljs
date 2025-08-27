(ns test.lib.lsp.client-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure.string :as str]
   [clojure.spec.alpha :as s]
   [datascript.core :as d]
   [reagent.core :as r]
   ["rxjs" :as rxjs]
   [lib.db :as db :refer [conn flatten-symbols create-documents! replace-symbols!]]
   [lib.lsp.client :as lsp]))

(use-fixtures :each
  (fn [f]
    (d/reset-conn! conn (d/empty-db db/schema))
    (f)))

(deftest connect-mock-websocket
  (let [state (r/atom {:lsp {:pending {}}})
        events (rxjs/Subject.)
        sock (js/Object.)]
    (set! (.-close sock) (fn [] ((.-onclose sock))))
    (set! (.-send sock) (fn [_data]))
    (with-redefs [js/WebSocket (fn [_] sock)]
      (lsp/connect "test" {:url "ws://test"} state events)
      (is (some? (get-in @state [:lsp "test" :ws]))))))

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
    (create-documents! [{:uri uri
                         :text ""
                         :language "test"
                         :version 1
                         :dirty false
                         :opened true}])
    (replace-symbols! uri flat-symbols)
    (is (= 2 (count flat-symbols)))
    (let [syms (db/symbols)
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
  (let [state (r/atom {:lsp {"test" {:ws (js/Object.)
                                     :pending {}
                                     :connected? true
                                     :reachable true}}})
        msg {:method "test"}
        sent (atom nil)]
    (set! (.-send (get-in @state [:lsp "test" :ws])) (fn [data] (reset! sent data)))
    (lsp/send "test" msg state)
    (is (some? @sent) "Message sent over WebSocket")
    (is (str/includes? @sent "Content-Length:") "Includes Content-Length header")
    (is (str/includes? @sent "\"method\":\"test\"") "JSON contains method")))

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

(deftest handle-message-with-header
  (let [state (r/atom {:lsp {"test-lang" {:pending {1 :initialize}}}})
        events (rxjs/Subject.)
        response-js #js {:jsonrpc "2.0"
                         :id 1
                         :result #js {:capabilities #js {}}}
        response (js/JSON.stringify response-js)
        full (str "Content-Length: " (.-length response) "\r\n\r\n" response)]
    (lsp/handle-message "test-lang" full state events)
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
    (db/create-documents! [{:uri "test-uri"
                            :text ""
                            :language "test-lang"
                            :version 1
                            :dirty false
                            :opened true}])
    (db/update-active-uri! "test-uri")
    (lsp/handle-message "test-lang" full state events)
    (let [diags (db/diagnostics)]
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
    (lsp/handle-message "test-lang" full state events)
    (let [logs (db/logs)]
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
        _ (create-documents! doc-tx)]
    (lsp/handle-message "test-lang" full state events)
    (let [syms (db/symbols)]
      (is (= 2 (count syms)) "Transacted flattened symbols")
      (let [root (first (filter #(= "root" (:name %)) syms))
            child (first (filter #(= "child" (:name %)) syms))]
        (is (= "test-uri" (:uri root)) "URI added to root")
        (is (= "test-uri" (:uri child)) "URI added to child")
        (is (not= (:parent root) (:parent child)) "Child parent differs from root")))))

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
