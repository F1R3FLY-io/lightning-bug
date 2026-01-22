(ns lib.perf.bench
  "Performance benchmarking infrastructure for Lightning Bug.

   Provides:
   - High-resolution timing with performance.mark/measure
   - Statistical aggregation (mean, median, p95, p99)
   - Memory tracking (when available)
   - DevTools integration
   - Frame time monitoring for 60fps validation"
  (:require [taoensso.timbre :as log]))

;; =============================================================================
;; Configuration
;; =============================================================================

(goog-define ^boolean BENCHMARKS-ENABLED true)

(def PERFORMANCE-TARGETS
  "Performance targets for key operations."
  {:tree-sitter-full-parse {:target-ms 50 :description "Full parse (10K lines)"}
   :tree-sitter-incremental {:target-ms 5 :description "Incremental parse"}
   :datascript-query {:target-ms 5 :description "Typical DataScript query"}
   :frame-time {:target-ms 16.67 :description "60fps frame budget"}
   :startup {:target-ms 2000 :description "Time to interactive"}
   :syntax-init {:target-ms 500 :description "Syntax initialization (cached WASM)"}
   :lsp-response {:target-ms 500 :description "LSP request/response"}
   :diagnostic-transform {:target-ms 10 :description "Diagnostic transformation"}})

;; =============================================================================
;; Timing State
;; =============================================================================

;; Atom holding collected measurements.
;; Structure: {:metric-name [{:duration ms :timestamp Date.now :metadata {}}]}
(defonce ^:private measurements (atom {}))

;; Atom holding active marks that haven't been ended.
(defonce ^:private active-marks (atom {}))

;; =============================================================================
;; High-Resolution Timing
;; =============================================================================

(defn now
  "Returns current high-resolution timestamp in milliseconds."
  []
  (if (and js/performance (.-now js/performance))
    (.now js/performance)
    (js/Date.now)))

(defn mark-start
  "Marks the start of a measurement.

   Returns a unique mark ID that should be passed to mark-end."
  ([name]
   (mark-start name {}))
  ([name metadata]
   (when BENCHMARKS-ENABLED
     (let [mark-id (str name "-" (random-uuid))
           timestamp (now)]
       ;; Use browser Performance API if available
       (when (and js/performance (.-mark js/performance))
         (try
           (.mark js/performance mark-id)
         (catch js/Error _e nil)))
       ;; Store in our tracking
       (swap! active-marks assoc mark-id
              {:name name
               :start timestamp
               :metadata metadata})
       mark-id))))

(defn mark-end
  "Marks the end of a measurement and records the duration.

   Returns the duration in milliseconds, or nil if mark not found."
  [mark-id]
  (when BENCHMARKS-ENABLED
    (when-let [mark-info (get @active-marks mark-id)]
      (let [end-timestamp (now)
            duration (- end-timestamp (:start mark-info))
            name (:name mark-info)
            metadata (:metadata mark-info)]

        ;; Use browser Performance API measure if available
        (when (and js/performance (.-measure js/performance))
          (try
            (.measure js/performance (str name "-measure") mark-id)
          (catch js/Error _e nil)))

        ;; Clear the mark
        (swap! active-marks dissoc mark-id)

        ;; Record the measurement
        (swap! measurements update name
               (fnil conj [])
               {:duration duration
                :timestamp (js/Date.now)
                :metadata metadata})

        duration))))

