(ns test.lib.lsp.client-test
  (:require
   ["rxjs" :as rxjs]
   [clojure.test :refer [deftest is]]
   [datascript.core :as d]
   [reagent.core :as r]
   [lib.lsp.client :as lsp]))

(deftest connect-mock
  (let [state (r/atom {:lsp {:pending {}} :conn (d/create-conn)})
        events (rxjs/Subject.)]
    (with-redefs [js/WebSocket (fn [_] #js {:onopen (fn []), :onmessage (fn []), :onclose (fn []), :onerror (fn [])})]
      (lsp/connect {:type "websocket" :url "ws://test"} state events)
      (is (some? @lsp/ws)))))

(deftest flatten-symbols-basic
  (let [syms [{:name "a" :children [{:name "b"}]}]]
    (is (= 2 (count (lsp/flatten-symbols syms nil))))))
