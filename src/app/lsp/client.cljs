(ns app.lsp.client
  (:require [re-frame.core :as rf]
            [taoensso.timbre :as log]
            [clojure.walk :as walk]))

(defonce ws (atom nil))

(defn handle-message [msg]
  (let [parsed (walk/keywordize-keys (js/JSON.parse msg))]
    (condp = (:method parsed)
      "textDocument/publishDiagnostics" (rf/dispatch [:lsp/diagnostics-update (:params parsed)])
      "window/logMessage" (rf/dispatch [:log/append (:params parsed)])
      ;; Handle symbols, etc.
      (log/info "Unhandled LSP msg" parsed))))

(defn connect [url]
  (let [socket (js/WebSocket. url)]
    (set! (.-onopen socket) #(do (log/info "LSP WS open")
                                 (send {:method "initialize" :params {:capabilities {}}}))) ; LSP init
    (set! (.-onmessage socket) #(handle-message (.-data %)))
    (set! (.-onclose socket) #(log/warn "LSP WS closed"))
    (set! (.-onerror socket) #(log/error "LSP error" %))
    (reset! ws socket)))

(defn send [msg]
  (when @ws
    (.send @ws (js/JSON.stringify (assoc msg :jsonrpc "2.0" :id (rand-int 1000))))))

;; Add functions for didChange, didOpen, documentSymbol request, etc.
(defn request-symbols [uri]
  (send {:method "textDocument/documentSymbol" :params {:textDocument {:uri uri}}}))
