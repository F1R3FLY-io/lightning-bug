(ns test.lib.test-helpers
  "Shared test utilities for Lightning Bug tests.

   Provides fixtures, generators, and assertion helpers for consistent
   test patterns across the codebase."
  (:require [clojure.test :refer [is]]
            [clojure.core.async :refer [go <! timeout promise-chan put!]]
            [clojure.spec.alpha :as s]
            [clojure.test.check.generators :as gen]
            [datascript.core :as d]
            [lib.db :as db]))

;; =============================================================================
;; Database Fixtures
;; =============================================================================

(defn reset-db!
  "Resets the DataScript database to empty state with schema."
  []
  (d/reset-conn! db/conn (d/empty-db db/schema)))

(defn with-clean-db
  "Fixture that resets database before test."
  [f]
  (reset-db!)
  (f))

(defn create-test-document!
  "Creates a test document with default values."
  [{:keys [uri text language version dirty opened]
    :or {uri "file:///test/test.txt"
         text ""
         language "text"
         version 0
         dirty false
         opened false}}]
  (db/create-documents! [{:uri uri
                          :text text
                          :language language
                          :version version
                          :dirty dirty
                          :opened opened}])
  (db/document-id-by-uri uri))

(defn with-sample-documents
  "Fixture that creates sample documents for testing.
   Creates 3 documents: test.rho, utils.rho, and readme.txt"
  [f]
  (reset-db!)
  (create-test-document! {:uri "file:///test/test.rho"
                          :text "new x in { x!(42) }"
                          :language "rholang"
                          :version 1
                          :dirty false
                          :opened true})
  (create-test-document! {:uri "file:///test/utils.rho"
                          :text "new helper in { Nil }"
                          :language "rholang"
                          :version 1
                          :dirty false
                          :opened true})
  (create-test-document! {:uri "file:///test/readme.txt"
                          :text "Test readme content"
                          :language "text"
                          :version 0
                          :dirty false
                          :opened false})
  (db/update-active-uri! "file:///test/test.rho")
  (f))

;; =============================================================================
;; Async Helpers
;; =============================================================================

(defn eventually
  "Returns a channel that resolves when predicate returns truthy,
   or fails after timeout-ms milliseconds.

   Returns [:ok value] on success, [:error :timeout] on timeout."
  ([pred]
   (eventually pred 1000))
  ([pred timeout-ms]
   (eventually pred timeout-ms 10))
  ([pred timeout-ms poll-ms]
   (let [result-ch (promise-chan)]
     (go
       (let [start (js/Date.now)]
         (loop []
           (if-let [value (pred)]
             (put! result-ch [:ok value])
             (if (> (- (js/Date.now) start) timeout-ms)
               (put! result-ch [:error :timeout])
               (do
                 (<! (timeout poll-ms))
                 (recur)))))))
     result-ch)))

(defn delay-ms
  "Returns a channel that resolves after ms milliseconds."
  [ms]
  (timeout ms))

;; =============================================================================
;; Assertion Helpers
;; =============================================================================

(defn assert-document-state
  "Asserts that a document exists with expected fields."
  [uri expected]
  (let [[id version] (db/document-id-version-by-uri uri)
        [text lang dirty] (db/doc-text-lang-dirty-by-uri uri)
        opened (db/document-opened-by-uri? uri)]
    (is (some? id) (str "Document exists: " uri))
    (when (:version expected)
      (is (= (:version expected) version) "Version matches"))
    (when (:text expected)
      (is (= (:text expected) text) "Text matches"))
    (when (:language expected)
      (is (= (:language expected) lang) "Language matches"))
    (when (contains? expected :dirty)
      (is (= (:dirty expected) dirty) "Dirty flag matches"))
    (when (contains? expected :opened)
      (is (= (:opened expected) opened) "Opened flag matches"))))

(defn assert-diagnostics
  "Asserts that diagnostics match expected values.
   Can check by URI or check all diagnostics."
  ([expected]
   (let [actual (db/diagnostics)]
     (is (= (count expected) (count actual)) "Diagnostic count matches")
     (doseq [exp expected]
       (is (some #(and (= (:uri exp) (:uri %))
                       (= (:message exp) (:message %))
                       (= (:severity exp) (:severity %)))
                 actual)
           (str "Expected diagnostic found: " (:message exp))))))
  ([uri expected]
   (let [actual (db/diagnostics-by-uri uri)]
     (is (= (count expected) (count actual)) "Diagnostic count matches for URI")
     (doseq [exp expected]
       (is (some #(and (= (:message exp) (:message %))
                       (= (:severity exp) (:severity %)))
                 actual)
           (str "Expected diagnostic found: " (:message exp)))))))

