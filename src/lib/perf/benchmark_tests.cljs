(ns lib.perf.benchmark-tests
  "Benchmark test suite for Lightning Bug.

   This module defines the actual benchmarks to be run against the editor.
   Each benchmark tests a specific performance-critical operation.

   Benchmarks:
   - Tree-Sitter full parse (1K, 5K, 10K lines)
   - Tree-Sitter incremental parse
   - DataScript queries (diagnostics-by-uri, symbols-by-uri)
   - Frame time monitoring
   - Diagnostic transformation
   - Syntax initialization time
   - Scroll performance (EXP-005a)
   - Debounce timing and latency (EXP-008)"
  (:require
   [clojure.core.async :as async :refer [go <! timeout]]
   [clojure.core.async.interop :refer-macros [<p!]]
   [lib.perf.bench :as bench]
   [lib.perf.bench-runner :as runner]
   [lib.perf.stats :as stats]
   [lib.db :as db]
   [lib.query-cache :as qc]
   [lib.debounce :as debounce]
   [lib.editor.syntax :as syntax]
   [lib.utils :refer [promise->chan]]
   [taoensso.timbre :as log]
   ["@codemirror/state" :refer [EditorState]]
   ["@codemirror/view" :refer [EditorView]]
   ["@codemirror/commands" :refer [cursorDocEnd]]))

;; =============================================================================
;; Sample Data Generation
;; =============================================================================

(defn generate-rholang-code
  "Generates synthetic Rholang code with the specified number of lines."
  [num-lines]
  (let [templates ["new x in { x!(\"hello\") }"
                   "contract @\"calc\"(ret) = { ret!(42) }"
                   "for (@msg <- channel) { stdout!(msg) }"
                   "match x { 1 => \"one\" | 2 => \"two\" | _ => \"other\" }"
                   "new stdout(`rho:io:stdout`) in { stdout!(\"test\") }"
                   "let val = 100 + 200 in { stdout!(val) }"
                   "// This is a comment line"
                   "new ch1, ch2, ch3 in { ch1!(ch2) | ch2!(ch3) }"]]
    (clojure.string/join "\n"
                         (map #(nth templates (mod % (count templates)))
                              (range num-lines)))))

(def sample-1k-lines (delay (generate-rholang-code 1000)))
(def sample-5k-lines (delay (generate-rholang-code 5000)))
(def sample-10k-lines (delay (generate-rholang-code 10000)))

;; =============================================================================
;; DataScript Test Data Setup
;; =============================================================================

(defn setup-test-db!
  "Sets up test data in DataScript for query benchmarks."
  []
  ;; EXP-006: Reset query cache stats before benchmarks
  (qc/reset-stats!)
  (qc/invalidate-all!)

  ;; Create test documents
  (db/create-documents!
   [{:uri "file:///test/large.rho"
     :text @sample-1k-lines
     :language "rholang"
     :version 1
     :dirty false
     :opened true}
    {:uri "file:///test/medium.rho"
     :text (generate-rholang-code 500)
     :language "rholang"
     :version 1
     :dirty false
     :opened true}])

  ;; Create test diagnostics
  (let [doc-id (db/document-id-by-uri "file:///test/large.rho")
        test-diags (for [i (range 50)]
                     {:message (str "Test diagnostic " i)
                      :severity (inc (mod i 4))
                      :startLine i
                      :startChar 0
                      :endLine i
                      :endChar 10})]
    (when doc-id
      (db/replace-diagnostics-by-uri! "file:///test/large.rho" 1 test-diags)))

  ;; Create test symbols
  (let [doc-id (db/document-id-by-uri "file:///test/large.rho")
        test-symbols (for [i (range 100)]
                       {:symbol/name (str "symbol_" i)
                        :symbol/kind (inc (mod i 25))
                        :symbol/start-line (* i 10)
                        :symbol/start-char 0
                        :symbol/end-line (+ (* i 10) 5)
                        :symbol/end-char 20
                        :symbol/selection-start-line (* i 10)
                        :symbol/selection-start-char 0
                        :symbol/selection-end-line (* i 10)
                        :symbol/selection-end-char 10})]
    (when doc-id
      (db/replace-symbols! "file:///test/large.rho" test-symbols)))

  ;; EXP-007: Set active URI for coalesced query benchmarks
  (db/update-active-uri! "file:///test/large.rho"))

;; =============================================================================
;; Benchmark Definitions
;; =============================================================================

(defn noop-benchmark
  "No-op benchmark for baseline timing overhead measurement."
  []
  nil)

(defn datascript-diagnostics-by-uri-benchmark
  "Benchmarks DataScript diagnostics-by-uri query."
  []
  (db/diagnostics-by-uri "file:///test/large.rho"))

(defn datascript-symbols-by-uri-benchmark
  "Benchmarks DataScript symbols-by-uri query."
  []
  (db/symbols-by-uri "file:///test/large.rho"))

