(ns lib.lsp.client
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [lib.db :as db :refer [flatten-diags flatten-symbols]]
   [taoensso.timbre :as log]))

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
  "Sends the full message string over the WebSocket if connected and reachable."
  [lang full-msg state-atom]
  (let [lsp-state (get-in @state-atom [:lsp lang])
        connected? (:connected? lsp-state false)
        warned? (:warned-unreachable? lsp-state false)]
    (if connected?
      (when-let [ws (:ws lsp-state)]
        (log/trace "Sending raw message over WS for lang:" lang)
        (.send ws full-msg))
      (when-not warned?
        (log/warn "LSP server unreachable for lang" lang "; not sending message")
        (swap! state-atom assoc-in [:lsp lang :warned-unreachable?] true)))))

(defn send
  "Constructs and sends an LSP message with JSON and Content-Length header.
  Adds serial ID for requests if response-type present."
  [lang msg state-atom]
  (let [id (or (:id msg)
               (when (:response-type msg)
                 (let [next-id (get-in @state-atom [:lsp lang :next-id] 1)]
                   (swap! state-atom assoc-in [:lsp lang :next-id] (inc next-id))
                   next-id)))
        msg (cond-> (assoc msg :jsonrpc "2.0") id (assoc :id id))
        json-str (js/JSON.stringify (clj->js msg))
        header (str "Content-Length: " (.-length json-str) "\r\n\r\n")
        full (str header json-str)]
    (log/info "Sending LSP message for lang" lang ":" (:method msg) "id" id)
    (log/trace "Full LSP send message:\n" full)
    (when id
      (swap! state-atom assoc-in [:lsp lang :pending id]
             (if (:uri msg)
               {:type (:response-type msg)
                :uri (:uri msg)}
               (:response-type msg))))
    (send-raw lang full state-atom)))

;; Sending functions (client -> server)

(defn request-initialize [lang state-atom]
  (send lang {:method "initialize"
              :params {:capabilities {}}
              :response-type :initialize} state-atom))

(defn notify-initialized [lang state-atom]
  (send lang {:method "initialized" :params {}} state-atom))

(defn notify-did-open [lang uri text version state-atom]
  (send lang {:method "textDocument/didOpen"
              :params {:textDocument {:uri uri
                                      :languageId lang
                                      :version version
                                      :text text}}} state-atom))

(defn notify-did-change [lang uri text version state-atom]
  (send lang {:method "textDocument/didChange"
              :params {:textDocument {:uri uri :version version}
                       :contentChanges [{:text text}]}} state-atom))

(defn notify-did-close [lang uri state-atom]
  (send lang {:method "textDocument/didClose"
              :params {:textDocument {:uri uri}}} state-atom))

(defn notify-did-save [lang uri text state-atom]
  (send lang {:method "textDocument/didSave"
              :params {:textDocument {:uri uri}
                       :text text}} state-atom))

(defn notify-did-rename-files
  ([lang files state-atom]
   (send lang {:method "workspace/didRenameFiles"
               :params {:files files}} state-atom))
  ([lang old-uri new-uri state-atom]
   (let [files [{:oldUri old-uri :newUri new-uri}]]
     (notify-did-rename-files lang files state-atom))))

(defn request-document-symbol [lang uri state-atom]
  (send lang {:method "textDocument/documentSymbol"
              :params {:textDocument {:uri uri}}
              :response-type :document-symbol
              :uri uri} state-atom))

(defn request-shutdown [lang state-atom]
  (send lang {:method "shutdown"
              :response-type :shutdown} state-atom))

(defn notify-exit [lang state-atom]
  (send lang {:method "exit"} state-atom))

;; Response handlers (server -> client responses)

(defn handle-initialize-response [lang _result state-atom events]
  (log/info "LSP initialized for lang" lang)
  (notify-initialized lang state-atom)
  (swap! state-atom assoc-in [:lsp lang :initialized?] true)
  (when-let [res-fn (get-in @state-atom [:lsp lang :promise-res-fn])]
    (res-fn)
    (swap! state-atom update-in [:lsp lang] dissoc :promise-res-fn :promise-rej-fn))
  (.next events (clj->js {:type "lsp-initialized" :data {:lang lang}})))

(defn handle-document-symbol-response [lang uri result _state-atom events]
  (let [hier-symbols result
        flat-symbols (flatten-symbols hier-symbols nil uri)]
    (db/replace-symbols! uri flat-symbols)
    (.next events (clj->js {:type "symbols" :data flat-symbols :lang lang}))))

(defn handle-shutdown-response [lang _result state-atom _events]
  (log/info "Received shutdown response for lang" lang)
  (notify-exit lang state-atom)
  (when-let [ws (get-in @state-atom [:lsp lang :ws])]
    (.close ws)))

;; Notification handlers (server -> client notifications)

(defn handle-log-message [lang params _state-atom events]
  (let [log-entry (assoc params :lang lang)]
    (db/create-logs! [log-entry])
    (.next events (clj->js {:type "log" :data log-entry :lang lang}))))

