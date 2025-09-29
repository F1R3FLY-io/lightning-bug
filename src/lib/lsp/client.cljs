(ns lib.lsp.client
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [lib.db :as db :refer [flatten-diags flatten-symbols close-all-opened-by-lang!]]
   [lib.state :refer [close-resource! get-resource set-resource!]]
   [taoensso.timbre :as log]
   [clojure.core.async :refer [go <!]]
   [lib.utils :refer [promise->chan]]))

(s/def ::jsonrpc string?)
(s/def ::id (s/or :num number? :str string?))
(s/def ::method string?)
(s/def ::params map?)
(s/def ::result any?)
(s/def ::error map?)

(s/def ::request
  (s/keys :req-un [::jsonrpc ::id ::method]
          :opt-un [::params]))

(s/def ::notification
  (s/keys :req-un [::jsonrpc ::method]
          :opt-un [::params]))

(s/def ::response
  (s/keys :req-un [::jsonrpc ::id]
          :opt-un [::result ::error]))

(defn send-raw
  "Sends the full message string over the WebSocket if connected and reachable.
   Logs a warning if the server is unreachable and hasn't been warned yet."
  [lang full-msg state-atom]
  (let [lsp-state (get-in @state-atom [:lsp lang])
        connected? (:connected? lsp-state false)
        warned? (:warned-unreachable? lsp-state false)]
    (if connected?
      (when-let [ws (get-resource :lsp lang)]
        (log/trace (str "Sending raw LSP message for lang=" lang ", length=" (.-length full-msg) ":\n" full-msg))
        (.send ws full-msg))
      (when-not warned?
        (log/warn (str "LSP server unreachable for lang=" lang "; not sending message:\n" full-msg))
        (swap! state-atom assoc-in [:lsp lang :warned-unreachable?] true)))))

(defn send
  "Constructs and sends an LSP message with JSON and Content-Length header.
   Assigns a serial ID for requests if response-type is present and tracks pending requests."
  [lang msg state-atom]
  (let [response-type (:response-type msg)
        extra-uri (:uri msg)
        id (or (:id msg)
               (when response-type
                 (let [next-id (get-in @state-atom [:lsp lang :next-id] 1)]
                   (swap! state-atom assoc-in [:lsp lang :next-id] (inc next-id))
                   next-id)))
        msg-with-jsonrpc (assoc msg :jsonrpc "2.0")
        msg-with-id (if id (assoc msg-with-jsonrpc :id id) msg-with-jsonrpc)
        whitelisted-msg (select-keys msg-with-id [:jsonrpc :id :method :params])
        json-str (js/JSON.stringify (clj->js whitelisted-msg))
        header (str "Content-Length: " (.-length json-str) "\r\n\r\n")
        full-msg (str header json-str)]
    (when id
      (swap! state-atom assoc-in [:lsp lang :pending id]
             (if extra-uri
               {:type response-type :uri extra-uri}
               response-type)))
    (send-raw lang full-msg state-atom)))

;; Sending functions (client -> server)

(defn request-initialize
  "Sends an LSP initialize request to the server."
  [lang state-atom]
  (send lang {:method "initialize"
              :params {:capabilities {}}
              :response-type :initialize} state-atom))

(defn notify-initialized
  "Notifies the LSP server that initialization is complete."
  [lang state-atom]
  (send lang {:method "initialized" :params {}} state-atom))

(defn notify-did-open
  "Notifies the LSP server that a document has been opened."
  [lang uri text version state-atom]
  (send lang {:method "textDocument/didOpen"
              :params {:textDocument {:uri uri
                                      :languageId lang
                                      :version version
                                      :text text}}} state-atom))

(defn notify-did-change
  "Notifies the LSP server of document changes."
  [lang uri text version state-atom]
  (send lang {:method "textDocument/didChange"
              :params {:textDocument {:uri uri :version version}
                       :contentChanges [{:text text}]}} state-atom))

