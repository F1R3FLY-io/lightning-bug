(ns test.integration.document-flow-test
  "Integration tests for document lifecycle flows.

   These tests verify that documents can be created, edited, renamed,
   and closed with all subsystems (database, state, LSP) remaining
   consistent."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure.string :as str]
   [re-frame.core :as rf]
   [re-frame.db :as rf-db]
   [lib.db :as db]
   [lib.workspace :as ws]
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
             (rfh/reset-captured-effects!))
   :after (fn []
            (rfh/restore-all-effects!)
            (rfh/restore-all-coeffects!))})

;; =============================================================================
;; Document Creation Flow Tests
;; =============================================================================

(deftest document-creation-registers-in-all-systems
  (testing "Creating a document registers it in DataScript and makes it accessible"
    (let [uri "file:///test/document.rho"
          text "new Contract {}"]
      ;; Create document in DataScript
      (h/create-test-document! {:uri uri
                                :text text
                                :language "rholang"
                                :version 1
                                :dirty false
                                :opened true})
      ;; Verify in DataScript
      (let [doc-id (db/document-id-by-uri (ws/default-conn) uri)]
        (is (some? doc-id) "Document should have an ID")
        (is (= text (db/document-text-by-uri (ws/default-conn) uri)))
        (is (= "rholang" (db/document-language-by-uri (ws/default-conn) uri)))))))

(deftest document-becomes-active-and-accessible-via-subscriptions
  (testing "Setting active document makes it accessible via subscriptions"
    (let [uri "file:///test/active.rho"
          text "active document content"]
      (h/create-test-document! {:uri uri
                                :text text
                                :language "rholang"})
      (db/update-active-uri! (ws/default-conn) uri)

      ;; Verify via Re-Frame subscriptions
      (is (= uri @(rf/subscribe [:workspace/active-file])))
      (is (= text @(rf/subscribe [:active-content])))
      (is (= "rholang" @(rf/subscribe [:active-lang])))
      (is (= "active.rho" @(rf/subscribe [:active-name]))))))

;; =============================================================================
;; Document Edit Flow Tests
;; =============================================================================

(deftest document-edit-updates-text-and-marks-dirty
  (testing "Editing a document updates its text and marks it dirty"
    (let [uri "file:///test/edit.rho"
          original "original content"
          updated "updated content"]
      (h/create-test-document! {:uri uri
                                :text original
                                :dirty false})
      ;; Verify initial state
      (is (= original (db/document-text-by-uri (ws/default-conn) uri)))
      (is (false? (db/document-dirty-by-uri (ws/default-conn) uri)))

      ;; Edit document
      (db/update-document-text-by-uri! (ws/default-conn) uri updated)

      ;; Verify updated state
      (is (= updated (db/document-text-by-uri (ws/default-conn) uri)))
      (is (true? (db/document-dirty-by-uri (ws/default-conn) uri))))))

(deftest document-edit-increments-version
  (testing "Editing a document increments its version"
    (let [uri "file:///test/version.rho"]
      (h/create-test-document! {:uri uri
                                :text "v1"
                                :version 1})
      (is (= 1 (db/document-version-by-uri (ws/default-conn) uri)))

      ;; Increment version
      (db/increment-document-version-by-uri! (ws/default-conn) uri)
      (is (= 2 (db/document-version-by-uri (ws/default-conn) uri)))

      ;; Increment again
      (db/increment-document-version-by-uri! (ws/default-conn) uri)
      (is (= 3 (db/document-version-by-uri (ws/default-conn) uri))))))

;; =============================================================================
;; Document Save Flow Tests
;; =============================================================================

(deftest document-save-clears-dirty-flag
  (testing "Marking document as saved clears the dirty flag"
    (let [uri "file:///test/save.rho"]
      (h/create-test-document! {:uri uri
                                :text "content"
                                :dirty true})
      (is (true? (db/document-dirty-by-uri (ws/default-conn) uri)))

      ;; Save document (clear dirty flag)
      (db/document-saved-by-uri! (ws/default-conn) uri)

      (is (false? (db/document-dirty-by-uri (ws/default-conn) uri))))))

