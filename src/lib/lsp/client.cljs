(ns lib.lsp.client
  (:require
   [datascript.core :as d]
   [taoensso.timbre :as log]))

(defonce ws (atom nil))

(defn flatten-symbols [symbols parent-id]
  (let [id-counter (atom -1)]
    (letfn [(flatten-rec [syms parent]
              (mapcat (fn [s]
                        (let [sid (swap! id-counter dec)
                              s' (assoc (dissoc s :children) :db/id sid :parent parent)
                              children (:children s)]
                          (cons s' (when children (flatten-rec children sid)))))
                      syms))]
      (flatten-rec symbols parent-id))))

(defn handle-message [msg state-atom events]
  (let [parsed (js->clj (js/JSON.parse msg) :keywordize-keys true)]
    (log/debug "Received LSP message:" parsed)
    (if-let [id (:id parsed)]
      (let [type (get-in @state-atom [:lsp :pending id])]
        (when type
          (swap! state-atom update-in [:lsp :pending] dissoc id)
          (if-let [err (:error parsed)]
            (log/error "LSP response error:" err)
            (case type
              :initialize (log/info "LSP initialized")
              :validate (let [diags (:result parsed)]
                          (d/transact! (:conn @state-atom) (map #(assoc % :type :diagnostic) diags))
                          (.next events (clj->js {:type "diagnostics" :data diags})))
              :document-symbol (let [flat (flatten-symbols (:result parsed) nil)
                                     tx (map #(assoc % :type :symbol) flat)]
                                 (d/transact! (:conn @state-atom) tx)
                                 (.next events (clj->js {:type "symbols-update" :data flat})))
              (log/warn "Unhandled response type:" type)))))
      (case (:method parsed)
        "window/logMessage" (let [log-entry (:params parsed)]
                              (swap! state-atom update-in [:lsp :logs] conj log-entry)
                              (.next events (clj->js {:type "log" :data log-entry})))
        "textDocument/publishDiagnostics" (let [diags (:diagnostics (:params parsed))]
                                             (d/transact! (:conn @state-atom) (map #(assoc % :type :diagnostic) diags))
                                             (.next events (clj->js {:type "diagnostics" :data diags})))
        (log/info "Unhandled notification:" (:method parsed))))))

(defn send [msg state-atom]
  (if @ws
    (let [id (rand-int 10000)
          msg (assoc msg :id id :jsonrpc "2.0")]
      (swap! state-atom assoc-in [:lsp :pending id] (:response-type msg))
      (log/debug "Sending LSP msg:" msg)
      (.send @ws (js/JSON.stringify (clj->js msg)))
      true)
    (do
      (log/warn "No LSP connection")
      false)))

(defn connect [config state-atom events]
  (when @ws (.close @ws))
  (if (= (:type config) "websocket")
    (let [url (:url config)
          socket (js/WebSocket. url)]
      (set! (.-onopen socket) #(do (log/info "LSP WS open")
                                   (send {:method "initialize" :params {:capabilities {}} :response-type :initialize} state-atom)
                                   (send {:method "initialized" :params {}} state-atom)
                                   (swap! state-atom assoc-in [:lsp :connection] true)
                                   (swap! state-atom assoc-in [:lsp :url] url)
                                   (.next events (clj->js {:type "connect"}))))
      (set! (.-onmessage socket) #(handle-message (.-data %) state-atom events))
      (set! (.-onclose socket) #(do (log/warn "LSP WS closed")
                                    (swap! state-atom assoc-in [:lsp :connection] false)
                                    (swap! state-atom assoc-in [:lsp :url] nil)
                                    (.next events (clj->js {:type "disconnect"}))))
      (set! (.-onerror socket) #(log/error "LSP connection error:" %))
      (reset! ws socket))
    (log/warn "Unsupported LSP transport type:" (:type config))))

(defn request-symbols [uri state-atom]
  (send {:method "textDocument/documentSymbol" :params {:textDocument {:uri uri}} :response-type :document-symbol} state-atom))