(defn notify-did-close
  "Notifies the LSP server that a document has been closed."
  [lang uri state-atom]
  (send lang {:method "textDocument/didClose"
              :params {:textDocument {:uri uri}}} state-atom))

(defn notify-did-save
  "Notifies the LSP server that a document has been saved."
  [lang uri text state-atom]
  (send lang {:method "textDocument/didSave"
              :params {:textDocument {:uri uri}
                       :text text}} state-atom))

(defn notify-did-rename-files
  "Notifies the LSP server of file renaming."
  ([lang files state-atom]
   (send lang {:method "workspace/didRenameFiles"
               :params {:files files}} state-atom))
  ([lang old-uri new-uri state-atom]
   (let [files [{:oldUri old-uri :newUri new-uri}]]
     (notify-did-rename-files lang files state-atom))))

(defn request-document-symbol
  "Requests document symbols from the LSP server."
  [lang uri state-atom]
  (send lang {:method "textDocument/documentSymbol"
              :params {:textDocument {:uri uri}}
              :response-type :document-symbol
              :uri uri} state-atom))

(defn request-shutdown
  "Requests shutdown of the LSP server for all languages or a specific one."
  ([state-atom]
   (doseq [[lang lsp-state] (:lsp @state-atom)]
     (when (and (:ws lsp-state) (:connected? lsp-state) (:reachable? lsp-state))
       (request-shutdown lang state-atom))))
  ([lang state-atom]
   (send lang {:method "shutdown"
               :response-type :shutdown} state-atom)))

(defn notify-exit
  "Notifies the LSP server to exit."
  [lang state-atom]
  (send lang {:method "exit"} state-atom))

;; Response handlers (server -> client responses)

(defn handle-initialize-response
  "Handles the LSP initialize response, marking the connection as initialized and resolving the promise."
  [lang _result state-atom events]
  (log/info "LSP initialized for lang" lang)
  (notify-initialized lang state-atom)
  (swap! state-atom assoc-in [:lsp lang :initialized?] true)
  (when-let [res-fn (get-in @state-atom [:lsp lang :promise-res-fn])]
    (res-fn)
    (swap! state-atom update-in [:lsp lang] dissoc :promise-res-fn :promise-rej-fn))
  (.next events (clj->js {:type "lsp-initialized" :data {:lang lang}})))

(defn handle-document-symbol-response
  "Handles the LSP document symbol response, updating the database with symbols."
  [lang uri result _state-atom events]
  (let [hier-symbols result
        flat-symbols (flatten-symbols hier-symbols nil uri)]
    (db/replace-symbols! uri flat-symbols)
    (.next events (clj->js {:type "symbols" :data flat-symbols :lang lang}))))

(defn handle-shutdown-response
  "Handles the LSP shutdown response, closing all open documents and notifying exit."
  [lang _result state-atom _events]
  (log/info "Received shutdown response for lang" lang)
  (close-all-opened-by-lang! lang)
  (notify-exit lang state-atom)
  (close-resource! :lsp lang (fn [ws] (.close ws))))

;; Notification handlers (server -> client notifications)

(defn handle-log-message
  "Handles LSP log messages, storing them in the database and emitting an event."
  [lang params _state-atom events]
  (let [log-entry (assoc params :lang lang)]
    (db/create-logs! [log-entry])
    (.next events (clj->js {:type "log" :data log-entry :lang lang}))))

(defn handle-publish-diagnostics
  "Handles LSP diagnostics notifications, updating the database and emitting an event."
  [lang params _state-atom events]
  (let [uri (:uri params)
        version (:version params)
        diags (:diagnostics params)
        flat-diags (flatten-diags diags uri version)
        active-version (db/active-version)]
    (when (and (= uri (db/active-uri))
               (or (nil? version) (= version active-version)))
      (db/replace-diagnostics-by-uri! uri version flat-diags)
      (.next events (clj->js {:type "diagnostics"
                              :data flat-diags
                              :lang lang
                              :uri uri
                              :version version})))))

