(ns test.lib.db-test
  "Comprehensive tests for the lib.db DataScript database layer."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures async]]
   [clojure.core.async :refer [go <!]]
   [clojure.spec.alpha :as s]
   [clojure.test.check :as tc]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [datascript.core :as d]
   [lib.db :as db]
   [test.lib.test-helpers :as h]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(use-fixtures :each
  {:before #(d/reset-conn! db/conn (d/empty-db db/schema))})

;; =============================================================================
;; Document CRUD Tests
;; =============================================================================

(deftest create-documents!-with-valid-data
  (testing "Creating a single document"
    (db/create-documents! [{:uri "file:///test.rho"
                            :text "new x in { x!(42) }"
                            :language "rholang"
                            :version 1
                            :dirty false
                            :opened false}])
    (is (some? (db/document-id-by-uri "file:///test.rho"))
        "Document should be created"))

  (testing "Creating multiple documents"
    (db/create-documents! [{:uri "file:///a.rho"
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
    (is (some? (db/document-id-by-uri "file:///a.rho")))
    (is (some? (db/document-id-by-uri "file:///b.rho")))))

(deftest create-documents!-with-all-fields
  (testing "All document fields are persisted correctly"
    (db/create-documents! [{:uri "file:///complete.rho"
                            :text "complete text"
                            :language "rholang"
                            :version 5
                            :dirty true
                            :opened true}])
    (let [[text lang dirty] (db/doc-text-lang-dirty-by-uri "file:///complete.rho")
          [_ version] (db/document-id-version-by-uri "file:///complete.rho")
          opened (db/document-opened-by-uri? "file:///complete.rho")]
      (is (= "complete text" text))
      (is (= "rholang" lang))
      (is (= 5 version))
      (is (true? dirty))
      (is (true? opened)))))

(deftest document-id-by-uri-existing
  (testing "Returns entity ID for existing document"
    (h/create-test-document! {:uri "file:///exists.rho"})
    (let [id (db/document-id-by-uri "file:///exists.rho")]
      (is (integer? id))
      (is (pos? id)))))

(deftest document-id-by-uri-non-existent
  (testing "Returns nil for non-existent document"
    (is (nil? (db/document-id-by-uri "file:///does-not-exist.rho")))))

(deftest update-document-text-by-uri!-marks-dirty
  (testing "Updating text marks document as dirty"
    (h/create-test-document! {:uri "file:///test.rho"
                              :text "original"
                              :dirty false})
    (db/update-document-text-by-uri! "file:///test.rho" "updated")
    (let [[text _ dirty] (db/doc-text-lang-dirty-by-uri "file:///test.rho")]
      (is (= "updated" text))
      (is (true? dirty)))))

(deftest update-document-text-by-id!-marks-dirty
  (testing "Updating text by ID marks document as dirty"
    (let [id (h/create-test-document! {:uri "file:///test.rho"
                                       :text "original"
                                       :dirty false})]
      (db/update-document-text-by-id! id "updated by id")
      (let [[text _ dirty] (db/doc-text-lang-dirty-by-uri "file:///test.rho")]
        (is (= "updated by id" text))
        (is (true? dirty))))))

(deftest delete-document-by-id!-removes-document
  (testing "Deleting document removes it from database"
    (let [id (h/create-test-document! {:uri "file:///to-delete.rho"})]
      (is (some? (db/document-id-by-uri "file:///to-delete.rho")))
      (db/delete-document-by-id! id)
      (is (nil? (db/document-id-by-uri "file:///to-delete.rho"))))))

(deftest delete-document-by-id!-removes-related-diagnostics
  (testing "Deleting document removes related diagnostics"
    (let [id (h/create-test-document! {:uri "file:///with-diags.rho"})]
      (db/replace-diagnostics-by-uri! "file:///with-diags.rho" nil
                                      [{:uri "file:///with-diags.rho"
                                        :message "error"
                                        :severity 1
                                        :startLine 0
                                        :startChar 0
                                        :endLine 0
                                        :endChar 5}])
      (is (= 1 (count (db/diagnostics-by-uri "file:///with-diags.rho"))))
      (db/delete-document-by-id! id)
      ;; Note: DataScript cascades deletes due to :db/valueType :db.type/ref
      (is (empty? (db/diagnostics-by-uri "file:///with-diags.rho"))))))

;; =============================================================================
;; Active Document Tests
;; =============================================================================

(deftest update-active-uri!-sets-new-active
  (testing "Setting active URI updates the active document"
    (h/create-test-document! {:uri "file:///first.rho"})
    (h/create-test-document! {:uri "file:///second.rho"})
    (db/update-active-uri! "file:///first.rho")
    (is (= "file:///first.rho" (db/active-uri)))
    (db/update-active-uri! "file:///second.rho")
    (is (= "file:///second.rho" (db/active-uri)))))

(deftest active-uri-returns-current
  (testing "active-uri returns the currently active URI"
    (h/create-test-document! {:uri "file:///active.rho"})
    (db/update-active-uri! "file:///active.rho")
    (is (= "file:///active.rho" (db/active-uri)))))

(deftest active-uri-returns-nil-when-none
  (testing "active-uri returns nil when no active document"
    (is (nil? (db/active-uri)))))

(deftest active-text-returns-text
  (testing "active-text returns the text of active document"
    (h/create-test-document! {:uri "file:///active.rho"
                              :text "active content"})
    (db/update-active-uri! "file:///active.rho")
    (is (= "active content" (db/active-text)))))

(deftest active-text-returns-nil-when-none
  (testing "active-text returns nil when no active document"
    (is (nil? (db/active-text)))))

(deftest active-lang-returns-language
  (testing "active-lang returns the language of active document"
    (h/create-test-document! {:uri "file:///active.rho"
                              :language "rholang"})
    (db/update-active-uri! "file:///active.rho")
    (is (= "rholang" (db/active-lang)))))

(deftest active-version-returns-version
  (testing "active-version returns the version of active document"
    (h/create-test-document! {:uri "file:///active.rho"
                              :version 7})
    (db/update-active-uri! "file:///active.rho")
    (is (= 7 (db/active-version)))))

(deftest active-uri?-checks-if-uri-is-active
  (testing "active-uri? returns true for active URI"
    (h/create-test-document! {:uri "file:///active.rho"})
    (h/create-test-document! {:uri "file:///inactive.rho"})
    (db/update-active-uri! "file:///active.rho")
    (is (true? (db/active-uri? "file:///active.rho")))
    (is (false? (db/active-uri? "file:///inactive.rho")))))

(deftest reset-active-uri!-clears-active
  (testing "reset-active-uri! clears the active document"
    (h/create-test-document! {:uri "file:///active.rho"})
    (db/update-active-uri! "file:///active.rho")
    (is (= "file:///active.rho" (db/active-uri)))
    (db/reset-active-uri!)
    (is (nil? (db/active-uri)))))

;; =============================================================================
;; Open/Close State Tests
;; =============================================================================

(deftest document-opened-by-uri!-marks-opened
  (testing "Marking document as opened"
    (h/create-test-document! {:uri "file:///to-open.rho"
                              :opened false})
    (is (false? (db/document-opened-by-uri? "file:///to-open.rho")))
    (db/document-opened-by-uri! "file:///to-open.rho")
    (is (true? (db/document-opened-by-uri? "file:///to-open.rho")))))

(deftest document-closed-by-uri!-marks-closed
  (testing "Marking document as closed"
    (h/create-test-document! {:uri "file:///to-close.rho"
                              :opened true})
    (is (true? (db/document-opened-by-uri? "file:///to-close.rho")))
    (db/document-closed-by-uri! "file:///to-close.rho")
    (is (false? (db/document-opened-by-uri? "file:///to-close.rho")))))

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
    (let [opened-rho (db/opened-uris-by-lang "rholang")
          opened-txt (db/opened-uris-by-lang "text")]
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
    (db/close-all-opened-by-lang! "rholang")
    (is (false? (db/document-opened-by-uri? "file:///a.rho")))
    (is (false? (db/document-opened-by-uri? "file:///b.rho")))
    (is (true? (db/document-opened-by-uri? "file:///c.txt")))))

;; =============================================================================
;; Version Management Tests
;; =============================================================================

(deftest inc-document-version-by-uri!-increments
  (testing "Incrementing version by URI"
    (h/create-test-document! {:uri "file:///versioned.rho"
                              :version 5})
    (let [new-version (db/inc-document-version-by-uri! "file:///versioned.rho")]
      (is (= 6 new-version))
      (is (= 6 (second (db/document-id-version-by-uri "file:///versioned.rho")))))))

(deftest inc-document-version-by-id!-increments
  (testing "Incrementing version by ID"
    (let [id (h/create-test-document! {:uri "file:///versioned.rho"
                                       :version 10})]
      (let [new-version (db/inc-document-version-by-id! id)]
        (is (= 11 new-version))
        (is (= 11 (db/document-version-by-id id)))))))

;; =============================================================================
;; Document Update Tests
;; =============================================================================

(deftest update-document-dirty-by-id!-updates-dirty-flag
  (testing "Updating dirty flag by ID"
    (let [id (h/create-test-document! {:uri "file:///dirty-test.rho"
                                       :dirty false})]
      (db/update-document-dirty-by-id! id true)
      (let [[_ _ dirty] (db/doc-text-lang-dirty-by-uri "file:///dirty-test.rho")]
        (is (true? dirty)))
      (db/update-document-dirty-by-id! id false)
      (let [[_ _ dirty] (db/doc-text-lang-dirty-by-uri "file:///dirty-test.rho")]
        (is (false? dirty))))))

(deftest update-document-uri-by-id!-renames-document
  (testing "Renaming document by ID"
    (let [id (h/create-test-document! {:uri "file:///old-name.rho"})]
      (db/update-document-uri-by-id! id "file:///new-name.rho")
      (is (nil? (db/document-id-by-uri "file:///old-name.rho")))
      (is (= id (db/document-id-by-uri "file:///new-name.rho"))))))

(deftest update-document-uri-language-by-id!-updates-both
  (testing "Updating both URI and language"
    (let [id (h/create-test-document! {:uri "file:///old.txt"
                                       :language "text"})]
      (db/update-document-uri-language-by-id! id "file:///new.rho" "rholang")
      (let [[_ lang] (db/doc-text-lang-by-uri "file:///new.rho")]
        (is (= "rholang" lang))
        (is (nil? (db/document-id-by-uri "file:///old.txt")))))))

(deftest update-document-text-language-by-id!-updates-selectively
  (testing "Updating text only when provided"
    (let [id (h/create-test-document! {:uri "file:///test.rho"
                                       :text "original"
                                       :language "rholang"
                                       :dirty false})]
      (db/update-document-text-language-by-id! id "new text" nil)
      (let [[text lang dirty] (db/doc-text-lang-dirty-by-uri "file:///test.rho")]
        (is (= "new text" text))
        (is (= "rholang" lang))
        (is (true? dirty)))))

  (testing "Updating language only when provided"
    (let [id (h/create-test-document! {:uri "file:///test2.rho"
                                       :text "original"
                                       :language "text"})]
      (db/update-document-text-language-by-id! id nil "rholang")
      (let [[text lang] (db/doc-text-lang-by-uri "file:///test2.rho")]
        (is (= "original" text))
        (is (= "rholang" lang))))))

;; =============================================================================
;; Diagnostic Tests
;; =============================================================================

(deftest flatten-diags-transforms-lsp-format
  (testing "Flattening LSP diagnostics"
    (let [lsp-diags [{:message "Error 1"
                      :severity 1
                      :range {:start {:line 0 :character 5}
                              :end {:line 0 :character 10}}}
                     {:message "Warning"
                      :severity 2
                      :range {:start {:line 1 :character 0}
                              :end {:line 1 :character 3}}}]
          flattened (db/flatten-diags lsp-diags "file:///test.rho" 3)]
      (is (= 2 (count flattened)))
      (let [first-diag (first flattened)]
        (is (= "file:///test.rho" (:uri first-diag)))
        (is (= "Error 1" (:message first-diag)))
        (is (= 1 (:severity first-diag)))
        (is (= 0 (:startLine first-diag)))
        (is (= 5 (:startChar first-diag)))
        (is (= 0 (:endLine first-diag)))
        (is (= 10 (:endChar first-diag)))
        (is (= 3 (:version first-diag)))))))

(deftest flatten-diags-without-version
  (testing "Flattening diagnostics without version"
    (let [lsp-diags [{:message "Error"
                      :severity 1
                      :range {:start {:line 0 :character 0}
                              :end {:line 0 :character 5}}}]
          flattened (db/flatten-diags lsp-diags "file:///test.rho" nil)]
      (is (= 1 (count flattened)))
      (is (not (contains? (first flattened) :version))))))

(deftest replace-diagnostics-by-uri!-replaces-all
  (testing "Replacing diagnostics clears old and adds new"
    (h/create-test-document! {:uri "file:///test.rho"})
    (db/replace-diagnostics-by-uri! "file:///test.rho" nil
                                    [{:message "old error"
                                      :severity 1
                                      :startLine 0
                                      :startChar 0
                                      :endLine 0
                                      :endChar 5}])
    (is (= 1 (count (db/diagnostics-by-uri "file:///test.rho"))))
    (db/replace-diagnostics-by-uri! "file:///test.rho" nil
                                    [{:message "new error 1"
                                      :severity 1
                                      :startLine 0
                                      :startChar 0
                                      :endLine 0
                                      :endChar 3}
                                     {:message "new error 2"
                                      :severity 2
                                      :startLine 1
                                      :startChar 0
                                      :endLine 1
                                      :endChar 3}])
    (let [diags (db/diagnostics-by-uri "file:///test.rho")]
      (is (= 2 (count diags)))
      (is (some #(= "new error 1" (:message %)) diags))
      (is (some #(= "new error 2" (:message %)) diags))
      (is (not (some #(= "old error" (:message %)) diags))))))

(deftest replace-diagnostics-by-uri!-with-empty-clears-all
  (testing "Replacing with empty list clears all diagnostics"
    (h/create-test-document! {:uri "file:///test.rho"})
    (db/replace-diagnostics-by-uri! "file:///test.rho" nil
                                    [{:message "error"
                                      :severity 1
                                      :startLine 0
                                      :startChar 0
                                      :endLine 0
                                      :endChar 5}])
    (is (= 1 (count (db/diagnostics-by-uri "file:///test.rho"))))
    (db/replace-diagnostics-by-uri! "file:///test.rho" nil [])
    (is (empty? (db/diagnostics-by-uri "file:///test.rho")))))

(deftest diagnostics-by-uri-filters-by-uri
  (testing "diagnostics-by-uri returns only diagnostics for specified URI"
    (h/create-test-document! {:uri "file:///a.rho"})
    (h/create-test-document! {:uri "file:///b.rho"})
    (db/replace-diagnostics-by-uri! "file:///a.rho" nil
                                    [{:message "error in a"
                                      :severity 1
                                      :startLine 0
                                      :startChar 0
                                      :endLine 0
                                      :endChar 5}])
    (db/replace-diagnostics-by-uri! "file:///b.rho" nil
                                    [{:message "error in b"
                                      :severity 1
                                      :startLine 0
                                      :startChar 0
                                      :endLine 0
                                      :endChar 5}])
    (let [diags-a (db/diagnostics-by-uri "file:///a.rho")
          diags-b (db/diagnostics-by-uri "file:///b.rho")]
      (is (= 1 (count diags-a)))
      (is (= "error in a" (:message (first diags-a))))
      (is (= 1 (count diags-b)))
      (is (= "error in b" (:message (first diags-b)))))))

(deftest diagnostics-returns-all
  (testing "diagnostics returns all diagnostics across documents"
    (h/create-test-document! {:uri "file:///a.rho"})
    (h/create-test-document! {:uri "file:///b.rho"})
    (db/replace-diagnostics-by-uri! "file:///a.rho" nil
                                    [{:message "error 1" :severity 1
                                      :startLine 0 :startChar 0
                                      :endLine 0 :endChar 5}])
    (db/replace-diagnostics-by-uri! "file:///b.rho" nil
                                    [{:message "error 2" :severity 1
                                      :startLine 0 :startChar 0
                                      :endLine 0 :endChar 5}])
    (let [all-diags (db/diagnostics)]
      (is (= 2 (count all-diags)))
      (is (some #(= "error 1" (:message %)) all-diags))
      (is (some #(= "error 2" (:message %)) all-diags)))))

;; =============================================================================
;; Symbol Tests
;; =============================================================================

(deftest flatten-symbols-handles-flat-list
  (testing "Flattening symbols without children"
    (let [symbols [{:name "func1"
                    :kind 12
                    :range {:start {:line 0 :character 0}
                            :end {:line 5 :character 0}}
                    :selectionRange {:start {:line 0 :character 4}
                                     :end {:line 0 :character 9}}}
                   {:name "func2"
                    :kind 12
                    :range {:start {:line 6 :character 0}
                            :end {:line 10 :character 0}}
                    :selectionRange {:start {:line 6 :character 4}
                                     :end {:line 6 :character 9}}}]
          flattened (db/flatten-symbols symbols nil "file:///test.rho")]
      (is (= 2 (count flattened)))
      (is (= "func1" (:symbol/name (first flattened))))
      (is (= "func2" (:symbol/name (second flattened))))
      (is (nil? (:symbol/parent (first flattened))))
      (is (nil? (:symbol/parent (second flattened)))))))

(deftest flatten-symbols-handles-nested-hierarchy
  (testing "Flattening nested symbols"
    (let [symbols [{:name "class"
                    :kind 5
                    :range {:start {:line 0 :character 0}
                            :end {:line 20 :character 0}}
                    :selectionRange {:start {:line 0 :character 6}
                                     :end {:line 0 :character 11}}
                    :children [{:name "method1"
                                :kind 6
                                :range {:start {:line 2 :character 2}
                                        :end {:line 5 :character 2}}
                                :selectionRange {:start {:line 2 :character 6}
                                                 :end {:line 2 :character 13}}}
                               {:name "method2"
                                :kind 6
                                :range {:start {:line 6 :character 2}
                                        :end {:line 10 :character 2}}
                                :selectionRange {:start {:line 6 :character 6}
                                                 :end {:line 6 :character 13}}
                                :children [{:name "innerFunc"
                                            :kind 12
                                            :range {:start {:line 7 :character 4}
                                                    :end {:line 9 :character 4}}
                                            :selectionRange {:start {:line 7 :character 8}
                                                             :end {:line 7 :character 17}}}]}]}]
          flattened (db/flatten-symbols symbols nil "file:///test.rho")]
      (is (= 4 (count flattened)))
      (let [class-sym (first (filter #(= "class" (:symbol/name %)) flattened))
            method1 (first (filter #(= "method1" (:symbol/name %)) flattened))
            method2 (first (filter #(= "method2" (:symbol/name %)) flattened))
            inner (first (filter #(= "innerFunc" (:symbol/name %)) flattened))]
        (is (nil? (:symbol/parent class-sym)))
        (is (= (:db/id class-sym) (:symbol/parent method1)))
        (is (= (:db/id class-sym) (:symbol/parent method2)))
        (is (= (:db/id method2) (:symbol/parent inner)))))))

(deftest flatten-symbols-assigns-parent-ids
  (testing "Parent IDs are correctly assigned"
    (let [symbols [{:name "parent"
                    :kind 1
                    :range {:start {:line 0 :character 0}
                            :end {:line 10 :character 0}}
                    :selectionRange {:start {:line 0 :character 0}
                                     :end {:line 0 :character 6}}
                    :children [{:name "child"
                                :kind 2
                                :range {:start {:line 1 :character 2}
                                        :end {:line 5 :character 2}}
                                :selectionRange {:start {:line 1 :character 2}
                                                 :end {:line 1 :character 7}}}]}]
          flattened (db/flatten-symbols symbols nil "file:///test.rho")
          parent-sym (first (filter #(= "parent" (:symbol/name %)) flattened))
          child-sym (first (filter #(= "child" (:symbol/name %)) flattened))]
      (is (some? parent-sym))
      (is (some? child-sym))
      (is (nil? (:symbol/parent parent-sym)))
      (is (= (:db/id parent-sym) (:symbol/parent child-sym))))))

(deftest flatten-symbols-empty-input
  (testing "Empty symbol list returns empty"
    (is (empty? (db/flatten-symbols [] nil "file:///test.rho")))))

(deftest replace-symbols!-replaces-all
  (testing "Replacing symbols clears old and adds new"
    (h/create-test-document! {:uri "file:///test.rho"})
    (let [old-symbols (db/flatten-symbols
                       [{:name "oldFunc"
                         :kind 12
                         :range {:start {:line 0 :character 0}
                                 :end {:line 5 :character 0}}
                         :selectionRange {:start {:line 0 :character 4}
                                          :end {:line 0 :character 11}}}]
                       nil "file:///test.rho")]
      (db/replace-symbols! "file:///test.rho" old-symbols))
    (is (= 1 (count (db/symbols-by-uri "file:///test.rho"))))
    (let [new-symbols (db/flatten-symbols
                       [{:name "newFunc1"
                         :kind 12
                         :range {:start {:line 0 :character 0}
                                 :end {:line 5 :character 0}}
                         :selectionRange {:start {:line 0 :character 4}
                                          :end {:line 0 :character 12}}}
                        {:name "newFunc2"
                         :kind 12
                         :range {:start {:line 6 :character 0}
                                 :end {:line 10 :character 0}}
                         :selectionRange {:start {:line 6 :character 4}
                                          :end {:line 6 :character 12}}}]
                       nil "file:///test.rho")]
      (db/replace-symbols! "file:///test.rho" new-symbols))
    (let [syms (db/symbols-by-uri "file:///test.rho")]
      (is (= 2 (count syms)))
      (is (some #(= "newFunc1" (:name %)) syms))
      (is (some #(= "newFunc2" (:name %)) syms))
      (is (not (some #(= "oldFunc" (:name %)) syms))))))

(deftest symbols-by-uri-filters-by-uri
  (testing "symbols-by-uri returns only symbols for specified URI"
    (h/create-test-document! {:uri "file:///a.rho"})
    (h/create-test-document! {:uri "file:///b.rho"})
    (db/replace-symbols! "file:///a.rho"
                         (db/flatten-symbols
                          [{:name "funcA"
                            :kind 12
                            :range {:start {:line 0 :character 0}
                                    :end {:line 5 :character 0}}
                            :selectionRange {:start {:line 0 :character 4}
                                             :end {:line 0 :character 9}}}]
                          nil "file:///a.rho"))
    (db/replace-symbols! "file:///b.rho"
                         (db/flatten-symbols
                          [{:name "funcB"
                            :kind 12
                            :range {:start {:line 0 :character 0}
                                    :end {:line 5 :character 0}}
                            :selectionRange {:start {:line 0 :character 4}
                                             :end {:line 0 :character 9}}}]
                          nil "file:///b.rho"))
    (let [syms-a (db/symbols-by-uri "file:///a.rho")
          syms-b (db/symbols-by-uri "file:///b.rho")]
      (is (= 1 (count syms-a)))
      (is (= "funcA" (:name (first syms-a))))
      (is (= 1 (count syms-b)))
      (is (= "funcB" (:name (first syms-b)))))))

;; =============================================================================
;; Log Tests
;; =============================================================================

(deftest create-logs!-adds-logs
  (testing "Creating logs"
    (db/create-logs! [{:message "Log message 1" :lang "rholang"}
                      {:message "Log message 2" :lang "text"}])
    (let [logs (db/logs)]
      (is (= 2 (count logs)))
      (is (some #(= "Log message 1" (:message %)) logs))
      (is (some #(= "Log message 2" (:message %)) logs)))))

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
                                       :language "rholang"})]
      (let [[returned-id text lang] (db/doc-id-text-lang-by-uri "file:///test.rho")]
        (is (= id returned-id))
        (is (= "test text" text))
        (is (= "rholang" lang))))))

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
    (let [id (h/create-test-document! {:uri "file:///state-test.rho"
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