(defn datascript-all-diagnostics-benchmark
  "Benchmarks DataScript all diagnostics query (uses or-join)."
  []
  (db/diagnostics))

(defn datascript-all-symbols-benchmark
  "Benchmarks DataScript all symbols query (uses or-join)."
  []
  (db/symbols))

(defn datascript-document-lookup-benchmark
  "Benchmarks simple document lookup by URI."
  []
  (db/document-id-by-uri "file:///test/large.rho"))

(defn datascript-active-uri-benchmark
  "Benchmarks active URI query."
  []
  (db/active-uri))

(defn diagnostic-transform-benchmark
  "Benchmarks diagnostic flattening/transformation."
  []
  (let [sample-diags (for [i (range 50)]
                       {:message (str "Diagnostic " i)
                        :severity (inc (mod i 4))
                        :range {:start {:line i :character 0}
                                :end {:line i :character 10}}})]
    (db/flatten-diags sample-diags "file:///test/large.rho" 1)))

(defn symbol-flatten-benchmark
  "Benchmarks symbol flattening for nested hierarchies."
  []
  (let [make-symbol (fn [name depth]
                      {:name name
                       :kind 12
                       :range {:start {:line depth :character 0}
                               :end {:line (+ depth 10) :character 30}}
                       :selectionRange {:start {:line depth :character 0}
                                        :end {:line depth :character (count name)}}})
        nested-symbols (reduce (fn [acc i]
                                 [{:name (str "parent_" i)
                                   :kind 12
                                   :range {:start {:line (* i 20) :character 0}
                                           :end {:line (+ (* i 20) 19) :character 30}}
                                   :selectionRange {:start {:line (* i 20) :character 0}
                                                    :end {:line (* i 20) :character 10}}
                                   :children (conj acc (make-symbol (str "child_" i) (+ (* i 20) 5)))}])
                               []
                               (range 20))]
    (db/flatten-symbols nested-symbols nil "file:///test/large.rho")))

;; =============================================================================
;; Query Cache Benchmarks (EXP-006)
;; =============================================================================

(defn query-cache-active-uri-benchmark
  "Benchmarks active-uri with cache (should hit cache after warmup)."
  []
  ;; Call multiple times to exercise cache
  (dotimes [_ 5]
    (db/active-uri)))

(defn query-cache-diagnostics-benchmark
  "Benchmarks diagnostics-by-uri with cache."
  []
  ;; Call multiple times to exercise cache
  (dotimes [_ 3]
    (db/diagnostics-by-uri "file:///test/large.rho")))

(defn query-cache-symbols-benchmark
  "Benchmarks symbols-by-uri with cache."
  []
  ;; Call multiple times to exercise cache
  (dotimes [_ 3]
    (db/symbols-by-uri "file:///test/large.rho")))

(defn query-cache-mixed-workload-benchmark
  "Benchmarks a realistic mixed workload of cached queries.
   Simulates typical editor operations: checking active document,
   fetching diagnostics, and fetching symbols."
  []
  ;; Simulate a typical editor event cycle
  (let [uri (db/active-uri)]
    (when uri
      (db/diagnostics-by-uri uri)
      (db/symbols-by-uri uri))))

;; =============================================================================
;; Query Coalescence Benchmarks (EXP-007)
;; =============================================================================

(defn coalesced-active-uri-version-benchmark
  "Benchmarks coalesced active-uri-version query (single query for uri+version).
   EXP-007: Compare with sequential active-uri + active-version."
  []
  (db/active-uri-version))

(defn coalesced-active-uri-text-lang-version-benchmark
  "Benchmarks coalesced active-uri-text-lang-version query.
   EXP-007: Compare with sequential queries for active document context."
  []
  (db/active-uri-text-lang-version))

(defn coalesced-doc-text-lang-version-benchmark
  "Benchmarks coalesced doc-text-lang-version-by-uri query.
   EXP-007: Compare with sequential doc-text-lang + doc-id-version queries."
  []
  (db/doc-text-lang-version-by-uri "file:///test/large.rho"))

(defn sequential-active-uri-version-benchmark
  "Benchmarks sequential active-uri + active-version queries (baseline for EXP-007).
   This simulates the old pattern before coalescence."
  []
  (let [uri (db/active-uri)
        version (db/active-version)]
    [uri version]))

(defn sequential-active-document-benchmark
  "Benchmarks sequential queries for active document context (baseline for EXP-007).
   Simulates the old coeffect pattern: active-uri -> doc-text-lang -> doc-id-version."
  []
  (let [uri (db/active-uri)]
    (when uri
      (let [[text lang] (db/doc-text-lang-by-uri uri)
            [_ version] (db/document-id-version-by-uri uri)]
        {:uri uri :text text :language lang :version version}))))

