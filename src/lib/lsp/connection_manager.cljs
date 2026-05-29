(ns lib.lsp.connection-manager
  "LSP connection manager with timeout handling and request lifecycle management.

   This module provides:
   - Request timeout handling (configurable, default 60s)
   - Periodic cleanup of stale pending requests
   - Connection state machine
   - Graceful reconnection handling"
  (:require [clojure.core.async :refer [go put! <! alts! timeout promise-chan]]
            [domain.protocols :as p]
            [lib.lsp.client :as lsp]
            [taoensso.timbre :as log]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def DEFAULT-CONFIG
  "Default configuration for LSP connection management."
  {:request-timeout-ms 60000       ; 60 seconds for request timeout
   :init-timeout-ms 30000          ; 30 seconds for initialization
   :cleanup-interval-ms 30000      ; Check for stale requests every 30 seconds
   :max-reconnect-attempts 3       ; Maximum reconnection attempts
   :reconnect-delay-ms 1000        ; Initial delay between reconnection attempts
   :reconnect-backoff-factor 2})   ; Exponential backoff multiplier

;; =============================================================================
;; Connection State Machine
;; =============================================================================

(def STATES
  "Valid connection states."
  #{:disconnected
    :connecting
    :connected
    :initializing
    :initialized
    :disconnecting
    :error})