;; =============================================================================
;; Document Open/Close Flow Tests
;; =============================================================================

(deftest document-open-close-lifecycle
  (testing "Document can be opened, used, and closed"
    (let [uri "file:///test/lifecycle.rho"]
      ;; Create as not opened (with explicit language)
      (h/create-test-document! {:uri uri
                                :text "content"
                                :language "rholang"
                                :opened false})
      (is (false? (db/document-opened-by-uri (ws/default-conn) uri)))

      ;; Open document
      (db/document-opened-by-uri! (ws/default-conn) uri)
      (is (true? (db/document-opened-by-uri (ws/default-conn) uri)))

      ;; Document appears in opened list
      (let [opened (db/opened-uris-by-lang (ws/default-conn) "rholang")]
        (is (contains? (set opened) uri)))

      ;; Close document
      (db/document-closed-by-uri! (ws/default-conn) uri)
      (is (false? (db/document-opened-by-uri (ws/default-conn) uri))))))

;; =============================================================================
;; Multiple Documents Tests
;; =============================================================================

(deftest multiple-documents-can-coexist
  (testing "Multiple documents can be created and managed independently"
    (let [uri1 "file:///test/doc1.rho"
          uri2 "file:///test/doc2.rho"
          uri3 "file:///test/doc3.rho"]
      (h/create-test-document! {:uri uri1 :text "doc1" :language "rholang"})
      (h/create-test-document! {:uri uri2 :text "doc2" :language "rholang"})
      (h/create-test-document! {:uri uri3 :text "doc3" :language "text"})

      ;; Verify all documents exist
      (is (some? (db/document-id-by-uri (ws/default-conn) uri1)))
      (is (some? (db/document-id-by-uri (ws/default-conn) uri2)))
      (is (some? (db/document-id-by-uri (ws/default-conn) uri3)))

      ;; Verify workspace/files subscription
      (let [files @(rf/subscribe [:workspace/files])]
        (is (= 3 (count files)))
        (is (contains? files uri1))
        (is (contains? files uri2))
        (is (contains? files uri3))))))

(deftest switching-active-document-updates-state
  (testing "Switching active document updates all related state"
    (let [uri1 "file:///test/switch1.rho"
          uri2 "file:///test/switch2.rho"]
      (h/create-test-document! {:uri uri1 :text "content1" :language "rholang"})
      (h/create-test-document! {:uri uri2 :text "content2" :language "text"})

      ;; Activate first document
      (db/update-active-uri! (ws/default-conn) uri1)
      (is (= uri1 @(rf/subscribe [:workspace/active-file])))
      (is (= "content1" @(rf/subscribe [:active-content])))
      (is (= "rholang" @(rf/subscribe [:active-lang])))

      ;; Switch to second document
      (db/update-active-uri! (ws/default-conn) uri2)
      (is (= uri2 @(rf/subscribe [:workspace/active-file])))
      (is (= "content2" @(rf/subscribe [:active-content])))
      (is (= "text" @(rf/subscribe [:active-lang]))))))

;; =============================================================================
;; Document with Diagnostics Tests
;; =============================================================================

(deftest document-with-diagnostics-flow
  (testing "Diagnostics are associated with documents correctly"
    (let [uri "file:///test/diag.rho"]
      (h/create-test-document! {:uri uri :text "error code"})

      ;; Add diagnostics
      (db/replace-diagnostics-by-uri! (ws/default-conn) uri nil
                                       [{:message "Syntax error"
                                         :severity 1
                                         :startLine 0
                                         :startChar 0
                                         :endLine 0
                                         :endChar 5}
                                        {:message "Warning"
                                         :severity 2
                                         :startLine 0
                                         :startChar 6
                                         :endLine 0
                                         :endChar 10}])

      ;; Verify diagnostics are retrievable
      (db/update-active-uri! (ws/default-conn) uri)
      (let [diags @(rf/subscribe [:lsp/diagnostics])]
        (is (= 2 (count diags)))))))