(defn handle-publish-diagnostics [lang params _state-atom events]
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

(defn- get-text [data]
  (if (instance? js/ArrayBuffer data)
    (.decode (js/TextDecoder. "utf-8") data)
    data))

(defn handle-message
  "Handles incoming LSP messages, parsing header and body, then dispatching to response or notification handlers."
  [lang msg state-atom events]
  (try
    (let [text (get-text msg)
          header-end (.indexOf text "\r\n\r\n")]
      (log/trace "Received raw LSP message for lang:" lang "length:" (.-length text))
      (if (= -1 header-end)
        (log/error "No header found in LSP message for lang" lang ":" text)
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
                (log/info "Received LSP message for lang" lang ":" (:method parsed) "id" (:id parsed))
                (log/trace "Full LSP received message:\n" parsed)
                (.next events (clj->js {:type "lsp-message" :data parsed-with-lang}))
                (let [type (cond
                             (and (:method parsed) (:id parsed)) :request
                             (:method parsed) :notification
                             (:id parsed) :response
                             :else :invalid)]
                  (if (= type :invalid)
                    (log/error "Invalid LSP message: no method or id" parsed)
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
                              (log/error "No handler for server request" method "lang" lang)
                              (send lang {:jsonrpc "2.0"
                                          :id (:id parsed)
                                          :error {:code -32601 :message "Method not found"}} state-atom))))

                        :notification
                        (let [method (:method parsed)
                              handler (get server-notification-handlers method)]
                          (if handler
                            (handler lang (:params parsed) state-atom events)
                            (if (str/starts-with? method "$/")
                              (log/warn "Optional server notification handler missing for" method "lang" lang)
                              (log/error "Required server notification handler missing for" method "lang" lang))))

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
                                  (log/warn "Unhandled response type for lang" lang ":" pending-type))))
                            (log/warn "Received response for unknown id" id "lang" lang))))))))
              (catch js/Error e
                (log/error "Failed to parse LSP message for lang" lang ":" (.-message e) "stack:" (.-stack e))))))))
    (catch js/Error error
      (log/error "Error in handle-message:" (.-message error) "stack:" (.-stack error)))))

(defn connect
  "Establishes a WebSocket connection to the LSP server for a specific language.
  Sets up event handlers and initializes the LSP for that lang.
  If unreachable, subsequent sends will be blocked and warned."
  [lang config state-atom events]
  (let [url (:url config)
        lsp-state (get-in @state-atom [:lsp lang])]
    (cond
      (:connected? lsp-state) (log/debug "Already connected to LSP server for lang" lang)
      (:connecting? lsp-state) (log/debug "Already connecting to LSP server for lang" lang)
      :else (do
              (if-let [existing (:ws lsp-state)]
                (.close existing)
                (log/debug "No existing WS to close for lang" lang))
              (let [socket (js/WebSocket. url)]
                (set! (.-binaryType socket) "arraybuffer")
                (swap! state-atom update-in [:lsp lang] assoc
                       :ws socket
                       :initialized? false
                       :connected? false
                       :reachable? false
                       :connecting? true
                       :warned-unreachable? false
                       :url url)
                (set! (.-onopen socket) #(do (log/info "LSP WS open for lang" lang)
                                             (swap! state-atom update-in [:lsp lang] assoc
                                                    :connected? true
                                                    :reachable? true
                                                    :connecting? false
                                                    :warned-unreachable? false)
                                             (.next events (clj->js {:type "connect" :data {:lang lang}}))
                                             (request-initialize lang state-atom)))
                (set! (.-onmessage socket) #(handle-message lang (.-data %) state-atom events))
                (set! (.-onclose socket) #(do (log/warn "LSP WS closed for lang" lang "; marking unreachable")
                                              (swap! state-atom update-in [:lsp lang] assoc
                                                     :connected? false
                                                     :reachable? false
                                                     :connecting? false)
                                              (when-let [rej-fn (get-in @state-atom [:lsp lang :promise-rej-fn])]
                                                (rej-fn (js/Error. (str "LSP WebSocket closed for language " lang))))
                                              (swap! state-atom update-in [:lsp lang] dissoc :promise-rej-fn :promise-res-fn)
                                              (.next events (clj->js {:type "disconnect" :data {:lang lang}}))))
                (set! (.-onerror socket) #(do (log/warn "LSP connection error for lang" lang "; marking unreachable:" %)
                                              (swap! state-atom update-in [:lsp lang] assoc
                                                     :connected? false
                                                     :reachable? false
                                                     :connecting? false)
                                              (js/window.console.debug %)
                                              (.next events (clj->js {:type "lsp-error" :data {:message "WebSocket connection error" :lang lang}}))
                                              (when-let [rej-fn (get-in @state-atom [:lsp lang :promise-rej-fn])]
                                                (rej-fn (js/Error. (str "LSP connection error for language " lang))))
                                              (swap! state-atom update-in [:lsp lang] dissoc :promise-rej-fn :promise-res-fn))))))))