(def TRANSITIONS
  "Valid state transitions."
  {:disconnected #{:connecting}
   :connecting #{:connected :error :disconnected}
   :connected #{:initializing :disconnecting :error}
   :initializing #{:initialized :error :disconnected}
   :initialized #{:disconnecting :error :disconnected}
   :disconnecting #{:disconnected}
   :error #{:disconnected :connecting}})

(defn valid-transition?
  "Returns true if the transition from current to next state is valid."
  [current next]
  (contains? (get TRANSITIONS current #{}) next))

;; =============================================================================
;; Pending Request Management
;; =============================================================================

(defrecord PendingRequest [id type uri timestamp timeout-ms])

(defn make-pending-request
  "Creates a pending request record."
  [id type uri timeout-ms]
  (->PendingRequest id type uri (js/Date.now) timeout-ms))

(defn request-expired?
  "Returns true if the pending request has exceeded its timeout."
  [pending-request]
  (let [now (js/Date.now)
        elapsed (- now (:timestamp pending-request))]
    (> elapsed (:timeout-ms pending-request))))

(defn cleanup-stale-requests!
  "Removes expired pending requests and optionally invokes rejection callbacks."
  [state-atom lang on-timeout]
  (let [pending (get-in @state-atom [:lsp lang :pending] {})
        now (js/Date.now)
        timeout-ms (get-in @state-atom [:lsp lang :request-timeout-ms]
                          (:request-timeout-ms DEFAULT-CONFIG))]
    (doseq [[id request-info] pending]
      (let [timestamp (if (map? request-info)
                        (get request-info :timestamp now)
                        now)
            elapsed (- now timestamp)]
        (when (> elapsed timeout-ms)
          (log/warn "Request timed out for lang" lang "id" id "after" elapsed "ms")
          (swap! state-atom update-in [:lsp lang :pending] dissoc id)
          (when on-timeout
            (on-timeout lang id request-info)))))))

;; =============================================================================
;; Connection Manager Record
;; =============================================================================

(defrecord ConnectionManager [state-atom events config]
  p/ILspClient

  ;; === Connection Management ===

  (connect! [this language config]
    (let [result-ch (promise-chan)
          init-timeout (or (:init-timeout-ms config)
                          (:init-timeout-ms (:config this))
                          (:init-timeout-ms DEFAULT-CONFIG))]
      (go
        ;; Check current state
        (let [current-state (get-in @state-atom [:lsp language :state] :disconnected)]
          (if-not (valid-transition? current-state :connecting)
            (do
              (log/warn "Cannot connect from state" current-state)
              (put! result-ch [:error (ex-info "Invalid state transition"
                                               {:current current-state
                                                :attempted :connecting})]))
            (do
              ;; Update state to connecting
              (swap! state-atom assoc-in [:lsp language :state] :connecting)

              ;; Attempt connection with timeout
              (let [connect-ch (lsp/connect language config state-atom events)
                    timeout-ch (timeout init-timeout)
                    [result port] (alts! [connect-ch timeout-ch])]
                (cond
                  ;; Timeout
                  (= port timeout-ch)
                  (do
                    (log/error "LSP connection timed out for" language "after" init-timeout "ms")
                    (swap! state-atom assoc-in [:lsp language :state] :error)
                    (put! result-ch [:error (ex-info "Connection timeout"
                                                     {:language language
                                                      :timeout init-timeout})]))

                  ;; Success
                  (and (vector? result) (= :ok (first result)))
                  (do
                    (swap! state-atom assoc-in [:lsp language :state] :initialized)
                    (log/info "LSP connection established for" language)
                    (put! result-ch result))

                  ;; Error
                  :else
                  (do
                    (swap! state-atom assoc-in [:lsp language :state] :error)
                    (log/error "LSP connection failed for" language)
                    (put! result-ch result))))))))
      result-ch))

  (disconnect! [_this language]
    (let [current-state (get-in @state-atom [:lsp language :state] :disconnected)]
      (when (valid-transition? current-state :disconnecting)
        (swap! state-atom assoc-in [:lsp language :state] :disconnecting)
        (lsp/request-shutdown language state-atom)
        (swap! state-atom assoc-in [:lsp language :state] :disconnected))))

  (connected? [_this language]
    (contains? #{:connected :initializing :initialized}
               (get-in @state-atom [:lsp language :state] :disconnected)))

  (initialized? [_this language]
    (= :initialized (get-in @state-atom [:lsp language :state])))

  (connect-supplier [_this language url]
    ;; Returns the exact resource supplier lib.core feeds to lib.state/load-resource,
    ;; preserving the resource-managed connect flow (no state-machine adoption).
    #(lsp/connect language {:url url} state-atom events))

  ;; === Document Lifecycle Notifications ===

  (notify-did-open! [_this language uri text version]
    (lsp/notify-did-open language uri text version state-atom))

  (notify-did-change! [_this language uri text version]
    (lsp/notify-did-change language uri text version state-atom))

  (notify-did-change-incremental! [_this language uri changes version]
    (lsp/notify-did-change-incremental language uri changes version state-atom))

  (notify-did-close! [_this language uri]
    (lsp/notify-did-close language uri state-atom))

  (notify-did-save! [_this language uri text]
    (lsp/notify-did-save language uri text state-atom))

  (notify-did-rename! [_this language old-uri new-uri]
    (lsp/notify-did-rename-files language old-uri new-uri state-atom))

  ;; === Request Operations ===

  (request-symbols! [_this language uri]
    ;; Narrowed (Phase 2): thin pass-through matching lib.core's live (unguarded) behavior.
    ;; State-machine disconnect remains available via disconnect!.
    (lsp/request-document-symbol language uri state-atom))

  (request-shutdown! [_this language]
    (lsp/request-shutdown language state-atom))

  (shutdown-all! [_this]
    (lsp/request-shutdown state-atom)))

;; =============================================================================
;; Cleanup Manager
;; =============================================================================

(defonce ^:private cleanup-intervals (atom {}))

(defn start-cleanup-task!
  "Starts periodic cleanup of stale pending requests for a language."
  [state-atom lang interval-ms on-timeout]
  (when-not (get @cleanup-intervals lang)
    (let [interval-id (js/setInterval
                       #(cleanup-stale-requests! state-atom lang on-timeout)
                       interval-ms)]
      (swap! cleanup-intervals assoc lang interval-id)
      (log/trace "Started cleanup task for lang" lang "with interval" interval-ms "ms"))))

(defn stop-cleanup-task!
  "Stops the periodic cleanup task for a language."
  [lang]
  (when-let [interval-id (get @cleanup-intervals lang)]
    (js/clearInterval interval-id)
    (swap! cleanup-intervals dissoc lang)
    (log/trace "Stopped cleanup task for lang" lang)))

(defn stop-all-cleanup-tasks!
  "Stops all periodic cleanup tasks."
  []
  (doseq [[lang interval-id] @cleanup-intervals]
    (js/clearInterval interval-id)
    (log/trace "Stopped cleanup task for lang" lang))
  (reset! cleanup-intervals {}))

;; =============================================================================
;; Factory Functions
;; =============================================================================

(defn make-connection-manager
  "Creates an LSP connection manager with timeout handling.

   Parameters:
   - state-atom: Atom holding editor state including LSP state
   - events: RxJS Subject for event emission
   - config: Optional configuration map overriding DEFAULT-CONFIG"
  ([state-atom events]
   (make-connection-manager state-atom events {}))
  ([state-atom events config]
   (let [merged-config (merge DEFAULT-CONFIG config)]
     (->ConnectionManager state-atom events merged-config))))

(defn connected?
  "Returns true if the connection manager is connected for the given language.
   Standalone wrapper for protocol method."
  [manager language]
  (p/connected? manager language))

(defn initialized?
  "Returns true if the connection manager is initialized for the given language.
   Standalone wrapper for protocol method."
  [manager language]
  (p/initialized? manager language))

;; =============================================================================
;; Request Timeout Wrapper
;; =============================================================================

(defn with-timeout
  "Wraps an async operation with a timeout.

   Returns a channel with the result or [:error :timeout] if timeout expires."
  [operation-ch timeout-ms description]
  (let [result-ch (promise-chan)]
    (go
      (let [timeout-ch (timeout timeout-ms)
            [result port] (alts! [operation-ch timeout-ch])]
        (if (= port timeout-ch)
          (do
            (log/warn description "timed out after" timeout-ms "ms")
            (put! result-ch [:error :timeout]))
          (put! result-ch result))))
    result-ch))

;; =============================================================================
;; Reconnection Logic
;; =============================================================================

(defn reconnect-with-backoff!
  "Attempts to reconnect with exponential backoff.

   Returns a channel with [:ok socket] or [:error reason] after all attempts."
  [connection-manager language config]
  (let [result-ch (promise-chan)
        max-attempts (:max-reconnect-attempts DEFAULT-CONFIG)
        base-delay (:reconnect-delay-ms DEFAULT-CONFIG)
        backoff-factor (:reconnect-backoff-factor DEFAULT-CONFIG)]
    (go
      (loop [attempt 1
             delay base-delay]
        (log/info "Reconnection attempt" attempt "of" max-attempts "for" language)
        (let [[status val] (<! (p/connect! connection-manager language config))]
          (if (= status :ok)
            (put! result-ch [:ok val])
            (if (>= attempt max-attempts)
              (do
                (log/error "All reconnection attempts failed for" language)
                (put! result-ch [:error (ex-info "Max reconnection attempts exceeded"
                                                 {:language language
                                                  :attempts attempt})]))
              (do
                (log/warn "Reconnection attempt" attempt "failed, retrying in" delay "ms")
                (<! (timeout delay))
                (recur (inc attempt) (* delay backoff-factor))))))))
    result-ch))
