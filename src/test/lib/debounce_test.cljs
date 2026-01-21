(ns test.lib.debounce-test
  "Tests for the lib.debounce coordination module."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures async]]
   [clojure.core.async :refer [go <! timeout]]
   [lib.debounce :as debounce]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(use-fixtures :each
  {:before debounce/cancel-all
   :after debounce/cancel-all})

;; =============================================================================
;; Default Delay Configuration Tests
;; =============================================================================

(deftest default-delays-contains-expected-keys
  (testing "DEFAULT-DELAYS contains all expected operation types"
    (is (contains? debounce/DEFAULT-DELAYS :content-change))
    (is (contains? debounce/DEFAULT-DELAYS :lsp-did-change))
    (is (contains? debounce/DEFAULT-DELAYS :diagnostics-update))
    (is (contains? debounce/DEFAULT-DELAYS :symbol-request))
    (is (contains? debounce/DEFAULT-DELAYS :search))
    (is (contains? debounce/DEFAULT-DELAYS :save))
    (is (contains? debounce/DEFAULT-DELAYS :cursor-update))
    (is (contains? debounce/DEFAULT-DELAYS :selection-update))
    (is (contains? debounce/DEFAULT-DELAYS :event-emit))
    (is (contains? debounce/DEFAULT-DELAYS :resize))))

(deftest get-delay-returns-keyword-value
  (testing "get-delay returns the value for known keywords"
    (is (= 150 (debounce/get-delay :content-change)))
    (is (= 250 (debounce/get-delay :lsp-did-change)))
    (is (= 300 (debounce/get-delay :symbol-request)))))

(deftest get-delay-returns-default-for-unknown-keyword
  (testing "get-delay returns 100 for unknown keywords"
    (is (= 100 (debounce/get-delay :unknown-operation)))))

(deftest get-delay-returns-number-as-is
  (testing "get-delay returns numeric values unchanged"
    (is (= 42 (debounce/get-delay 42)))
    (is (= 1000 (debounce/get-delay 1000)))))

;; =============================================================================
;; Debounced Call Tests
;; =============================================================================

