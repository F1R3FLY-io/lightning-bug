(ns lib.query-cache
  "Query result caching for DataScript to improve performance.

   Provides:
   - TTL-based cache invalidation
   - Automatic cache invalidation on transactions
   - Query-specific cache keys
   - Memory-bounded caching"
  (:require [taoensso.timbre :as log]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def DEFAULT-TTL-MS
  "Default time-to-live for cached query results in milliseconds."
  5000) ; 5 seconds

(def MAX-CACHE-ENTRIES
  "Maximum number of entries in the cache."
  100)

;; =============================================================================
;; Cache State
;; =============================================================================

(defonce ^:private cache
  "Atom holding cached query results.
   Structure: {cache-key {:result query-result
                          :timestamp timestamp-ms
                          :ttl-ms ttl}}"
  (atom {}))

(defonce ^:private cache-stats
  "Atom holding cache statistics."
  (atom {:hits 0 :misses 0 :evictions 0}))

(defonce ^:private transaction-counter
  "Counter incremented on each transaction to invalidate stale caches."
  (atom 0))

;; =============================================================================
;; Cache Key Generation
;; =============================================================================

(defn make-cache-key
  "Creates a cache key from query parameters."
  [query-name & args]
  (hash [query-name args @transaction-counter]))

(defn make-cache-key-stable
  "Creates a stable cache key that doesn't depend on transaction counter.
   Use for queries that should persist across transactions."
  [query-name & args]
  (hash [query-name args]))

;; =============================================================================
;; Cache Operations
;; =============================================================================

(defn- expired?
  "Returns true if the cache entry has expired."
  [{:keys [timestamp ttl-ms]}]
  (let [now (js/Date.now)
        age (- now timestamp)]
    (> age ttl-ms)))

(defn- evict-oldest!
  "Evicts the oldest entries to make room for new ones."
  []
  (let [entries @cache
        count (count entries)]
    (when (> count MAX-CACHE-ENTRIES)
      (let [sorted (sort-by (fn [[_ v]] (:timestamp v)) entries)
            to-evict (take (- count (quot MAX-CACHE-ENTRIES 2)) sorted)]
        (doseq [[k _] to-evict]
          (swap! cache dissoc k))
        (swap! cache-stats update :evictions + (count to-evict))
        (log/trace "Evicted" (count to-evict) "cache entries")))))

(defn get-cached
  "Gets a cached result if available and not expired.
   Returns [hit? result]."
  [cache-key]
  (if-let [entry (get @cache cache-key)]
    (if (expired? entry)
      (do
        (swap! cache dissoc cache-key)
        (swap! cache-stats update :misses inc)
        [false nil])
      (do
        (swap! cache-stats update :hits inc)
        [true (:result entry)]))
    (do
      (swap! cache-stats update :misses inc)
      [false nil])))

(defn put-cached!
  "Caches a query result with optional TTL."
  ([cache-key result]
   (put-cached! cache-key result DEFAULT-TTL-MS))
  ([cache-key result ttl-ms]
   (evict-oldest!)
   (swap! cache assoc cache-key
          {:result result
           :timestamp (js/Date.now)
           :ttl-ms ttl-ms})))

(defn invalidate!
  "Invalidates a specific cache entry."
  [cache-key]
  (swap! cache dissoc cache-key))

(defn invalidate-all!
  "Invalidates all cached entries."
  []
  (reset! cache {})
  (log/trace "Cache invalidated"))

(defn invalidate-matching!
  "Invalidates all cache entries whose keys match the predicate."
  [pred]
  (swap! cache (fn [c]
                 (into {} (remove (fn [[k _]] (pred k)) c)))))

;; =============================================================================
;; Transaction Hook
;; =============================================================================

(defn on-transaction!
  "Called after each transaction to increment the counter.
   This effectively invalidates all transaction-dependent caches."
  []
  (swap! transaction-counter inc))

;; =============================================================================
;; Cached Query Wrapper
;; =============================================================================

(defn cached-query
  "Executes a query with caching.

   Usage:
   (cached-query :my-query [arg1 arg2] #(expensive-query arg1 arg2))

   Options:
   - :ttl-ms - Custom TTL (default: DEFAULT-TTL-MS)
   - :stable? - If true, cache persists across transactions"
  [query-name args query-fn & {:keys [ttl-ms stable?]
                               :or {ttl-ms DEFAULT-TTL-MS
                                    stable? false}}]
  (let [cache-key (if stable?
                    (apply make-cache-key-stable query-name args)
                    (apply make-cache-key query-name args))
        [hit? result] (get-cached cache-key)]
    (if hit?
      result
      (let [result (query-fn)]
        (put-cached! cache-key result ttl-ms)
        result))))

;; =============================================================================
;; Statistics
;; =============================================================================

(defn get-stats
  "Returns cache statistics."
  []
  (let [{:keys [hits misses evictions]} @cache-stats
        total (+ hits misses)
        hit-rate (if (pos? total) (* 100 (/ hits total)) 0)]
    {:hits hits
     :misses misses
     :evictions evictions
     :hit-rate hit-rate
     :entry-count (count @cache)
     :max-entries MAX-CACHE-ENTRIES}))

(defn reset-stats!
  "Resets cache statistics."
  []
  (reset! cache-stats {:hits 0 :misses 0 :evictions 0}))
