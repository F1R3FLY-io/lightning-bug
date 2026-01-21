(ns test.lib.mock-lsp
  (:require [clojure.core.async :refer [go <!]]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

(defn parse-headers
  "Parses HTTP headers from a string into a map."
  [headers-str]
  (->> (str/split-lines headers-str)
       (map str/trim)
       (filter (complement str/blank?))
       (map #(str/split % #":" 2))
       (filter #(= 2 (count %)))
       (map (fn [[name value]]
              [(str/upper-case (str/trim name))
               (str/trim value)]))
       (into {})))

(defn parse-body
  "Parses a JSON body string into a Clojure map."
  [body-str]
  (as-> body-str body
    (.parse js/JSON body)
    (js->clj body :keywordize-keys true)))

(defn parse-message
  "Parses an LSP message into headers and body."
  [message]
  (let [header-end (.indexOf message "\r\n\r\n")]
    (if (= -1 header-end)
      (do
        (log/error "No header found in message:" message)
        [{} {}])
      (let [headers-str (.substring message 0 header-end)
            match (.match headers-str #"Content-Length: (\d+)")
            length (when match (js/parseInt (aget match 1)))
            body-start (+ header-end 4)
            body (.substring message body-start (+ body-start length))
            headers (parse-headers headers-str)]
        (try
          [headers (parse-body body)]
          (catch :default e
            (log/error "Failed to parse body:" e "body:" body)
            [headers {}]))))))

(defn create-mock-socket
  "Creates a mock WebSocket object with controlled event triggers.
   The `handle-fn` processes sent messages."
  [handle-fn]
  (let [sock (js/Object.)
        sent (atom [])
        mock {:sock sock
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
                                   (log/trace "Mock: triggering onerror with error:" err)
                                   ((.-onerror sock) err))
                                 (log/warn "Mock: onerror not set")))}]
    (set! (.-binaryType sock) "arraybuffer")
    (set! (.-readyState sock) js/WebSocket.CONNECTING) ; Mimic real WebSocket behavior
    (set! (.-send sock) (fn [message]
                          (swap! sent conj message)
                          (let [[headers body] (parse-message message)]
                            (log/trace "Handling message with headers:" headers "body:" body)
                            (handle-fn mock headers body))))
    (set! (.-close sock) (fn []
                           (when (.-onclose sock)
                             (log/trace "Mock: closing socket")
                             (set! (.-readyState sock) js/WebSocket.CLOSED)
                             ((.-onclose sock)))))
    mock))

(defn respond!
  "Sends a response message through the mock socket."
  [mock request-id result-body]
  (let [message {:jsonrpc "2.0" :id request-id :result result-body}
        json (js/JSON.stringify (clj->js message))
        header (str "Content-Length: " (.-length json) "\r\n\r\n")
        full (str header json)]
    ((:trigger-message mock) full)))

(defn error!
  "Sends an error message through the mock socket."
  [mock error-body]
  (let [message {:jsonrpc "2.0" :id nil :error error-body}
        json (js/JSON.stringify (clj->js message))
        header (str "Content-Length: " (.-length json) "\r\n\r\n")
        full (str header json)]
    ((:trigger-message mock) full)))

(defn default-handler-fn
  "Default handler for mock LSP messages."
  [mock _headers body]
  (condp = (:method body)
    "initialize" (respond! mock (:id body) {:capabilities {}})
    "initialized" nil
    "textDocument/didOpen" nil
    "textDocument/didChange" nil
    "workspace/didRenameFiles" nil
    "textDocument/didClose" nil
    "textDocument/didSave" nil
    "textDocument/documentSymbol" (respond! mock (:id body) [])
    "shutdown" (respond! mock (:id body) {})
    "exit" nil))

;; =============================================================================
;; Enhanced Mock LSP Utilities
;; =============================================================================

(defn mock-lsp-with-delays
  "Creates a handler function that adds configurable delays to responses.
   delay-map is {method-name delay-ms}."
  [delay-map]
  (fn [mock _headers body]
    (let [method (:method body)
          delay-ms (get delay-map method 0)]
      (if (pos? delay-ms)
        (js/setTimeout
         #(condp = method
            "initialize" (respond! mock (:id body) {:capabilities {}})
            "shutdown" (respond! mock (:id body) {})
            "textDocument/documentSymbol" (respond! mock (:id body) [])
            nil)
         delay-ms)
        (default-handler-fn mock _headers body)))))

(defn mock-lsp-with-errors
  "Creates a handler function that returns specific error codes for methods.
   error-map is {method-name {:code error-code :message error-msg}}."
  [error-map]
  (fn [mock _headers body]
    (let [method (:method body)]
      (if-let [err (get error-map method)]
        (error! mock (merge {:id (:id body)} err))
        (default-handler-fn mock _headers body)))))

(defn mock-diagnostic-notification
  "Creates a publishDiagnostics notification message."
  [uri diagnostics & {:keys [version]}]
  (let [params (cond-> {:uri uri
                        :diagnostics diagnostics}
                 version (assoc :version version))
        message {:jsonrpc "2.0"
                 :method "textDocument/publishDiagnostics"
                 :params params}
        json (js/JSON.stringify (clj->js message))
        header (str "Content-Length: " (.-length json) "\r\n\r\n")]
    (str header json)))

(defn mock-log-notification
  "Creates a window/logMessage notification."
  [message & {:keys [type] :or {type 4}}]  ; 4 = Log
  (let [params {:type type :message message}
        msg {:jsonrpc "2.0"
             :method "window/logMessage"
             :params params}
        json (js/JSON.stringify (clj->js msg))
        header (str "Content-Length: " (.-length json) "\r\n\r\n")]
    (str header json)))

(defn mock-symbol-response
  "Creates a documentSymbol response message."
  [request-id symbols]
  (let [message {:jsonrpc "2.0"
                 :id request-id
                 :result symbols}
        json (js/JSON.stringify (clj->js message))
        header (str "Content-Length: " (.-length json) "\r\n\r\n")]
    (str header json)))

(defn trigger-diagnostic!
  "Triggers a publishDiagnostics notification through the mock socket."
  [mock uri diagnostics & opts]
  (let [msg (apply mock-diagnostic-notification uri diagnostics opts)]
    ((:trigger-message mock) msg)))

(defn trigger-log!
  "Triggers a window/logMessage notification through the mock socket."
  [mock message & opts]
  (let [msg (apply mock-log-notification message opts)]
    ((:trigger-message mock) msg)))

(defn trigger-symbols!
  "Triggers a documentSymbol response through the mock socket."
  [mock request-id symbols]
  (let [msg (mock-symbol-response request-id symbols)]
    ((:trigger-message mock) msg)))

;; =============================================================================
;; Message Assertion Helpers
;; =============================================================================

(defn get-sent-messages
  "Returns all messages sent through the mock socket, parsed."
  [mock]
  (map (fn [raw]
         (let [[headers body] (parse-message raw)]
           {:headers headers :body body}))
       @(:sent mock)))

(defn get-sent-methods
  "Returns the methods of all messages sent through the mock socket."
  [mock]
  (map #(get-in % [:body :method]) (get-sent-messages mock)))

(defn message-sent?
  "Returns true if a message with the given method was sent."
  [mock method]
  (some #(= method %) (get-sent-methods mock)))

(defn get-sent-message
  "Returns the first sent message with the given method, or nil."
  [mock method]
  (first (filter #(= method (get-in % [:body :method]))
                 (get-sent-messages mock))))

(defn get-last-sent-message
  "Returns the last message sent through the mock socket."
  [mock]
  (last (get-sent-messages mock)))

(defn clear-sent-messages!
  "Clears all recorded sent messages."
  [mock]
  (reset! (:sent mock) []))

(defn assert-message-sent
  "Asserts that a message with the given method was sent."
  [mock method]
  (when-not (message-sent? mock method)
    (throw (js/Error. (str "Expected message '" method "' to be sent, but it wasn't. "
                           "Sent methods: " (pr-str (get-sent-methods mock)))))))

(defn assert-messages-sent-in-order
  "Asserts that messages were sent in the specified order."
  [mock expected-methods]
  (let [actual-methods (get-sent-methods mock)]
    (when-not (= expected-methods (take (count expected-methods) actual-methods))
      (throw (js/Error. (str "Expected messages in order: " (pr-str expected-methods)
                             ", but got: " (pr-str actual-methods)))))))

(defn with-mock-lsp
  "Temporarily overrides js/WebSocket with a mock socket for testing."
  ([body-fn]
   (with-mock-lsp default-handler-fn body-fn))
  ([handle-fn body-fn]
   (let [mock (create-mock-socket handle-fn)
         sock (:sock mock)
         original-WebSocket js/WebSocket]
     (log/trace "Mock: overriding js/WebSocket")
     (set! js/WebSocket (fn [_url]
                          (log/trace "Mock: creating mock socket (ignoring URL)")
                          sock))
     (go
       (try
         (let [body-ch (body-fn mock)
               res (<! body-ch)]
           (if (and (vector? res)
                    (= (count res) 2)
                    (= :error (first res)))
             [:error (js/Error. "(with-mock-lsp body-fn handle-fn) failed" #js {:cause (second res)})]
             [:ok res]))
         (catch :default e
           [:error (js/Error. "(with-mock-lsp body-fn handle-fn) failed" #js {:cause e})])
         (finally
           ((:trigger-close mock))
           (log/trace "Mock: restoring original js/WebSocket")
           (set! js/WebSocket original-WebSocket)))))))

;; =============================================================================
;; Global Mock State
;; =============================================================================

(defonce ^:private mock-state (atom {:messages []
                                      :handlers {}
                                      :response-handlers {}
                                      :responses {}}))

(defn reset-mock!
  "Resets all global mock state."
  []
  (reset! mock-state {:messages []
                      :handlers {}
                      :response-handlers {}
                      :responses {}}))

;; =============================================================================
;; High-Level Mock LSP API
;; =============================================================================

(defn create-mock-lsp
  "Creates a mock LSP client with send/receive capabilities.
   Returns {:send! fn, :receive! fn, :mock state-atom}.

   - send! simulates sending a message and triggers on-message! handlers
   - receive! simulates receiving a response and triggers on-response! handlers"
  []
  (let [state (atom {:sent []
                     :received []
                     :handlers {}
                     :response-handlers {}
                     :responses {}})]
    {:send! (fn [message]
              (swap! state update :sent conj message)
              ;; Trigger on-message! handlers with the sent message
              (doseq [[_ handler] (:handlers @state)]
                (handler message))
              ;; Check for configured responses and auto-trigger if set
              (when-let [method (:method message)]
                (when-let [response (get-in @state [:responses method])]
                  (swap! state update :received conj response))))
     :receive! (fn [message]
                 (swap! state update :received conj message)
                 ;; Trigger on-response! handlers by message ID
                 (when-let [id (:id message)]
                   (when-let [handler (get-in @state [:response-handlers id])]
                     (handler message)
                     (swap! state update :response-handlers dissoc id))))
     :mock state}))

(defn on-message!
  "Registers a handler to be called when messages are received.
   mock-state should be the atom returned as :mock from create-mock-lsp."
  [mock-state handler-fn]
  (let [id (random-uuid)]
    (swap! mock-state assoc-in [:handlers id] handler-fn)
    id))

(defn on-response!
  "Registers a one-time handler for a specific request ID.
   mock-state should be the atom returned as :mock from create-mock-lsp."
  [mock-state request-id handler-fn]
  (swap! mock-state assoc-in [:response-handlers request-id] handler-fn))

(defn set-response!
  "Configures a mock response for a specific method.
   mock-state should be the atom returned as :mock from create-mock-lsp."
  [mock-state method response]
  (swap! mock-state assoc-in [:responses method] response))
