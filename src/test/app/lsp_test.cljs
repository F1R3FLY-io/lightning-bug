(ns test.app.lsp-test
  (:require [clojure.test :refer [deftest is]]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]
            [app.lsp.client :as lsp]))

(deftest connect
  (with-redefs [js/WebSocket (fn [url] (is (= url "ws://test"))
                               #js {:onopen (fn [])
                                    :onmessage (fn [])
                                    :onclose (fn [])
                                    :onerror (fn [])
                                    :close (fn [])})]
    (lsp/connect "ws://test")
    (is (some? @lsp/ws))))

(deftest send-fallback
  (with-redefs [lsp/ws (atom nil)]
    (lsp/send {:method "test"})
    (is true "Message not sent due to no connection")))

(deftest handle-message
  (with-redefs [rf/dispatch (fn [evt] (is (= evt [:app.events/lsp-diagnostics-update []])))]
    (lsp/handle-message (js/JSON.stringify (clj->js {:method "textDocument/publishDiagnostics" :params {:diagnostics []}})))))

(deftest retry-on-close
  (with-redefs [js/WebSocket (fn [_] #js {:onopen (fn [])
                                          :onmessage (fn [])
                                          :onclose (fn [])
                                          :onerror (fn [])
                                          :close (fn [])})
                rf/dispatch-sync (fn [evt] (is (= evt [:app.events/lsp-connect])))]
    (lsp/connect "ws://test")
    ((.-onclose @lsp/ws))
    (is (get-in @app-db [:lsp :retry?]) "Sets retry flag")))
