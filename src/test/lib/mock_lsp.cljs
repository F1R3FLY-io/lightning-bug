(ns test.lib.mock-lsp
  (:require [clojure.core.async :refer [go <!]]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

(defn parse-headers [headers-str]
  (->> (str/split-lines headers-str)
       (map str/trim)
       (filter (complement str/blank?))
       (map #(str/split % #":" 2))
       (filter #(= 2 (count %)))
       (map (fn [[name value]]
              [(str/upper-case (str/trim name))
               (str/trim value)]))
       (into {})))

(defn parse-body [body-str]
  (as-> body-str body
    (.parse js/JSON body)
    (js->clj body :keywordize-keys true)))

(defn parse-message [message]
  (let [[headers-str body-str] (str/split message #"\r\n\r\n" 2)
        headers (parse-headers headers-str)
        body (parse-body body-str)]
    [headers body]))

(defn create-mock-socket [handle-fn]
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
                                   (log/trace "Mock: triggering onerror")
                                   ((.-onerror sock) err))
                                 (log/warn "Mock: onerror not set")))}]
    (set! (.-binaryType sock) "arraybuffer")
    (set! (.-send sock) (fn [message]
                          (swap! sent conj message)
                          (let [[headers body] (parse-message message)]
                            (log/trace "Handling message with headers:" headers "body:" body)
                            (handle-fn mock headers body))))
    (set! (.-close sock) (fn [] (when (.-onclose sock) ((.-onclose sock)))))
    mock))

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

(defn default-handler-fn
  [mock _headers body]
  (condp = (:method body)
    "initialize" (respond! mock (:id body) {:capabilities {}})
    "initialized" nil
    "textDocument/didOpen" nil
    "textDocument/didChange" nil
    "workspace/didRenameFiles" nil
    "textDocument/didClose" nil
    "textDocument/didSave" nil
    "shutdown" (respond! mock (:id body) {})
    "exit" nil))

(defn with-mock-lsp
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
           (set! js/WebSocket original-WebSocket))))))
  ([body-fn]
   (with-mock-lsp default-handler-fn body-fn)))
