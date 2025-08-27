(ns test.lib.mock-lsp
  (:require [clojure.core.async :refer [go <!]]
            [lib.lsp.client :as lsp]
            [taoensso.timbre :as log]))

(defn create-mock-socket []
  (let [sock (js/Object.)
        sent (atom [])]
    (set! (.-binaryType sock) "arraybuffer")
    (set! (.-send sock) (fn [data] (swap! sent conj data)))
    (set! (.-close sock) (fn [] (when (.-onclose sock) ((.-onclose sock)))))
    {:sock sock
     :sent sent
     :trigger-open (fn []
                     (if (.-onopen sock)
                       (do
                         (log/trace "Mock: triggering onopen")
                         ((.-onopen sock)))
                       (log/warn "Mock: onopen not set")))
     :trigger-message (fn [msg]
                        (if (.-onmessage sock)
                          (do
                            (log/trace "Mock: triggering onmessage")
                            ((.-onmessage sock) #js {:data msg}))
                          (log/warn "Mock: onmessage not set")))
     :trigger-close (fn []
                      (if (.-onclose sock)
                        (do
                          (log/trace "Mock: triggering onclose")
                          ((.-onclose sock)))
                        (log/warn "Mock: onclose not set")))
     :trigger-error (fn [err]
                      (if (.-onerror sock)
                        (do
                          (log/trace "Mock: triggering onerror")
                          ((.-onerror sock) err))
                        (log/warn "Mock: onerror not set")))}))

(defn mock-send-raw [sent-atom]
  (fn [_lang full-msg _state]
    (swap! sent-atom conj full-msg)))

(defn respond! [mock request-id result-body]
  (let [message {:jsonrpc "2.0" :id request-id :result result-body}
        json (js/JSON.stringify (clj->js message))
        header (str "Content-Length: " (.-length json) "\r\n\r\n")
        full (str header json)]
    ((:trigger-message mock) full)))

(defn error! [mock error-body]
  (let [message {:jsonrpc "2.0" :id nil :error error-body}
        json (js/JSON.stringify (clj->js message))
        header (str "Content-Length: " (.-length json) "\r\n\r\n")
        full (str header json)]
    ((:trigger-message mock) full)))

(defn with-mock-lsp [lang config state-atom events body-fn]
  (let [mock (create-mock-socket)
        sock (:sock mock)
        original-WebSocket js/WebSocket]
    (log/trace "Mock: overriding js/WebSocket")
    (set! js/WebSocket (fn [_url]
                         (log/trace "Mock: creating mock socket (ignoring URL)")
                         sock))
    (lsp/connect lang config state-atom events)
    (go
      (let [body-ch (body-fn mock)]
        (<! body-ch))
      ((:trigger-close mock))
      (log/trace "Mock: restoring original js/WebSocket")
      (set! js/WebSocket original-WebSocket))))