(deftest clearing-diagnostics-removes-all
  (testing "Clearing diagnostics removes all for a document"
    (let [uri "file:///test/clear-diag.rho"]
      (h/create-test-document! {:uri uri :text "code"})
      (db/replace-diagnostics-by-uri! (ws/default-conn) uri nil
                                       [{:message "Error"
                                         :severity 1
                                         :startLine 0
                                         :startChar 0
                                         :endLine 0
                                         :endChar 4}])
      (db/update-active-uri! (ws/default-conn) uri)

      ;; Verify diagnostics exist
      (is (= 1 (count @(rf/subscribe [:lsp/diagnostics]))))

      ;; Clear diagnostics
      (db/replace-diagnostics-by-uri! (ws/default-conn) uri nil [])

      ;; Verify cleared
      (is (= 0 (count @(rf/subscribe [:lsp/diagnostics])))))))

;; =============================================================================
;; Document with Symbols Tests
;; =============================================================================

(deftest document-with-symbols-flow
  (testing "Symbols are associated with documents correctly"
    (let [uri "file:///test/symbols.rho"]
      (h/create-test-document! {:uri uri :text "contract Test {}"})

      ;; Add symbols
      (let [symbols (db/flatten-symbols
                     [{:name "Test"
                       :kind 5  ; Class
                       :range {:start {:line 0 :character 0}
                               :end {:line 0 :character 16}}
                       :selectionRange {:start {:line 0 :character 9}
                                        :end {:line 0 :character 13}}}]
                     nil uri)]
        (db/replace-symbols! (ws/default-conn) uri symbols))

      ;; Verify symbols are retrievable
      (db/update-active-uri! (ws/default-conn) uri)
      (let [syms @(rf/subscribe [:lsp/symbols])]
        (is (= 1 (count syms)))
        (is (= "Test" (:name (first syms))))))))

;; =============================================================================
;; Document Deletion Tests
;; =============================================================================

(deftest document-deletion-removes-all-related-data
  (testing "Deleting a document removes it and related data"
    (let [uri "file:///test/delete.rho"]
      (h/create-test-document! {:uri uri :text "to delete"})
      (let [doc-id (db/document-id-by-uri (ws/default-conn) uri)]
        ;; Add diagnostics
        (db/replace-diagnostics-by-uri! (ws/default-conn) uri nil
                                         [{:message "Error"
                                           :severity 1
                                           :startLine 0
                                           :startChar 0
                                           :endLine 0
                                           :endChar 5}])
        ;; Verify exists
        (is (some? doc-id))

        ;; Delete document
        (db/delete-document-by-id! (ws/default-conn) doc-id)

        ;; Verify removed
        (is (nil? (db/document-id-by-uri (ws/default-conn) uri)))))))

;; =============================================================================
;; Event Handler Integration Tests
;; =============================================================================

(deftest cursor-update-event-reflects-in-subscription
  (testing "Cursor update event is reflected in subscription"
    (rf/dispatch-sync [::events/update-cursor {:line 10 :column 5}])
    (is (= {:line 10 :column 5} @(rf/subscribe [:editor/cursor])))))

(deftest selection-update-event-reflects-in-subscription
  (testing "Selection update event is reflected in subscription"
    (let [selection {:from {:line 1 :column 0}
                     :to {:line 3 :column 10}}]
      (rf/dispatch-sync [::events/update-selection selection])
      (is (= selection @(rf/subscribe [:editor/selection]))))))

