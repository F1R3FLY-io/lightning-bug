(ns lib.debounce
  "Centralized debounce coordination for consistent behavior across the application.

   This module provides:
   - Event-specific debounce delays
   - Key-based deduplication
   - Cleanup on unmount
   - Leading/trailing edge options
   - Throttling support"
  (:require [domain.protocols :as p]
            [taoensso.timbre :as log]))

;; =============================================================================
;; Default Delay Configuration
;; =============================================================================

(def DEFAULT-DELAYS
  "Default debounce delays for common operations (in milliseconds)."
  {:content-change 150       ; Text changes before LSP notification
   :lsp-did-change 250       ; LSP didChange notification
   :diagnostics-update 100   ; Diagnostic display update
   :symbol-request 300       ; Document symbol requests
   :search 200               ; Search input
   :save 500                 ; Auto-save
   :cursor-update 50         ; Cursor position updates
   :selection-update 50      ; Selection updates
   :event-emit 16            ; Event emission (one frame at 60fps)
   :resize 100})             ; Window/panel resize

(defn get-delay
  "Gets the default delay for an operation type, or the provided value."
  [operation-type-or-ms]
  (if (keyword? operation-type-or-ms)
    (get DEFAULT-DELAYS operation-type-or-ms 100)
    operation-type-or-ms))

;; =============================================================================
;; Debounce State
;; =============================================================================

;; Atom holding active timer IDs keyed by debounce key.
(defonce ^:private timers (atom {}))

;; Atom holding last call timestamps for throttling.
(defonce ^:private last-calls (atom {}))

;; =============================================================================
;; Core Debounce Functions
;; =============================================================================

(defn cancel
  "Cancels any pending debounced call for the given key."
  [key]
  (when-let [timer-id (get @timers key)]
    (js/clearTimeout timer-id)
    (swap! timers dissoc key)
    (log/trace "Cancelled debounced call:" key)))

(defn cancel-all
  "Cancels all pending debounced calls."
  []
  (doseq [[key timer-id] @timers]
    (js/clearTimeout timer-id)
    (log/trace "Cancelled debounced call:" key))
  (reset! timers {})
  (reset! last-calls {}))

(defn cancel-matching
  "Cancels all pending debounced calls whose keys match the predicate."
  [pred]
  (doseq [[key timer-id] @timers
          :when (pred key)]
    (js/clearTimeout timer-id)
    (swap! timers dissoc key)
    (log/trace "Cancelled debounced call:" key)))

(defn debounced-call
  "Schedules a debounced call identified by key.

   If called again with the same key before delay expires, the previous
   call is cancelled and a new one is scheduled.

   Options:
   - :leading? - If true, executes immediately on first call (default false)
   - :trailing? - If true, executes after delay (default true)
   - :max-wait - Maximum time to wait before forcing execution

   Returns a function that cancels the pending call."
  ([key f delay-ms]
   (debounced-call key f delay-ms {}))
  ([key f delay-ms {:keys [leading? trailing? max-wait]
                    :or {leading? false trailing? true}}]
   (let [delay (get-delay delay-ms)
         now (js/Date.now)
         last-call (get @last-calls key)
         first-call? (nil? last-call)
         max-wait-exceeded? (and max-wait last-call (> (- now last-call) max-wait))]

     ;; Cancel existing timer
     (cancel key)

     ;; Update last call timestamp
     (when first-call?
       (swap! last-calls assoc key now))

     ;; Execute immediately if leading edge or max-wait exceeded
     (when (or (and leading? first-call?)
               max-wait-exceeded?)
       (try
         (f)
       (catch js/Error e
         (log/error "Error in debounced call (leading):" key "-" (.-message e))))
       (swap! last-calls assoc key (js/Date.now)))

     ;; Schedule trailing edge execution
     (when trailing?
       (let [timer-id (js/setTimeout
                       (fn []
                         (swap! timers dissoc key)
                         (swap! last-calls dissoc key)
                         (try
                           (f)
                         (catch js/Error e
                           (log/error "Error in debounced call (trailing):" key "-" (.-message e)))))
                       delay)]
         (swap! timers assoc key timer-id)))

     ;; Return cancel function
     (fn [] (cancel key)))))

(defn throttled-call
  "Executes f at most once per interval.

   Unlike debounce, throttle guarantees regular execution during
   continuous calls."
  [key f interval-ms]
  (let [interval (get-delay interval-ms)
        now (js/Date.now)
        last-call (get @last-calls key 0)
        time-since (- now last-call)]
    (if (>= time-since interval)
      ;; Enough time has passed, execute immediately
      (do
        (swap! last-calls assoc key now)
        (try
          (f)
        (catch js/Error e
          (log/error "Error in throttled call:" key "-" (.-message e)))))
      ;; Schedule trailing execution if one is not already pending
      (when-not (get @timers key)
        (let [remaining (- interval time-since)
              timer-id (js/setTimeout
                        (fn []
                          (swap! timers dissoc key)
                          (swap! last-calls assoc key (js/Date.now))
                          (try
                            (f)
                          (catch js/Error e
                            (log/error "Error in throttled call:" key "-" (.-message e)))))
                        remaining)]
          (swap! timers assoc key timer-id))))
    ;; Return cancel function
    (fn [] (cancel key))))

;; =============================================================================
;; Debounce Function Factory
;; =============================================================================

(defn debounce-fn
  "Returns a debounced version of function f.

   Each call to the returned function uses the provided key-fn to
   determine the debounce key. If key-fn is nil, a single key is used
   for all calls.

   Options:
   - :key-fn - Function to derive debounce key from arguments (receives args as a seq)
   - :delay - Delay in ms or keyword from DEFAULT-DELAYS
   - :leading? - Execute on leading edge
   - :trailing? - Execute on trailing edge (default true)
   - :max-wait - Maximum wait before forced execution"
  [f & {:keys [key-fn delay leading? trailing? max-wait]
        :or {delay 100 trailing? true}}]
  (let [default-key (random-uuid)]
    (fn [& args]
      (let [key (if key-fn
                  (key-fn args)
                  default-key)]
        (debounced-call
         key
         #(apply f args)
         delay
         {:leading? leading?
          :trailing? trailing?
          :max-wait max-wait})))))

(defn throttle-fn
  "Returns a throttled version of function f.

   Options:
   - :key-fn - Function to derive throttle key from arguments (receives args as a seq)
   - :interval - Interval in ms or keyword from DEFAULT-DELAYS"
  [f & {:keys [key-fn interval]
        :or {interval 100}}]
  (let [default-key (random-uuid)]
    (fn [& args]
      (let [key (if key-fn
                  (key-fn args)
                  default-key)]
        (throttled-call key #(apply f args) interval)))))

;; =============================================================================
;; Debounce Coordinator Record
;; =============================================================================

(defrecord DebounceCoordinator []
  p/IDebounceCoordinator

  (debounced-call [_this key f delay-ms]
    (debounced-call key f delay-ms))

  (cancel [_this key]
    (cancel key))

  (cancel-all [_this]
    (cancel-all)))

(defn make-debounce-coordinator
  "Creates a debounce coordinator instance."
  []
  (->DebounceCoordinator))

;; =============================================================================
;; Utility Functions
;; =============================================================================

(defn pending-count
  "Returns the number of pending debounced calls."
  []
  (count @timers))

(defn pending-keys
  "Returns the set of keys with pending debounced calls."
  []
  (set (keys @timers)))

(defn has-pending?
  "Returns true if there's a pending debounced call for the given key."
  [key]
  (contains? @timers key))