(defmacro with-timing
  "Macro to time a block of code.

   Usage: (with-timing :operation-name (do-something))"
  [name & body]
  `(if BENCHMARKS-ENABLED
     (let [mark-id# (mark-start ~name)]
       (try
         (let [result# (do ~@body)]
           (mark-end mark-id#)
           result#)
         (catch js/Error e#
           (mark-end mark-id#)
           (throw e#))))
     (do ~@body)))

;; Since macros don't work well in ClojureScript at runtime,
;; provide a function-based alternative
(defn timed
  "Times the execution of f and records the measurement.

   Usage: (timed :operation-name #(do-something))"
  ([name f]
   (timed name {} f))
  ([name metadata f]
   (if BENCHMARKS-ENABLED
     (let [mark-id (mark-start name metadata)]
       (try
         (let [result (f)]
           (mark-end mark-id)
           result)
         (catch js/Error e
           (mark-end mark-id)
           (throw e))))
     (f))))

(defn timed-async
  "Times an async operation. Returns a promise.

   Usage: (timed-async :operation-name (js/Promise. ...))"
  ([name promise]
   (timed-async name {} promise))
  ([name metadata promise]
   (if BENCHMARKS-ENABLED
     (let [mark-id (mark-start name metadata)]
       (-> promise
           (.then (fn [result]
                    (mark-end mark-id)
                    result))
           (.catch (fn [error]
                     (mark-end mark-id)
                     (throw error)))))
     promise)))

;; =============================================================================
;; Statistical Analysis
;; =============================================================================

(defn percentile
  "Calculates the nth percentile of a sorted sequence of numbers."
  [sorted-values n]
  (when (seq sorted-values)
    (let [count (count sorted-values)
          index (-> (* n (dec count))
                    (/ 100)
                    Math/round
                    (max 0)
                    (min (dec count)))]
      (nth sorted-values index))))

(defn statistics
  "Calculates statistics for a collection of measurements.

   Returns: {:count :min :max :mean :median :p95 :p99 :std-dev}"
  [measurements]
  (when (seq measurements)
    (let [durations (map :duration measurements)
          sorted (sort durations)
          count (count durations)
          sum (reduce + durations)
          mean (/ sum count)
          variance (/ (reduce + (map #(Math/pow (- % mean) 2) durations)) count)
          std-dev (Math/sqrt variance)]
      {:count count
       :min (first sorted)
       :max (last sorted)
       :mean mean
       :median (percentile sorted 50)
       :p95 (percentile sorted 95)
       :p99 (percentile sorted 99)
       :std-dev std-dev})))

;; =============================================================================
;; Reporting
;; =============================================================================

(defn report
  "Generates a performance report for all or specified metrics."
  ([]
   (report (keys @measurements)))
  ([metric-names]
   (doseq [name metric-names]
     (when-let [data (get @measurements name)]
       (let [stats (statistics data)
             target (get PERFORMANCE-TARGETS name)]
         (log/info "=== Performance Report:" name "===")
         (log/info "  Count:" (:count stats))
         (log/info "  Min:" (str (.toFixed (:min stats) 2) "ms"))
         (log/info "  Max:" (str (.toFixed (:max stats) 2) "ms"))
         (log/info "  Mean:" (str (.toFixed (:mean stats) 2) "ms"))
         (log/info "  Median:" (str (.toFixed (:median stats) 2) "ms"))
         (log/info "  P95:" (str (.toFixed (:p95 stats) 2) "ms"))
         (log/info "  P99:" (str (.toFixed (:p99 stats) 2) "ms"))
         (log/info "  Std Dev:" (str (.toFixed (:std-dev stats) 2) "ms"))
         (when target
           (let [passes? (<= (:p95 stats) (:target-ms target))]
             (log/info "  Target:" (str (:target-ms target) "ms")
                      "(" (:description target) ")"
                      (if passes? "PASS" "FAIL")))))))))

(defn report-json
  "Returns performance data as a JSON-serializable map."
  ([]
   (report-json (keys @measurements)))
  ([metric-names]
   (into {}
         (for [name metric-names
               :let [data (get @measurements name)]
               :when data]
           [name (merge (statistics data)
                        {:raw-count (count data)
                         :target (get PERFORMANCE-TARGETS name)})]))))

;; =============================================================================
;; Memory Tracking
;; =============================================================================

(defn memory-usage
  "Returns current memory usage if available.

   Returns: {:usedJSHeapSize :totalJSHeapSize :jsHeapSizeLimit} or nil"
  []
  (when-let [memory (and js/performance (.-memory js/performance))]
    {:usedJSHeapSize (.-usedJSHeapSize memory)
     :totalJSHeapSize (.-totalJSHeapSize memory)
     :jsHeapSizeLimit (.-jsHeapSizeLimit memory)}))

(defn memory-pressure?
  "Returns true if memory usage is above 80% of limit."
  []
  (when-let [mem (memory-usage)]
    (> (/ (:usedJSHeapSize mem) (:jsHeapSizeLimit mem)) 0.8)))

;; =============================================================================
;; Frame Time Monitoring
;; =============================================================================

(defonce ^:private frame-times (atom []))
(defonce ^:private raf-id (atom nil))

(defn start-frame-monitoring
  "Starts monitoring frame times using requestAnimationFrame."
  []
  (when BENCHMARKS-ENABLED
    (let [last-time (atom (now))]
      (letfn [(frame-callback [_timestamp]
                (let [current (now)
                      delta (- current @last-time)]
                  (reset! last-time current)
                  (swap! frame-times conj delta)
                  ;; Keep only last 1000 frames
                  (when (> (count @frame-times) 1000)
                    (swap! frame-times #(vec (drop 500 %))))
                  (reset! raf-id (js/requestAnimationFrame frame-callback))))]
        (reset! raf-id (js/requestAnimationFrame frame-callback))))))

(defn stop-frame-monitoring
  "Stops frame time monitoring."
  []
  (when @raf-id
    (js/cancelAnimationFrame @raf-id)
    (reset! raf-id nil)))

(defn frame-statistics
  "Returns statistics about recent frame times."
  []
  (let [times @frame-times]
    (when (seq times)
      (let [stats (statistics (map #(hash-map :duration %) times))
            target 16.67
            dropped (count (filter #(> % target) times))]
        (assoc stats
               :dropped-frames dropped
               :dropped-percentage (* 100 (/ dropped (count times)))
               :target-fps 60
               :actual-fps (/ 1000 (:mean stats)))))))

;; =============================================================================
;; Cleanup and Reset
;; =============================================================================

(defn clear-measurements
  "Clears all recorded measurements."
  ([]
   (reset! measurements {}))
  ([name]
   (swap! measurements dissoc name)))

(defn clear-frame-times
  "Clears recorded frame times."
  []
  (reset! frame-times []))

;; =============================================================================
;; DevTools Integration
;; =============================================================================

(defn performance-entries
  "Returns Performance API entries for inspection."
  []
  (when (and js/performance (.-getEntriesByType js/performance))
    (concat
     (array-seq (.getEntriesByType js/performance "mark"))
     (array-seq (.getEntriesByType js/performance "measure")))))

(defn clear-performance-entries
  "Clears Performance API marks and measures."
  []
  (when js/performance
    (when (.-clearMarks js/performance)
      (.clearMarks js/performance))
    (when (.-clearMeasures js/performance)
      (.clearMeasures js/performance))))

;; =============================================================================
;; Benchmark Suite
;; =============================================================================

(defn run-benchmark
  "Runs a benchmark function n times and records measurements.

   Returns statistics for the benchmark."
  [name f iterations]
  (log/info "Running benchmark:" name "for" iterations "iterations")
  (clear-measurements name)
  (dotimes [_ iterations]
    (timed name f))
  (let [stats (statistics (get @measurements name))]
    (log/info "Benchmark complete:" name)
    (log/info "  Mean:" (str (.toFixed (:mean stats) 2) "ms"))
    (log/info "  P95:" (str (.toFixed (:p95 stats) 2) "ms"))
    stats))

(defn assert-performance
  "Asserts that a metric meets its performance target.

   Throws if the assertion fails."
  [metric-name percentile-key]
  (let [data (get @measurements metric-name)
        stats (statistics data)
        target (get PERFORMANCE-TARGETS metric-name)
        actual (get stats percentile-key)]
    (when (and target actual)
      (when (> actual (:target-ms target))
        (throw (ex-info "Performance target not met"
                        {:metric metric-name
                         :target (:target-ms target)
                         :actual actual
                         :percentile percentile-key}))))))
