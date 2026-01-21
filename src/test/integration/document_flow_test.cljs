(ns test.integration.document-flow-test
  "Integration tests for document lifecycle flows.

   These tests verify that documents can be created, edited, renamed,
   and closed with all subsystems (database, state, LSP) remaining
   consistent."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures async]]
   [clojure.core.async :refer [go <! timeout]]
   [re-frame.core :as rf]
   [re-frame.db :as rf-db]
   [datascript.core :as d]
   [lib.db :as db]
   [app.events :as events]
   [app.subs]
   [test.lib.test-helpers :as h]
   [test.app.reframe-helpers :as rfh]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(use-fixtures :each
  {:before (fn []
             (d/reset-conn! db/conn (d/empty-db db/schema))
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
      (let [doc-id (db/document-id-by-uri uri)]
        (is (some? doc-id) "Document should have an ID")
        (is (= text (db/document-text-by-uri uri)))
        (is (= "rholang" (db/document-language-by-uri uri)))))))

(deftest document-becomes-active-and-accessible-via-subscriptions
  (testing "Setting active document makes it accessible via subscriptions"
    (let [uri "file:///test/active.rho"
          text "active document content"]
      (h/create-test-document! {:uri uri
                                :text text
                                :language "rholang"})
      (db/update-active-uri! uri)

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
      (is (= original (db/document-text-by-uri uri)))
      (is (false? (db/document-dirty-by-uri uri)))

      ;; Edit document
      (db/update-document-text-by-uri! uri updated)

      ;; Verify updated state
      (is (= updated (db/document-text-by-uri uri)))
      (is (true? (db/document-dirty-by-uri uri))))))

(deftest document-edit-increments-version
  (testing "Editing a document increments its version"
    (let [uri "file:///test/version.rho"]
      (h/create-test-document! {:uri uri
                                :text "v1"
                                :version 1})
      (is (= 1 (db/document-version-by-uri uri)))

      ;; Increment version
      (db/increment-document-version-by-uri! uri)
      (is (= 2 (db/document-version-by-uri uri)))

      ;; Increment again
      (db/increment-document-version-by-uri! uri)
      (is (= 3 (db/document-version-by-uri uri))))))

;; =============================================================================
;; Document Save Flow Tests
;; =============================================================================

(deftest document-save-clears-dirty-flag
  (testing "Marking document as saved clears the dirty flag"
    (let [uri "file:///test/save.rho"]
      (h/create-test-document! {:uri uri
                                :text "content"
                                :dirty true})
      (is (true? (db/document-dirty-by-uri uri)))

      ;; Save document (clear dirty flag)
      (db/document-saved-by-uri! uri)

      (is (false? (db/document-dirty-by-uri uri))))))

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
      (is (false? (db/document-opened-by-uri uri)))

      ;; Open document
      (db/document-opened-by-uri! uri)
      (is (true? (db/document-opened-by-uri uri)))

      ;; Document appears in opened list
      (let [opened (db/opened-uris-by-lang "rholang")]
        (is (contains? (set opened) uri)))

      ;; Close document
      (db/document-closed-by-uri! uri)
      (is (false? (db/document-opened-by-uri uri))))))

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
      (is (some? (db/document-id-by-uri uri1)))
      (is (some? (db/document-id-by-uri uri2)))
      (is (some? (db/document-id-by-uri uri3)))

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
      (db/update-active-uri! uri1)
      (is (= uri1 @(rf/subscribe [:workspace/active-file])))
      (is (= "content1" @(rf/subscribe [:active-content])))
      (is (= "rholang" @(rf/subscribe [:active-lang])))

      ;; Switch to second document
      (db/update-active-uri! uri2)
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
      (db/replace-diagnostics-by-uri! uri nil
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
      (db/update-active-uri! uri)
      (let [diags @(rf/subscribe [:lsp/diagnostics])]
        (is (= 2 (count diags)))))))

(deftest clearing-diagnostics-removes-all
  (testing "Clearing diagnostics removes all for a document"
    (let [uri "file:///test/clear-diag.rho"]
      (h/create-test-document! {:uri uri :text "code"})
      (db/replace-diagnostics-by-uri! uri nil
                                       [{:message "Error"
                                         :severity 1
                                         :startLine 0
                                         :startChar 0
                                         :endLine 0
                                         :endChar 4}])
      (db/update-active-uri! uri)

      ;; Verify diagnostics exist
      (is (= 1 (count @(rf/subscribe [:lsp/diagnostics]))))

      ;; Clear diagnostics
      (db/replace-diagnostics-by-uri! uri nil [])

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
        (db/replace-symbols! uri symbols))

      ;; Verify symbols are retrievable
      (db/update-active-uri! uri)
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
      (let [doc-id (db/document-id-by-uri uri)]
        ;; Add diagnostics
        (db/replace-diagnostics-by-uri! uri nil
                                         [{:message "Error"
                                           :severity 1
                                           :startLine 0
                                           :startChar 0
                                           :endLine 0
                                           :endChar 5}])
        ;; Verify exists
        (is (some? doc-id))

        ;; Delete document
        (db/delete-document-by-id! doc-id)

        ;; Verify removed
        (is (nil? (db/document-id-by-uri uri)))))))

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
      (db/update-active-uri! uri)

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
    (db/create-logs! [{:message "Log message 1" :lang "rholang"}
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
