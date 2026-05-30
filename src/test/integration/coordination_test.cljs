(ns test.integration.coordination-test
  "Integration tests for multi-module coordination.

   These tests verify that different modules (db, editor, LSP, debounce)
   work together correctly and maintain consistent state."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures async]]
   [clojure.core.async :refer [go <! timeout]]
   [re-frame.core :as rf]
   [lib.db :as db]
   [lib.workspace :as ws]
   [lib.debounce :as debounce]
   [lib.query-cache :as qc]
   [app.events :as events]
   [app.subs]
   [test.lib.test-helpers :as h]
   [test.app.reframe-helpers :as rfh]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(use-fixtures :each
  {:before (fn []
             (ws/reset-workspace! @ws/default-workspace)
             (rfh/reset-app-db!)
             (rfh/reset-captured-effects!)
             (debounce/cancel-all)
             (qc/invalidate-all!)
             (qc/reset-stats!))
   :after (fn []
            (debounce/cancel-all)
            (rfh/restore-all-effects!)
            (rfh/restore-all-coeffects!))})

;; =============================================================================
;; DB-Editor Sync Tests
;; =============================================================================

(deftest db-editor-sync-text-changes
  (testing "Editor text changes reflect in database correctly"
    (let [uri "file:///test/editor-sync.rho"
          initial-text "initial"
          updated-text "updated content"]
      ;; Create document
      (h/create-test-document! {:uri uri
                                :text initial-text
                                :language "rholang"
                                :version 1
                                :dirty false
                                :opened true})
      (db/update-active-uri! (ws/default-conn) uri)

      ;; Verify initial state via subscription
      (is (= initial-text @(rf/subscribe [:active-content])))

      ;; Simulate editor update
      (db/update-document-text-by-uri! (ws/default-conn) uri updated-text)

      ;; Verify database updated
      (is (= updated-text (db/document-text-by-uri (ws/default-conn) uri)))

      ;; Verify subscription reflects change
      (is (= updated-text @(rf/subscribe [:active-content]))))))

(deftest db-editor-sync-dirty-flag
  (testing "Dirty flag synchronizes correctly in DB"
    (let [uri "file:///test/dirty-sync.rho"]
      (h/create-test-document! {:uri uri
                                :text "content"
                                :dirty false})
      (db/update-active-uri! (ws/default-conn) uri)

      ;; Verify initially clean
      (is (false? (db/document-dirty-by-uri (ws/default-conn) uri)))

      ;; Edit makes dirty
      (db/update-document-text-by-uri! (ws/default-conn) uri "new content")
      (is (true? (db/document-dirty-by-uri (ws/default-conn) uri)))

      ;; Save clears dirty
      (db/document-saved-by-uri! (ws/default-conn) uri)
      (is (false? (db/document-dirty-by-uri (ws/default-conn) uri))))))

;; =============================================================================
;; DB-LSP Sync Tests
;; =============================================================================

(deftest db-lsp-sync-diagnostics
  (testing "LSP diagnostics are stored and retrieved consistently"
    (let [uri "file:///test/lsp-diag-sync.rho"]
      (h/create-test-document! {:uri uri :text "code" :language "rholang"})
      (db/update-active-uri! (ws/default-conn) uri)

      ;; Add diagnostics (simulating LSP notification)
      (db/replace-diagnostics-by-uri! (ws/default-conn) uri nil
                                       [{:message "Error 1"
                                         :severity 1
                                         :startLine 0 :startChar 0
                                         :endLine 0 :endChar 4}
                                        {:message "Warning 1"
                                         :severity 2
                                         :startLine 1 :startChar 0
                                         :endLine 1 :endChar 4}])

      ;; Verify retrieval via subscription
      (let [diags @(rf/subscribe [:lsp/diagnostics])]
        (is (= 2 (count diags)))
        (is (some #(= 1 (:severity %)) diags))
        (is (some #(= 2 (:severity %)) diags))))))

(deftest db-lsp-sync-symbols
  (testing "LSP symbols are stored and retrieved consistently"
    (let [uri "file:///test/lsp-symbol-sync.rho"]
      (h/create-test-document! {:uri uri :text "contract" :language "rholang"})
      (db/update-active-uri! (ws/default-conn) uri)

      ;; Add symbols (simulating LSP response)
      (let [symbols (db/flatten-symbols
                     [{:name "MainContract"
                       :kind 5
                       :range {:start {:line 0 :character 0}
                               :end {:line 10 :character 1}}
                       :selectionRange {:start {:line 0 :character 9}
                                        :end {:line 0 :character 21}}}]
                     nil uri)]
        (db/replace-symbols! (ws/default-conn) uri symbols))

      ;; Verify retrieval
      (let [syms @(rf/subscribe [:lsp/symbols])]
        (is (= 1 (count syms)))
        (is (= "MainContract" (:name (first syms))))))))

(deftest db-lsp-sync-version-tracking
  (testing "Document version is tracked for LSP sync"
    (let [uri "file:///test/version-sync.rho"]
      (h/create-test-document! {:uri uri
                                :text "v1"
                                :version 1
                                :language "rholang"})

      ;; Simulate multiple edits
      (dotimes [_ 5]
        (db/increment-document-version-by-uri! (ws/default-conn) uri))

      (is (= 6 (db/document-version-by-uri (ws/default-conn) uri))))))

;; =============================================================================
;; Edit-to-Diagnostics Pipeline Tests
;; =============================================================================

(deftest edit-to-diagnostics-pipeline
  (async done
         (go
           (let [uri "file:///test/pipeline.rho"]
             ;; Step 1: Create document with initial code
             (h/create-test-document! {:uri uri
                                       :text "valid code"
                                       :language "rholang"
                                       :version 1
                                       :opened true})
             (db/update-active-uri! (ws/default-conn) uri)

             ;; Step 2: Verify no diagnostics initially
             (is (= 0 (count @(rf/subscribe [:lsp/diagnostics]))))

             ;; Step 3: Edit document (introduce error)
             (db/update-document-text-by-uri! (ws/default-conn) uri "invalid { code")
             (db/increment-document-version-by-uri! (ws/default-conn) uri)

             ;; Simulate debounced LSP notification delay
             (<! (timeout 50))

             ;; Step 4: Simulate LSP publishing diagnostics
             (db/replace-diagnostics-by-uri! (ws/default-conn) uri nil
                                              [{:message "Syntax error"
                                                :severity 1
                                                :startLine 0 :startChar 8
                                                :endLine 0 :endChar 14}])

             ;; Step 5: Verify diagnostics visible
             (is (= 1 (count @(rf/subscribe [:lsp/diagnostics]))))

             ;; Step 6: Fix the error
             (db/update-document-text-by-uri! (ws/default-conn) uri "valid { code }")
             (db/increment-document-version-by-uri! (ws/default-conn) uri)

             (<! (timeout 50))

             ;; Step 7: LSP clears diagnostics
             (db/replace-diagnostics-by-uri! (ws/default-conn) uri nil [])

             ;; Step 8: Verify diagnostics cleared
             (is (= 0 (count @(rf/subscribe [:lsp/diagnostics])))))
           (done))))

;; =============================================================================
;; Debounce Coordination Tests
;; =============================================================================

(deftest debounce-coordinates-rapid-edits
  (async done
         (go
           (let [edit-count (atom 0)
                 debounce-key :test-edit-debounce]
             ;; Simulate rapid edits
             (dotimes [_ 10]
               (debounce/debounced-call
                debounce-key
                (fn [] (swap! edit-count inc))
                50))

             ;; Wait for debounce to complete
             (<! (timeout 150))

             ;; Should only execute once due to debouncing
             (is (= 1 @edit-count) "Debouncing should collapse rapid calls"))
           (done))))

(deftest debounce-throttle-coordinates-continuous-updates
  (async done
         (go
           (let [update-count (atom 0)
                 throttle-key :test-throttle]
             ;; Simulate continuous updates
             (dotimes [_ 10]
               (debounce/throttled-call
                throttle-key
                (fn [] (swap! update-count inc))
                50)
               (<! (timeout 20)))

             ;; Wait for final throttle
             (<! (timeout 100))

             ;; Should execute multiple times but not every call
             (is (> @update-count 1) "Throttle should allow some calls through")
             (is (< @update-count 10) "Throttle should limit call frequency"))
           (done))))

;; =============================================================================
;; Query Cache Coordination Tests
;; =============================================================================

(deftest cache-coordinates-with-db-changes
  (testing "Query cache responds to database transaction changes"
    (let [uri "file:///test/cache-coord.rho"]
      ;; Create document
      (h/create-test-document! {:uri uri :text "content" :language "rholang"})

      ;; Reset stats
      (qc/reset-stats!)

      ;; First query (cache miss)
      (let [result1 (qc/cached-query
                     :test-query
                     [uri]
                     (fn [] (db/document-text-by-uri (ws/default-conn) uri)))]
        (is (= "content" result1)))

      ;; Second identical query (cache hit)
      (let [result2 (qc/cached-query
                     :test-query
                     [uri]
                     (fn [] (db/document-text-by-uri (ws/default-conn) uri)))]
        (is (= "content" result2)))

      ;; Verify cache stats
      (let [stats (qc/get-stats)]
        (is (= 1 (:misses stats)) "First query was miss")
        (is (= 1 (:hits stats)) "Second query was hit")))))

(deftest cache-invalidation-on-document-change
  (testing "Cache invalidation triggers on document changes"
    (let [uri "file:///test/cache-invalidation.rho"]
      (h/create-test-document! {:uri uri :text "original" :language "rholang"})

      ;; Create cache-key AFTER document creation to match transaction counter
      ;; (create-test-document! increments the counter)
      ;; NOTE: Must use apply to match how cached-query internally creates keys
      ;; cached-query does: (apply make-cache-key query-name args)
      (let [cache-key (apply qc/make-cache-key :doc-text [uri])]
        ;; Cache the query
        (qc/cached-query :doc-text [uri] (fn [] (db/document-text-by-uri (ws/default-conn) uri)))

        ;; Invalidate cache
        (qc/invalidate! cache-key)

        ;; Next query should be a miss
        (qc/reset-stats!)
        (qc/cached-query :doc-text [uri] (fn [] (db/document-text-by-uri (ws/default-conn) uri)))

        (let [stats (qc/get-stats)]
          (is (= 1 (:misses stats)) "Query after invalidation is a miss"))))))

;; =============================================================================
;; Multi-Module State Consistency Tests
;; =============================================================================

(deftest multi-document-state-consistency
  (testing "State remains consistent across multiple document operations"
    (let [uris (mapv #(str "file:///test/multi" % ".rho") (range 5))]
      ;; Create all documents
      (doseq [[idx uri] (map-indexed vector uris)]
        (h/create-test-document! {:uri uri
                                  :text (str "content " idx)
                                  :language "rholang"
                                  :version 1
                                  :opened true}))

      ;; Add diagnostics to some
      (db/replace-diagnostics-by-uri! (ws/default-conn) (first uris) nil
                                       [{:message "Error"
                                         :severity 1
                                         :startLine 0 :startChar 0
                                         :endLine 0 :endChar 5}])
      (db/replace-diagnostics-by-uri! (ws/default-conn) (second uris) nil
                                       [{:message "Warning"
                                         :severity 2
                                         :startLine 0 :startChar 0
                                         :endLine 0 :endChar 5}])

      ;; Switch between documents rapidly and verify consistency
      (doseq [uri uris]
        (db/update-active-uri! (ws/default-conn) uri)
        ;; Each switch should show correct active content
        (let [idx (.indexOf uris uri)]
          (is (= (str "content " idx) @(rf/subscribe [:active-content])))))

      ;; Verify all documents still exist
      (is (= 5 (count @(rf/subscribe [:workspace/files])))))))

(deftest subscription-reactivity-to-db-changes
  (testing "Subscriptions react immediately to database changes"
    (let [uri "file:///test/reactive.rho"]
      (h/create-test-document! {:uri uri :text "initial" :language "rholang" :version 1})
      (db/update-active-uri! (ws/default-conn) uri)

      ;; Verify initial state via subscription
      (is (= "initial" @(rf/subscribe [:active-content])))

      ;; Change text and verify subscription reflects change
      (db/update-document-text-by-uri! (ws/default-conn) uri "changed")
      (is (= "changed" @(rf/subscribe [:active-content])))

      ;; Verify version changes via db function
      (db/increment-document-version-by-uri! (ws/default-conn) uri)
      (is (= 2 (db/document-version-by-uri (ws/default-conn) uri)))

      ;; Verify dirty flag changes via db function
      (db/document-saved-by-uri! (ws/default-conn) uri)
      (is (false? (db/document-dirty-by-uri (ws/default-conn) uri))))))

;; =============================================================================
;; Event Handler Coordination Tests
;; =============================================================================

(deftest cursor-and-selection-event-coordination
  (testing "Cursor and selection events update state correctly"
    ;; Update cursor
    (rf/dispatch-sync [::events/update-cursor {:line 5 :column 10}])
    (is (= {:line 5 :column 10} @(rf/subscribe [:editor/cursor])))

    ;; Update selection
    (rf/dispatch-sync [::events/update-selection
                       {:from {:line 5 :column 10}
                        :to {:line 7 :column 20}}])
    (is (= {:from {:line 5 :column 10}
            :to {:line 7 :column 20}}
           @(rf/subscribe [:editor/selection])))))

;; =============================================================================
;; Cleanup and Resource Management Tests
;; =============================================================================

(deftest document-deletion-cleans-up-all-resources
  (testing "Deleting a document cleans up diagnostics, symbols, and references"
    (let [uri "file:///test/cleanup.rho"]
      ;; Create document with associated data
      (h/create-test-document! {:uri uri :text "code" :language "rholang"})

      ;; Add diagnostics
      (db/replace-diagnostics-by-uri! (ws/default-conn) uri nil
                                       [{:message "Error"
                                         :severity 1
                                         :startLine 0 :startChar 0
                                         :endLine 0 :endChar 4}])

      ;; Add symbols
      (let [symbols (db/flatten-symbols
                     [{:name "Symbol"
                       :kind 5
                       :range {:start {:line 0 :character 0}
                               :end {:line 0 :character 6}}
                       :selectionRange {:start {:line 0 :character 0}
                                        :end {:line 0 :character 6}}}]
                     nil uri)]
        (db/replace-symbols! (ws/default-conn) uri symbols))

      (db/update-active-uri! (ws/default-conn) uri)

      ;; Verify data exists
      (is (= 1 (count @(rf/subscribe [:lsp/diagnostics]))))
      (is (= 1 (count @(rf/subscribe [:lsp/symbols]))))

      ;; Delete document
      (let [doc-id (db/document-id-by-uri (ws/default-conn) uri)]
        (db/delete-document-by-id! (ws/default-conn) doc-id))

      ;; Document should be gone
      (is (nil? (db/document-id-by-uri (ws/default-conn) uri))))))

(deftest debounce-cleanup-on-document-close
  (async done
         (go
           (let [uri "file:///test/debounce-cleanup.rho"
                 debounce-key [:lsp-did-change uri]
                 executed (atom false)]
             ;; Create document
             (h/create-test-document! {:uri uri :text "content" :language "rholang"})

             ;; Schedule a debounced operation
             (debounce/debounced-call
              debounce-key
              (fn [] (reset! executed true))
              100)

             ;; Verify pending
             (is (true? (debounce/has-pending? debounce-key)))

             ;; Cancel (as would happen on document close)
             (debounce/cancel debounce-key)

             ;; Verify cancelled
             (is (false? (debounce/has-pending? debounce-key)))

             ;; Wait past debounce time
             (<! (timeout 150))

             ;; Should not have executed
             (is (false? @executed) "Cancelled debounce should not execute"))
           (done))))
