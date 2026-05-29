(ns test.lib.db-diagnostics-symbols-test
  "Tests for the lib.db diagnostics, symbols, and log layers (split from db_test)."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [datascript.core :as d]
   [lib.db :as db]
   [test.lib.test-helpers :as h]))

(use-fixtures :each
  {:before #(d/reset-conn! db/conn (d/empty-db db/schema))})

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
        (is (zero? (:startLine first-diag)))
        (is (= 5 (:startChar first-diag)))
        (is (zero? (:endLine first-diag)))
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
      (is (not-any? #(= "old error" (:message %)) diags)))))

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
      (is (not-any? #(= "oldFunc" (:name %)) syms)))))

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

