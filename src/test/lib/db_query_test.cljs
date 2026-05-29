(ns test.lib.db-query-test
  "Tests for the lib.db query functions, property-based invariants, and spec
  validation (split from db_test)."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure.test.check :as tc]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [datascript.core :as d]
   [lib.db :as db]
   [test.lib.test-helpers :as h]))

(use-fixtures :each
  {:before #(d/reset-conn! db/conn (d/empty-db db/schema))})

;; =============================================================================
;; Query Function Tests
;; =============================================================================

(deftest documents-returns-all-documents
  (testing "documents returns all documents in database"
    (h/create-test-document! {:uri "file:///a.rho"
                              :text "a"
                              :language "rholang"})
    (h/create-test-document! {:uri "file:///b.rho"
                              :text "b"
                              :language "rholang"})
    (let [docs (db/documents)]
      (is (= 2 (count docs)))
      (is (some #(= "file:///a.rho" (:uri %)) docs))
      (is (some #(= "file:///b.rho" (:uri %)) docs)))))

(deftest document-text-by-uri-returns-text
  (testing "document-text-by-uri returns document text"
    (h/create-test-document! {:uri "file:///test.rho"
                              :text "test content"})
    (is (= "test content" (db/document-text-by-uri "file:///test.rho")))))

(deftest document-language-by-uri-returns-language
  (testing "document-language-by-uri returns document language"
    (h/create-test-document! {:uri "file:///test.rho"
                              :language "rholang"})
    (is (= "rholang" (db/document-language-by-uri "file:///test.rho")))))

(deftest first-document-uri-returns-first
  (testing "first-document-uri returns a document URI"
    (h/create-test-document! {:uri "file:///first.rho"})
    (is (= "file:///first.rho" (db/first-document-uri)))))

(deftest first-document-uri-returns-nil-when-empty
  (testing "first-document-uri returns nil when no documents"
    (is (nil? (db/first-document-uri)))))

(deftest active-uri-text-lang-returns-all
  (testing "active-uri-text-lang returns all active document info"
    (h/create-test-document! {:uri "file:///active.rho"
                              :text "active text"
                              :language "rholang"})
    (db/update-active-uri! "file:///active.rho")
    (let [[uri text lang] (db/active-uri-text-lang)]
      (is (= "file:///active.rho" uri))
      (is (= "active text" text))
      (is (= "rholang" lang)))))

(deftest doc-text-version-by-uri-returns-both
  (testing "doc-text-version-by-uri returns text and version"
    (h/create-test-document! {:uri "file:///test.rho"
                              :text "test text"
                              :version 7})
    (let [[text version] (db/doc-text-version-by-uri "file:///test.rho")]
      (is (= "test text" text))
      (is (= 7 version)))))

(deftest doc-id-text-lang-by-uri-returns-all
  (testing "doc-id-text-lang-by-uri returns id, text, and language"
    (let [id (h/create-test-document! {:uri "file:///test.rho"
                                       :text "test text"
                                       :language "rholang"})
          [returned-id text lang] (db/doc-id-text-lang-by-uri "file:///test.rho")]
      (is (= id returned-id))
      (is (= "test text" text))
      (is (= "rholang" lang)))))

(deftest document-language-opened-by-uri-returns-both
  (testing "document-language-opened-by-uri returns language and opened status"
    (h/create-test-document! {:uri "file:///test.rho"
                              :language "rholang"
                              :opened true})
    (let [[lang opened] (db/document-language-opened-by-uri "file:///test.rho")]
      (is (= "rholang" lang))
      (is (true? opened)))))

;; =============================================================================
;; Property-Based Tests
;; =============================================================================

(deftest flatten-symbols-preserves-count-property
  (testing "Flattening preserves total symbol count"
    (let [prop (prop/for-all [symbols (gen/vector h/gen-symbol 0 5)]
                             (let [flattened (db/flatten-symbols symbols nil "file:///test.rho")]
                               (= (count symbols) (count flattened))))
          result (tc/quick-check 100 prop {:seed 42})]
      (is (:result result) "Flatten preserves count for flat symbols"))))

(deftest flatten-symbols-with-children-property
  (testing "Flattening preserves total count including children"
    ;; Create symbols with children for property testing
    (letfn [(make-nested [depth]
              (if (zero? depth)
                {:name "leaf"
                 :kind 12
                 :range {:start {:line 0 :character 0}
                         :end {:line 1 :character 0}}
                 :selectionRange {:start {:line 0 :character 0}
                                  :end {:line 0 :character 4}}}
                {:name (str "node-" depth)
                 :kind 5
                 :range {:start {:line 0 :character 0}
                         :end {:line 10 :character 0}}
                 :selectionRange {:start {:line 0 :character 0}
                                  :end {:line 0 :character 5}}
                 :children [(make-nested (dec depth))]}))]
      (let [symbols [(make-nested 3)]
            flattened (db/flatten-symbols symbols nil "file:///test.rho")]
        (is (= 4 (count flattened)))))))

(deftest flatten-symbols-parent-refs-valid-property
  (testing "All parent references point to valid symbols"
    (let [symbols [{:name "root"
                    :kind 1
                    :range {:start {:line 0 :character 0}
                            :end {:line 20 :character 0}}
                    :selectionRange {:start {:line 0 :character 0}
                                     :end {:line 0 :character 4}}
                    :children [{:name "child1"
                                :kind 2
                                :range {:start {:line 1 :character 2}
                                        :end {:line 5 :character 2}}
                                :selectionRange {:start {:line 1 :character 2}
                                                 :end {:line 1 :character 8}}}
                               {:name "child2"
                                :kind 2
                                :range {:start {:line 6 :character 2}
                                        :end {:line 10 :character 2}}
                                :selectionRange {:start {:line 6 :character 2}
                                                 :end {:line 6 :character 8}}
                                :children [{:name "grandchild"
                                            :kind 3
                                            :range {:start {:line 7 :character 4}
                                                    :end {:line 9 :character 4}}
                                            :selectionRange {:start {:line 7 :character 4}
                                                             :end {:line 7 :character 14}}}]}]}]
          flattened (db/flatten-symbols symbols nil "file:///test.rho")
          ids (set (map :db/id flattened))]
      ;; All parent refs should be in the id set or nil
      (doseq [sym flattened]
        (when-let [parent (:symbol/parent sym)]
          (is (contains? ids parent)
              (str "Parent " parent " should be a valid symbol ID")))))))

;; =============================================================================
;; Spec Validation Tests
;; =============================================================================

(deftest valid-document?-validates-correctly
  (testing "Valid document passes validation"
    (is (db/valid-document? {:document/uri "file:///test.rho"
                             :document/text "content"
                             :document/language "rholang"
                             :document/version 0
                             :document/dirty false
                             :document/opened false
                             :type :document}))))

(deftest valid-diagnostic?-validates-correctly
  (testing "Valid diagnostic passes validation"
    (is (db/valid-diagnostic? {:diagnostic/document 1
                               :diagnostic/message "error"
                               :diagnostic/severity 1
                               :diagnostic/start-line 0
                               :diagnostic/start-char 0
                               :diagnostic/end-line 0
                               :diagnostic/end-char 5
                               :type :diagnostic}))))

(deftest valid-symbol?-validates-correctly
  (testing "Valid symbol passes validation"
    (is (db/valid-symbol? {:symbol/document 1
                           :symbol/name "testFunc"
                           :symbol/kind 12
                           :symbol/start-line 0
                           :symbol/start-char 0
                           :symbol/end-line 5
                           :symbol/end-char 0
                           :symbol/selection-start-line 0
                           :symbol/selection-start-char 4
                           :symbol/selection-end-line 0
                           :symbol/selection-end-char 12
                           :type :symbol}))))

;; =============================================================================
;; Edge Case Tests - Phase 2
;; =============================================================================

(deftest inc-document-version-by-uri!-non-existent-uri
  (testing "Incrementing version for non-existent URI throws due to nil entity id"
    ;; Note: document-id-version-by-uri returns [nil nil] for non-existent docs,
    ;; which causes when-let to pass but then transaction fails with nil entity id.
    ;; This is arguably a bug - the fallback [nil nil] defeats when-let's guard.
    (is (thrown-with-msg? js/Error
                          #"Expected number or lookup ref for entity id"
                          (db/inc-document-version-by-uri! "file:///does-not-exist.rho")))))

(deftest inc-document-version-by-id!-invalid-id
  (testing "Incrementing version for invalid ID handles gracefully"
    ;; The function checks if id is truthy, so nil should return nil
    (let [result (db/inc-document-version-by-id! nil)]
      (is (nil? result) "Should return nil for nil ID"))
    ;; Non-existent positive ID will throw from DataScript, but the when guard
    ;; ensures nil old-version leads to (inc nil) -> 1
    ;; Actually looking at the code: (when id ...) so nil returns nil
    ;; For non-existent ID, document-version-by-id returns nil, then (inc nil) throws
    ;; So this tests that the code path works for nil
    ))

(deftest update-document-text-by-uri!-non-existent-uri
  (testing "Updating text for non-existent URI does not throw"
    ;; Should not throw - just a no-op
    (db/update-document-text-by-uri! "file:///does-not-exist.rho" "new text")
    (is (nil? (db/document-text-by-uri "file:///does-not-exist.rho")))))

(deftest delete-document-by-id!-cascades-symbols-and-diagnostics
  (testing "Deleting document cascades to symbols and diagnostics"
    (let [id (h/create-test-document! {:uri "file:///cascade.rho"})]
      ;; Add diagnostics
      (db/replace-diagnostics-by-uri! "file:///cascade.rho" nil
                                      [{:message "error"
                                        :severity 1
                                        :startLine 0
                                        :startChar 0
                                        :endLine 0
                                        :endChar 5}])
      ;; Add symbols
      (db/replace-symbols! "file:///cascade.rho"
                           (db/flatten-symbols
                            [{:name "func"
                              :kind 12
                              :range {:start {:line 0 :character 0}
                                      :end {:line 5 :character 0}}
                              :selectionRange {:start {:line 0 :character 4}
                                               :end {:line 0 :character 8}}}]
                            nil "file:///cascade.rho"))
      ;; Verify they exist
      (is (= 1 (count (db/diagnostics-by-uri "file:///cascade.rho"))))
      (is (= 1 (count (db/symbols-by-uri "file:///cascade.rho"))))
      ;; Delete document
      (db/delete-document-by-id! id)
      ;; Both should be gone due to DataScript ref cascading
      (is (empty? (db/diagnostics-by-uri "file:///cascade.rho")))
      (is (empty? (db/symbols-by-uri "file:///cascade.rho"))))))

(deftest replace-diagnostics-by-uri!-filters-stale-versions
  (testing "Diagnostics are filtered when version doesn't match document version"
    (h/create-test-document! {:uri "file:///versioned.rho"
                              :version 5})
    ;; Add diagnostics with version 5 (matches)
    (db/replace-diagnostics-by-uri! "file:///versioned.rho" 5
                                    [{:message "current error"
                                      :severity 1
                                      :startLine 0
                                      :startChar 0
                                      :endLine 0
                                      :endChar 5}])
    (is (= 1 (count (db/diagnostics-by-uri "file:///versioned.rho"))))
    ;; Increment document version
    (db/inc-document-version-by-uri! "file:///versioned.rho")
    ;; Now version is 6, but diagnostics are at version 5
    ;; When querying, the version mismatch should filter them
    (let [diags (db/diagnostics-by-uri "file:///versioned.rho")]
      ;; The diagnostic has version 5 but doc is now 6 - should be filtered
      (is (empty? diags) "Stale diagnostics should be filtered"))))

(deftest empty-document-text-handling
  (testing "Documents can have empty text"
    (h/create-test-document! {:uri "file:///empty.rho"
                              :text ""})
    (is (= "" (db/document-text-by-uri "file:///empty.rho")))))

(deftest unicode-in-document-text-preserved
  (testing "Unicode characters in document text are preserved"
    (let [unicode-text "// 你好世界 Hello 🌍\nλx.x → identity\nΩ = ω ω\n// Привет мир"]
      (h/create-test-document! {:uri "file:///unicode.rho"
                                :text unicode-text})
      (is (= unicode-text (db/document-text-by-uri "file:///unicode.rho"))))))

(deftest document-dirty-flag-transitions
  (testing "Dirty flag transitions correctly through operations"
    (let [id (h/create-test-document! {:uri "file:///dirty-transitions.rho"
                                       :text "initial"
                                       :dirty false})]
      ;; Update text should set dirty to true
      (db/update-document-text-by-uri! "file:///dirty-transitions.rho" "modified")
      (is (true? (db/document-dirty-by-uri "file:///dirty-transitions.rho")))
      ;; Save should clear dirty flag
      (db/document-saved-by-uri! "file:///dirty-transitions.rho")
      (is (false? (db/document-dirty-by-uri "file:///dirty-transitions.rho")))
      ;; Explicit dirty update
      (db/update-document-dirty-by-id! id true)
      (is (true? (db/document-dirty-by-uri "file:///dirty-transitions.rho"))))))

(deftest document-state-transitions-valid-property
  (testing "Document state transitions produce valid states"
    (let [_id (h/create-test-document! {:uri "file:///state-test.rho"
                                       :text "initial"
                                       :language "rholang"
                                       :version 0
                                       :dirty false
                                       :opened false})]
      ;; Open the document
      (db/document-opened-by-uri! "file:///state-test.rho")
      (is (true? (db/document-opened-by-uri? "file:///state-test.rho")))
      ;; Update text - should set dirty
      (db/update-document-text-by-uri! "file:///state-test.rho" "modified")
      (is (true? (db/document-dirty-by-uri "file:///state-test.rho")))
      ;; Increment version
      (db/inc-document-version-by-uri! "file:///state-test.rho")
      (is (= 1 (second (db/document-id-version-by-uri "file:///state-test.rho"))))
      ;; Close the document
      (db/document-closed-by-uri! "file:///state-test.rho")
      (is (false? (db/document-opened-by-uri? "file:///state-test.rho")))
      ;; Document should still exist with correct state
      (let [[text lang dirty] (db/doc-text-lang-dirty-by-uri "file:///state-test.rho")]
        (is (= "modified" text))
        (is (= "rholang" lang))
        (is (true? dirty))))))

(deftest document-version-monotonic-property
  (testing "Document version always increases"
    (h/create-test-document! {:uri "file:///monotonic.rho"
                              :version 0})
    (let [versions (atom [0])]
      (dotimes [_ 10]
        (let [new-version (db/inc-document-version-by-uri! "file:///monotonic.rho")]
          (swap! versions conj new-version)))
      ;; Check monotonicity
      (is (apply < @versions) "Versions should be strictly increasing"))))

(deftest coalesced-queries-return-consistent-results
  (testing "EXP-007 coalesced queries return consistent results with individual queries"
    (h/create-test-document! {:uri "file:///coalesced.rho"
                              :text "coalesced content"
                              :language "rholang"
                              :version 3
                              :dirty true
                              :opened true})
    (db/update-active-uri! "file:///coalesced.rho")
    ;; Test active-uri-text-lang vs individual queries
    (let [[uri text lang] (db/active-uri-text-lang)]
      (is (= (db/active-uri) uri))
      (is (= (db/active-text) text))
      (is (= (db/active-lang) lang)))
    ;; Test active-uri-text-lang-version
    (let [[uri text lang version] (db/active-uri-text-lang-version)]
      (is (= (db/active-uri) uri))
      (is (= (db/active-text) text))
      (is (= (db/active-lang) lang))
      (is (= (db/active-version) version)))
    ;; Test doc-text-lang-version-by-uri
    (let [[text lang version] (db/doc-text-lang-version-by-uri "file:///coalesced.rho")]
      (is (= (db/document-text-by-uri "file:///coalesced.rho") text))
      (is (= (db/document-language-by-uri "file:///coalesced.rho") lang))
      (is (= (db/document-version-by-uri "file:///coalesced.rho") version)))))

(deftest diagnostics-with-nil-version-always-match
  (testing "Diagnostics without version always match document version"
    (h/create-test-document! {:uri "file:///nil-version.rho"
                              :version 10})
    ;; Add diagnostics without version (nil)
    (db/replace-diagnostics-by-uri! "file:///nil-version.rho" nil
                                    [{:message "no version diagnostic"
                                      :severity 1
                                      :startLine 0
                                      :startChar 0
                                      :endLine 0
                                      :endChar 5}])
    ;; Should be visible
    (is (= 1 (count (db/diagnostics-by-uri "file:///nil-version.rho"))))
    ;; Increment version multiple times
    (db/inc-document-version-by-uri! "file:///nil-version.rho")
    (db/inc-document-version-by-uri! "file:///nil-version.rho")
    (db/inc-document-version-by-uri! "file:///nil-version.rho")
    ;; Should still be visible since diagnostic has nil version
    (is (= 1 (count (db/diagnostics-by-uri "file:///nil-version.rho"))))))