(def server-notification-handlers
  {"window/logMessage" handle-log-message
   "textDocument/publishDiagnostics" handle-publish-diagnostics})

(def server-request-handlers
  {;; Add optional request handlers here if needed, e.g., "$/cancelRequest"
   })

(defn- get-text
  "Converts message data to text, handling ArrayBuffer if necessary."
  [data]
  (if (instance? js/ArrayBuffer data)
    (.decode (js/TextDecoder. "utf-8") data)
    data))

(defn handle-message
  "Parses and handles incoming LSP messages, dispatching to appropriate handlers."
  [lang msg state-atom events]
  (try
    (let [text (get-text msg)
          header-end (.indexOf text "\r\n\r\n")]
      (log/trace (str "Received raw LSP message for lang=" lang ", length=" (.-length text) ":\n" text))
      (if (= -1 header-end)
        (log/error (str "No header found in LSP message for lang=" lang ":" text))
        (let [header (.substring text 0 header-end)
              match (.match header #"Content-Length: (\d+)")
              length (when match (js/parseInt (aget match 1)))
              body-start (+ header-end 4)
              body (.substring text body-start (+ body-start length))]
          (if (not= length (.-length body))
            (log/error "Incomplete LSP body for lang" lang ": expected" length "got" (.-length body))
            (try
              (let [parsed-js (js/JSON.parse body)
                    parsed (js->clj parsed-js :keywordize-keys true)
                    parsed-with-lang (assoc parsed :lang lang)]
                (.next events (clj->js {:type "lsp-message" :data parsed-with-lang}))
                (let [type (cond
                             (and (:method parsed) (:id parsed)) :request
                             (:method parsed) :notification
                             (:id parsed) :response
                             :else :invalid)]
                  (if (= type :invalid)
                    (log/error "Invalid LSP message: no method or id:" parsed)
                    (do
                      (when-not (s/valid? (case type
                                            :request ::request
                                            :notification ::notification
                                            :response ::response)
                                          parsed)
                        (log/error "Message does not conform to spec" type
                                   (s/explain-str
                                    (case type
                                      :request ::request
                                      :notification ::notification
                                      :response ::response)
                                    parsed)))
                      (case type
                        :request
                        (let [method (:method parsed)
                              handler (get server-request-handlers method)]
                          (if handler
                            (handler lang (:params parsed) state-atom events)
                            (do
                              (log/error (str "No handler for server request=" method ", lang=" lang))
                              (send lang {:jsonrpc "2.0"
                                          :id (:id parsed)
                                          :error {:code -32601 :message "Method not found"}} state-atom))))

                        :notification
                        (let [method (:method parsed)
                              handler (get server-notification-handlers method)]
                          (if handler
                            (handler lang (:params parsed) state-atom events)
                            (if (str/starts-with? method "$/")
                              (log/warn (str "Optional server notification handler missing for method=" method ", lang=" lang))
                              (log/error (str "Required server notification handler missing for method=" method ", lang=" lang)))))

                        :response
                        (let [id (:id parsed)
                              pending (get-in @state-atom [:lsp lang :pending id])
                              pending-type (if (map? pending) (:type pending) pending)
                              pending-uri (:uri pending)]
                          (if pending
                            (do
                              (swap! state-atom update-in [:lsp lang :pending] dissoc id)
                              (if (:error parsed)
                                (do
                                  (log/error "LSP response error for lang" lang ":" (:error parsed))
                                  (.next events (clj->js {:type "lsp-error" :data (assoc (:error parsed) :lang lang)}))
                                  (when (= pending-type :initialize)
                                    (when-let [rej-fn (get-in @state-atom [:lsp lang :promise-rej-fn])]
                                      (rej-fn)
                                      (swap! state-atom update-in [:lsp lang] dissoc :promise-rej-fn :promise-res-fn))))
                                (case pending-type
                                  :initialize (handle-initialize-response lang (:result parsed) state-atom events)
                                  :document-symbol (handle-document-symbol-response lang pending-uri (:result parsed) state-atom events)
                                  :shutdown (handle-shutdown-response lang (:result parsed) state-atom events)
                                  (log/warn (str "Unhandled response type for lang=" lang ":" pending-type)))))
                            (log/warn (str "Received response for unknown id=" id ", lang=" lang)))))))))
              (catch js/Error e
                (log/error (str "Failed to parse LSP message for lang=" lang ":" (.-message e) "\n" (.-stack e)))))))))
    (catch js/Error error
      (log/error (str "Error in handle-message: " (.-message error) "\n" (.-stack error))))))

(defn connect
  "Establishes a WebSocket connection to the LSP server for a specific language.
   Sets up event handlers and initializes the LSP, resolving the promise with the WebSocket object."
  [lang config state-atom events]
  (go
    (try
      (let [url (:url config)
            socket (js/WebSocket. url)]
        (log/trace "Creating WebSocket connection for lang=" lang " with url=" url)
        (set! (.-binaryType socket) "arraybuffer")
        (set-resource! :lsp lang socket)
        (swap! state-atom update-in [:lsp lang] assoc
               :ws socket
               :initialized? false
               :connected? false
               :reachable? false
               :connecting? true
               :warned-unreachable? false
               :url url)
        (set! (.-onmessage socket) #(handle-message lang (.-data %) state-atom events))
        (set! (.-onclose socket) #(do
                                    (log/trace (str "LSP WS closed for lang=" lang))
                                    (when-let [rej-fn (get-in @state-atom [:lsp lang :promise-rej-fn])]
                                      (rej-fn (js/Error. (str "LSP WebSocket closed unexpectedly for language " lang))))
                                    (swap! state-atom update :lsp dissoc lang)
                                    (.next events (clj->js {:type "disconnect" :data {:lang lang}}))))
        (set! (.-onerror socket) #(do
                                    (log/warn (str "LSP connection error for lang=" lang "; marking unreachable: " (.-message %)))
                                    (swap! state-atom update-in [:lsp lang] assoc
                                           :connected? false
                                           :reachable? false
                                           :connecting? false)
                                    (.next events (clj->js {:type "lsp-error" :data {:message "WebSocket connection error" :lang lang}}))
                                    (when-let [rej-fn (get-in @state-atom [:lsp lang :promise-rej-fn])]
                                      (rej-fn (js/Error. (str "LSP connection error for lang=" lang ": " (.-message %)))))
                                    (swap! state-atom update-in [:lsp lang] dissoc :promise-rej-fn :promise-res-fn)))
        (set! (.-onopen socket) #(do
                                   (log/info (str "LSP WS open for lang=" lang))
                                   (swap! state-atom update-in [:lsp lang] assoc
                                          :connected? true
                                          :reachable? true
                                          :connecting? false
                                          :warned-unreachable? false)
                                   (.next events (clj->js {:type "connect" :data {:lang lang}}))
                                   (request-initialize lang state-atom)))
        (let [init-promise (js/Promise. (fn [res rej]
                                          (swap! state-atom assoc-in [:lsp lang :promise-res-fn] res)
                                          (swap! state-atom assoc-in [:lsp lang :promise-rej-fn] rej)))
              init-ch (promise->chan init-promise)
              [status val] (<! init-ch)]
          (if (= :ok status)
            (do
              (log/trace "LSP connection promise resolved for lang=" lang " with socket")
              [:ok socket])
            (do
              (log/error "LSP connection initialization failed for lang=" lang ": " (.-message val))
              [:error (js/Error. (str "LSP initialization failed for lang=" lang) #js {:cause val})]))))
      (catch js/Error e
        (log/error "Failed to create WebSocket for lang=" lang ": " (.-message e))
        [:error (js/Error. (str "WebSocket creation failed for lang=" lang) #js {:cause e})]))))
