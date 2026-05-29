(ns lib.lifecycle
  "Lifecycle management for resources requiring ordered initialization and cleanup.

   This module provides:
   - Ordered resource initialization (dependency-aware)
   - Cleanup on unmount with proper ordering
   - Resource registration and tracking
   - Graceful shutdown handling"
  (:require [clojure.core.async :refer [go put! <! promise-chan]]
            [domain.protocols :as p]
            [taoensso.timbre :as log]))

;; =============================================================================
;; Resource Registry
;; =============================================================================

;; Atom holding registered resources with their metadata.
;; Structure: {:resource-key {:resource instance
;;                            :started? boolean
;;                            :cleanup-fn fn
;;                            :priority int (lower = start first, stop last)
;;                            :depends-on #{resource-keys}}}
(defonce ^:private registry (atom {}))

;; Flag to prevent new registrations during shutdown.
(defonce ^:private shutdown-in-progress? (atom false))

;; Forward declarations for functions used before definition
(declare start-resource! stop-resource!)

;; =============================================================================
;; Registration Functions
;; =============================================================================

(defn register-resource!
  "Registers a resource for lifecycle management.

   Options:
   - :cleanup-fn - Function called with resource during cleanup
   - :priority - Startup priority (lower = earlier), default 100
   - :depends-on - Set of resource keys this resource depends on
   - :auto-start? - If true, starts the resource immediately (default false)

   Returns the resource key."
  [key resource & {:keys [cleanup-fn priority depends-on auto-start?]
                   :or {priority 100
                        depends-on #{}
                        auto-start? false}}]
  (when @shutdown-in-progress?
    (throw (ex-info "Cannot register resources during shutdown" {:key key})))

  (swap! registry assoc key
         {:resource resource
          :started? false
          :cleanup-fn cleanup-fn
          :priority priority
          :depends-on depends-on})

  (log/trace "Registered resource:" key "with priority:" priority)

  (when auto-start?
    (go (<! (start-resource! key))))

  key)

(defn unregister-resource!
  "Unregisters a resource, cleaning it up if started.
   Returns a channel that completes when cleanup is done."
  [key]
  (let [result-ch (promise-chan)]
    (go
      (when-let [entry (get @registry key)]
        (when (:started? entry)
          (<! (stop-resource! key))))
      (swap! registry dissoc key)
      (log/trace "Unregistered resource:" key)
      (put! result-ch :done))
    result-ch))

(defn get-resource
  "Retrieves a registered resource by key, or nil if not found."
  [key]
  (get-in @registry [key :resource]))

(defn resource-started?
  "Returns true if the resource has been started."
  [key]
  (get-in @registry [key :started?] false))

;; =============================================================================
;; Dependency Resolution
;; =============================================================================

(defn- topological-sort
  "Returns keys in dependency order for starting (dependencies first).
   Uses Kahn's algorithm."
  [registry-snapshot keys-to-start]
  (let [;; Build adjacency list and in-degree map
        relevant-keys (set keys-to-start)
        in-degree (atom {})
        ;; Initialize in-degree to 0 for all keys
        _ (doseq [k relevant-keys]
            (swap! in-degree assoc k 0))
        ;; Count dependencies within the relevant set
        _ (doseq [k relevant-keys
                  :let [deps (get-in registry-snapshot [k :depends-on] #{})]
                  dep deps
                  :when (relevant-keys dep)]
            (swap! in-degree update k (fnil inc 0)))
        ;; Find all keys with no dependencies
        queue (atom (filterv #(zero? (get @in-degree % 0)) relevant-keys))
        result (atom [])]

    ;; Process queue
    (while (seq @queue)
      (let [current (first @queue)]
        (swap! queue rest)
        (swap! result conj current)
        ;; Reduce in-degree for dependents
        (doseq [k relevant-keys
                :let [deps (get-in registry-snapshot [k :depends-on] #{})]
                :when (deps current)]
          (swap! in-degree update k dec)
          (when (zero? (get @in-degree k))
            (swap! queue conj k)))))

    ;; Check for cycles
    (when (not= (count @result) (count relevant-keys))
      (log/warn "Dependency cycle detected in resource graph"))

    ;; Sort by priority within same dependency level
    (sort-by #(get-in registry-snapshot [% :priority] 100) @result)))

(defn- reverse-topological-sort
  "Returns keys in reverse dependency order for stopping (dependents first)."
  [registry-snapshot keys-to-stop]
  (reverse (topological-sort registry-snapshot keys-to-stop)))

;; =============================================================================
;; Start/Stop Functions
;; =============================================================================

(defn start-resource!
  "Starts a single resource if not already started.
   Returns a channel with [:ok resource] or [:error reason]."
  [key]
  (let [result-ch (promise-chan)]
    (go
      (if-let [entry (get @registry key)]
        (if (:started? entry)
          (put! result-ch [:ok (:resource entry)])
          (let [resource (:resource entry)]
            (try
              (if (satisfies? p/IResourceLifecycle resource)
                (let [[status val] (<! (p/start! resource))]
                  (if (= status :ok)
                    (do
                      (swap! registry assoc-in [key :started?] true)
                      (log/trace "Started resource:" key)
                      (put! result-ch [:ok val]))
                    (do
                      (log/error "Failed to start resource:" key "-" val)
                      (put! result-ch [:error val]))))
                (do
                  (swap! registry assoc-in [key :started?] true)
                  (log/trace "Started resource (no lifecycle):" key)
                  (put! result-ch [:ok resource])))
              (catch js/Error e
                (log/error "Exception starting resource:" key "-" (.-message e))
                (put! result-ch [:error e])))))
        (put! result-ch [:error (ex-info "Resource not found" {:key key})])))
    result-ch))

(defn stop-resource!
  "Stops a single resource if started.
   Returns a channel with [:ok] or [:error reason]."
  [key]
  (let [result-ch (promise-chan)]
    (go
      (if-let [entry (get @registry key)]
        (if-not (:started? entry)
          (put! result-ch [:ok])
          (let [resource (:resource entry)
                cleanup-fn (:cleanup-fn entry)]
            (try
              ;; Try IResourceLifecycle.stop! first
              (if (satisfies? p/IResourceLifecycle resource)
                (let [[status val] (<! (p/stop! resource))]
                  (swap! registry assoc-in [key :started?] false)
                  (if (= status :ok)
                    (do
                      (log/trace "Stopped resource:" key)
                      (put! result-ch [:ok]))
                    (do
                      (log/warn "Error stopping resource:" key "-" val)
                      (put! result-ch [:error val]))))
                ;; Fall back to cleanup-fn
                (do
                  (when cleanup-fn
                    (cleanup-fn resource))
                  (swap! registry assoc-in [key :started?] false)
                  (log/trace "Cleaned up resource:" key)
                  (put! result-ch [:ok])))
              (catch js/Error e
                (log/error "Exception stopping resource:" key "-" (.-message e))
                (swap! registry assoc-in [key :started?] false)
                (put! result-ch [:error e])))))
        (put! result-ch [:ok])))
    result-ch))

;; =============================================================================
;; Batch Operations
;; =============================================================================

(defn start-all!
  "Starts all registered resources in dependency order.
   Returns a channel with [:ok] or [:error {:failed [keys]}]."
  []
  (let [result-ch (promise-chan)]
    (go
      (let [snapshot @registry
            keys-to-start (keys snapshot)
            ordered-keys (topological-sort snapshot keys-to-start)
            failed (atom [])]

        (log/info "Starting" (count ordered-keys) "resources in order:" ordered-keys)

        (doseq [k ordered-keys]
          (let [[status _] (<! (start-resource! k))]
            (when (= status :error)
              (swap! failed conj k))))

        (if (empty? @failed)
          (put! result-ch [:ok])
          (put! result-ch [:error {:failed @failed}]))))
    result-ch))

(defn stop-all!
  "Stops all started resources in reverse dependency order.
   Returns a channel with [:ok] or [:error {:failed [keys]}]."
  []
  (let [result-ch (promise-chan)]
    (go
      (reset! shutdown-in-progress? true)
      (let [snapshot @registry
            started-keys (filter #(get-in snapshot [% :started?]) (keys snapshot))
            ordered-keys (reverse-topological-sort snapshot started-keys)
            failed (atom [])]

        (log/info "Stopping" (count ordered-keys) "resources in order:" ordered-keys)

        (doseq [k ordered-keys]
          (let [[status _] (<! (stop-resource! k))]
            (when (= status :error)
              (swap! failed conj k))))

        (reset! shutdown-in-progress? false)

        (if (empty? @failed)
          (put! result-ch [:ok])
          (put! result-ch [:error {:failed @failed}]))))
    result-ch))

;; =============================================================================
;; Lifecycle Manager Record
;; =============================================================================

(defrecord LifecycleManager [id]
  p/IResourceLifecycle

  (start! [_this]
    (start-all!))

  (stop! [_this]
    (stop-all!))

  (started? [_this]
    (let [snapshot @registry]
      (every? #(get-in snapshot [% :started?]) (keys snapshot))))

  (restart! [this]
    (go
      (<! (p/stop! this))
      (<! (p/start! this)))))

(defn make-lifecycle-manager
  "Creates a lifecycle manager instance."
  ([]
   (make-lifecycle-manager (random-uuid)))
  ([id]
   (->LifecycleManager id)))

;; =============================================================================
;; Cleanup Utilities
;; =============================================================================

(defn reset-registry!
  "Clears all registered resources. Use only for testing."
  []
  (reset! registry {})
  (reset! shutdown-in-progress? false))

(defn list-resources
  "Returns a list of all registered resource keys with their status."
  []
  (map (fn [[k v]]
         {:key k
          :started? (:started? v)
          :priority (:priority v)
          :depends-on (:depends-on v)})
       @registry))
