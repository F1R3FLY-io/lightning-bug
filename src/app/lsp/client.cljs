(ns app.lsp.client
  (:require
   [re-frame.core :as rf]
   [re-frame.db :refer [app-db]]
   [re-posh.core :as rp]
   [taoensso.timbre :as log]))

(defonce ws (atom nil))

;; Handles incoming LSP messages; dispatches based on response type or notification method.
(defn handle-message [msg]
  (let [parsed (js->clj (js/JSON.parse msg) :keywordize-keys true)]
    (log/debug "Received LSP message:" parsed)
    (if-let [id (:id parsed)]
      (let [type (get-in @app-db [:lsp :pending id])]
        (when type
          (rf/dispatch [:app.events/lsp-clear-pending id])
          (if-let [err (:error parsed)]
            (log/error "LSP response error:" (:message err) (:data err))
            (case type
              :initialize (log/info "LSP initialized")
              :validate (rp/dispatch [:app.events/lsp-diagnostics-update (:result parsed)])
              :document-symbol (rp/dispatch [:app.events/lsp-symbols-update (:result parsed)])
              (log/info "Unhandled response type:" type)))))
      (case (:method parsed)
        "window/logMessage" (rf/dispatch [:app.events/log-append (:params parsed)])
        "textDocument/publishDiagnostics" (rp/dispatch [:app.events/lsp-diagnostics-update (:diagnostics (:params parsed))])
        (log/info "Unhandled notification:" (:method parsed))))))

;; Sends an LSP message; sets pending if response expected.
(defn send [msg & {:keys [response-type]}]
  (if @ws
    (let [id (when response-type (rand-int 1000))
          msg (if id (assoc msg :id id) msg)]
      (when id (rf/dispatch [:app.events/lsp-set-pending id response-type]))
      (log/debug "Sending LSP msg:" (:method msg) "id" id)
      (if (== (.-readyState @ws) 1)
        (.send @ws (js/JSON.stringify (assoc msg :jsonrpc "2.0")))
        (log/warn "WS not open; cannot send message:" (:method msg))))
    (log/warn "No LSP connection; message not sent:" (:method msg))))

;; Establishes WebSocket connection to LSP server; sends initialization messages on open.
(defn connect [url]
  (log/info "Initializing LSP connection to" url)
  (when @ws (.close @ws))
  (let [socket (js/WebSocket. url)]
    (set! (.-onopen socket) #(do (log/info "LSP WS open")
                                 (send {:method "initialize" :params {:capabilities {}}} :response-type :initialize)
                                 (send {:method "initialized" :params {}})
                                 (rf/dispatch [:app.events/lsp-set-connection true])))
    (set! (.-onmessage socket) #(handle-message (.-data %)))
    (set! (.-onclose socket) #(do (log/warn "LSP WS closed")
                                  (rf/dispatch [:app.events/lsp-set-connection false])
                                  (when-not (get-in @app-db [:lsp :retry?])
                                    (rf/dispatch-sync [:app.events/lsp-connect])
                                    (swap! app-db assoc-in [:lsp :retry?] true))))
    (set! (.-onerror socket) #(do (log/error "LSP connection error:" (or (.-message %) "unknown") (or (.-code %) "unknown"))
                                  (rf/dispatch [:app.events/lsp-set-connection false])))
    (reset! ws socket)))

;; Requests document symbols from LSP for the given URI.
(defn request-symbols [uri]
  (send {:method "textDocument/documentSymbol" :params {:textDocument {:uri uri}}} :response-type :document-symbol))
