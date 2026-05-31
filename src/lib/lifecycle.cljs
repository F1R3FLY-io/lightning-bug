(ns lib.lifecycle
  "Lifecycle management for resources requiring ordered initialization and cleanup.

   This module provides:
   - Ordered resource initialization (dependency-aware)
   - Cleanup on unmount with proper ordering
   - Resource registration and tracking
   - Graceful shutdown handling

   Each operation takes an OPTIONAL leading `reg-atom` (a registry atom). The no-`reg-atom`
   arities operate on the module-global default registry below, so simple/global callers and
   the existing test-suite are unchanged. Per-instance callers (e.g. one LifecycleManager per
   Editor) pass their OWN registry atom (via `make-isolated-lifecycle-manager`) so two editors
   don't collide. The registry atom is threaded explicitly (NOT via a dynamic var) because the
   start/stop go-blocks park on async channels, across which a dynamic binding would not hold."
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
;; This is the DEFAULT (global) registry used by the no-`reg-atom` arities.
(defonce ^:private registry (atom {}))

;; Flag to prevent new registrations during shutdown (for the default registry).
(defonce ^:private shutdown-in-progress? (atom false))

;; Forward declarations for functions used before definition
(declare start-resource! stop-resource!)

;; =============================================================================
;; Registration Functions
;; =============================================================================

(defn register-resource-in!
  "Registers a resource into the given `reg-atom` (guarded by `shutdown-atom`).

   Options:
   - :cleanup-fn - Function called with resource during cleanup
   - :priority - Startup priority (lower = earlier), default 100
   - :depends-on - Set of resource keys this resource depends on
   - :auto-start? - If true, starts the resource immediately (default false)
   - :started? - Initial started state (default false). Set true to register an ALREADY-running
     resource (e.g. an EditorView created before registration) so an ordered teardown will
     clean it up without an async start step.

   Returns the resource key."
  [reg-atom shutdown-atom key resource & {:keys [cleanup-fn priority depends-on auto-start? started?]
                                          :or {priority 100
                                               depends-on #{}
                                               auto-start? false
                                               started? false}}]
  (when @shutdown-atom
    (throw (ex-info "Cannot register resources during shutdown" {:key key})))

  (swap! reg-atom assoc key
         {:resource resource
          :started? started?
          :cleanup-fn cleanup-fn
          :priority priority
          :depends-on depends-on})

  (log/trace "Registered resource:" key "with priority:" priority)

  (when auto-start?
    (go (<! (start-resource! reg-atom key))))

  key)

(defn register-resource!
  "Registers a resource in the default (global) registry. See `register-resource-in!`."
  [key resource & opts]
  (apply register-resource-in! registry shutdown-in-progress? key resource opts))

(defn unregister-resource!
  "Unregisters a resource, cleaning it up if started.
   Returns a channel that completes when cleanup is done."
  ([key] (unregister-resource! registry key))
  ([reg-atom key]
   (let [result-ch (promise-chan)]
     (go
       (when-let [entry (get @reg-atom key)]
         (when (:started? entry)
           (<! (stop-resource! reg-atom key))))
       (swap! reg-atom dissoc key)
       (log/trace "Unregistered resource:" key)
       (put! result-ch :done))
     result-ch)))

(defn get-resource
  "Retrieves a registered resource by key, or nil if not found."
  ([key] (get-resource registry key))
  ([reg-atom key] (get-in @reg-atom [key :resource])))

(defn resource-started?
  "Returns true if the resource has been started."
  ([key] (resource-started? registry key))
  ([reg-atom key] (get-in @reg-atom [key :started?] false)))

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
  ([key] (start-resource! registry key))
  ([reg-atom key]
   (let [result-ch (promise-chan)]
     (go
       (if-let [entry (get @reg-atom key)]
         (if (:started? entry)
           (put! result-ch [:ok (:resource entry)])
           (let [resource (:resource entry)]
             (try
               (if (satisfies? p/IResourceLifecycle resource)
                 (let [[status val] (<! (p/start! resource))]
                   (if (= status :ok)
                     (do
                       (swap! reg-atom assoc-in [key :started?] true)
                       (log/trace "Started resource:" key)
                       (put! result-ch [:ok val]))
                     (do
                       (log/error "Failed to start resource:" key "-" val)
                       (put! result-ch [:error val]))))
                 (do
                   (swap! reg-atom assoc-in [key :started?] true)
                   (log/trace "Started resource (no lifecycle):" key)
                   (put! result-ch [:ok resource])))
               (catch js/Error e
                 (log/error "Exception starting resource:" key "-" (.-message e))
                 (put! result-ch [:error e])))))
         (put! result-ch [:error (ex-info "Resource not found" {:key key})])))
     result-ch)))

(defn stop-resource!
  "Stops a single resource if started.
   Returns a channel with [:ok] or [:error reason]."
  ([key] (stop-resource! registry key))
  ([reg-atom key]
   (let [result-ch (promise-chan)]
     (go
       (if-let [entry (get @reg-atom key)]
         (if-not (:started? entry)
           (put! result-ch [:ok])
           (let [resource (:resource entry)
                 cleanup-fn (:cleanup-fn entry)]
             (try
               ;; Try IResourceLifecycle.stop! first
               (if (satisfies? p/IResourceLifecycle resource)
                 (let [[status val] (<! (p/stop! resource))]
                   (swap! reg-atom assoc-in [key :started?] false)
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
                   (swap! reg-atom assoc-in [key :started?] false)
                   (log/trace "Cleaned up resource:" key)
                   (put! result-ch [:ok])))
               (catch js/Error e
                 (log/error "Exception stopping resource:" key "-" (.-message e))
                 (swap! reg-atom assoc-in [key :started?] false)
                 (put! result-ch [:error e])))))
         (put! result-ch [:ok])))
     result-ch)))

