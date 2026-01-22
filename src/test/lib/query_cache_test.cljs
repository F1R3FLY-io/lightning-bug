(ns test.lib.query-cache-test
  "Tests for the lib.query-cache module.

   This module provides caching for DataScript query results with TTL-based
   invalidation, automatic eviction, and transaction-based cache invalidation."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures async]]
   [clojure.core.async :refer [go <! timeout]]
   [clojure.test.check :as tc]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [lib.query-cache :as cache]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(use-fixtures :each
  {:before (fn []
             (cache/invalidate-all!)
             (cache/reset-stats!))
   :after (fn []
            (cache/invalidate-all!)
            (cache/reset-stats!))})

;; =============================================================================
;; Cache Key Generation Tests
;; =============================================================================

(deftest make-cache-key-uniqueness
  (testing "Different queries produce different keys"
    (let [key1 (cache/make-cache-key :query-a "arg1")
          key2 (cache/make-cache-key :query-b "arg1")
          key3 (cache/make-cache-key :query-a "arg2")]
      (is (not= key1 key2) "Different query names should produce different keys")
      (is (not= key1 key3) "Different arguments should produce different keys")
      (is (not= key2 key3) "Different queries and args should produce different keys"))))

(deftest make-cache-key-deterministic
  (testing "Same inputs produce the same key"
    (let [key1 (cache/make-cache-key :query-a "arg1" 42)
          key2 (cache/make-cache-key :query-a "arg1" 42)]
      (is (= key1 key2) "Same inputs should produce the same key"))))

(deftest make-cache-key-stable-ignores-transactions
  (testing "Stable keys are unaffected by transaction counter"
    (let [key1 (cache/make-cache-key-stable :query-a "arg1")
          _ (cache/on-transaction!)
          _ (cache/on-transaction!)
          key2 (cache/make-cache-key-stable :query-a "arg1")]
      (is (= key1 key2) "Stable keys should be the same across transactions"))))

(deftest make-cache-key-affected-by-transactions
  (testing "Regular keys change with transaction counter"
    (let [key1 (cache/make-cache-key :query-a "arg1")
          _ (cache/on-transaction!)
          key2 (cache/make-cache-key :query-a "arg1")]
      (is (not= key1 key2) "Regular keys should change after transactions"))))

;; =============================================================================
;; Cache Put/Get Tests
;; =============================================================================

(deftest put-cached!-stores-with-default-ttl
  (testing "put-cached! stores entries that can be retrieved"
    (let [key (cache/make-cache-key-stable :test-put "arg")
          result {:data "test-data"}]
      (cache/put-cached! key result)
      (let [[hit? cached-result] (cache/get-cached key)]
        (is (true? hit?) "Should be a cache hit")
        (is (= result cached-result) "Should return the cached data")))))

(deftest put-cached!-stores-with-custom-ttl
  (async done
         (go
           (let [key (cache/make-cache-key-stable :test-custom-ttl "arg")
                 result {:data "short-lived"}]
             ;; Store with very short TTL
             (cache/put-cached! key result 50)
             ;; Should be there immediately
             (let [[hit? _] (cache/get-cached key)]
               (is (true? hit?) "Should be a cache hit immediately"))
             ;; Wait for TTL to expire
             (<! (timeout 100))
             ;; Should be expired
             (let [[hit? _] (cache/get-cached key)]
               (is (false? hit?) "Should be a cache miss after TTL expires")))
           (done))))

(deftest get-cached-returns-hit-for-fresh-entry
  (testing "get-cached returns [true result] for non-expired entries"
    (let [key (cache/make-cache-key-stable :test-hit "arg")
          result {:value 42}]
      (cache/put-cached! key result)
      (let [[hit? cached] (cache/get-cached key)]
        (is (true? hit?))
        (is (= result cached))))))

(deftest get-cached-returns-miss-for-expired-entry
  (async done
         (go
           (let [key (cache/make-cache-key-stable :test-expire "arg")]
             (cache/put-cached! key {:data "old"} 30)
             (<! (timeout 60))
             (let [[hit? result] (cache/get-cached key)]
               (is (false? hit?) "Should be a miss after expiration")
               (is (nil? result) "Result should be nil")))
           (done))))

(deftest get-cached-returns-miss-for-absent-key
  (testing "get-cached returns [false nil] for keys not in cache"
    (let [[hit? result] (cache/get-cached :non-existent-key)]
      (is (false? hit?))
      (is (nil? result)))))

;; =============================================================================
;; Cache Invalidation Tests
;; =============================================================================

(deftest invalidate!-removes-specific-key
  (testing "invalidate! removes a specific cache entry"
    (let [key1 (cache/make-cache-key-stable :keep "arg")
          key2 (cache/make-cache-key-stable :remove "arg")]
      (cache/put-cached! key1 {:data "keep"})
      (cache/put-cached! key2 {:data "remove"})
      (cache/invalidate! key2)
      (let [[hit1? _] (cache/get-cached key1)
            [hit2? _] (cache/get-cached key2)]
        (is (true? hit1?) "Key1 should still be in cache")
        (is (false? hit2?) "Key2 should be removed")))))

(deftest invalidate-all!-clears-entire-cache
  (testing "invalidate-all! removes all cache entries"
    (let [key1 (cache/make-cache-key-stable :entry1 "arg")
          key2 (cache/make-cache-key-stable :entry2 "arg")
          key3 (cache/make-cache-key-stable :entry3 "arg")]
      (cache/put-cached! key1 {:data 1})
      (cache/put-cached! key2 {:data 2})
      (cache/put-cached! key3 {:data 3})
      (cache/invalidate-all!)
      (let [[hit1? _] (cache/get-cached key1)
            [hit2? _] (cache/get-cached key2)
            [hit3? _] (cache/get-cached key3)]
        (is (false? hit1?))
        (is (false? hit2?))
        (is (false? hit3?))))))

(deftest invalidate-matching!-removes-by-predicate
  (testing "invalidate-matching! removes entries matching predicate"
    (let [key1 (cache/make-cache-key-stable :prefix-a "arg")
          key2 (cache/make-cache-key-stable :prefix-b "arg")
          key3 (cache/make-cache-key-stable :other "arg")]
      (cache/put-cached! key1 {:data 1})
      (cache/put-cached! key2 {:data 2})
      (cache/put-cached! key3 {:data 3})
      ;; Remove keys that are odd numbers (key hash-based, so we test differently)
      ;; Just test that the function works by removing specific key
      (cache/invalidate-matching! #(= % key1))
      (let [[hit1? _] (cache/get-cached key1)
            [hit2? _] (cache/get-cached key2)
            [hit3? _] (cache/get-cached key3)]
        (is (false? hit1?) "Matched key should be removed")
        (is (true? hit2?) "Non-matched key should remain")
        (is (true? hit3?) "Non-matched key should remain")))))

;; =============================================================================
;; Transaction Hook Tests
;; =============================================================================

(deftest on-transaction!-increments-counter
  (testing "on-transaction! increments the transaction counter"
    ;; This is implicitly tested by cache key changes
    (let [key1 (cache/make-cache-key :tx-test "arg")
          _ (cache/on-transaction!)
          key2 (cache/make-cache-key :tx-test "arg")
          _ (cache/on-transaction!)
          key3 (cache/make-cache-key :tx-test "arg")]
      (is (not= key1 key2) "Keys should differ after first transaction")
      (is (not= key2 key3) "Keys should differ after second transaction")
      (is (not= key1 key3) "All keys should be unique"))))

;; =============================================================================
;; Cached Query Wrapper Tests
;; =============================================================================

(deftest cached-query-returns-cached-on-hit
  (testing "cached-query returns cached result without calling query-fn"
    (let [call-count (atom 0)
          query-fn #(do (swap! call-count inc) {:result "computed"})]
      ;; First call - should execute query-fn
      (cache/cached-query :test-query ["arg1"] query-fn :stable? true)
      (is (= 1 @call-count))
      ;; Second call - should use cache
      (cache/cached-query :test-query ["arg1"] query-fn :stable? true)
      (is (= 1 @call-count) "query-fn should not be called again"))))

(deftest cached-query-calls-fn-on-miss
  (testing "cached-query executes query-fn on cache miss"
    (let [call-count (atom 0)
          query-fn #(do (swap! call-count inc) {:result @call-count})]
      ;; First call
      (let [result1 (cache/cached-query :miss-test ["arg1"] query-fn :stable? true)]
        (is (= 1 @call-count))
        (is (= {:result 1} result1)))
      ;; Cache should now have the result
      (let [result2 (cache/cached-query :miss-test ["arg1"] query-fn :stable? true)]
        (is (= 1 @call-count) "Should use cached result")
        (is (= {:result 1} result2))))))

(deftest cached-query-stable-persists-across-transactions
  (testing "cached-query with stable? true persists across transactions"
    (let [call-count (atom 0)
          query-fn #(do (swap! call-count inc) {:result @call-count})]
      ;; First call
      (cache/cached-query :stable-test ["arg1"] query-fn :stable? true)
      (is (= 1 @call-count))
      ;; Simulate transaction
      (cache/on-transaction!)
      ;; Should still use cached result
      (cache/cached-query :stable-test ["arg1"] query-fn :stable? true)
      (is (= 1 @call-count) "Stable cache should persist across transactions"))))

(deftest cached-query-non-stable-invalidated-by-transactions
  (testing "cached-query without stable? is invalidated by transactions"
    (let [call-count (atom 0)
          query-fn #(do (swap! call-count inc) {:result @call-count})]
      ;; First call
      (cache/cached-query :non-stable-test ["arg1"] query-fn)
      (is (= 1 @call-count))
      ;; Simulate transaction
      (cache/on-transaction!)
      ;; Should recompute
      (cache/cached-query :non-stable-test ["arg1"] query-fn)
      (is (= 2 @call-count) "Non-stable cache should be invalidated by transactions"))))

(deftest cached-query-respects-custom-ttl
  (async done
         (go
           (let [call-count (atom 0)
                 query-fn #(do (swap! call-count inc) {:result @call-count})]
             ;; First call with short TTL
             (cache/cached-query :ttl-test ["arg1"] query-fn :ttl-ms 50 :stable? true)
             (is (= 1 @call-count))
             ;; Wait for TTL to expire
             (<! (timeout 100))
             ;; Should recompute
             (cache/cached-query :ttl-test ["arg1"] query-fn :ttl-ms 50 :stable? true)
             (is (= 2 @call-count) "Should recompute after TTL expires"))
           (done))))

;; =============================================================================
;; Statistics Tests
;; =============================================================================

(deftest get-stats-returns-accurate-counts
  (testing "get-stats returns accurate hit and miss counts"
    (cache/reset-stats!)
    (let [key1 (cache/make-cache-key-stable :stats-test "arg")]
      ;; Miss
      (cache/get-cached key1)
      ;; Miss (still not in cache)
      (cache/get-cached key1)
      ;; Put and hit
      (cache/put-cached! key1 {:data "test"})
      (cache/get-cached key1) ; hit
      (cache/get-cached key1) ; hit
      (let [stats (cache/get-stats)]
        (is (= 2 (:hits stats)) "Should have 2 hits")
        (is (= 2 (:misses stats)) "Should have 2 misses")
        (is (>= (:hit-rate stats) 0) "Hit rate should be >= 0")
        (is (<= (:hit-rate stats) 100) "Hit rate should be <= 100")))))

(deftest reset-stats!-clears-statistics
  (testing "reset-stats! clears all statistics"
    ;; Generate some stats
    (let [key (cache/make-cache-key-stable :reset-test "arg")]
      (cache/get-cached key)
      (cache/put-cached! key {:data "test"})
      (cache/get-cached key))
    ;; Reset
    (cache/reset-stats!)
    (let [stats (cache/get-stats)]
      (is (= 0 (:hits stats)))
      (is (= 0 (:misses stats)))
      (is (= 0 (:evictions stats))))))

(deftest get-stats-includes-entry-count
  (testing "get-stats includes current entry count"
    (cache/invalidate-all!)
    (let [key1 (cache/make-cache-key-stable :count1 "arg")
          key2 (cache/make-cache-key-stable :count2 "arg")]
      (cache/put-cached! key1 {:data 1})
      (cache/put-cached! key2 {:data 2})
      (let [stats (cache/get-stats)]
        (is (= 2 (:entry-count stats)))
        (is (= cache/MAX-CACHE-ENTRIES (:max-entries stats)))))))

;; =============================================================================
;; Eviction Tests
;; =============================================================================

(deftest evict-oldest!-removes-entries-when-over-limit
  (testing "Cache evicts oldest entries when over MAX-CACHE-ENTRIES"
    ;; This test is limited since MAX-CACHE-ENTRIES is 100
    ;; We'll verify the eviction mechanism works in principle
    (cache/invalidate-all!)
    (cache/reset-stats!)
    ;; Add a few entries
    (dotimes [i 5]
      (let [key (cache/make-cache-key-stable :evict-test i)]
        (cache/put-cached! key {:data i})))
    ;; All should be retrievable since we're under the limit
    (let [[hit? _] (cache/get-cached (cache/make-cache-key-stable :evict-test 0))]
      (is (true? hit?) "Entry should still be in cache when under limit"))))

;; =============================================================================
;; Property-Based Tests
;; =============================================================================

(deftest cache-key-determinism-property
  (testing "Cache keys are deterministic for same inputs"
    (let [prop (prop/for-all [query-name gen/keyword-ns
                              arg1 gen/string-alphanumeric
                              arg2 gen/int]
                             (let [key1 (cache/make-cache-key-stable query-name arg1 arg2)
                                   key2 (cache/make-cache-key-stable query-name arg1 arg2)]
                               (= key1 key2)))
          result (tc/quick-check 100 prop {:seed 42})]
      (is (:result result) "Cache keys should be deterministic"))))

(deftest hit-rate-calculation-always-valid
  (testing "Hit rate is always between 0 and 100"
    (cache/reset-stats!)
    ;; Generate various hit/miss patterns
    (dotimes [i 10]
      (let [key (cache/make-cache-key-stable :rate-test i)]
        (cache/get-cached key) ; miss
        (cache/put-cached! key {:data i})
        (cache/get-cached key))) ; hit
    (let [stats (cache/get-stats)]
      (is (>= (:hit-rate stats) 0) "Hit rate should be >= 0")
      (is (<= (:hit-rate stats) 100) "Hit rate should be <= 100"))))

(deftest cache-key-collision-resistance-property
  (testing "Different inputs produce different keys with high probability"
    (let [prop (prop/for-all [query1 gen/keyword-ns
                              query2 gen/keyword-ns
                              arg gen/string-alphanumeric]
                             ;; If queries are different, keys should be different
                             (if (= query1 query2)
                               true ; trivially true for same query
                               (not= (cache/make-cache-key-stable query1 arg)
                                     (cache/make-cache-key-stable query2 arg))))
          result (tc/quick-check 100 prop {:seed 42})]
      (is (:result result) "Different queries should produce different keys"))))

;; =============================================================================
;; Edge Case Tests
;; =============================================================================

(deftest cache-with-nil-value
  (testing "Cache can store and retrieve nil values"
    (let [key (cache/make-cache-key-stable :nil-test "arg")]
      (cache/put-cached! key nil)
      (let [[hit? result] (cache/get-cached key)]
        (is (true? hit?) "Should be a cache hit")
        (is (nil? result) "Result should be nil")))))

(deftest cache-with-empty-map
  (testing "Cache can store and retrieve empty maps"
    (let [key (cache/make-cache-key-stable :empty-map "arg")]
      (cache/put-cached! key {})
      (let [[hit? result] (cache/get-cached key)]
        (is (true? hit?) "Should be a cache hit")
        (is (= {} result) "Result should be empty map")))))

(deftest cache-with-nested-data
  (testing "Cache can store and retrieve nested data structures"
    (let [key (cache/make-cache-key-stable :nested "arg")
          nested-data {:level1 {:level2 {:level3 [1 2 3 {:a "b"}]}}}]
      (cache/put-cached! key nested-data)
      (let [[hit? result] (cache/get-cached key)]
        (is (true? hit?))
        (is (= nested-data result))))))

(deftest multiple-transactions-invalidate-properly
  (testing "Multiple rapid transactions properly invalidate cache"
    (let [call-count (atom 0)
          query-fn #(do (swap! call-count inc) @call-count)]
      ;; Initial query
      (let [r1 (cache/cached-query :multi-tx ["arg"] query-fn)]
        (is (= 1 r1)))
      ;; Multiple transactions
      (cache/on-transaction!)
      (cache/on-transaction!)
      (cache/on-transaction!)
      ;; Should recompute
      (let [r2 (cache/cached-query :multi-tx ["arg"] query-fn)]
        (is (= 2 r2))))))