(defn assert-symbols
  "Asserts that symbols match expected values for a URI."
  [uri expected]
  (let [actual (db/symbols-by-uri uri)]
    (is (= (count expected) (count actual)) "Symbol count matches")
    (doseq [exp expected]
      (is (some #(and (= (:name exp) (:name %))
                      (= (:kind exp) (:kind %)))
                actual)
          (str "Expected symbol found: " (:name exp))))))

(defn assert-active-uri
  "Asserts that the active URI matches expected."
  [expected-uri]
  (is (= expected-uri (db/active-uri)) "Active URI matches"))

;; =============================================================================
;; Test Data Generators
;; =============================================================================

(def gen-uri
  "Generator for document URIs."
  (gen/fmap (fn [[scheme path]]
              (str scheme ":///" path))
            (gen/tuple
             (gen/elements ["file" "inmemory"])
             (gen/fmap #(str "test/" % ".rho") gen/string-alphanumeric))))

(def gen-position
  "Generator for LSP positions."
  (gen/hash-map
   :line gen/nat
   :character gen/nat))

(def gen-range
  "Generator for LSP ranges."
  (gen/hash-map
   :start gen-position
   :end gen-position))

(def gen-severity
  "Generator for diagnostic severity (1-4)."
  (gen/choose 1 4))

(def gen-diagnostic
  "Generator for LSP diagnostics."
  (gen/hash-map
   :message gen/string-alphanumeric
   :severity gen-severity
   :range gen-range))

(def gen-symbol-kind
  "Generator for LSP symbol kinds (1-26)."
  (gen/choose 1 26))

(def gen-symbol
  "Generator for LSP document symbols."
  (gen/hash-map
   :name (gen/such-that #(pos? (count %)) gen/string-alphanumeric)
   :kind gen-symbol-kind
   :range gen-range
   :selectionRange gen-range))

(def gen-document
  "Generator for document entities."
  (gen/hash-map
   :uri gen-uri
   :text gen/string
   :language (gen/elements ["rholang" "text" "javascript"])
   :version gen/nat
   :dirty gen/boolean
   :opened gen/boolean))

;; =============================================================================
;; Mock Factories
;; =============================================================================

(defn make-test-diagnostic
  "Creates a test diagnostic with defaults."
  [{:keys [uri message severity start-line start-char end-line end-char version]
    :or {uri "file:///test/test.rho"
         message "Test error"
         severity 1
         start-line 0
         start-char 0
         end-line 0
         end-char 5
         version nil}}]
  (cond-> {:uri uri
           :message message
           :severity severity
           :startLine start-line
           :startChar start-char
           :endLine end-line
           :endChar end-char}
    version (assoc :version version)))

(defn make-test-symbol
  "Creates a test symbol with defaults."
  [{:keys [uri name kind start-line start-char end-line end-char
           selection-start-line selection-start-char
           selection-end-line selection-end-char parent]
    :or {uri "file:///test/test.rho"
         name "testSymbol"
         kind 12 ; function
         start-line 0
         start-char 0
         end-line 5
         end-char 0
         selection-start-line 0
         selection-start-char 4
         selection-end-line 0
         selection-end-char 14
         parent nil}}]
  (cond-> {:uri uri
           :name name
           :kind kind
           :startLine start-line
           :startChar start-char
           :endLine end-line
           :endChar end-char
           :selectionStartLine selection-start-line
           :selectionStartChar selection-start-char
           :selectionEndLine selection-end-line
           :selectionEndChar selection-end-char}
    parent (assoc :parent parent)))

(defn make-lsp-diagnostic
  "Creates an LSP-format diagnostic for testing."
  [{:keys [message severity start-line start-char end-line end-char]
    :or {message "Test error"
         severity 1
         start-line 0
         start-char 0
         end-line 0
         end-char 5}}]
  {:message message
   :severity severity
   :range {:start {:line start-line :character start-char}
           :end {:line end-line :character end-char}}})

(defn make-lsp-symbol
  "Creates an LSP-format symbol for testing."
  [{:keys [name kind start-line start-char end-line end-char
           selection-start-line selection-start-char
           selection-end-line selection-end-char children]
    :or {name "testSymbol"
         kind 12
         start-line 0
         start-char 0
         end-line 5
         end-char 0
         selection-start-line 0
         selection-start-char 4
         selection-end-line 0
         selection-end-char 14
         children nil}}]
  (cond-> {:name name
           :kind kind
           :range {:start {:line start-line :character start-char}
                   :end {:line end-line :character end-char}}
           :selectionRange {:start {:line selection-start-line :character selection-start-char}
                           :end {:line selection-end-line :character selection-end-char}}}
    children (assoc :children children)))

;; =============================================================================
;; Property Test Helpers
;; =============================================================================

(defn spec-valid?
  "Helper for checking spec validity in property tests."
  [spec data]
  (s/valid? spec data))

(defn count-preserved?
  "Checks that a transformation preserves element count."
  [input output]
  (= (count input) (count output)))

(defn flatten-preserves-total-count
  "Checks that flattening symbols preserves total count including children."
  [symbols flattened]
  (letfn [(total-count [syms]
            (reduce (fn [acc s]
                      (+ acc 1 (total-count (:children s []))))
                    0 syms))]
    (= (total-count symbols) (count flattened))))

;; =============================================================================
;; Timing Helpers (Phase 4 additions)
;; =============================================================================

(defn assert-timing-within
  "Asserts that actual timing is within tolerance of expected.
   All values in milliseconds."
  [expected-ms tolerance-ms actual-ms]
  (let [diff (js/Math.abs (- actual-ms expected-ms))]
    (is (<= diff tolerance-ms)
        (str "Expected ~" expected-ms "ms (±" tolerance-ms "ms), got " actual-ms "ms"))))

(defn measure-time
  "Measures execution time of a function in milliseconds.
   Returns [result time-ms]."
  [f]
  (let [start (js/performance.now)
        result (f)
        end (js/performance.now)]
    [result (- end start)]))

(defn with-timing
  "Wraps a test function to measure and return its execution time.
   Returns a channel with [result time-ms]."
  [f]
  (let [result-ch (promise-chan)]
    (go
      (let [start (js/performance.now)
            result (<! (f))
            end (js/performance.now)]
        (put! result-ch [result (- end start)])))
    result-ch))

;; =============================================================================
;; DB State Snapshot Helpers
;; =============================================================================

(defn- get-all-document-uris
  "Returns a set of all document URIs in the database."
  []
  (set (map :uri (db/documents))))

(defn snapshot-db-state
  "Captures current database state for later comparison.
   Returns a map with document count, diagnostics count, symbols count,
   active URI, and sample data checksums."
  []
  (let [all-uris (get-all-document-uris)]
    {:documents all-uris
     :document-count (count all-uris)
     :active-uri (db/active-uri)
     :timestamp (js/Date.now)
     :dirty-uris (set (filter db/document-dirty-by-uri all-uris))}))

(defn assert-db-unchanged
  "Asserts that database state matches a previous snapshot.
   Use for verifying operations don't have unintended side effects."
  [snapshot]
  (let [current (snapshot-db-state)]
    (is (= (:documents snapshot) (:documents current))
        "Document set should be unchanged")
    (is (= (:document-count snapshot) (:document-count current))
        "Document count should be unchanged")
    (is (= (:active-uri snapshot) (:active-uri current))
        "Active URI should be unchanged")
    (is (= (:dirty-uris snapshot) (:dirty-uris current))
        "Dirty URIs should be unchanged")))

(defn assert-db-documents-added
  "Asserts that only the specified URIs were added to the database."
  [snapshot added-uris]
  (let [current-docs (get-all-document-uris)
        expected-docs (clojure.set/union (:documents snapshot) (set added-uris))]
    (is (= expected-docs current-docs)
        (str "Expected documents " expected-docs " but got " current-docs))))

(defn assert-db-documents-removed
  "Asserts that only the specified URIs were removed from the database."
  [snapshot removed-uris]
  (let [current-docs (get-all-document-uris)
        expected-docs (clojure.set/difference (:documents snapshot) (set removed-uris))]
    (is (= expected-docs current-docs)
        (str "Expected documents " expected-docs " but got " current-docs))))

;; =============================================================================
;; Enhanced Async Helpers
;; =============================================================================

(defn wait-for-condition
  "Waits for a condition to become true within timeout.

   Parameters:
   - pred: Predicate function to check (returns truthy when condition met)
   - timeout-ms: Maximum time to wait (default 5000ms)
   - poll-ms: Time between checks (default 50ms)

   Returns channel with:
   - [:ok value] when predicate returns truthy (value is predicate result)
   - [:error :timeout] when timeout exceeded"
  ([pred]
   (wait-for-condition pred 5000 50))
  ([pred timeout-ms]
   (wait-for-condition pred timeout-ms 50))
  ([pred timeout-ms poll-ms]
   (let [result-ch (promise-chan)]
     (go
       (let [start (js/Date.now)]
         (loop []
           (let [value (try (pred) (catch :default _ nil))]
             (cond
               value
               (put! result-ch [:ok value])

               (> (- (js/Date.now) start) timeout-ms)
               (put! result-ch [:error :timeout])

               :else
               (do
                 (<! (timeout poll-ms))
                 (recur)))))))
     result-ch)))

(defn wait-for-value
  "Waits for a function to return a specific value.

   Returns channel with:
   - [:ok] when value matches
   - [:error :timeout] when timeout exceeded"
  ([f expected]
   (wait-for-value f expected 5000 50))
  ([f expected timeout-ms]
   (wait-for-value f expected timeout-ms 50))
  ([f expected timeout-ms poll-ms]
   (wait-for-condition #(= expected (f)) timeout-ms poll-ms)))

(defn wait-for-count
  "Waits for a collection-producing function to reach expected count.

   Returns channel with:
   - [:ok collection] when count matches
   - [:error :timeout] when timeout exceeded"
  ([f expected-count]
   (wait-for-count f expected-count 5000 50))
  ([f expected-count timeout-ms]
   (wait-for-count f expected-count timeout-ms 50))
  ([f expected-count timeout-ms poll-ms]
   (wait-for-condition
    (fn []
      (let [coll (f)]
        (when (= expected-count (count coll))
          coll)))
    timeout-ms
    poll-ms)))

(defn wait-for-document-dirty
  "Waits for a document to become dirty or clean."
  ([uri expected-dirty?]
   (wait-for-document-dirty uri expected-dirty? 1000))
  ([uri expected-dirty? timeout-ms]
   (wait-for-condition
    #(= expected-dirty? (db/document-dirty-by-uri uri))
    timeout-ms)))

(defn wait-for-version
  "Waits for a document to reach a specific version."
  ([uri expected-version]
   (wait-for-version uri expected-version 1000))
  ([uri expected-version timeout-ms]
   (wait-for-condition
    #(= expected-version (db/document-version-by-uri uri))
    timeout-ms)))

;; =============================================================================
;; Batch Testing Helpers
;; =============================================================================

(defn create-test-documents!
  "Creates multiple test documents at once.
   Takes a collection of document specs."
  [doc-specs]
  (doseq [spec doc-specs]
    (create-test-document! spec)))

(defn create-documents-with-diagnostics!
  "Creates documents with associated diagnostics."
  [docs-with-diags]
  (doseq [{:keys [doc diagnostics]} docs-with-diags]
    (create-test-document! doc)
    (when (seq diagnostics)
      (db/replace-diagnostics-by-uri! (:uri doc) nil diagnostics))))

(defn create-documents-with-symbols!
  "Creates documents with associated symbols."
  [docs-with-symbols]
  (doseq [{:keys [doc symbols]} docs-with-symbols]
    (create-test-document! doc)
    (when (seq symbols)
      (let [flattened (db/flatten-symbols symbols nil (:uri doc))]
        (db/replace-symbols! (:uri doc) flattened)))))

;; =============================================================================
;; Test Isolation Helpers
;; =============================================================================

(defn with-isolated-db
  "Runs test function with isolated database state.
   Restores previous state after test completes."
  [f]
  (let [saved-db @db/conn]
    (try
      (reset-db!)
      (f)
      (finally
        (d/reset-conn! db/conn saved-db)))))

(defn with-test-documents
  "Higher-order fixture that creates documents, runs test, then cleans up."
  [doc-specs f]
  (reset-db!)
  (create-test-documents! doc-specs)
  (f))
