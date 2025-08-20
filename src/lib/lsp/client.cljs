(ns lib.lsp.client
  (:require
   [clojure.core.async :as async :refer [put!]]
   [lib.db :as db :refer [flatten-diags flatten-symbols]]
   [taoensso.timbre :as log]))

(defn- get-text [data]
  (if (instance? js/ArrayBuffer data)
    (.decode (js/TextDecoder. "utf-8") data)
    data))

(defn send
  "Sends an LSP message over the WebSocket connection with Content-Length header.
  Adds an ID for requests if :response-type is present."
  [lang msg state-atom]
  (let [id (or (:id msg) (when (:response-type msg) (rand-int 10000)))
        msg (cond-> (assoc msg :jsonrpc "2.0") id (assoc :id id))
        json-str (js/JSON.stringify (clj->js msg))
        header (str "Content-Length: " (.-length json-str) "\r\n\r\n")
        full (str header json-str)]
    (log/info "Sending LSP message for lang" lang ":" (:method msg) "id" id)
    (log/trace "Full LSP send message:\n" full)
    (when id
      (swap! state-atom assoc-in [:lsp lang :pending id] (if (:uri msg) {:type (:response-type msg) :uri (:uri msg)} (:response-type msg))))
    (if-let [ws (get-in @state-atom [:lsp lang :ws])]
      (.send ws full)
      (log/warn "No WebSocket for LSP send for lang" lang))))

(defn request-symbols
  "Requests document symbols from the LSP server for the given lang and URI."
  [lang uri state-atom]
  (send lang {:method "textDocument/documentSymbol" :params {:textDocument {:uri uri}} :response-type :document-symbol :uri uri} state-atom))

(defn handle-message
  "Handles incoming LSP messages for a specific lang, parsing the transport header, then JSON, logging, and processing responses or notifications.
  Emits events for all relevant LSP messages with lang added to data."
  [lang msg state-atom events]
  (let [text (get-text msg)
        header-end (.indexOf text "\r\n\r\n")]
    (if (= -1 header-end)
      (log/error "No header found in LSP message for lang" lang ":" text)
      (let [header (.substring text 0 header-end)
            match (.match header #"Content-Length: (\d+)")
            length (when match (js/parseInt (aget match 1)))
            body-start (+ header-end 4)
            body (.substring text body-start (+ body-start length))]
        (if (not= length (.-length body))
          (log/error "Incomplete LSP body for lang" lang ": expected" length "got" (.-length body))
          (let [parsed-js (js/JSON.parse body)
                parsed (js->clj parsed-js :keywordize-keys true)
                parsed-with-lang (assoc parsed :lang lang)]
            (log/info "Received LSP message for lang" lang ":" (:method parsed) "id" (:id parsed))
            (log/trace "Full LSP received message:\n" parsed)
            (.next events (clj->js {:type "lsp-message" :data parsed-with-lang})) ;; Emit all LSP messages with lang.
            (if-let [id (:id parsed)]
              (let [pending (get-in @state-atom [:lsp lang :pending id])
                    pending-type (if (map? pending) (:type pending) pending)
                    pending-uri (:uri pending)]
                (when pending-type
                  (swap! state-atom update-in [:lsp lang :pending] dissoc id)
                  (if (:error parsed)
                    (do
                      (log/error "LSP response error for lang" lang ":" (:error parsed))
                      (.next events (clj->js {:type "lsp-error" :data (assoc (:error parsed) :lang lang)})))
                    (case pending-type
                      :initialize (do
                                    (log/info "LSP initialized for lang" lang)
                                    (send lang {:method "initialized" :params {}} state-atom)
                                    (swap! state-atom assoc-in [:lsp lang :initialized?] true)
                                    (.next events (clj->js {:type "lsp-initialized" :data {:lang lang}}))
                                    ;; Signal success on conn-ch.
                                    (when-let [ch (get-in @state-atom [:lsp lang :connect-chan])]
                                      (async/put! ch :success)))
                      :document-symbol (let [hier-symbols (:result parsed)
                                             flat-symbols (flatten-symbols hier-symbols nil pending-uri)]
                                         (db/replace-symbols! pending-uri flat-symbols)
                                         (.next events (clj->js {:type "symbols" :data flat-symbols})))
                      (log/warn "Unhandled response type for lang" lang ":" pending-type)))))
              (case (:method parsed)
                "window/logMessage" (let [log-entry (assoc (:params parsed) :lang lang)]
                                      (db/create-logs! [log-entry])
                                      (.next events (clj->js {:type "log" :data log-entry})))
                "textDocument/publishDiagnostics" (let [diag-params (:params parsed)
                                                        uri (:uri diag-params)
                                                        version (:version diag-params)
                                                        diags (:diagnostics diag-params)
                                                        flat-diags (flatten-diags diags uri version)]
                                                    (db/replace-diagnostics-by-uri! uri version flat-diags)
                                                    (.next events (clj->js {:type "diagnostics" :data flat-diags})))
                (log/info "Unhandled notification for lang" lang ":" (:method parsed))))))))))

(defn connect
  "Establishes a WebSocket connection to the LSP server for a specific language.
  Sets up event handlers and initializes the LSP for that lang.
  Puts :success or :error on the provided channel."
  [lang config state-atom events ch]
  (let [url (:url config)]
    (if-let [existing (get-in @state-atom [:lsp lang :ws])]
      (.close existing)
      (log/debug "No existing WS to close for lang" lang))
    (let [socket (js/WebSocket. url)]
      (set! (.-binaryType socket) "arraybuffer")
      (swap! state-atom update-in [:lsp lang] assoc
             :ws socket
             :pending {}
             :initialized? false
             :url url)
      (set! (.-onopen socket) #(do (log/info "LSP WS open for lang" lang)
                                   (swap! state-atom update-in [:lsp lang] assoc
                                          :connected? true
                                          :connecting false)
                                   (.next events (clj->js {:type "connect" :data {:lang lang}}))
                                   (send lang {:method "initialize"
                                               :params {:capabilities {}}
                                               :id (rand-int 10000)
                                               :response-type :initialize} state-atom)))
      (set! (.-onmessage socket) #(handle-message lang (.-data %) state-atom events))
      (set! (.-onclose socket) #(do (log/warn "LSP WS closed for lang" lang)
                                    (swap! state-atom update-in [:lsp lang]
                                           :connected? false
                                           :connecting false)
                                    (.next events (clj->js {:type "disconnect" :data {:lang lang}}))))
      (set! (.-onerror socket) #(do (log/error "LSP connection error for lang" lang ":" %)
                                    (js/window.console.debug %)
                                    (.next events (clj->js {:type "lsp-error" :data {:message "WebSocket connection error" :lang lang}}))
                                    (put! ch :error)))))
  ch)
