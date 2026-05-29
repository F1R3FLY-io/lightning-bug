(ns test.infrastructure.datascript-adapter-test
  "Tests for the DataScript-backed repository adapters (infrastructure.datascript-adapter).

   These adapters became live in Phase 2 (wired into app.cofx/app.fx via app.system).
   The tests assert each protocol method delegates correctly to lib.db against an
   in-memory conn, and lock in the EXP-007 coalesced single-query hot reads."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [datascript.core :as d]
   [domain.protocols :as p]
   [infrastructure.datascript-adapter :as ds]
   [lib.db :as db]))

(use-fixtures :each
  {:before #(d/reset-conn! db/conn (d/empty-db db/schema))})

(def doc-repo (ds/make-document-repository))
(def diag-repo (ds/make-diagnostics-repository))
(def sym-repo (ds/make-symbols-repository))
(def log-repo (ds/make-log-repository))

;; =============================================================================
;; Document Repository
;; =============================================================================

(deftest create-document!-applies-defaults-and-returns-id
  (testing "create-document! merges {:version 0 :dirty false :opened false} and returns the entity id"
    (let [id (p/create-document! doc-repo {:uri "file:///a.rho" :text "x" :language "rholang"})]
      (is (some? id))
      (is (= id (db/document-id-by-uri "file:///a.rho")))
      (is (zero? (p/get-document-version doc-repo "file:///a.rho")))
      (is (false? (p/document-opened? doc-repo "file:///a.rho"))))))

(deftest get-document-returns-full-map
  (testing "get-document returns the full document map incl. :dirty/:opened"
    (p/create-document! doc-repo {:uri "file:///a.rho" :text "x" :language "rholang"})
    (let [doc (p/get-document doc-repo "file:///a.rho")]
      (is (= "file:///a.rho" (:uri doc)))
      (is (= "x" (:text doc)))
      (is (= "rholang" (:language doc)))
      (is (zero? (:version doc)))
      (is (false? (:dirty doc)))
      (is (false? (:opened doc))))
    (is (nil? (p/get-document doc-repo "file:///missing.rho")))))

(deftest get-document-summary-is-coalesced-lightweight
  (testing "EXP-007: get-document-summary returns {:uri :text :language :version} or nil"
    (p/create-document! doc-repo {:uri "file:///a.rho" :text "x" :language "rholang" :version 7})
    (is (= {:uri "file:///a.rho" :text "x" :language "rholang" :version 7}
           (p/get-document-summary doc-repo "file:///a.rho")))
    (is (nil? (p/get-document-summary doc-repo "file:///missing.rho")))))

(deftest get-active-document-is-coalesced
  (testing "EXP-007: get-active-document returns the coalesced active summary or nil"
    (is (nil? (p/get-active-document doc-repo)) "nil when no active document")
    (p/create-document! doc-repo {:uri "file:///a.rho" :text "x" :language "rholang" :version 2})
    (p/set-active-document! doc-repo "file:///a.rho")
    (is (= {:uri "file:///a.rho" :text "x" :language "rholang" :version 2}
           (p/get-active-document doc-repo)))))

(deftest scalar-reads-delegate
  (testing "text/language/version/active-uri reads delegate to lib.db"
    (p/create-document! doc-repo {:uri "file:///a.rho" :text "hello" :language "rholang"})
    (is (= "hello" (p/get-document-text doc-repo "file:///a.rho")))
    (is (= "rholang" (p/get-document-language doc-repo "file:///a.rho")))
    (is (zero? (p/get-document-version doc-repo "file:///a.rho")))
    (p/set-active-document! doc-repo "file:///a.rho")
    (is (= "file:///a.rho" (p/get-active-uri doc-repo)))))

(deftest list-documents-and-opened-by-language
  (testing "list-documents + list-opened-documents-by-language"
    (p/create-document! doc-repo {:uri "file:///a.rho" :text "a" :language "rholang"})
    (p/create-document! doc-repo {:uri "file:///b.rho" :text "b" :language "rholang"})
    (is (= 2 (count (p/list-documents doc-repo))))
    (p/mark-document-opened! doc-repo "file:///a.rho")
    (is (= ["file:///a.rho"] (vec (p/list-opened-documents-by-language doc-repo "rholang"))))))

(deftest mutations-delegate
  (testing "update-text/language, increment-version, mark-opened/closed, rename, delete"
    (p/create-document! doc-repo {:uri "file:///a.rho" :text "a" :language "rholang"})
    (p/update-document-text! doc-repo "file:///a.rho" "a2")
    (is (= "a2" (p/get-document-text doc-repo "file:///a.rho")))
    (p/update-document-language! doc-repo "file:///a.rho" "text")
    (is (= "text" (p/get-document-language doc-repo "file:///a.rho")))
    (is (= 1 (p/increment-version! doc-repo "file:///a.rho")))
    (is (= 1 (p/get-document-version doc-repo "file:///a.rho")))
    (p/mark-document-opened! doc-repo "file:///a.rho")
    (is (true? (p/document-opened? doc-repo "file:///a.rho")))
    (p/mark-document-closed! doc-repo "file:///a.rho")
    (is (false? (p/document-opened? doc-repo "file:///a.rho")))
    (p/rename-document! doc-repo "file:///a.rho" "file:///renamed.rho")
    (is (some? (db/document-id-by-uri "file:///renamed.rho")))
    (is (nil? (db/document-id-by-uri "file:///a.rho")))
    (p/delete-document! doc-repo "file:///renamed.rho")
    (is (nil? (db/document-id-by-uri "file:///renamed.rho")))))

;; =============================================================================
;; Diagnostics Repository
;; =============================================================================

(deftest diagnostics-replace-and-read
  (testing "replace-diagnostics! flattens LSP diagnostics; get-diagnostics(-by-uri) read them back"
    ;; version nil matches the document so the version-filtered query returns them
    ;; (mirrors lib.db's own diagnostics tests; the adapter forwards version verbatim).
    (p/create-document! doc-repo {:uri "file:///a.rho" :text "x" :language "rholang"})
    (p/replace-diagnostics! diag-repo "file:///a.rho" nil
                            [{:message "boom" :severity 1
                              :range {:start {:line 0 :character 2}
                                      :end {:line 0 :character 5}}}])
    (let [diags (p/get-diagnostics-by-uri diag-repo "file:///a.rho")]
      (is (= 1 (count diags)))
      (is (= "boom" (:message (first diags))))
      (is (= 2 (:startChar (first diags)))))
    (is (= 1 (count (p/get-diagnostics diag-repo))))
    (testing "empty diagnostics clears"
      (p/replace-diagnostics! diag-repo "file:///a.rho" nil [])
      (is (empty? (p/get-diagnostics-by-uri diag-repo "file:///a.rho"))))))

;; =============================================================================
;; Symbols Repository
;; =============================================================================

(deftest symbols-replace-and-read
  (testing "replace-symbols! flattens nested symbols; get-symbols(-by-uri) read them back"
    (p/create-document! doc-repo {:uri "file:///a.rho" :text "x" :language "rholang"})
    (p/replace-symbols! sym-repo "file:///a.rho"
                        [{:name "outer" :kind 5
                          :range {:start {:line 0 :character 0} :end {:line 9 :character 0}}
                          :selectionRange {:start {:line 0 :character 6} :end {:line 0 :character 11}}
                          :children [{:name "inner" :kind 6
                                      :range {:start {:line 1 :character 2} :end {:line 3 :character 0}}
                                      :selectionRange {:start {:line 1 :character 6} :end {:line 1 :character 11}}}]}])
    (let [syms (p/get-symbols-by-uri sym-repo "file:///a.rho")]
      (is (<= 2 (count syms)) "nested symbols are flattened")
      (is (some #(= "outer" (:name %)) syms))
      (is (some #(= "inner" (:name %)) syms)))
    (is (<= 2 (count (p/get-symbols sym-repo))))))

;; =============================================================================
;; Log Repository
;; =============================================================================

(deftest log-add-and-read
  (testing "add-log! then get-logs"
    (p/add-log! log-repo {:message "hello" :lang "rholang"})
    (let [logs (p/get-logs log-repo)]
      (is (= 1 (count logs)))
      (is (= "hello" (:message (first logs)))))))
