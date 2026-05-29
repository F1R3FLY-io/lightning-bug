(ns lib.perf.bench-runner
  "Benchmark runner for Lightning Bug performance testing.

   Provides:
   - Configurable warmup and measurement iterations
   - JSON output for CI integration
   - Environment capture (hardware, versions, git hash)
   - Automated baseline comparison
   - Statistical significance testing

   Configuration:
   - Warmup iterations: 10 (discarded)
   - Measurement iterations: 100
   - Minimum sample size: 30 (CLT requirement)
   - Confidence level: 0.95
   - Significance threshold: 0.05"
  (:require
   [clojure.core.async :as async :refer [go go-loop <! timeout]]
   [clojure.core.async.interop :refer-macros [<p!]]
   [clojure.string :as str]
   [lib.perf.bench :as bench]
   [lib.perf.stats :as stats]
   [taoensso.timbre :as log]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:const DEFAULT-CONFIG
  {:warmup-iterations 10
   :measurement-iterations 100
   :min-sample-size 30
   :confidence-level 0.95
   :significance-threshold 0.05
   :gc-between-iterations? true
   :delay-between-iterations-ms 10})

(defonce ^:private current-config (atom DEFAULT-CONFIG))
(defonce ^:private benchmark-results (atom {}))
(defonce ^:private environment-info (atom nil))

;; =============================================================================
;; Environment Capture
;; =============================================================================

(defn capture-environment
  "Captures environment information for benchmark reproducibility."
  []
  (let [nav (when (exists? js/navigator) js/navigator)
        perf-mem (when (and (exists? js/performance)
                            (.-memory js/performance))
                   (.-memory js/performance))]
    {:timestamp (js/Date.now)
     :iso-timestamp (.toISOString (js/Date.))
     :user-agent (when nav (.-userAgent nav))
     :platform (when nav (.-platform nav))
     :hardware-concurrency (when nav (.-hardwareConcurrency nav))
     :device-memory (when nav (.-deviceMemory nav))
     :heap-size-limit (when perf-mem (.-jsHeapSizeLimit perf-mem))
     :language (when nav (.-language nav))
     :timezone (try
                 (-> (js/Intl.DateTimeFormat.)
                     (.resolvedOptions)
                     (.-timeZone))
                 ;; Timezone is optional environment metadata; fall back to nil if Intl is unavailable.
                 (catch js/Error _ nil))
     ;; Git info would be injected at build time
     :git-commit (when (exists? js/BENCHMARK_GIT_COMMIT)
                   js/BENCHMARK_GIT_COMMIT)
     :git-branch (when (exists? js/BENCHMARK_GIT_BRANCH)
                   js/BENCHMARK_GIT_BRANCH)
     :build-mode (when (exists? js/BENCHMARK_BUILD_MODE)
                   js/BENCHMARK_BUILD_MODE)}))

(defn set-environment-info!
  "Sets additional environment info (e.g., from build system)."
  [info]
  (swap! environment-info merge info))

;; =============================================================================
;; Benchmark Execution
;; =============================================================================

(defn- trigger-gc
  "Attempts to trigger garbage collection if available."
  []
  (when (and (exists? js/window)
             (exists? js/window.gc))
    (try
      (js/window.gc)
      ;; GC is a best-effort hint (only present under --expose-gc); ignore if unsupported.
      (catch js/Error _ nil))))

(defn run-single-iteration
  "Runs a single iteration of a benchmark and returns the duration."
  [f]
  (let [start (bench/now)]
    (f)
    (- (bench/now) start)))

(defn run-single-iteration-async
  "Runs a single async iteration of a benchmark and returns a promise with duration."
  [f]
  (let [start (bench/now)]
    (-> (f)
        (.then (fn [_result]
                 (- (bench/now) start))))))

(defn run-benchmark-sync
  "Runs a synchronous benchmark with warmup and measurement phases.

   Arguments:
   - name: keyword identifying the benchmark
   - f: function to benchmark (no arguments)
   - config: optional configuration map

   Returns a promise that resolves to the benchmark results."
  ([name f]
   (run-benchmark-sync name f @current-config))
  ([name f config]
   (let [{:keys [warmup-iterations measurement-iterations
                 gc-between-iterations? delay-between-iterations-ms]} config]
     (js/Promise.
      (fn [resolve _reject]
        (go
          (log/info "Starting benchmark:" name)
          (log/info "  Warmup iterations:" warmup-iterations)
          (log/info "  Measurement iterations:" measurement-iterations)

          ;; Warmup phase
          (log/debug "Running warmup phase...")
          (dotimes [_ warmup-iterations]
            (run-single-iteration f)
            (when gc-between-iterations? (trigger-gc))
            (<! (timeout delay-between-iterations-ms)))

          ;; Measurement phase
          (log/debug "Running measurement phase...")
          (let [measurements (loop [i 0
                                    results []]
                               (if (>= i measurement-iterations)
                                 results
                                 (do
                                   (let [duration (run-single-iteration f)]
                                     (when gc-between-iterations? (trigger-gc))
                                     (<! (timeout delay-between-iterations-ms))
                                     (recur (inc i) (conj results duration))))))]

            ;; Calculate statistics
            (let [raw-stats (stats/full-statistics measurements)
                  cleaned-data (stats/remove-outliers measurements)
                  clean-stats (stats/full-statistics (:cleaned cleaned-data))
                  target (get bench/PERFORMANCE-TARGETS name)
                  result {:name name
                          :config config
                          :raw {:measurements measurements
                                :stats raw-stats}
                          :cleaned {:measurements (:cleaned cleaned-data)
                                    :stats clean-stats
                                    :outliers-removed (:outlier-count cleaned-data)}
                          :target target
                          :passes-target? (when target
                                            (<= (:p95 clean-stats)
                                                (:target-ms target)))
                          :environment (capture-environment)
                          :timestamp (js/Date.now)}]

              (log/info "Benchmark complete:" name)
              (log/info "  Mean:" (.toFixed (:mean clean-stats) 3) "ms")
              (log/info "  Median:" (.toFixed (:median clean-stats) 3) "ms")
              (log/info "  P95:" (.toFixed (:p95 clean-stats) 3) "ms")
              (log/info "  Std Dev:" (.toFixed (:std-dev clean-stats) 3) "ms")
              (log/info "  Outliers removed:" (:outlier-count cleaned-data))
              (when target
                (log/info "  Target:" (:target-ms target) "ms"
                         (if (:passes-target? result) "PASS" "FAIL")))

              ;; Store results
              (swap! benchmark-results assoc name result)
              (resolve result)))))))))

(defn run-benchmark-async
  "Runs an asynchronous benchmark with warmup and measurement phases.

   Arguments:
   - name: keyword identifying the benchmark
   - f: async function to benchmark (returns Promise)
   - config: optional configuration map

   Returns a promise that resolves to the benchmark results."
  ([name f]
   (run-benchmark-async name f @current-config))
  ([name f config]
   (let [{:keys [warmup-iterations measurement-iterations
                 gc-between-iterations? delay-between-iterations-ms]} config]
     (js/Promise.
      (fn [resolve _reject]
        (go
          (log/info "Starting async benchmark:" name)
          (log/info "  Warmup iterations:" warmup-iterations)
          (log/info "  Measurement iterations:" measurement-iterations)

          ;; Warmup phase
          (log/debug "Running warmup phase...")
          (loop [i 0]
            (when (< i warmup-iterations)
              (<p! (run-single-iteration-async f))
              (when gc-between-iterations? (trigger-gc))
              (<! (timeout delay-between-iterations-ms))
              (recur (inc i))))

          ;; Measurement phase
          (log/debug "Running measurement phase...")
          (let [measurements (<! (go-loop [i 0
                                           results []]
                                   (if (>= i measurement-iterations)
                                     results
                                     (let [duration (<p! (run-single-iteration-async f))]
                                       (when gc-between-iterations? (trigger-gc))
                                       (<! (timeout delay-between-iterations-ms))
                                       (recur (inc i) (conj results duration))))))]

            ;; Calculate statistics
            (let [raw-stats (stats/full-statistics measurements)
                  cleaned-data (stats/remove-outliers measurements)
                  clean-stats (stats/full-statistics (:cleaned cleaned-data))
                  target (get bench/PERFORMANCE-TARGETS name)
                  result {:name name
                          :config config
                          :raw {:measurements measurements
                                :stats raw-stats}
                          :cleaned {:measurements (:cleaned cleaned-data)
                                    :stats clean-stats
                                    :outliers-removed (:outlier-count cleaned-data)}
                          :target target
                          :passes-target? (when target
                                            (<= (:p95 clean-stats)
                                                (:target-ms target)))
                          :environment (capture-environment)
                          :timestamp (js/Date.now)}]

              (log/info "Async benchmark complete:" name)
              (log/info "  Mean:" (.toFixed (:mean clean-stats) 3) "ms")
              (log/info "  Median:" (.toFixed (:median clean-stats) 3) "ms")
              (log/info "  P95:" (.toFixed (:p95 clean-stats) 3) "ms")
              (log/info "  Std Dev:" (.toFixed (:std-dev clean-stats) 3) "ms")
              (log/info "  Outliers removed:" (:outlier-count cleaned-data))
              (when target
                (log/info "  Target:" (:target-ms target) "ms"
                         (if (:passes-target? result) "PASS" "FAIL")))

              ;; Store results
              (swap! benchmark-results assoc name result)
              (resolve result)))))))))

;; =============================================================================
;; Benchmark Suite
;; =============================================================================

(defn run-suite
  "Runs a suite of benchmarks sequentially.

   Arguments:
   - benchmarks: vector of {:name keyword, :fn function, :async? boolean}
   - config: optional configuration map

   Returns a promise that resolves to all benchmark results."
  ([benchmarks]
   (run-suite benchmarks @current-config))
  ([benchmarks config]
   (js/Promise.
    (fn [resolve _reject]
      (go
        (log/info "Starting benchmark suite with" (count benchmarks) "benchmarks")
        (let [start-time (js/Date.now)
              results (loop [remaining benchmarks
                             results []]
                        (if (empty? remaining)
                          results
                          (let [{:keys [name fn async?]} (first remaining)
                                result (<p! (if async?
                                              (run-benchmark-async name fn config)
                                              (run-benchmark-sync name fn config)))]
                            ;; Brief pause between benchmarks
                            (<! (timeout 100))
                            (recur (rest remaining) (conj results result)))))
              end-time (js/Date.now)
              suite-result {:benchmarks results
                            :total-time-ms (- end-time start-time)
                            :environment (capture-environment)
                            :config config
                            :timestamp (js/Date.now)}]

          (log/info "Benchmark suite complete")
          (log/info "  Total time:" (- end-time start-time) "ms")
          (log/info "  Benchmarks run:" (count results))

          (resolve suite-result)))))))

;; =============================================================================
;; Results Export
;; =============================================================================

(defn results-to-json
  "Converts benchmark results to JSON string."
  ([]
   (results-to-json @benchmark-results))
  ([results]
   (js/JSON.stringify (clj->js results) nil 2)))

(defn export-results
  "Exports benchmark results to a downloadable JSON file (browser only).

   Arguments:
   - filename: string, the filename for the download
   - results: optional results map (defaults to stored results)"
  ([filename]
   (export-results filename @benchmark-results))
  ([filename results]
   (when (exists? js/document)
     (let [json-str (results-to-json results)
           blob (js/Blob. #js [json-str] #js {:type "application/json"})
           url (js/URL.createObjectURL blob)
           link (js/document.createElement "a")]
       (set! (.-href link) url)
       (set! (.-download link) filename)
       (.click link)
       (js/URL.revokeObjectURL url)))))

(defn format-suite-report
  "Formats a suite result as a human-readable report."
  [suite-result]
  (let [lines (atom ["# Benchmark Report"
                     ""
                     (str "**Date:** " (:iso-timestamp (:environment suite-result)))
                     (str "**Platform:** " (:platform (:environment suite-result)))
                     (str "**User Agent:** " (:user-agent (:environment suite-result)))
                     (str "**Total Time:** " (:total-time-ms suite-result) "ms")
                     ""
                     "## Results"
                     ""])]
    (doseq [bench (:benchmarks suite-result)]
      (let [stats (get-in bench [:cleaned :stats])
            target (:target bench)
            passes? (:passes-target? bench)]
        (swap! lines conj (str "### " (name (:name bench))))
        (swap! lines conj "")
        (swap! lines conj "| Metric | Value |")
        (swap! lines conj "|--------|-------|")
        (swap! lines conj (str "| Mean | " (.toFixed (:mean stats) 3) " ms |"))
        (swap! lines conj (str "| Median | " (.toFixed (:median stats) 3) " ms |"))
        (swap! lines conj (str "| P95 | " (.toFixed (:p95 stats) 3) " ms |"))
        (swap! lines conj (str "| P99 | " (.toFixed (:p99 stats) 3) " ms |"))
        (swap! lines conj (str "| Std Dev | " (.toFixed (:std-dev stats) 3) " ms |"))
        (swap! lines conj (str "| Sample Size | " (:count stats) " |"))
        (when target
          (swap! lines conj (str "| Target | " (:target-ms target) " ms |"))
          (swap! lines conj (str "| Status | " (if passes? "PASS" "FAIL") " |")))
        (swap! lines conj "")))
    (str/join "\n" @lines)))

;; =============================================================================
;; Comparison
;; =============================================================================

(defn compare-results
  "Compares two benchmark results with statistical significance testing.

   Arguments:
   - baseline: benchmark result map
   - experiment: benchmark result map

   Returns comparison analysis."
  [baseline experiment]
  (let [b-measurements (get-in baseline [:cleaned :measurements])
        e-measurements (get-in experiment [:cleaned :measurements])]
    (when (and (seq b-measurements) (seq e-measurements))
      (let [comparison (stats/compare-benchmarks b-measurements e-measurements)
            b-stats (get-in baseline [:cleaned :stats])
            e-stats (get-in experiment [:cleaned :stats])]
        (merge comparison
               {:baseline-name (:name baseline)
                :experiment-name (:name experiment)
                :baseline-p95 (:p95 b-stats)
                :experiment-p95 (:p95 e-stats)
                :baseline-mean (:mean b-stats)
                :experiment-mean (:mean e-stats)})))))

(defn format-comparison-report
  "Formats a comparison result as a human-readable report."
  [comparison]
  (let [decision (:decision comparison)
        b-stats (:baseline-stats comparison)
        e-stats (:experiment-stats comparison)
        t-test (:welch-t-test comparison)
        effect (:cohens-d comparison)]
    (str "# Benchmark Comparison Report\n\n"
         "## Summary\n\n"
         "**Decision:** " (name decision) "\n\n"
         (:summary comparison) "\n\n"
         "## Statistics\n\n"
         "| Metric | Baseline | Experiment | Change |\n"
         "|--------|----------|------------|--------|\n"
         "| Mean | " (.toFixed (:mean b-stats) 3) " ms | "
         (.toFixed (:mean e-stats) 3) " ms | "
         (if (:improved? comparison) "" "+")
         (.toFixed (:percent-change comparison) 2) "% |\n"
         "| Median | " (.toFixed (:median b-stats) 3) " ms | "
         (.toFixed (:median e-stats) 3) " ms | |\n"
         "| P95 | " (.toFixed (:p95 b-stats) 3) " ms | "
         (.toFixed (:p95 e-stats) 3) " ms | |\n"
         "| Std Dev | " (.toFixed (:std-dev b-stats) 3) " ms | "
         (.toFixed (:std-dev e-stats) 3) " ms | |\n\n"
         "## Statistical Tests\n\n"
         "**Welch's t-test:**\n"
         "- t-statistic: " (.toFixed (:t-statistic t-test) 4) "\n"
         "- p-value: " (.toFixed (:p-value t-test) 4) "\n"
         "- Degrees of freedom: " (.toFixed (:df t-test) 2) "\n"
         "- Significant: " (if (:significant? t-test) "Yes" "No") "\n\n"
         "**Cohen's d:**\n"
         "- d: " (.toFixed (:d effect) 4) "\n"
         "- Magnitude: " (:magnitude effect) "\n"
         "- Meaningful: " (if (:meaningful? effect) "Yes" "No") "\n")))

;; =============================================================================
;; Configuration API
;; =============================================================================

(defn set-config!
  "Updates benchmark configuration."
  [config]
  (swap! current-config merge config))

(defn reset-config!
  "Resets configuration to defaults."
  []
  (reset! current-config DEFAULT-CONFIG))

(defn get-config
  "Returns current configuration."
  []
  @current-config)

(defn clear-results!
  "Clears stored benchmark results."
  []
  (reset! benchmark-results {}))

(defn get-results
  "Returns stored benchmark results."
  []
  @benchmark-results)