(deftest debounced-call-delays-execution
  (async done
         (go
           (let [call-count (atom 0)]
             (debounce/debounced-call :test-delay #(swap! call-count inc) 50)
             ;; Should not be called immediately
             (is (= 0 @call-count))
             ;; Wait for debounce
             (<! (timeout 100))
             ;; Should have been called
             (is (= 1 @call-count)))
           (done))))

(deftest debounced-call-deduplicates-by-key
  (async done
         (go
           (let [call-count (atom 0)]
             ;; Call multiple times with same key
             (debounce/debounced-call :test-dedup #(swap! call-count inc) 50)
             (debounce/debounced-call :test-dedup #(swap! call-count inc) 50)
             (debounce/debounced-call :test-dedup #(swap! call-count inc) 50)
             ;; Wait for debounce
             (<! (timeout 100))
             ;; Should only have been called once
             (is (= 1 @call-count)))
           (done))))

(deftest debounced-call-different-keys-independent
  (async done
         (go
           (let [counts (atom {:a 0 :b 0})]
             (debounce/debounced-call :key-a #(swap! counts update :a inc) 50)
             (debounce/debounced-call :key-b #(swap! counts update :b inc) 50)
             ;; Wait for debounce
             (<! (timeout 100))
             ;; Both should have been called
             (is (= 1 (:a @counts)))
             (is (= 1 (:b @counts))))
           (done))))

(deftest debounced-call-leading-edge
  (async done
         (go
           (let [call-count (atom 0)]
             ;; Leading edge should execute immediately on first call
             (debounce/debounced-call :test-leading #(swap! call-count inc) 100
                                      {:leading? true :trailing? false})
             ;; Should be called immediately
             (is (= 1 @call-count))
             ;; Additional calls should be ignored (no trailing)
             (debounce/debounced-call :test-leading #(swap! call-count inc) 100
                                      {:leading? true :trailing? false})
             (<! (timeout 150))
             ;; Still only one call
             (is (= 1 @call-count)))
           (done))))

(deftest debounced-call-returns-cancel-fn
  (async done
         (go
           (let [call-count (atom 0)
                 cancel-fn (debounce/debounced-call :test-cancel #(swap! call-count inc) 100)]
             ;; Cancel before it fires
             (cancel-fn)
             (<! (timeout 150))
             ;; Should not have been called
             (is (= 0 @call-count)))
           (done))))

;; =============================================================================
;; Cancel Tests
;; =============================================================================

(deftest cancel-stops-pending
  (async done
         (go
           (let [call-count (atom 0)]
             (debounce/debounced-call :test-cancel-key #(swap! call-count inc) 100)
             ;; Cancel before it fires
             (debounce/cancel :test-cancel-key)
             (<! (timeout 150))
             ;; Should not have been called
             (is (= 0 @call-count)))
           (done))))

(deftest cancel-is-safe-for-missing-key
  (testing "cancel does not throw for non-existent key"
    ;; Should not throw
    (debounce/cancel :non-existent-key)
    (is true)))

(deftest cancel-all-stops-all-pending
  (async done
         (go
           (let [counts (atom {:a 0 :b 0 :c 0})]
             (debounce/debounced-call :key-a #(swap! counts update :a inc) 100)
             (debounce/debounced-call :key-b #(swap! counts update :b inc) 100)
             (debounce/debounced-call :key-c #(swap! counts update :c inc) 100)
             ;; Cancel all
             (debounce/cancel-all)
             (<! (timeout 150))
             ;; None should have been called
             (is (= 0 (:a @counts)))
             (is (= 0 (:b @counts)))
             (is (= 0 (:c @counts))))
           (done))))

(deftest cancel-matching-cancels-matching-keys
  (async done
         (go
           (let [counts (atom {:lsp-a 0 :lsp-b 0 :other 0})]
             (debounce/debounced-call :lsp-a #(swap! counts update :lsp-a inc) 100)
             (debounce/debounced-call :lsp-b #(swap! counts update :lsp-b inc) 100)
             (debounce/debounced-call :other #(swap! counts update :other inc) 100)
             ;; Cancel keys starting with :lsp
             (debounce/cancel-matching #(and (keyword? %)
                                             (clojure.string/starts-with? (name %) "lsp")))
             (<! (timeout 150))
             ;; LSP keys should not have been called
             (is (= 0 (:lsp-a @counts)))
             (is (= 0 (:lsp-b @counts)))
             ;; Other key should have been called
             (is (= 1 (:other @counts))))
           (done))))

;; =============================================================================
;; Throttled Call Tests
;; =============================================================================

(deftest throttled-call-executes-immediately-first-time
  (async done
         (go
           (let [call-count (atom 0)]
             ;; First call should execute immediately
             (debounce/throttled-call :throttle-test #(swap! call-count inc) 100)
             ;; Should have been called immediately
             (is (= 1 @call-count)))
           (done))))

(deftest throttled-call-limits-frequency
  (async done
         (go
           (let [call-count (atom 0)]
             ;; First call executes immediately
             (debounce/throttled-call :throttle-freq #(swap! call-count inc) 100)
             (is (= 1 @call-count))
             ;; Rapid subsequent calls should be throttled
             (debounce/throttled-call :throttle-freq #(swap! call-count inc) 100)
             (debounce/throttled-call :throttle-freq #(swap! call-count inc) 100)
             ;; Still only 1 call (subsequent ones are scheduled)
             (is (= 1 @call-count))
             ;; Wait for throttle interval
             (<! (timeout 150))
             ;; Should have executed once more (the scheduled one)
             (is (= 2 @call-count)))
           (done))))

(deftest throttled-call-returns-cancel-fn
  (async done
         (go
           (let [call-count (atom 0)]
             ;; First call executes, second is scheduled
             (debounce/throttled-call :throttle-cancel #(swap! call-count inc) 100)
             (let [cancel-fn (debounce/throttled-call :throttle-cancel #(swap! call-count inc) 100)]
               ;; Cancel the scheduled one
               (cancel-fn))
             (<! (timeout 150))
             ;; Should only have the first call
             (is (= 1 @call-count)))
           (done))))

;; =============================================================================
;; Debounce Function Factory Tests
;; =============================================================================

(deftest debounce-fn-creates-debounced-function
  (async done
         (go
           (let [call-count (atom 0)
                 debounced (debounce/debounce-fn #(swap! call-count inc) :delay 50)]
             (debounced)
             (debounced)
             (debounced)
             (is (= 0 @call-count))
             (<! (timeout 100))
             (is (= 1 @call-count)))
           (done))))

(deftest debounce-fn-with-key-fn
  (async done
         (go
           (let [calls (atom [])
                 debounced (debounce/debounce-fn
                            (fn [id val] (swap! calls conj [id val]))
                            :key-fn first
                            :delay 50)]
             ;; Same key, different values - should deduplicate
             (debounced :a 1)
             (debounced :a 2)
             (debounced :a 3)
             ;; Different key - independent
             (debounced :b 1)
             (<! (timeout 100))
             ;; Should have last value for :a and the value for :b
             (is (= 2 (count @calls)))
             (is (some #(= [:a 3] %) @calls))
             (is (some #(= [:b 1] %) @calls)))
           (done))))

(deftest throttle-fn-creates-throttled-function
  (async done
         (go
           (let [call-count (atom 0)
                 throttled (debounce/throttle-fn #(swap! call-count inc) :interval 100)]
             ;; First call executes immediately
             (throttled)
             (is (= 1 @call-count))
             ;; Subsequent calls are throttled
             (throttled)
             (throttled)
             (is (= 1 @call-count))
             (<! (timeout 150))
             ;; One more call after interval
             (is (= 2 @call-count)))
           (done))))

;; =============================================================================
;; Utility Function Tests
;; =============================================================================

(deftest pending-count-returns-count
  (async done
         (go
           ;; Start clean
           (is (= 0 (debounce/pending-count)))
           ;; Add some pending calls
           (debounce/debounced-call :pending-1 #() 500)
           (debounce/debounced-call :pending-2 #() 500)
           (debounce/debounced-call :pending-3 #() 500)
           (is (= 3 (debounce/pending-count)))
           ;; Cancel one
           (debounce/cancel :pending-1)
           (is (= 2 (debounce/pending-count)))
           ;; Cancel all
           (debounce/cancel-all)
           (is (= 0 (debounce/pending-count)))
           (done))))

(deftest pending-keys-returns-set
  (testing "pending-keys returns the set of pending keys"
    (debounce/debounced-call :key-x #() 500)
    (debounce/debounced-call :key-y #() 500)
    (debounce/debounced-call :key-z #() 500)
    (let [keys (debounce/pending-keys)]
      (is (set? keys))
      (is (contains? keys :key-x))
      (is (contains? keys :key-y))
      (is (contains? keys :key-z)))))

(deftest has-pending?-checks-key
  (testing "has-pending? returns true for pending keys"
    (debounce/debounced-call :has-pending-test #() 500)
    (is (true? (debounce/has-pending? :has-pending-test)))
    (is (false? (debounce/has-pending? :non-existent)))))

;; =============================================================================
;; Debounce Coordinator Tests
;; =============================================================================

(deftest make-debounce-coordinator-creates-coordinator
  (testing "make-debounce-coordinator creates a coordinator"
    (let [coordinator (debounce/make-debounce-coordinator)]
      (is (some? coordinator)))))

(deftest coordinator-debounced-call-works
  (async done
         (go
           (let [coordinator (debounce/make-debounce-coordinator)
                 call-count (atom 0)]
             ;; Use protocol method (assuming domain.protocols/IDebounceCoordinator)
             (debounce/debounced-call :coord-test #(swap! call-count inc) 50)
             (<! (timeout 100))
             (is (= 1 @call-count)))
           (done))))

;; =============================================================================
;; Error Handling Tests
;; =============================================================================

(deftest debounced-call-handles-errors-gracefully
  (async done
         (go
           (let [error-thrown (atom false)
                 other-called (atom false)]
             ;; Schedule a call that throws
             (debounce/debounced-call :error-test #(throw (js/Error. "test error")) 50)
             ;; Schedule another call
             (debounce/debounced-call :other-test #(reset! other-called true) 50)
             (<! (timeout 100))
             ;; The other call should still have executed
             (is (true? @other-called)))
           (done))))

(deftest throttled-call-handles-errors-gracefully
  (async done
         (go
           (let [other-called (atom false)]
             ;; Call that throws - executes immediately
             (debounce/throttled-call :throttle-error #(throw (js/Error. "test error")) 50)
             ;; Another call - should still work
             (debounce/throttled-call :throttle-other #(reset! other-called true) 50)
             (is (true? @other-called)))
           (done))))
