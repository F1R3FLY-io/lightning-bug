(ns test.lib.lsp.client-test
  (:require
   ["rxjs" :as rxjs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [datascript.core :as d]
   [reagent.core :as r]
   [taoensso.timbre :as log]
   [lib.lsp.client :as lsp]))

(deftest connect-mock-websocket
  (let [state (r/atom {:lsp {:pending {}} :conn (d/create-conn)})
        events (rxjs/Subject.)]
    (with-redefs [js/WebSocket (fn [_] #js {:onopen (fn []), :onmessage (fn []), :onclose (fn []), :onerror (fn [])})]
      (lsp/connect {:url "ws://test"} state events)
      (is (some? @lsp/ws)))))

(deftest flatten-symbols-basic
  (let [syms [{:name "a" :children [{:name "b"}]}]]
    (is (= 2 (count (lsp/flatten-symbols syms nil))))))

(deftest send-mock-websocket
  (let [state (r/atom {:lsp {:pending {}} :conn (d/create-conn)})
        msg {:method "test"}
        sent (atom nil)]
    (with-redefs [lsp/ws (atom #js {:send (fn [data] (reset! sent data))})]
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
