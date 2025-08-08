(ns lib.lsp.client
  (:require
    [datascript.core :as d]
    [lib.db :refer [diagnostics-tx symbols-tx flatten-diags flatten-symbols]]
    [taoensso.timbre :as log]))

(defonce ws (atom nil))

(defn- get-text [data]
  (if (instance? js/ArrayBuffer data)
    (.decode (js/TextDecoder. "utf-8") data)
    data))

(defn send
  "Sends an LSP message over the WebSocket connection with Content-Length header.
  Adds an ID for requests if :response-type is present."
  [msg state-atom]
  (let [id (or (:id msg) (when (:response-type msg) (rand-int 10000)))
        msg (cond-> (assoc msg :jsonrpc "2.0") id (assoc :id id))
        json-str (js/JSON.stringify (clj->js msg))
        header (str "Content-Length: " (.-length json-str) "\r\n\r\n")
        full (str header json-str)]
    (when id
      (swap! state-atom assoc-in [:lsp :pending id] (:response-type msg)))
    (if-let [ws-conn @ws]
      (do
        (log/debug "Sending LSP msg over WebSocket:" msg)
        (.send ws-conn full))
      (log/warn "No WebSocket for LSP send"))))

(defn request-symbols
  "Requests document symbols from the LSP server for the given URI."
  [uri state-atom]
  (send {:method "textDocument/documentSymbol" :params {:textDocument {:uri uri}} :response-type :document-symbol} state-atom))

(defn handle-message
  "Handles incoming LSP messages, parsing the transport header, then JSON, logging, and processing responses or notifications.
  Emits events for all relevant LSP messages."
  [msg state-atom events]
  (let [text (get-text msg)]
    (log/debug "Received raw LSP message:" text)
    (let [header-end (.indexOf text "\r\n\r\n")]
      (if (= -1 header-end)
        (log/error "No header found in LSP message:" text)
        (let [header (.substring text 0 header-end)
              match (.match header #"Content-Length: (\d+)")
              length (when match (js/parseInt (aget match 1)))
              body-start (+ header-end 4)
              body (.substring text body-start (+ body-start length))]
          (if (not= length (.-length body))
            (log/error "Incomplete LSP body: expected" length "got" (.-length body))
            (let [parsed-js (js/JSON.parse body)]
              (log/debug "Parsed LSP JSON:" parsed-js)
              (let [parsed (js->clj parsed-js :keywordize-keys true)]
                (.next events (clj->js {:type "lsp-message" :data parsed})) ;; Emit all LSP messages for clients.
                (if-let [id (:id parsed)]
                  (let [type (get-in @state-atom [:lsp :pending id])]
                    (when type
                      (swap! state-atom update-in [:lsp :pending] dissoc id)
                      (if (:error parsed)
                        (do
                          (log/error "LSP response error:" (:error parsed))
                          (.next events (clj->js {:type "lsp-error" :data (:error parsed)})))
                        (case type
                          :initialize (do
                                        (log/info "LSP initialized")
                                        (send {:method "initialized" :params {}} state-atom)
                                        (swap! state-atom assoc-in [:lsp :initialized?] true)
                                        (.next events (clj->js {:type "lsp-initialized"}))
                                        (when (and (:uri @state-atom) (not (:opened? @state-atom)))
                                          (let [uri (:uri @state-atom)
                                                lang (:language @state-atom)
                                                version (:version @state-atom)
                                                text (:content @state-atom)]
                                            (send {:method "textDocument/didOpen"
                                                   :params {:textDocument {:uri uri
                                                                           :languageId lang
                                                                           :version version
                                                                           :text text}}}
                                                  state-atom)
                                            (request-symbols uri state-atom)
                                            (swap! state-atom assoc :opened? true))))
                          :document-symbol (let [hier-symbols (:result parsed)
                                                 symbols (flatten-symbols hier-symbols nil)]
                                             (.next events (clj->js {:type "symbols" :data symbols})))
                          (log/warn "Unhandled response type:" type)))))
                  (case (:method parsed)
                    "window/logMessage" (let [log-entry (:params parsed)]
                                          (swap! state-atom update-in [:lsp :logs] conj log-entry)
                                          (.next events (clj->js {:type "log" :data log-entry})))
                    "textDocument/publishDiagnostics" (let [diag-params (:params parsed)
                                                            diags (flatten-diags diag-params)]
                                                        (.next events (clj->js {:type "diagnostics" :data diags})))
                    (log/info "Unhandled notification:" (:method parsed))))))))))))

(defn connect
  "Establishes a WebSocket connection to the LSP server.
  Sets up event handlers and initializes the LSP."
  [config state-atom events]
  (let [url (:url config)]
    (when @ws (.close @ws))
    (let [socket (js/WebSocket. url)]
      (set! (.-binaryType socket) "arraybuffer")
      (set! (.-onopen socket) #(do (log/info "LSP WS open")
                                   (send {:method "initialize" :params {:capabilities {}} :response-type :initialize} state-atom)
                                   (swap! state-atom update :lsp assoc :connection true :initialized? false :url url)
                                   (.next events (clj->js {:type "connect"}))))
      (set! (.-onmessage socket) #(handle-message (.-data %) state-atom events))
      (set! (.-onclose socket) #(do (log/warn "LSP WS closed")
                                    (swap! state-atom (fn [s]
                                                        (-> s
                                                            (assoc :opened? false)
                                                            (update :lsp assoc :connection false :initialized? false :url nil))))
                                    (.next events (clj->js {:type "disconnect"}))))
      (set! (.-onerror socket) #(do (log/error "LSP connection error:" %)
                                    (js/window.console.debug %)
                                    (.next events (clj->js {:type "lsp-error" :data %}))))
      (reset! ws socket))))