(defn sequential-document-lookup-benchmark
  "Benchmarks sequential queries for document lookup (baseline for EXP-007).
   Simulates the old coeffect pattern: doc-text-lang + doc-id-version."
  []
  (let [[text lang] (db/doc-text-lang-by-uri "file:///test/large.rho")
        [_ version] (db/document-id-version-by-uri "file:///test/large.rho")]
    {:text text :language lang :version version}))

;; =============================================================================
;; Debounce Timing Benchmarks (EXP-008)
;; =============================================================================
;;
;; These benchmarks measure the performance of the centralized debounce system.
;; Key metrics:
;; - Debounce call overhead: Time to schedule a debounced call
;; - Max-wait enforcement: Verify events fire within max-wait limits
;; - Consolidation ratio: How many events are consolidated

(defn debounce-call-overhead-benchmark
  "Measures the overhead of scheduling a debounced call.
   This benchmark does NOT wait for the callback to execute -
   it only measures the synchronous scheduling overhead."
  []
  ;; Cancel any pending calls from previous benchmark
  (debounce/cancel-all)
  ;; Schedule a debounced call (measuring just the scheduling overhead)
  (debounce/debounced-call
   :benchmark-overhead
   #(identity nil)
   100
   {}))

(defn debounce-cancel-overhead-benchmark
  "Measures the overhead of cancelling a debounced call."
  []
  ;; First schedule a call
  (debounce/debounced-call
   :benchmark-cancel
   #(identity nil)
   1000
   {})
  ;; Then cancel it (this is what we're measuring)
  (debounce/cancel :benchmark-cancel))

(defn debounce-key-lookup-benchmark
  "Measures the overhead of key-based deduplication.
   Simulates rapid calls with the same key."
  []
  ;; Multiple calls with the same key - measures timer cancellation + rescheduling
  (dotimes [_ 10]
    (debounce/debounced-call
     :benchmark-dedup
     #(identity nil)
     100
     {}))
  ;; Clean up
  (debounce/cancel :benchmark-dedup))

(defn debounce-multiple-keys-benchmark
  "Measures handling of multiple independent debounce keys.
   Simulates real-world scenario with different event types."
  []
  ;; Cancel any existing
  (debounce/cancel-all)
  ;; Schedule calls for different keys
  (doseq [i (range 10)]
    (debounce/debounced-call
     (keyword (str "benchmark-key-" i))
     #(identity nil)
     100
     {}))
  ;; Cancel all
  (debounce/cancel-all))

(defn debounce-max-wait-check-benchmark
  "Measures the overhead of max-wait calculation.
   When max-wait is provided, additional timestamp checks are performed."
  []
  (debounce/cancel-all)
  ;; Call with max-wait option
  (debounce/debounced-call
   :benchmark-max-wait
   #(identity nil)
   100
   {:max-wait 500})
  ;; Call again (triggers max-wait check)
  (debounce/debounced-call
   :benchmark-max-wait
   #(identity nil)
   100
   {:max-wait 500})
  ;; Clean up
  (debounce/cancel :benchmark-max-wait))

(defn debounce-leading-edge-benchmark
  "Measures leading-edge execution overhead.
   Leading edge executes immediately on first call."
  []
  (debounce/cancel-all)
  (let [executed (atom false)]
    (debounce/debounced-call
     :benchmark-leading
     #(reset! executed true)
     100
     {:leading? true})
    ;; Verify it executed
    @executed)
  (debounce/cancel :benchmark-leading))

(defn debounce-throttle-benchmark
  "Measures throttled call overhead.
   Throttle guarantees regular execution during continuous calls."
  []
  (debounce/cancel-all)
  (dotimes [_ 5]
    (debounce/throttled-call
     :benchmark-throttle
     #(identity nil)
     100))
  (debounce/cancel :benchmark-throttle))

;; Async debounce benchmark for measuring actual latency
(defn debounce-latency-benchmark
  "Measures actual end-to-end latency of debounced execution.
   This async benchmark times from call to callback execution."
  []
  (js/Promise.
   (fn [resolve _]
     (debounce/cancel-all)
     (let [start (js/performance.now)]
       (debounce/debounced-call
        :benchmark-latency
        (fn []
          (let [end (js/performance.now)]
            (resolve (- end start))))
        50  ; 50ms debounce delay
        {})))))

(defn debounce-max-wait-latency-benchmark
  "Measures latency when max-wait forces execution.
   Simulates continuous typing scenario where max-wait ensures updates."
  []
  (js/Promise.
   (fn [resolve _]
     (debounce/cancel-all)
     (let [start (atom nil)
           call-count (atom 0)]
       ;; Simulate continuous calls that would keep delaying
       ;; but max-wait should force execution within 200ms
       (letfn [(make-call []
                 (when (nil? @start)
                   (reset! start (js/performance.now)))
                 (swap! call-count inc)
                 (debounce/debounced-call
                  :benchmark-max-wait-latency
                  (fn []
                    (let [end (js/performance.now)
                          latency (- end @start)]
                      (resolve {:latency-ms latency
                                :call-count @call-count})))
                  100  ; 100ms debounce (would keep delaying)
                  {:max-wait 200}))]  ; but max-wait forces execution at 200ms
         ;; Make rapid calls every 50ms for 300ms
         (make-call)
         (js/setTimeout make-call 50)
         (js/setTimeout make-call 100)
         (js/setTimeout make-call 150)
         (js/setTimeout make-call 200)
         (js/setTimeout make-call 250))))))

;; =============================================================================
;; Syntax Initialization Benchmark
;; =============================================================================
;;
;; NOTE: This benchmark is currently disabled in the default suite because it
;; requires WASM files that aren't available in the isolated benchmark environment.
;; The key optimization in EXP-004 (removing the 100ms timeout) saves 100ms
;; per syntax initialization. This can be verified by manual testing with the
;; full demo environment.
;;
;; To re-enable: add {:name :syntax-init :fn syntax-init-benchmark :async? true}
;; to the benchmark-suite vector after copying the required WASM files to
;; resources/public/benchmark/js/

(defn syntax-init-benchmark
  "Benchmarks syntax initialization time (WASM loading, parser setup, etc.).
   This is an async benchmark that measures the full syntax init pipeline.

   DISABLED: Requires WASM files to be available. The 100ms timeout was removed
   in EXP-004, which saves 100ms per syntax initialization.

   Note: Uses cached WASM after first run, so mainly measures query compilation."
  []
  ;; Create a promise that resolves when syntax init completes
  (js/Promise.
   (fn [resolve reject]
     ;; Check if WASM files are likely available
     (-> (js/fetch "/js/tree-sitter.wasm" #js {:method "HEAD"})
         (.then (fn [resp]
                  (if (.-ok resp)
                    ;; WASM available, proceed with benchmark
                    (let [mock-state (atom {:languages {"rholang" {:grammar-wasm "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
                                                                   :highlights-query-path "/extensions/lang/rholang/tree-sitter/highlights.scm"
                                                                   :indents-query-path "/extensions/lang/rholang/tree-sitter/indents.scm"
                                                                   :indent-size 2}}
                                            :tree-sitter-wasm "/js/tree-sitter.wasm"})]
                      ;; Clear cached language to force re-init
                      (when-let [langs-atom (some-> syntax/languages)]
                        (swap! langs-atom dissoc "rholang"))

                      (go
                        (let [result (<! (syntax/init-syntax nil mock-state))]
                          (resolve result))))
                    ;; WASM not available, skip benchmark
                    (do
                      (log/warn "Syntax init benchmark skipped: WASM files not available")
                      (resolve [:skipped :wasm-not-available])))))
         (.catch (fn [e]
                   (log/warn "Syntax init benchmark skipped due to error:" (.-message e))
                   (resolve [:skipped :error])))))))

;; =============================================================================
;; Scroll Performance Benchmark (EXP-005a)
;; =============================================================================
;;
;; This benchmark validates the EXP-005 viewport-aware highlight caching
;; optimization. It measures:
;; - Frame time during scrolling
;; - Cache hit/miss ratio
;; - Decoration rebuild frequency
;; - Tree-Sitter query count
;;
;; The benchmark creates a CodeMirror editor with a large document, initializes
;; syntax highlighting, then simulates scrolling through the document while
;; measuring performance metrics.

;; Editor instance for scroll benchmarks (created during setup)
(defonce ^:private scroll-benchmark-editor (atom nil))
(defonce ^:private scroll-benchmark-container (atom nil))

(defn setup-scroll-benchmark!
  "Sets up the scroll benchmark environment.
   Creates a CodeMirror editor with syntax highlighting enabled."
  []
  (js/Promise.
   (fn [resolve reject]
     (try
       ;; Check if WASM files are available
       (-> (js/fetch "/js/tree-sitter.wasm" #js {:method "HEAD"})
           (.then (fn [resp]
                    (if (.-ok resp)
                      (do
                        (js/console.log "[ScrollBench] WASM files available, setting up editor")
                        ;; Create a container for the editor
                        (let [container (js/document.createElement "div")]
                          (set! (.-id container) "scroll-benchmark-container")
                          (set! (.. container -style -position) "absolute")
                          (set! (.. container -style -left) "-9999px")
                          (set! (.. container -style -width) "800px")
                          (set! (.. container -style -height) "600px")
                          (set! (.. container -style -overflow) "hidden")
                          (js/document.body.appendChild container)
                          (reset! scroll-benchmark-container container)

                          ;; Generate 10K lines of Rholang code
                          (let [doc-content @sample-10k-lines
                                ;; Create a test document in the database for scroll benchmark
                                scroll-uri "file:///benchmark/scroll.rho"
                                _ (db/create-documents!
                                   [{:uri scroll-uri
                                     :text doc-content
                                     :language "rholang"
                                     :version 1
                                     :dirty false
                                     :opened true}])
                                ;; Set it as the active document
                                _ (db/update-active-uri! scroll-uri)
                                ;; Create editor state with syntax compartment for reconfiguration
                                state (.create EditorState #js {:doc doc-content
                                                                :extensions #js [(.of syntax/syntax-compartment #js [])]})
                                view (EditorView. #js {:state state
                                                       :parent container})]
                            (reset! scroll-benchmark-editor view)

                            ;; Initialize syntax highlighting
                            (let [mock-state (atom {:languages {"rholang" {:grammar-wasm "/extensions/lang/rholang/tree-sitter/tree-sitter-rholang.wasm"
                                                                           :highlights-query-path "/extensions/lang/rholang/tree-sitter/highlights.scm"
                                                                           :indents-query-path "/extensions/lang/rholang/tree-sitter/indents.scm"
                                                                           :indent-size 2}}
                                                    :tree-sitter-wasm "/js/tree-sitter.wasm"})]
                              (go
                                (let [result (<! (syntax/init-syntax view mock-state))]
                                  (js/console.log "[ScrollBench] Syntax init result:" (clj->js result))
                                  ;; Wait for syntax to be fully applied
                                  (<! (timeout 500))
                                  (resolve {:status :ready :view view})))))))
                      (do
                        (js/console.log "[ScrollBench] WASM files not available, skipping")
                        (resolve {:status :skipped :reason :wasm-not-available})))))
           (.catch (fn [e]
                     (js/console.error "[ScrollBench] Setup error:" (.-message e))
                     (resolve {:status :skipped :reason :error}))))
       (catch js/Error e
         (js/console.error "[ScrollBench] Setup exception:" (.-message e))
         (reject e))))))

(defn teardown-scroll-benchmark!
  "Cleans up the scroll benchmark environment."
  []
  (when-let [^js view @scroll-benchmark-editor]
    (.destroy view)
    (reset! scroll-benchmark-editor nil))
  (when-let [container @scroll-benchmark-container]
    (when-let [parent (.-parentNode container)]
      (.removeChild parent container))
    (reset! scroll-benchmark-container nil)))

(defn simulate-scroll
  "Simulates scrolling to a specific line in the editor.
   Returns a promise that resolves after the viewport update."
  [^js view target-line]
  (js/Promise.
   (fn [resolve _]
     (let [doc (.. view -state -doc)
           max-lines (.-lines doc)
           safe-line (min (max 1 target-line) max-lines)
           line-info (.line doc safe-line)
           pos (.-from line-info)]
       ;; Dispatch scroll effect
       (.dispatch view #js {:effects (.scrollIntoView EditorView pos #js {:y "start"})})
       ;; Wait for next frame to let viewport update
       (js/requestAnimationFrame
        (fn []
          (resolve {:scrolled-to safe-line
                    :viewport-from (.. view -viewport -from)
                    :viewport-to (.. view -viewport -to)})))))))

(defn measure-scroll-frame
  "Measures a single scroll operation's performance.
   Returns {:frame-time-ms N :viewport ...}."
  [^js view target-line]
  (js/Promise.
   (fn [resolve _]
     (let [start (js/performance.now)]
       (-> (simulate-scroll view target-line)
           (.then (fn [scroll-result]
                    (let [end (js/performance.now)]
                      (resolve (assoc scroll-result
                                      :frame-time-ms (- end start)))))))))))

(defn scroll-performance-benchmark
  "Benchmarks scroll performance with EXP-005 viewport caching.

   Performs 50 scroll operations and measures:
   - Average frame time
   - Cache hit ratio
   - Total decoration rebuilds
   - Total Tree-Sitter queries

   Returns a promise that resolves with scroll metrics."
  []
  (js/Promise.
   (fn [resolve reject]
     (if-let [view @scroll-benchmark-editor]
       (do
         ;; Reset cache stats before benchmark
         (syntax/reset-cache-stats!)

         (go
           (try
             (let [num-scrolls 50
                   doc (.. view -state -doc)
                   max-lines (.-lines doc)
                   ;; Generate scroll positions: alternating small and large jumps
                   scroll-positions (for [i (range num-scrolls)]
                                      (let [base (* i (quot max-lines num-scrolls))]
                                        (if (even? i)
                                          ;; Small scroll (within cache margin)
                                          (+ base (rand-int 50))
                                          ;; Large scroll (likely cache miss)
                                          (+ base 200 (rand-int 100)))))
                   ;; Measure each scroll
                   frame-times (atom [])
                   _ (doseq [target-line scroll-positions]
                       (let [result (<p! (measure-scroll-frame view target-line))]
                         (swap! frame-times conj (:frame-time-ms result))
                         ;; Small delay between scrolls to simulate real scrolling
                         (<! (timeout 16))))  ; ~60fps

                   ;; Collect results
                   times @frame-times
                   cache-stats (syntax/get-cache-stats)
                   total-ops (+ (:hits cache-stats) (:misses cache-stats))
                   hit-ratio (if (pos? total-ops)
                               (/ (:hits cache-stats) total-ops)
                               0)]

               (js/console.log "[ScrollBench] Results:"
                               "scrolls=" num-scrolls
                               "hits=" (:hits cache-stats)
                               "misses=" (:misses cache-stats)
                               "queries=" (:queries cache-stats))

               (resolve {:scroll-count num-scrolls
                         :frame-times times
                         :mean-frame-time-ms (stats/mean times)
                         :p95-frame-time-ms (stats/percentile times 0.95)
                         :cache-hits (:hits cache-stats)
                         :cache-misses (:misses cache-stats)
                         :cache-hit-ratio hit-ratio
                         :total-rebuilds (:rebuilds cache-stats)
                         :total-queries (:queries cache-stats)}))
             (catch js/Error e
               (js/console.error "[ScrollBench] Error:" (.-message e))
               (reject e)))))
       ;; No editor available
       (resolve {:status :skipped
                 :reason :editor-not-available
                 :message "Scroll benchmark requires setup. Run setup-scroll-benchmark! first."})))))

(defn scroll-frame-time-benchmark
  "Simplified scroll benchmark that measures just frame times.
   This benchmark can be run via the standard runner for statistical analysis."
  []
  (js/Promise.
   (fn [resolve reject]
     (if-let [view @scroll-benchmark-editor]
       (let [doc (.. view -state -doc)
             max-lines (.-lines doc)
             ;; Random line within middle 80% of document
             target-line (+ (quot max-lines 10)
                            (rand-int (quot (* max-lines 8) 10)))]
         (-> (measure-scroll-frame view target-line)
             (.then (fn [result]
                      (resolve (:frame-time-ms result))))
             (.catch reject)))
       (resolve 0)))))  ; Skip if editor not available

;; =============================================================================
;; Benchmark Suite Definition
;; =============================================================================

(def benchmark-suite
  "The default benchmark suite for Lightning Bug."
  [;; Timing overhead baseline
   {:name :timing-overhead
    :fn noop-benchmark
    :async? false}

   ;; DataScript query benchmarks
   {:name :datascript-diagnostics-by-uri
    :fn datascript-diagnostics-by-uri-benchmark
    :async? false}

   {:name :datascript-symbols-by-uri
    :fn datascript-symbols-by-uri-benchmark
    :async? false}

   {:name :datascript-all-diagnostics
    :fn datascript-all-diagnostics-benchmark
    :async? false}

   {:name :datascript-all-symbols
    :fn datascript-all-symbols-benchmark
    :async? false}

   {:name :datascript-document-lookup
    :fn datascript-document-lookup-benchmark
    :async? false}

   {:name :datascript-active-uri
    :fn datascript-active-uri-benchmark
    :async? false}

   ;; Transformation benchmarks
   {:name :diagnostic-transform
    :fn diagnostic-transform-benchmark
    :async? false}

   {:name :symbol-flatten
    :fn symbol-flatten-benchmark
    :async? false}

   ;; EXP-006: Query cache benchmarks
   {:name :query-cache-active-uri
    :fn query-cache-active-uri-benchmark
    :async? false}

   {:name :query-cache-diagnostics
    :fn query-cache-diagnostics-benchmark
    :async? false}

   {:name :query-cache-symbols
    :fn query-cache-symbols-benchmark
    :async? false}

   {:name :query-cache-mixed-workload
    :fn query-cache-mixed-workload-benchmark
    :async? false}

   ;; EXP-007: Query coalescence benchmarks
   {:name :coalesced-active-uri-version
    :fn coalesced-active-uri-version-benchmark
    :async? false}

   {:name :coalesced-active-uri-text-lang-version
    :fn coalesced-active-uri-text-lang-version-benchmark
    :async? false}

   {:name :coalesced-doc-text-lang-version
    :fn coalesced-doc-text-lang-version-benchmark
    :async? false}

   {:name :sequential-active-uri-version
    :fn sequential-active-uri-version-benchmark
    :async? false}

   {:name :sequential-active-document
    :fn sequential-active-document-benchmark
    :async? false}

   {:name :sequential-document-lookup
    :fn sequential-document-lookup-benchmark
    :async? false}

   ;; EXP-008: Debounce timing benchmarks
   {:name :debounce-call-overhead
    :fn debounce-call-overhead-benchmark
    :async? false}

   {:name :debounce-cancel-overhead
    :fn debounce-cancel-overhead-benchmark
    :async? false}

   {:name :debounce-key-lookup
    :fn debounce-key-lookup-benchmark
    :async? false}

   {:name :debounce-multiple-keys
    :fn debounce-multiple-keys-benchmark
    :async? false}

   {:name :debounce-max-wait-check
    :fn debounce-max-wait-check-benchmark
    :async? false}

   {:name :debounce-leading-edge
    :fn debounce-leading-edge-benchmark
    :async? false}

   {:name :debounce-throttle
    :fn debounce-throttle-benchmark
    :async? false}

   ;; NOTE: Async debounce latency benchmarks are disabled by default
   ;; because they add significant time to the benchmark suite.
   ;; Uncomment to enable for latency testing:
   ;; {:name :debounce-latency
   ;;  :fn debounce-latency-benchmark
   ;;  :async? true}
   ;; {:name :debounce-max-wait-latency
   ;;  :fn debounce-max-wait-latency-benchmark
   ;;  :async? true}

   ;; NOTE: syntax-init benchmark is disabled because it requires WASM files.
   ;; The 100ms timeout removal in EXP-004 saves 100ms per syntax init.
   ;; Uncomment to enable if WASM files are available:
   ;; {:name :syntax-init
   ;;  :fn syntax-init-benchmark
   ;;  :async? true}

   ;; NOTE: scroll-performance benchmark is disabled by default.
   ;; It requires WASM files and has its own setup/teardown lifecycle.
   ;; Use run-scroll-benchmark! for scroll performance testing.
   ;; Uncomment to enable if WASM files are available:
   ;; {:name :scroll-frame-time
   ;;  :fn scroll-frame-time-benchmark
   ;;  :async? true
   ;;  :setup-fn setup-scroll-benchmark!
   ;;  :teardown-fn teardown-scroll-benchmark!}
   ])

;; =============================================================================
;; Running Benchmarks
;; =============================================================================

(defn run-all-benchmarks!
  "Runs all benchmarks and returns results.

   Options:
   - :config - benchmark configuration (warmup, iterations, etc.)
   - :setup? - whether to setup test data (default true)"
  ([]
   (run-all-benchmarks! {}))
  ([{:keys [config setup?] :or {setup? true}}]
   (go
     (try
       (when setup?
         (js/console.log "[Benchmark] Setting up test data...")
         (setup-test-db!)
         (js/console.log "[Benchmark] Test data setup complete")
         (<! (timeout 100)))

       (js/console.log "[Benchmark] Running benchmark suite...")
       (let [cfg (merge runner/DEFAULT-CONFIG config)
             result (<p! (runner/run-suite benchmark-suite cfg))]
         (js/console.log "[Benchmark] Benchmark suite complete!")
         result)
       (catch js/Error e
         (js/console.error "[Benchmark] Error in run-all-benchmarks:" (.-message e))
         (js/console.error "[Benchmark] Stack:" (.-stack e))
         {:error (.-message e) :benchmarks []})))))

(defn run-single-benchmark!
  "Runs a single benchmark by name."
  [benchmark-name]
  (if-let [bench (first (filter #(= benchmark-name (:name %)) benchmark-suite))]
    (if (:async? bench)
      (runner/run-benchmark-async benchmark-name (:fn bench))
      (runner/run-benchmark-sync benchmark-name (:fn bench)))
    (do
      (log/error "Unknown benchmark:" benchmark-name)
      nil)))

;; =============================================================================
;; Quick Test Mode (for development)
;; =============================================================================

(defn quick-test!
  "Runs benchmarks with reduced iterations for quick testing."
  []
  (run-all-benchmarks!
   {:config {:warmup-iterations 3
             :measurement-iterations 10
             :delay-between-iterations-ms 5}}))

;; =============================================================================
;; Export for Browser/Node
;; =============================================================================

;; =============================================================================
;; Automation State (for headless runner)
;; =============================================================================

(defonce ^:private benchmark-complete (atom false))
(defonce ^:private current-results (atom nil))

;; =============================================================================
;; Export for Browser/Node
;; =============================================================================

(defn ^:export runBenchmarks
  "Entry point for running benchmarks from JavaScript.
   Sets window.benchmarkComplete to true when done.
   Stores results in window.currentResults."
  []
  (js/console.log "Benchmark suite started")
  (reset! benchmark-complete false)
  (reset! current-results nil)
  (when (exists? js/window)
    (set! (.-benchmarkComplete js/window) false)
    (set! (.-currentResults js/window) nil))

  (go
    (try
      (let [result (<! (run-all-benchmarks!))]
        (reset! current-results result)
        (reset! benchmark-complete true)
        (when (exists? js/window)
          (set! (.-currentResults js/window) (clj->js result))
          (set! (.-benchmarkComplete js/window) true))
        (js/console.log "Benchmark suite complete")
        result)
      (catch js/Error e
        (js/console.error "Benchmark error:" (.-message e))
        (js/console.error "Stack:" (.-stack e))
        (reset! benchmark-complete true)
        (when (exists? js/window)
          (set! (.-benchmarkComplete js/window) true)
          (set! (.-currentResults js/window) #js {:error (.-message e)}))
        nil))))

(defn ^:export runQuickTest
  "Entry point for quick benchmark testing from JavaScript.
   Sets window.benchmarkComplete to true when done."
  []
  (js/console.log "Quick benchmark test started")
  (reset! benchmark-complete false)
  (reset! current-results nil)
  (when (exists? js/window)
    (set! (.-benchmarkComplete js/window) false)
    (set! (.-currentResults js/window) nil))

  (go
    (try
      (let [result (<! (quick-test!))]
        (reset! current-results result)
        (reset! benchmark-complete true)
        (when (exists? js/window)
          (set! (.-currentResults js/window) (clj->js result))
          (set! (.-benchmarkComplete js/window) true))
        (js/console.log "Quick benchmark test complete")
        result)
      (catch js/Error e
        (js/console.error "Quick test error:" (.-message e))
        (js/console.error "Stack:" (.-stack e))
        (reset! benchmark-complete true)
        (when (exists? js/window)
          (set! (.-benchmarkComplete js/window) true)
          (set! (.-currentResults js/window) #js {:error (.-message e)}))
        nil))))

(defn ^:export getResults
  "Returns benchmark results as a JavaScript object."
  []
  (clj->js (or @current-results (runner/get-results))))

(defn ^:export exportResults
  "Exports results to a downloadable JSON file."
  [filename]
  (runner/export-results filename))

(defn ^:export isComplete
  "Returns true if benchmarks have completed."
  []
  @benchmark-complete)

(defn ^:export reset
  "Resets benchmark state for a new run."
  []
  (reset! benchmark-complete false)
  (reset! current-results nil)
  (runner/clear-results!)
  (when (exists? js/window)
    (set! (.-benchmarkComplete js/window) false)
    (set! (.-currentResults js/window) nil))
  (js/console.log "Benchmark state reset"))

;; =============================================================================
;; Scroll Performance Benchmark Exports (EXP-005a)
;; =============================================================================

(defn ^:export runScrollBenchmark
  "Entry point for running scroll performance benchmark from JavaScript.
   This is a dedicated benchmark for validating EXP-005 viewport caching.

   Returns a promise that resolves with scroll metrics including:
   - scroll-count: Number of scrolls performed
   - mean-frame-time-ms: Average frame time during scroll
   - p95-frame-time-ms: 95th percentile frame time
   - cache-hits: Number of cache hits
   - cache-misses: Number of cache misses
   - cache-hit-ratio: Hit ratio (0-1)
   - total-rebuilds: Total decoration rebuilds
   - total-queries: Total Tree-Sitter queries"
  []
  (js/console.log "Scroll performance benchmark started")
  (js/Promise.
   (fn [resolve reject]
     (go
       (try
         ;; Setup scroll benchmark environment
         (js/console.log "[ScrollBench] Setting up environment...")
         (let [setup-result (<p! (setup-scroll-benchmark!))]
           (if (= :skipped (:status setup-result))
             (do
               (js/console.log "[ScrollBench] Skipped:" (:reason setup-result))
               (resolve (clj->js setup-result)))
             (do
               (js/console.log "[ScrollBench] Environment ready, running benchmark...")
               (let [result (<p! (scroll-performance-benchmark))]
                 ;; Cleanup
                 (teardown-scroll-benchmark!)
                 (js/console.log "Scroll performance benchmark complete")
                 (resolve (clj->js result))))))
         (catch js/Error e
           (js/console.error "[ScrollBench] Error:" (.-message e))
           (teardown-scroll-benchmark!)
           (reject e)))))))

(defn ^:export getCacheStats
  "Returns current cache statistics from the syntax highlighting system."
  []
  (syntax/getCacheStats))

(defn ^:export resetCacheStats
  "Resets cache statistics to zero."
  []
  (syntax/resetCacheStats))

;; =============================================================================
;; Query Cache Statistics (EXP-006)
;; =============================================================================

(defn ^:export getQueryCacheStats
  "Returns current query cache statistics.

   Returns a JavaScript object with:
   - hits: Number of cache hits
   - misses: Number of cache misses
   - evictions: Number of cache evictions
   - hitRate: Cache hit rate percentage (0-100)
   - entryCount: Current number of entries in cache
   - maxEntries: Maximum cache size"
  []
  (clj->js (qc/get-stats)))

(defn ^:export resetQueryCacheStats
  "Resets query cache statistics to zero."
  []
  (qc/reset-stats!))

(defn ^:export invalidateQueryCache
  "Invalidates all query cache entries."
  []
  (qc/invalidate-all!))

;; =============================================================================
;; Module Initialization
;; =============================================================================

(defn ^:export init
  "Module initialization function. Sets up exports without running benchmarks."
  []
  (js/console.log "Benchmark module initialized")
  ;; Set up window properties for headless automation
  (when (exists? js/window)
    (set! (.-benchmarkComplete js/window) false)
    (set! (.-currentResults js/window) nil)
    (set! (.-benchmarkProgress js/window) #js {:current 0 :total 0 :currentName nil})))