(deftest search-flow-integration
  (testing "Search flow from event to subscription"
    (let [uri "file:///test/search.rho"]
      (h/create-test-document! {:uri uri
                                :text "line one\nline two with match\nline three"})
      (db/update-active-uri! (ws/default-conn) uri)

      ;; Mock the coeffects
      (rfh/mock-coeffect! :document-repo/active-document
                          {:text "line one\nline two with match\nline three"})
      (rfh/mock-coeffect! :logs/all [])

      ;; Dispatch search event
      (rf/dispatch-sync [::events/search "match"])

      ;; Verify results via subscription
      (let [results @(rf/subscribe [:search-results])]
        (is (= 1 (count results)))
        (is (clojure.string/includes? (first results) "match"))))))

;; =============================================================================
;; Logs Integration Tests
;; =============================================================================

(deftest logs-creation-and-retrieval
  (testing "Logs can be created and retrieved"
    (db/create-logs! (ws/default-conn) [{:message "Log message 1" :lang "rholang"}
                      {:message "Log message 2" :lang "rholang"}
                      {:message "Error occurred" :lang "text"}])

    (let [logs @(rf/subscribe [:logs])]
      (is (= 3 (count logs))))))

(deftest logs-visibility-toggle
  (testing "Logs visibility can be toggled"
    (swap! rf-db/app-db assoc :logs-visible? false)
    (rf/dispatch-sync [::events/toggle-logs])
    (is (true? @(rf/subscribe [:logs-visible?])))
    (rf/dispatch-sync [::events/toggle-logs])
    (is (false? @(rf/subscribe [:logs-visible?])))))

;; =============================================================================
;; Complete Lifecycle Flow Tests (Phase 3)
;; =============================================================================

(deftest document-create-edit-save-close-flow
  (testing "Complete document lifecycle: create -> edit -> save -> close"
    (let [uri "file:///test/lifecycle-complete.rho"
          initial-text "new Contract in { Nil }"
          edited-text "new Contract in { x!(\"Hello\") }"]
      ;; Step 1: Create document
      (h/create-test-document! {:uri uri
                                :text initial-text
                                :language "rholang"
                                :version 1
                                :dirty false
                                :opened true})
      (db/update-active-uri! (ws/default-conn) uri)

      ;; Verify creation
      (is (some? (db/document-id-by-uri (ws/default-conn) uri)) "Document created")
      (is (= initial-text (db/document-text-by-uri (ws/default-conn) uri)) "Initial text set")
      (is (= 1 (db/document-version-by-uri (ws/default-conn) uri)) "Initial version is 1")
      (is (false? (db/document-dirty-by-uri (ws/default-conn) uri)) "Not dirty after creation")
      (is (true? (db/document-opened-by-uri (ws/default-conn) uri)) "Document is opened")

      ;; Step 2: Edit document
      (db/update-document-text-by-uri! (ws/default-conn) uri edited-text)

      ;; Verify edit effects
      (is (= edited-text (db/document-text-by-uri (ws/default-conn) uri)) "Text updated")
      (is (true? (db/document-dirty-by-uri (ws/default-conn) uri)) "Marked dirty after edit")

      ;; Step 3: Increment version (as LSP would)
      (db/increment-document-version-by-uri! (ws/default-conn) uri)
      (is (= 2 (db/document-version-by-uri (ws/default-conn) uri)) "Version incremented")

      ;; Step 4: Save document
      (db/document-saved-by-uri! (ws/default-conn) uri)
      (is (false? (db/document-dirty-by-uri (ws/default-conn) uri)) "Dirty cleared after save")

      ;; Step 5: Close document
      (db/document-closed-by-uri! (ws/default-conn) uri)
      (is (false? (db/document-opened-by-uri (ws/default-conn) uri)) "Document closed")

      ;; Document still exists but is closed
      (is (some? (db/document-id-by-uri (ws/default-conn) uri)) "Document still exists after close"))))

