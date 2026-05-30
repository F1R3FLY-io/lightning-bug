(ns test.lib.db-test
  "Comprehensive tests for the lib.db DataScript database layer."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [lib.db :as db]
   [lib.workspace :as ws]
   [test.lib.test-helpers :as h]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(use-fixtures :each
  {:before #(ws/reset-workspace! @ws/default-workspace)})

;; =============================================================================
;; Document CRUD Tests
;; =============================================================================

(deftest create-documents!-with-valid-data
  (testing "Creating a single document"
    (db/create-documents! (ws/default-conn) [{:uri "file:///test.rho"
                            :text "new x in { x!(42) }"
                            :language "rholang"
                            :version 1
                            :dirty false
                            :opened false}])
    (is (some? (db/document-id-by-uri (ws/default-conn) "file:///test.rho"))
        "Document should be created"))

  (testing "Creating multiple documents"
    (db/create-documents! (ws/default-conn) [{:uri "file:///a.rho"
                            :text "a"
                            :language "rholang"
                            :version 0
                            :dirty false
                            :opened false}
                           {:uri "file:///b.rho"
                            :text "b"
                            :language "rholang"
                            :version 0
                            :dirty false
                            :opened false}])
    (is (some? (db/document-id-by-uri (ws/default-conn) "file:///a.rho")))
    (is (some? (db/document-id-by-uri (ws/default-conn) "file:///b.rho")))))

(deftest create-documents!-with-all-fields
  (testing "All document fields are persisted correctly"
    (db/create-documents! (ws/default-conn) [{:uri "file:///complete.rho"
                            :text "complete text"
                            :language "rholang"
                            :version 5
                            :dirty true
                            :opened true}])
    (let [[text lang dirty] (db/doc-text-lang-dirty-by-uri (ws/default-conn) "file:///complete.rho")
          [_ version] (db/document-id-version-by-uri (ws/default-conn) "file:///complete.rho")
          opened (db/document-opened-by-uri? (ws/default-conn) "file:///complete.rho")]
      (is (= "complete text" text))
      (is (= "rholang" lang))
      (is (= 5 version))
      (is (true? dirty))
      (is (true? opened)))))

(deftest document-id-by-uri-existing
  (testing "Returns entity ID for existing document"
    (h/create-test-document! {:uri "file:///exists.rho"})
    (let [id (db/document-id-by-uri (ws/default-conn) "file:///exists.rho")]
      (is (integer? id))
      (is (pos? id)))))

(deftest document-id-by-uri-non-existent
  (testing "Returns nil for non-existent document"
    (is (nil? (db/document-id-by-uri (ws/default-conn) "file:///does-not-exist.rho")))))

(deftest update-document-text-by-uri!-marks-dirty
  (testing "Updating text marks document as dirty"
    (h/create-test-document! {:uri "file:///test.rho"
                              :text "original"
                              :dirty false})
    (db/update-document-text-by-uri! (ws/default-conn) "file:///test.rho" "updated")
    (let [[text _ dirty] (db/doc-text-lang-dirty-by-uri (ws/default-conn) "file:///test.rho")]
      (is (= "updated" text))
      (is (true? dirty)))))

(deftest update-document-text-by-id!-marks-dirty
  (testing "Updating text by ID marks document as dirty"
    (let [id (h/create-test-document! {:uri "file:///test.rho"
                                       :text "original"
                                       :dirty false})]
      (db/update-document-text-by-id! (ws/default-conn) id "updated by id")
      (let [[text _ dirty] (db/doc-text-lang-dirty-by-uri (ws/default-conn) "file:///test.rho")]
        (is (= "updated by id" text))
        (is (true? dirty))))))

(deftest delete-document-by-id!-removes-document
  (testing "Deleting document removes it from database"
    (let [id (h/create-test-document! {:uri "file:///to-delete.rho"})]
      (is (some? (db/document-id-by-uri (ws/default-conn) "file:///to-delete.rho")))
      (db/delete-document-by-id! (ws/default-conn) id)
      (is (nil? (db/document-id-by-uri (ws/default-conn) "file:///to-delete.rho"))))))

(deftest delete-document-by-id!-removes-related-diagnostics
  (testing "Deleting document removes related diagnostics"
    (let [id (h/create-test-document! {:uri "file:///with-diags.rho"})]
      (db/replace-diagnostics-by-uri! (ws/default-conn) "file:///with-diags.rho" nil
                                      [{:uri "file:///with-diags.rho"
                                        :message "error"
                                        :severity 1
                                        :startLine 0
                                        :startChar 0
                                        :endLine 0
                                        :endChar 5}])
      (is (= 1 (count (db/diagnostics-by-uri (ws/default-conn) "file:///with-diags.rho"))))
      (db/delete-document-by-id! (ws/default-conn) id)
      ;; Note: DataScript cascades deletes due to :db/valueType :db.type/ref
      (is (empty? (db/diagnostics-by-uri (ws/default-conn) "file:///with-diags.rho"))))))

;; =============================================================================
;; Active Document Tests
;; =============================================================================

(deftest update-active-uri!-sets-new-active
  (testing "Setting active URI updates the active document"
    (h/create-test-document! {:uri "file:///first.rho"})
    (h/create-test-document! {:uri "file:///second.rho"})
    (db/update-active-uri! (ws/default-conn) "file:///first.rho")
    (is (= "file:///first.rho" (db/active-uri (ws/default-conn))))
    (db/update-active-uri! (ws/default-conn) "file:///second.rho")
    (is (= "file:///second.rho" (db/active-uri (ws/default-conn))))))

(deftest active-uri-returns-current
  (testing "active-uri returns the currently active URI"
    (h/create-test-document! {:uri "file:///active.rho"})
    (db/update-active-uri! (ws/default-conn) "file:///active.rho")
    (is (= "file:///active.rho" (db/active-uri (ws/default-conn))))))

(deftest active-uri-returns-nil-when-none
  (testing "active-uri returns nil when no active document"
    (is (nil? (db/active-uri (ws/default-conn))))))

(deftest active-text-returns-text
  (testing "active-text returns the text of active document"
    (h/create-test-document! {:uri "file:///active.rho"
                              :text "active content"})
    (db/update-active-uri! (ws/default-conn) "file:///active.rho")
    (is (= "active content" (db/active-text (ws/default-conn))))))

(deftest active-text-returns-nil-when-none
  (testing "active-text returns nil when no active document"
    (is (nil? (db/active-text (ws/default-conn))))))

(deftest active-lang-returns-language
  (testing "active-lang returns the language of active document"
    (h/create-test-document! {:uri "file:///active.rho"
                              :language "rholang"})
    (db/update-active-uri! (ws/default-conn) "file:///active.rho")
    (is (= "rholang" (db/active-lang (ws/default-conn))))))

(deftest active-version-returns-version
  (testing "active-version returns the version of active document"
    (h/create-test-document! {:uri "file:///active.rho"
                              :version 7})
    (db/update-active-uri! (ws/default-conn) "file:///active.rho")
    (is (= 7 (db/active-version (ws/default-conn))))))

(deftest active-uri?-checks-if-uri-is-active
  (testing "active-uri? returns true for active URI"
    (h/create-test-document! {:uri "file:///active.rho"})
    (h/create-test-document! {:uri "file:///inactive.rho"})
    (db/update-active-uri! (ws/default-conn) "file:///active.rho")
    (is (true? (db/active-uri? (ws/default-conn) "file:///active.rho")))
    (is (false? (db/active-uri? (ws/default-conn) "file:///inactive.rho")))))

(deftest reset-active-uri!-clears-active
  (testing "reset-active-uri! clears the active document"
    (h/create-test-document! {:uri "file:///active.rho"})
    (db/update-active-uri! (ws/default-conn) "file:///active.rho")
    (is (= "file:///active.rho" (db/active-uri (ws/default-conn))))
    (db/reset-active-uri! (ws/default-conn))
    (is (nil? (db/active-uri (ws/default-conn))))))

;; =============================================================================
;; Open/Close State Tests
;; =============================================================================

(deftest document-opened-by-uri!-marks-opened
  (testing "Marking document as opened"
    (h/create-test-document! {:uri "file:///to-open.rho"
                              :opened false})
    (is (false? (db/document-opened-by-uri? (ws/default-conn) "file:///to-open.rho")))
    (db/document-opened-by-uri! (ws/default-conn) "file:///to-open.rho")
    (is (true? (db/document-opened-by-uri? (ws/default-conn) "file:///to-open.rho")))))

(deftest document-closed-by-uri!-marks-closed
  (testing "Marking document as closed"
    (h/create-test-document! {:uri "file:///to-close.rho"
                              :opened true})
    (is (true? (db/document-opened-by-uri? (ws/default-conn) "file:///to-close.rho")))
    (db/document-closed-by-uri! (ws/default-conn) "file:///to-close.rho")
    (is (false? (db/document-opened-by-uri? (ws/default-conn) "file:///to-close.rho")))))

(deftest opened-uris-by-lang-filters-correctly
  (testing "opened-uris-by-lang returns only opened URIs for specified language"
    (h/create-test-document! {:uri "file:///open-rho.rho"
                              :language "rholang"
                              :opened true})
    (h/create-test-document! {:uri "file:///closed-rho.rho"
                              :language "rholang"
                              :opened false})
    (h/create-test-document! {:uri "file:///open-txt.txt"
                              :language "text"
                              :opened true})
    (let [opened-rho (db/opened-uris-by-lang (ws/default-conn) "rholang")
          opened-txt (db/opened-uris-by-lang (ws/default-conn) "text")]
      (is (= 1 (count opened-rho)))
      (is (contains? (set opened-rho) "file:///open-rho.rho"))
      (is (= 1 (count opened-txt)))
      (is (contains? (set opened-txt) "file:///open-txt.txt")))))

(deftest close-all-opened-by-lang!-closes-all
  (testing "close-all-opened-by-lang! closes all documents for language"
    (h/create-test-document! {:uri "file:///a.rho"
                              :language "rholang"
                              :opened true})
    (h/create-test-document! {:uri "file:///b.rho"
                              :language "rholang"
                              :opened true})
    (h/create-test-document! {:uri "file:///c.txt"
                              :language "text"
                              :opened true})
    (db/close-all-opened-by-lang! (ws/default-conn) "rholang")
    (is (false? (db/document-opened-by-uri? (ws/default-conn) "file:///a.rho")))
    (is (false? (db/document-opened-by-uri? (ws/default-conn) "file:///b.rho")))
    (is (true? (db/document-opened-by-uri? (ws/default-conn) "file:///c.txt")))))

;; =============================================================================
;; Version Management Tests
;; =============================================================================

(deftest inc-document-version-by-uri!-increments
  (testing "Incrementing version by URI"
    (h/create-test-document! {:uri "file:///versioned.rho"
                              :version 5})
    (let [new-version (db/inc-document-version-by-uri! (ws/default-conn) "file:///versioned.rho")]
      (is (= 6 new-version))
      (is (= 6 (second (db/document-id-version-by-uri (ws/default-conn) "file:///versioned.rho")))))))

(deftest inc-document-version-by-id!-increments
  (testing "Incrementing version by ID"
    (let [id (h/create-test-document! {:uri "file:///versioned.rho"
                                       :version 10})]
      (let [new-version (db/inc-document-version-by-id! (ws/default-conn) id)]
        (is (= 11 new-version))
        (is (= 11 (db/document-version-by-id (ws/default-conn) id)))))))

;; =============================================================================
;; Document Update Tests
;; =============================================================================

(deftest update-document-dirty-by-id!-updates-dirty-flag
  (testing "Updating dirty flag by ID"
    (let [id (h/create-test-document! {:uri "file:///dirty-test.rho"
                                       :dirty false})]
      (db/update-document-dirty-by-id! (ws/default-conn) id true)
      (let [[_ _ dirty] (db/doc-text-lang-dirty-by-uri (ws/default-conn) "file:///dirty-test.rho")]
        (is (true? dirty)))
      (db/update-document-dirty-by-id! (ws/default-conn) id false)
      (let [[_ _ dirty] (db/doc-text-lang-dirty-by-uri (ws/default-conn) "file:///dirty-test.rho")]
        (is (false? dirty))))))

(deftest update-document-uri-by-id!-renames-document
  (testing "Renaming document by ID"
    (let [id (h/create-test-document! {:uri "file:///old-name.rho"})]
      (db/update-document-uri-by-id! (ws/default-conn) id "file:///new-name.rho")
      (is (nil? (db/document-id-by-uri (ws/default-conn) "file:///old-name.rho")))
      (is (= id (db/document-id-by-uri (ws/default-conn) "file:///new-name.rho"))))))

(deftest update-document-uri-language-by-id!-updates-both
  (testing "Updating both URI and language"
    (let [id (h/create-test-document! {:uri "file:///old.txt"
                                       :language "text"})]
      (db/update-document-uri-language-by-id! (ws/default-conn) id "file:///new.rho" "rholang")
      (let [[_ lang] (db/doc-text-lang-by-uri (ws/default-conn) "file:///new.rho")]
        (is (= "rholang" lang))
        (is (nil? (db/document-id-by-uri (ws/default-conn) "file:///old.txt")))))))

(deftest update-document-text-language-by-id!-updates-selectively
  (testing "Updating text only when provided"
    (let [id (h/create-test-document! {:uri "file:///test.rho"
                                       :text "original"
                                       :language "rholang"
                                       :dirty false})]
      (db/update-document-text-language-by-id! (ws/default-conn) id "new text" nil)
      (let [[text lang dirty] (db/doc-text-lang-dirty-by-uri (ws/default-conn) "file:///test.rho")]
        (is (= "new text" text))
        (is (= "rholang" lang))
        (is (true? dirty)))))

  (testing "Updating language only when provided"
    (let [id (h/create-test-document! {:uri "file:///test2.rho"
                                       :text "original"
                                       :language "text"})]
      (db/update-document-text-language-by-id! (ws/default-conn) id nil "rholang")
      (let [[text lang] (db/doc-text-lang-by-uri (ws/default-conn) "file:///test2.rho")]
        (is (= "original" text))
        (is (= "rholang" lang))))))

