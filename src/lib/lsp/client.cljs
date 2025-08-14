(ns lib.lsp.client
  (:require
   [datascript.core :as d]
   [lib.db :refer [diagnostics-tx symbols-tx flatten-diags flatten-symbols]]
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
            (.next events (clj->js {:type "lsp-message" :data parsed-with-lang})) ;; Emit all LSP messages with lang.
              (if-let [id (:id parsed)]
                (let [pending (get-in @state-atom [:lsp lang :pending id])
                      type (if (map? pending) (:type pending) pending)
                      pending-uri (:uri pending)]
                  (when type
                    (swap! state-atom update-in [:lsp lang :pending] dissoc id)
                    (if (:error parsed)
                      (do
                        (log/error "LSP response error for lang" lang ":" (:error parsed))
                        (.next events (clj->js {:type "lsp-error" :data (assoc (:error parsed) :lang lang)})))
                      (case type
                        :initialize (do
                                      (log/info "LSP initialized for lang" lang)
                                      (send lang {:method "initialized" :params {}} state-atom)
                                      (swap! state-atom assoc-in [:lsp lang :initialized?] true)
                                      (.next events (clj->js {:type "lsp-initialized" :data {:lang lang}}))
                                      (doseq [[uri doc] (get-in @state-atom [:workspace :documents])]
                                        (when (and (= lang (:language doc)) (not (:opened? doc)))
                                          (send lang {:method "textDocument/didOpen"
                                                      :params {:textDocument {:uri uri
                                                                              :languageId lang
                                                                              :version (:version doc)
                                                                              :text (:content doc)}}} state-atom)
                                          (request-symbols lang uri state-atom)
                                          (swap! state-atom assoc-in [:workspace :documents uri :opened?] true))))
                        :document-symbol (let [hier-symbols (:result parsed)
                                               symbols (flatten-symbols hier-symbols nil pending-uri)]
                                           (d/transact! (:conn @state-atom) (symbols-tx hier-symbols pending-uri))
                                           (.next events (clj->js {:type "symbols" :data symbols})))
                        (log/warn "Unhandled response type for lang" lang ":" type)))))
                (case (:method parsed)
                  "window/logMessage" (let [log-entry (:params parsed)]
                                        (swap! state-atom update :logs conj (assoc log-entry :lang lang))
                                        (.next events (clj->js {:type "log" :data (assoc log-entry :lang lang)})))
                  "textDocument/publishDiagnostics" (let [diag-params (:params parsed)
                                                          diags (flatten-diags diag-params)]
                                                      (d/transact! (:conn @state-atom) (diagnostics-tx diags))
                                                      (.next events (clj->js {:type "diagnostics" :data diags})))
                  (log/info "Unhandled notification for lang" lang ":" (:method parsed))))))))))

(defn connect
  "Establishes a WebSocket connection to the LSP server for a specific language.
  Sets up event handlers and initializes the LSP for that lang."
  [lang config state-atom events]
  (let [url (:url config)]
    (when-let [existing (get-in @state-atom [:lsp lang :ws])]
      (.close existing))
    (let [socket (js/WebSocket. url)]
      (set! (.-binaryType socket) "arraybuffer")
      (set! (.-onopen socket) #(do (log/info "LSP WS open for lang" lang)
                                   (send lang {:method "initialize" :params {:capabilities {}} :response-type :initialize} state-atom)
                                   (swap! state-atom assoc-in [:lsp lang :connection] true)
                                   (.next events (clj->js {:type "connect" :data {:lang lang}}))))
      (set! (.-onmessage socket) #(handle-message lang (.-data %) state-atom events))
      (set! (.-onclose socket) #(do (log/warn "LSP WS closed for lang" lang)
                                    (swap! state-atom update :lsp dissoc lang)
                                    (.next events (clj->js {:type "disconnect" :data {:lang lang}}))))
      (set! (.-onerror socket) #(do (log/error "LSP connection error for lang" lang ":" %)
                                    (js/window.console.debug %)
                                    (.next events (clj->js {:type "lsp-error" :data (assoc % :lang lang)}))))
      (swap! state-atom update-in [:lsp lang] assoc
             :ws socket
             :pending {}
             :initialized? false
             :url url))))