;; =============================================================================
;; Batch Operations
;; =============================================================================

(defn start-all!
  "Starts all registered resources in dependency order.
   Returns a channel with [:ok] or [:error {:failed [keys]}]."
  ([] (start-all! registry))
  ([reg-atom]
   (let [result-ch (promise-chan)]
     (go
       (let [snapshot @reg-atom
             keys-to-start (keys snapshot)
             ordered-keys (topological-sort snapshot keys-to-start)
             failed (atom [])]

         (log/info "Starting" (count ordered-keys) "resources in order:" ordered-keys)

         (doseq [k ordered-keys]
           (let [[status _] (<! (start-resource! reg-atom k))]
             (when (= status :error)
               (swap! failed conj k))))

         (if (empty? @failed)
           (put! result-ch [:ok])
           (put! result-ch [:error {:failed @failed}]))))
     result-ch)))

(defn stop-all!
  "Stops all started resources in reverse dependency order.
   Returns a channel with [:ok] or [:error {:failed [keys]}]."
  ([] (stop-all! registry shutdown-in-progress?))
  ([reg-atom shutdown-atom]
   (let [result-ch (promise-chan)]
     (go
       (reset! shutdown-atom true)
       (let [snapshot @reg-atom
             started-keys (filter #(get-in snapshot [% :started?]) (keys snapshot))
             ordered-keys (reverse-topological-sort snapshot started-keys)
             failed (atom [])]

         (log/info "Stopping" (count ordered-keys) "resources in order:" ordered-keys)

         (doseq [k ordered-keys]
           (let [[status _] (<! (stop-resource! reg-atom k))]
             (when (= status :error)
               (swap! failed conj k))))

         (reset! shutdown-atom false)

         (if (empty? @failed)
           (put! result-ch [:ok])
           (put! result-ch [:error {:failed @failed}]))))
     result-ch)))

(defn stop-all-sync!
  "Synchronously stops all started resources in reverse dependency order by invoking their
  `:cleanup-fn`s. Use when teardown must be synchronous and ordered — e.g. a React effect
  cleanup tearing down a DOM EditorView, where routing teardown through the async
  `stop-all!` go-block would risk a strict-mode remount racing the destroy. Resources implementing the async
  IResourceLifecycle should use `stop-all!` instead; this path is cleanup-fn only."
  [reg-atom]
  (let [snapshot @reg-atom
        started-keys (filter #(get-in snapshot [% :started?]) (keys snapshot))
        ordered-keys (reverse-topological-sort snapshot started-keys)]
    (doseq [k ordered-keys]
      (let [entry (get snapshot k)
            resource (:resource entry)
            cleanup-fn (:cleanup-fn entry)]
        (try
          (when cleanup-fn
            (cleanup-fn resource))
          (log/trace "Cleaned up resource (sync):" k)
          (catch js/Error e
            (log/error "Exception stopping resource (sync):" k "-" (.-message e))))
        (swap! reg-atom assoc-in [k :started?] false)))))

;; =============================================================================
;; Lifecycle Manager Record
;; =============================================================================

(defrecord LifecycleManager [id registry shutdown?]
  p/IResourceLifecycle

  (start! [this]
    (start-all! (:registry this)))

  (stop! [this]
    (stop-all! (:registry this) (:shutdown? this)))

  (started? [this]
    (let [snapshot @(:registry this)]
      (every? #(get-in snapshot [% :started?]) (keys snapshot))))

  (restart! [this]
    (go
      (<! (p/stop! this))
      (<! (p/start! this)))))

(defn make-lifecycle-manager
  "Creates a lifecycle manager over the DEFAULT (global) registry. Resources registered via
  the no-`reg-atom` `register-resource!` are managed by this manager (used by the test-suite
  and simple global callers). For an ISOLATED per-instance registry, use
  `make-isolated-lifecycle-manager`."
  ([]
   (make-lifecycle-manager (random-uuid)))
  ([id]
   (->LifecycleManager id registry shutdown-in-progress?)))

(defn make-isolated-lifecycle-manager
  "Creates a lifecycle manager with its OWN private registry + shutdown flag (no shared
  global state). Register into it with `(register-resource-in! (:registry mgr) (:shutdown? mgr)
  ...)` and tear it down with `(p/stop! mgr)`. Used per-Editor so two editors never collide."
  []
  (->LifecycleManager (random-uuid) (atom {}) (atom false)))

;; =============================================================================
;; Cleanup Utilities
;; =============================================================================

(defn reset-registry!
  "Clears all registered resources. Use only for testing."
  ([] (reset-registry! registry shutdown-in-progress?))
  ([reg-atom shutdown-atom]
   (reset! reg-atom {})
   (reset! shutdown-atom false)))

(defn list-resources
  "Returns a list of all registered resource keys with their status."
  ([] (list-resources registry))
  ([reg-atom]
   (map (fn [[k v]]
          {:key k
           :started? (:started? v)
           :priority (:priority v)
           :depends-on (:depends-on v)})
        @reg-atom)))