(deftest document-rename-preserves-diagnostics-and-symbols
  (testing "Renaming a document preserves its associated diagnostics and symbols"
    (let [old-uri "file:///test/old-name.rho"
          new-uri "file:///test/new-name.rho"]
      ;; Create original document
      (h/create-test-document! {:uri old-uri
                                :text "contract Test {}"
                                :language "rholang"
                                :version 1
                                :dirty false
                                :opened true})

      ;; Add diagnostics
      (db/replace-diagnostics-by-uri! (ws/default-conn) old-uri nil
                                       [{:message "Warning: unused variable"
                                         :severity 2
                                         :startLine 0
                                         :startChar 0
                                         :endLine 0
                                         :endChar 8}])

      ;; Add symbols
      (let [symbols (db/flatten-symbols
                     [{:name "Test"
                       :kind 5
                       :range {:start {:line 0 :character 9}
                               :end {:line 0 :character 13}}
                       :selectionRange {:start {:line 0 :character 9}
                                        :end {:line 0 :character 13}}}]
                     nil old-uri)]
        (db/replace-symbols! (ws/default-conn) old-uri symbols))

      ;; Verify initial state
      (db/update-active-uri! (ws/default-conn) old-uri)
      (is (= 1 (count @(rf/subscribe [:lsp/diagnostics]))) "Diagnostics exist before rename")
      (is (= 1 (count @(rf/subscribe [:lsp/symbols]))) "Symbols exist before rename")

      ;; Perform rename by:
      ;; 1. Creating new document with same content
      ;; 2. Copying diagnostics and symbols
      ;; 3. Deleting old document
      (let [doc-text (db/document-text-by-uri (ws/default-conn) old-uri)
            doc-lang (db/document-language-by-uri (ws/default-conn) old-uri)
            doc-version (db/document-version-by-uri (ws/default-conn) old-uri)
            old-diags @(rf/subscribe [:lsp/diagnostics])
            _old-syms @(rf/subscribe [:lsp/symbols])]

        ;; Create new document
        (h/create-test-document! {:uri new-uri
                                  :text doc-text
                                  :language doc-lang
                                  :version doc-version
                                  :dirty false
                                  :opened true})

        ;; Copy diagnostics to new URI
        (db/replace-diagnostics-by-uri! (ws/default-conn) new-uri nil
                                         (map #(dissoc % :db/id :document) old-diags))

        ;; Copy symbols to new URI
        (let [new-symbols (db/flatten-symbols
                           [{:name "Test"
                             :kind 5
                             :range {:start {:line 0 :character 9}
                                     :end {:line 0 :character 13}}
                             :selectionRange {:start {:line 0 :character 9}
                                              :end {:line 0 :character 13}}}]
                           nil new-uri)]
          (db/replace-symbols! (ws/default-conn) new-uri new-symbols))

        ;; Delete old document
        (let [old-id (db/document-id-by-uri (ws/default-conn) old-uri)]
          (db/delete-document-by-id! (ws/default-conn) old-id))

        ;; Activate new document
        (db/update-active-uri! (ws/default-conn) new-uri)

        ;; Verify preservation
        (is (nil? (db/document-id-by-uri (ws/default-conn) old-uri)) "Old document deleted")
        (is (some? (db/document-id-by-uri (ws/default-conn) new-uri)) "New document exists")
        (is (= 1 (count @(rf/subscribe [:lsp/diagnostics]))) "Diagnostics preserved after rename")
        (is (= 1 (count @(rf/subscribe [:lsp/symbols]))) "Symbols preserved after rename")))))

(deftest multi-document-active-switching
  (testing "Rapidly switching between multiple active documents"
    (let [uris (mapv #(str "file:///test/doc" % ".rho") (range 5))]
      ;; Create all documents
      (doseq [[idx uri] (map-indexed vector uris)]
        (h/create-test-document! {:uri uri
                                  :text (str "content " idx)
                                  :language "rholang"
                                  :version 1
                                  :dirty false
                                  :opened true}))

      ;; Rapidly switch between documents
      (doseq [_ (range 3)]  ; Multiple rounds
        (doseq [[idx uri] (map-indexed vector uris)]
          (db/update-active-uri! (ws/default-conn) uri)
          ;; Verify correct document is active
          (is (= uri @(rf/subscribe [:workspace/active-file]))
              (str "Active file should be " uri))
          (is (= (str "content " idx) @(rf/subscribe [:active-content]))
              "Content should match active document")))

      ;; Final verification - all documents still exist
      (doseq [uri uris]
        (is (some? (db/document-id-by-uri (ws/default-conn) uri))
            (str "Document " uri " should still exist"))))))

(deftest document-state-consistency-after-errors
  (testing "Document state remains consistent after operations on non-existent URIs"
    (let [valid-uri "file:///test/valid.rho"
          invalid-uri "file:///test/nonexistent.rho"]
      ;; Create valid document
      (h/create-test-document! {:uri valid-uri
                                :text "valid content"
                                :language "rholang"})
      (db/update-active-uri! (ws/default-conn) valid-uri)

      ;; Try operations on non-existent URI (should not throw or corrupt state)
      (is (nil? (db/document-text-by-uri (ws/default-conn) invalid-uri)) "Non-existent returns nil")
      (is (nil? (db/document-id-by-uri (ws/default-conn) invalid-uri)) "Non-existent ID returns nil")

      ;; Valid document should still be accessible
      (is (= "valid content" (db/document-text-by-uri (ws/default-conn) valid-uri))
          "Valid document unaffected")
      (is (= valid-uri @(rf/subscribe [:workspace/active-file]))
          "Active file unaffected"))))

(deftest document-version-tracking-across-multiple-edits
  (testing "Document version correctly tracks across multiple edits"
    (let [uri "file:///test/version-track.rho"]
      (h/create-test-document! {:uri uri
                                :text "v1"
                                :version 1})

      ;; Simulate multiple edit cycles
      (dotimes [i 10]
        (db/update-document-text-by-uri! (ws/default-conn) uri (str "v" (+ i 2)))
        (db/increment-document-version-by-uri! (ws/default-conn) uri)
        (is (= (+ i 2) (db/document-version-by-uri (ws/default-conn) uri))
            (str "Version should be " (+ i 2) " after edit " (inc i))))

      ;; Final state
      (is (= 11 (db/document-version-by-uri (ws/default-conn) uri)) "Final version is 11")
      (is (= "v11" (db/document-text-by-uri (ws/default-conn) uri)) "Final text is v11"))))

(deftest diagnostics-filtering-by-document
  (testing "Diagnostics are correctly isolated per document"
    (let [uri1 "file:///test/diag1.rho"
          uri2 "file:///test/diag2.rho"]
      ;; Create two documents
      (h/create-test-document! {:uri uri1 :text "code1" :language "rholang"})
      (h/create-test-document! {:uri uri2 :text "code2" :language "rholang"})

      ;; Add different diagnostics to each
      (db/replace-diagnostics-by-uri! (ws/default-conn) uri1 nil
                                       [{:message "Error in doc1"
                                         :severity 1
                                         :startLine 0
                                         :startChar 0
                                         :endLine 0
                                         :endChar 5}])
      (db/replace-diagnostics-by-uri! (ws/default-conn) uri2 nil
                                       [{:message "Warning in doc2"
                                         :severity 2
                                         :startLine 0
                                         :startChar 0
                                         :endLine 0
                                         :endChar 5}
                                        {:message "Another warning"
                                         :severity 2
                                         :startLine 0
                                         :startChar 0
                                         :endLine 0
                                         :endChar 5}])

      ;; Verify diagnostics are correctly associated with each document
      (let [diags1 (db/diagnostics-by-uri (ws/default-conn) uri1)]
        (is (= 1 (count diags1)) "Doc1 has 1 diagnostic")
        (is (= "Error in doc1" (:message (first diags1)))))

      (let [diags2 (db/diagnostics-by-uri (ws/default-conn) uri2)]
        (is (= 2 (count diags2)) "Doc2 has 2 diagnostics")))))
